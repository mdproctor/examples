package io.casehub.examples.helpdesk;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.casehub.examples.helpdesk.event.NotificationEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class NotificationService {

    private final ChatPlatform chatPlatform;
    private final List<SentNotification> sent = new CopyOnWriteArrayList<>();
    @Inject
    Event<NotificationEvent> notificationEvent;


    @Inject
    public NotificationService(ChatPlatform chatPlatform) {
        this.chatPlatform = chatPlatform;
    }

    public void notify(String customerRef, String message) {
        chatPlatform.messaging().send(new ChatChannelRef(customerRef), new ChatContent(message));
        sent.add(new SentNotification(customerRef, message, Instant.now()));
        if (notificationEvent != null) notificationEvent.fire(new NotificationEvent(customerRef, message));
    }

    public List<SentNotification> getSentNotifications() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }

    public record SentNotification(String to, String message, Instant sentAt) {}
}
