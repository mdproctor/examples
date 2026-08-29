package io.casehub.examples.manor.agent;

import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManorGoalRevisionStrategyTest {

    @Test
    void revise_parses_revise_action() {
        String response = """
            {"revisions": [{"goalName": "protect-tea", "action": "REVISE",
              "revisedDescription": "Guard the tea service at all costs",
              "revisionReason": "Goal description too vague"}],
             "rationale": "Refinement needed"}
            """;
        var strategy = new ManorGoalRevisionStrategy(mockProvider(response));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).hasSize(1);
        assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REVISE);
        assertThat(proposal.revisions().get(0).revisedDescription()).isEqualTo("Guard the tea service at all costs");
    }

    @Test
    void revise_parses_abandon_action() {
        String response = """
            {"revisions": [{"goalName": "find-diamond", "action": "ABANDON",
              "revisedDescription": null,
              "revisionReason": "Diamond confirmed not in the manor"}],
             "rationale": "Goal unachievable"}
            """;
        var strategy = new ManorGoalRevisionStrategy(mockProvider(response));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).hasSize(1);
        assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.ABANDON);
    }

    @Test
    void revise_parses_complete_action() {
        String response = """
            {"revisions": [{"goalName": "poison-tea", "action": "COMPLETE",
              "revisedDescription": null,
              "revisionReason": "Successfully poisoned the tea"}],
             "rationale": "Goal achieved"}
            """;
        var strategy = new ManorGoalRevisionStrategy(mockProvider(response));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).hasSize(1);
        assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.COMPLETE);
    }

    @Test
    void revise_prompt_includes_goals_and_counts() {
        String[] capturedPrompt = {null};
        AgentProvider capturingProvider = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                capturedPrompt[0] = config.userPrompt();
                return Multi.createFrom().item(new AgentEvent.TextDelta(
                    "{\"revisions\": [], \"rationale\": \"\"}"));
            }
            @Override
            public AgentSession openSession(AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        var strategy = new ManorGoalRevisionStrategy(capturingProvider);
        var context = buildContext();
        strategy.revise(context);
        assertThat(capturedPrompt[0]).contains("protect-tea");
        assertThat(capturedPrompt[0]).contains("success: 3");
        assertThat(capturedPrompt[0]).contains("failure: 1");
    }

    @Test
    void revise_returns_empty_on_malformed_response() {
        var strategy = new ManorGoalRevisionStrategy(mockProvider("not json"));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).isEmpty();
    }

    @Test
    void id_returns_manor_llm() {
        var strategy = new ManorGoalRevisionStrategy(mockProvider("{}"));
        assertThat(strategy.id()).isEqualTo("manor-llm");
    }

    private GoalRevisionContext buildContext() {
        return new GoalRevisionContext("hc", "wacky-manor",
                List.of(new AgentGoal("protect-tea", "Prevent poisoning",
                        GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of())),
                Map.of("protect-tea", new GoalOutcomeCounts(3, 1)));
    }

    private AgentProvider mockProvider(String responseText) {
        return new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(responseText));
            }
            @Override
            public AgentSession openSession(AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
