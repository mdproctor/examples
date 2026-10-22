package io.casehub.examples.manor.agent;

import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.innerlife.InnerLifeConfig;
import io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.strategy.StrategyLearningConfig;
import io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator;
import io.casehub.neocortex.cognition.usermodel.UserModelConfig;
import io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator;
import io.casehub.neocortex.cognition.drive.AffiliationDrive;
import io.casehub.neocortex.cognition.drive.AutonomyDrive;
import io.casehub.neocortex.cognition.drive.CompetenceDrive;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.goal.GoalEscalationConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator;
import io.casehub.neocortex.cognition.prompt.DrivePromptSection;
import io.casehub.neocortex.cognition.prompt.EmergentGoalPromptSection;
import io.casehub.neocortex.cognition.prompt.MentalModelPromptSection;
import io.casehub.neocortex.cognition.prompt.MoodPromptSection;
import io.casehub.neocortex.cognition.prompt.NarrativePromptSection;
import io.casehub.neocortex.cognition.prompt.StrategyPromptSection;
import io.casehub.neocortex.cognition.prompt.UserModelPromptSection;
import io.casehub.blocks.memory.ArousalScorer;
import io.casehub.blocks.memory.CompositeConfidenceScorer;
import io.casehub.blocks.memory.MemoryHygieneOrchestrator;
import io.casehub.blocks.memory.RetentionConfig;
import io.casehub.blocks.memory.SurpriseScorer;
import io.casehub.blocks.memory.WeightedScorer;
import io.casehub.neocortex.memory.cbr.ScopeDecay;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class CognitiveActivationTest {

    public static CognitionCore buildCore(CognitionConfig config) {
        return buildCore(config, null);
    }

    public static CognitionCore buildCore(CognitionConfig config, io.casehub.platform.agent.session.SessionPipeline sessionPipeline) {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var cbrStore = new InMemoryCbrRecordStore();
        var narrativeOrch = new NarrativeOrchestrator(new io.casehub.neocortex.cognition.narrative.NarrativeMemory(cbrStore, io.casehub.neocortex.cognition.narrative.NarrativeConfig.defaults()));
        var memoryHygiene = new MemoryHygieneOrchestrator(
                cbrStore,
                new CompositeConfidenceScorer(List.of(
                        new WeightedScorer(new ArousalScorer(), 0.5),
                        new WeightedScorer(new SurpriseScorer(), 0.5))),
                new TemporalDecay.HalfLife(Duration.ofDays(365)),
                new ScopeDecay.Step(1.0), null,
                StrategyLearningConfig.defaults().memoryDomain(),
                List.of(StrategyLearningConfig.defaults().engagementCaseType()),
                RetentionConfig.DEFAULT, 10, 0.7, event -> {});
        var memoryHygieneAdapter = new io.casehub.blocks.agentic.cognition.MemoryHygieneSpiAdapter(memoryHygiene);

        io.casehub.neocortex.memory.reflection.ReflectionOrchestrator noOpReflection =
                (agentId, tenantId, since, maxEntries) -> List.of();
        var userModel = new UserModelOrchestrator(
                new io.casehub.neocortex.cognition.usermodel.UserProfileMemory(cbrStore, UserModelConfig.defaults()), UserModelConfig.defaults());
        var mentalModel = new MentalModelOrchestrator(
                new io.casehub.neocortex.cognition.mentalmodel.MentalModelMemory(cbrStore, MentalModelConfig.defaults()), MentalModelConfig.defaults());
        var strategy = new StrategyLearningOrchestrator(
                new io.casehub.neocortex.cognition.strategy.StrategyMemory(cbrStore, StrategyLearningConfig.defaults()), noOpReflection,
                StrategyLearningConfig.defaults());

        var drives = new DriveOrchestrator(
                new CuriosityDrive(memoryHygieneAdapter), new CompetenceDrive(strategy),
                new AffiliationDrive(userModel, 0.3, Duration.ofHours(1)),
                new AutonomyDrive(mentalModel, 0.5),
                mood, new DriveComposer(), DriveConfig.defaults());

        var innerLife = new InnerLifeOrchestrator(
                noOpReflection, List.of(), InnerLifeConfig.defaults(), drives);

        var goals = new GoalProposalOrchestrator(
                drives, List.of(), null, Optional.empty(),
                null, null, null,
                GoalProposalConfig.defaults(), GoalEscalationConfig.defaults(),
                java.time.Clock.systemUTC());

        return new CognitionCore(mood, drives, userModel, mentalModel, strategy,
                narrativeOrch, goals, memoryHygieneAdapter, innerLife, config, null, null, null, null, null, null, null,
                sessionPipeline);
    }

    @Test void tickRunsWithoutErrorOnFullConfig() {
        var core = buildCore(CognitionConfig.all());
        core.tick("test-agent", "test-tenant", null, (a, t) -> Set.of("other"));
    }

    @Test void allSectionsPresentWithFullConfig() {
        var core = buildCore(CognitionConfig.all());
        core.tick("test-agent", "test-tenant", null, (a, t) -> Set.of("other"));
        var sections = core.promptSections();
        assertThat(sections).anyMatch(s -> s instanceof MoodPromptSection);
        assertThat(sections).anyMatch(s -> s instanceof DrivePromptSection);
        assertThat(sections).anyMatch(s -> s instanceof NarrativePromptSection);
        assertThat(sections).anyMatch(s -> s instanceof UserModelPromptSection);
        assertThat(sections).anyMatch(s -> s instanceof MentalModelPromptSection);
        assertThat(sections).anyMatch(s -> s instanceof StrategyPromptSection);
        assertThat(sections).anyMatch(s -> s instanceof EmergentGoalPromptSection);
    }

    @Test void sectionAbsentWhenSubsystemDisabled() {
        var config = CognitionConfig.none().with("goals", true);
        var core = buildCore(config);
        core.tick("test-agent", "test-tenant", null, (a, t) -> Set.of());
        var sections = core.promptSections();
        assertThat(sections).anyMatch(s -> s instanceof EmergentGoalPromptSection);
        assertThat(sections).noneMatch(s -> s instanceof MoodPromptSection);
        assertThat(sections).noneMatch(s -> s instanceof NarrativePromptSection);
    }

    record ConfigLevel(String label, CognitionConfig config, int minSections) {}

    static Stream<ConfigLevel> configProgression() {
        var base = CognitionConfig.none()
                .with("goals", true).with("characterDrives", true).with("needsPyramid", true);
        return Stream.of(
                new ConfigLevel("baseline", base, 1),
                new ConfigLevel("+mood", base.with("mood", true), 2),
                new ConfigLevel("+narrative", base.with("mood", true).with("narrative", true), 3),
                new ConfigLevel("+models", base.with("mood", true).with("narrative", true)
                        .with("userModel", true).with("mentalModel", true), 5),
                new ConfigLevel("+strategy", base.with("mood", true).with("narrative", true)
                        .with("userModel", true).with("mentalModel", true).with("strategy", true), 6),
                new ConfigLevel("+drives", CognitionConfig.all().without("memoryHygiene", "innerLife"), 7),
                new ConfigLevel("full", CognitionConfig.all(), 7)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configProgression")
    void configLevelProducesSections(ConfigLevel level) {
        var core = buildCore(level.config());
        core.tick("test-agent", "test-tenant", null, (a, t) -> Set.of("other"));
        var sections = core.promptSections();
        assertThat(sections).hasSizeGreaterThanOrEqualTo(level.minSections());
    }
}
