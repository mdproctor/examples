package io.casehub.examples.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.casehub.examples.helpdesk.model.TicketStatus;

class TicketServiceTest {

    TicketService service;

    @BeforeEach
    void setUp() {
        service = new TicketService();
    }

    @Test
    void createTicketSetsOpenStatus() {
        var ticket = service.create("Laptop won't boot", "After update last night", "alice");
        assertThat(ticket.id()).isNotNull();
        assertThat(ticket.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.customerRef()).isEqualTo("alice");
    }

    @Test
    void classifyTicketSetsTriagedStatus() {
        var ticket = service.create("Laptop won't boot", "After update", "alice");
        service.classify(ticket.id(), TicketCategory.HARDWARE, TicketPriority.HIGH);
        var updated = service.findById(ticket.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(updated.category()).isEqualTo(TicketCategory.HARDWARE);
        assertThat(updated.priority()).isEqualTo(TicketPriority.HIGH);
    }

    @Test
    void assignTicketSetsAssignedStatus() {
        var ticket = service.create("Laptop won't boot", "After update", "alice");
        service.classify(ticket.id(), TicketCategory.HARDWARE, TicketPriority.HIGH);
        service.assign(ticket.id(), "hw-specialist");
        var updated = service.findById(ticket.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(updated.assigneeId()).isEqualTo("hw-specialist");
    }

    @Test
    void resolveTicketSetsResolvedStatus() {
        var ticket = service.create("Laptop won't boot", "After update", "alice");
        service.classify(ticket.id(), TicketCategory.HARDWARE, TicketPriority.HIGH);
        service.assign(ticket.id(), "hw-specialist");
        var resolved = service.resolve(ticket.id(), "BIOS reset fixed it");
        assertThat(resolved.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("BIOS reset fixed it");
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    @Test
    void findAllReturnsAllTickets() {
        service.create("Issue 1", "Desc 1", "alice");
        service.create("Issue 2", "Desc 2", "bob");
        assertThat(service.findAll()).hasSize(2);
    }
}
