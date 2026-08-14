package io.casehub.examples.helpdesk.demo;

import io.casehub.examples.helpdesk.engine.KeywordClassifierDispatcher;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/scenario/bootstrap/helpdesk")
@IfBuildProfile("demo")
@ApplicationScoped
public class ScenarioBootstrapResource {

    @Inject KeywordClassifierDispatcher classifier;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bootstrap(BootstrapRequest request) {
        if (request.ticketClassifications() != null) {
            var entries = request.ticketClassifications().stream()
                    .map(e -> new KeywordClassifierDispatcher.ClassificationEntry(
                            e.get("match").toString(),
                            e.get("category").toString(),
                            e.get("priority").toString()))
                    .toList();
            classifier.loadClassifications(entries);
        }
        return Response.ok().build();
    }

    public record BootstrapRequest(List<Map<String, Object>> ticketClassifications) {}
}
