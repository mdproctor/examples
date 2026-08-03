package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.ManorConstants;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.PendingAction;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

public final class CharacterAgentLoop {

    private static final Logger log = Logger.getLogger(CharacterAgentLoop.class);

    public static final java.util.List<io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor> ACTION_DESCRIPTORS = java.util.List.of(
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("MOVE", "walk to an adjacent room", "target = room-id"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("TAKE", "pick up a portable object into your inventory", "target = object-id"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("USE", "apply an inventory item to an object", "target = object-id, withItem = item from your inventory"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("GIVE", "hand an inventory item to another character", "target = character-id, withItem = item from your inventory"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("INTERACT", "activate an object's built-in mechanism (levers, locks, puzzles)", "target = object-id, withItem = required item if any"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("LOOK", "examine an object or room closely", "target = object-id or null for room"),
            new io.casehub.blocks.summarisation.observation.affordance.ActionDescriptor("WAIT", "do nothing this turn", null)
                                                                                                                                                       );

    public static final String RESPONSE_FORMAT_INSTRUCTION = "\n" +
                                                              new io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer().renderActionVocabulary(
                                                                      """
                                                                      You MUST respond with ONLY a JSON object in this exact format:
                                                                      {
                                                                        "thinking": "your internal reasoning (not shown to others)",
                                                                        "dialogue": "what you say aloud (or null if silent)",
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
                    AgentProvider agentProvider, String systemPrompt,
                    BlockingQueue<PendingAction> actionQueue,
                    ManorEventDispatcher dispatcher,
                    java.util.List<io.casehub.eidos.api.AgentGoal> goals) {
        while (!world.isScenarioComplete() && character.isActive()) {
            try {
                if (character.sceneContext() != null) {
                    character.sceneContext().awaitRelease();
                    if (world.isScenarioComplete()) {break;}
                }

                var    drain       = dispatcher.observationService().drain(character.agentId(), System.currentTimeMillis());
                String observation = ObservationBuilder.buildObservation(character, world, goals, drain);
                String userPrompt  = observation + RESPONSE_FORMAT_INSTRUCTION;

                AgentResponse response = callAgentWithRetry(
                        agentProvider, systemPrompt, userPrompt, character);

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

                if (response.action() != null &&
                    response.action().type() != ActionType.WAIT) {
                    var pending = new PendingAction(character, response.action());
                    actionQueue.put(pending);
                    pending.awaitResult();
                } else {
                    character.setLastActionResult("You waited and observed.");
                }

                Thread.sleep(thinkDelay(character));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.errorf(e, "%s: loop error", character.agentId());
                break;
            }
        }
    }

    private AgentResponse callAgentWithRetry(
            AgentProvider agentProvider, String systemPrompt,
            String userPrompt, CharacterState character) {
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
                    character.agentId(), attempt + 1, e.getMessage());
                if (attempt == 0) {
                    try { Thread.sleep(thinkDelay(character)); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return AgentResponse.idle();
                    }
                }
            }
        }
        log.warnf("%s: falling back to idle action", character.agentId());
        return AgentResponse.idle();
    }

    private long thinkDelay(CharacterState character) {
        return switch (character.agentId()) {
            case "lazy-luke" -> ManorConstants.THINK_DELAY_LAZY_LUKE_MS;
            case "sergeant-blast" -> ManorConstants.THINK_DELAY_SERGEANT_BLAST_MS;
            default -> ManorConstants.THINK_DELAY_DEFAULT_MS;
        };
    }
}
