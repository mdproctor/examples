package io.casehub.examples.helpdesk.mcp;

import io.casehub.connectors.InboundMessage;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.examples.helpdesk.engine.KeywordClassifierDispatcher;
import io.casehub.examples.helpdesk.engine.KeywordClassifierDispatcher.ClassificationEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@ApplicationScoped
public class HelpdeskOperationsImpl implements HelpdeskOperations {

    private final KeywordClassifierDispatcher classifier;
    private final CaseInstanceCache caseInstanceCache;
    private final NotificationService notificationService;
    private final Consumer<InboundMessage> messageSink;

    @Inject
    public HelpdeskOperationsImpl(final KeywordClassifierDispatcher classifier,
                                  final CaseInstanceCache caseInstanceCache,
                                  final NotificationService notificationService,
                                  final Event<InboundMessage> inboundEvent) {
        this(classifier, caseInstanceCache, notificationService,
                msg -> inboundEvent.fireAsync(msg));
    }

    HelpdeskOperationsImpl(final KeywordClassifierDispatcher classifier,
                           final CaseInstanceCache caseInstanceCache,
                           final NotificationService notificationService,
                           final Consumer<InboundMessage> messageSink) {
        this.classifier = classifier;
        this.caseInstanceCache = caseInstanceCache;
        this.notificationService = notificationService;
        this.messageSink = messageSink;
    }

    @Override
    public BootstrapResult bootstrapClassifications(final List<ClassificationInput> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        final var converted = entries.stream()
                .map(e -> new ClassificationEntry(e.match(), e.category(), e.priority()))
                .toList();
        classifier.loadClassifications(converted);
        return new BootstrapResult(true, converted.size());
    }

    @Override
    public InjectTicketResult injectTicket(final String from, final String channelId,
                                           final String text,
                                           final List<ClassificationInput> classifications) {
        if (classifications != null && !classifications.isEmpty()) {
            bootstrapClassifications(classifications);
        }
        final var msg = new InboundMessage(
                "ref", "ref", from, channelId, text,
                List.of(), Instant.now(), Map.of("source", "mcp-inject"), null);
        messageSink.accept(msg);
        return new InjectTicketResult(true, from, channelId);
    }

    @Override
    public List<TicketView> tickets() {
        return caseInstanceCache.getAll().stream()
                .filter(i -> "helpdesk-ticket".equals(
                        i.getCaseMetaModel() != null ? i.getCaseMetaModel().getName() : null))
                .map(instance -> {
                    final var ctx = instance.getCaseContext();
                    final Map<String, Object> data = ctx != null ? ctx.getData() : Map.of();
                    return new TicketView(
                            instance.getUuid() != null ? instance.getUuid().toString() : null,
                            str(data, "status"),
                            str(data, "category"),
                            str(data, "priority"),
                            str(data, "customerRef"),
                            str(data, "subject"),
                            str(data, "assigneeId"),
                            str(data, "resolution"),
                            parseInstant(data.get("createdAt")));
                })
                .toList();
    }

    @Override
    public List<NotificationView> notifications(final Integer limit) {
        final var all = notificationService.getSentNotifications();
        final var bounded = limit != null ? all.subList(0, Math.min(limit, all.size())) : all;
        return bounded.stream()
                .map(n -> new NotificationView(n.to(), n.message(), n.sentAt()))
                .toList();
    }

    private static String str(final Map<String, Object> data, final String key) {
        final var v = data.get(key);
        return v != null ? v.toString() : null;
    }

    private static Instant parseInstant(final Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof String s) {
            try { return Instant.parse(s); } catch (Exception e) { return null; }
        }
        return null;
    }
}
