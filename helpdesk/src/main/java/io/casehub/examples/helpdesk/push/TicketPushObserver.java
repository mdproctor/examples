package io.casehub.examples.helpdesk.push;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.event.NotificationEvent;
import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class TicketPushObserver {

    @Inject EventBroadcaster broadcaster;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject NotificationService notificationService;

    void onNotification(@Observes NotificationEvent event) {
        broadcaster.broadcast("helpdesk:notifications", event);
        broadcastMetrics();
    }

    private void broadcastMetrics() {
        var cases = caseInstanceCache.getAll().stream()
                .filter(i -> i.getCaseMetaModel() != null
                        && "helpdesk-ticket".equals(i.getCaseMetaModel().getName()))
                .toList();
        long total = cases.size();
        long resolved = cases.stream()
                .filter(i -> {
                    var ctx = i.getCaseContext();
                    return ctx != null && "RESOLVED".equals(ctx.get("status"));
                }).count();
        long notified = notificationService.getSentNotifications().size();
        broadcaster.broadcast("helpdesk:metrics", Map.of(
                "total", total,
                "open", total - resolved,
                "resolved", resolved,
                "notified", notified
        ));
    }
}
