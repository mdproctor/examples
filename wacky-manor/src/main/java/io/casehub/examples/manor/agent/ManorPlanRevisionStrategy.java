package io.casehub.examples.manor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.CompletedStep;
import io.casehub.engine.plan.adaptation.PlanRevisionStrategy;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ManorPlanRevisionStrategy implements PlanRevisionStrategy {

    private static final Logger log = Logger.getLogger(ManorPlanRevisionStrategy.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are a tactical replanner for an autonomous agent. Given the agent's \
        current plan (with completed and pending steps) and a reason for revision \
        (action failure or strategic reassessment), propose a revised plan. \
        Keep completed steps as context. Revise or replace pending steps as needed. \
        Each step should be achievable in 1-3 turns.
        Return ONLY a JSON object: {"steps": [{"id": "step-slug", "description": "what to do"}], \
        "rationale": "why this revision"}""";

    private final SessionPipeline sessionPipeline;

    @Inject
    public ManorPlanRevisionStrategy(SessionPipeline sessionPipeline) {
        this.sessionPipeline = sessionPipeline;
    }

    @Override
    public String id() { return "manor-llm"; }

    @Override
    public RevisedPlan revise(RevisionContext context) {
        try {
            String userPrompt = buildPrompt(context);
            var session = sessionPipeline.openSession("plan-revision", AgentSessionInit.of(SYSTEM_PROMPT));
            var responseBuilder = new StringBuilder();
            session.query(userPrompt).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseBuilder::append);
            String response = responseBuilder.toString();
            return parseResponse(response);
        } catch (Exception e) {
            log.warnf("Plan revision failed (non-fatal): %s", e.getMessage());
            return new RevisedPlan(List.of(), "");
        }
    }

    private String buildPrompt(RevisionContext context) {
        var adaptCtx = context.adaptationContext();
        var sb = new StringBuilder();
        sb.append("Goal: ").append(adaptCtx.goalName()).append("\n");
        sb.append("Revision #").append(adaptCtx.adaptationGeneration() + 1).append("\n");

        if (!adaptCtx.completedSteps().isEmpty()) {
            sb.append("\nCompleted steps:\n");
            for (CompletedStep step : adaptCtx.completedSteps()) {
                sb.append("- [DONE] ").append(step.description()).append("\n");
            }
        }
        if (!adaptCtx.pendingSteps().isEmpty()) {
            sb.append("\nPending steps:\n");
            for (PlanStepDescriptor step : adaptCtx.pendingSteps()) {
                sb.append("- [PENDING] ").append(step.id()).append(": ").append(step.description()).append("\n");
            }
        }

        AdaptationCause cause = context.cause();
        if (cause instanceof AdaptationCause.StepFailed failed) {
            sb.append("\nRevision trigger: ACTION FAILURE\n");
            sb.append("  Failed step: ").append(failed.stepId()).append("\n");
            sb.append("  Reason: ").append(failed.reason()).append("\n");
        } else if (cause instanceof AdaptationCause.StepCompleted completed) {
            sb.append("\nRevision trigger: STRATEGIC REASSESSMENT\n");
            if (completed.output() != null && !completed.output().isEmpty()) {
                var insights = completed.output().get("insights");
                if (insights instanceof List<?> list) {
                    sb.append("  Recent insights:\n");
                    for (Object insight : list) {
                        sb.append("  - ").append(insight).append("\n");
                    }
                }
            }
        }

        if (!context.memories().isEmpty()) {
            sb.append("\nRelevant memories:\n");
            for (var m : context.memories()) {
                sb.append("- ").append(m.text()).append("\n");
            }
        }
        sb.append("\nRespond with JSON only.");
        return sb.toString();
    }

    private RevisedPlan parseResponse(String response) {
        try {
            JsonNode root = JSON.readTree(response);
            JsonNode stepsNode = root.get("steps");
            String rationale = root.has("rationale") ? root.get("rationale").asText() : "";
            List<PlanStepDescriptor> steps = new ArrayList<>();
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode node : stepsNode) {
                    steps.add(new PlanStepDescriptor(
                            node.get("id").asText(),
                            node.get("description").asText(),
                            ""));
                }
            }
            return new RevisedPlan(steps, rationale);
        } catch (Exception e) {
            log.warnf("Failed to parse plan revision response: %s", e.getMessage());
            return new RevisedPlan(List.of(), "");
        }
    }
}
