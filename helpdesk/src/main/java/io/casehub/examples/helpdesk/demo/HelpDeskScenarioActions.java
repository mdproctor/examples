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
        return Map.of("status", "RESOLVED", "resolution", ctx.data("resolution"));
    }

    @ScenarioAction("claim-work-item")
    Map<String, Object> claimWorkItem(ActionContext ctx) {
        String port = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", String.class).orElse("8090");
        String baseUrl = "http://localhost:" + port;
        String claimant = ctx.actor() != null ? ctx.actor() : "specialist";
        String category = ctx.data("category");

        var client = java.net.http.HttpClient.newHttpClient();
        try {
            var wiResp = client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/workitems"))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            var items = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(wiResp.body());
            String workItemId = null;
            for (var item : items) {
                if (!"PENDING".equals(item.path("status").asText())) continue;
                if (category != null) {
                    String payload = item.path("payload").asText("");
                    if (!payload.contains("\"category\":\"" + category + "\"")) continue;
                }
                workItemId = item.path("id").asText();
                break;
            }
            if (workItemId == null) throw new IllegalStateException("No pending work item found");

            client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/workitems/" + workItemId + "/claim?claimant=" + claimant))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/workitems/" + workItemId + "/start"))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            return Map.of("workItemId", workItemId, "claimant", claimant, "status", "IN_PROGRESS");
        } catch (Exception e) {
            throw new IllegalStateException("Claim failed: " + e.getMessage(), e);
        }
    }

    @ScenarioAction("complete-work-item")
    Map<String, Object> completeWorkItem(ActionContext ctx) {
        String port = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", String.class).orElse("8090");
        String baseUrl = "http://localhost:" + port;
        String resolution = ctx.data("resolution") != null ? ctx.data("resolution") : "Resolved";
        String assigneeId = ctx.actor() != null ? ctx.actor() : "specialist";

        var client = java.net.http.HttpClient.newHttpClient();
        try {
            var wiResp = client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/workitems"))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            var items = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(wiResp.body());
            String workItemId = null;
            for (var item : items) {
                if ("IN_PROGRESS".equals(item.path("status").asText())) {
                    workItemId = item.path("id").asText();
                    break;
                }
            }
            if (workItemId == null) throw new IllegalStateException("No in-progress work item found");

            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    Map.of("resolution", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                            Map.of("resolution", resolution, "assigneeId", assigneeId))));
            client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/workitems/" + workItemId + "/complete"))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            return Map.of("workItemId", workItemId, "status", "COMPLETED", "resolution", resolution);
        } catch (Exception e) {
            throw new IllegalStateException("Complete failed: " + e.getMessage(), e);
        }
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

    @ScenarioAction("rest-inject-chat")
    Map<String, Object> restInjectChat(ActionContext ctx) {
        String from = ctx.data("from") != null ? ctx.data("from") : "demo-customer";
        String channelId = ctx.data("channelId") != null ? ctx.data("channelId") : "support";
        String text = ctx.data("text");

        String port = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", String.class).orElse("8090");
        String baseUrl = "http://localhost:" + port;
        String body = "{\"from\":\"%s\",\"channelId\":\"%s\",\"text\":\"%s\"}"
                .formatted(from, channelId, text);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/scenario/inject/chat"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return Map.of("status", response.statusCode(), "from", from, "text", text);
        } catch (Exception e) {
            throw new IllegalStateException("REST inject failed: " + e.getMessage(), e);
        }
    }
}
