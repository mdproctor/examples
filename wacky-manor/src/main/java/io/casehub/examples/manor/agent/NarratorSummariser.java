package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class NarratorSummariser implements Summariser<ManorEvent, String> {

    private static final Logger log = Logger.getLogger(NarratorSummariser.class);

    static final String SYSTEM_PROMPT = """
        You are the narrator of a Wacky Races cartoon special set in a haunted mansion.
        Your style is breathless, alliterative, dramatic, and omniscient — like the
        original Wacky Races narrator.

        Rules:
        - Use CAPITAL LETTERS for dramatic emphasis
        - Be alliterative when possible
        - Use exclamation marks liberally
        - You see everything and know everyone's secrets
        - Address the audience directly
        - Keep each narration to 2-3 sentences maximum

        Example: "And so our heroes GATHER in the dusty entrance of Doily Manor,
        UTTERLY UNAWARE that DANGER lurks behind every cobweb! The Hooded Claw
        adjusts his disguise and flashes a smile SO sinister it could curdle MILK!"
        """;

    private final AgentProvider agentProvider;

    public NarratorSummariser(AgentProvider agentProvider) {
        this.agentProvider = agentProvider;
    }

    @Override
    public CompletionStage<List<String>> summarise(List<LevelEvent<ManorEvent>> batch) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String prompt = formatPrompt(batch);
        try {
            String narration = agentProvider.invoke(
                            AgentSessionConfig.of(SYSTEM_PROMPT, prompt, Duration.ofSeconds(30)))
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .collect().with(Collectors.joining())
                    .await().atMost(Duration.ofSeconds(60));
            return CompletableFuture.completedFuture(List.of(narration));
        } catch (Exception e) {
            log.warnf("Narrator LLM call failed: %s", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    String formatPrompt(List<LevelEvent<ManorEvent>> batch) {
        var byRoom = new LinkedHashMap<String, ArrayList<String>>();
        for (var event : batch) {
            ManorEvent e = event.payload();
            String room = e.room() != null ? e.room() : "General";
            byRoom.computeIfAbsent(room, k -> new ArrayList<>())
                    .add("- " + e.description());
        }
        var sb = new StringBuilder("Narrate what just happened in 2-3 sentences:\n\n");
        for (var entry : byRoom.entrySet()) {
            sb.append("[").append(capitalize(entry.getKey())).append("]\n");
            for (String line : entry.getValue()) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
