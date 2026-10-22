package io.casehub.examples.manor.agent;

import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.neocortex.cognition.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.session.SessionPipeline;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManorPlanRevisionStrategyTest {

    private ManorPlanRevisionStrategy strategy;
    private String lastPrompt;

    @BeforeEach
    void setUp() {
        var pipeline = StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                lastPrompt = prompt;
                String response = """
                    {"steps": [
                      {"id": "alt-route", "description": "Find another way to the kitchen"},
                      {"id": "take-poison", "description": "Pick up the poison"}
                    ], "rationale": "Door is locked, need alternative route"}
                    """;
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });
        strategy = new ManorPlanRevisionStrategy(pipeline);
    }

    @Test
    void revise_returns_revised_plan() {
        var pending = List.of(
                new PlanStepDescriptor("go-kitchen", "Go to the kitchen", ""),
                new PlanStepDescriptor("take-poison", "Pick up the poison", ""));
        var cause = new AdaptationCause.StepFailed("MOVE:kitchen", "The door is locked");
        var adaptCtx = new AdaptationContext(UUID.randomUUID(), "wacky-manor", "",
                "protect-penelope", List.of(), pending, List.of(),
                null, new CaseDefinition("manor", "wacky-manor", "1.0"), TaskStatus.COMPLETED, "", 0);
        var ctx = new RevisionContext(adaptCtx, cause, List.of(), List.of());

        RevisedPlan result = strategy.revise(ctx);

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).id()).isEqualTo("alt-route");
        assertThat(result.rationale()).contains("locked");
    }

    @Test
    void revise_prompt_includes_failure_context() {
        var pending = List.of(new PlanStepDescriptor("s1", "Do something", ""));
        var cause = new AdaptationCause.StepFailed("TAKE:poison", "Someone already took it");
        var adaptCtx = new AdaptationContext(UUID.randomUUID(), "wacky-manor", "",
                "eliminate", List.of(), pending, List.of(),
                null, new CaseDefinition("manor", "wacky-manor", "1.0"), TaskStatus.COMPLETED, "", 1);
        var ctx = new RevisionContext(adaptCtx, cause, List.of(), List.of());

        strategy.revise(ctx);

        assertThat(lastPrompt).contains("eliminate");
        assertThat(lastPrompt).contains("Someone already took it");
    }

    @Test
    void revise_returns_empty_on_malformed_response() {
        var badPipeline = StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta("not json"));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });
        var badStrategy = new ManorPlanRevisionStrategy(badPipeline);
        var adaptCtx = new AdaptationContext(UUID.randomUUID(), "wacky-manor", "",
                "goal", List.of(), List.of(), List.of(),
                null, new CaseDefinition("manor", "wacky-manor", "1.0"), TaskStatus.COMPLETED, "", 0);
        var cause = new AdaptationCause.StepCompleted("reflection", "", Map.of());
        var ctx = new RevisionContext(adaptCtx, cause, List.of(), List.of());

        RevisedPlan result = badStrategy.revise(ctx);

        assertThat(result.steps()).isEmpty();
    }

    @Test
    void id_returns_manor_llm() {
        assertThat(strategy.id()).isEqualTo("manor-llm");
    }
}
