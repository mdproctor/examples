package io.casehub.examples.helpdesk.demo;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.pages.scenario.runtime.ScenarioOrchestrator;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.flywaydb.core.Flyway;

@Path("/scenario/reset")
@IfBuildProfile("demo")
@ApplicationScoped
public class DemoResetResource {

    @Inject ScenarioOrchestrator orchestrator;
    @Inject Flyway flyway;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject NotificationService notificationService;

    @POST
    public Response reset() {
        orchestrator.stop();
        caseInstanceCache.clear();
        notificationService.reset();
        flyway.clean();
        flyway.migrate();
        return Response.ok().build();
    }
}
