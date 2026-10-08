package io.casehub.examples.manor.engine;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ManorGraduationScorerTest {

    private final ManorGraduationScorer scorer = new ManorGraduationScorer();

    private Memory memory(String text, Map<String, String> attributes, Confidence confidence) {
        return new Memory("m1", Subject.of("agent", "e1"),
            new MemoryDomain("test"), "t1", "c1",
            text, attributes, Instant.EPOCH,
            confidence, null, null, null, null, null);
    }

    @Test
    void highArousal_highImportance_highConfidence() {
        var mem = memory("critical emergency failure alert",
            Map.of("event-type", "conflict_resolution"),
            Confidence.unknown(0.9));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(3, "test"));
        assertThat(score).isBetween(0.5, 1.0);
    }

    @Test
    void lowArousal_lowImportance_lowConfidence() {
        var mem = memory("a peaceful calm day passed",
            Map.of("event-type", "idle"),
            Confidence.unknown(0.1));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(3, "test"));
        assertThat(score).isBetween(0.0, 0.3);
    }

    @Test
    void nullConfidence_fallbackToHalf() {
        var mem = memory("critical failure detected",
            Map.of("event-type", "trust_change"), null);
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(3, "test"));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void formulaVerification() {
        // peaceful text → arousal ≈ 0.0
        // conflict_resolution → actionImportance = 0.9
        // composite = (0.0*0.3 + 0.9*0.4) / (0.3+0.4) = 0.36/0.7 ≈ 0.514
        // final = 0.514 * 0.7 + 1.0 * 0.3 = 0.36 + 0.3 = 0.66
        var mem = memory("peaceful conflict resolution",
            Map.of("event-type", "conflict_resolution"),
            Confidence.unknown(1.0));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(3, "test"));
        assertThat(score).isCloseTo(0.66, offset(0.05));
    }

    @Test
    void scoreAlwaysClamped() {
        var mem = memory("text", Map.of("event-type", "observation"),
            Confidence.unknown(0.5));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(3, "test"));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void belowCorroborationGate_returnsZero() {
        var mem = memory("critical emergency failure alert",
            Map.of("event-type", "conflict_resolution"),
            Confidence.unknown(0.9));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(1, "test"));
        assertThat(score).as("memories with fewer than 2 corroborations must not graduate")
                         .isEqualTo(0.0);
    }

    @Test
    void formativeMemory_scoresAboveThreshold() {
        var mem = memory("childhood experience shaped distrust",
                         Map.of("event-type", "formative",
                                "salience-multiplier", "1.5",
                                "catalogue-entry-id", "test",
                                "situation-types", "formation"),
                         Confidence.unknown(0.9));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(0, "test"));
        assertThat(score).as("formative memories must graduate (score >= 0.5)")
                         .isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void formativeMemory_scoreDerivedFromConfidenceTimesSalience() {
        var mem = memory("low salience formative",
                         Map.of("event-type", "formative",
                                "salience-multiplier", "1.0",
                                "catalogue-entry-id", "test",
                                "situation-types", "formation"),
                         Confidence.unknown(0.7));
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(0, "test"));
        assertThat(score).as("min(1.0, 0.7 * 1.0) = 0.7")
                         .isCloseTo(0.7, offset(0.01));
    }

    @Test
    void formativeMemory_nullConfidence_usesDefault() {
        var mem = memory("null confidence formative",
                         Map.of("event-type", "formative",
                                "salience-multiplier", "1.5",
                                "catalogue-entry-id", "test",
                                "situation-types", "formation"),
                         null);
        double score = scorer.score(mem, new io.casehub.neocortex.memory.experience.GraduationContext(0, "test"));
        assertThat(score).as("default 0.8 * 1.5 = 1.2 clamped to 1.0")
                         .isCloseTo(1.0, offset(0.01));
    }

}
