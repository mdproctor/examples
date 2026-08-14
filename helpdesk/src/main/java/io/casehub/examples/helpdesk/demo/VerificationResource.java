package io.casehub.examples.helpdesk.demo;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

@Path("/scenario/verify")
@IfBuildProfile("demo")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class VerificationResource {

    @Inject CaseInstanceCache caseInstanceCache;
    @Inject NotificationService notificationService;

    @SuppressWarnings("unchecked")
    @GET
    @Path("/tickets")
    public List<Map<String, Object>> tickets() {
        return caseInstanceCache.getAll().stream()
                .filter(i -> "helpdesk-ticket".equals(
                        i.getCaseMetaModel() != null ? i.getCaseMetaModel().getName() : null))
                .map(instance -> {
                    var ctx = instance.getCaseContext();
                    return ctx != null ? ctx.getData() : Map.<String, Object>of();
                })
                .toList();
    }

    @GET
    @Path("/notifications")
    public List<NotificationService.SentNotification> notifications() {
        return notificationService.getSentNotifications();
    }
}
