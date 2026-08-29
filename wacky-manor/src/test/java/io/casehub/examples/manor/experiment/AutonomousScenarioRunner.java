package io.casehub.examples.manor.experiment;

import io.casehub.eidos.api.AgentGoal;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.examples.manor.agent.NarrativeEventBuilder;
import io.casehub.examples.manor.agent.ManorWorldObservationProvider;
import io.casehub.examples.manor.agent.ObservationBuilder;
import io.casehub.examples.manor.engine.ActionResolver;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CompletionReason;
import io.casehub.examples.manor.model.ProfileMode;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AutonomousScenarioRunner {

    private static final Logger log = Logger.getLogger(AutonomousScenarioRunner.class);
    private static final List<String> DEFAULT_CHARACTER_ORDER = List.of(
            "penelope-pitstop", "hooded-claw", "ant-hill-mob",
            "dick-dastardly", "peter-perfect");

    private final io.casehub.examples.manor.agent.AgentInvocationService invocationService;
    private final io.casehub.examples.manor.agent.AgentExperienceService experienceService;
    private final String modelIdentifier;
    private final String gitCommitHash;

    public AutonomousScenarioRunner(io.casehub.examples.manor.agent.AgentInvocationService invocationService,
                                     io.casehub.examples.manor.agent.AgentExperienceService experienceService,
                                     String modelIdentifier,
                                     String gitCommitHash) {
        this.invocationService = invocationService;
        this.experienceService = experienceService;
        this.modelIdentifier = modelIdentifier;
        this.gitCommitHash = gitCommitHash;
    }

    public TranscriptRecorder.RunResult run(WorldState world,
                                            ProfileMode profile,
                                            int runNumber,
                                            Map<String, List<AgentGoal>> goalsByAgent,
                                            int maxTurns,
                                            Function<String, String> promptRenderer,
                                            List<String> characterOrder) {
        var  characters = characterOrder != null ? characterOrder : DEFAULT_CHARACTER_ORDER;
        var  recorder       = new TranscriptRecorder(modelIdentifier, gitCommitHash);
        var  resolver       = new ActionResolver();
        var  turnLatencies  = new java.util.ArrayList<Long>();
        long startMs        = System.currentTimeMillis();
        int  turnCount      = 0;

        while (!world.isScenarioComplete() && turnCount < maxTurns) {
            turnCount++;
            long turnStart = System.currentTimeMillis();
            for (String agentId : characters) {
                var character = world.character(agentId);
                if (character == null || world.isScenarioComplete()) {break;}

                var goals = goalsByAgent.getOrDefault(agentId, List.of());
                var emptyDrain = new io.casehub.blocks.summarisation.observation.PartitionedDrain<String>(io.casehub.blocks.summarisation.observation.ObservationResult.empty(0), java.util.Map.of());
                var worldProvider = new ManorWorldObservationProvider(character, world, emptyDrain);
                String observation = ObservationBuilder.buildObservation(worldProvider, null, java.util.Set.of(), character, goals, emptyDrain, java.util.List.of(), java.util.List.of(), java.util.Map.of())
                                     + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
                String systemPrompt = promptRenderer.apply(agentId);

                AgentResponse response = invocationService.invoke(systemPrompt, observation, agentId);

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

                if (experienceService != null) {
                    String desc = (response.dialogue() != null ? response.dialogue() + " " : "")
                                  + (response.action() != null ? response.action().type() + " " + response.action().target() : "WAIT");
                    experienceService.ingest(agentId, character.currentRoom(), desc.strip(), response.thinking());
                }

                if (world.hasEffect("tea-service", "rat-poison")) {
                    world.setScenarioComplete(CompletionReason.DAWN);
                }
            }
            turnLatencies.add(System.currentTimeMillis() - turnStart);
        }

        if (!world.isScenarioComplete()) {
            world.setScenarioComplete(CompletionReason.DAWN);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        lastTurnLatencies = turnLatencies.stream().mapToLong(Long::longValue).toArray();
        return recorder.toRunResult(profile, runNumber,
                                    world.completionReason(), turnCount, durationMs);
    }

    public long[] lastTurnLatencies() { return lastTurnLatencies; }
    private long[] lastTurnLatencies = new long[0];

}
