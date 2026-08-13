package io.casehub.examples.helpdesk.push;

import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.TicketService;
import io.casehub.examples.helpdesk.event.NotificationEvent;
import io.casehub.examples.helpdesk.event.TicketEvent;
import io.casehub.examples.helpdesk.model.TicketStatus;
import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class TicketPushObserver {

    private static final Set<TicketStatus> OPEN_STATUSES =
            Set.of(TicketStatus.OPEN, TicketStatus.TRIAGED, TicketStatus.ASSIGNED);

    @Inject EventBroadcaster broadcaster;
    @Inject TicketService ticketService;
    @Inject NotificationService notificationService;

    void onTicketEvent(@Observes TicketEvent event) {
        broadcaster.broadcast("helpdesk:tickets", event);
        broadcastMetrics();
    }

    void onNotification(@Observes NotificationEvent event) {
        broadcaster.broadcast("helpdesk:notifications", event);
        broadcastMetrics();
    }

    private void broadcastMetrics() {
        var all = ticketService.findAll();
        long open = all.stream().filter(t -> OPEN_STATUSES.contains(t.status())).count();
        long resolved = all.stream().filter(t -> t.status() == TicketStatus.RESOLVED
                || t.status() == TicketStatus.CLOSED).count();
        long notified = notificationService.getSentNotifications().size();
        broadcaster.broadcast("helpdesk:metrics", Map.of(
                "total", all.size(),
                "open", open,
                "resolved", resolved,
                "notified", notified
        ));
    }
}
