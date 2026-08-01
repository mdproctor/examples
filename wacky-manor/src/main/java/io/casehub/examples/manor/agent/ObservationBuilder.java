package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.model.GameObject;
import io.casehub.examples.manor.model.Room;

import java.util.List;

public final class ObservationBuilder {

    private static final io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer RENDERER           =
            new io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer();

    public static String buildObservation(CharacterState character, WorldState world,
                                          java.util.List<io.casehub.eidos.api.AgentGoal> goals,
                                          io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain) {
        Room room     = world.room(character.currentRoom());
        var  sections = new java.util.ArrayList<io.casehub.blocks.summarisation.observation.affordance.ObservationSection>();

        sections.add(locationSection(room));
        sections.add(exitsSection(room, world));
        sections.add(objectsSection(character, world));
        sections.add(charactersSection(character, world));
        sections.add(inventorySection(character));
        sections.add(goalsSection(goals));
        sections.add(recentActivitySection(drain));
        if (!drain.rememberedPartitions().isEmpty()) {
            sections.add(rememberedSection(drain, world));
        }
        sections.add(lastActionResultSection(character));

        return RENDERER.renderObservation(sections);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection locationSection(Room room) {
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.text(
                "Current Location", room.name() + ": " + room.description());
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection exitsSection(Room room, WorldState world) {
        var exits = room.adjacentRooms().stream()
                        .map(id -> {
                            Room target = world.room(id);
                            return new io.casehub.blocks.summarisation.observation.affordance.ObservableEntity(
                                    id, target.name(), target.description(),
                                    java.util.List.of(new io.casehub.blocks.summarisation.observation.affordance.Affordance("MOVE", "to walk here")));
                        })
                        .toList();
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.entities(
                "Exits", "No exits.", exits);}

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection objectsSection(
            CharacterState character, WorldState world) {
        List<GameObject> objects = world.visibleObjects(character.currentRoom(), character.agentId());
        var entities = objects.stream()
                              .map(ObservationBuilder::toObservableEntity)
                              .toList();
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.entities(
                "Visible Objects", "Nothing notable here.", entities);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservableEntity toObservableEntity(GameObject obj) {
        var affordances = new java.util.ArrayList<io.casehub.blocks.summarisation.observation.affordance.Affordance>();
        if (obj.interactable()) {
            affordances.add(new io.casehub.blocks.summarisation.observation.affordance.Affordance(
                    "INTERACT", null, obj.interactionRequires(), java.util.List.of()));
        }
        if (obj.portable()) {
            affordances.add(new io.casehub.blocks.summarisation.observation.affordance.Affordance(
                    "TAKE", "to pick up"));
        }
        if (!obj.usableWith().isEmpty()) {
            affordances.add(new io.casehub.blocks.summarisation.observation.affordance.Affordance(
                    "USE", null, null, obj.usableWith()));
        }
        return new io.casehub.blocks.summarisation.observation.affordance.ObservableEntity(
                obj.id(), obj.name(), obj.description(), affordances);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection charactersSection(
            CharacterState character, WorldState world) {
        List<CharacterState> others = world.charactersInRoom(character.currentRoom()).stream()
                                           .filter(c -> !c.agentId().equals(character.agentId()))
                                           .toList();
        var entities = others.stream()
                             .map(c -> new io.casehub.blocks.summarisation.observation.affordance.ObservableEntity(
                                     c.agentId(), c.name(), null,
                                     java.util.List.of(new io.casehub.blocks.summarisation.observation.affordance.Affordance("GIVE", "to hand an item"))))
                             .toList();
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.entities(
                "Characters Present", "You are alone.", entities);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection inventorySection(CharacterState character) {
        var items = character.inventory().stream()
                             .map(item -> "- " + item)
                             .toList();
        if (items.isEmpty()) {
            return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                    "Your Inventory", "You are carrying nothing.", java.util.List.of());
        }
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                "Your Inventory", null, character.inventory());
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection goalsSection(
            java.util.List<io.casehub.eidos.api.AgentGoal> goals) {
        if (goals.isEmpty()) {
            return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                    "Your Goals", "No specific goals.", java.util.List.of());
        }
        var items = goals.stream()
                         .sorted(java.util.Comparator.comparing(io.casehub.eidos.api.AgentGoal::priority)
                                                     .thenComparing(io.casehub.eidos.api.AgentGoal::name))
                         .map(g -> "[" + g.priority().name() + "] " + g.description())
                         .toList();
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                "Your Goals", null, items);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection recentActivitySection(
            io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain) {
        String text = drain.currentPartition().renderedText();
        if (text == null || text.isBlank()) {
            return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                    "Recent Activity", "The room is quiet.", java.util.List.of());
        }
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.text(
                "Recent Activity", text.strip());
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection rememberedSection(
            io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain, WorldState world) {
        var items   = new java.util.ArrayList<String>();
        var entries = new java.util.ArrayList<>(drain.rememberedPartitions().entrySet());
        java.util.Collections.reverse(entries);
        long now = System.currentTimeMillis();
        for (var entry : entries) {
            String         roomId     = entry.getKey();
            io.casehub.blocks.summarisation.observation.RememberedPartition remembered = entry.getValue();
            Room           room       = world.room(roomId);
            long           elapsed    = now - remembered.cachedAt();
            String         timeAgo    = formatElapsed(elapsed);
            String         text       = remembered.result().renderedText();
            if (text != null && !text.isBlank()) {
                items.add(room.name() + " (" + timeAgo + " ago): " + text.strip());
            }
        }
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                "Remembered", null, items);
    }

    private static String formatElapsed(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {return seconds + "s";}
        long minutes = seconds / 60;
        return minutes + " minute" + (minutes == 1 ? "" : "s");
    }


    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection lastActionResultSection(
            CharacterState character) {
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.text(
                "Last Action Result", character.lastActionResult());
    }
}
