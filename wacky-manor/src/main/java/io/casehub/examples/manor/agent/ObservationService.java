package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.observation.PartitionedDrain;
import io.casehub.blocks.summarisation.observation.PartitionedObservationService;
import io.casehub.blocks.summarisation.observation.VisibilityPolicy;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.ManorEvent;

public final class ObservationService {

    static final EventLevel MANOR = new EventLevel("manor", 0);

    private final ManorObservationRenderer                          renderer;
    private       PartitionedObservationService<ManorEvent, String> delegate;
    private       WorldState                                        worldState;

    public ObservationService(ManorObservationRenderer renderer) {
        this.renderer = renderer;
    }

    public void init(WorldState worldState) {
        this.worldState = worldState;
        VisibilityPolicy<ManorEvent, String> policy = this::resolveVisibility;
        this.delegate = new PartitionedObservationService<>(
                renderer, policy, e -> e.timestamp().toEpochMilli(), MANOR);
        for (var entry : worldState.characters().entrySet()) {
            delegate.addObserver(entry.getKey(), entry.getValue().currentRoom());
        }
    }

    public void publishEvent(ManorEvent event) {
        delegate.publishEvent(event);
    }

    public PartitionedDrain<String> drain(String characterId, long now) {
        CharacterState character = worldState.character(characterId);
        if (character == null) {
            return new PartitionedDrain<>(io.casehub.blocks.summarisation.observation.ObservationResult.empty(0), java.util.Map.of());
        }
        return delegate.drain(characterId, character.currentRoom(), now);}

    private java.util.Map<String, java.util.Set<String>> resolveVisibility(ManorEvent event) {
        if (event.room() == null) {return java.util.Map.of();}

        var result = new java.util.HashMap<String, java.util.Set<String>>();
        for (var entry : worldState.characters().entrySet()) {
            String         charId    = entry.getKey();
            CharacterState character = entry.getValue();
            String         charRoom  = character.currentRoom();

            if (charRoom.equals(event.room())) {
                if ("aside".equals(event.type()) && !charId.equals(event.characterId())) {
                    continue;
                }
                result.put(charId, java.util.Set.of(charRoom));
            } else if (event.actionType() == ActionType.MOVE
                       && event.departureRoom() != null
                       && charRoom.equals(event.departureRoom())
                       && !charId.equals(event.characterId())) {
                result.put(charId, java.util.Set.of(charRoom));
            }
        }
        return result;
    }
}
