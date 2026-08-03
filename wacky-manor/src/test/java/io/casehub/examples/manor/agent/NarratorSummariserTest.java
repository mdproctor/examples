package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NarratorSummariserTest {

    static final EventLevel NARRATOR = new EventLevel("narrator", 0);

    private static AgentProvider stubProvider(String response) {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AgentProvider capturingProvider(String response, AtomicReference<String> capturedPrompt) {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                capturedPrompt.set(config.userPrompt());
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AgentProvider failingProvider() {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                throw new RuntimeException("API timeout");
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void formats_events_by_room_and_calls_llm() {
        var capturedPrompt = new AtomicReference<String>();
        var summariser     = new NarratorSummariser(capturingProvider("DRAMATIC narration!", capturedPrompt));
        var events = List.of(
                new LevelEvent<>(new ManorEvent(Instant.now(), "action", "hooded-claw",
                                                "kitchen", "The Hooded Claw picked up the Rat Poison"),
                                 Instant.now().toEpochMilli(), NARRATOR),
                new LevelEvent<>(new ManorEvent(Instant.now(), "dialogue", "penelope",
                                                "ballroom", "Penelope Pitstop: \"Why, this is simply darlin'!\""),
                                 Instant.now().toEpochMilli(), NARRATOR)
                            );

        List<String> result = summariser.summarise(events).toCompletableFuture().join();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("DRAMATIC narration!");
        assertThat(capturedPrompt.get()).contains("[Kitchen]");
        assertThat(capturedPrompt.get()).contains("[Ballroom]");
        assertThat(capturedPrompt.get()).contains("Rat Poison");
    }

    @Test
    void null_room_grouped_under_general() {
        var summariser = new NarratorSummariser(stubProvider("narration"));
        var events = List.of(
                new LevelEvent<>(new ManorEvent(Instant.now(), "action", "x",
                                                null, "something happened"), Instant.now().toEpochMilli(), NARRATOR)
                            );

        List<String> result = summariser.summarise(events).toCompletableFuture().join();

        assertThat(result).hasSize(1);
    }

    @Test
    void llm_failure_returns_failed_future() {
        var summariser = new NarratorSummariser(failingProvider());
        var events = List.of(
                new LevelEvent<>(new ManorEvent(Instant.now(), "action", "x",
                                                "kitchen", "event"), Instant.now().toEpochMilli(), NARRATOR)
                            );

        var future = summariser.summarise(events).toCompletableFuture();
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void empty_batch_returns_empty_list() {
        AgentProvider neverCalledProvider = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                throw new AssertionError("Should not be called for empty batch");
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        var summariser = new NarratorSummariser(neverCalledProvider);

        List<String> result = summariser.summarise(List.of()).toCompletableFuture().join();

        assertThat(result).isEmpty();
    }
}
