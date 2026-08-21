package io.casehub.examples.helpdesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.examples.helpdesk.demo.HelpDeskScenarioActions;
import io.casehub.pages.push.PushMessage;
import io.casehub.pages.scenario.client.ScenarioExecutorClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class ScenarioExecutorIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    HelpDeskScenarioActions scenarioActions;

    @Test
    void executorClientDispatchesCreateTicketAction() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var client = ScenarioExecutorClient.create(
            "helpdesk", List.of(scenarioActions), sent::add);

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("executor-register")
            .contains("helpdesk")
            .contains("create-ticket");

        String stepsJson = JSON.writeValueAsString(List.of(
            Map.of("name", "create-ticket", "label", "Create support ticket",
                "commands", List.of(Map.of(
                    "action", "create-ticket",
                    "data", Map.of(
                        "subject", "Laptop won't boot",
                        "category", "HARDWARE",
                        "priority", "HIGH"))))
        ));

        client.onMessage(PushMessage.dispatchSequence(
            "s-integration", "helpdesk", stepsJson, 1000.0, false));

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            var stepResults = sent.stream()
                .filter(s -> s.contains("step-result")).toList();
            assertThat(stepResults).hasSize(1);
            assertThat(stepResults.getFirst())
                .contains("\"ok\":true")
                .contains("\"stepName\":\"create-ticket\"");
        });
    }

    @Test
    void executorClientHandlesResolveTicketAction() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var client = ScenarioExecutorClient.create(
            "helpdesk", List.of(scenarioActions), sent::add);
        sent.clear();

        String stepsJson = JSON.writeValueAsString(List.of(
            Map.of("name", "resolve", "label", "Resolve ticket",
                "actor", "hw-specialist",
                "commands", List.of(Map.of(
                    "action", "resolve-ticket",
                    "data", Map.of("resolution", "BIOS reset fixed it"))))
        ));

        client.onMessage(PushMessage.dispatchSequence(
            "s-resolve", "helpdesk", stepsJson, 1000.0, false));

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            var stepResults = sent.stream()
                .filter(s -> s.contains("step-result")).toList();
            assertThat(stepResults).hasSize(1);
            assertThat(stepResults.getFirst())
                .contains("\"ok\":true")
                .contains("RESOLVED")
                .contains("BIOS reset fixed it");
        });
    }

    @Test
    void executorClientRegistersAllActions() {
        var sent = new CopyOnWriteArrayList<String>();
        ScenarioExecutorClient.create(
            "helpdesk", List.of(scenarioActions), sent::add);

        assertThat(sent.getFirst())
            .contains("create-ticket")
            .contains("verify-ticket-exists")
            .contains("resolve-ticket")
            .contains("verify-notification")
            .contains("bootstrap-classifications");
    }
}
