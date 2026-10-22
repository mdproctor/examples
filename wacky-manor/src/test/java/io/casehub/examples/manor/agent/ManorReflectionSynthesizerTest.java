package io.casehub.examples.manor.agent;

import io.casehub.neocortex.cognition.testing.StubPipelines;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.reflection.ReflectionEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.session.SessionPipeline;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManorReflectionSynthesizerTest {

    @Test
    void synthesizesInsightsFromMemories() {
        String llmResponse = """
            [
              {"insight": "Sneekly is always near dangerous items", "importance": 0.8},
              {"insight": "Penelope trusts Sneekly too easily", "importance": 0.7}
            ]
            """;
        var synthesizer = new ManorReflectionSynthesizer(mockPipeline(llmResponse));

        var memories = List.of(
            new Memory("m1", "agent-1", new MemoryDomain("manor"), "t1",
                null, "Sneekly picked up rat poison", Map.of(), Instant.now(), null, null, null, null),
            new Memory("m2", "agent-1", new MemoryDomain("manor"), "t1",
                null, "Sneekly offered Penelope tea", Map.of(), Instant.now(), null, null, null, null)
        );

        List<ReflectionEvent> results = synthesizer.synthesize("agent-1", "t1", memories, 1);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).insight()).isEqualTo("Sneekly is always near dangerous items");
        assertThat(results.get(0).confidence()).isEqualTo(0.8);
        assertThat(results.get(0).agentId()).isEqualTo("agent-1");
        assertThat(results.get(0).tenantId()).isEqualTo("t1");
        assertThat(results.get(0).level()).isEqualTo(1);
        assertThat(results.get(1).insight()).isEqualTo("Penelope trusts Sneekly too easily");
    }

    @Test
    void returnsEmptyOnLlmFailure() {
        var synthesizer = new ManorReflectionSynthesizer(failingPipeline());

        var memories = List.of(
            new Memory("m1", "agent-1", new MemoryDomain("manor"), "t1",
                null, "test memory", Map.of(), Instant.now(), null, null, null, null)
        );

        List<ReflectionEvent> results = synthesizer.synthesize("agent-1", "t1", memories, 1);

        assertThat(results).isEmpty();
    }

    @Test
    void returnsEmptyForEmptySources() {
        var synthesizer = new ManorReflectionSynthesizer(mockPipeline("[]"));

        List<ReflectionEvent> results = synthesizer.synthesize("agent-1", "t1", List.of(), 1);

        assertThat(results).isEmpty();
    }

    @Test
    void defaultsImportanceWhenNull() {
        String llmResponse = """
            [{"insight": "something interesting", "importance": null}]
            """;
        var synthesizer = new ManorReflectionSynthesizer(mockPipeline(llmResponse));

        var memories = List.of(
            new Memory("m1", "a1", new MemoryDomain("manor"), "t1",
                null, "test", Map.of(), Instant.now(), null, null, null, null)
        );

        List<ReflectionEvent> results = synthesizer.synthesize("a1", "t1", memories, 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).confidence()).isEqualTo(0.7);
    }

    private SessionPipeline mockPipeline(String responseText) {
        return StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(responseText));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(Duration timeout) {}
        });
    }

    private SessionPipeline failingPipeline() {
        return StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().failure(new RuntimeException("LLM unavailable"));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(Duration timeout) {}
        });
    }
}
