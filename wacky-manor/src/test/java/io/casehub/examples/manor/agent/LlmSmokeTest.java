package io.casehub.examples.manor.agent;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.Collectors;

@QuarkusTest
class LlmSmokeTest {

    @Inject AgentProvider agentProvider;
    @Inject io.casehub.eidos.api.AgentRegistry agentRegistry;
    @Inject io.casehub.eidos.api.SystemPromptRenderer renderer;

    @Test
    void real_character_prompt_and_llm_call() {
        try {
            var desc = agentRegistry.findById("penelope-pitstop", "wacky-manor")
                .orElseThrow(() -> new IllegalStateException("No descriptor for penelope-pitstop"));
            System.out.println("DESCRIPTOR FOUND: " + desc.agentId() + " / " + desc.name());

            var ctx = io.casehub.eidos.api.AgentPromptContext.forFormat(
                io.casehub.eidos.api.SystemPromptRenderer.RenderFormat.MARKDOWN);
            var rendered = renderer.render(desc, ctx);
            System.out.println("PROMPT RENDERED: " + rendered.content().length() + " chars");
            System.out.println("COHERENCE: " + (rendered.coherenceReport() != null ? rendered.coherenceReport().overall() : "null"));

            String userPrompt = "You just arrived at a mansion. What do you say? Respond with JSON: {\"thinking\":\"...\",\"dialogue\":\"...\",\"aside\":null,\"action\":{\"type\":\"WAIT\",\"target\":null,\"withItem\":null}}";

            String result = agentProvider.invoke(
                    AgentSessionConfig.of(rendered.content(), userPrompt, Duration.ofSeconds(60)))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(120));
            System.out.println("LLM RESULT: " + result.substring(0, Math.min(200, result.length())));
        } catch (Exception e) {
            System.err.println("LLM ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
