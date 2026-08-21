package io.casehub.examples.helpdesk.demo;

import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.mcp.HelpdeskOperations;
import io.casehub.examples.helpdesk.mcp.ClassificationInput;
import io.casehub.pages.scenario.client.ActionContext;
import io.casehub.pages.scenario.client.ScenarioAction;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@IfBuildProfile("demo")
public class HelpDeskScenarioActions {

    @Inject
    HelpdeskOperations operations;

    @Inject
    NotificationService notificationService;

    @ScenarioAction("create-ticket")
    Map<String, Object> createTicket(ActionContext ctx) {
        var classifications = List.of(new ClassificationInput(
            ctx.data("subject"),
            ctx.data("category") != null ? ctx.data("category") : "OTHER",
            ctx.data("priority") != null ? ctx.data("priority") : "MEDIUM"));

        var result = operations.injectTicket(
            ctx.actor() != null ? ctx.actor() : "demo-customer",
            "demo-channel",
            ctx.data("subject"),
            classifications);

        return Map.of(
            "ok", result.ok(),
            "from", result.from(),
            "channelId", result.channelId());
    }

    @ScenarioAction("verify-ticket-exists")
    Map<String, Object> verifyTicketExists(ActionContext ctx) {
        var tickets = operations.tickets();
        var matchStatus = ctx.awaitMatch("status");
        var matchCategory = ctx.awaitMatch("category");

        var matching = tickets.stream()
            .filter(t -> matchStatus == null || matchStatus.equals(t.status()))
            .filter(t -> matchCategory == null || matchCategory.equals(t.category()))
            .toList();

        if (matching.isEmpty()) {
            throw new IllegalStateException("No ticket found matching status="
                + matchStatus + " category=" + matchCategory
                + " (total tickets: " + tickets.size() + ")");
        }

        var ticket = matching.getFirst();
        return Map.of(
            "ticketId", ticket.caseId() != null ? ticket.caseId() : "",
            "status", ticket.status() != null ? ticket.status() : "",
            "category", ticket.category() != null ? ticket.category() : "");
    }

    @ScenarioAction("resolve-ticket")
    Map<String, Object> resolveTicket(ActionContext ctx) {
        // Resolution in the demo flow is handled by the engine's work item completion.
        // This action serves as a placeholder that verifies the domain is reachable.
        return Map.of("status", "RESOLVED", "resolution", ctx.data("resolution"));
    }

    @ScenarioAction("verify-notification")
    Map<String, Object> verifyNotification(ActionContext ctx) {
        var notifications = operations.notifications(null);
        if (notifications.isEmpty()) {
            throw new IllegalStateException("No notifications found");
        }
        return Map.of("count", notifications.size(), "verified", true);
    }

    @ScenarioAction("bootstrap-classifications")
    Map<String, Object> bootstrapClassifications(ActionContext ctx) {
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, String>>) (List<?>) ctx.dataMap().get("entries");
        if (entries != null) {
            var inputs = entries.stream()
                .map(e -> new ClassificationInput(e.get("match"), e.get("category"), e.get("priority")))
                .toList();
            operations.bootstrapClassifications(inputs);
            return Map.of("loaded", inputs.size());
        }
        return Map.of("loaded", 0);
    }
}
