package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.PendingAction;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;

public final class CharacterAgentLoop {

    private static final Logger log = Logger.getLogger(CharacterAgentLoop.class);

    public static final java.util.List<io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor> ACTION_DESCRIPTORS = java.util.List.of(
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("MOVE", "walk to an adjacent room", "target = room-id"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("TAKE", "pick up a portable object into your inventory", "target = object-id"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("USE", "apply an inventory item to an object", "target = object-id, withItem = item from your inventory"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("GIVE", "hand an inventory item to another character", "target = character-id, withItem = item from your inventory"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("INTERACT", "activate an object's built-in mechanism (levers, locks, puzzles)", "target = object-id, withItem = required item if any"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("LOOK", "examine an object or room closely", "target = object-id or null for room"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("STEAL", "take an item from another character's inventory", "target = character-id, withItem = item to steal"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("PULL_ASIDE", "pull a character aside for a private multi-turn conversation", "target = character-id"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("WAIT", "do nothing this turn", null)
                                                                                                                                                       );

    public static final String RESPONSE_FORMAT_INSTRUCTION = "\n" +
                                                             new io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer().renderActionVocabulary(
                                                                     """
                                                                     You MUST respond with ONLY a JSON object in this exact format:
                                                                     {
                                                                       "thinking": "your immediate reasoning about the current situation — what you notice, how it affects your plans, what to do next. Shown to you next turn as 'Your Current Thinking'",
                                                                       "dialogue": "what you say aloud (or null if silent)",
                                                                       "talkTo": "character-id to direct dialogue at (or null for broadcast)",
                                                                       "aside": "private thoughts for the audience only (or null)",
                                                                       "action": {
                                                                         "type": "one of the action types below",
                                                                         "target": "room-id or object-id or character-id (or null for WAIT)",
                                                                         "withItem": "inventory-item-id to use (or null)"
                                                                       }
                                                                     }
                                                                     
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
                String observation = ObservationBuilder.buildObservation(worldProvider, character, goals, drain, memories, java.util.List.of(), java.util.Map.of());
                String userPrompt  = observation + RESPONSE_FORMAT_INSTRUCTION;

                AgentResponse response = invocationService.invoke(systemPrompt, userPrompt, character.agentId());

                if (response.dialogue() != null) {
                    var dialogueEvent = new io.casehub.examples.manor.model.ManorEvent(
                            java.time.Instant.now(), "dialogue", character.agentId(),
                            character.currentRoom(), character.name() + ": " + response.dialogue());
                    dispatcher.publishDialogue(dialogueEvent, response.dialogue());
                }
                if (response.aside() != null) {
                    var asideEvent = new io.casehub.examples.manor.model.ManorEvent(
                            java.time.Instant.now(), "aside", character.agentId(),
                            character.currentRoom(), response.aside());
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
