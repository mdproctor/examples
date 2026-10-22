package io.casehub.examples.manor.agent;

import io.casehub.eidos.api.AgentConstraint;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.goal.GoalEscalationConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.prompt.CognitiveSystemPromptRenderer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DirectiveMinimalIntegrationTest {

    @Test
    void systemPromptContainsOnlyIdentityAndHardConstraints() {
        var hard = new AgentConstraint("no-break", "Never break cover",
                Visibility.PUBLIC, ConstraintSeverity.HARD);
        var soft = new AgentConstraint("be-polite", "Always be polite",
                Visibility.PUBLIC, ConstraintSeverity.SOFT);
        var desc = AgentDescriptor.builder()
                .agentId("penelope-pitstop")
                .name("Penelope Pitstop")
                .slot("test")
                .tenancyId("test-tenant")
                .briefing("A glamorous Southern belle. You speak with a Southern drawl.")
                .constraints(List.of(hard, soft))
                .build();

        var renderer = new CognitiveSystemPromptRenderer(CognitionConfig.all());
        var result = renderer.render(desc, AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("Penelope Pitstop");
        assertThat(result.content()).contains("Southern belle");
        assertThat(result.content()).contains("Prime Directives");
        assertThat(result.content()).contains("Never break cover");
        assertThat(result.content()).doesNotContain("Always be polite");
        assertThat(result.content()).contains("inner life");
        assertThat(result.format()).isEqualTo(RenderFormat.MARKDOWN);
        assertThat(result.enriched()).isFalse();
    }

    @Test
    void seededGoalsRenderThroughCognitionCore() {
        var goalOrchestrator = new GoalProposalOrchestrator(
                null, List.of(), null, Optional.empty(),
                null, null, null,
                GoalProposalConfig.defaults(),
                GoalEscalationConfig.defaults(),
                Clock.systemUTC());
        var config = CognitionConfig.none().with("goals", true);
        var core = new CognitionCore(
                null, null, null, null, null, null,
                goalOrchestrator, null, null, config, null, null, null, null, null, null, null, null);

        var socialConfig = ManorSocialConfigLoader.load().get("penelope-pitstop");
        var proposals = ManorCognitiveSeeder.mapGoals(socialConfig.goals());
        goalOrchestrator.registerGoals("penelope-pitstop", "test-tenant", proposals);

        var sections = core.promptSections();
        var goalSection = sections.stream()
                .filter(s -> {
                    var rendered = s.render(new io.casehub.neocortex.cognition.prompt.CognitionRenderContext("penelope-pitstop", "test-tenant", null));
                    return rendered != null && rendered.contains("goals");
                })
                .findFirst();

        assertThat(goalSection).isPresent();
        var rendered = goalSection.get().render(new io.casehub.neocortex.cognition.prompt.CognitionRenderContext("penelope-pitstop", "test-tenant", null));
        assertThat(rendered).contains("curiosity").contains("priority");
    }

    @Test
    void characterCognitionOmitsConstraints() {
        var hard = new AgentConstraint("no-break", "Never break cover",
                Visibility.PUBLIC, ConstraintSeverity.HARD);
        var soft = new AgentConstraint("be-polite", "Always be polite",
                Visibility.PUBLIC, ConstraintSeverity.SOFT);
        var socialConfig = ManorSocialConfigLoader.load().get("penelope-pitstop");
        var cognition = new CharacterCognition("penelope-pitstop", null, null,
                socialConfig, List.of(hard, soft));

        var sections = cognition.renderCognitiveSections(
                new io.casehub.examples.manor.model.CharacterState(
                        "penelope-pitstop", "Penelope", "Room", 0.0, List.of()),
                List.of(), java.util.Map.of());

        assertThat(sections).noneMatch(s -> s.header().equals("Your Principles"));
        assertThat(sections.stream().map(s -> s.header()).toList())
                .doesNotContain("Your Principles");
    }

    @Test
    void allCharacterGoalsSeededFromSocialConfig() {
        var goalOrchestrator = new GoalProposalOrchestrator(
                null, List.of(), null, Optional.empty(),
                null, null, null,
                GoalProposalConfig.defaults(),
                GoalEscalationConfig.defaults(),
                Clock.systemUTC());

        var socialConfigs = ManorSocialConfigLoader.load();
        for (var entry : socialConfigs.entrySet()) {
            if (!entry.getValue().goals().isEmpty()) {
                var proposals = ManorCognitiveSeeder.mapGoals(entry.getValue().goals());
                goalOrchestrator.registerGoals(entry.getKey(), "test-tenant", proposals);
                var stored = goalOrchestrator.currentProposals(entry.getKey(), "test-tenant");
                assertThat(stored).isPresent();
                assertThat(stored.get()).hasSameSizeAs(entry.getValue().goals());
            }
        }
    }

    @Test
    void allBriefingsAreIdentityOnly() throws Exception {
        var url = Thread.currentThread().getContextClassLoader()
                        .getResource("META-INF/eidos/descriptors-composite.yaml");
        assertThat(url).isNotNull();
        try (var stream = url.openStream()) {
            var descriptors = new io.casehub.eidos.core.registrar.ClasspathYamlDescriptorRegistrar()
                                      .loadFrom(stream, null);
            for (var desc : descriptors) {
                var briefing = desc.briefing();
                if (briefing == null || briefing.isBlank()) {continue;}
                var wordCount = briefing.strip().split("\\s+").length;
                assertThat(wordCount)
                        .as("Briefing for %s is %d words (max 40)", desc.agentId(), wordCount)
                        .isLessThanOrEqualTo(40);
                assertThat(briefing)
                        .as("Briefing for %s should not contain situational instructions", desc.agentId())
                        .doesNotContainIgnoringCase("when you")
                        .doesNotContainIgnoringCase("before you")
                        .doesNotContainIgnoringCase("if someone");
            }
        }
    }

    @Test
    void allCharactersHaveVoiceSections() throws Exception {
        var url = Thread.currentThread().getContextClassLoader()
                        .getResource("META-INF/eidos/descriptors-composite.yaml");
        assertThat(url).isNotNull();
        try (var stream = url.openStream()) {
            var descriptors = new io.casehub.eidos.core.registrar.ClasspathYamlDescriptorRegistrar()
                                      .loadFrom(stream, null);
            for (var desc : descriptors) {
                assertThat(desc.voice())
                        .as("Character %s should have a voice section", desc.agentId())
                        .isNotNull();
            }
        }
    }

    @Test
    void tendenciesRenderedAsFirstCognitiveSection() {
        var socialConfigs = ManorSocialConfigLoader.load();
        for (var entry : socialConfigs.entrySet()) {
            if (entry.getValue().tendencies().isEmpty()) {continue;}
            var cognition = new CharacterCognition(entry.getKey(), null, null,
                                                   entry.getValue(), java.util.List.of());
            var sections = cognition.renderCognitiveSections(
                    new io.casehub.examples.manor.model.CharacterState(
                            entry.getKey(), entry.getKey(), "Room", 0.0, java.util.List.of()),
                    java.util.List.of(), java.util.Map.of());
            assertThat(sections).isNotEmpty();
            assertThat(sections.get(0).header())
                    .as("First cognitive section for %s should be tendencies", entry.getKey())
                    .isEqualTo("Your Behavioral Tendencies");
        }
    }

    @Test
    void allCharactersHaveTendencies() {
        var socialConfigs = ManorSocialConfigLoader.load();
        for (var entry : socialConfigs.entrySet()) {
            assertThat(entry.getValue().tendencies())
                    .as("Character %s should have tendencies", entry.getKey())
                    .isNotEmpty();
        }
    }


}
