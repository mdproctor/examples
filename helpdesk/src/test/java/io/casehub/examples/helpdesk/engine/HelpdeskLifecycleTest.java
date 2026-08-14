package io.casehub.examples.helpdesk.engine;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.DemoTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class HelpdeskLifecycleTest {

    @Inject HelpDeskCaseHub caseHub;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject HelpdeskDispatcherRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar.classifier().loadClassifications(List.of(
                new KeywordClassifierDispatcher.ClassificationEntry("laptop", "HARDWARE", "HIGH"),
                new KeywordClassifierDispatcher.ClassificationEntry("password", "ACCESS", "LOW")));
    }

    @Test
    void fullLifecycle_classifyCreateWorkItemResolveNotify() {
        UUID caseId = caseHub.startCase(Map.of(
                "subject", "My laptop won't boot",
                "customerRef", "alice",
                "status", "OPEN"));

        // Wait for triage binding to fire and classify
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Object category = caseHub.query(caseId, "category");
            assertNotNull(category, "Ticket should be classified");
            assertEquals("HARDWARE", category);
        });

        // Wait for status to be TRIAGED
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Object status = caseHub.query(caseId, "status");
            assertEquals("TRIAGED", status);
        });

        // Wait for WorkItem to be created by humanTask binding
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var workItems = given()
                    .queryParam("candidateGroups", "hw-specialist")
                    .when().get("/workitems")
                    .then().statusCode(200)
                    .extract().jsonPath().getList("$");
            assertFalse(workItems.isEmpty(), "WorkItem should be created for hw-specialist");
        });

        // Specialist claims, starts, and completes the WorkItem
        String workItemId = given()
                .queryParam("candidateGroups", "hw-specialist")
                .when().get("/workitems")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].id");

        // Claim (PENDING → ASSIGNED)
        given().queryParam("claimant", "hw-specialist")
                .when().put("/workitems/" + workItemId + "/claim")
                .then().statusCode(200);

        // Start (ASSIGNED → IN_PROGRESS)
        given().when().put("/workitems/" + workItemId + "/start")
                .then().statusCode(200);

        // Complete with JSON resolution for outputMapping JQ evaluation
        String resolutionJson;
        try {
            resolutionJson = new ObjectMapper().writeValueAsString(Map.of(
                    "resolution", "BIOS reset resolved the issue",
                    "assigneeId", "hw-specialist"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        given().contentType("application/json")
                .body(Map.of("resolution", resolutionJson))
                .when().put("/workitems/" + workItemId + "/complete")
                .then().statusCode(200);

        // Wait for case to complete (notification fired, goal met)
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var instance = caseInstanceCache.get(caseId);
            assertNotNull(instance);
            assertEquals(CaseStatus.COMPLETED, instance.getState(),
                    "Case should complete after resolution and notification");
        });
    }
}
