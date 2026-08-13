package io.casehub.examples.helpdesk.push;

import io.casehub.examples.helpdesk.DemoTestProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
class PushEndpointTest {

    @TestHTTPResource("/push")
    URI pushUri;

    @Test
    void client_connects_and_receives_ack_on_listen() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(1);

        var wsUri = URI.create(pushUri.toString().replace("http://", "ws://"));
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUri, new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messages.add(data.toString());
                        latch.countDown();
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"op\":\"listen\",\"id\":\"req-1\",\"topics\":[\"helpdesk:tickets\"]}", true);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("\"op\":\"ack\"");
        assertThat(messages.get(0)).contains("\"id\":\"req-1\"");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
