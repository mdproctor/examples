package io.casehub.examples.helpdesk.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.DemoTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class ChatCaseCreationHandlerTest {

    @Inject HelpDeskCaseHub caseHub;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject HelpdeskDispatcherRegistrar registrar;

    @Test
    void startCaseCreatesInstance() {
        registrar.classifier().loadClassifications(List.of(
                new KeywordClassifierDispatcher.ClassificationEntry("laptop", "HARDWARE", "HIGH")));

        var caseId = caseHub.startCase(Map.of(
                "subject", "My laptop won't boot",
                "customerRef", "alice",
                "status", "OPEN"));

        assertNotNull(caseId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var instance = caseInstanceCache.get(caseId);
            assertNotNull(instance, "Case instance should exist in cache");
        });
    }
}
