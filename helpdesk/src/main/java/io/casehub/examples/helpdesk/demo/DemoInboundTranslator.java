package io.casehub.examples.helpdesk.demo;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.InboundTranslator;

@ApplicationScoped
public class DemoInboundTranslator implements InboundTranslator {

    @Override
    public String connectorType() {
        return "demo";
    }

    @Override
    public ReceivedMessage translate(InboundMessage msg) {
        var channel = new ChatChannelRef(
                msg.externalChannelRef() != null ? msg.externalChannelRef() : "support");
        return new ReceivedMessage(
                "demo", channel,
                new ChatMessageRef(channel, UUID.randomUUID().toString()),
                null,
                new MemberRef(msg.externalSenderId()),
                new ChatContent(msg.content(), null, msg.attachments(), List.of()),
                msg.receivedAt());
    }
}
