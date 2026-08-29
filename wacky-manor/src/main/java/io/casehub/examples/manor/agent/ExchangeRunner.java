package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.ManorEvent;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ExchangeRunner {

    private static final Logger log = Logger.getLogger(ExchangeRunner.class);

    static final String EXCHANGE_FORMAT_INSTRUCTION = """

    You are in a private conversation. Respond with ONLY a JSON object:
    {
      "thinking": "your persistent strategic plan",
      "dialogue": "what you say to the other person",
      "action": {"type": "WAIT"} 
    }
    Respond with action WAIT to end this private conversation and return to normal activity.
    Any other action type also ends the conversation (but the action does not execute now).
    Respond with ONLY the JSON. No other text.""";

    private final int maxRoundTrips;
    private final long timeoutMs;

    public ExchangeRunner(int maxRoundTrips, long timeoutMs) {
        this.maxRoundTrips = maxRoundTrips;
        this.timeoutMs = timeoutMs;
    }

    public List<ManorEvent> run(CharacterState initiator, CharacterState target,
                                 String openingDialogue, WorldState world,
                                 AgentInvocationService invocationService,
                                 Function<String, String> systemPromptRenderer) {
        var events = new ArrayList<ManorEvent>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        String initiatorPrompt = systemPromptRenderer.apply(initiator.agentId());
        String targetPrompt = systemPromptRenderer.apply(target.agentId());

        String lastDialogue = (openingDialogue == null || openingDialogue.isBlank())
                ? initiator.name() + " pulls you aside."
                : openingDialogue;
        String currentSpeakerId = initiator.agentId();

        events.add(createExchangeEvent(initiator, target.agentId(), openingDialogue, world));

        for (int round = 0; round < maxRoundTrips; round++) {
            if (System.currentTimeMillis() > deadline) {
                log.infof("Exchange %s↔%s timed out after %d rounds", initiator.agentId(), target.agentId(), round);
                break;
            }

            CharacterState responder = currentSpeakerId.equals(initiator.agentId()) ? target : initiator;
            String responderPrompt = responder == initiator ? initiatorPrompt : targetPrompt;

            var exchangeProvider = new ManorExchangeObservationProvider(responder, lastDialogue, world);
            String observation = ObservationBuilder.buildObservation(exchangeProvider, null, java.util.Set.of(), responder, java.util.List.of(), new io.casehub.blocks.summarisation.observation.PartitionedDrain<>(io.casehub.blocks.summarisation.observation.ObservationResult.empty(0), java.util.Map.of()), java.util.List.of(), java.util.List.of(), java.util.Map.of());
            String userPrompt = observation + EXCHANGE_FORMAT_INSTRUCTION;

            AgentResponse response = invocationService.invoke(responderPrompt, userPrompt, responder.agentId());

            if (response.thinking() != null) {
                responder.setCurrentThinking(response.thinking());
            }

            if (response.dialogue() != null) {
                lastDialogue = response.dialogue();
                currentSpeakerId = responder.agentId();
                String otherParticipant = responder == initiator ? target.agentId() : initiator.agentId();
                events.add(createExchangeEvent(responder, otherParticipant, response.dialogue(), world));
            }

            if (response.action() != null && response.action().type() != ActionType.WAIT) {
                log.infof("Exchange %s↔%s ended by %s action %s (round %d)",
                        initiator.agentId(), target.agentId(), responder.agentId(), response.action().type(), round);
                break;
            }
            if (response.action() != null && response.action().type() == ActionType.WAIT && response.dialogue() == null) {
                log.infof("Exchange %s↔%s ended by %s WAIT (round %d)",
                        initiator.agentId(), target.agentId(), responder.agentId(), round);
                break;
            }
        }

        return events;
    }

    private ManorEvent createExchangeEvent(CharacterState speaker, String targetId, String dialogue, WorldState world) {
        var narr = NarrativeEventBuilder.describeDirectedDialogue(speaker.name(), targetId, dialogue);
        return new ManorEvent(Instant.now(), "dialogue", speaker.agentId(), speaker.currentRoom(),
                narr.publicText(), null, null, null, null, narr.detailedText(), false, targetId);
    }
}
