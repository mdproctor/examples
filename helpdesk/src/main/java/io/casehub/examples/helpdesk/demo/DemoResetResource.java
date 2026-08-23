package io.casehub.examples.helpdesk.demo;

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

    @POST
    public Response reset() {
        orchestrator.stop();
        flyway.clean();
        flyway.migrate();
        return Response.ok().build();
    }
}
