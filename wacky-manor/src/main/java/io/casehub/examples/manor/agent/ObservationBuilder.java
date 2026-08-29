package io.casehub.examples.manor.agent;

public final class ObservationBuilder {

    private static final io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer RENDERER =
            new io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer();

    public static String buildObservation(io.casehub.blocks.summarisation.observation.affordance.WorldObservationProvider worldProvider,
                                          io.casehub.blocks.summarisation.observation.affordance.ObservationPipeline pipeline,
                                          java.util.Set<String> observerTags,
                                          io.casehub.examples.manor.model.CharacterState character,
                                          java.util.List<io.casehub.eidos.api.AgentGoal> goals,
                                          io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain,
                                          java.util.List<io.casehub.neocortex.memory.Memory> memories,
                                          java.util.List<io.casehub.neocortex.memory.Memory> reflections,
                                          java.util.Map<String, java.util.List<io.casehub.neocortex.memory.Memory>> relationshipMemories) {
        var sections = new java.util.ArrayList<io.casehub.blocks.summarisation.observation.affordance.ObservationSection>();

        sections.addAll(worldProvider.worldSections());

        for (var entry : relationshipMemories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.relationshipNotesSection(
                        entry.getKey(), entry.getValue()));
            }
        }
        sections.add(inventorySection(character));
        var thinking = currentThinkingSection(character);
        if (thinking != null) {sections.add(thinking);}

        sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.goalsSection(goals));
        planSections(character).forEach(sections::add);
        sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.recentActivitySection(drain));
        if (memories != null && !memories.isEmpty()) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.pastExperienceSection(memories));
        }
        if (reflections != null && !reflections.isEmpty()) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.insightsSection(reflections));
        }
        sections.add(lastActionResultSection(character));

        var filtered = pipeline != null
                ? pipeline.apply(sections, observerTags)
                : sections.stream()
                      .map(s -> s instanceof io.casehub.blocks.summarisation.observation.affordance.AnnotatedSection a ? a.section() : s)
                      .toList();

        return RENDERER.renderObservation(filtered);
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection inventorySection(io.casehub.examples.manor.model.CharacterState character) {
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

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection currentThinkingSection(io.casehub.examples.manor.model.CharacterState character) {
        String thinking = character.currentThinking();
        if (thinking == null || thinking.isBlank()) {return null;}
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.text("Your Current Thinking", thinking);
    }

    private static java.util.List<io.casehub.blocks.summarisation.observation.affordance.ObservationSection> planSections(io.casehub.examples.manor.model.CharacterState character) {
        if (character.plans().isEmpty()) {return java.util.List.of();}
        return character.plans().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .<io.casehub.blocks.summarisation.observation.affordance.ObservationSection>map(e -> {
                            var plan = e.getValue();
                            var items = plan.steps().stream()
                                            .map(s -> "[" + s.status().name() + "] " + s.description())
                                            .toList();
                            return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.items(
                                    "Plan: " + e.getKey(), plan.rationale(), items);
                        })
                        .toList();
    }

    private static io.casehub.blocks.summarisation.observation.affordance.ObservationSection lastActionResultSection(io.casehub.examples.manor.model.CharacterState character) {
        return io.casehub.blocks.summarisation.observation.affordance.ObservationSection.text(
                "Last Action Result", character.lastActionResult());
    }
}
