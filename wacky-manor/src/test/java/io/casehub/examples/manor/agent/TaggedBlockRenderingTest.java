package io.casehub.examples.manor.agent;

import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.prompt.BlockTag;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TaggedBlockRenderingTest {

    @Test
    void directivePrompts_renderWithBlockTags() {
        var config = CognitionConfig.all().with("directivePrompts", true);
        var core = CognitiveActivationTest.buildCore(config);

        core.tick("hooded-claw", "wacky-manor", null,
                  (a, t) -> Set.of("peter-perfect"),
                  "Penelope is examining the bookcase. Peter Perfect is watching her with concern.");
        core.tick("hooded-claw", "wacky-manor", null,
                  (a, t) -> Set.of("peter-perfect"),
                  "Peter says: 'I think something is wrong with the cellar door.'");

        var sections = core.promptSections();
        var ctx = new CognitionRenderContext("hooded-claw", "wacky-manor", null);

        var renderedTexts = sections.stream()
            .map(s -> s.render(ctx))
            .filter(t -> t != null && !t.isBlank())
            .toList();

        assertThat(renderedTexts)
            .as("At least some sections should render")
            .isNotEmpty();

        System.out.println("\n=== TAGGED BLOCK RENDERING ===");
        for (String text : renderedTexts) {
            var preview = text.length() > 120 ? text.substring(0, 120) + "..." : text;
            System.out.println(preview);
            System.out.println("---");
        }
        System.out.println("=== END ===\n");

        System.out.println("=== ALL SECTIONS (including null-content) ===");
        for (var section : sections) {
            var tag = section.blockTag();
            var text = section.render(ctx);
            System.out.printf("  %-20s tag=%-15s content=%s%n",
                section.getClass().getSimpleName(),
                tag != null ? tag.name() : "(none)",
                text != null ? "yes (" + text.length() + " chars)" : "null");
        }
        System.out.println("=== END ===\n");

        var taggedCount = renderedTexts.stream()
            .filter(t -> t.startsWith("["))
            .count();

        assertThat(taggedCount)
            .as("Sections with blockTag should render with [TAG] prefix")
            .isGreaterThan(0);

        for (String text : renderedTexts) {
            if (text.startsWith("[")) {
                var tagEnd = text.indexOf(']');
                var tagName = text.substring(1, tagEnd);
                assertThat(BlockTag.valueOf(tagName))
                    .as("Tag %s should be a valid BlockTag", tagName)
                    .isNotNull();
            }
        }
    }

    @Test
    void sections_sortedByBlockTagOrdinal() {
        var config = CognitionConfig.all().with("directivePrompts", true);
        var core = CognitiveActivationTest.buildCore(config);

        core.tick("peter-perfect", "wacky-manor", null, (a, t) -> Set.of("hooded-claw"));

        var sections = core.promptSections();

        int lastOrdinal = -1;
        for (var section : sections) {
            var tag = section.blockTag();
            if (tag != null) {
                assertThat(tag.sortOrdinal())
                    .as("Sections should be sorted by tag ordinal — %s (%d) should come after %d",
                        tag, tag.sortOrdinal(), lastOrdinal)
                    .isGreaterThanOrEqualTo(lastOrdinal);
                lastOrdinal = tag.sortOrdinal();
            }
        }
    }
}
