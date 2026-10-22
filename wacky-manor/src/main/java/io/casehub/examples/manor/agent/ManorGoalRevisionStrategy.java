package io.casehub.examples.manor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.api.spi.routing.GoalRevisionStrategy;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ManorGoalRevisionStrategy implements GoalRevisionStrategy {

    private static final Logger log = Logger.getLogger(ManorGoalRevisionStrategy.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are a goal effectiveness analyst for an autonomous agent. Given the \
        agent's goals, their performance metrics, and their current need \
        satisfaction levels, evaluate each goal and recommend an action:
        - REVISE: refine the goal description (provide revisedDescription)
        - ABANDON: drop the goal — unachievable or no longer relevant
        - COMPLETE: the goal has been achieved
        - REPRIORITIZE: change goal priority to address unmet needs \
          (provide newPriority as a number 0.0–1.0, where higher = more urgent)
        Goals addressing neglected needs (low satisfaction) should be elevated. \
        Goals addressing well-met needs may be deprioritized. Only act on goals \
        with clear signals. If a goal is fine as-is, omit it from revisions.
        Return ONLY a JSON object: {"revisions": [{"goalName": "...", \
        "action": "REVISE"|"ABANDON"|"COMPLETE"|"REPRIORITIZE", \
        "revisedDescription": "..."|null, "newPriority": <number>|null, \
        "revisionReason": "..."}], "rationale": "..."}""";

    private final SessionPipeline sessionPipeline;
    private final MindMapStore mindMapStore;

    public ManorGoalRevisionStrategy(SessionPipeline sessionPipeline) {
        this(sessionPipeline, null);
    }

    @Inject
    public ManorGoalRevisionStrategy(SessionPipeline sessionPipeline,
                                      @Nullable MindMapStore mindMapStore) {
        this.sessionPipeline = sessionPipeline;
        this.mindMapStore = mindMapStore;
    }

    @Override
    public String id() { return "manor-llm"; }

    @Override
    public GoalRevisionProposal revise(GoalRevisionContext context) {
        try {
            String userPrompt = buildPrompt(context);
            var session = sessionPipeline.openSession("goal-revision", AgentSessionInit.of(SYSTEM_PROMPT));
            var responseBuilder = new StringBuilder();
            session.query(userPrompt).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseBuilder::append);
            String response = responseBuilder.toString();
            return parseResponse(response);
        } catch (Exception e) {
            log.warnf("Goal revision failed (non-fatal): %s", e.getMessage());
            return new GoalRevisionProposal(List.of(), "");
        }
    }

    private String buildPrompt(GoalRevisionContext context) {
        var sb = new StringBuilder();
        sb.append("Agent: ").append(context.agentId()).append("\n\nGoals:\n");
        for (AgentGoal goal : context.goals()) {
            GoalOutcomeCounts counts = context.counts().get(goal.name());
            sb.append("- ").append(goal.name()).append(": ").append(goal.description());
            sb.append(" (priority: ").append(goal.priority());
            if (counts != null) {
                sb.append(String.format(", success: %d, failure: %d, rate: %.0f%%",
                        counts.successCount(), counts.failureCount(), counts.successRate() * 100));
            }
            sb.append(")\n");
        }
        String satisfaction = readSatisfaction(context.agentId(), context.tenancyId());
        if (!satisfaction.isEmpty()) {
            sb.append(satisfaction);
            sb.append("\nConsider these satisfaction levels when evaluating goals. ");
            sb.append("Goals addressing neglected needs (low satisfaction) should be elevated.\n");
        }
        sb.append("\nRespond with JSON only.");
        return sb.toString();
    }

    private String readSatisfaction(String agentId, String tenantId) {
        if (mindMapStore == null) return "";
        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var sb = new StringBuilder();
        for (var sg : subgraphs) {
            if (!"cognitive".equals(sg.type())) continue;
            for (var node : mindMapStore.nodesIn(sg.id(), tenantId)) {
                if (!"need-satisfaction".equals(node.properties().get("cognitiveKind"))) continue;
                if (!agentId.equals(node.properties().get("agent-id"))) continue;
                var tier = node.properties().get("tier");
                var sat = node.properties().get("satisfaction");
                if (tier != null && sat != null) {
                    if (sb.isEmpty()) sb.append("\nNeed satisfaction levels:\n  ");
                    else sb.append(", ");
                    sb.append(tier).append("=").append(String.format("%.2f", Double.parseDouble(sat)));
                }
            }
        }
        return sb.toString();
    }

    private GoalRevisionProposal parseResponse(String response) {
        try {
            JsonNode root = JSON.readTree(response);
            JsonNode revisionsNode = root.get("revisions");
            String rationale = root.has("rationale") ? root.get("rationale").asText() : "";
            List<GoalRevisionProposal.RevisedGoal> revisions = new ArrayList<>();
            if (revisionsNode != null && revisionsNode.isArray()) {
                for (JsonNode node : revisionsNode) {
                    String goalName = node.get("goalName").asText();
                    GoalRevisionAction action = GoalRevisionAction.valueOf(
                            node.get("action").asText());
                    String desc = node.has("revisedDescription") && !node.get("revisedDescription").isNull()
                            ? node.get("revisedDescription").asText() : null;
                    String reason = node.get("revisionReason").asText();
                    Double priority = null;
                    if (node.has("newPriority") && !node.get("newPriority").isNull()) {
                        priority = node.get("newPriority").asDouble();
                    }
                    revisions.add(new GoalRevisionProposal.RevisedGoal(
                            goalName, action, desc, reason, priority));
                }
            }
            return new GoalRevisionProposal(revisions, rationale);
        } catch (Exception e) {
            log.warnf("Failed to parse goal revision response: %s", e.getMessage());
            return new GoalRevisionProposal(List.of(), "");
        }
    }
}
