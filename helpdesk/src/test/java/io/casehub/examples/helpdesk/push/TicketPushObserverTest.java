package io.casehub.examples.helpdesk.push;

import io.casehub.examples.helpdesk.DemoTestProfile;
import io.casehub.examples.helpdesk.TicketService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class TicketPushObserverTest {

    @TestHTTPResource("/push")
    URI pushUri;

    @Inject
    TicketService ticketService;

    @Test
    void ticket_creation_broadcasts_to_connected_client() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var ackLatch = new CountDownLatch(1);
        var eventLatch = new CountDownLatch(2);

        var wsUri = URI.create(pushUri.toString().replace("http://", "ws://"));
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUri, new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        messages.add(msg);
                        if (msg.contains("\"op\":\"ack\"")) ackLatch.countDown();
                        if (msg.contains("\"op\":\"event\"")) eventLatch.countDown();
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"op\":\"listen\",\"id\":\"r1\",\"topics\":[\"helpdesk:tickets\",\"helpdesk:metrics\"]}", true);
        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        ticketService.create("Test ticket", "Test description", "bob");

        assertThat(eventLatch.await(5, TimeUnit.SECONDS)).isTrue();

        var ticketEvents = messages.stream()
                .filter(m -> m.contains("\"op\":\"event\"") && m.contains("helpdesk:tickets")).toList();
        assertThat(ticketEvents).isNotEmpty();
        assertThat(ticketEvents.get(0)).contains("CREATED");

        var metricsEvents = messages.stream()
                .filter(m -> m.contains("\"op\":\"event\"") && m.contains("helpdesk:metrics")).toList();
        assertThat(metricsEvents).isNotEmpty();
        assertThat(metricsEvents.get(0)).contains("\"total\"");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
