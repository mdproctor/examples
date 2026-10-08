package io.casehub.examples.manor.agent;

public final class ObservationBuilder {

    private static final io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer RENDERER =
            new io.casehub.blocks.summarisation.observation.affordance.AffordanceRenderer();

    private final io.casehub.blocks.summarisation.observation.affordance.WorldObservationProvider worldProvider;
    private final io.casehub.blocks.summarisation.observation.affordance.ObservationPipeline      pipeline;
    private final java.util.Set<String>                                                           observerTags;

    private io.casehub.examples.manor.model.CharacterState                                            character;
    private java.util.List<io.casehub.eidos.api.AgentGoal>                                            goals                = java.util.List.of();
    private io.casehub.blocks.summarisation.observation.PartitionedDrain<String>                      drain;
    private java.util.List<io.casehub.neocortex.memory.Memory>                                        memories             = java.util.List.of();
    private java.util.List<io.casehub.neocortex.memory.Memory>                                        reflections          = java.util.List.of();
    private java.util.Map<String, java.util.List<io.casehub.neocortex.memory.Memory>>                 relationshipMemories = java.util.Map.of();
    private java.util.List<io.casehub.blocks.summarisation.observation.affordance.ObservationSection> cognitiveSections    = java.util.List.of();
    private java.util.List<String>                                                                    taggedSections       = java.util.List.of();

    public ObservationBuilder(io.casehub.blocks.summarisation.observation.affordance.WorldObservationProvider worldProvider,
                              io.casehub.blocks.summarisation.observation.affordance.ObservationPipeline pipeline,
                              java.util.Set<String> observerTags) {
        this.worldProvider = worldProvider;
        this.pipeline      = pipeline;
        this.observerTags  = observerTags != null ? observerTags : java.util.Set.of();
    }

    public ObservationBuilder withCharacter(io.casehub.examples.manor.model.CharacterState character) {
        this.character = character;
        return this;
    }

    public ObservationBuilder withGoals(java.util.List<io.casehub.eidos.api.AgentGoal> goals) {
        this.goals = goals != null ? goals : java.util.List.of();
        return this;
    }

    public ObservationBuilder withDrain(io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain) {
        this.drain = drain;
        return this;
    }

    public ObservationBuilder withMemories(java.util.List<io.casehub.neocortex.memory.Memory> memories,
                                           java.util.List<io.casehub.neocortex.memory.Memory> reflections,
                                           java.util.Map<String, java.util.List<io.casehub.neocortex.memory.Memory>> relationshipMemories) {
        this.memories             = memories != null ? memories : java.util.List.of();
        this.reflections          = reflections != null ? reflections : java.util.List.of();
        this.relationshipMemories = relationshipMemories != null ? relationshipMemories : java.util.Map.of();
        return this;
    }

    public ObservationBuilder withCognitiveSections(java.util.List<io.casehub.blocks.summarisation.observation.affordance.ObservationSection> sections) {
        this.cognitiveSections = sections != null ? sections : java.util.List.of();
        return this;
    }

    public ObservationBuilder withTaggedSections(java.util.List<String> sections) {
        this.taggedSections = sections != null ? sections : java.util.List.of();
        return this;
    }

    public String build() {
        var sections = new java.util.ArrayList<io.casehub.blocks.summarisation.observation.affordance.ObservationSection>();

        if (worldProvider != null) {
            sections.addAll(worldProvider.worldSections());
        }

        for (var entry : relationshipMemories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.relationshipNotesSection(
                        entry.getKey(), entry.getValue()));
            }
        }
        sections.add(inventorySection(character));
        var thinking = currentThinkingSection(character);
        if (thinking != null) {sections.add(thinking);}

        for (var cs : cognitiveSections) {
            sections.add(cs);
        }

        sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.goalsSection(goals));
        planSections(character).forEach(sections::add);
        if (drain != null) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.recentActivitySection(drain));
        }
        if (!memories.isEmpty()) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.pastExperienceSection(memories));
        }
        if (!reflections.isEmpty()) {
            sections.add(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.insightsSection(reflections));
        }
        sections.add(lastActionResultSection(character));

        var filtered = pipeline != null
                       ? pipeline.apply(sections, observerTags)
                       : sections.stream()
                                 .map(s -> s instanceof io.casehub.blocks.summarisation.observation.affordance.AnnotatedSection a ? a.section() : s)
                                 .toList();

        var sb = new StringBuilder();
        if (!taggedSections.isEmpty()) {
            for (var tagged : taggedSections) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(tagged);
            }
            sb.append("\n\n");
        }
        sb.append(RENDERER.renderObservation(filtered));
        return sb.toString();
    }

    public static String buildObservation(io.casehub.blocks.summarisation.observation.affordance.WorldObservationProvider worldProvider,
                                          io.casehub.blocks.summarisation.observation.affordance.ObservationPipeline pipeline,
                                          java.util.Set<String> observerTags,
                                          io.casehub.examples.manor.model.CharacterState character,
                                          java.util.List<io.casehub.eidos.api.AgentGoal> goals,
                                          io.casehub.blocks.summarisation.observation.PartitionedDrain<String> drain,
                                          java.util.List<io.casehub.neocortex.memory.Memory> memories,
                                          java.util.List<io.casehub.neocortex.memory.Memory> reflections,
                                          java.util.Map<String, java.util.List<io.casehub.neocortex.memory.Memory>> relationshipMemories) {
        return new ObservationBuilder(worldProvider, pipeline, observerTags)
                       .withCharacter(character)
                       .withGoals(goals)
                       .withDrain(drain)
                       .withMemories(memories, reflections, relationshipMemories)
                       .build();
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
