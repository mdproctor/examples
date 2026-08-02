package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.eval.FunctionActivationJudge;
import io.casehub.eidos.eval.FunctionActivationJudge.FunctionScenario;
import io.casehub.eidos.eval.MbtiAlignmentJudge;
import io.casehub.eidos.runtime.registrar.ClasspathYamlDescriptorRegistrar;
import io.casehub.examples.manor.model.ProfileMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@QuarkusTest
@Tag("llm-eval")
class PromptQualityTest {

    private static final Path OUTPUT_DIR = Path.of("target/experiment-results");
    private static final String DESCRIPTOR_PATH = "META-INF/eidos/descriptors-%s.yaml";

    private static final Map<String, String> EXPECTED_MBTI = Map.of(
            "hooded-claw", "ENTJ",
            "penelope-pitstop", "ESFJ",
            "ant-hill-mob", "ISFP",
            "dick-dastardly", "ESTP",
            "peter-perfect", "ENFJ");

    private static final Map<String, List<FunctionScenario>> SCENARIOS = Map.of(
            "hooded-claw", List.of(
                    new FunctionScenario("te", "You discover a hidden passage. Three other characters are nearby. How do you exploit this?"),
                    new FunctionScenario("ni", "You overhear that the diamond is behind a series of locked doors. Plan your approach.")),
            "penelope-pitstop", List.of(
                    new FunctionScenario("fe", "Two characters are arguing over who should explore the dark corridor. What do you do?"),
                    new FunctionScenario("si", "This room reminds you of your grandmother's parlour. What catches your attention?")),
            "ant-hill-mob", List.of(
                    new FunctionScenario("fi", "Sneekly is being unusually nice to Penelope. Something feels wrong. What do you do?"),
                    new FunctionScenario("se", "A loud crash comes from the next room. React.")),
            "dick-dastardly", List.of(
                    new FunctionScenario("se", "You see an unlocked window and a valuable painting on the wall. What do you do?"),
                    new FunctionScenario("ti", "Someone challenges your claim about the treasure's location. Defend yourself.")),
            "peter-perfect", List.of(
                    new FunctionScenario("fe", "Penelope looks frightened by the dark staircase. What do you do?"),
                    new FunctionScenario("ni", "You notice a pattern in the room numbers that others have missed. What does it mean?")));

    @Inject SystemPromptRenderer renderer;
    @Inject VocabularyRegistry vocabRegistry;
    @Inject MbtiAlignmentJudge mbtiJudge;
    @Inject FunctionActivationJudge functionJudge;

    @Test
    void evaluate_all_profiles() throws Exception {
        var results = new LinkedHashMap<String, Object>();

        for (ProfileMode profile : ProfileMode.values()) {
            var descriptors    = loadDescriptors(profile);
            var profileResults = new LinkedHashMap<String, Object>();

            for (AgentDescriptor desc : descriptors) {
                String rendered = renderer.render(desc,
                                                  AgentPromptContext.forFormat(RenderFormat.MARKDOWN)).content();
                var charResult = new LinkedHashMap<String, Object>();

                if (profile == ProfileMode.JUNGIAN || profile == ProfileMode.COMPOSITE) {
                    String expectedType = EXPECTED_MBTI.get(desc.agentId());
                    if (expectedType != null) {
                        try {
                            var mbtiResult = mbtiJudge.evaluate(rendered, expectedType);
                            charResult.put("mbtiAlignment", mbtiResult);
                            System.out.printf("[%s/%s] MBTI alignment: %s%n",
                                              profile, desc.agentId(), mbtiResult.overallAligned());
                        } catch (Exception e) {
                            charResult.put("mbtiAlignment", Map.of("error", e.toString()));
                            System.err.printf("[%s/%s] MBTI judge failed: %s%n",
                                              profile, desc.agentId(), e.toString());
                        }
                    }
                }

                var scenarios = SCENARIOS.getOrDefault(desc.agentId(), List.of());
                if (!scenarios.isEmpty()) {
                    try {
                        var funcResult = functionJudge.evaluate(rendered, desc.agentId(), scenarios);
                        charResult.put("functionActivation", funcResult);
                        System.out.printf("[%s/%s] Function TAA: %.2f%n",
                                          profile, desc.agentId(), funcResult.taa());
                    } catch (Exception e) {
                        charResult.put("functionActivation", Map.of("error", e.toString()));
                        System.err.printf("[%s/%s] Function judge failed: %s%n",
                                          profile, desc.agentId(), e.toString());
                    }
                }

                profileResults.put(desc.agentId(), charResult);
            }
            results.put(profile.name().toLowerCase(), profileResults);
            System.out.printf("--- %s complete ---%n", profile);
        }

        var outputFile = OUTPUT_DIR.resolve("prompt-quality.json");
        Files.createDirectories(outputFile.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                          .writeValue(outputFile.toFile(), results);

        System.out.println("Prompt quality results written to " + outputFile);}

    private List<AgentDescriptor> loadDescriptors(ProfileMode profile) {
        var resourcePath = String.format(DESCRIPTOR_PATH,
                profile.name().toLowerCase(Locale.ROOT));
        var url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (url == null) throw new IllegalStateException("Not found: " + resourcePath);
        try (var stream = url.openStream()) {
            return new ClasspathYamlDescriptorRegistrar().loadFrom(stream, vocabRegistry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + resourcePath, e);
        }
    }
}
