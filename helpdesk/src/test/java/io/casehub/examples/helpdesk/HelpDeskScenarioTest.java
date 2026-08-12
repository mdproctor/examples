package io.casehub.examples.helpdesk;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class HelpDeskScenarioTest {

    @Test
    void chatMessageCreatesClassifiedTicket() {
        given()
            .contentType("application/json")
            .body(Map.of("ticketClassifications", List.of(
                    Map.of("match", "laptop won't boot",
                            "category", "HARDWARE",
                            "priority", "HIGH"))))
            .when().post("/scenario/bootstrap/helpdesk")
            .then().statusCode(200);

        given()
            .contentType("application/json")
            .body(Map.of("from", "Alice Customer",
                    "channelId", "support",
                    "text", "My laptop won't boot after the update last night"))
            .when().post("/scenario/inject/chat")
            .then().statusCode(202);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var tickets = given()
                    .when().get("/scenario/verify/tickets")
                    .then().statusCode(200)
                    .extract().jsonPath().getList(".", Map.class);
            assertThat(tickets).hasSize(1);
            assertThat(tickets.getFirst().get("status")).isEqualTo("ASSIGNED");
            assertThat(tickets.getFirst().get("category")).isEqualTo("HARDWARE");
            assertThat(tickets.getFirst().get("customerRef")).isEqualTo("Alice Customer");
            assertThat(tickets.getFirst().get("assigneeId")).isEqualTo("hw-specialist");
        });
    }

    @Test
    void resolveTicketSendsNotification() {
        given()
            .contentType("application/json")
            .body(Map.of("ticketClassifications", List.of(
                    Map.of("match", "printer", "category", "HARDWARE", "priority", "MEDIUM"))))
            .when().post("/scenario/bootstrap/helpdesk")
            .then().statusCode(200);

        given()
            .contentType("application/json")
            .body(Map.of("from", "Bob User",
                    "channelId", "support",
                    "text", "The printer is jammed"))
            .when().post("/scenario/inject/chat")
            .then().statusCode(202);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var tickets = given()
                    .when().get("/scenario/verify/tickets")
                    .then().statusCode(200)
                    .extract().jsonPath().getList(".", Map.class);
            assertThat(tickets).anySatisfy(t ->
                    assertThat(t.get("customerRef")).isEqualTo("Bob User"));
        });

        var ticketId = given()
                .when().get("/scenario/verify/tickets")
                .then().extract().jsonPath()
                .getString("find { it.customerRef == 'Bob User' }.id");

        given()
            .contentType("application/json")
            .body(Map.of("resolution", "Cleared the paper jam"))
            .when().put("/tickets/" + ticketId + "/resolve")
            .then().statusCode(200);

        var notifications = given()
                .when().get("/scenario/verify/notifications")
                .then().statusCode(200)
                .extract().jsonPath().getList(".", Map.class);
        assertThat(notifications).anySatisfy(n -> {
            assertThat(n.get("to")).isEqualTo("Bob User");
            assertThat((String) n.get("message")).contains("Cleared the paper jam");
        });
    }
}
