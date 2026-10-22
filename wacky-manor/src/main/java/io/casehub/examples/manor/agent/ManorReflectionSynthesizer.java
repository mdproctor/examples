package io.casehub.examples.manor.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.reflection.ReflectionEvent;
import io.casehub.neocortex.memory.reflection.ReflectionSynthesizer;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ManorReflectionSynthesizer implements ReflectionSynthesizer {

    private static final Logger log = Logger.getLogger(ManorReflectionSynthesizer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are analyzing the recent experiences of an agent to identify \
        patterns, relationships, and strategic insights. Each insight should \
        be one clear, specific sentence. Focus on:
        - Patterns in other agents' behavior
        - Cause-and-effect relationships between actions
        - Strategic implications for the agent's goals
        - Social dynamics and trust signals

        Return a JSON array of insights:
        [{"insight": "...", "importance": 0.0-1.0}]
        Return ONLY the JSON array. No other text.""";

    private final SessionPipeline sessionPipeline;

    public ManorReflectionSynthesizer(SessionPipeline sessionPipeline) {
        this.sessionPipeline = sessionPipeline;
    }

    @Override
    public List<ReflectionEvent> synthesize(String agentId, String tenantId,
                                             List<Memory> sources, int targetLevel) {
        if (sources.isEmpty()) return List.of();
        try {
            var sb = new StringBuilder("Recent experiences:\n");
            for (var m : sources) {
                sb.append("- ").append(m.text()).append("\n");
            }
            var sourceIds = sources.stream().map(Memory::memoryId).toList();

            var session = sessionPipeline.openSession("reflection", AgentSessionInit.of(SYSTEM_PROMPT));
            var responseBuilder = new StringBuilder();
            session.query(sb.toString()).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseBuilder::append);
            String response = responseBuilder.toString();

            record InsightEntry(String insight, Double importance) {}
            var entries = JSON.readValue(response, new TypeReference<List<InsightEntry>>() {});

            return entries.stream()
                .map(e -> new ReflectionEvent(agentId, tenantId, null, null,
                    e.insight(), targetLevel, sourceIds,
                    e.importance() != null ? e.importance() : 0.7,
                    Map.of()))
                .toList();
        } catch (Exception e) {
            log.warnf("%s: reflection synthesis failed (non-fatal): %s",
                agentId, e.getMessage());
            return List.of();
        }
    }
}
