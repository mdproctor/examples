package io.casehub.examples.helpdesk.rest;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.TicketService;
import io.casehub.examples.helpdesk.model.Ticket;

@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
public class TicketResource {

    @Inject
    TicketService ticketService;

    @Inject
    NotificationService notificationService;

    @GET
    public List<Ticket> list() {
        return ticketService.findAll();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return ticketService.findById(id)
                .map(t -> Response.ok(t).build())
                .orElse(Response.status(404).build());
    }

    @PUT
    @Path("/{id}/resolve")
    public Response resolve(@PathParam("id") UUID id, ResolveRequest request) {
        var ticket = ticketService.resolve(id, request.resolution());
        if (ticket != null) {
            notificationService.notify(ticket.customerRef(),
                    "Your ticket has been resolved: " + request.resolution());
            return Response.ok(ticket).build();
        }
        return Response.status(404).build();
    }

    public record ResolveRequest(String resolution) {}
}
