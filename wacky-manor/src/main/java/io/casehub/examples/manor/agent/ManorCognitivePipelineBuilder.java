package io.casehub.examples.manor.agent;

import io.casehub.eidos.api.AgentRegistry;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.core.CognitivePipelineBuilder;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.CognitiveProfile;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.experience.ExperienceRecorder;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class ManorCognitivePipelineBuilder {

    private static final Logger log = Logger.getLogger(ManorCognitivePipelineBuilder.class);

    private final @Nullable MindMapStore mindMapStore;
    private final String tenantId;
    private final CbrRecordStore cbrStore;

    private @Nullable SessionPipeline sessionPipeline;
    private @Nullable ManorConfig config;
    private @Nullable CapsEngine capsEngine;
    private @Nullable ExperienceRecorder experienceRecorder;
    private @Nullable AgentRegistry agentRegistry;
    private @Nullable GoalProposalOrchestrator goalOrchestrator;
    private @Nullable CognitionConfig cognitionConfig;
    private @Nullable CognitiveProfile cognitiveProfile;
    private @Nullable AgentExperienceService experienceService;
    private @Nullable SituationClassifier situationClassifier;
    private Map<String, SocialConfig> socialConfigs = Map.of();
    private boolean seedDrives = true;
    private boolean seedCaps = true;
    private boolean seedFormationMemories = true;
    private boolean seedGoals = true;
    private boolean seedPeople = true;

    private ManorCognitivePipelineBuilder(@Nullable MindMapStore mindMapStore,
                                          String tenantId,
                                          CbrRecordStore cbrStore) {
        this.mindMapStore = mindMapStore;
        this.tenantId = tenantId;
        this.cbrStore = cbrStore;
    }

    public static ManorCognitivePipelineBuilder create(@Nullable MindMapStore mindMapStore,
                                                       String tenantId,
                                                       CbrRecordStore cbrStore) {
        return new ManorCognitivePipelineBuilder(mindMapStore, tenantId, cbrStore);
    }

    public ManorCognitivePipelineBuilder sessionPipeline(@Nullable SessionPipeline pipeline) {
        this.sessionPipeline = pipeline;
        return this;
    }

    public ManorCognitivePipelineBuilder config(ManorConfig config) {
        this.config = config;
        return this;
    }

    public ManorCognitivePipelineBuilder capsEngine(CapsEngine capsEngine) {
        this.capsEngine = capsEngine;
        return this;
    }

    public ManorCognitivePipelineBuilder experienceRecorder(ExperienceRecorder recorder) {
        this.experienceRecorder = recorder;
        return this;
    }

    public ManorCognitivePipelineBuilder agentRegistry(AgentRegistry registry) {
        this.agentRegistry = registry;
        return this;
    }

    public ManorCognitivePipelineBuilder goalOrchestrator(GoalProposalOrchestrator goals) {
        this.goalOrchestrator = goals;
        return this;
    }

    public ManorCognitivePipelineBuilder cognitionConfig(CognitionConfig cognitionConfig) {
        this.cognitionConfig = cognitionConfig;
        return this;
    }

    public ManorCognitivePipelineBuilder cognitiveProfile(@Nullable CognitiveProfile profile) {
        this.cognitiveProfile = profile;
        return this;
    }

    public ManorCognitivePipelineBuilder experienceService(@Nullable AgentExperienceService service) {
        this.experienceService = service;
        return this;
    }

    public ManorCognitivePipelineBuilder situationClassifier(@Nullable SituationClassifier classifier) {
        this.situationClassifier = classifier;
        return this;
    }

    public ManorCognitivePipelineBuilder socialConfigs(Map<String, SocialConfig> configs) {
        this.socialConfigs = configs;
        return this;
    }

    public ManorCognitivePipelineBuilder seedDrives(boolean enabled) {
        this.seedDrives = enabled;
        return this;
    }

    public ManorCognitivePipelineBuilder seedCaps(boolean enabled) {
        this.seedCaps = enabled;
        return this;
    }

    public ManorCognitivePipelineBuilder seedFormationMemories(boolean enabled) {
        this.seedFormationMemories = enabled;
        return this;
    }

    public ManorCognitivePipelineBuilder seedGoals(boolean enabled) {
        this.seedGoals = enabled;
        return this;
    }

    public ManorCognitivePipelineBuilder seedPeople(boolean enabled) {
        this.seedPeople = enabled;
        return this;
    }

    public ManorCognitivePipeline build() {
        var effectiveConfig = cognitionConfig != null ? cognitionConfig : CognitionConfig.all();

        var moodOrch = new io.casehub.neocortex.cognition.mood.MoodOrchestrator(
                config != null ? config.mood().toMoodConfig()
                        : io.casehub.neocortex.cognition.mood.MoodConfig.defaults());
        var narrativeOrch = new io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator(
                new io.casehub.neocortex.cognition.narrative.NarrativeMemory(
                        cbrStore, io.casehub.neocortex.cognition.narrative.NarrativeConfig.defaults()));
        var memoryHygiene = new io.casehub.agency.social.memory.MemoryHygieneOrchestrator(
                cbrStore,
                new io.casehub.agency.social.memory.CompositeConfidenceScorer(java.util.List.of(
                        new io.casehub.agency.social.memory.WeightedScorer(
                                new io.casehub.agency.social.memory.ArousalScorer(), 0.5),
                        new io.casehub.agency.social.memory.WeightedScorer(
                                new io.casehub.agency.social.memory.SurpriseScorer(), 0.5))),
                new io.casehub.neocortex.memory.cbr.TemporalDecay.HalfLife(java.time.Duration.ofDays(365)),
                new io.casehub.neocortex.memory.cbr.ScopeDecay.Step(1.0), null,
                io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults().memoryDomain(),
                java.util.List.of(io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults().engagementCaseType()),
                io.casehub.agency.social.memory.RetentionConfig.DEFAULT, 10, 0.7, event -> {});
        var memoryHygieneAdapter = new io.casehub.agency.social.cognition.MemoryHygieneSpiAdapter(memoryHygiene);

        io.casehub.neocortex.memory.reflection.ReflectionOrchestrator noOpReflection =
                (agentId, tid, since, maxEntries) -> java.util.List.of();
        var userModelOrch = new io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator(
                new io.casehub.neocortex.cognition.usermodel.UserProfileMemory(
                        cbrStore, io.casehub.neocortex.cognition.usermodel.UserModelConfig.defaults()),
                io.casehub.neocortex.cognition.usermodel.UserModelConfig.defaults(), sessionPipeline);
        var mentalModelOrch = new io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator(
                new io.casehub.neocortex.cognition.mentalmodel.MentalModelMemory(
                        cbrStore, io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig.defaults()),
                io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig.defaults(), java.time.Clock.systemUTC(), sessionPipeline);
        var strategyOrch = new io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator(
                new io.casehub.neocortex.cognition.strategy.StrategyMemory(
                        cbrStore, io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults()),
                noOpReflection,
                io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults(), java.time.Clock.systemUTC(), sessionPipeline);

        var curiosityDrive = new io.casehub.neocortex.cognition.drive.CuriosityDrive(memoryHygieneAdapter);
        var competenceDrive = new io.casehub.neocortex.cognition.drive.CompetenceDrive(strategyOrch);
        var affiliationDrive = new io.casehub.neocortex.cognition.drive.AffiliationDrive(
                userModelOrch, 0.3, java.time.Duration.ofHours(1));
        var autonomyDrive = new io.casehub.neocortex.cognition.drive.AutonomyDrive(mentalModelOrch, 0.5);
        var driveOrch = new io.casehub.neocortex.cognition.drive.DriveOrchestrator(
                curiosityDrive, competenceDrive, affiliationDrive, autonomyDrive,
                moodOrch, new io.casehub.neocortex.cognition.drive.DriveComposer(),
                io.casehub.neocortex.cognition.drive.DriveConfig.defaults());

        var innerLifeOrch = new io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator(
                noOpReflection, java.util.List.of(),
                io.casehub.neocortex.cognition.innerlife.InnerLifeConfig.defaults(), driveOrch, sessionPipeline);

        var goals = goalOrchestrator != null ? goalOrchestrator
                : new io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator(
                        driveOrch, java.util.List.of(), null, java.util.Optional.empty(),
                        null, null, null,
                        io.casehub.neocortex.cognition.goal.GoalProposalConfig.defaults(),
                        io.casehub.neocortex.cognition.goal.GoalEscalationConfig.defaults(),
                        java.time.Clock.systemUTC());

        var defaultsRegistry = new CognitiveDefaultsRegistry();
        var cogBuilder = CognitivePipelineBuilder
                .create(moodOrch, driveOrch)
                .userModel(userModelOrch)
                .mentalModel(mentalModelOrch)
                .strategy(strategyOrch)
                .narrative(narrativeOrch)
                .goals(goals)
                .memoryHygiene(memoryHygieneAdapter)
                .innerLife(innerLifeOrch)
                .mindMapStore(mindMapStore)
                .needTierMappingProvider(new ManorNeedTierMappingProvider())
                .sessionPipeline(sessionPipeline)
                .defaultsRegistry(defaultsRegistry);

        if (config != null && config.appraisal().enabled() && sessionPipeline != null) {
            cogBuilder.appraisal(
                    new io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy(sessionPipeline),
                    new io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy());
            cogBuilder.situationClassifier(situationClassifier);
        }

        var cognitionCore = cogBuilder.build();
        var seeder = mindMapStore != null ? new ManorCognitiveSeeder(mindMapStore) : null;
        var cognitions = new HashMap<String, CharacterCognition>();

        for (var entry : socialConfigs.entrySet()) {
            String agentId = entry.getKey();
            SocialConfig socialCfg = entry.getValue();

            io.casehub.eidos.api.AgentDescriptor desc = null;
            java.util.List<io.casehub.eidos.api.AgentConstraint> constraints = java.util.List.of();
            if (agentRegistry != null) {
                desc = agentRegistry.findById(agentId, tenantId).orElse(null);
                if (desc != null) {
                    constraints = desc.constraints();
                }
            }

            var cogDefaults = desc != null
                    ? ManorCognitiveSetup.deriveDefaults(desc, socialCfg)
                    : io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(
                            new io.casehub.neocortex.cognitive.index.DescriptorView(
                                    agentId, null, java.util.List.of(), java.util.List.of(), null));
            defaultsRegistry.register(cogDefaults);

            ManorCognitiveSeeder.SeedResult seedResult = null;
            if (seeder != null) {
                if (seedDrives) {
                    seedResult = seeder.seed(agentId, socialCfg, tenantId);
                }
                if (seedGoals && goals != null) {
                    seeder.seedGoals(agentId, socialCfg, goals, tenantId);
                }
                if (seedCaps && capsEngine != null) {
                    seeder.seedCapsState(agentId, socialCfg, tenantId, capsEngine);
                }
                if (seedFormationMemories && experienceRecorder != null) {
                    int count = seeder.seedFormationMemories(agentId, socialCfg, tenantId, experienceRecorder);
                    if (count > 0) {
                        log.infof("Seeded %d formation memories for %s", count, agentId);
                    }
                }
            }

            cognitions.put(agentId, new CharacterCognition(
                    agentId, experienceService, cogDefaults, socialCfg, constraints,
                    cognitiveProfile, new ManorContextStrategy(), cognitionCore, seedResult, tenantId,
                    mindMapStore, ManorTrustEvolutionConfigLoader.load(), situationClassifier));
        }

        if (seeder != null && seedPeople) {
            seeder.seedPeople(socialConfigs, tenantId);
        }

        return new ManorCognitivePipeline(cognitionCore, mindMapStore, cognitions, defaultsRegistry, tenantId);
    }
}
