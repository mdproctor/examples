package io.casehub.examples.manor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ManorGoalFormationStrategy implements GoalFormationStrategy {

    private static final Logger log = Logger.getLogger(ManorGoalFormationStrategy.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are a goal discovery analyst for an autonomous agent. Given the agent's \
        recent reflection insights, current goals, and relevant memories, identify \
        new goals the agent should pursue. Only propose goals that represent genuinely \
        new objectives — not refinements of existing goals. Each goal must be specific, \
        actionable, and distinct from existing goals.
        Return ONLY a JSON object: {"goals": [{"name": "...", "description": "...", \
        "suggestedPriority": "PRIMARY"|"SECONDARY"|null, "formationReason": "..."}], \
        "rationale": "..."}""";

    private final AgentProvider agentProvider;

    @Inject
    public ManorGoalFormationStrategy(AgentProvider agentProvider) {
        this.agentProvider = agentProvider;
    }

    @Override
    public String id() { return "manor-llm"; }

    @Override
    public GoalFormationProposal propose(GoalFormationContext context) {
        try {
            String userPrompt = buildPrompt(context);
            String response = agentProvider.invoke(
                    AgentSessionConfig.of(SYSTEM_PROMPT, userPrompt))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(120));
            return parseResponse(response);
        } catch (Exception e) {
            log.warnf("Goal formation failed (non-fatal): %s", e.getMessage());
            return new GoalFormationProposal(List.of(), "");
        }
    }

    private String buildPrompt(GoalFormationContext context) {
        var sb = new StringBuilder();
        sb.append("Agent: ").append(context.agentId()).append("\n");
        sb.append("Remaining goal capacity: ").append(context.remainingCapacity()).append("\n");
        sb.append("\nCurrent goals:\n");
        for (AgentGoal goal : context.existingGoals()) {
            sb.append("- ").append(goal.name()).append(": ").append(goal.description())
              .append(" (priority: ").append(goal.priority()).append(")\n");
        }
        sb.append("\nRecent reflection insights:\n");
        for (String insight : context.reflectionInsights()) {
            sb.append("- ").append(insight).append("\n");
        }
        if (!context.recentMemories().isEmpty()) {
            sb.append("\nRelevant memories:\n");
            for (RetrievedMemory memory : context.recentMemories()) {
                sb.append("- ").append(memory.text()).append("\n");
            }
        }
        sb.append("\nRespond with JSON only.");
        return sb.toString();
    }

    private GoalFormationProposal parseResponse(String response) {
        try {
            JsonNode root = JSON.readTree(response);
            JsonNode goalsNode = root.get("goals");
            String rationale = root.has("rationale") ? root.get("rationale").asText() : "";
            List<GoalFormationProposal.ProposedGoal> goals = new ArrayList<>();
            if (goalsNode != null && goalsNode.isArray()) {
                for (JsonNode node : goalsNode) {
                    String name = node.get("name").asText();
                    String description = node.get("description").asText();
                    GoalPriority priority = null;
                    if (node.has("suggestedPriority") && !node.get("suggestedPriority").isNull()) {
                        try {
                            priority = GoalPriority.valueOf(node.get("suggestedPriority").asText());
                        } catch (IllegalArgumentException e) {
                            priority = GoalPriority.SECONDARY;
                        }
                    }
                    String reason = node.has("formationReason") ? node.get("formationReason").asText() : "";
                    goals.add(new GoalFormationProposal.ProposedGoal(name, description, priority, reason, java.util.Map.of()));
                }
            }
            return new GoalFormationProposal(goals, rationale);
        } catch (Exception e) {
            log.warnf("Failed to parse goal formation response: %s", e.getMessage());
            return new GoalFormationProposal(List.of(), "");
        }
    }
}
