package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionResult;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.examples.manor.web.ManorEventBus;
import io.casehub.examples.manor.web.ManorWebSocketEvent;

public final class ManorEventDispatcher {

    private final WorldState world;
    private final ObservationService observationService;
    private final NarratorAgent narratorAgent;
    private final ManorChannels manorChannels;
    private final ManorEventBus webEventBus;

    public ManorEventDispatcher(WorldState world,
                                ObservationService observationService,
                                NarratorAgent narratorAgent,
                                ManorChannels manorChannels,
                                ManorEventBus webEventBus) {
        this.world = world;
        this.observationService = observationService;
        this.narratorAgent = narratorAgent;
        this.manorChannels = manorChannels;
        this.webEventBus = webEventBus;
    }

    public void publishAction(ManorEvent event, ActionResult result, double characterX) {
        commonPublish(event);
        if (result instanceof ActionResult.MovedToRoom moved) {
            if (manorChannels != null) {
                manorChannels.dispatchPositionEvent(event.characterId(), moved.roomId());
            }
            if (webEventBus != null) {
                webEventBus.broadcast(ManorWebSocketEvent.position(
                        event.characterId(), moved.roomId(), characterX));
            }
        }}

    public void publishDialogue(ManorEvent event, String rawDialogue) {
        commonPublish(event);
        if (manorChannels != null) {
            manorChannels.dispatchDialogue(event.characterId(), event.room(), rawDialogue);
        }
        if (webEventBus != null) {
            webEventBus.broadcast(ManorWebSocketEvent.dialogue(
                    event.characterId(), event.room(), rawDialogue));
        }}

    public void publishAside(ManorEvent event, String rawContent) {
        commonPublish(event);
        if (manorChannels != null) {
            manorChannels.dispatchAside(event.characterId(), rawContent);
        }
        if (webEventBus != null) {
            webEventBus.broadcast(ManorWebSocketEvent.aside(
                    event.characterId(), rawContent));
        }}

    public ObservationService observationService() {
        return observationService;
    }

    private void commonPublish(ManorEvent event) {
        world.addEvent(event);
        observationService.publishEvent(event);
        if (narratorAgent != null) {
            narratorAgent.collect(event);
        }
    }
}
