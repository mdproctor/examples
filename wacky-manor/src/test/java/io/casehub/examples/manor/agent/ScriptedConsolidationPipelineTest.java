package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.ManorConstants;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fast")
class ScriptedConsolidationPipelineTest {

    @Test
    void setSessionPipelineMustNotBeInsideModeConditional() throws IOException {
        var source = Files.readString(
                Path.of("src/main/java/io/casehub/examples/manor/agent/PlaybookOrchestrator.java"));

        int pipelineCall = source.indexOf("consolidationScheduler.setSessionPipeline(sessionPipeline)");
        assertThat(pipelineCall)
                .as("setSessionPipeline call must exist in PlaybookOrchestrator")
                .isGreaterThan(0);

        String before = source.substring(Math.max(0, pipelineCall - 200), pipelineCall);
        assertThat(before)
                .as("setSessionPipeline must be called BEFORE the mode-conditional block, not inside "
                    + "if (AUTONOMOUS). Without this, scripted mode gets no LLM-calling consolidation "
                    + "phases — drive-description-generation silently skips and characters fall back to "
                    + "YAML briefing prose instead of emerged somatic descriptions")
                .doesNotContain("if (mode == io.casehub.examples.manor.model.PlaybookMode.AUTONOMOUS)");
    }
}
