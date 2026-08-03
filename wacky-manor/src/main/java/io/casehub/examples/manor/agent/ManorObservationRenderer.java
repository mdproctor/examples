package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;
import io.casehub.blocks.summarisation.observation.ObservationContext;
import io.casehub.blocks.summarisation.observation.ObservationRenderer;
import io.casehub.blocks.summarisation.observation.ObservationResult;
import io.casehub.blocks.summarisation.observation.ObservationTier;
import io.casehub.blocks.summarisation.observation.TieredObservationRenderer;
import io.casehub.examples.manor.model.ManorEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class ManorObservationRenderer implements ObservationRenderer<ManorEvent> {

    private final io.casehub.blocks.summarisation.Compactor<ManorEvent> compactor;
    private final TieredObservationRenderer<ManorEvent> delegate;

    public ManorObservationRenderer(io.casehub.blocks.summarisation.Compactor<ManorEvent> compactor,
                                     int verbatimThreshold,
                                     int groupedThreshold,
                                     Summariser<ManorEvent, String> summariser) {
        this.compactor = compactor;
        TieredObservationRenderer<ManorEvent> tier;
        if (summariser != null) {
            tier = new TieredObservationRenderer<>(
                    ManorEvent::description,
                    ManorEvent::type,
                    verbatimThreshold,
                    groupedThreshold,
                    summariser);
        } else {
            tier = new TieredObservationRenderer<>(
                    ManorEvent::description,
                    ManorEvent::type,
                    verbatimThreshold);
        }
        this.delegate = tier.withHeaderFormatter(ctx -> "");
    }

    @Override
    public CompletionStage<ObservationResult> render(
            List<LevelEvent<ManorEvent>> events, ObservationContext context) {
        if (events.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ObservationResult.empty(context.timeSinceLastDrain()));
        }
        List<LevelEvent<ManorEvent>> compacted = compactor.compact(events);
        return delegate.render(compacted, context)
                .exceptionally(ex -> renderFallback(compacted, context));
    }

    private ObservationResult renderFallback(
            List<LevelEvent<ManorEvent>> compacted, ObservationContext context) {
        String text = compacted.stream()
                .map(e -> e.payload().description())
                .collect(Collectors.joining("\n"));
        return new ObservationResult(text, List.of(), compacted.size(),
                context.timeSinceLastDrain(), ObservationTier.GROUPED);
    }
}
