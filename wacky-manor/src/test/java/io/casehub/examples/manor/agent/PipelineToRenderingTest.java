package io.casehub.examples.manor.agent;

import io.casehub.agency.perception.observation.affordance.ObservationSection;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline-to-rendering test: seeds MindMapStore with consolidation
 * artifacts, renders through CharacterCognition, and asserts on what
 * the character actually sees.
 *
 * No LLM calls. No Quarkus. Fast iteration on the rendering pipeline.
 */
class PipelineToRenderingTest {

    private InMemoryMindMapStore store;
    private String sgId;
    private static final String TENANT = "render-test";
    private static final String AGENT = "peter-perfect";

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        sgId = store.createSubgraph(
            new SubgraphInput("cognitive-" + AGENT, SubgraphTypes.COGNITIVE, null), TENANT);
    }

    @Test
    void generatedDescriptions_replaceHandAuthored() {
        seedDrive("gallantry", 0.9, "Hand-authored gallantry text");
        seedDrive("proving-worth", 0.7, "Hand-authored proving-worth text");
        seedGenerated("gallantry", "LLM somatic: heat behind sternum, shoulders squaring");
        seedGenerated("proving-worth", "LLM somatic: tension in hands, engine that won't idle");

        var content = renderWhatPulls();

        assertThat(content).contains("heat behind sternum");
        assertThat(content).contains("tension in hands");
        assertThat(content).doesNotContain("Hand-authored");
    }

    @Test
    void fallbackToLabelAndDegree_whenNoGenerated() {
        seedDrive("gallantry", 0.9, "Fallback gallantry description");

        var content = renderWhatPulls();

        assertThat(content).contains("GALLANTRY (0.9)");
        assertThat(content).doesNotContain("Fallback gallantry description");
    }

    @Test
    void drives_renderedInStrengthOrder_regardlessOfActivation() {
        seedDrive("gallantry", 0.9, "Gallantry text");
        seedDrive("proving-worth", 0.7, "Proving-worth text");
        seedGenerated("gallantry", "Gallantry: bright heat, spine locks straight");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, engine that won't idle");

        var section = renderInternalState(List.of("proving-worth"), "");

        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content.indexOf("Gallantry"))
                .as("Highest intensity (gallantry 0.9) must appear before lower (proving-worth 0.7) regardless of activation")
                .isLessThan(content.indexOf("Proving-worth"));
    }

    @Test
    void norms_renderedInPriorityOrder() {
        var config = new SocialConfig(
                List.of(), List.of(),
                List.of(
                        new SocialConfig.NormEntry("Low priority rule", 1),
                        new SocialConfig.NormEntry("High priority rule", 10),
                        new SocialConfig.NormEntry("Medium priority rule", 5)),
                List.of(), List.of(), Map.of(), null, null, List.of(),
                List.of(), List.of(), null, List.of(), null, null);

        var cognition = new CharacterCognition(AGENT, null, null, config, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(AGENT, AGENT, "room", 0.0, List.of()),
                List.of(), Map.of());

        var normsSection = sections.stream()
                                   .filter(s -> "Social Rules".equals(s.header()))
                                   .findFirst().orElseThrow();
        var items = ((ObservationSection.ItemList) normsSection).items();

        assertThat(items.get(0)).as("Highest priority norm first").contains("High priority");
        assertThat(items.get(1)).as("Medium priority norm second").contains("Medium priority");
        assertThat(items.get(2)).as("Lowest priority norm last").contains("Low priority");
    }

    @Test
    void formationMemories_renderedBySalienceNotAge() {
        var config = new SocialConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), null, null, List.of(),
                List.of(), List.of(), null,
                List.of(
                        new SocialConfig.FormationMemory(5, "Calm early memory", 0.3, 0.2, 0.3),
                        new SocialConfig.FormationMemory(16, "Traumatic late memory", -0.8, 0.95, 0.1),
                        new SocialConfig.FormationMemory(8, "Moderately emotional memory", 0.6, 0.5, 0.4)),
                null, null);

        var cognition = new CharacterCognition(AGENT, null, null, config, List.of());
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(AGENT, AGENT, "room", 0.0, List.of()),
                List.of(), Map.of());

        var memSection = sections.stream()
                                 .filter(s -> "Where You Come From".equals(s.header()))
                                 .findFirst().orElseThrow();
        var content = ((ObservationSection.TextBlock) memSection).content();

        assertThat(content.indexOf("Traumatic late"))
                .as("Highest salience memory (high arousal + extreme pleasure) should appear first")
                .isLessThan(content.indexOf("Moderately emotional"));
        assertThat(content.indexOf("Moderately emotional"))
                .as("Medium salience memory should appear before low salience")
                .isLessThan(content.indexOf("Calm early"));
    }


    @Test
    void differentiationText_appearsInOutput() {
        seedDrive("gallantry", 0.9, "g");
        seedDrive("proving-worth", 0.7, "pw");
        seedGenerated("gallantry", "Gallantry somatic");
        seedGenerated("proving-worth", "Proving-worth somatic");
        seedCompetition("gallantry", "proving-worth",
            "How they differ:\nOne is gesture. The other is result.\nNotice which moved your hands.");

        var content = renderWhatPulls();

        assertThat(content).contains("How they differ");
        assertThat(content).contains("Notice which moved your hands");
    }

    @Test
    void situationPriming_surfacesRelevantMemory() {
        seedDrive("gallantry", 0.9, "g");
        seedGenerated("gallantry", "Gallantry somatic");
        seedFormativeMemory("The girl from school was in trouble", "gallantry");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) -> {
            if (desc.contains("struggling"))
                return new io.casehub.neocortex.caps.ClassificationResult(List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6)));
            return new io.casehub.neocortex.caps.ClassificationResult(List.of());
        };

        var section = renderInternalStateWithClassifier(
            List.of("gallantry"),
            "Clara is struggling with a heavy door",
            classifier);

        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content).contains("A memory surfaces");
        assertThat(content).contains("girl from school");
    }

    @Test
    void fullPipeline_allSignalsPresent() {
        seedDrive("gallantry", 0.9, "fallback");
        seedDrive("proving-worth", 0.7, "fallback");
        seedGenerated("gallantry", "Gallantry: heat behind sternum, shoulders squaring.");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, engine that won't idle.");
        seedFormativeMemory("The girl from school was in trouble", "gallantry");
        seedProfile("gallantry", "acceptance:0.7");
        seedCompetition("gallantry", "proving-worth",
            "How they differ:\nOne pull is the gesture. The other is the result.\nNotice which one moved your hands.");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) -> {
            if (desc.contains("door"))
                return new io.casehub.neocortex.caps.ClassificationResult(List.of(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.7)));
            return new io.casehub.neocortex.caps.ClassificationResult(List.of());
        };

        var section = renderInternalStateWithClassifier(
            List.of("gallantry"),
            "Clara is struggling with the door",
            classifier, List.of("penelope-pitstop"));

        var content = ((ObservationSection.TextBlock) section).content();

        assertThat(content)
            .as("Should have generated gallantry description")
            .contains("heat behind sternum");
        assertThat(content)
            .as("Should have generated proving-worth description")
            .contains("tension in hands");
        assertThat(content)
            .as("Should have situation-primed memory")
            .contains("A memory surfaces");
        assertThat(content)
            .as("Should have differentiation text")
            .contains("How they differ");
        assertThat(content)
            .as("Should NOT have hand-authored fallback")
            .doesNotContain("fallback");
    }

    // --- helpers ---


    @Test
    void appraisalFiltering_excludesDrive_whenNoSituationOverlap() {
        seedDrive("gallantry", 0.5, "fallback-g");
        seedDrive("proving-worth", 0.5, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(new io.casehub.neocortex.caps.SituationActivation("competence", 0.7)));

        var section = renderInternalStateWithClassifier(
                List.of(), "PP is alone in the library, reviewing his notes", classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Proving-worth should render — competence overlaps with profile")
                .contains("tension in hands");
        assertThat(content)
                .as("Gallantry should NOT render — no acceptance/sharing in situation")
                .doesNotContain("the person exposed");
    }

    @Test
    void appraisalFiltering_includesDrive_whenSituationOverlaps() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(
                                                                                   new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6),
                                                                                   new io.casehub.neocortex.caps.SituationActivation("competence", 0.5)));

        var section = renderInternalStateWithClassifier(
                List.of(), "Clara is struggling with a heavy door", classifier,
                List.of("penelope-pitstop"));

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Gallantry should render — acceptance overlaps with profile + social context")
                .contains("the person exposed");
        assertThat(content)
                .as("Proving-worth should also render — competence overlaps")
                .contains("tension in hands");
    }

    @Test
    void appraisalFiltering_includesDrive_whenNoProfileExists() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(new io.casehub.neocortex.caps.SituationActivation("competence", 0.7)));

        var section = renderInternalStateWithClassifier(
                List.of(), "Clara is nearby in the library", classifier,
                List.of("penelope-pitstop"));

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Gallantry should render — no profile means no overlap filtering, social context present")
                .contains("the person exposed");
        assertThat(content)
                .as("Proving-worth should render — competence overlaps")
                .contains("tension in hands");
    }

    @Test
    void appraisalFiltering_reverseDirection_provingWorthRendersGallantryFiltered() {
        seedDrive("gallantry", 0.5, "fallback-g");
        seedDrive("proving-worth", 0.5, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(
                                                                                   new io.casehub.neocortex.caps.SituationActivation("mastery", 0.7),
                                                                                   new io.casehub.neocortex.caps.SituationActivation("competence", 0.6)));

        var section = renderInternalStateWithClassifier(
                List.of(), "PP examines the broken engine, turning parts in his hands", classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Proving-worth should render — mastery/competence overlap with profile")
                .contains("tension in hands");
        assertThat(content)
                .as("Gallantry should NOT render — no acceptance/sharing in a solo competence scenario")
                .doesNotContain("the person exposed");
    }

    @Test
    void appraisalFiltering_mixedSituation_bothDrivesRender() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(
                                                                                   new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6),
                                                                                   new io.casehub.neocortex.caps.SituationActivation("mastery", 0.5)));

        var section = renderInternalStateWithClassifier(
                List.of(), "Clara is struggling with a jammed engine — she needs help AND the machine needs fixing",
                classifier, List.of("penelope-pitstop"));

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Gallantry should render — acceptance overlaps + social context")
                .contains("the person exposed");
        assertThat(content)
                .as("Proving-worth should also render — mastery overlaps")
                .contains("tension in hands");
    }

    @Test
    void appraisalFiltering_classifierReturnsEmpty_allDrivesRender() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) -> new io.casehub.neocortex.caps.ClassificationResult(List.of());

        var section = renderInternalStateWithClassifier(
                List.of(), "An unfamiliar sound echoes through the corridor", classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Gallantry should render — classifier has no opinion, so no filtering")
                .contains("the person exposed");
        assertThat(content)
                .as("Proving-worth should render — classifier has no opinion, so no filtering")
                .contains("tension in hands");
    }

    @Test
    void classifierBypass_excludesDifferentiationTexts() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");
        seedCompetition("gallantry", "proving-worth",
                        "How they differ:\nOne is gesture. The other is result.\nNotice which moved your hands.");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) -> new io.casehub.neocortex.caps.ClassificationResult(List.of());

        var section = renderInternalStateWithClassifier(
                List.of(), "An unfamiliar sound echoes through the corridor", classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Drives should still render on classifier bypass")
                .contains("the person exposed")
                .contains("tension in hands");
        assertThat(content)
                .as("Differentiation texts must NOT appear when classifier bypasses — no situation context means no evidence of simultaneous activation (Barrett 2017)")
                .doesNotContain("How they differ");
    }


    @Test
    void appraisalFiltering_dominantDriveRenders_subdominantFiltered() {
        seedDrive("scheming", 0.9, "fallback-scheming");
        seedDrive("dominance", 0.5, "fallback-dominance");
        seedGenerated("scheming", "Scheming: cold clarity, the plan assembling itself");
        seedGenerated("dominance", "Dominance: jaw tightens, control slipping");
        seedProfile("scheming", "autonomy:0.8,competition:0.7");
        seedProfile("dominance", "agency_granted:0.8");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) ->
                                                                           new io.casehub.neocortex.caps.ClassificationResult(List.of(new io.casehub.neocortex.caps.SituationActivation("secure_attachment", 0.85)));

        var section = renderInternalStateWithClassifier(
                List.of(), "She trusts you completely", classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Drive at 0.9 renders — CAPS resting activation (0.178) exceeds threshold (substrate-mechanism gap)")
                .contains("cold clarity");
        assertThat(content)
                .as("Drive at 0.5 filtered — resting activation (0.055) insufficient, no situation overlap")
                .doesNotContain("jaw tightens");
    }

    @Test
    void appraisalFiltering_nullSituationText_allDrivesRender() {
        seedDrive("gallantry", 0.9, "fallback-g");
        seedDrive("proving-worth", 0.7, "fallback-pw");
        seedGenerated("gallantry", "Gallantry: bright heat, the person exposed");
        seedGenerated("proving-worth", "Proving-worth: tension in hands, the gap not yet built");
        seedProfile("gallantry", "acceptance:0.7,sharing:0.5");
        seedProfile("proving-worth", "competence:0.8,mastery:0.6");

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta, ctx) -> {
            throw new AssertionError("Classifier should not be called with null/blank situation");
        };

        var section = renderInternalStateWithClassifier(
                List.of(), null, classifier);

        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();
        assertThat(content)
                .as("Gallantry should render — null situation means no filtering")
                .contains("the person exposed");
        assertThat(content)
                .as("Proving-worth should render — null situation means no filtering")
                .contains("tension in hands");
    }


    // --- Golden prose rendering pattern tests ---

    @Test
    void bodyLocationAnchors_presentForEachDrive() {
        seedDrive("gallantry", 0.9, "g");
        seedDrive("proving-worth", 0.7, "pw");
        seedGenerated("gallantry", "Heat pools behind sternum, hands reach before deciding");
        seedGenerated("proving-worth", "Weight settles in shoulders, jaw clenches on its own");

        var content = renderWhatPulls();

        assertThat(content).contains("**hands, chest warmth** — GALLANTRY");
        assertThat(content).contains("**shoulders, jaw, grip** — PROVING WORTH");
    }

    @Test
    void bodyLocationAnchors_eachDriveGetsUniqueTerritoryLabel() {
        seedDrive("gallantry", 0.9, "g");
        seedDrive("protection", 0.6, "p");
        seedGenerated("gallantry", "Warmth in hands");
        seedGenerated("protection", "Weight drops to legs");

        var content = renderWhatPulls();

        assertThat(content).contains("**hands, chest warmth**");
        assertThat(content).contains("**legs, neck, positioning**");
        assertThat(content).as("Different drives have different body territories")
                .satisfies(c -> {
                    int gallantryIdx = c.indexOf("**hands, chest warmth**");
                    int protectionIdx = c.indexOf("**legs, neck, positioning**");
                    assertThat(gallantryIdx).isNotEqualTo(protectionIdx);
                });
    }

    @Test
    void paragraphStructure_drivesAreSeparatedByDoubleNewline() {
        seedDrive("gallantry", 0.9, "g");
        seedDrive("proving-worth", 0.7, "pw");
        seedGenerated("gallantry", "Heat pools behind sternum");
        seedGenerated("proving-worth", "Weight settles in shoulders");

        var content = renderWhatPulls();

        assertThat(content).contains("\n\n");
        String[] paragraphs = content.split("\n\n");
        assertThat(paragraphs.length).as("Multiple paragraphs separated by \\n\\n").isGreaterThanOrEqualTo(2);
    }

    @Test
    void driveDescriptions_appearsOnNewLineAfterLabel() {
        seedDrive("gallantry", 0.9, "g");
        seedGenerated("gallantry", "Your palms open before you have decided to help");

        var content = renderWhatPulls();

        assertThat(content).contains("GALLANTRY (0.9)\nYour palms open");
    }

    @Test
    void barrettInstruction_includesStructuralGuidance() {
        var config = ManorSocialConfigLoader.loadForProfile("generic").getOrDefault(AGENT, SocialConfig.empty());
        var cognition = new CharacterCognition(AGENT, null, null, config, List.of(),
                null, new ManorContextStrategy(), null, null,
                TENANT, store, null, null, false);
        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(AGENT, AGENT, "room", 0.0, List.of()),
                List.of(), Map.of());

        var howToFeel = sections.stream()
                                .filter(s -> "How to Feel".equals(s.header()))
                                .findFirst().orElseThrow();
        var content = ((ObservationSection.TextBlock) howToFeel).content();

        assertThat(content).contains("STRUCTURE IN YOUR ASIDE");
        assertThat(content).contains("bold body-location anchor");
        assertThat(content).contains("discovery verbs");
        assertThat(content).contains("subordinate clause");
    }

    @Test
    void hoodedClawDrives_renderWithCorrectBodyLocations() {
        var hcStore = new InMemoryMindMapStore();
        var hcSgId = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        hcStore.addNode(NodeInput.of("scheming", hcSgId)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", "hooded-claw",
                        "drive-type", "scheming", "intensity", "0.9", "description", "s")), TENANT);
        hcStore.addNode(NodeInput.of("gloating", hcSgId)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", "hooded-claw",
                        "drive-type", "gloating", "intensity", "0.7", "description", "g")), TENANT);

        hcStore.addNode(NodeInput.of("drive-description-scheming", hcSgId)
                .withTraits(Set.of("DriveDescriptionGenerated"))
                .withProperties(Map.of("agent-id", "hooded-claw", "drive-type", "scheming",
                        "generated-description", "Your fingertips trace possibilities before your eyes confirm them")), TENANT);
        hcStore.addNode(NodeInput.of("drive-description-gloating", hcSgId)
                .withTraits(Set.of("DriveDescriptionGenerated"))
                .withProperties(Map.of("agent-id", "hooded-claw", "drive-type", "gloating",
                        "generated-description", "Behind your sternum, warmth pools — a private heat")), TENANT);

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config = configs.getOrDefault("hooded-claw", SocialConfig.empty());
        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                null, new ManorContextStrategy(), null, null,
                TENANT, hcStore, null, null, true);
        var section = cognition.renderInternalState(List.of(), "");
        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();

        assertThat(content).contains("**fingertips, periphery, visual field** — SCHEMING");
        assertThat(content).contains("**sternum, breastbone** — GLOATING");
        assertThat(content).as("Scheming uses fingertips territory, not sternum")
                .satisfies(c -> assertThat(c.indexOf("fingertips, periphery")).isLessThan(c.indexOf("fingertips trace")));
    }

    private void seedDrive(String type, double intensity, String description) {
        store.addNode(NodeInput.of(type, sgId)
            .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", AGENT,
                "drive-type", type, "intensity", String.valueOf(intensity),
                "description", description)), TENANT);
    }

    private void seedGenerated(String driveType, String description) {
        store.addNode(NodeInput.of("drive-description-" + driveType, sgId)
            .withTraits(Set.of("DriveDescriptionGenerated"))
            .withProperties(Map.of("agent-id", AGENT, "drive-type", driveType,
                "generated-description", description)), TENANT);
    }

    private void seedProfile(String driveType, String inferredInputs) {
        store.addNode(NodeInput.of("drive-profile-" + driveType, sgId)
            .withTraits(Set.of("DriveProfileInferred"))
            .withProperties(Map.of("agent-id", AGENT, "drive-type", driveType,
                "inferred-inputs", inferredInputs)), TENANT);
    }

    private void seedCompetition(String driveA, String driveB, String diffText) {
        store.addNode(NodeInput.of("competition-" + driveA + "-" + driveB, sgId)
            .withTraits(Set.of("DriveCompetitionInferred"))
            .withProperties(Map.of("agent-id", AGENT,
                "drive-a", driveA, "drive-b", driveB,
                "differentiation-text", diffText)), TENANT);
    }

    private void seedFormativeMemory(String text, String driveType) {
        store.addNode(NodeInput.of(text, sgId)
            .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", AGENT,
                "situation-types", "formation," + driveType)), TENANT);
    }

    private String renderWhatPulls() {
        var section = renderInternalState(List.of(), "");
        assertThat(section).isNotNull();
        return ((ObservationSection.TextBlock) section).content();
    }

    private ObservationSection renderInternalState(List<String> activated, String situation) {
        return renderInternalStateWithClassifier(activated, situation, null);
    }

    private ObservationSection renderInternalStateWithClassifier(
            List<String> activated, String situation,
            io.casehub.neocortex.caps.SituationClassifier classifier) {
        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config = configs.getOrDefault(AGENT, SocialConfig.empty());
        var cognition = new CharacterCognition(AGENT, null, null, config, List.of(),
            null, new ManorContextStrategy(), null, null,
            TENANT, store, null, classifier, true);
        return cognition.renderInternalState(activated, situation != null ? situation : "");
    }

    private ObservationSection renderInternalStateWithClassifier(
            List<String> activated, String situation,
            io.casehub.neocortex.caps.SituationClassifier classifier,
            Collection<String> nearbyAgentIds) {
        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config  = configs.getOrDefault(AGENT, SocialConfig.empty());
        var cognition = new CharacterCognition(AGENT, null, null, config, List.of(),
                                               null, new ManorContextStrategy(), null, null,
                                               TENANT, store, null, classifier, true);
        return cognition.renderInternalState(activated, situation != null ? situation : "",
                                             nearbyAgentIds);
    }

}
