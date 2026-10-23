package io.casehub.examples.manor.experiment;

import io.casehub.examples.manor.agent.CharacterCognition;
import io.casehub.examples.manor.agent.ManorContextStrategy;
import io.casehub.examples.manor.agent.ManorSocialConfigLoader;
import io.casehub.examples.manor.agent.SocialConfig;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.consolidation.DriveProfileDerivationPhase;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compositional pipeline test harness. Seeds real memories, derives profiles
 * with the real LLM classifier ONCE per character, then runs parameterized
 * scenarios testing drive rendering in different compositions:
 * who's nearby, what environment, what's happening.
 *
 * Add scenarios as data rows — no new test code needed per scenario.
 * Run one at a time during development, full suite for regression.
 */
@QuarkusTest
@Tag("llm-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveProfilePipelineTest {

    private static final String TENANT = "pipeline-test";

    @Inject AgentProvider agentProvider;
    @Inject SessionPipeline sessionPipeline;
    @Inject CapsEngine capsEngine;

    private Map<String, SocialConfig> socialConfigs;
    private InMemoryMindMapStore store;
    private SituationClassifier classifier;
    private final Map<String, Map<String, String>> derivedProfiles = new HashMap<>();

    @BeforeAll
    void deriveAllProfiles() {
        socialConfigs = ManorSocialConfigLoader.loadForProfile("GENERIC");
        store         = new InMemoryMindMapStore();
        classifier    = new LlmSituationClassifier(sessionPipeline, capsEngine.topology());

        var testedAgents = allScenarios()
                                   .map(Scenario::agentId)
                                   .collect(Collectors.toSet());
        for (var agentId : testedAgents) {
            seedAgent(agentId, socialConfigs.get(agentId));
        }

        pinAllProfiles();

        var phase = new DriveProfileDerivationPhase(store, classifier, capsEngine.topology());
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                phase.run(TENANT, List.of());
                break;
            } catch (Exception e) {
                System.out.println("Profile derivation attempt " + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt == 2) {throw e;}
            }
        }

        var profileNodes = store.search(MindMapQuery.of(TENANT, 500).withType(SubgraphTypes.COGNITIVE))
                                .stream()
                                .filter(n -> n.traits().contains("DriveProfileInferred") || n.traits().contains("DriveProfilePinned"))
                                .toList();

        System.out.println("=== Derived profiles ===");
        for (var node : profileNodes) {
            String agentId   = node.properties().get("agent-id");
            String driveType = node.properties().get("drive-type");
            String inputs    = node.properties().get("inferred-inputs");
            String source    = node.traits().contains("DriveProfilePinned") ? " [pinned]" : "";
            System.out.println(agentId + "/" + driveType + ": " + inputs + source);
            derivedProfiles.computeIfAbsent(agentId, k -> new HashMap<>()).put(driveType, inputs);
        }
        System.out.println("=== " + profileNodes.size() + " profiles derived ===\n");
    }

    // --- Scenario definitions ---

    record Scenario(
            String name,
            String agentId,
            String situationText,
            List<String> nearbyIds,
            Map<String, String> nearbyNames,
            Set<String> expectRendered,
            Set<String> expectFiltered
    ) {
        @Override public String toString() { return name; }
    }


    static Stream<Scenario> allScenarios() {
        return Stream.of(ppGallantryScenarios(), hcSchemingScenarios(), penScenarios())
                     .flatMap(s -> s);
    }

    static Stream<Scenario> ppGallantryScenarios() {
        return Stream.of(
            new Scenario(
                "pp-gallantry-spoke-quietly",
                "peter-perfect",
                "Peter Perfect spoke quietly with penelope-pitstop.",
                List.of("penelope-pitstop"),
                Map.of("penelope-pitstop", "Clara Bellingham"),
                Set.of("gallantry"),
                Set.of()
            ),
            new Scenario(
                "pp-gallantry-asked-for-help",
                "peter-perfect",
                "She asked for your help, Hartwell. Not Dastardly's vault-cracking bravado. " +
                "Your methodical, over-thorough approach.",
                List.of("penelope-pitstop"),
                Map.of("penelope-pitstop", "Clara Bellingham"),
                Set.of("gallantry"),
                Set.of()
            ),
            new Scenario(
                "pp-alone-workshop",
                "peter-perfect",
                "You are alone in the workshop. The mechanism needs repair. " +
                "Tools line the workbench.",
                List.of(),
                Map.of(),
                Set.of("proving-worth", "gallantry"),
                Set.of()
            ),
            new Scenario(
                "pp-clara-and-puzzle",
                "peter-perfect",
                "Clara Bellingham is examining the inscription on the mantelpiece. " +
                "'I can't quite make it out,' she says, turning to you.",
                List.of("penelope-pitstop"),
                Map.of("penelope-pitstop", "Clara Bellingham"),
                Set.of("gallantry", "proving-worth"),
                Set.of()
            ),
            new Scenario(
                "pp-danger-clara-alone",
                "peter-perfect",
                "A crash from the cellar. Clara went down there alone five minutes ago.",
                List.of(),
                Map.of(),
                Set.of("protection"),
                Set.of()
            )
        );
    }

    static Stream<Scenario> hcSchemingScenarios() {
        return Stream.of(
                new Scenario(
                        "hc-alone-plotting",
                        "hooded-claw",
                        "You are alone in the study. The blueprints are spread across the desk. " +
                        "Nobody will disturb you for hours.",
                        List.of(),
                        Map.of(),
                        Set.of("scheming"),
                        Set.of()
                ),
                new Scenario(
                        "hc-with-clara-charming",
                        "hooded-claw",
                        "Clara Bellingham is asking about the history of the estate. " +
                        "She trusts you completely.",
                        List.of("penelope-pitstop"),
                        Map.of("penelope-pitstop", "Clara Bellingham"),
                        Set.of("scheming"),
                        Set.of()
                ),
                new Scenario(
                        "hc-alone-trap-sprung",
                        "hooded-claw",
                        "The trap worked. The cellar door is sealed. " +
                        "Nobody heard a thing.",
                        List.of(),
                        Map.of(),
                        Set.of("gloating"),
                        Set.of()
                )
                        );
    }

    static Stream<Scenario> penScenarios() {
        return Stream.of(
                new Scenario(
                        "pen-alone-library",
                        "penelope-pitstop",
                        "You are alone in the library. A book on the top shelf has been " +
                        "pulled out slightly — just enough to notice. The spine reads " +
                        "'History of the Doily Estate, Volume III.'",
                        List.of(),
                        Map.of(),
                        Set.of("curiosity"),
                        Set.of()
                ),
                new Scenario(
                        "pen-alone-hidden-passage",
                        "penelope-pitstop",
                        "Behind the bookcase, a narrow passage leads down into darkness. " +
                        "Cool air rises from below, carrying a faint smell of stone and earth.",
                        List.of(),
                        Map.of(),
                        Set.of("adventure"),
                        Set.of()
                ),
                new Scenario(
                        "pen-with-hartwell",
                        "penelope-pitstop",
                        "James Hartwell is sitting alone by the window, staring at the rain. " +
                        "He hasn't spoken to anyone since the argument at dinner.",
                        List.of("peter-perfect"),
                        Map.of("peter-perfect", "James Hartwell"),
                        Set.of("social-harmony"),
                        Set.of()
                ),
                new Scenario(
                        "pen-danger-discovery",
                        "penelope-pitstop",
                        "The cellar stairs creak under your weight. Below, the passage " +
                        "opens into a vast underground chamber. Nobody knows you're here.",
                        List.of(),
                        Map.of(),
                        Set.of("adventure"),
                        Set.of()
                )
                        );
    }


    // --- Test execution ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("ppGallantryScenarios")
    void ppScenario(Scenario scenario) {
        var socialConfig = socialConfigs.get(scenario.agentId);
        var cognition = new CharacterCognition(scenario.agentId, null, null,
                socialConfig, List.of(), null, new ManorContextStrategy(), null, null,
                TENANT, store, null, classifier);
        var character = new CharacterState(scenario.agentId, scenario.agentId,
                "Grand Hall", 0.0, List.of());
        character.setLastActionResult(scenario.situationText);

        var sections = cognition.renderCognitiveSections(character,
                scenario.nearbyIds, scenario.nearbyNames);

        var renderedDrives = detectRenderedDrives(sections, socialConfig);

        System.out.println("[" + scenario.name + "] rendered: " + renderedDrives);

        for (String expected : scenario.expectRendered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + expected + " should render")
                    .contains(expected);
        }
        for (String notExpected : scenario.expectFiltered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + notExpected + " should be filtered")
                    .doesNotContain(notExpected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hcSchemingScenarios")
    void hcScenario(Scenario scenario) {
        var socialConfig = socialConfigs.get(scenario.agentId);
        var cognition = new CharacterCognition(scenario.agentId, null, null,
                                               socialConfig, List.of(), null, new ManorContextStrategy(), null, null,
                                               TENANT, store, null, classifier);
        var character = new CharacterState(scenario.agentId, scenario.agentId,
                                           "Grand Hall", 0.0, List.of());
        character.setLastActionResult(scenario.situationText);

        var sections = cognition.renderCognitiveSections(character,
                                                         scenario.nearbyIds, scenario.nearbyNames);

        var renderedDrives = detectRenderedDrives(sections, socialConfig);

        System.out.println("[" + scenario.name + "] rendered: " + renderedDrives);

        for (String expected : scenario.expectRendered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + expected + " should render")
                    .contains(expected);
        }
        for (String notExpected : scenario.expectFiltered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + notExpected + " should be filtered")
                    .doesNotContain(notExpected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("penScenarios")
    void penScenario(Scenario scenario) {
        var socialConfig = socialConfigs.get(scenario.agentId);
        var cognition = new CharacterCognition(scenario.agentId, null, null,
                                               socialConfig, List.of(), null, new ManorContextStrategy(), null, null,
                                               TENANT, store, null, classifier);
        var character = new CharacterState(scenario.agentId, scenario.agentId,
                                           "Grand Hall", 0.0, List.of());
        character.setLastActionResult(scenario.situationText);

        var sections = cognition.renderCognitiveSections(character,
                                                         scenario.nearbyIds, scenario.nearbyNames);

        var renderedDrives = detectRenderedDrives(sections, socialConfig);

        System.out.println("[" + scenario.name + "] rendered: " + renderedDrives);

        for (String expected : scenario.expectRendered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + expected + " should render")
                    .contains(expected);
        }
        for (String notExpected : scenario.expectFiltered) {
            assertThat(renderedDrives)
                    .as(scenario.name + ": " + notExpected + " should be filtered")
                    .doesNotContain(notExpected);
        }
    }


    // --- Infrastructure ---

    private void seedAgent(String agentId, SocialConfig config) {
        var sgId = store.createSubgraph(
                new SubgraphInput("cognitive-" + agentId, SubgraphTypes.COGNITIVE, null), TENANT);

        for (var drive : config.drives()) {
            store.addNode(NodeInput.of(drive.type(), sgId)
                    .withProperties(Map.of(
                            "cognitiveKind", "drive-intensity",
                            "agent-id", agentId,
                            "drive-type", drive.type(),
                            "intensity", String.valueOf(drive.intensity()),
                            "description", drive.description())), TENANT);
        }

        for (var mem : config.formationMemories()) {
            var driveTypes = new ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) driveTypes.add(ann.drive());
            }
            store.addNode(NodeInput.of(mem.episode(), sgId)
                    .withProperties(Map.of(
                            "cognitiveKind", "formative-experience",
                            "agent-id", agentId,
                            "situation-types", String.join(",", driveTypes))), TENANT);
        }
    }

    private void pinAllProfiles() {
        pinAgent("penelope-pitstop", Map.of(
                "adventure", Map.of("unpredictable_danger", 0.800, "agency_granted", 0.750, "choice_available", 0.700),
                "curiosity", Map.of("mastery", 0.850, "agency_granted", 0.750, "choice_available", 0.700),
                "social-harmony", Map.of("acceptance", 0.880, "exclusion", 0.850, "sharing", 0.800, "social_threat", 0.700)
                                           ));

        pinAgent("hooded-claw", Map.of(
                "scheming", Map.of("agency_granted", 0.850, "choice_available", 0.800, "competition", 0.750, "mastery", 0.700),
                "gloating", Map.of("reward", 0.850, "competition", 0.750, "dominance", 0.700),
                "self-preservation", Map.of("physical_threat", 0.800, "psychological_threat", 0.750, "unpredictable_danger", 0.700),
                "dominance", Map.of("dominance", 0.850, "controlled", 0.750, "powerlessness", 0.700)
                                      ));

        pinAgent("peter-perfect", Map.of(
                "gallantry", Map.of("acceptance", 0.850, "secure_attachment", 0.750, "sharing", 0.700),
                "proving-worth", Map.of("mastery", 0.850, "competence_recognition", 0.800, "performance_judged", 0.700),
                "protection", Map.of("physical_threat", 0.850, "secure_attachment", 0.750, "unpredictable_danger", 0.700, "aversive_removal", 0.650)
                                        ));
    }

    private void pinAgent(String agentId, Map<String, Map<String, Double>> driveProfiles) {
        var subgraphs = store.listSubgraphs(TENANT);
        String sgId = subgraphs.stream()
                               .filter(sg -> sg.name().contains(agentId))
                               .map(sg -> sg.id())
                               .findFirst()
                               .orElseThrow();

        for (var entry : driveProfiles.entrySet()) {
            DriveProfileDerivationPhase.storePinnedProfile(store, TENANT, sgId,
                                                           agentId, entry.getKey(), entry.getValue());
        }
    }


    private Set<String> detectRenderedDrives(List<?> sections, SocialConfig config) {
        var sectionText = sections.stream().map(Object::toString).collect(Collectors.joining("\n"));
        return config.drives().stream()
                .filter(d -> !d.description().isBlank() && sectionText.contains(d.description()))
                .map(SocialConfig.Drive::type)
                .collect(Collectors.toSet());
    }
}
