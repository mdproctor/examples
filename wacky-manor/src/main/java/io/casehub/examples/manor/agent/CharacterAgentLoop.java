package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.PendingAction;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;

public final class CharacterAgentLoop {

    private static final Logger log = Logger.getLogger(CharacterAgentLoop.class);

    public static final java.util.List<io.casehub.agency.perception.observation.affordance.ActionDescriptor> ACTION_DESCRIPTORS = java.util.List.of(
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("MOVE", "walk to an adjacent room", "target = room-id"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("TAKE", "pick up a portable object into your inventory", "target = object-id"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("USE", "apply an inventory item to an object", "target = object-id, withItem = item from your inventory"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("GIVE", "hand an inventory item to another character", "target = character-id, withItem = item from your inventory"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("INTERACT", "activate an object's built-in mechanism (levers, locks, puzzles)", "target = object-id, withItem = required item if any"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("LOOK", "examine an object or room closely", "target = object-id or null for room"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("STEAL", "take an item from another character's inventory", "target = character-id, withItem = item to steal"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("PULL_ASIDE", "pull a character aside for a private multi-turn conversation", "target = character-id"),
            new io.casehub.agency.perception.observation.affordance.ActionDescriptor("WAIT", "do nothing this turn", null)
                                                                                                                                                       );

    // Multi-channel thinking prompt aligned with neocortex DriveDescriptionGenerationPhase channels:
    // somatic (Damasio 1994; Barrett 2017), attentional (Easterbrook 1959),
    // temporal (Barrett 2017 predictive coding), action tendency (Frijda 1986)
    public static final String RESPONSE_FORMAT_INSTRUCTION = "\n" +
                                                             new io.casehub.agency.perception.observation.affordance.AffordanceRenderer().renderActionVocabulary(
                                                                     """
                                                                     You MUST respond with ONLY a JSON object in this exact format:
                                                                     {
                                                                       "thinking": "What's happening inside you right now? Notice your body — a tightness, a warmth, a numbness. Notice what's grabbing your attention — what you can't look away from, what you're avoiding. Notice time — does the moment feel urgent, or suspended? Notice the pull — what do you feel drawn to do? Your thinking is your private truth — raw, unperformed, sometimes confused. The character's voice and manner belong in your dialogue. Here you are just a person feeling something. Shown to you next turn.",
                                                                       "situationChangeDegree": 0.0-1.0,
                                                                       "dialogue": "what you say aloud (or null if silent)",
                                                                       "talkTo": "character-id to direct dialogue at (or null for broadcast)",
                                                                       "aside": "private thoughts for the audience only (or null)",
                                                                       "action": {
                                                                         "type": "one of the action types below",
                                                                         "target": "room-id or object-id or character-id (or null for WAIT)",
                                                                         "withItem": "inventory-item-id to use (or null)"
                                                                       }
                                                                     }
                                                                     
                                                                     situationChangeDegree: rate how much the situation changed this tick (0.0 = nothing happened, 1.0 = dramatic shift).
                                                                     
                                                                     ACTION TYPES — use the right one for your intent:""",
                                                                     ACTION_DESCRIPTORS) +
                                                             "\n\nTo get an object, use TAKE. To apply an item you're carrying, use USE.\nRespond with ONLY the JSON. No other text.";

    public void run(CharacterState character, WorldState world,
                    AgentInvocationService invocationService,
                    AgentExperienceService experienceService,
                    String systemPrompt,
                    BlockingQueue<PendingAction> actionQueue,
                    ManorEventDispatcher dispatcher,
                    java.util.List<io.casehub.eidos.api.AgentGoal> goals) {
        run(character, world, invocationService, experienceService,
            systemPrompt, actionQueue, dispatcher, goals, null);
    }

    public void run(CharacterState character, WorldState world,
                    AgentInvocationService invocationService,
                    AgentExperienceService experienceService,
                    String systemPrompt,
                    BlockingQueue<PendingAction> actionQueue,
                    ManorEventDispatcher dispatcher,
                    java.util.List<io.casehub.eidos.api.AgentGoal> goals,
                    CharacterCognition cognition) {
        while (!world.isScenarioComplete() && character.isActive()) {
            try {
                if (character.sceneContext() != null) {
                    character.sceneContext().awaitRelease();
                    if (world.isScenarioComplete()) {break;}
                }

                while (world.isPaused() && !world.isScenarioComplete() && character.isActive()) {
                    Thread.sleep(200);
                }
                if (world.isScenarioComplete()) {break;}

                var drain = dispatcher.observationService().drain(character.agentId(), System.currentTimeMillis());
                java.util.List<io.casehub.neocortex.memory.Memory> memories = experienceService != null
                                                                              ? experienceService.recall(character.agentId(), 10) : java.util.List.of();
                var worldProvider = new ManorWorldObservationProvider(character, world, drain);
                var pipeline = new io.casehub.agency.perception.observation.affordance.ObservationPipeline(new io.casehub.agency.perception.observation.affordance.PerceptionFilter());

                String observation;
                if (cognition != null) {
                    var nearbyIds = world.charactersInRoom(character.currentRoom()).stream()
                        .map(io.casehub.examples.manor.model.CharacterState::agentId)
                        .filter(id -> !id.equals(character.agentId()))
                        .toList();
                    var agentNameMap = new java.util.HashMap<String, String>();
                    for (var id : nearbyIds) {
                        var nearby = world.character(id);
                        if (nearby != null) agentNameMap.put(id, nearby.name());
                    }
                    var reflections = cognition.recallReflections(5);
                    var relationships = new java.util.HashMap<String, java.util.List<io.casehub.neocortex.memory.Memory>>();
                    for (var other : world.charactersInRoom(character.currentRoom())) {
                        if (!other.agentId().equals(character.agentId())) {
                            var relMems = cognition.recallRelationships(other.agentId(), 3);
                            if (!relMems.isEmpty()) relationships.put(other.name(), relMems);
                        }
                    }
                    var situationCtx = cognition.classifySituation(character.lastActionResult(), nearbyIds);
                    observation = new ObservationBuilder(worldProvider, pipeline, character.capabilityTags())
                        .withCharacter(character)
                        .withGoals(goals)
                        .withDrain(drain)
                        .withMemories(memories, reflections, relationships)
                        .withCognitiveSections(cognition.renderCognitiveSections(character, nearbyIds, agentNameMap, situationCtx))
                        .withTaggedSections(cognition.renderTaggedSections())
                        .build();
                } else {
                    observation = ObservationBuilder.buildObservation(worldProvider, pipeline, character.capabilityTags(), character, goals, drain, memories, java.util.List.of(), java.util.Map.of());
                }
                String userPrompt  = observation + RESPONSE_FORMAT_INSTRUCTION;

                AgentResponse response = invocationService.invoke(systemPrompt, userPrompt, character.agentId());

                if (response.dialogue() != null) {
                    var dialogueEvent = new io.casehub.examples.manor.model.ManorEvent.Dialogue(
                            java.time.Instant.now(), character.agentId(),
                            character.currentRoom(), character.name() + ": " + response.dialogue(),
                            null, null, response.thinking());
                    dispatcher.publishDialogue(dialogueEvent, response.dialogue());
                }
                if (response.aside() != null) {
                    var asideEvent = new io.casehub.examples.manor.model.ManorEvent.Aside(
                            java.time.Instant.now(), character.agentId(),
                            character.currentRoom(), response.aside(), response.thinking());
                    dispatcher.publishAside(asideEvent, response.aside());
                }

                if (response.action() != null && response.action().type() != ActionType.WAIT) {
                    var pending = new PendingAction(character, response.action());
                    actionQueue.put(pending);
                    pending.awaitResult(60);
                } else {
                    character.setLastActionResult("You waited and observed.");
                }

                if (experienceService != null) {
                    String desc = (response.dialogue() != null ? response.dialogue() + " " : "")
                                  + (response.action() != null ? response.action().type() + " " + response.action().target() : "WAIT");
                    experienceService.ingest(character.agentId(), character.currentRoom(),
                                             desc.strip(), response.thinking());
                }

                Thread.sleep((long) (character.thinkDelayMs() / world.speedMultiplier()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.errorf(e, "%s: loop error", character.agentId());
                break;
            }
        }
    }


}
