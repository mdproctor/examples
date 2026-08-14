package io.casehub.examples.helpdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.SendResult;
import io.casehub.connectors.chat.spi.ChatPlatform;
import io.casehub.examples.helpdesk.NotificationService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class NotificationDispatcherTest {

    @Test
    void dispatchSendsNotificationAndRecordsIt() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        ChatPlatform chatPlatform = ChatPlatform.builder("stub")
                .messaging((ref, content) -> {
                    sent.add(ref.id() + ":" + content.text());
                    return SendResult.success(new ChatMessageRef(new ChatChannelRef(ref.id()), "msg-1"), Instant.now());
                })
                .build();
        var notificationService = new NotificationService(chatPlatform);
        var dispatcher = new NotificationDispatcher(notificationService);

        var result = dispatcher.dispatch("wf-1",
                Map.of("customerRef", "alice", "message", "Resolved")).get();

        assertTrue((Boolean) result.get("notified"));
        assertEquals(1, notificationService.getSentNotifications().size());
        assertEquals("alice", notificationService.getSentNotifications().get(0).to());
        assertEquals(1, sent.size());
    }
}
