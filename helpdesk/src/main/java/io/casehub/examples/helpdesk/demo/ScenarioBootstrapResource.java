package io.casehub.examples.helpdesk.demo;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;

@Path("/scenario/bootstrap/helpdesk")
@IfBuildProfile("demo")
@ApplicationScoped
public class ScenarioBootstrapResource {

    @Inject
    DemoTicketClassifier classifier;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bootstrap(BootstrapRequest request) {
        if (request.ticketClassifications() != null) {
            var entries = request.ticketClassifications().stream()
                    .map(e -> new DemoTicketClassifier.ClassificationEntry(
                            e.get("match").toString(),
                            TicketCategory.valueOf(e.get("category").toString()),
                            TicketPriority.valueOf(e.get("priority").toString())))
                    .toList();
            classifier.loadClassifications(entries);
        }
        return Response.ok().build();
    }

    public record BootstrapRequest(List<Map<String, Object>> ticketClassifications) {}
}
