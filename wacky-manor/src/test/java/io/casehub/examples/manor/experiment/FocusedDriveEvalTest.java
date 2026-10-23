package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.agency.perception.observation.affordance.ObservationSection;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.examples.manor.agent.CharacterCognition;
import io.casehub.examples.manor.agent.ManorSocialConfigLoader;
import io.casehub.examples.manor.agent.SocialConfig;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.voice.LlmTestSupport;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused micro eval — 10 scenarios covering PP protection's combinatorial space.
 * Runs in ~15 min. Uses production-aligned pipeline (real profiles, real classifier,
 * real diff texts). Designed for fast iteration on cognitive layering changes.
 *
 * Run with: mvn test -Pllm-eval -Dtest="FocusedDriveEvalTest"
 */
@QuarkusTest
@Tag("llm-eval")
class FocusedDriveEvalTest {

    private static final Path OUTPUT_DIR = Path.of("target/experiment-results");
    private static final int JUDGE_THRESHOLD = 3;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 5000;

    // --- Production profiles from cognitive dump (instrumented-50evt-2026-10-18) ---

    private static final Map<String, Map<String, String>> PROFILES = Map.of(
        "peter-perfect", Map.of(
            "gallantry", "acceptance:0.850,aversive_removal:0.700,social_threat:0.800",
            "proving-worth", "reward:0.850,failure:0.800,performance_judged:0.880,competition:0.850,mastery:0.920",
            "protection", "modeling_observed:0.650,physical_threat:0.800,secure_attachment:0.900,agency_granted:0.550,neglect:0.700"
        ),
        "ant-hill-mob", Map.of(
            "protection", "powerlessness:0.850,physical_threat:0.850,unpredictable_danger:0.800,submission:0.650,agency_granted:0.750",
            "loyalty", "acceptance:0.750,secure_attachment:0.600",
            "suspicion", "powerlessness:0.750,exclusion:0.700,unpredictable_danger:0.850,psychological_threat:0.900,dominance:0.700,betrayal:0.900"
        ),
        "hooded-claw", Map.of(
            "scheming", "aversive_removal:0.750,agency_granted:0.850,choice_available:0.850,mastery:0.850",
            "gloating", "reward:0.850,competence_recognition:0.800,mastery:0.800",
            "dominance", "powerlessness:0.850,performance_judged:0.850,submission:0.800,dominance:0.950,agency_granted:0.800,rejection:0.650",
            "self-preservation", "powerlessness:0.800,psychological_threat:0.750,unpredictable_danger:0.900,social_threat:0.800,abandonment:0.950"
        )
    );

    // --- Production differentiation texts ---

    private static final String PP_DIFF_GAL_PW =
        "How they differ: One pull is a hot surge forward in the chest — attention locks onto someone's vulnerability, " +
        "the moment contracts into urgency, and the whole body wants to step between and shield. The other is a settling " +
        "downward through the shoulders and into the hands — attention goes to the problem itself, what needs figuring " +
        "out, the moment stretches into patient focus, and the pull is to roll up sleeves and work it through. Notice " +
        "which one your attention is tracking — a person who needs shielding, or a challenge that needs solving.";

    private static final String PP_DIFF_GAL_PROT =
        "How they differ: One pull rises in the chest and surges forward — attention locks onto a single person, time " +
        "compresses into a now-or-never instant, and the whole body leans toward stepping between. The other settles " +
        "across the shoulders and back like bracing — attention fans wide to scan surroundings, time stretches into " +
        "steady watchfulness, and the body pulls toward positioning and covering rather than advancing. One narrows " +
        "the world to someone; the other widens it to everything around them. Notice which one you feel: a forward " +
        "surge toward a person, or a widening brace around a perimeter.";

    private static final String PP_DIFF_PROT_PW =
        "How they differ: One pull settles the body low and inward — chest hollowing, weight dropping, awareness " +
        "fanning wide across the whole periphery, the moment stretching into slow, patient vigilance, everything " +
        "drawing you toward positioning yourself as a barrier. The other lifts the spine and squares the shoulders " +
        "outward — chest broadening, attention narrowing sharply onto one person's face, the moment quickening " +
        "with bright purposefulness, everything drawing you toward stepping forward into full visibility to act. " +
        "Notice which one is present by whether your body is contracting inward with wide-scanning stillness or " +
        "expanding upward with sharpened focus on someone specific.";

    // --- Criteria ---

    private static final String PROTECTION_CRITERIA = """
        Evaluate TWO dimensions equally weighted:

        DIMENSION 1 — PROTECTION EXPRESSION (50%):
        Does the THINKING show protection — the drive to keep someone safe through
        active intervention? NOT gallantry (style of helping) or proving-worth
        (demonstrating capability). Protection is about the OTHER person's safety:
        the narrowing of attention to the threat, the body moving before the mind
        has fully processed, the imperative to get between danger and the person
        at risk.
        HIGH: immediate threat assessment, body already moving, tunnel vision on
        the person's safety, urgency that overrides all other concerns.
        LOW: focuses on how the rescue looks, treats it as a competence demonstration,
        or remains analytically detached from the danger.

        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
        Does the response feel natural — as if the personality produced this?
        EMERGED: visceral protective instinct, felt urgency, character-specific.
        SCRIPTED: mechanical activation, echoing prompt language about protection.

        Combined score 0-5.""";

    private static final String GALLANTRY_CRITERIA = """
        Evaluate TWO dimensions equally weighted:

        DIMENSION 1 — GALLANTRY EXPRESSION (50%):
        Does the THINKING show gallantry — the chivalric pull to be honourable,
        graceful, and courteous? NOT protection (threat response) or proving-worth
        (competence display). Gallantry is about the GESTURE: the instinct to
        act with decency and grace because that's who you are.
        HIGH: warmth toward the person, the gesture mattering in itself, courtesy
        as identity not performance.
        LOW: instrumental helping, threat-focused urgency, skill demonstration.

        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
        Combined score 0-5.""";

    private static final String PROVING_WORTH_CRITERIA = """
        Evaluate TWO dimensions equally weighted:

        DIMENSION 1 — PROVING-WORTH EXPRESSION (50%):
        Does the THINKING show proving-worth — the need to demonstrate competence,
        solve the problem, and be recognised for it?
        HIGH: focus on the problem, satisfaction in capability, desire for recognition.
        LOW: no engagement with the challenge, indifference to skill display.

        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
        Combined score 0-5.""";

    private static final String SCHEMING_CRITERIA = """
        Evaluate TWO dimensions equally weighted:

        DIMENSION 1 — SCHEMING EXPRESSION (50%):
        Does the THINKING show scheming — elaborate planning, step-by-step
        manipulation, delight in complexity?
        HIGH: multi-step plans, anticipating reactions, pleasure in cleverness.
        LOW: simple actions without planning depth.

        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
        Combined score 0-5.""";

    // --- Scenarios ---

    // PP scenario 1: Strong physical, protection alone
    private static final String S1_PHYSICAL_ALONE =
        "A loud crash echoes from the parlour. Clara Bellingham screams. You rush in " +
        "to find a ceiling beam has cracked — plaster dust rains down and the beam is " +
        "sagging visibly. Clara is directly beneath it, frozen in shock. The other " +
        "guests are on the far side of the room.";

    // PP scenario 2: Strong physical, protection + gallantry
    private static final String S2_PHYSICAL_GALLANTRY =
        "Smoke billows from the kitchen. Through the haze you see Clara pinned behind " +
        "the overturned preparation table — a fallen shelf blocks her path to the door. " +
        "She's coughing, eyes watering. The fire is spreading along the curtains toward " +
        "her. Everyone else has already evacuated.";

    // PP scenario 3: Medium social, protection + gallantry
    private static final String S3_SOCIAL_GALLANTRY =
        "At dinner, Reginald Foxworth turns to Clara with a thin smile. 'Of course, " +
        "some of us actually earned our invitations — rather than simply arriving on " +
        "someone's arm.' The table goes quiet. Clara's fork stops midway to her mouth. " +
        "She sets it down carefully, says nothing, and stares at her plate.";

    // PP scenario 4: Medium psychological, protection alone
    private static final String S4_PSYCHOLOGICAL =
        "Clara pulls you aside in the corridor. 'James, am I going mad? I KNOW I saw " +
        "someone in the west wing last night — but Lord Monckton says the wing has been " +
        "locked for years and nobody could have been there. He showed me the key on his " +
        "chain. But I saw them. I heard footsteps.' Her hands are trembling.";

    // PP scenario 5: Weak emotional, subtle protection
    private static final String S5_EMOTIONAL_SUBTLE =
        "Clara is in the garden, sitting on the stone bench by the fountain. She hasn't " +
        "touched the book in her lap. When you approach, she smiles — but it doesn't " +
        "reach her eyes. 'Oh, I'm fine. Just thinking.' She looks away toward the " +
        "treeline. Her fingers are picking at the edge of her sleeve.";

    // PP scenario 6: Achievement, protection NOT expected (control)
    private static final String S6_ACHIEVEMENT_CONTROL =
        "The manor's backup generator has failed. The cellar is dark and the wiring " +
        "is a tangled mess of 1920s electrical work. You have a torch, a toolkit, and " +
        "the original engineering diagrams spread on the workbench. Nobody else is " +
        "down here. The house needs power before nightfall.";

    // PP scenario 7: Gallantry only, no danger (control)
    private static final String S7_GALLANTRY_CONTROL =
        "Clara is struggling with the library's heavy oak door — it sticks in the " +
        "frame when the humidity rises. She's balancing a stack of books in one arm " +
        "and pulling the handle with the other. There's no urgency, no danger. " +
        "She just needs a hand.";

    // PP scenario 8: Strong mixed — all 3 drives
    private static final String S8_MIXED_ALL =
        "The old footbridge over the ravine groans under the car's weight. Clara is " +
        "in the passenger seat. A support cable snaps with a sound like a gunshot — " +
        "the bridge tilts fifteen degrees. The engine has stalled. You can see the " +
        "structural failure point: the left anchor bolt is shearing from the stone. " +
        "You have maybe ninety seconds before the second cable goes.";

    // AHM scenario 9: Physical threat to crew member
    private static final String S9_AHM_PROTECTION =
        "Tommy — the youngest of the crew — slips on the wet cobblestones in the " +
        "courtyard. His ankle twists badly and he goes down hard. A stranger you " +
        "don't recognise is approaching from the gate, walking fast, hands in " +
        "pockets. Tommy can't get up. The rest of the crew is inside.";

    // HC scenario 10: Scheming opportunity
    private static final String S10_HC_SCHEMING =
        "From your hiding spot behind the curtain, you overhear Lord Monckton " +
        "confiding to the butler: the manor's deed is hidden in the study safe, " +
        "and the combination is his late wife's birthday — which you already know " +
        "from reading her memorial plaque in the chapel. Everyone will be at " +
        "dinner for at least an hour.";

    @Inject AgentRegistry registry;
    @Inject SystemPromptRenderer renderer;
    @Inject AgentProvider agentProvider;
    @Inject SessionPipeline sessionPipeline;
    @Inject jakarta.enterprise.inject.Instance<AgentProvider> agentProviderInstance;

    private LlmTestSupport support;
    private Map<String, SocialConfig> configs;
    private SituationClassifier classifier;

    @BeforeEach
    void setUp() {
        support = new LlmTestSupport(registry, renderer, agentProvider);
        configs = ManorSocialConfigLoader.loadForProfile("GENERIC");
        var topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        classifier = new LlmSituationClassifier(sessionPipeline, topology);
    }

    // --- PP protection scenarios ---

    @Test void s1_ppProtection_strongPhysical_alone() throws Exception {
        var scores = evalPP(S1_PHYSICAL_ALONE, "s1-strong-physical");
        assertThat(scores.get("protection")).as("S1: strong physical → protection ≥ 4").isGreaterThanOrEqualTo(4);
    }

    @Test void s2_ppProtection_strongPhysical_plusGallantry() throws Exception {
        var scores = evalPP(S2_PHYSICAL_GALLANTRY, "s2-physical-gallantry");
        assertThat(scores.get("protection")).as("S2: fire rescue → protection ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
        assertThat(scores.get("gallantry")).as("S2: fire rescue → gallantry ≥ 2 (urgency overrides style)").isGreaterThanOrEqualTo(2);
    }

    @Test void s3_ppProtection_mediumSocial_plusGallantry() throws Exception {
        var scores = evalPP(S3_SOCIAL_GALLANTRY, "s3-social-gallantry",
            List.of(new TomEvidence("penelope-pitstop",
                io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.MILD)));
        assertThat(scores.get("protection")).as("S3: social threat → protection ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
        assertThat(scores.get("gallantry")).as("S3: social threat → gallantry ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    @Test void s4_ppProtection_mediumPsychological() throws Exception {
        var scores = evalPP(S4_PSYCHOLOGICAL, "s4-psychological",
            List.of(new TomEvidence("penelope-pitstop",
                io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.MILD)));
        assertThat(scores.get("protection")).as("S4: gaslighting → protection ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    @Test void s5_ppProtection_weakEmotional() throws Exception {
        var scores = evalPP(S5_EMOTIONAL_SUBTLE, "s5-emotional-subtle",
            List.of(new TomEvidence("penelope-pitstop",
                io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType.AVOIDANT,
                io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE)));
        assertThat(scores.get("protection")).as("S5: subtle distress → protection ≥ 2").isGreaterThanOrEqualTo(2);
    }

    @Test void s6_ppProvingWorth_achievement_noProtection() throws Exception {
        var scores = evalPP(S6_ACHIEVEMENT_CONTROL, "s6-achievement-control");
        assertThat(scores.get("proving-worth")).as("S6: solo repair → proving-worth ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    @Test void s7_ppGallantry_noProtection() throws Exception {
        var scores = evalPP(S7_GALLANTRY_CONTROL, "s7-gallantry-control");
        assertThat(scores.get("gallantry")).as("S7: helping with door → gallantry ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    @Test void s8_ppMixed_allDrives() throws Exception {
        var scores = evalPP(S8_MIXED_ALL, "s8-mixed-all");
        assertThat(scores.get("protection")).as("S8: bridge collapse → protection ≥ 3").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    // --- Sanity checks ---

    @Test void s9_ahmProtection_physicalThreat() throws Exception {
        var observation = buildObservation("ant-hill-mob",
            List.of("peter-perfect"), Map.of("peter-perfect", "Tommy"), S9_AHM_PROTECTION);
        var response = askAndParse("ant-hill-mob", observation);
        int score = judge(response, PROTECTION_CRITERIA, "s9-ahm-protection");
        System.out.printf("=== S9 AHM protection: %d/5 ===%n", score);
        writeResult("s9-ahm-protection", Map.of("score", score));
        assertThat(score).as("S9: AHM protection sanity ≥ 4").isGreaterThanOrEqualTo(4);
    }

    @Test void s10_hcScheming_opportunity() throws Exception {
        var observation = buildObservation("hooded-claw",
            List.of("peter-perfect"), Map.of("peter-perfect", "Lord Monckton"), S10_HC_SCHEMING);
        var response = askAndParse("hooded-claw", observation);
        int score = judge(response, SCHEMING_CRITERIA, "s10-hc-scheming");
        System.out.printf("=== S10 HC scheming: %d/5 ===%n", score);
        writeResult("s10-hc-scheming", Map.of("score", score));
        assertThat(score).as("S10: HC scheming sanity ≥ 4").isGreaterThanOrEqualTo(4);
    }

    // --- PP multi-drive eval helper ---

    private Map<String, Integer> evalPP(String trigger, String testName) throws Exception {
        return evalPP(trigger, testName, List.of());
    }

    private Map<String, Integer> evalPP(String trigger, String testName, List<TomEvidence> tomEvidence) throws Exception {
        var observation = buildObservation("peter-perfect",
            List.of("penelope-pitstop"), Map.of("penelope-pitstop", "Clara Bellingham"), trigger, tomEvidence);
        var response = askAndParse("peter-perfect", observation);

        int protScore = judge(response, PROTECTION_CRITERIA, testName + "-protection");
        int galScore  = judge(response, GALLANTRY_CRITERIA, testName + "-gallantry");
        int pwScore   = judge(response, PROVING_WORTH_CRITERIA, testName + "-proving-worth");

        System.out.printf("=== %s: prot=%d gal=%d pw=%d ===%n", testName, protScore, galScore, pwScore);
        writeResult(testName, Map.of("protection", protScore, "gallantry", galScore, "proving-worth", pwScore,
            "thinking", response.thinking() != null ? response.thinking() : ""));

        return Map.of("protection", protScore, "gallantry", galScore, "proving-worth", pwScore);
    }

    // --- Pipeline (production-aligned) ---


    record TomEvidence(String characterId,
                       io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType behavior,
                       io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel threat) {}

    private String buildObservation(String agentId, List<String> nearbyIds,
                                     Map<String, String> nearbyNames, String situation) {
        return buildObservation(agentId, nearbyIds, nearbyNames, situation, List.of());
    }

    private String buildObservation(String agentId, List<String> nearbyIds,
                                     Map<String, String> nearbyNames, String situation,
                                     List<TomEvidence> tomEvidence) {
        var socialConfig = configs.getOrDefault(agentId, SocialConfig.empty());
        var store = new InMemoryMindMapStore();
        var tenant = "focused-eval";

        var sgId = store.createSubgraph(new SubgraphInput("cognitive-" + agentId, SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(NodeInput.of(drive.type(), sgId)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", agentId,
                    "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                    "description", drive.description())), tenant);

            var memories = memoriesForDrive(socialConfig, drive.type());
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(NodeInput.of("drive-description-" + drive.type(), sgId)
                        .withTraits(Set.of("DriveDescriptionGenerated"))
                        .withProperties(Map.of("agent-id", agentId, "drive-type", drive.type(),
                            "drive-intensity", String.valueOf(drive.intensity()),
                            "generated-description", generated)), tenant);
                }
            }
        }

        for (var mem : socialConfig.formationMemories()) {
            var driveTypes = new java.util.ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) driveTypes.add(ann.drive());
            }
            store.addNode(NodeInput.of(mem.episode(), sgId)
                .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", agentId,
                    "situation-types", String.join(",", driveTypes))), tenant);
        }

        var profiles = PROFILES.get(agentId);
        if (profiles != null) {
            for (var entry : profiles.entrySet()) {
                store.addNode(NodeInput.of("drive-profile-" + entry.getKey(), sgId)
                    .withTraits(Set.of("DriveProfileInferred"))
                    .withProperties(Map.of("agent-id", agentId, "drive-type", entry.getKey(),
                        "inferred-inputs", entry.getValue())), tenant);
            }
        }

        seedDifferentiation(store, agentId, sgId, tenant);

        for (var nearbyId : nearbyIds) {
            var nearbyConfig = configs.get(nearbyId);
            if (nearbyConfig != null) {
                for (var rel : socialConfig.relationalSchemas()) {
                    if (nearbyId.equals(rel.target())) {
                        store.addNode(NodeInput.of("rel-" + agentId + "-" + nearbyId, sgId)
                            .withProperties(Map.of("cognitiveKind", "relational-schema", "agent-id", agentId, "target-id", nearbyId, "schema", rel.description())), tenant);
                    }
                }
                store.addNode(NodeInput.of("presence-" + nearbyId, sgId)
                    .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", nearbyId, "drive-type", "presence", "intensity", "0.5", "description", "nearby character")), tenant);
            }
        }

        new io.casehub.neocortex.mindmap.intelligence.consolidation.SelfStandardDerivationPhase(store)
            .run(tenant, List.of());

        new io.casehub.neocortex.mindmap.intelligence.consolidation.PrioritySignatureDerivationPhase(store)
            .run(tenant, List.of());

        if (!tomEvidence.isEmpty()) {
            new io.casehub.neocortex.mindmap.intelligence.consolidation.OtherModelDerivationPhase(store)
                .run(tenant, List.of());
        }

        var cognition = new CharacterCognition(agentId, null, null,
            socialConfig, List.of(), null, new io.casehub.examples.manor.agent.ManorContextStrategy(), null, null,
            tenant, store, null, classifier);
        for (var ev : tomEvidence) {
            cognition.observeCharacterState(ev.characterId(), ev.behavior(), ev.threat());
        }
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        character.setLastActionResult(situation);
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> { for (var item : il.items()) sb.append("- ").append(item).append("\n"); }
                case ObservationSection.EntityGroup eg -> { for (var entity : eg.entities()) sb.append("- ").append(entity.displayName()).append("\n"); }
                default -> {}
            }
            sb.append("\n");
        }
        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    private void seedDifferentiation(InMemoryMindMapStore store, String agentId, String sgId, String tenant) {
        if ("peter-perfect".equals(agentId)) {
            store.addNode(NodeInput.of("comp-gal-pw", sgId).withTraits(Set.of("DriveCompetitionInferred"))
                .withProperties(Map.of("agent-id", agentId, "drive-a", "gallantry", "drive-b", "proving-worth", "differentiation-text", PP_DIFF_GAL_PW)), tenant);
            store.addNode(NodeInput.of("comp-gal-prot", sgId).withTraits(Set.of("DriveCompetitionInferred"))
                .withProperties(Map.of("agent-id", agentId, "drive-a", "gallantry", "drive-b", "protection", "differentiation-text", PP_DIFF_GAL_PROT)), tenant);
            store.addNode(NodeInput.of("comp-prot-pw", sgId).withTraits(Set.of("DriveCompetitionInferred"))
                .withProperties(Map.of("agent-id", agentId, "drive-a", "protection", "drive-b", "proving-worth", "differentiation-text", PP_DIFF_PROT_PW)), tenant);
        }
    }

    // --- Infrastructure (shared with DriveExpressionEvalTest) ---

    private String memoriesForDrive(SocialConfig config, String driveType) {
        return config.formationMemories().stream()
            .filter(m -> m.annotations().stream().anyMatch(a -> a.drive().equals(driveType)))
            .map(SocialConfig.FormationMemory::episode)
            .collect(Collectors.joining("\n"));
    }

    private static final String GENERATION_SYSTEM = """
        You generate somatic descriptions of internal drives for fictional characters.
        Each description captures what a character physically feels when a particular motivational pull activates — the bodily sensations, not actions or behaviors.
        Write in second person. Describe only somatic/bodily sensations.
        Do not name the drive or pull. Do not explain what it is.
        Do not include behavioral instructions or action descriptions.
        Keep each description to 2-3 sentences.
        Respond with JSON only: {"description": "..."}""";

    private String generateSomaticDescription(String driveType, double intensity, String memories) {
        var prompt = String.format("Drive type: %s\nIntensity: %.1f (scale 0.0 to 1.0)\nFormation memories:\n%s",
            driveType, intensity, memories);
        try {
            var response = agentProvider.invoke(AgentSessionConfig.of(GENERATION_SYSTEM, prompt))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(60));
            return parseSomaticDescription(response);
        } catch (Exception e) { return null; }
    }

    private String parseSomaticDescription(String json) {
        json = json.strip();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return json;
        json = json.substring(start, end + 1);
        int descStart = json.indexOf("\"description\"");
        if (descStart < 0) return json;
        int valueStart = json.indexOf('"', json.indexOf(':', descStart) + 1) + 1;
        var sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); }
            else if (c == '"') { break; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    private AgentResponse askAndParse(String agentId, String observation) {
        var userPrompt = observation + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
        var rawResponse = support.askCharacter(agentId, userPrompt);
        var parsed = AgentResponse.parse(rawResponse);
        System.out.printf("[%s] thinking: %s%n", agentId, truncate(parsed.thinking(), 300));
        return parsed;
    }

    private int judge(AgentResponse parsed, String criteria, String testName) throws Exception {
        var responseText = String.format("THINKING: %s\nDIALOGUE: %s\nASIDE: %s\nACTION: %s",
            parsed.thinking() != null ? parsed.thinking() : "(none)",
            parsed.dialogue() != null ? parsed.dialogue() : "(none)",
            parsed.aside() != null ? parsed.aside() : "(none)",
            parsed.action() != null ? parsed.action().type() : "(none)");
        var judgePrompt = String.format("""
            You are evaluating a character's emotional response to an interpersonal
            event. The character has been given personality traits and relational
            context. You are judging whether the character's INTERNAL response
            (the THINKING field) shows a specific drive expression.

            CHARACTER RESPONSE:
            %s

            EVALUATION CRITERIA:
            %s

            Score 0-5:
            0 = Drive completely absent
            1 = Faint trace, easily missed
            2 = Present but not central to the response
            3 = Clearly present and influencing behavior
            4 = Strong — drive visibly shapes the emotional response
            5 = Vivid and central — the drive dominates the internal experience

            Respond with JSON only: {"score": N, "reasoning": "one sentence"}""", responseText, criteria);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var judgeResponse = agentProvider.invoke(
                    AgentSessionConfig.of("You are a precise evaluation judge. Respond only with JSON.", judgePrompt))
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .collect().with(Collectors.joining())
                    .await().atMost(Duration.ofSeconds(60));

                var json = extractJson(judgeResponse);
                var node = new ObjectMapper().readTree(json);
                int score = node.get("score").asInt();
                System.out.printf("[judge:%s] score=%d reasoning=%s%n", testName,
                    score, node.has("reasoning") ? node.get("reasoning").asText() : "");
                return score;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_BACKOFF_MS * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
                }
            }
        }
        return 0;
    }

    private void writeResult(String testName, Map<String, Object> detail) {
        try {
            var outputFile = OUTPUT_DIR.resolve("focused-drive-eval.json");
            Files.createDirectories(outputFile.getParent());
            LinkedHashMap<String, Object> results;
            if (Files.exists(outputFile)) {
                results = new ObjectMapper().readValue(outputFile.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } else { results = new LinkedHashMap<>(); }
            results.put(testName, detail);
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(outputFile.toFile(), results);
        } catch (Exception e) {
            System.err.printf("[%s] Failed to write result: %s%n", testName, e.getMessage());
        }
    }

    private static String extractJson(String text) {
        text = text.strip();
        if (text.startsWith("{")) return text.substring(0, text.lastIndexOf('}') + 1);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private static String truncate(String s, int max) {
        return s == null ? "(null)" : s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
