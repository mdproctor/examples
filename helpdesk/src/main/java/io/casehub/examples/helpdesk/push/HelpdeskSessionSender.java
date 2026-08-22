package io.casehub.examples.helpdesk.push;

import io.casehub.pages.push.SessionSender;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HelpdeskSessionSender implements SessionSender {
    private final java.util.Map<String, java.util.function.Consumer<String>> localHandlers = new java.util.concurrent.ConcurrentHashMap<>();


    @Inject
    ConnectionRegistry registry;


    public void registerLocal(String connectionId, java.util.function.Consumer<String> handler) {
        localHandlers.put(connectionId, handler);
    }

    @Override
    public void send(String connectionId, String message) {
        var local = localHandlers.get(connectionId);
        if (local != null) {
            local.accept(message);
            return;
        }
        WebSocketConnection conn = registry.get(connectionId);
        if (conn != null && !conn.isClosed()) {
            conn.sendTextAndAwait(message);
        }
    }
}
