package io.casehub.examples.helpdesk.event;

import io.casehub.examples.helpdesk.DemoTestProfile;
import io.casehub.examples.helpdesk.TicketService;
import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class TicketEventFiringTest {

    @Inject TicketService ticketService;
    @Inject EventCaptor captor;

    @BeforeEach
    void reset() {
        captor.events.clear();
    }

    @Test
    void create_fires_created_event() {
        var ticket = ticketService.create("Laptop broken", "Screen cracked", "alice");
        assertThat(captor.events).hasSize(1);
        assertThat(captor.events.get(0).type()).isEqualTo(TicketEvent.Type.CREATED);
        assertThat(captor.events.get(0).ticket().id()).isEqualTo(ticket.id());
    }

    @Test
    void classify_fires_classified_event() {
        var ticket = ticketService.create("Laptop broken", "Screen cracked", "alice");
        captor.events.clear();
        ticketService.classify(ticket.id(), TicketCategory.HARDWARE, TicketPriority.HIGH);
        assertThat(captor.events).hasSize(1);
        assertThat(captor.events.get(0).type()).isEqualTo(TicketEvent.Type.CLASSIFIED);
    }

    @Test
    void assign_fires_assigned_event() {
        var ticket = ticketService.create("Laptop broken", "Screen cracked", "alice");
        captor.events.clear();
        ticketService.assign(ticket.id(), "hw-specialist");
        assertThat(captor.events).hasSize(1);
        assertThat(captor.events.get(0).type()).isEqualTo(TicketEvent.Type.ASSIGNED);
    }

    @Test
    void resolve_fires_resolved_event() {
        var ticket = ticketService.create("Laptop broken", "Screen cracked", "alice");
        captor.events.clear();
        ticketService.resolve(ticket.id(), "Replaced screen");
        assertThat(captor.events).hasSize(1);
        assertThat(captor.events.get(0).type()).isEqualTo(TicketEvent.Type.RESOLVED);
    }

    @Singleton
    static class EventCaptor {
        final List<TicketEvent> events = new CopyOnWriteArrayList<>();

        void onTicketEvent(@Observes TicketEvent event) {
            events.add(event);
        }
    }
}
