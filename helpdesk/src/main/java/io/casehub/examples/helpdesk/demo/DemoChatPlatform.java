package io.casehub.examples.helpdesk.demo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.SendResult;
import io.casehub.connectors.chat.spi.ChannelManagement;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.casehub.connectors.chat.spi.Discovery;
import io.casehub.connectors.chat.spi.MemberManagement;
import io.casehub.connectors.chat.spi.Members;
import io.casehub.connectors.chat.spi.MessageHistory;
import io.casehub.connectors.chat.spi.Messaging;
import io.casehub.connectors.chat.spi.Presence;
import io.casehub.connectors.chat.spi.Reactions;
import io.casehub.connectors.chat.spi.Threading;

@ApplicationScoped
@Alternative
@Priority(300)
@IfBuildProfile("demo")
public class DemoChatPlatform implements ChatPlatform {

    public record SentMessage(String channelId, String text, Instant sentAt) {}

    private final List<SentMessage> sentMessages = new CopyOnWriteArrayList<>();

    private final ChatPlatform degraded = ChatPlatform.builder("demo")
            .messaging((ch, c) -> SendResult.success(
                    new ChatMessageRef(ch, UUID.randomUUID().toString()), Instant.now()))
            .build();

    @Override
    public String id() { return "demo"; }

    @Override
    public Messaging messaging() {
        return (channel, content) -> {
            sentMessages.add(new SentMessage(channel.id(), content.text(), Instant.now()));
            var ref = new ChatMessageRef(channel, UUID.randomUUID().toString());
            return SendResult.success(ref, Instant.now());
        };
    }

    public List<SentMessage> getSentMessages() { return List.copyOf(sentMessages); }

    @Override public Threading threading() { return degraded.threading(); }
    @Override public Discovery discovery() { return degraded.discovery(); }
    @Override public Reactions reactions() { return degraded.reactions(); }
    @Override public Presence presence() { return degraded.presence(); }
    @Override public Members members() { return degraded.members(); }
    @Override public ChannelManagement channelManagement() { return degraded.channelManagement(); }
    @Override public MemberManagement memberManagement() { return degraded.memberManagement(); }
    @Override public MessageHistory messageHistory() { return degraded.messageHistory(); }
    @Override public boolean supports(Class<?> capability) { return Messaging.class.equals(capability); }
}
