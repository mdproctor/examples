package io.casehub.examples.helpdesk.engine;

import io.casehub.engine.flow.CallableDispatcher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class KeywordClassifierDispatcher implements CallableDispatcher {

    private final List<ClassificationEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Map<String, Object>> dispatch(String workflowInstanceId,
                                                            Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", "")).toLowerCase(Locale.ROOT);
        var match = entries.stream()
                .filter(e -> text.contains(e.match().toLowerCase(Locale.ROOT)))
                .findFirst();
        String category = match.map(ClassificationEntry::category).orElse("OTHER");
        String priority = match.map(ClassificationEntry::priority).orElse("MEDIUM");
        return CompletableFuture.completedFuture(Map.of("category", category, "priority", priority));
    }

    public void loadClassifications(List<ClassificationEntry> data) {
        entries.clear();
        entries.addAll(data);
    }

    public record ClassificationEntry(String match, String category, String priority) {}
}
