package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.model.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterCognitionTest {

    @Test
    void computeImportanceMatchesExistingBehavior() {
        var cognition = new CharacterCognition("test-agent", null);
        assertThat(cognition.computeImportance(ActionType.STEAL)).isEqualTo(0.9);
        assertThat(cognition.computeImportance(ActionType.USE)).isEqualTo(0.8);
        assertThat(cognition.computeImportance(ActionType.TAKE)).isEqualTo(0.7);
        assertThat(cognition.computeImportance(ActionType.GIVE)).isEqualTo(0.7);
        assertThat(cognition.computeImportance(ActionType.PULL_ASIDE)).isEqualTo(0.7);
        assertThat(cognition.computeImportance(ActionType.INTERACT)).isEqualTo(0.6);
        assertThat(cognition.computeImportance(ActionType.MOVE)).isEqualTo(0.3);
        assertThat(cognition.computeImportance(ActionType.LOOK)).isEqualTo(0.2);
        assertThat(cognition.computeImportance(ActionType.WAIT)).isEqualTo(0.1);
        assertThat(cognition.computeImportance(null)).isEqualTo(0.5);
    }

    @Test
    void cognitiveSectionsEmptyBeforeWiring() {
        var cognition = new CharacterCognition("test-agent", null);
        var sections = cognition.renderCognitiveSections(
            new io.casehub.examples.manor.model.CharacterState("test-agent", "Test", "Room", 0.0, List.of()),
            List.of(), Map.of());
        assertThat(sections).isEmpty();
    }

    @Test
    void recallMemoriesReturnsEmptyWithNullService() {
        var cognition = new CharacterCognition("test-agent", null);
        assertThat(cognition.recallMemories(10)).isEmpty();
        assertThat(cognition.recallReflections(5)).isEmpty();
        assertThat(cognition.recallRelationships("other", 3)).isEmpty();
    }

    @Test
    void drivesRenderedWhenSocialConfigPresent() {
        var socialConfig = ManorSocialConfigLoader.load().get("hooded-claw");
        var cognition    = new CharacterCognition("hooded-claw", null, null, socialConfig, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("hooded-claw", "HC", "Room", 0.0, List.of()),
                List.of(), Map.of());
        assertThat(sections).noneMatch(s -> s.header().equals("Your Drives"));
    }

    @Test
    void constraintsNoLongerRenderedDirectly() {
        var constraint = new io.casehub.eidos.api.AgentConstraint(
                "test-constraint", "Never reveal your true identity",
                io.casehub.eidos.api.Visibility.PRIVATE, io.casehub.eidos.api.ConstraintSeverity.HARD);
        var cognition = new CharacterCognition("test", null, null, SocialConfig.empty(), List.of(constraint));
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("test", "Test", "Room", 0.0, List.of()),
                List.of(), Map.of());
        assertThat(sections).noneMatch(s -> s.header().equals("Your Principles"));
    }

    @Test
    void beliefsAndNormsRendered() {
        var socialConfig = ManorSocialConfigLoader.load().get("penelope-pitstop");
        var cognition    = new CharacterCognition("penelope-pitstop", null, null, socialConfig, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("penelope-pitstop", "Penelope", "Room", 0.0, List.of()),
                List.of(), Map.of());
        assertThat(sections).anyMatch(s -> s.header().equals("Your Beliefs"));
        assertThat(sections).anyMatch(s -> s.header().equals("Social Rules"));
    }

    @Test
    void allSectionsForFullyConfiguredCharacter() {
        var socialConfig = ManorSocialConfigLoader.load().get("hooded-claw");
        var cognition    = new CharacterCognition("hooded-claw", null, null, socialConfig, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("hooded-claw", "HC", "Room", 0.0, List.of()),
                List.of("penelope-pitstop"), Map.of("penelope-pitstop", "Penelope Pitstop"));
        assertThat(sections).hasSize(7);
        assertThat(sections.stream().map(s -> s.header()).toList())
                .containsExactly("Your Behavioral Tendencies", "Your Beliefs", "Social Rules",
                        "Who You Are", "How You Feel About Those Present",
                        "How You Bond", "Active Voice");
    }

    @Test
    void socialAwarenessAbsentWithoutSchemingDrives() {
        var socialConfig = ManorSocialConfigLoader.load().get("penelope-pitstop");
        var cognition = new CharacterCognition("penelope-pitstop", null, null, socialConfig, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("penelope-pitstop", "Penelope", "Room", 0.0, List.of()),
                List.of("hooded-claw"), Map.of("hooded-claw", "Hooded Claw"));
        assertThat(sections.stream().map(s -> s.header()).toList())
                .doesNotContain("Social Awareness");
    }

    @Test
    void socialAwarenessAbsentWhenCognitiveProfileNull() {
        var drives = List.of(new SocialConfig.Drive("scheming", 0.9, "Schemes"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition = new CharacterCognition("hooded-claw", null, null, socialConfig, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("hooded-claw", "HC", "Room", 0.0, List.of()),
                List.of("peter-perfect"), Map.of("peter-perfect", "Peter Perfect"));
        assertThat(sections.stream().map(s -> s.header()).toList())
                .doesNotContain("Social Awareness");
    }

    @Test
    void socialAwarenessUsesAdaptedDriveIntensitiesFromMindMap() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "adapted-drives-test";

        seeder.seed(agent, allConfigs.get(agent), tenant);

        // Verify static config has scheming at 0.9 (above threshold)
        assertThat(allConfigs.get(agent).drives().stream()
                             .filter(d -> "scheming".equals(d.type()))
                             .findFirst().orElseThrow().intensity()).isEqualTo(0.9);

        // Decay scheming drive to 0.1 in MindMap (below threshold)
        var subgraphs = store.listSubgraphs(tenant);
        var cognitiveSg = subgraphs.stream()
                                   .filter(s -> "cognitive".equals(s.type())).findFirst().orElseThrow();
        var allNodes = store.nodesIn(cognitiveSg.id(), tenant);

        allNodes.stream()
                .filter(n -> "drive-intensity".equals(n.properties().get("cognitiveKind")))
                .filter(n -> agent.equals(n.properties().get("agent-id")))
                .filter(n -> "scheming".equals(n.properties().get("drive-type")))
                .forEach(n -> store.updateNode(n.id(),
                                               io.casehub.neocortex.mindmap.NodeUpdate.empty()
                                                                                      .withPropertiesToSet(java.util.Map.of("intensity", "0.1")), tenant));

        // Also decay suspicion below threshold
        allNodes.stream()
                .filter(n -> "drive-intensity".equals(n.properties().get("cognitiveKind")))
                .filter(n -> agent.equals(n.properties().get("agent-id")))
                .filter(n -> "suspicion".equals(n.properties().get("drive-type")))
                .forEach(n -> store.updateNode(n.id(),
                                               io.casehub.neocortex.mindmap.NodeUpdate.empty()
                                                                                      .withPropertiesToSet(java.util.Map.of("intensity", "0.1")), tenant));

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), null, null,
                tenant, store, null);

        // Social awareness should be absent — adapted drives are below threshold
        // even though static config has scheming at 0.9
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "Room", 0.0, List.of()),
                List.of("peter-perfect"), Map.of("peter-perfect", "Peter Perfect"));
        assertThat(sections.stream().map(s -> s.header()).toList())
                .doesNotContain("Social Awareness");
    }

    @Test
    void socialAwarenessFallsBackToStaticConfigWhenNoMindMapNodes() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "fallback-test";

        // No seeding — MindMap has no drive-intensity nodes
        // Static config has scheming at 0.9 (above threshold), so social awareness should still gate on static config
        // But cognitiveProfile is null, so renderSocialAwareness returns early anyway
        // This test verifies the fallback path doesn't error when MindMap is empty
        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), null, null,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "Room", 0.0, List.of()),
                List.of("peter-perfect"), Map.of("peter-perfect", "Peter Perfect"));
        // Social awareness absent because cognitiveProfile is null, but no error from fallback
        assertThat(sections.stream().map(s -> s.header()).toList())
                .doesNotContain("Social Awareness");
    }

    @Test
    void behavioralCueReflectsStageGates() {
        var cognition = new CharacterCognition("test-agent", null);

        // Stranger — no behavioral cue
        assertThat(cognition.behavioralCue("stranger")).isNull();

        // Acquaintance — cooperation only
        assertThat(cognition.behavioralCue("acquaintance")).isEqualTo("Willing to cooperate");

        // Familiar — cooperation only
        assertThat(cognition.behavioralCue("familiar")).isEqualTo("Willing to cooperate");

        // Friend — cooperation + disclosure
        assertThat(cognition.behavioralCue("friend")).isEqualTo("Willing to cooperate and share openly");

        // Confidant — cooperation + disclosure
        assertThat(cognition.behavioralCue("confidant")).isEqualTo("Willing to cooperate and share openly");
    }


    @Test
    void beliefRenderingFromMindMapStore() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "rendering-test";

        seeder.seed(agent, allConfigs.get(agent), tenant);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), null, null,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        var beliefSection = sections.stream()
                                    .filter(s -> "Your Beliefs".equals(s.header()))
                                    .findFirst().orElseThrow();

        assertThat(((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.ItemList) beliefSection).items())
                .anyMatch(item -> item.contains("naive"));
    }

    @Test
    void revisedBeliefShowsRevisedMarker() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "revised-test";

        var seedResult = seeder.seed(agent, allConfigs.get(agent), tenant);

        var revisedNodeId = store.addNode(
                io.casehub.neocortex.mindmap.NodeInput.of(
                          "Penelope is more perceptive than she appears", seedResult.subgraphId())
                                                      .withConfidence(io.casehub.neocortex.cognitive.Confidence.inferred(0.6, java.time.Instant.now()))
                                                      .withProvenance("belief-revision")
                                                      .withTraits(java.util.Set.of("Belieflike"))
                                                      .withProperties(java.util.Map.of("subject", "penelope-awareness"))
                                                      .withPrincipalId(io.casehub.platform.api.identity.PrincipalId.agent(agent)),
                tenant);

        var subgraphs = store.listSubgraphs(tenant);
        var beliefSg = subgraphs.stream()
                                .filter(sg -> sg.name().equals("beliefs-" + agent))
                                .findFirst().orElseThrow();
        var originalId = store.nodesIn(beliefSg.id(), tenant).stream()
                              .filter(n -> n.traits().contains("Belieflike"))
                              .filter(n -> "penelope-awareness".equals(n.property("subject").orElse(null)))
                              .filter(n -> "manor-seed".equals(n.provenance()))
                              .map(io.casehub.neocortex.mindmap.MindMapNode::id)
                              .findFirst().orElseThrow();

        store.supersede(originalId, revisedNodeId, "Penelope demonstrated perceptiveness", tenant);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), null, null,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        var beliefSection = sections.stream()
                                    .filter(s -> "Your Beliefs".equals(s.header()))
                                    .findFirst().orElseThrow();

        assertThat(((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.ItemList) beliefSection).items())
                .anyMatch(item -> item.contains("[REVISED]") && item.contains("penelope-awareness"));
        assertThat(((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.ItemList) beliefSection).items())
                .noneMatch(item -> item.contains("naive"));
    }

    @Test
    void cognitionCoreSectionsIncludedInRendering() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "cognition-core-test";

        var seedResult = seeder.seed(agent, allConfigs.get(agent), tenant);

        var cognitionCore = new io.casehub.neocortex.cognition.core.CognitionCore(
                null, null, null, null, null, null, null, null, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        var headers = sections.stream().map(s -> s.header()).toList();
        assertThat(headers).contains("Character Motivations", "Inner Needs");

        var drivesSection = sections.stream()
                                    .filter(s -> "Character Motivations".equals(s.header()))
                                    .findFirst().orElseThrow();
        assertThat(drivesSection).isInstanceOf(io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock.class);
        var drivesContent = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) drivesSection).content();
        assertThat(drivesContent).contains("scheming");
        assertThat(drivesContent).contains("90%");
        assertThat(drivesContent).contains("self-preservation");
        assertThat(drivesContent).contains("dominance");

        var needsSection = sections.stream()
                                   .filter(s -> "Inner Needs".equals(s.header()))
                                   .findFirst().orElseThrow();
        var needsContent = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) needsSection).content();
        assertThat(needsContent).containsIgnoringCase("self-expression");
        assertThat(needsContent).containsIgnoringCase("safety");
    }

    @Test
    void goalsRenderedInObservationPipeline() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "goals-rendering-test";

        var seedResult = seeder.seed(agent, allConfigs.get(agent), tenant);

        var goalOrchestrator = new io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator(
                null, java.util.List.of(), null, java.util.Optional.empty(),
                null, null, null,
                io.casehub.neocortex.cognition.goal.GoalProposalConfig.defaults(),
                io.casehub.neocortex.cognition.goal.GoalEscalationConfig.defaults(),
                java.time.Clock.systemUTC());
        seeder.seedGoals(agent, allConfigs.get(agent), goalOrchestrator, tenant);

        var cognitionCore = new io.casehub.neocortex.cognition.core.CognitionCore(
                null, null, null, null, null, null, goalOrchestrator, null, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("goals", true)
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        var headers = sections.stream().map(s -> s.header()).toList();
        assertThat(headers).contains("Your current goals:");

        var goalsSection = sections.stream()
                                   .filter(s -> "Your current goals:".equals(s.header()))
                                   .findFirst().orElseThrow();
        assertThat(goalsSection).isInstanceOf(io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock.class);
        var goalsContent = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) goalsSection).content();
        assertThat(goalsContent).contains("Kill Penelope Pitstop");
        assertThat(goalsContent).contains("Stay in character");
        assertThat(goalsContent).contains("competence");
    }


    @Test
    void cognitionCoreNoSectionsWhenNoDrives() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "muttley";
        var tenant     = "no-drives-test";

        var seedResult = seeder.seed(agent, allConfigs.get(agent), tenant);

        var cognitionCore = new io.casehub.neocortex.cognition.core.CognitionCore(
                null, null, null, null, null, null, null, null, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.getOrDefault(agent, SocialConfig.empty()), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "Muttley", "library", 0.0, List.of()),
                List.of(), Map.of());

        var headers = sections.stream().map(s -> s.header()).toList();
        assertThat(headers).doesNotContain("Character Motivations");
        assertThat(headers).doesNotContain("Inner Needs");
    }

    @Test
    void cognitionCoreNullDoesNotBreakRendering() {
        var store      = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var seeder     = new ManorCognitiveSeeder(store);
        var allConfigs = ManorSocialConfigLoader.load();
        var agent      = "hooded-claw";
        var tenant     = "null-core-test";

        seeder.seed(agent, allConfigs.get(agent), tenant);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), null, null,
                tenant, store, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        assertThat(sections).isNotEmpty();
        assertThat(sections.stream().map(s -> s.header()).toList()).contains("Your Beliefs");
    }


    @Test
    void rendersTendenciesAsFirstCognitiveSection() {
        var socialConfig = ManorSocialConfigLoader.load().get("penelope-pitstop");
        var cognition = new CharacterCognition("penelope-pitstop", null, null,
                                               socialConfig, java.util.List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(
                        "penelope-pitstop", "Penelope", "Room", 0.0, java.util.List.of()),
                java.util.List.of(), java.util.Map.of());
        assertThat(sections).isNotEmpty();
        assertThat(sections.get(0).header()).isEqualTo("Your Behavioral Tendencies");
    }
}
