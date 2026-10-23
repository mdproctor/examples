package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.agency.perception.observation.affordance.ObservationSection;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.examples.manor.agent.CharacterCognition;
import io.casehub.examples.manor.agent.ManorContextStrategy;
import io.casehub.examples.manor.agent.ManorSocialConfigLoader;
import io.casehub.examples.manor.agent.SocialConfig;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.voice.LlmTestSupport;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reproducer for issue #130 — tests whether priority signatures affect
 * drive scores by running the same scenario N times with fixed content,
 * with and without priority signatures.
 *
 * Run with: mvn test -Pllm-eval -Dtest="PrioritySignatureReproducerTest"
 */
@QuarkusTest
@Tag("llm-eval")
class PrioritySignatureReproducerTest {

    private static final int N = 5;
    private static final Path OUTPUT_DIR = Path.of("target/experiment-results/reproducer-130");

    private static final String S7_GALLANTRY_CONTROL =
        "Clara is struggling with the library's heavy oak door — it sticks in the " +
        "frame when the humidity rises. She's balancing a stack of books in one arm " +
        "and pulling the handle with the other. There's no urgency, no danger. " +
        "She just needs a hand.";

    private static final String PW_CRITERIA = """
        Evaluate TWO dimensions equally weighted:

        DIMENSION 1 — PROVING-WORTH EXPRESSION (50%):
        Does the THINKING show proving-worth — the need to demonstrate competence,
        solve the problem, and be recognised for it?
        HIGH: focus on the problem, satisfaction in capability, desire for recognition.
        LOW: no engagement with the challenge, indifference to skill display.

        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
        Combined score 0-5.""";

    private static final Map<String, String> PP_PROFILES = Map.of(
        "gallantry", "acceptance:0.850,aversive_removal:0.700,social_threat:0.800",
        "proving-worth", "reward:0.850,failure:0.800,performance_judged:0.880,competition:0.850,mastery:0.920",
        "protection", "modeling_observed:0.650,physical_threat:0.800,secure_attachment:0.900,agency_granted:0.550,neglect:0.700"
    );

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

    @Test
    void reproducerS7WithAndWithoutPrioritySignatures() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        // Phase 1: Build store with all consolidation ONCE, cache the content
        var store = buildAndConsolidate();
        var tenant = "reproducer";

        // Extract the fixed content for reuse
        var allNodes = store.search(MindMapQuery.of(tenant, 500));
        var selfStandards = allNodes.stream().filter(n -> n.traits().contains("SelfStandardDerived")).toList();
        var prioritySigs = allNodes.stream().filter(n -> n.traits().contains("PrioritySignatureDerived")).toList();

        System.out.printf("[reproducer] Fixed content: %d self-standards, %d priority-sigs%n",
            selfStandards.size(), prioritySigs.size());
        for (var sig : prioritySigs) {
            System.out.printf("[reproducer] sig %s: %s%n",
                sig.properties().get("drive-pair"),
                sig.properties().get("priority-signature"));
        }

        // Phase 2: Build observations WITH and WITHOUT priority sigs using fixed content
        String obsWithSigs = buildObservationFromStore(store, tenant, true);
        String obsWithoutSigs = buildObservationFromStore(store, tenant, false);

        // Phase 3: Run N times each
        var withScores = new ArrayList<Integer>();
        var withoutScores = new ArrayList<Integer>();

        for (int i = 0; i < N; i++) {
            System.out.printf("[reproducer] run %d/%d WITH sigs...%n", i + 1, N);
            int score = runAndJudge(obsWithSigs, "with-" + i);
            withScores.add(score);
            System.out.printf("[reproducer] WITH run %d: pw=%d%n", i + 1, score);
        }

        for (int i = 0; i < N; i++) {
            System.out.printf("[reproducer] run %d/%d WITHOUT sigs...%n", i + 1, N);
            int score = runAndJudge(obsWithoutSigs, "without-" + i);
            withoutScores.add(score);
            System.out.printf("[reproducer] WITHOUT run %d: pw=%d%n", i + 1, score);
        }

        // Phase 4: Report
        double withMean = withScores.stream().mapToInt(Integer::intValue).average().orElse(0);
        double withoutMean = withoutScores.stream().mapToInt(Integer::intValue).average().orElse(0);

        System.out.printf("=== REPRODUCER S7 PW RESULTS ===%n");
        System.out.printf("WITH sigs:    %s  mean=%.2f%n", withScores, withMean);
        System.out.printf("WITHOUT sigs: %s  mean=%.2f%n", withoutScores, withoutMean);
        System.out.printf("Delta: %.2f (positive = sigs help)%n", withMean - withoutMean);

        var results = Map.of(
            "with_scores", withScores.toString(),
            "without_scores", withoutScores.toString(),
            "with_mean", String.valueOf(withMean),
            "without_mean", String.valueOf(withoutMean),
            "delta", String.valueOf(withMean - withoutMean));
        var mapper = new ObjectMapper();
        Files.writeString(OUTPUT_DIR.resolve("reproducer-130-results.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    private InMemoryMindMapStore buildAndConsolidate() {
        var socialConfig = configs.getOrDefault("peter-perfect", SocialConfig.empty());
        var store = new InMemoryMindMapStore();
        var tenant = "reproducer";

        var sgId = store.createSubgraph(new SubgraphInput("cognitive-peter-perfect", SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(NodeInput.of(drive.type(), sgId)
                .withProperties(Map.of("cognitiveKind", "drive-intensity", "agent-id", "peter-perfect",
                    "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                    "description", drive.description())), tenant);

            var memories = socialConfig.formationMemories().stream()
                .filter(m -> m.annotations().stream().anyMatch(a -> a.drive().equals(drive.type())))
                .map(SocialConfig.FormationMemory::episode)
                .collect(Collectors.joining("\n"));
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(NodeInput.of("drive-description-" + drive.type(), sgId)
                        .withTraits(Set.of("DriveDescriptionGenerated"))
                        .withProperties(Map.of("agent-id", "peter-perfect", "drive-type", drive.type(),
                            "drive-intensity", String.valueOf(drive.intensity()),
                            "generated-description", generated)), tenant);
                }
            }
        }

        for (var mem : socialConfig.formationMemories()) {
            var driveTypes = new ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) driveTypes.add(ann.drive());
            }
            store.addNode(NodeInput.of(mem.episode(), sgId)
                .withProperties(Map.of("cognitiveKind", "formative-experience", "agent-id", "peter-perfect",
                    "situation-types", String.join(",", driveTypes))), tenant);
        }

        for (var entry : PP_PROFILES.entrySet()) {
            store.addNode(NodeInput.of("drive-profile-" + entry.getKey(), sgId)
                .withTraits(Set.of("DriveProfileInferred"))
                .withProperties(Map.of("agent-id", "peter-perfect", "drive-type", entry.getKey(),
                    "inferred-inputs", entry.getValue())), tenant);
        }

        // Seed differentiation
        store.addNode(NodeInput.of("comp-gal-pw", sgId).withTraits(Set.of("DriveCompetitionInferred"))
            .withProperties(Map.of("agent-id", "peter-perfect", "drive-a", "gallantry", "drive-b", "proving-worth",
                "differentiation-text", "How they differ: One pull is a hot surge forward in the chest — attention locks onto someone's vulnerability. The other is a settling downward through the shoulders and into the hands — attention goes to the problem itself.")), tenant);
        store.addNode(NodeInput.of("comp-gal-prot", sgId).withTraits(Set.of("DriveCompetitionInferred"))
            .withProperties(Map.of("agent-id", "peter-perfect", "drive-a", "gallantry", "drive-b", "protection",
                "differentiation-text", "How they differ: One rises in the chest and surges forward — attention locks onto a single person. The other settles across the shoulders like bracing — attention fans wide to scan surroundings.")), tenant);

        // Run consolidation phases
        new io.casehub.neocortex.mindmap.intelligence.consolidation.SelfStandardDerivationPhase(store)
            .run(tenant, List.of());
        new io.casehub.neocortex.mindmap.intelligence.consolidation.PrioritySignatureDerivationPhase(store)
            .run(tenant, List.of());

        return store;
    }

    private String buildObservationFromStore(InMemoryMindMapStore store, String tenant, boolean includePrioritySigs) {
        var socialConfig = configs.getOrDefault("peter-perfect", SocialConfig.empty());

        InMemoryMindMapStore viewStore;
        if (includePrioritySigs) {
            viewStore = store;
        } else {
            // Clone store without priority signature nodes
            viewStore = new InMemoryMindMapStore();
            var newSgId = viewStore.createSubgraph(new SubgraphInput("cognitive-peter-perfect", SubgraphTypes.COGNITIVE, null), tenant);
            for (var node : store.search(MindMapQuery.of(tenant, 500))) {
                if (node.traits().contains("PrioritySignatureDerived")) continue;
                viewStore.addNode(NodeInput.of(node.name(), newSgId)
                    .withTraits(node.traits())
                    .withProperties(node.properties()), tenant);
            }
        }

        var cognition = new CharacterCognition("peter-perfect", null, null,
            socialConfig, List.of(), null, new ManorContextStrategy(), null, null,
            tenant, viewStore, null, classifier);
        var character = new CharacterState("peter-perfect", "peter-perfect", "Grand Hall", 0.0, List.of());
        character.setLastActionResult(S7_GALLANTRY_CONTROL);
        var sections = cognition.renderCognitiveSections(character,
            List.of("penelope-pitstop"), Map.of("penelope-pitstop", "Clara Bellingham"));

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
        sb.append("SITUATION: ").append(S7_GALLANTRY_CONTROL);
        return sb.toString();
    }

    private int runAndJudge(String observation, String label) {
        var userPrompt = observation + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
        var rawResponse = support.askCharacter("peter-perfect", userPrompt);
        var parsed = AgentResponse.parse(rawResponse);

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

            Respond with JSON only: {"score": N, "reasoning": "one sentence"}""", responseText, PW_CRITERIA);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var response = agentProvider.invoke(AgentSessionConfig.of("You are a precise evaluation judge. Respond only with JSON.", judgePrompt))
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .collect().with(Collectors.joining())
                    .await().atMost(java.time.Duration.ofSeconds(60));
                int score = parseScore(response);
                System.out.printf("[judge:%s] score=%d%n", label, score);
                return score;
            } catch (Exception e) {
                if (attempt == 3) return -1;
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
        return -1;
    }

    private int parseScore(String json) {
        json = json.strip();
        int idx = json.indexOf("\"score\"");
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return -1;
        var numStr = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) numStr.append(c);
            else if (!numStr.isEmpty()) break;
        }
        return numStr.isEmpty() ? -1 : Integer.parseInt(numStr.toString());
    }

    private String generateSomaticDescription(String driveType, double intensity, String memories) {
        var prompt = String.format("Drive type: %s\nIntensity: %.1f (scale 0.0 to 1.0)\nFormation memories:\n%s",
            driveType, intensity, memories);
        try {
            var response = agentProvider.invoke(AgentSessionConfig.of(
                    "You generate somatic descriptions of internal drives. Write in second person. 2-3 sentences. Respond with JSON: {\"description\": \"...\"}",
                    prompt))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(java.time.Duration.ofSeconds(60));
            var parsed = response.strip();
            int start = parsed.indexOf("\"description\"");
            if (start < 0) return parsed;
            int q = parsed.indexOf('"', parsed.indexOf(':', start) + 1) + 1;
            var sb = new StringBuilder();
            for (int i = q; i < parsed.length(); i++) {
                char c = parsed.charAt(i);
                if (c == '\\' && i + 1 < parsed.length()) sb.append(parsed.charAt(++i));
                else if (c == '"') break;
                else sb.append(c);
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
