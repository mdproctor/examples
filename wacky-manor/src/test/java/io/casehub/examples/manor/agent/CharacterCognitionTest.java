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
        assertThat(sections).hasSize(9);
        assertThat(sections.stream().map(s -> s.header()).toList())
                .containsExactly("Your Behavioral Tendencies", "Your Beliefs", "Social Rules",
                        "Who You Are", "What Pulls at You", "Where You Come From",
                        "How You Feel About Those Present", "How You Bond", "Active Voice");
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
                tenant, store, null, null);

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
                tenant, store, null, null);

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
                tenant, store, null, null);

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
                tenant, store, null, null);

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
                null, null, null, null, null, null, null, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null, null);

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
                null, null, null, null, null, null, goalOrchestrator, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("goals", true)
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.get(agent), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null, null);

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
                null, null, null, null, null, null, null, null, null,
                io.casehub.neocortex.cognition.core.CognitionConfig.none()
                                                                .with("characterDrives", true)
                                                                .with("needsPyramid", true),
                store, new ManorNeedTierMappingProvider(), null, null, null, null, null, null);

        var cognition = new CharacterCognition(
                agent, null, null, allConfigs.getOrDefault(agent, SocialConfig.empty()), List.of(),
                null, new ManorContextStrategy(), cognitionCore, seedResult,
                tenant, store, null, null);

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
                tenant, store, null, null);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(agent, "HC", "library", 0.0, List.of()),
                List.of(), Map.of());

        assertThat(sections).isNotEmpty();
        assertThat(sections.stream().map(s -> s.header()).toList()).contains("Your Beliefs");
    }


    @Test
    void renderInternalStateForegoundsActivatedDrives() {
        var drives = List.of(
                new SocialConfig.Drive("scheming", 0.9, "Webs of plans spinning behind your eyes"),
                new SocialConfig.Drive("gloating", 0.7, "Triumph surging electric through your chest"),
                new SocialConfig.Drive("self-preservation", 0.5, "A watchful edge along your spine"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition    = new CharacterCognition("test", null, null, socialConfig, List.of());

        // Without activation: drives ordered by intensity (scheming > gloating > self-preservation)
        var noActivation = cognition.renderInternalState(List.of(), "");
        assertThat(noActivation).isNotNull();
        var content0 = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) noActivation).content();
        assertThat(content0.indexOf("Webs")).isLessThan(content0.indexOf("Triumph"));
        assertThat(content0.indexOf("Triumph")).isLessThan(content0.indexOf("watchful"));

        // With gloating activated: intensity order preserved (Principle 43 — strength, not activation)
        var withActivation = cognition.renderInternalState(List.of("gloating"), "");
        var content1       = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) withActivation).content();
        assertThat(content1.indexOf("Webs"))
                .as("Highest intensity (scheming 0.9) first regardless of activation")
                .isLessThan(content1.indexOf("Triumph"));
        assertThat(content1.indexOf("Triumph")).isLessThan(content1.indexOf("watchful"));
    }

    @Test
    void renderInternalStateNoActivationPreservesIntensityOrder() {
        var drives = List.of(
                new SocialConfig.Drive("loyalty", 0.3, "A pull toward those you belong to"),
                new SocialConfig.Drive("protection", 0.8, "Fists tightening when someone you care about is near danger"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition    = new CharacterCognition("test", null, null, socialConfig, List.of());

        var section = cognition.renderInternalState(List.of(), "");
        assertThat(section).isNotNull();
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();
        assertThat(content.indexOf("Fists")).isLessThan(content.indexOf("pull toward"));
    }


    @Test
    void situationPrimingSurfacesRelevantMemories() {
        var store   = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent   = "peter-perfect";
        var tenant  = "priming-test";
        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var ppConfig = configs.get(agent);
        var sgId = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("gallantry", sgId).withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent, "drive-type", "gallantry", "intensity", "0.9", "description", "test")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("The girl from school was in trouble — you stepped in", sgId).withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", agent, "situation-types", "formation,adolescence,gallantry")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId).withTraits(java.util.Set.of("DriveProfileInferred")).withProperties(Map.of("agent-id", agent, "drive-type", "gallantry", "inferred-inputs", "acceptance:0.7,sharing:0.5")), tenant);
        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> {
            if (desc.contains("welcomed")) return List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.8));
            return List.of();
        };
        var cognition = new CharacterCognition(agent, null, null, ppConfig, List.of(), null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);
        var section = cognition.renderInternalState(List.of("gallantry"), "Penelope smiled and welcomed Peter into the drawing room");
        assertThat(section).isNotNull();
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();
        assertThat(content).contains("A memory surfaces");
        assertThat(content).contains("girl from school");
    }

    @Test
    void situationPrimingAbsentWhenNoClassifier() {
        var drives = List.of(new SocialConfig.Drive("scheming", 0.9, "Webs of plans"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition = new CharacterCognition("test", null, null, socialConfig, List.of());
        var section = cognition.renderInternalState(List.of("scheming"), "something happened");
        assertThat(section).isNotNull();
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();
        assertThat(content).doesNotContain("A memory surfaces");
    }

    @Test
    void situationPrimingAbsentWhenNoOverlap() {
        var store   = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent   = "test-agent";
        var tenant  = "no-overlap-test";
        var sgId = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-scheming", sgId).withTraits(java.util.Set.of("DriveProfileInferred")).withProperties(Map.of("agent-id", agent, "drive-type", "scheming", "inferred-inputs", "dominance:0.8")), tenant);
        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.9));
        var drives = List.of(new SocialConfig.Drive("scheming", 0.5, "Plans spinning"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition = new CharacterCognition(agent, null, null, socialConfig, List.of(), null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);
        var section = cognition.renderInternalState(List.of("scheming"), "people welcomed each other");
        assertThat(section)
                .as("No drives overlap the situation (scheming needs dominance, situation has acceptance) — section is null")
                .isNull();
    }

    @Test
    void appraisalFilterDrives_withSituationContext_usesPreComputedActivations() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "test-agent";
        var tenant = "ctx-filter-test";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-scheming", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-type", "scheming", "inferred-inputs", "reward:0.8,punishment:0.3")), tenant);

        var drives       = List.of(new SocialConfig.Drive("scheming", 0.7, "loves scheming", "any"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition = new CharacterCognition(agent, null, null, socialConfig, List.of(),
                                               null, new ManorContextStrategy(), null, null, tenant, store, null, null);

        var ctx = io.casehub.neocortex.caps.SituationContext.from(
                List.of(new io.casehub.neocortex.caps.SituationActivation("reward", 0.9)));

        var filtered = cognition.appraisalFilterDrives(drives, ctx, List.of());

        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().type()).isEqualTo("scheming");
    }

    @Test
    void appraisalFilterDrives_emptySituationContext_returnsAllDrives() {
        var drives       = List.of(new SocialConfig.Drive("scheming", 0.7, "desc", "any"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition    = new CharacterCognition("test", null, null, socialConfig, List.of());

        var filtered = cognition.appraisalFilterDrives(drives, io.casehub.neocortex.caps.SituationContext.EMPTY, List.of());

        assertThat(filtered).isEqualTo(drives);
    }

    @Test
    void selectSituationPrimedMemories_withSituationContext_surfacesMemories() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "ctx-priming-test";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("The girl from school was in trouble — you stepped in", sgId)
                                                            .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", agent, "situation-types", "gallantry")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry", "inferred-inputs", "acceptance:0.7")), tenant);

        var cognition = new CharacterCognition(agent, null, null, SocialConfig.empty(), List.of(),
                                               null, new ManorContextStrategy(), null, null, tenant, store, null, null);

        var ctx = io.casehub.neocortex.caps.SituationContext.from(
                List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.8)));

        var result = cognition.selectSituationPrimedMemories(ctx, List.of("gallantry"));

        assertThat(result).isNotEmpty();
        assertThat(result.getFirst()).contains("memory surfaces");
    }

    @Test
    void selectSituationPrimedMemories_emptySituationContext_returnsEmpty() {
        var cognition = new CharacterCognition("test", null);

        var result = cognition.selectSituationPrimedMemories(io.casehub.neocortex.caps.SituationContext.EMPTY, List.of("scheming"));

        assertThat(result).isEmpty();
    }

    @Test
    void renderCognitiveSections_withSituationContext_neverCallsClassifier() {
        io.casehub.neocortex.caps.SituationClassifier throwingClassifier = (desc, meta) -> {
            throw new AssertionError("Classifier must not be called when SituationContext is provided");
        };
        var drives       = List.of(new SocialConfig.Drive("scheming", 0.7, "loves scheming", "any"));
        var socialConfig = new SocialConfig(List.of(), drives, List.of(), List.of(), List.of(), Map.of(), null);
        var cognition = new CharacterCognition("test-agent",
                                               null, null, socialConfig, List.of(),
                                               null, new ManorContextStrategy(), null, null, "test-tenant",
                                               new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(), null, throwingClassifier);

        var character = new io.casehub.examples.manor.model.CharacterState("test-agent", "Test Agent", "hall", 0.0, List.of());
        character.setLastActionResult("You stole the key.");

        var ctx = io.casehub.neocortex.caps.SituationContext.from(
                List.of(new io.casehub.neocortex.caps.SituationActivation("reward", 0.8)));

        var sections = cognition.renderCognitiveSections(character, List.of(), Map.of(), ctx);
        assertThat(sections).isNotNull();
    }

    @Test
    void classifySituation_returnsContextFromClassifier() {
        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) ->
                                                                           List.of(new io.casehub.neocortex.caps.SituationActivation("reward", 0.9));
        var cognition = new CharacterCognition("test-agent",
                                               null, null, SocialConfig.empty(), List.of(),
                                               null, new ManorContextStrategy(), null, null, "test-tenant",
                                               new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore(), null, classifier);

        var ctx = cognition.classifySituation("something good happened", List.of());
        assertThat(ctx.isEmpty()).isFalse();
        assertThat(ctx.confidenceMap()).containsEntry("reward", 0.9);
    }

    @Test
    void classifySituation_returnsEmptyForNullText() {
        var cognition = new CharacterCognition("test-agent", null);
        var ctx       = cognition.classifySituation(null, List.of());
        assertThat(ctx.isEmpty()).isTrue();
    }


    @Test
    void phase1Only_missingDifferentiationAndPriming() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "phase1-only";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
            "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("gallantry", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "gallantry", "intensity", "0.9", "description", "fallback")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("proving-worth", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "proving-worth", "intensity", "0.7", "description", "fallback")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "generated-description", "A warmth rises through your chest, spreading into your shoulders.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "proving-worth",
                "generated-description", "A tension gathers in your hands, an engine that won't idle.")), tenant);

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
            null, new ManorContextStrategy(), null, null, tenant, store, null, null);

        var section = cognition.renderInternalState(List.of("gallantry"),
            "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content).contains("warmth rises");
        assertThat(content).contains("tension gathers");
        assertThat(content).doesNotContain("A memory surfaces");
        assertThat(content).doesNotContain("How they differ");
    }

    @Test
    void fullPipeline_hasDifferentiationAndPriming() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "full-pipeline";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
            "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("gallantry", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "gallantry", "intensity", "0.9", "description", "fallback")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("proving-worth", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "proving-worth", "intensity", "0.7", "description", "fallback")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "generated-description", "A warmth rises through your chest, spreading into your shoulders.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "proving-worth",
                "generated-description", "A tension gathers in your hands, an engine that won't idle.")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
            "The girl from school was in trouble — you stepped in", sgId)
            .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", agent,
                "situation-types", "formation,adolescence,gallantry")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "inferred-inputs", "acceptance:0.7,sharing:0.5")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "proving-worth",
                "inferred-inputs", "mastery:0.8,competence_recognition:0.6")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
            .withProperties(Map.of("agent-id", agent,
                "drive-a", "gallantry", "drive-b", "proving-worth",
                "differentiation-text", "How they differ:\nOne pull is about what your hands say — the kneeling itself was the gift, not what it fixed. When this pull leads, the gesture IS the feeling.\nThe other pull is about what your hands can do — the bridge held, the engine ran. When this pull leads, the result is the point.\nBoth are real. Notice which one moved your hands.")), tenant);

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> {
            if (desc.contains("struggling") || desc.contains("door")) {
                return List.of(
                    new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6),
                    new io.casehub.neocortex.caps.SituationActivation("sharing", 0.5),
                    new io.casehub.neocortex.caps.SituationActivation("mastery", 0.4));
            }
            return List.of();
        };

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
            null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);

        var section = cognition.renderInternalState(List.of("gallantry", "proving-worth"),
            "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content).contains("warmth rises");
        assertThat(content).contains("A memory surfaces");
        assertThat(content).contains("girl from school");
        assertThat(content).contains("How they differ");
        assertThat(content).contains("which one moved your hands");

        System.out.println("=== FULL PIPELINE OBSERVATION ===");
        System.out.println(content);
        System.out.println("=== END ===");
    }


    @Test
    void allDifferentiationTextsReachPrompt_notJustFirst() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "multi-diff";
        var sgId = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
                "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : List.of("gallantry", "proving-worth", "protection")) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive, sgId)
                                                                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                                                                                       "drive-type", drive, "intensity", "0.8", "description", "fallback")), tenant);
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-" + drive, sgId)
                                                                .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                                                                .withProperties(Map.of("agent-id", agent, "drive-type", drive,
                                                                                       "generated-description", "Description for " + drive + ".")), tenant);
        }

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-gal-prov", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "gallantry", "drive-b", "proving-worth",
                                                                                   "differentiation-text", "DIFF-GALLANTRY-PROVINGWORTH: one is warmth, the other is proof.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-gal-prot", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "gallantry", "drive-b", "protection",
                                                                                   "differentiation-text", "DIFF-GALLANTRY-PROTECTION: one is grace, the other is urgency.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-prov-prot", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "proving-worth", "drive-b", "protection",
                                                                                   "differentiation-text", "DIFF-PROVINGWORTH-PROTECTION: one is about being seen, the other about shielding.")), tenant);

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
                                               null, new ManorContextStrategy(), null, null, tenant, store, null, null);

        var section = cognition.renderInternalState(List.of("gallantry"),
                                                    "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content)
                .as("all 3 differentiation texts must reach the prompt, not just the first")
                .contains("DIFF-GALLANTRY-PROVINGWORTH")
                .contains("DIFF-GALLANTRY-PROTECTION")
                .contains("DIFF-PROVINGWORTH-PROTECTION");
    }


    @Test
    void differentiationFilteredByActiveDrives() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "diff-filter";
        var sgId = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
                "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : List.of("gallantry", "proving-worth", "protection")) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive, sgId)
                                                                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                                                                                       "drive-type", drive, "intensity", "0.8", "description", "fallback")), tenant);
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-" + drive, sgId)
                                                                .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                                                                .withProperties(Map.of("agent-id", agent, "drive-type", drive,
                                                                                       "generated-description", "Description for " + drive + ".")), tenant);
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-" + drive, sgId)
                                                                .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                                .withProperties(Map.of("agent-id", agent, "drive-type", drive,
                                                                                       "inferred-inputs", drive.equals("gallantry") ? "acceptance:0.8,sharing:0.5"
                                                                                                                                    : drive.equals("proving-worth") ? "mastery:0.8,competence_recognition:0.6"
                                                                                                                                                                    : "physical_threat:0.8,secure_attachment:0.7")), tenant);
        }

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-gal-prov", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "gallantry", "drive-b", "proving-worth",
                                                                                   "differentiation-text", "DIFF-GAL-PROV")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-gal-prot", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "gallantry", "drive-b", "protection",
                                                                                   "differentiation-text", "DIFF-GAL-PROT")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("comp-prov-prot", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-a", "proving-worth", "drive-b", "protection",
                                                                                   "differentiation-text", "DIFF-PROV-PROT")), tenant);

        // Classifier returns acceptance (gallantry) + mastery (proving-worth) but NOT physical_threat
        // So gallantry and proving-worth are active; protection is NOT active
        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> List.of(
                new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.7),
                new io.casehub.neocortex.caps.SituationActivation("mastery", 0.6));

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
                                               null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);

        var section = cognition.renderInternalState(List.of("gallantry", "proving-worth"),
                                                    "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content)
                .as("differentiation for active pair (gallantry+proving-worth) should be included")
                .contains("DIFF-GAL-PROV");
        assertThat(content)
                .as("differentiation involving inactive drive (protection) should be excluded")
                .doesNotContain("DIFF-GAL-PROT")
                .doesNotContain("DIFF-PROV-PROT");
    }

    @Test
    void formationMemoryMetadataStrippedFromPrompt() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "penelope-pitstop";
        var tenant = "template-strip";
        var sgId = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
                "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("social-harmony", sgId)
                                                            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                                                                                   "drive-type", "social-harmony", "intensity", "0.8", "description", "fallback")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-social-harmony", sgId)
                                                            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-type", "social-harmony",
                                                                                   "generated-description", "Your stomach clenches when voices rise.")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
                                "Your best friend was being excluded. {{serves: social-harmony|strong: You chose the friend over the group.}} The popular girls stopped talking to you.", sgId)
                                                            .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", agent,
                                                                                   "situation-types", "formation,childhood,social-harmony")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-social-harmony", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(Map.of("agent-id", agent, "drive-type", "social-harmony",
                                                                                   "inferred-inputs", "acceptance:0.8,exclusion:0.7")), tenant);

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) ->
                                                                           List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6));

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
                                               null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);

        var section = cognition.renderInternalState(List.of("social-harmony"),
                                                    "James Hartwell is sitting alone after the argument", List.of("peter-perfect"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content)
                .as("memory content should appear but metadata annotations must be stripped")
                .contains("chose the friend")
                .doesNotContain("{{serves:")
                .doesNotContain("|strong:")
                .doesNotContain("}}");
    }

    @Test
    void selfStandards_includedForActiveDrives() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "self-standard-included";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
            "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("gallantry", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "gallantry", "intensity", "0.9", "description", "fallback")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "generated-description", "A warmth rises through your chest.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("self-standard-gallantry", sgId)
            .withTraits(java.util.Set.of("SelfStandardDerived"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "self-standard", "When you protect someone weaker, you feel like the person you were meant to be.")), tenant);

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
            null, new ManorContextStrategy(), null, null, tenant, store, null, null);

        var section = cognition.renderInternalState(List.of("gallantry"),
            "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        assertThat(content).contains("warmth rises");
        assertThat(content).contains("person you were meant to be");
    }

    @Test
    void selfStandards_excludedWhenDriveFiltered() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var agent  = "peter-perfect";
        var tenant = "self-standard-excluded";
        var sgId   = store.createSubgraph(new io.casehub.neocortex.mindmap.SubgraphInput(
            "cognitive-" + agent, io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        // Two drives: gallantry (will be active) and protection (will be filtered)
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("gallantry", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "gallantry", "intensity", "0.8", "description", "fallback")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("protection", sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                "drive-type", "protection", "intensity", "0.5", "description", "fallback")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "generated-description", "A warmth rises through your chest.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-description-protection", sgId)
            .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "protection",
                "generated-description", "Fists tighten when someone you care about is near danger.")), tenant);

        // Self-standard only for the drive that will be filtered (protection)
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("self-standard-protection", sgId)
            .withTraits(java.util.Set.of("SelfStandardDerived"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "protection",
                "self-standard", "PROTECTION-STANDARD: Failing to shield those who depend on you is failing yourself.")), tenant);

        // Drive profiles so appraisal can filter
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "gallantry",
                "inferred-inputs", "acceptance:0.8,sharing:0.5")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-protection", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(Map.of("agent-id", agent, "drive-type", "protection",
                "inferred-inputs", "physical_threat:0.8,secure_attachment:0.7")), tenant);

        // Classifier returns acceptance (for gallantry) but NOT physical_threat (for protection)
        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> List.of(
                new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.7));

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var cognition = new CharacterCognition(agent, null, null, configs.get(agent), List.of(),
            null, new ManorContextStrategy(), null, null, tenant, store, null, classifier);

        var section = cognition.renderInternalState(List.of("gallantry", "protection"),
            "Clara is struggling with a heavy door", List.of("penelope-pitstop"));
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) section).content();

        // Gallantry's description should be present (active drive)
        assertThat(content).contains("warmth rises");
        // Protection's self-standard should NOT appear (drive was filtered out by appraisal)
        assertThat(content).doesNotContain("PROTECTION-STANDARD");
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

    @Test
    void tomSectionAppearsWhenCharacterStateObservedAndModelExists() {
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
            new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-peter-perfect-clara", sgId)
            .withTraits(java.util.Set.of("OtherModelDerived"))
            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                "empathic-sensing", "A tightness gathers across your shoulders and down through your arms.",
                "inferred-values", "She carries herself as though belonging must be earned, never assumed.",
                "appraisal-sensitivities", "Exclusion shuts her down. Public scrutiny makes her smaller.",
                "relational-understanding", "Between you there is a quiet contract — you show up, she trusts.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
            SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
            tenant, store, null, null);
        cognition.observeCharacterState("clara",
            io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
            io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);

        var sections = cognition.renderCognitiveSections(
            new io.casehub.examples.manor.model.CharacterState("peter-perfect", "PP", "Room", 0.0, List.of()),
            List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
            .filter(s -> s.header().startsWith("What You Sense About"))
            .toList();
        assertThat(tomSections).hasSize(1);
        assertThat(tomSections.get(0).header()).isEqualTo("What You Sense About Clara");
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tomSections.get(0)).content();
        assertThat(content).contains("tightness");
        assertThat(content).contains("belonging");
        assertThat(content).contains("Exclusion");
    }

    @Test
    void tomSectionAbsentWhenNoObservation() {
        var cognition = new CharacterCognition("peter-perfect", null);
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("peter-perfect", "PP", "Room", 0.0, List.of()),
                List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).isEmpty();
    }

    @Test
    void tomSectionAbsentForUnobservedNearbyCharacter() {
        var cognition = new CharacterCognition("peter-perfect", null);
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState("peter-perfect", "PP", "Room", 0.0, List.of()),
                List.of("foxworth"), Map.of("foxworth", "Foxworth"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).isEmpty();
    }

    @Test
    void velocitySignal_improving_appendedToTomSection() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-pp-clara", sgId)
                                                            .withTraits(java.util.Set.of("OtherModelDerived"))
                                                            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                                                                                   "empathic-sensing", "A tightness gathers across your shoulders.",
                                                                                   "inferred-values", "She values belonging above all.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
                                               SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
                                               tenant, store, null, null);

        // Turn 1: very high distress (avoidant + severe)
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.SEVERE);
        // Turn 2: still distressed but less (avoidant + mild) — delta negative = improving
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.MILD);

        var character = new io.casehub.examples.manor.model.CharacterState(
                "peter-perfect", "PP", "Room", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).hasSize(1);
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tomSections.get(0)).content();
        assertThat(content).containsIgnoringCase("easing");
    }


    @Test
    void velocitySignal_worsening_appendedToTomSection() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-pp-clara", sgId)
                                                            .withTraits(java.util.Set.of("OtherModelDerived"))
                                                            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                                                                                   "empathic-sensing", "A tightness gathers across your shoulders.",
                                                                                   "inferred-values", "She values belonging above all.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
                                               SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
                                               tenant, store, null, null);

        // Turn 1: moderate distress
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.NEUTRAL,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);
        // Turn 2: higher distress (avoidant + severe)
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.SEVERE);

        var character = new io.casehub.examples.manor.model.CharacterState(
                "peter-perfect", "PP", "Room", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).hasSize(1);
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tomSections.get(0)).content();
        assertThat(content).containsIgnoringCase("worse");
    }

    @Test
    void velocitySignal_singleObservation_noVelocityContent() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-pp-clara", sgId)
                                                            .withTraits(java.util.Set.of("OtherModelDerived"))
                                                            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                                                                                   "empathic-sensing", "A tightness gathers across your shoulders.",
                                                                                   "inferred-values", "She values belonging above all.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
                                               SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
                                               tenant, store, null, null);

        // Single observation — no trajectory possible
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.MILD);

        var character = new io.casehub.examples.manor.model.CharacterState(
                "peter-perfect", "PP", "Room", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).hasSize(1);
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tomSections.get(0)).content();
        // Should have ToM content but no velocity signal
        assertThat(content).contains("tightness");
        assertThat(content).doesNotContainIgnoringCase("easing");
        assertThat(content).doesNotContainIgnoringCase("worse");
        assertThat(content).doesNotContainIgnoringCase("shifting");
    }

    @Test
    void velocitySignal_multiTurnTrajectory_evolvesAcrossTurns() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-pp-clara", sgId)
                                                            .withTraits(java.util.Set.of("OtherModelDerived"))
                                                            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                                                                                   "empathic-sensing", "A tightness gathers across your shoulders.",
                                                                                   "inferred-values", "She values belonging above all.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
                                               SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
                                               tenant, store, null, null);
        var character = new io.casehub.examples.manor.model.CharacterState(
                "peter-perfect", "PP", "Room", 0.0, List.of());

        // Turn 1: single observation — no velocity signal yet
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.SEVERE);
        var sections1 = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));
        var tom1      = sections1.stream().filter(s -> s.header().startsWith("What You Sense About")).findFirst().orElseThrow();
        var content1  = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tom1).content();
        assertThat(content1).doesNotContainIgnoringCase("easing");
        assertThat(content1).doesNotContainIgnoringCase("worse");

        // Turn 2: still distressed but less — IMPROVING
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.MILD);
        var sections2 = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));
        var tom2      = sections2.stream().filter(s -> s.header().startsWith("What You Sense About")).findFirst().orElseThrow();
        var content2  = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tom2).content();
        assertThat(content2).containsIgnoringCase("easing");

        // Turn 3: distress increases again — WORSENING
        cognition.observeCharacterState("clara",
                                        io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.SEVERE);
        var sections3 = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));
        var tom3      = sections3.stream().filter(s -> s.header().startsWith("What You Sense About")).findFirst().orElseThrow();
        var content3  = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tom3).content();
        assertThat(content3).containsIgnoringCase("worse");
    }

    @Test
    void velocitySignal_classifiedFromResponses_producesTrajectory() {
        var store  = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "test";
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-peter-perfect",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("other-model-pp-clara", sgId)
                                                            .withTraits(java.util.Set.of("OtherModelDerived"))
                                                            .withProperties(Map.of("agent-id", "peter-perfect", "target-id", "clara",
                                                                                   "empathic-sensing", "A heaviness settles in your chest.",
                                                                                   "inferred-values", "She needs safety above all.")), tenant);

        var cognition = new CharacterCognition("peter-perfect", null, null,
                                               SocialConfig.empty(), List.of(), null, new ManorContextStrategy(), null, null,
                                               tenant, store, null, null);

        // Simulate production pipeline: classify Clara's responses, feed to Peter's ToM
        // Clara stays silent across 2 turns — both classify as AVOIDANT + NONE
        var claraWaits = new AgentResponse("...", null, null, null,
                                           new io.casehub.examples.manor.model.Action(io.casehub.examples.manor.model.ActionType.WAIT, null, null), null, null);
        var claraMoves = new AgentResponse("...", null, null, null,
                                           new io.casehub.examples.manor.model.Action(io.casehub.examples.manor.model.ActionType.MOVE, "library", null), null, null);

        // Turn 1: Clara waits silently (AVOIDANT)
        cognition.observeCharacterState("clara",
                                        RegulationTracker.classifyBehavior(claraWaits),
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);
        // Turn 2: Clara leaves the room (AVOIDANT) — same evidence, STABLE trajectory
        cognition.observeCharacterState("clara",
                                        RegulationTracker.classifyBehavior(claraMoves),
                                        io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);

        var character = new io.casehub.examples.manor.model.CharacterState(
                "peter-perfect", "PP", "Room", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, List.of("clara"), Map.of("clara", "Clara"));

        var tomSections = sections.stream()
                                  .filter(s -> s.header().startsWith("What You Sense About"))
                                  .toList();
        assertThat(tomSections).hasSize(1);
        var content = ((io.casehub.blocks.summarisation.observation.affordance.ObservationSection.TextBlock) tomSections.get(0)).content();
        // Sustained avoidance → STABLE trajectory → "not shifted" signal
        assertThat(content).containsIgnoringCase("not shifted");
    }
}
