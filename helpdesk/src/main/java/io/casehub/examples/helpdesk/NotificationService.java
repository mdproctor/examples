package io.casehub.examples.helpdesk;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.spi.ChatPlatform;

@ApplicationScoped
public class NotificationService {

    private final ChatPlatform chatPlatform;
    private final List<SentNotification> sent = new CopyOnWriteArrayList<>();

    @Inject
    public NotificationService(ChatPlatform chatPlatform) {
        this.chatPlatform = chatPlatform;
    }

    public void notify(String customerRef, String message) {
        chatPlatform.messaging().send(new ChatChannelRef(customerRef), new ChatContent(message));
        sent.add(new SentNotification(customerRef, message, Instant.now()));
    }

    public List<SentNotification> getSentNotifications() {
        return List.copyOf(sent);
    }

    public record SentNotification(String to, String message, Instant sentAt) {}
}
