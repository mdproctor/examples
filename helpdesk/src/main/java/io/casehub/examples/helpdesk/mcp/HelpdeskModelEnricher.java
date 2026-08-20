package io.casehub.examples.helpdesk.mcp;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.ModelEnricher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@McpDomain("helpdesk")
@ApplicationScoped
public class HelpdeskModelEnricher implements ModelEnricher {

    @Inject CaseInstanceCache caseInstanceCache;
    @Inject NotificationService notificationService;

    @Override
    public String summary() {
        return "IT Help Desk — bootstrap demo data, inject tickets, "
             + "query ticket status and sent notifications.";
    }

    @Override
    public Map<String, Object> state() {
        return Map.of(
                "activeCases", caseInstanceCache.getAll().stream()
                        .filter(i -> "helpdesk-ticket".equals(
                                i.getCaseMetaModel() != null
                                        ? i.getCaseMetaModel().getName() : null))
                        .count(),
                "notificationsSent", notificationService.getSentNotifications().size());
    }
}
