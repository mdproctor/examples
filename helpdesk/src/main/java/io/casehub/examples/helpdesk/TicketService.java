package io.casehub.examples.helpdesk;

import io.casehub.examples.helpdesk.model.Ticket;
import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.casehub.examples.helpdesk.event.TicketEvent;
import io.casehub.examples.helpdesk.model.TicketStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TicketService {

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    @Inject
    Event<TicketEvent> ticketEvent;


    public Ticket create(String subject, String description, String customerRef) {
        var ticket = new Ticket(
                UUID.randomUUID(), subject, description,
                null, null, TicketStatus.OPEN,
                customerRef, null, null,
                Instant.now(), null);
        tickets.put(ticket.id(), ticket);
        if (ticketEvent != null) ticketEvent.fire(new TicketEvent(TicketEvent.Type.CREATED, ticket));
        return ticket;
    }

    public void classify(UUID ticketId, TicketCategory category, TicketPriority priority) {
        var updated = tickets.computeIfPresent(ticketId, (id, t) -> t.withClassification(category, priority));
        if (updated != null) {
            if (ticketEvent != null) ticketEvent.fire(new TicketEvent(TicketEvent.Type.CLASSIFIED, updated));
        }
    }

    public void assign(UUID ticketId, String assigneeId) {
        var updated = tickets.computeIfPresent(ticketId, (id, t) -> t.withAssignee(assigneeId));
        if (updated != null) {
            if (ticketEvent != null) ticketEvent.fire(new TicketEvent(TicketEvent.Type.ASSIGNED, updated));
        }
    }

    public Ticket resolve(UUID ticketId, String resolution) {
        var updated = tickets.computeIfPresent(ticketId, (id, t) -> t.withResolution(resolution));
        if (updated != null) {
            if (ticketEvent != null) ticketEvent.fire(new TicketEvent(TicketEvent.Type.RESOLVED, updated));
        }
        return updated;
    }

    public Optional<Ticket> findById(UUID ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    public List<Ticket> findAll() {
        return List.copyOf(tickets.values());
    }
}
