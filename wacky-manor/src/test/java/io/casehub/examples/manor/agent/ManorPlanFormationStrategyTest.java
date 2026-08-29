package io.casehub.examples.manor.agent;

import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.examples.manor.model.AgentPlan;
import io.casehub.examples.manor.model.PlanStepStatus;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManorPlanFormationStrategyTest {

    private ManorPlanFormationStrategy strategy;
    private String lastPrompt;

    @BeforeEach
    void setUp() {
        AgentProvider mockProvider = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(io.casehub.platform.agent.AgentSessionConfig config) {
                lastPrompt = config.userPrompt();
                String response = """
                    {"steps": [
                      {"id": "find-poison", "description": "Locate the poison bottle in the kitchen"},
                      {"id": "take-poison", "description": "Pick up the poison"}
                    ], "rationale": "Need to remove the poison before HC uses it"}
                    """;
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        strategy = new ManorPlanFormationStrategy(mockProvider);
    }

    @Test
    void formPlan_returns_plan_with_steps() {
        var goal = new AgentGoal("protect-penelope", "Prevent poisoning",
                GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        AgentPlan plan = strategy.formPlan("pp", "wacky-manor", goal, List.of(goal), List.of(), 5);
        assertThat(plan.goalName()).isEqualTo("protect-penelope");
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).id()).isEqualTo("find-poison");
        assertThat(plan.steps().get(0).status()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(plan.rationale()).isEqualTo("Need to remove the poison before HC uses it");
        assertThat(plan.creationTick()).isEqualTo(5);
    }

    @Test
    void formPlan_prompt_includes_goal_and_context() {
        var goal = new AgentGoal("find-diamond", "Find the hidden diamond",
                GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        var otherGoal = new AgentGoal("protect-penelope", "Protect Penelope",
                GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        strategy.formPlan("pp", "wacky-manor", goal, List.of(goal, otherGoal), List.of(), 3);
        assertThat(lastPrompt).contains("find-diamond");
        assertThat(lastPrompt).contains("Find the hidden diamond");
        assertThat(lastPrompt).contains("protect-penelope");
    }

    @Test
    void formPlan_returns_null_on_malformed_response() {
        AgentProvider badProvider = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(io.casehub.platform.agent.AgentSessionConfig config) {
                return Multi.createFrom().item(new AgentEvent.TextDelta("not json"));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        var badStrategy = new ManorPlanFormationStrategy(badProvider);
        var goal = new AgentGoal("g", "desc", GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of());
        AgentPlan plan = badStrategy.formPlan("id", "t", goal, List.of(), List.of(), 1);
        assertThat(plan).isNull();
    }
}
