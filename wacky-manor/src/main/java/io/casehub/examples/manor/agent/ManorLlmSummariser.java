package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class ManorLlmSummariser implements Summariser<ManorEvent, String> {

    private static final Logger log = Logger.getLogger(ManorLlmSummariser.class);

    private static final String SYSTEM_PROMPT = """
            You are a concise event summariser for a cartoon mansion game.
            Summarise the following events into a brief narrative paragraph.
            Preserve all character names, item names, and factual details.
            Compress dialogue exchanges into summaries.
            Do not invent events that did not happen.
            Respond with ONLY the summary text, no formatting.""";

    private final SessionPipeline sessionPipeline;

    public ManorLlmSummariser(SessionPipeline sessionPipeline) {
        this.sessionPipeline = sessionPipeline;
    }

    @Override
    public CompletionStage<List<String>> summarise(List<LevelEvent<ManorEvent>> batch) {
        String eventText = batch.stream()
                .map(e -> e.payload().description())
                .collect(Collectors.joining("\n"));
        try {
            var session = sessionPipeline.openSession("summariser", AgentSessionInit.of(SYSTEM_PROMPT));
            var responseBuilder = new StringBuilder();
            session.query(eventText).subscribe().asStream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .forEach(responseBuilder::append);
            String summary = responseBuilder.toString();
            return CompletableFuture.completedFuture(List.of(summary));
        } catch (Exception e) {
            log.warnf("LLM summarisation failed, falling back to raw text: %s", e.getMessage());
            return CompletableFuture.completedFuture(
                    batch.stream().map(ev -> ev.payload().description()).toList());
        }
    }
}
