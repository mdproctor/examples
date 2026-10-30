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

        assertThat(content).contains("STRUCTURE IN YOUR THINKING");
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

    /**
     * Golden prose target: HC aside event 2 — entrance hall, all characters present.
     *
     * This test reproduces the EXACT scenario conditions of the golden prose and
     * dumps the FULL prompt the LLM receives. Iterate on the pipeline components
     * until this prompt produces golden-prose-quality thinking output.
     *
     * Golden target (event 2 thinking):
     *   "Every person in this room is a piece I have already begun to position —
     *    my hearing sorting their positions before my eyes confirm them.
     *    **Clara signs the guest book** with that open, trusting flourish and
     *    something warm and unhurried fills my chest..."
     */
    @Test
    void goldenScenario_hcEntranceHall_fullPromptDump() {
        var hcStore = new InMemoryMindMapStore();
        var hcSg = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        // HC drives — same as golden prose scenario
        seedDriveIn(hcStore, hcSg, "hooded-claw", "scheming", 0.9);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "self-preservation", 0.7);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "dominance", 0.6);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "gloating", 0.7);

        // P22 descriptions — golden-prose-quality with contrast and discovery verbs
        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "scheming",
                "Your fingertips trace possibilities before your eyes confirm them — " +
                "peripheral vision sharpening to map exits, sight lines, who watches whom. " +
                "Not the sternum's slow warmth, not the spine's territorial weight. " +
                "This is faster, colder: attention that inventories a room the way other " +
                "people hum a song. Time compresses around the mapping. Your hands stay " +
                "loose at your sides but your body is already three moves ahead.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "gloating",
                "Behind your sternum, warmth pools — not the fingertip clarity of tracking " +
                "a plan, not the spine's territorial straightening. This is slower, heavier: " +
                "a private heat that settles when the pieces click into place and no one else " +
                "sees it yet. Your jaw loosens. Your shoulders drop. Time stretches around " +
                "the knowing, and you want to stay inside it.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "dominance",
                "Your spine finds its vertical before you have decided to stand straight — " +
                "not the fingertips' scheming alertness, not the sternum's private warmth. " +
                "This is structural: jaw level, feet planted, weight even. The room should " +
                "organise around you. When it doesn't, something old tightens in the jaw, " +
                "a correction that predates Pemberton by decades.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "self-preservation",
                "Your ears read the room before your eyes do — not the fingertips' " +
                "scheming geometry, not the spine's ownership. A change in someone's " +
                "posture, a question asked too casually, and your hearing sharpens to " +
                "track it. The fine hairs on your forearms lift. Being watched doesn't " +
                "alarm you. What alarms you is not knowing who is watching.");

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config = configs.getOrDefault("hooded-claw", SocialConfig.empty());

        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                null, new ManorContextStrategy(), null, null,
                TENANT, hcStore, null, null, false);

        var nearbyAgents = List.of("penelope-pitstop", "peter-perfect", "dick-dastardly", "ant-hill-mob");
        var agentNames = Map.of(
                "penelope-pitstop", "Clara Bellingham",
                "peter-perfect", "James Hartwell",
                "dick-dastardly", "Reginald Foxworth",
                "ant-hill-mob", "The Brixton Boys");

        var character = new io.casehub.examples.manor.model.CharacterState(
                "hooded-claw", "Vincent Marsh", "entrance-hall", 0.0, List.of());
        character.setLastActionResult("You moved to Entrance Hall. Clara Bellingham signs the guest book.");

        var sections = cognition.renderCognitiveSections(character, nearbyAgents, agentNames);

        // Dump the FULL prompt for inspection
        var fullPrompt = new StringBuilder();
        for (var section : sections) {
            fullPrompt.append("--- ").append(section.header()).append(" ---\n");
            if (section instanceof ObservationSection.TextBlock tb) {
                fullPrompt.append(tb.content());
            } else if (section instanceof ObservationSection.ItemList il) {
                for (var item : il.items()) {
                    fullPrompt.append("• ").append(item).append("\n");
                }
            }
            fullPrompt.append("\n\n");
        }
        System.out.println("\n========== HC ENTRANCE HALL — FULL PROMPT ==========");
        System.out.println(fullPrompt);
        System.out.println("====================================================\n");

        // Structural assertions — the minimum the prompt must have
        var prompt = fullPrompt.toString();

        assertThat(prompt).as("Barrett instruction present")
                .contains("STRUCTURE IN YOUR THINKING");

        assertThat(prompt).as("Body-location anchor for scheming")
                .contains("**fingertips, periphery, visual field** — SCHEMING");

        assertThat(prompt).as("Body-location anchor for gloating")
                .contains("**sternum, breastbone** — GLOATING");

        assertThat(prompt).as("Contrast in gloating description")
                .contains("not the fingertip clarity");

        assertThat(prompt).as("Contrast in scheming description")
                .contains("Not the sternum");
    }

    private void seedDriveIn(InMemoryMindMapStore s, String sg, String agent, String type, double intensity) {
        s.addNode(NodeInput.of(type, sg)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agent,
                        "drive-type", type, "intensity", String.valueOf(intensity),
                        "description", type)), TENANT);
    }

    private void seedGeneratedIn(InMemoryMindMapStore s, String sg, String agent, String type, String desc) {
        s.addNode(NodeInput.of("drive-description-" + type, sg)
                .withTraits(Set.of("DriveDescriptionGenerated"))
                .withProperties(Map.of("agent-id", agent, "drive-type", type,
                        "generated-description", desc)), TENANT);
    }


    /**
     * Golden prose target: HC dialogue event 0 — entrance hall, welcoming guests.
     * <p>
     * Tests whether Barrett v3 scene-discovery instruction generalises to DIALOGUE
     * (event 1 proved it for aside). Same drives and P22 descriptions, different
     * lastActionResult reflecting the arrival scene.
     * <p>
     * Golden target (event 0 thinking):
     * "The foyer. My foyer. The chandelier casts a familiar pattern on the floor
     * and my chest settles into that architectural stillness... My spine finds
     * its vertical before I've decided to stand straight..."
     */
    @Test
    void goldenScenario_hcDialogue_entranceHall_fullPromptDump() {
        var hcStore = new InMemoryMindMapStore();
        var hcSg = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        // HC drives — same as golden prose scenario
        seedDriveIn(hcStore, hcSg, "hooded-claw", "scheming", 0.9);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "self-preservation", 0.7);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "dominance", 0.6);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "gloating", 0.7);

        // P22 descriptions — same golden-prose-quality descriptions
        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "scheming",
                        "Your fingertips trace possibilities before your eyes confirm them — " +
                        "peripheral vision sharpening to map exits, sight lines, who watches whom. " +
                        "Not the sternum's slow warmth, not the spine's territorial weight. " +
                        "This is faster, colder: attention that inventories a room the way other " +
                        "people hum a song. Time compresses around the mapping. Your hands stay " +
                        "loose at your sides but your body is already three moves ahead.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "gloating",
                        "Behind your sternum, warmth pools — not the fingertip clarity of tracking " +
                        "a plan, not the spine's territorial straightening. This is slower, heavier: " +
                        "a private heat that settles when the pieces click into place and no one else " +
                        "sees it yet. Your jaw loosens. Your shoulders drop. Time stretches around " +
                        "the knowing, and you want to stay inside it.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "dominance",
                        "Your spine finds its vertical before you have decided to stand straight — " +
                        "not the fingertips' scheming alertness, not the sternum's private warmth. " +
                        "This is structural: jaw level, feet planted, weight even. The room should " +
                        "organise around you. When it doesn't, something old tightens in the jaw, " +
                        "a correction that predates Pemberton by decades.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "self-preservation",
                        "Your ears read the room before your eyes do — not the fingertips' " +
                        "scheming geometry, not the spine's ownership. A change in someone's " +
                        "posture, a question asked too casually, and your hearing sharpens to " +
                        "track it. The fine hairs on your forearms lift. Being watched doesn't " +
                        "alarm you. What alarms you is not knowing who is watching.");

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config  = configs.getOrDefault("hooded-claw", SocialConfig.empty());

        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                                               null, new ManorContextStrategy(), null, null,
                                               TENANT, hcStore, null, null, false);

        var nearbyAgents = List.of("penelope-pitstop", "peter-perfect", "dick-dastardly", "ant-hill-mob");
        var agentNames = Map.of(
                "penelope-pitstop", "Clara Bellingham",
                "peter-perfect", "James Hartwell",
                "dick-dastardly", "Reginald Foxworth",
                "ant-hill-mob", "The Brixton Boys");

        var character = new io.casehub.examples.manor.model.CharacterState(
                "hooded-claw", "Vincent Marsh", "entrance-hall", 0.0, List.of());
        // Event 0: guests arriving — HC is the estate manager welcoming them
        character.setLastActionResult(
                "All guests have arrived in the Entrance Hall. The chandelier casts patterns on the marble floor. " +
                "Clara Bellingham stands near the door. James Hartwell scans the room. The Brixton Boys cluster near the entrance.");

        var sections = cognition.renderCognitiveSections(character, nearbyAgents, agentNames);

        // Dump the FULL prompt for inspection
        var fullPrompt = new StringBuilder();
        for (var section : sections) {
            fullPrompt.append("--- ").append(section.header()).append(" ---\n");
            if (section instanceof ObservationSection.TextBlock tb) {
                fullPrompt.append(tb.content());
            } else if (section instanceof ObservationSection.ItemList il) {
                for (var item : il.items()) {
                    fullPrompt.append("• ").append(item).append("\n");
                }
            }
            fullPrompt.append("\n\n");
        }
        System.out.println("\n========== HC DIALOGUE EVENT 0 — ENTRANCE HALL — FULL PROMPT ==========");
        System.out.println(fullPrompt);
        System.out.println("========================================================================\n");

        // Structural assertions — same pipeline, dialogue context
        var prompt = fullPrompt.toString();

        assertThat(prompt).as("Barrett instruction present")
                          .contains("STRUCTURE IN YOUR THINKING");

        assertThat(prompt).as("Body-location anchor for scheming")
                          .contains("**fingertips, periphery, visual field** — SCHEMING");

        assertThat(prompt).as("Body-location anchor for dominance")
                          .contains("**spine, jaw, feet** — DOMINANCE");

        assertThat(prompt).as("Body-location anchor for self-preservation")
                          .contains("**ears, hearing, peripheral awareness** — SELF PRESERVATION");

        assertThat(prompt).as("Contrast in dominance description")
                          .contains("not the fingertips' scheming alertness");

    }


    /**
     * Golden prose target: HC dialogue event 5 — entrance hall, Brixton Boys confrontation.
     * <p>
     * Tests self-preservation under direct threat: cornered near staircase, maintaining
     * Pemberton mask while body prepares for escape. Same drives and P22 descriptions,
     * different lastActionResult reflecting the confrontation.
     * <p>
     * Golden target (event 5 thinking):
     * "The Brixton Boys have cornered me near the staircase and every nerve fires —
     * the flat, total alertness that empties the world of everything except the
     * threat and the exits..."
     */
    @Test
    void goldenScenario_hcConfrontation_entranceHall_fullPromptDump() {
        var hcStore = new InMemoryMindMapStore();
        var hcSg = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        seedDriveIn(hcStore, hcSg, "hooded-claw", "scheming", 0.9);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "self-preservation", 0.7);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "dominance", 0.6);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "gloating", 0.7);

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "scheming",
                        "Your fingertips trace possibilities before your eyes confirm them — " +
                        "peripheral vision sharpening to map exits, sight lines, who watches whom. " +
                        "Not the sternum's slow warmth, not the spine's territorial weight. " +
                        "This is faster, colder: attention that inventories a room the way other " +
                        "people hum a song. Time compresses around the mapping. Your hands stay " +
                        "loose at your sides but your body is already three moves ahead.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "gloating",
                        "Behind your sternum, warmth pools — not the fingertip clarity of tracking " +
                        "a plan, not the spine's territorial straightening. This is slower, heavier: " +
                        "a private heat that settles when the pieces click into place and no one else " +
                        "sees it yet. Your jaw loosens. Your shoulders drop. Time stretches around " +
                        "the knowing, and you want to stay inside it.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "dominance",
                        "Your spine finds its vertical before you have decided to stand straight — " +
                        "not the fingertips' scheming alertness, not the sternum's private warmth. " +
                        "This is structural: jaw level, feet planted, weight even. The room should " +
                        "organise around you. When it doesn't, something old tightens in the jaw, " +
                        "a correction that predates Pemberton by decades.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "self-preservation",
                        "Your ears read the room before your eyes do — not the fingertips' " +
                        "scheming geometry, not the spine's ownership. A change in someone's " +
                        "posture, a question asked too casually, and your hearing sharpens to " +
                        "track it. The fine hairs on your forearms lift. Being watched doesn't " +
                        "alarm you. What alarms you is not knowing who is watching.");

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config  = configs.getOrDefault("hooded-claw", SocialConfig.empty());

        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                                               null, new ManorContextStrategy(), null, null,
                                               TENANT, hcStore, null, null, false);

        var nearbyAgents = List.of("ant-hill-mob");
        var agentNames   = Map.of("ant-hill-mob", "The Brixton Boys");

        var character = new io.casehub.examples.manor.model.CharacterState(
                "hooded-claw", "Vincent Marsh", "entrance-hall", 0.0, List.of());
        // Event 5: Brixton Boys have cornered HC near the staircase
        character.setLastActionResult(
                "The Brixton Boys have moved to block the corridor. Danny is staring at you steadily. " +
                "Tommy is positioned near the servants' passage. Sparks leans against the wall near the drawing room door. " +
                "You are near the staircase. Clara and the others are not present.");

        var sections = cognition.renderCognitiveSections(character, nearbyAgents, agentNames);

        var fullPrompt = new StringBuilder();
        for (var section : sections) {
            fullPrompt.append("--- ").append(section.header()).append(" ---\n");
            if (section instanceof ObservationSection.TextBlock tb) {
                fullPrompt.append(tb.content());
            } else if (section instanceof ObservationSection.ItemList il) {
                for (var item : il.items()) {
                    fullPrompt.append("• ").append(item).append("\n");
                }
            }
            fullPrompt.append("\n\n");
        }
        System.out.println("\n========== HC CONFRONTATION EVENT 5 — ENTRANCE HALL — FULL PROMPT ==========");
        System.out.println(fullPrompt);
        System.out.println("=============================================================================\n");

        var prompt = fullPrompt.toString();

        assertThat(prompt).as("Barrett instruction present")
                          .contains("STRUCTURE IN YOUR THINKING");

        assertThat(prompt).as("Body-location anchor for self-preservation")
                          .contains("**ears, hearing, peripheral awareness** — SELF PRESERVATION");

        assertThat(prompt).as("Body-location anchor for scheming")
                          .contains("**fingertips, periphery, visual field** — SCHEMING");

        assertThat(prompt).as("Self-preservation description includes forearm reference")
                          .contains("fine hairs on your forearms");
    }

    /**
     * Golden prose target: HC aside event 2 — library, alone.
     * <p>
     * HC alone in the library. Pemberton mask drops. Body claims room via
     * shelves/books memory. Inscription on mantelpiece sharpens into focus.
     * Exposure awareness — if someone entered, they'd see him studying it.
     * <p>
     * No threat scenario — gloating is legitimate here (alone, private
     * satisfaction of territorial claim and plan's first thread).
     * <p>
     * Golden target (event 2 thinking):
     * "Alone. The library door closes behind me and the Pemberton mask drops...
     * My shoulders settle into a different alignment — wider, claiming more
     * of the doorframe than Pemberton would..."
     */
    @Test
    void goldenScenario_hcAside_library_fullPromptDump() {
        var hcStore = new InMemoryMindMapStore();
        var hcSg = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        seedDriveIn(hcStore, hcSg, "hooded-claw", "scheming", 0.9);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "self-preservation", 0.7);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "dominance", 0.6);
        seedDriveIn(hcStore, hcSg, "hooded-claw", "gloating", 0.7);

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "scheming",
                        "Your fingertips trace possibilities before your eyes confirm them — " +
                        "peripheral vision sharpening to map exits, sight lines, who watches whom. " +
                        "Not the sternum's slow warmth, not the spine's territorial weight. " +
                        "This is faster, colder: attention that inventories a room the way other " +
                        "people hum a song. Time compresses around the mapping. Your hands stay " +
                        "loose at your sides but your body is already three moves ahead.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "gloating",
                        "Behind your sternum, warmth pools — not the fingertip clarity of tracking " +
                        "a plan, not the spine's territorial straightening. This is slower, heavier: " +
                        "a private heat that settles when the pieces click into place and no one else " +
                        "sees it yet. Your jaw loosens. Your shoulders drop. Time stretches around " +
                        "the knowing, and you want to stay inside it.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "dominance",
                        "Your spine finds its vertical before you have decided to stand straight — " +
                        "not the fingertips' scheming alertness, not the sternum's private warmth. " +
                        "This is structural: jaw level, feet planted, weight even. The room should " +
                        "organise around you. When it doesn't, something old tightens in the jaw, " +
                        "a correction that predates Pemberton by decades.");

        seedGeneratedIn(hcStore, hcSg, "hooded-claw", "self-preservation",
                        "Your ears read the room before your eyes do — not the fingertips' " +
                        "scheming geometry, not the spine's ownership. A change in someone's " +
                        "posture, a question asked too casually, and your hearing sharpens to " +
                        "track it. The fine hairs on your forearms lift. Being watched doesn't " +
                        "alarm you. What alarms you is not knowing who is watching.");

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config  = configs.getOrDefault("hooded-claw", SocialConfig.empty());

        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                                               null, new ManorContextStrategy(), null, null,
                                               TENANT, hcStore, null, null, false);

        // HC is alone in the library
        var nearbyAgents = List.<String>of();
        var agentNames   = Map.<String, String>of();

        var character = new io.casehub.examples.manor.model.CharacterState(
                "hooded-claw", "Vincent Marsh", "library", 0.0, List.of());
        // Event 2: HC enters library alone, discovers inscription on mantelpiece
        character.setLastActionResult(
                "You enter the library alone. The door closes behind you. Tall ceilings and shelves climbing " +
                "toward them. Leather chairs, dust motes in the light. On the mantelpiece above the fireplace, " +
                "an inscription is carved into the stone — partially obscured by age but legible.");

        var sections = cognition.renderCognitiveSections(character, nearbyAgents, agentNames);

        var fullPrompt = new StringBuilder();
        for (var section : sections) {
            fullPrompt.append("--- ").append(section.header()).append(" ---\n");
            if (section instanceof ObservationSection.TextBlock tb) {
                fullPrompt.append(tb.content());
            } else if (section instanceof ObservationSection.ItemList il) {
                for (var item : il.items()) {
                    fullPrompt.append("• ").append(item).append("\n");
                }
            }
            fullPrompt.append("\n\n");
        }
        System.out.println("\n========== HC ASIDE EVENT 2 — LIBRARY (ALONE) — FULL PROMPT ==========");
        System.out.println(fullPrompt);
        System.out.println("=======================================================================\n");

        var prompt = fullPrompt.toString();

        assertThat(prompt).as("Barrett instruction present")
                          .contains("STRUCTURE IN YOUR THINKING");

        assertThat(prompt).as("Body-location anchor for dominance")
                          .contains("**spine, jaw, feet** — DOMINANCE");

        assertThat(prompt).as("Body-location anchor for scheming")
                          .contains("**fingertips, periphery, visual field** — SCHEMING");

        assertThat(prompt).as("Body-location anchor for gloating")
                          .contains("**sternum, breastbone** — GLOATING");

        assertThat(prompt).as("Dominance description includes Pemberton reference")
                          .contains("correction that predates Pemberton");
    }

    /**
     * Golden prose target: DD dialogue event 10 — entrance hall, arrival.
     * <p>
     * Tests a DIFFERENT character (Reginald Foxworth / dick-dastardly).
     * DD never uses bold anchors — continuous flow, one dense paragraph.
     * Different drives: greed (gut/navel), recognition (upper chest/spine),
     * scheming (fingertips), gloating (sternum).
     * <p>
     * Golden target (event 10 thinking):
     * "The chandelier catches the light and something behind my navel opens —
     * a pull toward the gold and crystal that has no words..."
     */
    @Test
    void goldenScenario_ddDialogue_entranceHall_fullPromptDump() {
        var ddStore = new InMemoryMindMapStore();
        var ddSg = ddStore.createSubgraph(
                new SubgraphInput("cognitive-dick-dastardly", SubgraphTypes.COGNITIVE, null), TENANT);

        // DD drives — greed/recognition/scheming/gloating
        seedDriveIn(ddStore, ddSg, "dick-dastardly", "greed", 0.9);
        seedDriveIn(ddStore, ddSg, "dick-dastardly", "scheming", 0.85);
        seedDriveIn(ddStore, ddSg, "dick-dastardly", "recognition", 0.8);
        seedDriveIn(ddStore, ddSg, "dick-dastardly", "gloating", 0.7);

        // P22 descriptions — golden-prose-quality, body-anchored with contrast structure
        seedGeneratedIn(ddStore, ddSg, "dick-dastardly", "greed",
                        "Behind your navel, a hollow opens — not the fingertips' scheming fizz, " +
                        "not the chest's upward reaching for attention. This is lower, older: " +
                        "a pull toward value that has no words, just the incompleteness of proximity " +
                        "to something that isn't yours yet. Your fingers curl. Your eyes track it. " +
                        "The gap between the thing existing and you not having it sits in your gut " +
                        "like hunger.");

        seedGeneratedIn(ddStore, ddSg, "dick-dastardly", "recognition",
                        "Your spine reaches upward — not the fingertips' lateral scheming, not " +
                        "the gut's hollow pull toward objects. This is vertical: an upward stretching " +
                        "toward something above you that hasn't happened yet. When eyes land on you, " +
                        "warmth floods your upper chest, sudden and sharp. When they slide away, the " +
                        "stretching returns, taut and unsatisfied. Your body angles toward the nearest " +
                        "person before you've decided to speak.");

        seedGeneratedIn(ddStore, ddSg, "dick-dastardly", "scheming",
                        "Your fingertips fizz before your mind catches up — not the gut's hollow " +
                        "pull toward objects, not the spine's upward reaching. This is lateral: " +
                        "angles, exits, contingencies mapping themselves through your peripheral " +
                        "vision. The steps assemble in your hands before the thought forms. Time " +
                        "compresses around the geometry. Your fingers trace the shape of something " +
                        "that hasn't happened yet.");

        seedGeneratedIn(ddStore, ddSg, "dick-dastardly", "gloating",
                        "Heat blooms behind your sternum — sudden, involuntary, spreading outward " +
                        "before you can contain it. Not the gut's acquisitive pull, not the spine's " +
                        "vertical reaching, not the fingertips' lateral planning. This is a grin " +
                        "pushing up from your breastbone, a warmth that sits heavy and satisfying " +
                        "when the gap between what they believe and what you know widens. Your jaw " +
                        "loosens. Your face splits before you can stop it. You don't try.");

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config  = configs.getOrDefault("dick-dastardly", SocialConfig.empty());

        var cognition = new CharacterCognition("dick-dastardly", null, null, config, List.of(),
                                               null, new ManorContextStrategy(), null, null,
                                               TENANT, ddStore, null, null, false);

        var nearbyAgents = List.of("hooded-claw", "penelope-pitstop", "peter-perfect", "ant-hill-mob");
        var agentNames = Map.of(
                "hooded-claw", "Vincent Marsh",
                "penelope-pitstop", "Clara Bellingham",
                "peter-perfect", "James Hartwell",
                "ant-hill-mob", "The Brixton Boys");

        var character = new io.casehub.examples.manor.model.CharacterState(
                "dick-dastardly", "Reginald Foxworth", "entrance-hall", 0.0, List.of());
        // Event 10: DD arrives at Doily Manor, chandelier, all guests present
        character.setLastActionResult(
                "You have arrived at Doily Manor. The entrance hall is grand — a chandelier catches the light " +
                "overhead, casting patterns on the marble floor. Vincent Marsh stands by the guest book. " +
                "Clara Bellingham is near the drawing room door. James Hartwell is scanning the room. " +
                "The Brixton Boys cluster near the entrance, watching.");

        var sections = cognition.renderCognitiveSections(character, nearbyAgents, agentNames);

        var fullPrompt = new StringBuilder();
        for (var section : sections) {
            fullPrompt.append("--- ").append(section.header()).append(" ---\n");
            if (section instanceof ObservationSection.TextBlock tb) {
                fullPrompt.append(tb.content());
            } else if (section instanceof ObservationSection.ItemList il) {
                for (var item : il.items()) {
                    fullPrompt.append("• ").append(item).append("\n");
                }
            }
            fullPrompt.append("\n\n");
        }
        System.out.println("\n========== DD DIALOGUE EVENT 10 — ENTRANCE HALL — FULL PROMPT ==========");
        System.out.println(fullPrompt);
        System.out.println("=========================================================================\n");

        var prompt = fullPrompt.toString();

        assertThat(prompt).as("Barrett instruction present")
                          .contains("STRUCTURE IN YOUR THINKING");

        assertThat(prompt).as("Body-location anchor for greed")
                          .contains("**gut, navel, ribs** — GREED");

        assertThat(prompt).as("Body-location anchor for recognition")
                          .contains("**upper chest, spine** — RECOGNITION");

        assertThat(prompt).as("Body-location anchor for scheming")
                          .contains("**fingertips, periphery, visual field** — SCHEMING");

        assertThat(prompt).as("Body-location anchor for gloating")
                          .contains("**sternum, breastbone** — GLOATING");

        assertThat(prompt).as("Greed description includes navel reference")
                          .contains("Behind your navel");

        assertThat(prompt).as("Dialogue form instruction present")
                          .contains("one dense paragraph");
    }

    @Test
    void goldenProse_hcGloating_descriptionRendersWithSternum() {
        var hcStore = new InMemoryMindMapStore();
        var hcSg = hcStore.createSubgraph(
                new SubgraphInput("cognitive-hooded-claw", SubgraphTypes.COGNITIVE, null), TENANT);

        hcStore.addNode(NodeInput.of("gloating", hcSg)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", "hooded-claw",
                        "drive-type", "gloating", "intensity", "0.7", "description", "g")), TENANT);
        hcStore.addNode(NodeInput.of("scheming", hcSg)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", "hooded-claw",
                        "drive-type", "scheming", "intensity", "0.9", "description", "s")), TENANT);

        // Golden-prose-quality description: sternum territory, contrast against scheming,
        // discovery verbs, warmth sensation
        hcStore.addNode(NodeInput.of("drive-description-gloating", hcSg)
                .withTraits(Set.of("DriveDescriptionGenerated"))
                .withProperties(Map.of("agent-id", "hooded-claw", "drive-type", "gloating",
                        "generated-description",
                        "Behind your sternum, warmth pools — not the fingertip clarity of " +
                        "tracking a plan, not the spine's territorial straightening. This is " +
                        "slower, heavier: a private heat that settles when the pieces click " +
                        "into place and no one else sees it yet. Your jaw loosens. Your " +
                        "shoulders drop. Time stretches around the knowing.")), TENANT);
        hcStore.addNode(NodeInput.of("drive-description-scheming", hcSg)
                .withTraits(Set.of("DriveDescriptionGenerated"))
                .withProperties(Map.of("agent-id", "hooded-claw", "drive-type", "scheming",
                        "generated-description",
                        "Your fingertips trace possibilities before your eyes confirm them. " +
                        "Peripheral vision sharpens — exits, sight lines, who is watching whom. " +
                        "Not the sternum warmth of savouring. This is colder, faster: attention " +
                        "that maps the room the way other people hum a song.")), TENANT);

        var configs = ManorSocialConfigLoader.loadForProfile("generic");
        var config = configs.getOrDefault("hooded-claw", SocialConfig.empty());
        var cognition = new CharacterCognition("hooded-claw", null, null, config, List.of(),
                null, new ManorContextStrategy(), null, null,
                TENANT, hcStore, null, null, true);
        var section = cognition.renderInternalState(List.of(), "");
        assertThat(section).isNotNull();
        var content = ((ObservationSection.TextBlock) section).content();

        // Gloating renders with sternum body-location anchor
        assertThat(content).contains("**sternum, breastbone** — GLOATING");

        // Gloating description includes contrast against scheming territory
        assertThat(content).contains("not the fingertip clarity");

        // Scheming renders with fingertips body-location anchor
        assertThat(content).contains("**fingertips, periphery, visual field** — SCHEMING");

        // Scheming description includes contrast against gloating territory
        assertThat(content).contains("Not the sternum warmth");

        // Both descriptions use discovery verbs
        assertThat(content).contains("pools");
        assertThat(content).contains("settles");
        assertThat(content).contains("trace");
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
