package io.casehub.examples.helpdesk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.spi.TicketClassifier;

@ApplicationScoped
public class TicketCreationHandler {

    private static final Logger LOG = Logger.getLogger(TicketCreationHandler.class);

    private final TicketService ticketService;
    private final TicketClassifier classifier;

    @Inject
    public TicketCreationHandler(TicketService ticketService, TicketClassifier classifier) {
        this.ticketService = ticketService;
        this.classifier = classifier;
    }

    public void onMessage(@ObservesAsync ReceivedMessage message) {
        var subject = message.content().text();
        var ticket = ticketService.create(subject, subject, message.sender().id());
        var classification = classifier.classify(subject, "");
        ticketService.classify(ticket.id(), classification.category(), classification.priority());
        ticketService.assign(ticket.id(), routeByCategory(classification.category()));
        LOG.infof("Ticket %s created from chat message: %s → %s",
                ticket.id(), subject, classification.category());
    }

    private String routeByCategory(TicketCategory category) {
        return switch (category) {
            case HARDWARE -> "hw-specialist";
            case SOFTWARE -> "sw-specialist";
            case ACCESS -> "access-specialist";
            case OTHER -> "general-specialist";
        };
    }
}
