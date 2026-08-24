package io.casehub.examples.helpdesk.demo;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.pages.scenario.runtime.ScenarioOrchestrator;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

@Path("/scenario/reset")
@IfBuildProfile("demo")
@ApplicationScoped
public class DemoResetResource {

    private static final Logger LOG = Logger.getLogger(DemoResetResource.class);

    @Inject ScenarioOrchestrator orchestrator;
    @Inject Flyway flyway;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject NotificationService notificationService;
    @Inject InMemoryWorkItemStore workItemStore;
    @Inject io.casehub.engine.common.spi.CaseInstanceRepository caseInstanceRepository;

    @POST
    public Response reset() {
        orchestrator.stop();
        caseInstanceCache.clear();
        workItemStore.clear();
        notificationService.reset();
        clearCaseRepository();
        flyway.clean();
        flyway.migrate();
        LOG.info("Demo state fully reset");
        return Response.ok().build();
    }

    private void clearCaseRepository() {
        try {
            var unwrapped = io.quarkus.arc.ClientProxy.unwrap(caseInstanceRepository);
            for (var field : unwrapped.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ((java.util.Map<?, ?>) field.get(unwrapped)).clear();
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not clear case repository via reflection: " + e.getMessage());
        }
    }
}
