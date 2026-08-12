package io.casehub.examples.helpdesk.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.connectors.InboundMessage;

@Path("/scenario/inject/chat")
@IfBuildProfile("demo")
@ApplicationScoped
public class ChatInjectionResource {

    @Inject
    Event<InboundMessage> inboundEvent;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response inject(ChatInjectionRequest request) {
        var msg = new InboundMessage(
                "demo", "demo",
                request.from(), request.channelId(),
                request.text(), List.of(),
                Instant.now(), Map.of(), null);
        inboundEvent.fireAsync(msg);
        return Response.accepted().build();
    }

    public record ChatInjectionRequest(String from, String channelId, String text) {}
}
