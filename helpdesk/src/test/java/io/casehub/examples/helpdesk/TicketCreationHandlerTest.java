package io.casehub.examples.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.examples.helpdesk.demo.DemoTicketClassifier;
import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.casehub.examples.helpdesk.model.TicketStatus;

class TicketCreationHandlerTest {

    TicketService ticketService;
    DemoTicketClassifier classifier;
    TicketCreationHandler handler;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService();
        classifier = new DemoTicketClassifier();
        classifier.loadClassifications(List.of(
                new DemoTicketClassifier.ClassificationEntry(
                        "laptop", TicketCategory.HARDWARE, TicketPriority.HIGH)));
        handler = new TicketCreationHandler(ticketService, classifier);
    }

    @Test
    void createsAndClassifiesTicketFromChatMessage() {
        var channel = new ChatChannelRef("support");
        var msg = new ReceivedMessage(
                "demo", channel,
                new ChatMessageRef(channel, "msg-1"), null,
                new MemberRef("alice"),
                new ChatContent("My laptop won't boot"),
                Instant.now());

        handler.onMessage(msg);

        var tickets = ticketService.findAll();
        assertThat(tickets).hasSize(1);
        var ticket = tickets.getFirst();
        assertThat(ticket.subject()).isEqualTo("My laptop won't boot");
        assertThat(ticket.customerRef()).isEqualTo("alice");
        assertThat(ticket.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket.category()).isEqualTo(TicketCategory.HARDWARE);
        assertThat(ticket.assigneeId()).isEqualTo("hw-specialist");
    }

    @Test
    void defaultClassificationWhenNoMatch() {
        var channel = new ChatChannelRef("support");
        var msg = new ReceivedMessage(
                "demo", channel,
                new ChatMessageRef(channel, "msg-2"), null,
                new MemberRef("bob"),
                new ChatContent("Something weird happened"),
                Instant.now());

        handler.onMessage(msg);

        var ticket = ticketService.findAll().getFirst();
        assertThat(ticket.category()).isEqualTo(TicketCategory.OTHER);
        assertThat(ticket.assigneeId()).isEqualTo("general-specialist");
    }
}
