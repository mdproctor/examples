package io.casehub.examples.helpdesk.yaml;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.runtime.engine.CaseHubRegistry;
import io.casehub.work.runtime.service.WorkItemService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@QuarkusTest
@TestProfile(OllamaLifecycleTest.OllamaProfile.class)
@EnabledIfEnvironmentVariable(named = "OLLAMA_HOST", matches = ".+")
class OllamaLifecycleTest {

  private static final Set<String> VALID_CATEGORIES = Set.of("HARDWARE", "SOFTWARE", "ACCESS");
  private static final Set<String> VALID_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

  @Inject CaseHubRegistry caseHubRegistry;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject WorkItemService workItemService;

  @Test
  void fullLifecycle_ollamaClassification() {
    CaseHub caseHub =
        caseHubRegistry
            .get("helpdesk-ticket")
            .orElseThrow(() -> new AssertionError("CaseHub not found"));

    UUID caseId =
        caseHub.startCase(
            Map.of(
                "subject", "Cannot access VPN from home office",
                "description", "VPN client shows 'authentication failed' after password reset",
                "customerRef", "cust-ollama-test",
                "status", "OPEN"));

    await()
        .atMost(60, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              Object category = caseHub.query(caseId, "category");
              assertNotNull(category, "Agent should classify the ticket");
              assertTrue(
                  VALID_CATEGORIES.contains(category.toString()),
                  "Category should be one of " + VALID_CATEGORIES + " but was: " + category);
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              Object priority = caseHub.query(caseId, "priority");
              assertNotNull(priority, "Agent should assign priority");
              assertTrue(
                  VALID_PRIORITIES.contains(priority.toString()),
                  "Priority should be one of " + VALID_PRIORITIES + " but was: " + priority);
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertEquals("TRIAGED", caseHub.query(caseId, "status")));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var workItems =
                  given()
                      .when()
                      .get("/api/work/items/list-all")
                      .then()
                      .statusCode(200)
                      .extract()
                      .jsonPath()
                      .getList(
                          "items.findAll { it.title.contains('Cannot access VPN') && it.status == 'PENDING' }",
                          Map.class);
              assertFalse(workItems.isEmpty(), "Work item should be created for resolution");
            });

    String workItemIdStr =
        given()
            .when()
            .get("/api/work/items/list-all")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString(
                "items.find { it.title.contains('Cannot access VPN') && it.status == 'PENDING' }.id");
    UUID workItemId = UUID.fromString(workItemIdStr);

    workItemService.completeFromSystem(
        workItemId,
        "access-specialist",
        "{\"resolution\":\"Reset VPN certificate and re-enrolled device\",\"assigneeId\":\"access-specialist\"}");

    await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertNotNull(instance);
              assertEquals(
                  CaseStatus.COMPLETED,
                  instance.getState(),
                  "Case should complete after resolution and notification");
            });
  }

  public static class OllamaProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "ollama";
    }
  }
}
