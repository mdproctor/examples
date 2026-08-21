package io.casehub.examples.helpdesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.examples.helpdesk.demo.HelpDeskScenarioActions;
import io.casehub.pages.scenario.client.ScenarioExecutorClient;
import io.casehub.pages.scenario.runtime.ScenarioOrchestrator;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class ScenarioE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/push")
    URI pushUri;

    @Inject
    ScenarioOrchestrator orchestrator;

    @Inject
    HelpDeskScenarioActions scenarioActions;

    @Test
    void fullScenarioFlowOverWebSocket() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var connectedLatch = new CountDownLatch(1);
        var wsRef = new java.util.concurrent.atomic.AtomicReference<WebSocket>();

        var wsUri = URI.create(pushUri.toString().replace("http://", "ws://"));

        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(wsUri, new WebSocket.Listener() {
                final StringBuilder buffer = new StringBuilder();
                volatile ScenarioExecutorClient executorClient;

                @Override
                public void onOpen(WebSocket webSocket) {
                    wsRef.set(webSocket);
                    executorClient = ScenarioExecutorClient.create(
                        "helpdesk", List.of(scenarioActions),
                        msg -> webSocket.sendText(msg, true));
                    connectedLatch.countDown();
                    webSocket.request(1);
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        String msg = buffer.toString();
                        buffer.setLength(0);
                        received.add(msg);

                        try {
                            JsonNode node = JSON.readTree(msg);
                            String op = node.path("op").asText("");
                            if ("dispatch-sequence".equals(op) || "executor-control".equals(op)) {
                                executorClient.onMessage(msg);
                            }
                        } catch (Exception e) {
                            // ignore parse errors
                        }
                    }
                    webSocket.request(1);
                    return null;
                }
            }).join();

        assertThat(connectedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        var scenarioYaml = """
            scenario: e2e-test
            steps:
              - label: "Create ticket"
                name: create-step
                target: helpdesk
                commands:
                  - action: create-ticket
                    data:
                      subject: "E2E test ticket"
                      category: HARDWARE
                      priority: HIGH
              - label: "Resolve ticket"
                name: resolve-step
                target: helpdesk
                actor: hw-specialist
                commands:
                  - action: resolve-ticket
                    data:
                      resolution: "Fixed in e2e test"
            """;

        given()
            .contentType("application/json")
            .body(Map.of("yaml", scenarioYaml))
            .when().post("/scenario/start")
            .then().statusCode(200);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var dispatches = received.stream()
                .filter(m -> m.contains("dispatch-sequence"))
                .toList();
            assertThat(dispatches).isNotEmpty();
        });

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var state = orchestrator.state();
            assertThat(state.scenario()).isEqualTo("e2e-test");
            assertThat(state.progress()).isGreaterThan(0.0);
        });

        ws.sendClose(1000, "done").join();
    }

    @Test
    void scenarioStateEndpointReflectsProgress() {
        var state = given()
            .when().get("/scenario/state")
            .then().statusCode(200)
            .extract().as(Map.class);

        assertThat(state).containsKey("speed");
        assertThat(state).containsKey("paused");
    }
}
