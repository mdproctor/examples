package io.casehub.examples.helpdesk.mcp;

import io.casehub.connectors.InboundMessage;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService.SentNotification;
import io.casehub.examples.helpdesk.engine.KeywordClassifierDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpdeskOperationsImplTest {

    private final KeywordClassifierDispatcher classifier = new KeywordClassifierDispatcher();
    private final List<InboundMessage> capturedMessages = new ArrayList<>();
    private final StubCaseInstanceCache caseCache = new StubCaseInstanceCache();
    private final StubNotificationService notifications = new StubNotificationService();
    private HelpdeskOperationsImpl ops;

    @BeforeEach
    void setUp() {
        capturedMessages.clear();
        caseCache.clear();
        notifications.reset();
        ops = new HelpdeskOperationsImpl(classifier, caseCache,
                notifications, capturedMessages::add);
    }

    @Test
    void bootstrapClassificationsLoadsEntries() {
        final var entries = List.of(
                new ClassificationInput("laptop", "HARDWARE", "HIGH"),
                new ClassificationInput("password", "ACCESS", "MEDIUM"));

        final var result = ops.bootstrapClassifications(entries);

        assertThat(result.ok()).isTrue();
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void bootstrapClassificationsWithEmptyList() {
        final var result = ops.bootstrapClassifications(List.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.count()).isEqualTo(0);
    }

    @Test
    void bootstrapClassificationsRejectsNull() {
        assertThatThrownBy(() -> ops.bootstrapClassifications(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bootstrapClassificationsAreUsableByClassifier() {
        ops.bootstrapClassifications(List.of(
                new ClassificationInput("laptop", "HARDWARE", "HIGH")));

        final var result = classifier.dispatch("test",
                Map.of("text", "my laptop is broken"));

        assertThat(result.join()).containsEntry("category", "HARDWARE");
        assertThat(result.join()).containsEntry("priority", "HIGH");
    }

    @Test
    void injectTicketBootstrapsAndFiresMessage() {
        final var result = ops.injectTicket("Alice", "support",
                "My laptop won't boot",
                List.of(new ClassificationInput("laptop", "HARDWARE", "HIGH")));

        assertThat(result.ok()).isTrue();
        assertThat(result.from()).isEqualTo("Alice");
        assertThat(result.channelId()).isEqualTo("support");

        assertThat(capturedMessages).hasSize(1);
        final var msg = capturedMessages.getFirst();
        assertThat(msg.externalSenderId()).isEqualTo("Alice");
        assertThat(msg.externalChannelRef()).isEqualTo("support");
        assertThat(msg.content()).isEqualTo("My laptop won't boot");
    }

    @Test
    void injectTicketWithNullClassificationsSkipsBootstrap() {
        final var result = ops.injectTicket("Bob", "support", "Help", null);

        assertThat(result.ok()).isTrue();
        assertThat(capturedMessages).hasSize(1);
    }

    @Test
    void injectTicketWithEmptyClassificationsSkipsBootstrap() {
        final var result = ops.injectTicket("Bob", "support", "Help", List.of());

        assertThat(result.ok()).isTrue();
        assertThat(capturedMessages).hasSize(1);
    }

    @Test
    void ticketsReturnsEmptyWhenNoCases() {
        assertThat(ops.tickets()).isEmpty();
    }

    @Test
    void ticketsProjectsCaseContextAsTicketView() {
        caseCache.add(buildCase("helpdesk-ticket", Map.of(
                "status", "TRIAGED",
                "category", "HARDWARE",
                "priority", "HIGH",
                "customerRef", "Alice",
                "subject", "Laptop issue")));

        final var result = ops.tickets();

        assertThat(result).hasSize(1);
        final var ticket = result.getFirst();
        assertThat(ticket.status()).isEqualTo("TRIAGED");
        assertThat(ticket.category()).isEqualTo("HARDWARE");
        assertThat(ticket.priority()).isEqualTo("HIGH");
        assertThat(ticket.customerRef()).isEqualTo("Alice");
    }

    @Test
    void ticketsFiltersToHelpdeskCasesOnly() {
        caseCache.add(buildCase("helpdesk-ticket", Map.of("status", "OPEN")));
        caseCache.add(buildCase("other-type", Map.of("status", "OPEN")));

        assertThat(ops.tickets()).hasSize(1);
    }

    @Test
    void ticketsHandlesMissingContextGracefully() {
        final var instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        final var meta = new CaseMetaModel();
        meta.setName("helpdesk-ticket");
        instance.setCaseMetaModel(meta);
        caseCache.add(instance);

        final var result = ops.tickets();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isNull();
    }

    @Test
    void notificationsReturnsEmptyWhenNone() {
        assertThat(ops.notifications(null)).isEmpty();
    }

    @Test
    void notificationsReturnsProjectedViews() {
        final var now = Instant.now();
        notifications.record("Alice", "Resolved", now);

        final var result = ops.notifications(null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().to()).isEqualTo("Alice");
        assertThat(result.getFirst().message()).isEqualTo("Resolved");
        assertThat(result.getFirst().sentAt()).isEqualTo(now);
    }

    @Test
    void notificationsRespectsLimit() {
        final var now = Instant.now();
        notifications.record("Alice", "msg1", now);
        notifications.record("Bob", "msg2", now);
        notifications.record("Carol", "msg3", now);

        assertThat(ops.notifications(2)).hasSize(2);
    }

    @Test
    void notificationsWithNullLimitReturnsAll() {
        final var now = Instant.now();
        notifications.record("Alice", "msg1", now);
        notifications.record("Bob", "msg2", now);

        assertThat(ops.notifications(null)).hasSize(2);
    }

    // --- helpers ---

    private static CaseInstance buildCase(final String caseDefName,
                                          final Map<String, Object> contextData) {
        final var instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        final var meta = new CaseMetaModel();
        meta.setName(caseDefName);
        instance.setCaseMetaModel(meta);
        instance.setCaseContext(new MapCaseContext(contextData));
        return instance;
    }

    // --- test doubles ---

    private static final class StubCaseInstanceCache implements CaseInstanceCache {
        private final List<CaseInstance> cases = new ArrayList<>();

        void add(final CaseInstance instance) { cases.add(instance); }
        @Override public void put(final CaseInstance instance) { cases.add(instance); }
        @Override public CaseInstance get(final UUID caseId) { return null; }
        @Override public void clear() { cases.clear(); }
        @Override public List<CaseInstance> getAll() { return List.copyOf(cases); }
    }

    static final class StubNotificationService extends io.casehub.examples.helpdesk.NotificationService {
        private final List<SentNotification> sent = new ArrayList<>();

        StubNotificationService() { super(null); }

        void record(final String to, final String message, final Instant sentAt) {
            sent.add(new SentNotification(to, message, sentAt));
        }

        void reset() { sent.clear(); }

        @Override
        public List<SentNotification> getSentNotifications() {
            return List.copyOf(sent);
        }
    }
}
