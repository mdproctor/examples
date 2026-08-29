package io.casehub.examples.manor.voice;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.examples.manor.agent.ManorPlanFormationStrategy;
import io.casehub.examples.manor.agent.ManorPlanRevisionStrategy;
import io.casehub.examples.manor.model.PlanStepStatus;
import io.casehub.platform.agent.AgentProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("llm-eval")
class PlanLifecycleTest {

    @Inject AgentProvider agentProvider;

    @Test
    void formation_produces_actionable_steps_for_goal() {
        var strategy = new ManorPlanFormationStrategy(agentProvider);
        var goal = new AgentGoal("poison-tea", "Poison Penelope's tea before she drinks it",
                GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        var plan = strategy.formPlan("hooded-claw", "wacky-manor", goal,
                List.of(goal), List.of(), 1);

        System.out.println("[Plan formation] " + plan);

        assertThat(plan)
                .as("LLM should produce a plan for a concrete goal")
                .isNotNull();
        assertThat(plan.steps())
                .as("Plan should have 2-5 actionable steps")
                .hasSizeBetween(2, 5);
        assertThat(plan.steps().get(0).id()).isNotBlank();
        assertThat(plan.steps().get(0).description()).isNotBlank();
        assertThat(plan.steps()).allSatisfy(step ->
                assertThat(step.status()).isEqualTo(PlanStepStatus.PENDING));
        assertThat(plan.rationale()).isNotBlank();
    }

    @Test
    void revision_adapts_plan_to_action_failure() {
        var strategy = new ManorPlanRevisionStrategy(agentProvider);
        var pending = List.of(
                new PlanStepDescriptor("go-kitchen", "Go to the kitchen to find the poison", ""),
                new PlanStepDescriptor("take-poison", "Pick up the rat poison", ""),
                new PlanStepDescriptor("put-in-tea", "Put the poison in Penelope's tea cup", ""));
        var cause = new AdaptationCause.StepFailed("MOVE:kitchen", "The kitchen door is locked from the inside");
        var adaptCtx = new AdaptationContext(UUID.randomUUID(), "wacky-manor", "",
                "poison-tea", List.of(), pending, List.of(),
                null, new CaseDefinition("manor", "wacky-manor", "1.0"),
                TaskStatus.COMPLETED, "", 0);
        var ctx = new RevisionContext(adaptCtx, cause, List.of(), List.of());

        var revised = strategy.revise(ctx);

        System.out.println("[Plan revision] " + revised);

        assertThat(revised.steps())
                .as("LLM should propose revised steps that address the locked door")
                .isNotEmpty();
        assertThat(revised.rationale())
                .as("Rationale should reference the failure")
                .isNotBlank();
    }
}
