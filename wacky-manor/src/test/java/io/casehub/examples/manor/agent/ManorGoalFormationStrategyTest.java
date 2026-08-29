package io.casehub.examples.manor.agent;

import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManorGoalFormationStrategyTest {

    private ManorGoalFormationStrategy strategy;
    private String                     lastUserPrompt;

    @BeforeEach
    void setUp() {
        AgentProvider mockProvider = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(io.casehub.platform.agent.AgentSessionConfig config) {
                lastUserPrompt = config.userPrompt();
                String response = """
                                  {"goals": [{"name": "protect-tea", "description": "Prevent poisoning",
                                    "suggestedPriority": "SECONDARY", "formationReason": "Observed suspicious behavior"}],
                                   "rationale": "Agent noticed danger signs"}
                                  """;
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        strategy = new ManorGoalFormationStrategy(mockProvider);
    }

    @Test
    void propose_returns_goals_from_llm_response() {
        var context = new GoalFormationContext("hc", "wacky-manor",
                                               List.of("Sneekly is acting suspiciously"),
                                               List.of(new AgentGoal("find-diamond", "Find the diamond",
                                                                     GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())),
                                               List.of(), 5);
        GoalFormationProposal proposal = strategy.propose(context);
        assertThat(proposal.goals()).hasSize(1);
        assertThat(proposal.goals().get(0).name()).isEqualTo("protect-tea");
        assertThat(proposal.goals().get(0).description()).isEqualTo("Prevent poisoning");
        assertThat(proposal.goals().get(0).suggestedPriority()).isEqualTo(GoalPriority.SECONDARY);
        assertThat(proposal.goals().get(0).formationReason()).isEqualTo("Observed suspicious behavior");
    }

    @Test
    void propose_prompt_includes_insights_and_goals() {
        var context = new GoalFormationContext("hc", "wacky-manor",
                                               List.of("HC spotted poison"),
                                               List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                     GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())),
                                               List.of(), 3);
        strategy.propose(context);
        assertThat(lastUserPrompt).contains("HC spotted poison");
        assertThat(lastUserPrompt).contains("eliminate");
        assertThat(lastUserPrompt).contains("Remaining goal capacity: 3");
    }

    @Test
    void propose_returns_empty_on_malformed_response() {
        var badStrategy = new ManorGoalFormationStrategy(mockProvider("not json at all"));
        var context = new GoalFormationContext("hc", "wacky-manor",
                                               List.of("insight"), List.of(), List.of(), 5);
        GoalFormationProposal proposal = badStrategy.propose(context);
        assertThat(proposal.goals()).isEmpty();
    }

    @Test
    void propose_handles_null_priority_gracefully() {
        String response = """
                          {"goals": [{"name": "explore", "description": "Look around",
                            "suggestedPriority": null, "formationReason": "Curiosity"}],
                           "rationale": ""}
                          """;
        var s = new ManorGoalFormationStrategy(mockProvider(response));
        var context = new GoalFormationContext("hc", "wacky-manor",
                                               List.of("insight"), List.of(), List.of(), 5);
        GoalFormationProposal proposal = s.propose(context);
        assertThat(proposal.goals()).hasSize(1);
        assertThat(proposal.goals().get(0).suggestedPriority()).isNull();
    }

    @Test
    void id_returns_manor_llm() {
        assertThat(strategy.id()).isEqualTo("manor-llm");
    }

    private AgentProvider mockProvider(String responseText) {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(io.casehub.platform.agent.AgentSessionConfig config) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(responseText));
            }

            @Override
            public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
