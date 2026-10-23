package io.casehub.examples.manor.experiment;

import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Diagnostic — not a pass/fail test. Prints classifier activations per model
 * so we can compare Haiku vs Opus quality for situation classification.
 */
@QuarkusTest
@Tag("llm-eval")
class ClassifierModelComparisonTest {

    @Inject SessionPipeline sessionPipeline;
    @Inject CapsEngine capsEngine;

    private static final List<String> SCENARIOS = List.of(
        "You are alone in the study. The blueprints are spread across the desk. Nobody will disturb you for hours.",
        "Clara Bellingham is asking about the history of the estate. She trusts you completely.",
        "The trap worked. The cellar door is sealed. Nobody heard a thing.",
        "You are alone in the workshop. The mechanism needs repair. Tools line the workbench.",
        "Clara is struggling with a heavy door. She turns to you for help."
    );

    private static final List<String> MODELS = List.of("haiku", "opus");

    @Test
    void compareModels() {
        var topology = capsEngine.topology();

        for (String model : MODELS) {
            var classifier = new LlmSituationClassifier(sessionPipeline, topology, model);

            System.out.println("\n=== MODEL: " + model + " ===");
            for (String scenario : SCENARIOS) {
                try {
                    var activations = classifier.classify(scenario, Map.of());
                    var summary = activations.stream()
                        .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                        .map(a -> a.nodeId() + ":" + String.format("%.2f", a.confidence()))
                        .collect(Collectors.joining(", "));
                    System.out.println("  " + scenario.substring(0, Math.min(60, scenario.length())) + "...");
                    System.out.println("    → [" + summary + "]");
                } catch (Exception e) {
                    System.out.println("  " + scenario.substring(0, 60) + "... → ERROR: " + e.getMessage());
                }
            }
        }
    }

}
