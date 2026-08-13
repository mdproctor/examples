package io.casehub.examples.helpdesk.event;

import io.casehub.examples.helpdesk.model.Ticket;

public record TicketEvent(Type type, Ticket ticket) {
    public enum Type { CREATED, CLASSIFIED, ASSIGNED, RESOLVED }
}
