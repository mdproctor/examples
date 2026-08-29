package io.casehub.examples.manor.agent;

import io.casehub.api.model.RetrievedMemory;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.examples.manor.model.ActionResult;
import io.casehub.examples.manor.model.AgentPlan;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.PlanStep;
import io.casehub.examples.manor.model.PlanStepStatus;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ManorPlanEvaluatorTest {

    private ManorPlanEvaluator evaluator;
    private CharacterState character;
    private AgentPlan nextFormationResult;
    private RevisedPlan nextRevisionResult;

    @BeforeEach
    void setUp() {
        character = new CharacterState("hc", "Hooded Claw", "ballroom", 0, List.of());

        nextFormationResult = new AgentPlan("goal-a",
                List.of(new PlanStep("s1", "Step 1", PlanStepStatus.PENDING)),
                "rationale", 1, 1, 0);

        nextRevisionResult = new RevisedPlan(
                List.of(new PlanStepDescriptor("s2", "Revised step", "")),
                "revised because failure");

        ManorPlanFormationStrategy formationStrategy = new ManorPlanFormationStrategy(null) {
            @Override
            public AgentPlan formPlan(String agentId, String tenancyId, AgentGoal goal,
                    List<AgentGoal> allGoals, List<RetrievedMemory> memories, int tick) {
                return nextFormationResult;
            }
        };

        ManorPlanRevisionStrategy revisionStrategy = new ManorPlanRevisionStrategy(null) {
            @Override
            public RevisedPlan revise(RevisionContext ctx) {
                return nextRevisionResult;
            }
        };

        CaseMemoryStore memoryStore = new CaseMemoryStore() {
            @Override
            public java.util.List<io.casehub.neocortex.memory.Memory> query(MemoryQuery q) {
                return List.of();
            }

            @Override
            public String store(io.casehub.neocortex.memory.MemoryInput input) {
                return "mem-1";
            }

            @Override
            public int erase(io.casehub.neocortex.memory.EraseRequest request) {
                return 0;
            }
        };

        Function<String, CharacterState> lookup = id -> character;

        evaluator = new ManorPlanEvaluator(formationStrategy, revisionStrategy,
                memoryStore, "wacky-manor", lookup, 5);
    }

    @Test
    void formPlanForGoal_stores_plan_on_character() {
        var goal = new AgentGoal("goal-a", "Do something",
                GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        evaluator.formPlanForGoal("hc", goal, List.of(goal), 1);
        assertThat(character.plans()).containsKey("goal-a");
        assertThat(character.plans().get("goal-a").steps()).hasSize(1);
    }

    @Test
    void removePlanForGoal_removes_plan() {
        character.setPlan("goal-a", nextFormationResult);
        evaluator.removePlanForGoal("hc", "goal-a");
        assertThat(character.plans()).isEmpty();
    }

    @Test
    void reviseOnFailure_updates_plan() {
        character.setPlan("goal-a", nextFormationResult);
        var failure = new ActionResult.Failed("The door is locked");
        evaluator.reviseOnFailure("hc", "MOVE", "kitchen", failure, 5);
        var plan = character.plans().get("goal-a");
        assertThat(plan.steps().get(0).id()).isEqualTo("s2");
        assertThat(plan.revisionGeneration()).isEqualTo(1);
    }

    @Test
    void reviseOnFailure_skips_when_no_plans() {
        var failure = new ActionResult.Failed("Something failed");
        evaluator.reviseOnFailure("hc", "TAKE", "poison", failure, 5);
        assertThat(character.plans()).isEmpty();
    }

    @Test
    void reviseOnFailure_respects_max_generation() {
        var maxedPlan = new AgentPlan("goal-a",
                List.of(new PlanStep("s1", "Step 1", PlanStepStatus.PENDING)),
                "r", 1, 4, 5);
        character.setPlan("goal-a", maxedPlan);
        var failure = new ActionResult.Failed("fail");
        evaluator.reviseOnFailure("hc", "MOVE", "library", failure, 6);
        assertThat(character.plans().get("goal-a").revisionGeneration()).isEqualTo(5);
    }

    @Test
    void reviseOnReflection_revises_plans() {
        character.setPlan("goal-a", nextFormationResult);
        evaluator.reviseOnReflection("hc", List.of("something changed"), 10);
        var plan = character.plans().get("goal-a");
        assertThat(plan.steps().get(0).id()).isEqualTo("s2");
        assertThat(plan.revisionGeneration()).isEqualTo(1);
    }

    @Test
    void formPlanForGoal_does_not_store_null_plan() {
        nextFormationResult = null;
        var goal = new AgentGoal("goal-b", "Bad goal",
                GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        evaluator.formPlanForGoal("hc", goal, List.of(goal), 1);
        assertThat(character.plans()).isEmpty();
    }
}
