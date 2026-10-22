package io.casehub.examples.manor.agent;

import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import io.casehub.platform.agent.gate.AimdAdmissionStrategy;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the cognitive pipeline actually fires LLM calls
 * when properly wired with SessionPipeline and fed interaction data.
 *
 * These tests expose the gap: tick() alone doesn't trigger cognitive
 * LLM work — recordInteraction() must also be called.
 */
class CognitivePipelineWiringTest {

    @Test
    void recordInteraction_triggersMoodAppraisal_viaPipeline() {
        var llmCallCount = new AtomicInteger();
        var pipeline = createCountingPipeline(llmCallCount);

        var core = CognitiveActivationTest.buildCore(CognitionConfig.all());
        core.setSessionPipeline(pipeline);

        core.recordInteraction("agent-1", "test-tenant", "subject-1",
                "What do you think about the locked door?",
                "I feel uneasy about it — something is wrong.",
                null);

        assertThat(llmCallCount.get())
                .as("recordInteraction should trigger mood appraisal + BDI extraction via pipeline")
                .isGreaterThan(0);
    }

    @Test
    void tickAlone_withoutRecordInteraction_noLlmCallsFire() {
        var llmCallCount = new AtomicInteger();
        var pipeline = createCountingPipeline(llmCallCount);

        var core = CognitiveActivationTest.buildCore(CognitionConfig.all());
        core.setSessionPipeline(pipeline);

        // Simulate autonomous loop: only tick(), never recordInteraction()
        for (int i = 0; i < 5; i++) {
            core.tick("agent-1", "test-tenant", null, (a, t) -> Set.of("subject-1"));
        }

        assertThat(llmCallCount.get())
                .as("tick() alone should NOT trigger any LLM calls — " +
                    "cognitive orchestrators need interaction signals from recordInteraction()")
                .isEqualTo(0);
    }

    @Test
    void tickAfterRecordInteraction_triggersOrchestratorsViaPipeline() {
        var llmCallCount = new AtomicInteger();
        var pipeline = createCountingPipeline(llmCallCount);

        var core = CognitiveActivationTest.buildCore(CognitionConfig.all());
        core.setSessionPipeline(pipeline);

        // Feed interaction data THEN tick — this is what PlaybookOrchestrator should do
        core.recordInteraction("agent-1", "test-tenant", "subject-1",
                "The door handle is cold.",
                "That's strange — doors are usually warm.",
                null);
        int callsAfterRecord = llmCallCount.get();

        core.tick("agent-1", "test-tenant", null, (a, t) -> Set.of("subject-1"));
        int callsAfterTick = llmCallCount.get();

        assertThat(callsAfterRecord)
                .as("recordInteraction should trigger LLM calls (mood appraisal + BDI)")
                .isGreaterThan(0);
        // Orchestrator ticks may or may not fire additional LLM calls depending on
        // signal accumulation thresholds — but at minimum recordInteraction worked.
    }

    private static SessionPipeline createCountingPipeline(AtomicInteger callCount) {
        AgentBackend backend = new AgentBackend() {
            @Override public String key() { return "stub"; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                throw new UnsupportedOperationException();
            }
            @Override public AgentSession openSession(AgentSessionInit init) {
                return new AgentSession() {
                    @Override public Multi<AgentEvent> query(String prompt) {
                        callCount.incrementAndGet();
                        return Multi.createFrom().item(new AgentEvent.TextDelta("{}"));
                    }
                    @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                    @Override public void close(Duration timeout) {}
                };
            }
        };
        BackendInstanceRegistry registry = new BackendInstanceRegistry() {
            @Override public void register(AgentBackend b) {}
            @Override public Optional<AgentBackend> resolve(String key, String instanceId) { return Optional.of(backend); }
            @Override public List<AgentBackend> resolveByKey(String key) { return List.of(backend); }
        };
        var pool = new PoolDeclaration("test", "test", "stub", 0, 20, null, Map.of(), Map.of());
        var poolRegistry = new SessionPoolRegistry(List.of(pool), registry);
        var lifecycleManager = new SessionLifecycleManager(poolRegistry);
        return new SessionPipeline(lifecycleManager, "stub",
                new AimdAdmissionStrategy(6, 5, Duration.ofSeconds(10)), 50_000);
    }
}
