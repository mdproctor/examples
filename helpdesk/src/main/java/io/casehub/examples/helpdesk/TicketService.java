package io.casehub.examples.helpdesk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.examples.helpdesk.model.Ticket;
import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.casehub.examples.helpdesk.model.TicketStatus;

@ApplicationScoped
public class TicketService {

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    public Ticket create(String subject, String description, String customerRef) {
        var ticket = new Ticket(
                UUID.randomUUID(), subject, description,
                null, null, TicketStatus.OPEN,
                customerRef, null, null,
                Instant.now(), null);
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    public void classify(UUID ticketId, TicketCategory category, TicketPriority priority) {
        tickets.computeIfPresent(ticketId, (id, t) -> t.withClassification(category, priority));
    }

    public void assign(UUID ticketId, String assigneeId) {
        tickets.computeIfPresent(ticketId, (id, t) -> t.withAssignee(assigneeId));
    }

    public Ticket resolve(UUID ticketId, String resolution) {
        return tickets.computeIfPresent(ticketId, (id, t) -> t.withResolution(resolution));
    }

    public Optional<Ticket> findById(UUID ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    public List<Ticket> findAll() {
        return List.copyOf(tickets.values());
    }
}
