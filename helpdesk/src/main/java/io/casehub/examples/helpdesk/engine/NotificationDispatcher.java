package io.casehub.examples.helpdesk.engine;

import io.casehub.engine.flow.CallableDispatcher;
import io.casehub.examples.helpdesk.NotificationService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class NotificationDispatcher implements CallableDispatcher {

    private final NotificationService notificationService;

    public NotificationDispatcher(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> dispatch(String workflowInstanceId,
                                                            Map<String, Object> args) {
        String customerRef = (String) args.get("customerRef");
        String message = (String) args.get("message");
        notificationService.notify(customerRef, message);
        return CompletableFuture.completedFuture(Map.of("notified", true));
    }
}
