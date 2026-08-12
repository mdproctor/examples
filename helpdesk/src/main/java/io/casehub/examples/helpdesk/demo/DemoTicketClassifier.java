package io.casehub.examples.helpdesk.demo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.arc.profile.IfBuildProfile;

import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;
import io.casehub.examples.helpdesk.spi.Classification;
import io.casehub.examples.helpdesk.spi.TicketClassifier;

@ApplicationScoped
@Alternative
@Priority(300)
@IfBuildProfile("demo")
public class DemoTicketClassifier implements TicketClassifier {

    private final List<ClassificationEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public Classification classify(String subject, String description) {
        var combined = (subject + " " + description).toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> combined.contains(e.match().toLowerCase(Locale.ROOT)))
                .findFirst()
                .map(e -> new Classification(e.category(), e.priority()))
                .orElse(new Classification(TicketCategory.OTHER, TicketPriority.MEDIUM));
    }

    public void loadClassifications(List<ClassificationEntry> data) {
        entries.clear();
        entries.addAll(data);
    }

    public record ClassificationEntry(String match, TicketCategory category, TicketPriority priority) {}
}
