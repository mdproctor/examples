package io.casehub.examples.manor.experiment;

import io.casehub.eidos.api.AgentGoal;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.examples.manor.agent.NarrativeEventBuilder;
import io.casehub.examples.manor.agent.ObservationBuilder;
import io.casehub.examples.manor.engine.ActionResolver;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CompletionReason;
import io.casehub.examples.manor.model.ProfileMode;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AutonomousScenarioRunner {

    private static final Logger log = Logger.getLogger(AutonomousScenarioRunner.class);
    private static final List<String> CHARACTER_ORDER = List.of(
            "penelope-pitstop", "hooded-claw", "ant-hill-mob",
            "dick-dastardly", "peter-perfect");

    private final AgentProvider agentProvider;
    private final String modelIdentifier;
    private final String gitCommitHash;

    public AutonomousScenarioRunner(AgentProvider agentProvider,
                                     String modelIdentifier,
                                     String gitCommitHash) {
        this.agentProvider = agentProvider;
        this.modelIdentifier = modelIdentifier;
        this.gitCommitHash = gitCommitHash;
    }

    public TranscriptRecorder.RunResult run(WorldState world,
                                             ProfileMode profile,
                                             int runNumber,
                                             Map<String, List<AgentGoal>> goalsByAgent,
                                             int maxTurns,
                                             Function<String, String> promptRenderer) {
        var recorder = new TranscriptRecorder(modelIdentifier, gitCommitHash);
        var resolver = new ActionResolver();
        long startMs = System.currentTimeMillis();
        int turnCount = 0;

        while (!world.isScenarioComplete() && turnCount < maxTurns) {
            turnCount++;
            for (String agentId : CHARACTER_ORDER) {
                var character = world.character(agentId);
                if (character == null || world.isScenarioComplete()) break;

                var goals = goalsByAgent.getOrDefault(agentId, List.of());
                String observation = ObservationBuilder.buildObservation(character, world, goals, new io.casehub.blocks.summarisation.observation.PartitionedDrain<>(io.casehub.blocks.summarisation.observation.ObservationResult.empty(0), java.util.Map.of()))
                        + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
                String systemPrompt = promptRenderer.apply(agentId);

                AgentResponse response = callAgentWithRetry(systemPrompt, observation, agentId);

                if (response.dialogue() != null) {
                    recorder.record(new TranscriptRecorder.Event(
                            turnCount, agentId, "dialogue", null, null,
                            response.dialogue(), null, null, null));
                }
                if (response.thinking() != null) {
                    recorder.record(new TranscriptRecorder.Event(
                            turnCount, agentId, "thinking", null, null,
                            null, response.thinking(), null, null));
                }
                if (response.aside() != null) {
                    recorder.record(new TranscriptRecorder.Event(
                            turnCount, agentId, "aside", null, null,
                            null, null, response.aside(), null));
                }

                if (response.action() != null && response.action().type() != ActionType.WAIT) {
                    var result = resolver.resolve(character, response.action(), world);

                    String narrative = NarrativeEventBuilder.describe(
                            character, response.action(), result);
                    if (narrative != null) {
                        world.addEvent("action", agentId, character.currentRoom(), narrative);
                    }

                    character.setLastActionResult(result.text());
                    recorder.record(new TranscriptRecorder.Event(
                            turnCount, agentId, "action",
                            response.action().type().name(), response.action().target(),
                            null, null, null, result.text()));
                } else {
                    character.setLastActionResult("You waited and observed.");
                    recorder.record(new TranscriptRecorder.Event(
                            turnCount, agentId, "action", "WAIT", null,
                            null, null, null, "You waited and observed."));
                }

                if (world.hasEffect("tea-service", "rat-poison")) {
                    world.setScenarioComplete(CompletionReason.POISONED);
                }
            }
        }

        if (!world.isScenarioComplete()) {
            world.setScenarioComplete(CompletionReason.TURN_LIMIT);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        return recorder.toRunResult(profile, runNumber,
                world.completionReason(), turnCount, durationMs);
    }

    private AgentResponse callAgentWithRetry(String systemPrompt,
                                              String userPrompt,
                                              String agentId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String text = agentProvider.invoke(
                                AgentSessionConfig.of(systemPrompt, userPrompt,
                                        Duration.ofSeconds(60)))
                        .filter(e -> e instanceof AgentEvent.TextDelta)
                        .map(e -> ((AgentEvent.TextDelta) e).text())
                        .collect().with(Collectors.joining())
                        .await().atMost(Duration.ofSeconds(120));
                return AgentResponse.parse(text);
            } catch (Exception e) {
                log.warnf("%s: LLM call failed (attempt %d): %s",
                        agentId, attempt + 1, e.getMessage());
                if (attempt == 0) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        log.warnf("%s: falling back to idle action", agentId);
        return AgentResponse.idle();
    }
}
