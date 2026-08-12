package io.casehub.examples.helpdesk.model;

import java.time.Instant;
import java.util.UUID;

public record Ticket(
        UUID id,
        String subject,
        String description,
        TicketCategory category,
        TicketPriority priority,
        TicketStatus status,
        String customerRef,
        String assigneeId,
        String resolution,
        Instant createdAt,
        Instant resolvedAt) {

    public Ticket withClassification(TicketCategory cat, TicketPriority pri) {
        return new Ticket(id, subject, description, cat, pri,
                TicketStatus.TRIAGED, customerRef, assigneeId, resolution, createdAt, resolvedAt);
    }

    public Ticket withAssignee(String assignee) {
        return new Ticket(id, subject, description, category, priority,
                TicketStatus.ASSIGNED, customerRef, assignee, resolution, createdAt, resolvedAt);
    }

    public Ticket withResolution(String res) {
        return new Ticket(id, subject, description, category, priority,
                TicketStatus.RESOLVED, customerRef, assigneeId, res, createdAt, Instant.now());
    }
}
