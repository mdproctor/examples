package io.casehub.examples.manor.voice;

import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.examples.manor.agent.ManorGoalFormationStrategy;
import io.casehub.examples.manor.agent.ManorGoalRevisionStrategy;
import io.casehub.platform.agent.AgentProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("llm-eval")
class GoalLifecycleTest {

    @Inject AgentProvider agentProvider;

    @Test
    void formation_proposes_goal_from_reflection_insights() {
        var strategy = new ManorGoalFormationStrategy(agentProvider);
        var context = new GoalFormationContext("hooded-claw", "wacky-manor",
                List.of("Penelope is alone in the ballroom near the tea service",
                        "The rat poison is within reach in the kitchen",
                        "Nobody is watching the kitchen right now"),
                List.of(new AgentGoal("eliminate-penelope", "Eliminate Penelope before dawn",
                        GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())),
                List.of(), 5);
        GoalFormationProposal proposal = strategy.propose(context);
        System.out.println("[Goal formation] " + proposal);
        assertThat(proposal.goals())
                .as("LLM should propose at least one new goal from reflection insights")
                .isNotEmpty();
        assertThat(proposal.goals().get(0).name()).isNotBlank();
        assertThat(proposal.goals().get(0).description()).isNotBlank();
        assertThat(proposal.goals().get(0).formationReason()).isNotBlank();
    }

    @Test
    void revision_recommends_abandon_for_impossible_goal() {
        var strategy = new ManorGoalRevisionStrategy(agentProvider);
        var context = new GoalRevisionContext("hooded-claw", "wacky-manor",
                List.of(new AgentGoal("steal-diamond", "Steal the Doily Diamond from the safe",
                        GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of())),
                Map.of("steal-diamond", new GoalOutcomeCounts(0, 8)));
        GoalRevisionProposal proposal = strategy.revise(context);
        System.out.println("[Goal revision — abandon] " + proposal);
        assertThat(proposal.revisions())
                .as("LLM should recommend action for a goal with 0% success rate")
                .isNotEmpty();
        var revision = proposal.revisions().get(0);
        assertThat(revision.action())
                .as("A goal with 0 successes and 8 failures should be abandoned")
                .isEqualTo(GoalRevisionAction.ABANDON);
    }

    @Test
    void revision_recommends_complete_for_achieved_goal() {
        var strategy = new ManorGoalRevisionStrategy(agentProvider);
        var context = new GoalRevisionContext("hooded-claw", "wacky-manor",
                List.of(new AgentGoal("poison-tea", "Poison Penelope's tea",
                        GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of())),
                Map.of("poison-tea", new GoalOutcomeCounts(5, 0)));
        GoalRevisionProposal proposal = strategy.revise(context);
        System.out.println("[Goal revision — complete] " + proposal);
        assertThat(proposal.revisions())
                .as("LLM should recommend action for a goal with 100% success rate")
                .isNotEmpty();
        var revision = proposal.revisions().get(0);
        assertThat(revision.action())
                .as("A goal with 5 successes and 0 failures should be completed")
                .isEqualTo(GoalRevisionAction.COMPLETE);
    }
}
