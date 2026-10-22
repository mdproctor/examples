package io.casehub.examples.manor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.examples.manor.model.AgentPlan;
import io.casehub.examples.manor.model.PlanStep;
import io.casehub.examples.manor.model.PlanStepStatus;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ManorPlanFormationStrategy {

    private static final Logger log = Logger.getLogger(ManorPlanFormationStrategy.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are a tactical planner for an autonomous agent. Given a goal the agent \
        wants to achieve, decompose it into 2-5 concrete, actionable steps. Each step \
        should be achievable in 1-3 turns using available actions (MOVE, TAKE, USE, \
        INTERACT, LOOK, GIVE, STEAL, WAIT). Steps must be specific and ordered.
        Return ONLY a JSON object: {"steps": [{"id": "step-slug", "description": "what to do"}], \
        "rationale": "why this plan"}""";

    private final SessionPipeline sessionPipeline;

    public ManorPlanFormationStrategy(SessionPipeline sessionPipeline) {
        this.sessionPipeline = sessionPipeline;
    }

    public AgentPlan formPlan(String agentId, String tenancyId, AgentGoal goal,
                              List<AgentGoal> allGoals, List<RetrievedMemory> memories,
                              int currentTick) {
        try {
            String userPrompt = buildPrompt(agentId, goal, allGoals, memories);
            var session = sessionPipeline.openSession("plan-formation", AgentSessionInit.of(SYSTEM_PROMPT));
            var responseBuilder = new StringBuilder();
            session.query(userPrompt).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseBuilder::append);
            String response = responseBuilder.toString();
            return parseResponse(response, goal.name(), currentTick);
        } catch (Exception e) {
            log.warnf("Plan formation failed for goal %s (non-fatal): %s",
                    goal.name(), e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String agentId, AgentGoal goal,
                                List<AgentGoal> allGoals, List<RetrievedMemory> memories) {
        var sb = new StringBuilder();
        sb.append("Agent: ").append(agentId).append("\n");
        sb.append("Goal to plan: ").append(goal.name()).append(" — ").append(goal.description()).append("\n");
        sb.append("Priority: ").append(goal.priority()).append("\n");
        if (allGoals.size() > 1) {
            sb.append("\nOther active goals (for awareness, not planning):\n");
            for (AgentGoal g : allGoals) {
                if (!g.name().equals(goal.name())) {
                    sb.append("- ").append(g.name()).append(": ").append(g.description()).append("\n");
                }
            }
        }
        if (!memories.isEmpty()) {
            sb.append("\nRelevant memories:\n");
            for (RetrievedMemory m : memories) {
                sb.append("- ").append(m.text()).append("\n");
            }
        }
        sb.append("\nRespond with JSON only.");
        return sb.toString();
    }

    private AgentPlan parseResponse(String response, String goalName, int currentTick) {
        try {
            JsonNode root = JSON.readTree(response);
            JsonNode stepsNode = root.get("steps");
            String rationale = root.has("rationale") ? root.get("rationale").asText() : "";
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) return null;
            List<PlanStep> steps = new ArrayList<>();
            for (JsonNode node : stepsNode) {
                String id = node.get("id").asText();
                String description = node.get("description").asText();
                steps.add(new PlanStep(id, description, PlanStepStatus.PENDING));
            }
            return new AgentPlan(goalName, steps, rationale, currentTick, currentTick, 0);
        } catch (Exception e) {
            log.warnf("Failed to parse plan formation response: %s", e.getMessage());
            return null;
        }
    }
}
