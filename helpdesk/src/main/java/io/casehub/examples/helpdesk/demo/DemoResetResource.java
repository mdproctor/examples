package io.casehub.examples.helpdesk.demo;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.pages.scenario.runtime.ScenarioOrchestrator;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
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
    @Inject InMemoryCaseInstanceRepository caseInstanceRepository;

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

    private String clearCaseRepository() {
        try {
            var unwrapped = io.quarkus.arc.ClientProxy.unwrap(caseInstanceRepository);
            Class<?> cls = unwrapped.getClass();
            while (cls != null) {
                try {
                    var field = cls.getDeclaredField("store");
                    field.setAccessible(true);
                    var map = (java.util.Map<?, ?>) field.get(unwrapped);
                    int before = map.size();
                    map.clear();
                    return cls.getSimpleName() + ".store: " + before + " → " + map.size();
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            return "FAILED: store field not found in hierarchy";
        } catch (Exception e) {
            return "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
