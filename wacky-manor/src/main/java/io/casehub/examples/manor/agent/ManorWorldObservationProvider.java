package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.observation.PartitionedDrain;
import io.casehub.blocks.summarisation.observation.RememberedPartition;
import io.casehub.blocks.summarisation.observation.affordance.Affordance;
import io.casehub.blocks.summarisation.observation.affordance.ObservableEntity;
import io.casehub.blocks.summarisation.observation.affordance.ObservationSection;
import io.casehub.blocks.summarisation.observation.affordance.WorldObservationProvider;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.GameObject;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.examples.manor.model.Room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ManorWorldObservationProvider implements WorldObservationProvider {

    private final CharacterState character;
    private final WorldState world;
    private final PartitionedDrain<String> drain;

    public ManorWorldObservationProvider(CharacterState character, WorldState world,
                                         PartitionedDrain<String> drain) {
        this.character = character;
        this.world = world;
        this.drain = drain;
    }

    @Override
    public List<ObservationSection> worldSections() {
        var sections = new ArrayList<ObservationSection>();
        Room room = world.room(character.currentRoom());

        sections.add(locationSection(room));
        sections.add(exitsSection(room, world));
        sections.add(objectsSection(character, world));
        sections.add(charactersSection(character, world));
        if (!drain.rememberedPartitions().isEmpty()) {
            sections.add(rememberedSection(drain, world));
        }
        var keen = keenObservationsSection(character, world);
        var directed = directedDialogueSection(character, world);
        if (keen != null || directed != null) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.AnnotatedSection.withResolution(
                    keen != null ? keen
                         : ObservationSection.items("Keen Observations", null, java.util.List.of()),
                    java.util.Set.of("perception"),
                    io.casehub.blocks.summarisation.observation.affordance.ResolutionTier.REDUCED,
                    directed));
        }
        return sections;
    }

    private static ObservationSection locationSection(Room room) {
        return ObservationSection.text(
                "Current Location", room.name() + ": " + room.description());
    }

    private static ObservationSection exitsSection(Room room, WorldState world) {
        var exits = room.adjacentRooms().stream()
                        .map(id -> {
                            Room target = world.room(id);
                            return new ObservableEntity(
                                    id, target.name(), target.description(),
                                    List.of(new Affordance("MOVE", "to walk here")));
                        })
                        .toList();
        return ObservationSection.entities("Exits", "No exits.", exits);
    }

    private static ObservationSection objectsSection(CharacterState character, WorldState world) {
        List<GameObject> objects = world.visibleObjects(character.currentRoom(), character.agentId());
        var entities = objects.stream()
                              .map(ManorWorldObservationProvider::toObservableEntity)
                              .toList();
        return ObservationSection.entities("Visible Objects", "Nothing notable here.", entities);
    }

    private static ObservableEntity toObservableEntity(GameObject obj) {
        var affordances = new ArrayList<Affordance>();
        if (obj.interactable()) {
            affordances.add(new Affordance("INTERACT", null, obj.interactionRequires(), List.of()));
        }
        if (obj.portable()) {
            affordances.add(new Affordance("TAKE", "to pick up"));
        }
        if (!obj.usableWith().isEmpty()) {
            affordances.add(new Affordance("USE", null, null, obj.usableWith()));
        }
        return new ObservableEntity(obj.id(), obj.name(), obj.description(), affordances);
    }

    private static ObservationSection charactersSection(CharacterState character, WorldState world) {
        List<CharacterState> others = world.charactersInRoom(character.currentRoom()).stream()
                                           .filter(c -> !c.agentId().equals(character.agentId()))
                                           .toList();
        var entities = others.stream()
                             .map(c -> new ObservableEntity(
                                     c.agentId(), c.name(), null,
                                     List.of(new Affordance("GIVE", "to hand an item"))))
                             .toList();
        return ObservationSection.entities("Characters Present", "You are alone.", entities);
    }

    private static ObservationSection keenObservationsSection(CharacterState character, WorldState world) {
        var events = world.recentEvents(character.currentRoom(), 20);
        var details = events.stream()
                            .filter(e -> e.detailedDescription() != null)
                            .filter(e -> !e.characterId().equals(character.agentId()))
                            .map(ManorEvent::detailedDescription)
                            .toList();
        if (details.isEmpty()) { return null; }
        return ObservationSection.items("Keen Observations", null, details);
    }

    private static ObservationSection directedDialogueSection(CharacterState character, WorldState world) {
        var events = world.recentEvents(character.currentRoom(), 20);
        var details = events.stream()
                            .filter(e -> e.detailedDescription() != null)
                            .filter(e -> character.agentId().equals(e.dialogueTarget()))
                            .map(ManorEvent::detailedDescription)
                            .toList();
        if (details.isEmpty()) { return null; }
        return ObservationSection.items("Directed to You", null, details);
    }

    private static ObservationSection rememberedSection(PartitionedDrain<String> drain, WorldState world) {
        var items = new ArrayList<String>();
        var entries = new ArrayList<>(drain.rememberedPartitions().entrySet());
        Collections.reverse(entries);
        long now = System.currentTimeMillis();
        for (var entry : entries) {
            String roomId = entry.getKey();
            RememberedPartition remembered = entry.getValue();
            Room room = world.room(roomId);
            long elapsed = now - remembered.cachedAt();
            String timeAgo = formatElapsed(elapsed);
            String text = remembered.result().renderedText();
            if (text != null && !text.isBlank()) {
                items.add(room.name() + " (" + timeAgo + " ago): " + text.strip());
            }
        }
        return ObservationSection.items("Remembered", null, items);
    }

    private static String formatElapsed(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) { return seconds + "s"; }
        long minutes = seconds / 60;
        return minutes + " minute" + (minutes == 1 ? "" : "s");
    }
}
