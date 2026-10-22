package io.casehub.examples.manor.agent;

import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.cognition.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.session.SessionPipeline;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        var strategy = new ManorGoalRevisionStrategy(mockPipeline(response));
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
        var strategy = new ManorGoalRevisionStrategy(mockPipeline(response));
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
        var strategy = new ManorGoalRevisionStrategy(mockPipeline(response));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).hasSize(1);
        assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.COMPLETE);
    }

    @Test
    void revise_prompt_includes_goals_and_counts() {
        String[] capturedPrompt = {null};
        var capturingPipeline = StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedPrompt[0] = prompt;
                return Multi.createFrom().item(new AgentEvent.TextDelta(
                    "{\"revisions\": [], \"rationale\": \"\"}"));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });
        var strategy = new ManorGoalRevisionStrategy(capturingPipeline);
        var context = buildContext();
        strategy.revise(context);
        assertThat(capturedPrompt[0]).contains("protect-tea");
        assertThat(capturedPrompt[0]).contains("success: 3");
        assertThat(capturedPrompt[0]).contains("failure: 1");
    }

    @Test
    void revise_returns_empty_on_malformed_response() {
        var strategy = new ManorGoalRevisionStrategy(mockPipeline("not json"));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).isEmpty();
    }

    @Test
    void id_returns_manor_llm() {
        var strategy = new ManorGoalRevisionStrategy(mockPipeline("{}"));
        assertThat(strategy.id()).isEqualTo("manor-llm");
    }

    @Test
    void revise_parses_reprioritize_action() {
        String response = """
            {"revisions": [{"goalName": "protect-tea", "action": "REPRIORITIZE",
              "revisedDescription": null, "newPriority": 0.9,
              "revisionReason": "Tasks neglected"}],
             "rationale": "Needs-based reprioritization"}
            """;
        var strategy = new ManorGoalRevisionStrategy(mockPipeline(response));
        var context = buildContext();
        GoalRevisionProposal proposal = strategy.revise(context);
        assertThat(proposal.revisions()).hasSize(1);
        assertThat(proposal.revisions().get(0).action()).isEqualTo(GoalRevisionAction.REPRIORITIZE);
        assertThat(proposal.revisions().get(0).newPriority()).isEqualTo(0.9);
    }

    @Test
    void revise_prompt_includes_satisfaction_levels() {
        String[] capturedPrompt = {null};
        var capturingPipeline = StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedPrompt[0] = prompt;
                return Multi.createFrom().item(new AgentEvent.TextDelta(
                    "{\"revisions\": [], \"rationale\": \"\"}"));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });

        var store = new InMemoryMindMapStore();
        var subgraphId = store.createSubgraph(
            new SubgraphInput("beliefs-hc", "cognitive", null), "wacky-manor");
        store.addNode(NodeInput.of("need-safety", subgraphId)
            .withConfidence(Confidence.stated(0.8, Instant.now()))
            .withProvenance("need-satisfaction")
            .withProperties(Map.of(
                "cognitiveKind", "need-satisfaction",
                "agent-id", "hc",
                "tier", "SAFETY",
                "satisfaction", "0.62")),
            "wacky-manor");
        store.addNode(NodeInput.of("need-tasks", subgraphId)
            .withConfidence(Confidence.stated(0.8, Instant.now()))
            .withProvenance("need-satisfaction")
            .withProperties(Map.of(
                "cognitiveKind", "need-satisfaction",
                "agent-id", "hc",
                "tier", "TASKS",
                "satisfaction", "0.28")),
            "wacky-manor");

        var strategy = new ManorGoalRevisionStrategy(capturingPipeline, store);
        strategy.revise(buildContext());

        assertThat(capturedPrompt[0]).contains("SAFETY=0.62");
        assertThat(capturedPrompt[0]).contains("TASKS=0.28");
        assertThat(capturedPrompt[0]).contains("Need satisfaction");
    }

    @Test
    void revise_prompt_omits_satisfaction_when_no_store() {
        String[] capturedPrompt = {null};
        var capturingPipeline = StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedPrompt[0] = prompt;
                return Multi.createFrom().item(new AgentEvent.TextDelta(
                    "{\"revisions\": [], \"rationale\": \"\"}"));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });

        var strategy = new ManorGoalRevisionStrategy(capturingPipeline, null);
        strategy.revise(buildContext());

        assertThat(capturedPrompt[0]).doesNotContain("Need satisfaction");
    }

    private GoalRevisionContext buildContext() {
        return new GoalRevisionContext("hc", "wacky-manor",
                List.of(new AgentGoal("protect-tea", "Prevent poisoning",
                        GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), java.util.Map.of())),
                Map.of("protect-tea", new GoalOutcomeCounts(3, 1)));
    }

    private SessionPipeline mockPipeline(String responseText) {
        return StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(responseText));
            }
            @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                return io.smallrye.mutiny.Uni.createFrom().voidItem();
            }
            @Override public void close(java.time.Duration timeout) {}
        });
    }
}
