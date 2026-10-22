package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.examples.manor.agent.CognitiveActivationTest;
import io.casehub.platform.agent.AgentProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("cognitive-eval")
class CognitiveEvalTest {

    private static final Path OUTPUT_BASE = Path.of("target/cognitive-eval");
    private static final int TICKS = 10;

    record ConfigLevel(String label, CognitionConfig config) {
        @Override public String toString() { return label; }
    }

    @Inject AgentProvider agentProvider;

    static List<ConfigLevel> levels() {
        var base = CognitionConfig.none()
                .with("goals", true).with("characterDrives", true).with("needsPyramid", true);
        return List.of(
                new ConfigLevel("0-baseline", base),
                new ConfigLevel("1-mood", base.with("mood", true)),
                new ConfigLevel("2-narrative", base.with("mood", true).with("narrative", true)),
                new ConfigLevel("3-models", base.with("mood", true).with("narrative", true)
                        .with("userModel", true).with("mentalModel", true)),
                new ConfigLevel("4-strategy", base.with("mood", true).with("narrative", true)
                        .with("userModel", true).with("mentalModel", true).with("strategy", true)),
                new ConfigLevel("5-drives", CognitionConfig.all().without("memoryHygiene", "innerLife")),
                new ConfigLevel("6-full", CognitionConfig.all())
        );
    }

    @Test void progressiveEvalWithDeltaCapture() throws IOException {
        Files.createDirectories(OUTPUT_BASE.resolve("levels"));
        Files.createDirectories(OUTPUT_BASE.resolve("diffs"));
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, List<CognitiveDeltaCapture.DeltaRecord>> allDeltas = new LinkedHashMap<>();

        for (var level : levels()) {
            var core = CognitiveActivationTest.buildCore(level.config());
            var capture = new CognitiveDeltaCapture();

            for (int tick = 1; tick <= TICKS; tick++) {
                core.tick("hooded-claw", "wacky-manor", null,
                        (a, t) -> Set.of("penelope-pitstop"));
                core.tick("penelope-pitstop", "wacky-manor", null,
                        (a, t) -> Set.of("hooded-claw"));

                capture.record(tick, "hooded-claw", renderSections(core, "hooded-claw"));
                capture.record(tick, "penelope-pitstop", renderSections(core, "penelope-pitstop"));
            }

            var deltas = capture.deltas();
            allDeltas.put(level.label(), deltas);
            var levelDir = OUTPUT_BASE.resolve("levels").resolve(level.label());
            Files.createDirectories(levelDir);
            mapper.writeValue(levelDir.resolve("deltas.json").toFile(), deltas);
        }

        var levelList = levels();
        for (int i = 1; i < levelList.size(); i++) {
            var prev = allDeltas.get(levelList.get(i - 1).label());
            var curr = allDeltas.get(levelList.get(i).label());
            assertThat(curr.size())
                    .as("Level %s should produce >= deltas than %s",
                            levelList.get(i).label(), levelList.get(i - 1).label())
                    .isGreaterThanOrEqualTo(prev.size());
        }

        for (int i = 1; i < levelList.size(); i++) {
            var report = generateDiffReport(levelList.get(i - 1), levelList.get(i), allDeltas);
            Files.writeString(OUTPUT_BASE.resolve("diffs").resolve(
                    "level-%s-to-%s.md".formatted(
                            levelList.get(i - 1).label(), levelList.get(i).label())), report);
        }

        Files.writeString(OUTPUT_BASE.resolve("summary.md"), generateSummary(allDeltas));
    }

    private Map<String, String> renderSections(CognitionCore core, String agentId) {
        var ctx = new io.casehub.neocortex.cognition.prompt.CognitionRenderContext(agentId, "wacky-manor", null);
        var result = new LinkedHashMap<String, String>();
        for (var section : core.promptSections()) {
            var rendered = section.render(ctx);
            if (rendered != null && !rendered.isBlank()) {
                result.put(section.getClass().getSimpleName(), rendered);
            }
        }
        return result;
    }

    private String generateDiffReport(ConfigLevel prev, ConfigLevel curr,
                                       Map<String, List<CognitiveDeltaCapture.DeltaRecord>> allDeltas) {
        var sb = new StringBuilder();
        sb.append("## %s → %s\n\n".formatted(prev.label(), curr.label()));
        var prevDeltas = allDeltas.get(prev.label());
        var currDeltas = allDeltas.get(curr.label());

        var prevSectionTypes = new java.util.TreeSet<String>();
        var currSectionTypes = new java.util.TreeSet<String>();
        for (var d : prevDeltas) {
            prevSectionTypes.addAll(d.addedSections().keySet());
            prevSectionTypes.addAll(d.changedSections().keySet());
        }
        for (var d : currDeltas) {
            currSectionTypes.addAll(d.addedSections().keySet());
            currSectionTypes.addAll(d.changedSections().keySet());
        }
        var newTypes = new java.util.TreeSet<>(currSectionTypes);
        newTypes.removeAll(prevSectionTypes);

        if (!newTypes.isEmpty()) {
            sb.append("**New section types:** ").append(String.join(", ", newTypes)).append("\n\n");
        }
        sb.append("**Delta count:** %d (prev: %d)\n\n".formatted(currDeltas.size(), prevDeltas.size()));

        for (var d : currDeltas) {
            if (!d.addedSections().isEmpty()) {
                for (var e : d.addedSections().entrySet()) {
                    if (!prevSectionTypes.contains(e.getKey())) {
                        sb.append("  %s tick %d: [%s] %s\n".formatted(
                                d.agentId(), d.tick(), e.getKey(),
                                e.getValue().length() > 120 ? e.getValue().substring(0, 120) + "..." : e.getValue()));
                    }
                }
            }
        }
        return sb.toString();
    }

    private String generateSummary(Map<String, List<CognitiveDeltaCapture.DeltaRecord>> allDeltas) {
        var sb = new StringBuilder("# Cognitive Eval Summary\n\n");
        sb.append("| Level | Delta Count | Agents With Changes |\n");
        sb.append("|-------|------------|--------------------|\n");
        for (var entry : allDeltas.entrySet()) {
            var agents = entry.getValue().stream()
                    .map(CognitiveDeltaCapture.DeltaRecord::agentId).distinct().count();
            sb.append("| %s | %d | %d |\n".formatted(entry.getKey(), entry.getValue().size(), agents));
        }
        return sb.toString();
    }

}
