package io.casehub.examples.helpdesk.demo;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.TicketService;
import io.casehub.examples.helpdesk.model.Ticket;

@Path("/scenario/verify")
@IfBuildProfile("demo")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class VerificationResource {

    @Inject
    TicketService ticketService;

    @Inject
    NotificationService notificationService;

    @GET
    @Path("/tickets")
    public List<Ticket> tickets() {
        return ticketService.findAll();
    }

    @GET
    @Path("/notifications")
    public List<NotificationService.SentNotification> notifications() {
        return notificationService.getSentNotifications();
    }
}
