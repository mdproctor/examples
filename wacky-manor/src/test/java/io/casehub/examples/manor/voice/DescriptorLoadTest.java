package io.casehub.examples.manor.voice;

import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.Visibility;
import io.casehub.examples.manor.ManorConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DescriptorLoadTest {

    @Inject
    AgentRegistry registry;

    @Inject
    SystemPromptRenderer renderer;

    @Test
    void five_characters_registered_at_startup() {
        var all = registry.find(AgentQuery.all(ManorConstants.TENANCY_ID));
        assertThat(all).hasSize(5);
        assertThat(all).extracting(m -> m.descriptor().agentId())
                       .containsExactlyInAnyOrder(
                               "penelope-pitstop", "hooded-claw", "ant-hill-mob",
                               "dick-dastardly", "peter-perfect");
    }

    @Test
    void hooded_claw_has_villain_disposition() {
        var desc = registry.findById("hooded-claw", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.disposition().primaryTerm(io.casehub.eidos.api.DispositionAxis.RISK_APPETITE)).isEqualTo("bold");
        assertThat(desc.disposition().primaryTerm(io.casehub.eidos.api.DispositionAxis.CONFLICT_MODE)).isEqualTo("competitive");
        assertThat(desc.briefing()).containsIgnoringCase("Hooded Claw");
        assertThat(desc.templates()).extracting(t -> t.templateId())
                                    .contains("cartoon-villain");}

    @Test
    void penelope_has_collaborative_disposition() {
        var desc = registry.findById("penelope-pitstop", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.disposition().primaryTerm(io.casehub.eidos.api.DispositionAxis.SOCIAL_ORIENTATION)).isEqualTo("cooperative");
        assertThat(desc.briefing()).containsIgnoringCase("Southern");
    }

    @Test
    void hooded_claw_has_villain_template() {
        var desc = registry.findById("hooded-claw", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.templates()).isNotEmpty();
        assertThat(desc.templates()).extracting(t -> t.templateId())
                                    .contains("theatrical-eccentric-style", "cartoon-villain");
    }

    @Test
    void rendered_prompt_includes_identity_and_directives() {
        var desc     = registry.findById("hooded-claw", ManorConstants.TENANCY_ID).orElseThrow();
        var ctx      = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);
        var rendered = renderer.render(desc, ctx);
        assertThat(rendered.content())
                .as("Rendered prompt should include character name")
                .containsIgnoringCase("Hooded Claw");
        assertThat(rendered.content())
                .as("Rendered prompt should include prime directives")
                .containsIgnoringCase("Never reveal your true identity");
        assertThat(rendered.content())
                .as("Rendered prompt should include cognitive preamble")
                .containsIgnoringCase("inner life");
    }

    @Test
    void all_characters_share_hanna_barbera_template() {
        var all = registry.find(AgentQuery.all(ManorConstants.TENANCY_ID));
        for (var match : all) {
            assertThat(match.descriptor().templates())
                    .as("Character %s should reference hanna-barbera-cartoon-style", match.descriptor().name())
                    .extracting(t -> t.templateId())
                    .contains("theatrical-eccentric-style");
        }
    }

    @Test
    void hooded_claw_has_private_elimination_goal() {
        var desc = registry.findById("hooded-claw", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.goals()).isNotEmpty();
        var eliminateGoal = desc.goals().stream()
                                .filter(g -> g.name().equals("eliminate-penelope"))
                                .findFirst().orElseThrow();
        assertThat(eliminateGoal.priority()).isEqualTo(GoalPriority.PRIMARY);
        assertThat(eliminateGoal.visibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void penelope_goals_are_public() {
        var desc = registry.findById("penelope-pitstop", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.publicGoals()).isNotEmpty();
        assertThat(desc.publicGoals()).allSatisfy(g ->
                                                          assertThat(g.visibility()).isEqualTo(Visibility.PUBLIC));
    }

    @Test
    void hooded_claw_has_never_break_cover_constraint() {
        var desc = registry.findById("hooded-claw", ManorConstants.TENANCY_ID).orElseThrow();
        assertThat(desc.constraints()).isNotEmpty();
        assertThat(desc.constraints()).extracting(c -> c.name())
                                      .contains("never-break-cover");
    }
}
