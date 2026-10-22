package io.casehub.examples.manor.agent;

import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.CoherenceLevel;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.examples.manor.ManorConstants;
import io.casehub.examples.manor.engine.ActionResolver;
import io.casehub.examples.manor.engine.MansionLoader;
import io.casehub.examples.manor.engine.SceneDirector;
import io.casehub.examples.manor.engine.TriggerEvaluator;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionResult;
import io.casehub.examples.manor.model.PendingAction;
import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlaybookOrchestrator {

    private static final Logger log = Logger.getLogger(PlaybookOrchestrator.class);

    @Inject
    AgentProvider                               agentProvider;
    @Inject
    io.casehub.platform.agent.BackendInstanceRegistry backendRegistry;
    @Inject
    AgentRegistry                               agentRegistry;
    @Inject
    SystemPromptRenderer                        renderer;
    @Inject
    ManorChannels                               manorChannels;
    @Inject
    io.casehub.examples.manor.web.ManorEventBus webEventBus;
    @Inject
    io.casehub.neocortex.memory.experience.ExperienceRecorder experienceRecorder;
    @Inject
    io.casehub.neocortex.memory.CaseMemoryStore caseMemoryStore;
    @Inject
    io.casehub.neocortex.memory.cbr.CbrRecordStore cbrRecordStore;
    @Inject
    io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationScheduler consolidationScheduler;
    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.neocortex.cognitive.index.CognitiveProfile> cognitiveProfileInstance;
    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.neocortex.mindmap.MindMapStore> mindMapStoreInstance;
    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.neocortex.mindmap.intelligence.MindMapExtractor> mindMapExtractorInstance;
    @Inject
    io.casehub.neocortex.caps.CapsEngine capsEngine;
    @Inject
    io.casehub.neocortex.caps.SituationClassifier situationClassifier;
    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.neocortex.mindmap.intelligence.consolidation.SignificanceAccumulator> significanceAccumulator;


    @Inject
    ManorConfig config;
    @Inject
    ManorGoalFormationStrategy goalFormationStrategy;
    @Inject
    ManorGoalRevisionStrategy  goalRevisionStrategy;
    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.scenario.profile", defaultValue = "BASELINE")
    io.casehub.examples.manor.model.ProfileMode profileMode;


    @Inject
    ManorPlanRevisionStrategy  planRevisionStrategy;

    @Inject
    jakarta.enterprise.event.Event<io.casehub.engine.trust.TrustRelevantAction> trustEvent;

    @Inject
    ClassificationModeRegistry                            classificationModeRegistry;
    @Inject
    io.casehub.platform.agent.session.SessionPoolRegistry sessionPoolRegistry;
    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.platform.agent.claude.max-concurrent-sessions", defaultValue = "12")
    int maxConcurrentSessions;

    private volatile AgentProvider gatedProvider;
    private final    java.util.Map<String, String> subgraphIdCache = new java.util.concurrent.ConcurrentHashMap<>();


    public Thread startScenario(WorldState world, io.casehub.examples.manor.model.PlaybookMode mode) {
        var triggers         = MansionLoader.loadTriggers();
        var scenes           = MansionLoader.loadScenes();
        var triggerEvaluator = new TriggerEvaluator(triggers);
        var sceneDirector    = new SceneDirector(scenes);
        var actionResolver   = new ActionResolver();

        return Thread.ofVirtual().name("scenario-loop")
                     .start(() -> {
                         try {
                             runScenario(world, triggerEvaluator,
                                         sceneDirector, actionResolver, mode);
                         } catch (Throwable t) {
                             log.error("scenario-loop crashed", t);
                         }
                     });
    }

    private void runScenario(WorldState world,
                             TriggerEvaluator triggerEvaluator,
                             SceneDirector sceneDirector,
                             ActionResolver actionResolver,
                             io.casehub.examples.manor.model.PlaybookMode mode) {
        manorChannels.initChannels();
        manorChannels.dispatchScenarioStart();
        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("started"));
        webEventBus.broadcast(webEventBus.buildSnapshot(world));

        var compactor          = new MechanicalCompactor();
        var summariser         = new ManorLlmSummariser(agentProvider);
        var obsRenderer        = new ManorObservationRenderer(compactor, config.observation().verbatimThreshold(), config.observation().groupedThreshold(), summariser);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var reflectionSynthesizer = new ManorReflectionSynthesizer(gatedProvider);
        var reflectionTrigger = new ManorReflectionTrigger(config.reflection().maxUnreflected(), config.reflection().importanceThreshold());
        ManorPlanEvaluator planEvaluator = null;
        if (config.plan().enabled() && config.goal().enabled()) {
            var planFormationStrategy = new ManorPlanFormationStrategy(gatedProvider);
            planEvaluator = new ManorPlanEvaluator(planFormationStrategy, planRevisionStrategy,
                caseMemoryStore, ManorConstants.TENANCY_ID,
                agentId -> world.character(agentId), config.plan().maxRevisionGeneration());
        }
        ManorGoalEvaluator goalEvaluator = null;
        if (config.goal().enabled()) {
            goalEvaluator = new ManorGoalEvaluator(goalFormationStrategy, goalRevisionStrategy,
                agentRegistry, caseMemoryStore, ManorConstants.TENANCY_ID,
                config.goal().cooldownTicks(), config.goal().maxNewPerReflection(), planEvaluator);
        }
        var experienceService = new AgentExperienceService(new ExperienceConfig(
            experienceRecorder, caseMemoryStore, ManorConstants.TENANCY_ID,
            reflectionSynthesizer, reflectionTrigger,
            config.reflection().enabled(), config.memory().decayEnabled(), config.memory().decayMaxAgeDays(), config.memory().decayMinImportance(),
            config.reflection().maxSourceMemories(), config.memory().recallLimit(), goalEvaluator, planEvaluator));

        var cogProfile = cognitiveProfileInstance.isResolvable() ? cognitiveProfileInstance.get() : null;
        var mmStore = mindMapStoreInstance.isResolvable() ? mindMapStoreInstance.get() : null;
        var seeder = mmStore != null ? new ManorCognitiveSeeder(mmStore) : null;
        var contextStrategy = new ManorContextStrategy();

        var sessionLifecycleManager = new io.casehub.platform.agent.session.SessionLifecycleManager(sessionPoolRegistry);
        io.casehub.platform.agent.session.SessionPipeline sessionPipeline = null;
        if (mode == io.casehub.examples.manor.model.PlaybookMode.AUTONOMOUS) {
            var admission = new io.casehub.platform.agent.gate.AimdAdmissionStrategy(
                    maxConcurrentSessions, 5, java.time.Duration.ofSeconds(10));
            sessionPipeline = new io.casehub.platform.agent.session.SessionPipeline(
                    sessionLifecycleManager, "claude", admission, 80_000);
        }

        // Phase 1 — Foundation (no dependencies)
        var moodOrch = new io.casehub.neocortex.cognition.mood.MoodOrchestrator(io.casehub.neocortex.cognition.mood.MoodConfig.defaults());
        var narrativeOrch = new io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator(new io.casehub.neocortex.cognition.narrative.NarrativeMemory(cbrRecordStore, io.casehub.neocortex.cognition.narrative.NarrativeConfig.defaults()));
        var cbrStore = cbrRecordStore;
        var memoryHygiene = new io.casehub.blocks.memory.MemoryHygieneOrchestrator(
                cbrStore,
                new io.casehub.blocks.memory.CompositeConfidenceScorer(java.util.List.of(
                        new io.casehub.blocks.memory.WeightedScorer(new io.casehub.blocks.memory.ArousalScorer(), 0.5),
                        new io.casehub.blocks.memory.WeightedScorer(new io.casehub.blocks.memory.SurpriseScorer(), 0.5))),
                new io.casehub.neocortex.memory.cbr.TemporalDecay.HalfLife(java.time.Duration.ofDays(365)),
                new io.casehub.neocortex.memory.cbr.ScopeDecay.Step(1.0), null,
                io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults().memoryDomain(),
                java.util.List.of(io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults().engagementCaseType()),
                io.casehub.blocks.memory.RetentionConfig.DEFAULT, 10, 0.7, event -> {});
        var memoryHygieneAdapter = new io.casehub.blocks.agentic.cognition.MemoryHygieneSpiAdapter(memoryHygiene);

        // Phase 2 — Independent orchestrators (D6)
        io.casehub.neocortex.memory.reflection.ReflectionOrchestrator noOpReflection =
                (agentId, tenantId, since, maxEntries) -> java.util.List.of();
        var userModelOrch = new io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator(
                new io.casehub.neocortex.cognition.usermodel.UserProfileMemory(cbrRecordStore, io.casehub.neocortex.cognition.usermodel.UserModelConfig.defaults()), io.casehub.neocortex.cognition.usermodel.UserModelConfig.defaults(), sessionPipeline);
        var mentalModelOrch = new io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator(
                new io.casehub.neocortex.cognition.mentalmodel.MentalModelMemory(cbrRecordStore, io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig.defaults()), io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig.defaults(), sessionPipeline);
        var strategyOrch = new io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator(
                new io.casehub.neocortex.cognition.strategy.StrategyMemory(cbrRecordStore, io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults()), noOpReflection,
                io.casehub.neocortex.cognition.strategy.StrategyLearningConfig.defaults(), sessionPipeline);

        // Phase 3 — Explicit DriveSource pattern (D11)
        var curiosityDrive = new io.casehub.neocortex.cognition.drive.CuriosityDrive(memoryHygieneAdapter);
        var competenceDrive = new io.casehub.neocortex.cognition.drive.CompetenceDrive(strategyOrch);
        var affiliationDrive = new io.casehub.neocortex.cognition.drive.AffiliationDrive(userModelOrch, 0.3, java.time.Duration.ofHours(1));
        var autonomyDrive = new io.casehub.neocortex.cognition.drive.AutonomyDrive(mentalModelOrch, 0.5);
        var driveOrch = new io.casehub.neocortex.cognition.drive.DriveOrchestrator(
                curiosityDrive, competenceDrive, affiliationDrive, autonomyDrive,
                moodOrch, new io.casehub.neocortex.cognition.drive.DriveComposer(),
                io.casehub.neocortex.cognition.drive.DriveConfig.defaults());

        // Phase 4 — InnerLife (depends on Phase 3)
        var innerLifeOrch = new io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator(
                noOpReflection, java.util.List.of(),
                io.casehub.neocortex.cognition.innerlife.InnerLifeConfig.defaults(), driveOrch, sessionPipeline);

        // Phase 5 — Goals (pass driveOrch instead of null)
        var goalOrchestrator = new io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator(
                driveOrch, java.util.List.of(), null, java.util.Optional.empty(),
                null, null, null,
                io.casehub.neocortex.cognition.goal.GoalProposalConfig.defaults(),
                io.casehub.neocortex.cognition.goal.GoalEscalationConfig.defaults(),
                java.time.Clock.systemUTC());

        var defaultsRegistry = new io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry();
        var cogBuilder = io.casehub.neocortex.cognition.core.CognitivePipelineBuilder
                .create(moodOrch, driveOrch)
                .userModel(userModelOrch)
                .mentalModel(mentalModelOrch)
                .strategy(strategyOrch)
                .narrative(narrativeOrch)
                .goals(goalOrchestrator)
                .memoryHygiene(memoryHygieneAdapter)
                .innerLife(innerLifeOrch)
                .mindMapStore(mmStore)
                .needTierMappingProvider(new ManorNeedTierMappingProvider())
                .sessionPipeline(sessionPipeline)
                .defaultsRegistry(defaultsRegistry);

        if (config.appraisal().enabled()) {
            cogBuilder.appraisal(
                    new io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy(sessionPipeline),
                    new io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy());
            log.info("Appraisal enabled — sub-LLM will evaluate situations against character drives and disposition");
        }

        var cognitionCore = cogBuilder.build();

        var cognitions = new java.util.HashMap<String, CharacterCognition>();

        NarratorAgent narratorAgent = null;

        var dispatcher = new ManorEventDispatcher(
                world, observationService, narratorAgent,
                manorChannels, webEventBus);

        var activeSet = config.activeCharacters().isBlank() ? null
                : java.util.Set.copyOf(java.util.Arrays.asList(config.activeCharacters().split(",")));

        var socialConfigs = ManorSocialConfigLoader.loadForProfile(profileMode.name());

        if (significanceAccumulator.isResolvable()) {
            significanceAccumulator.get().suspend();
        }

        var cacheDir = java.nio.file.Path.of(System.getProperty("user.home"), "claude", "casehub", "consolidation-cache");
        boolean cacheRestored = false;
        if (config.consolidation().enabled() && mmStore != null) {
            cacheRestored = ConsolidationCache.restore(mmStore, ManorConstants.TENANCY_ID, profileMode.name(), cacheDir);
        }

        for (var entry : world.characters().entrySet()) {
            if (activeSet != null && !activeSet.contains(entry.getKey())) {continue;}
            var desc = agentRegistry.findById(entry.getKey(), ManorConstants.TENANCY_ID)
                    .orElseThrow(() -> new IllegalStateException("No Eidos descriptor for character: " + entry.getKey()));
            var tags = desc.capabilities().stream()
                    .flatMap(c -> c.tags().stream())
                    .collect(java.util.stream.Collectors.toSet());
            entry.getValue().setCapabilityTags(tags);
            var socialCfg = socialConfigs.getOrDefault(entry.getKey(), SocialConfig.empty());
            var cogDefaults = ManorCognitiveSetup.deriveDefaults(desc, socialCfg);
            defaultsRegistry.register(cogDefaults);
            ManorCognitiveSeeder.SeedResult seedResult = null;
            if (seeder != null) {
                if (!cacheRestored) {
                    seedResult = seeder.seed(entry.getKey(), socialCfg, ManorConstants.TENANCY_ID);
                }
                seeder.seedGoals(entry.getKey(), socialCfg, goalOrchestrator, ManorConstants.TENANCY_ID);
                seeder.seedCapsState(entry.getKey(), socialCfg, ManorConstants.TENANCY_ID, capsEngine);
                int memCount = seeder.seedFormationMemories(entry.getKey(), socialCfg, ManorConstants.TENANCY_ID, experienceRecorder);
                if (memCount > 0) {
                    log.info("Seeded " + memCount + " formation memories for " + entry.getKey());
                }
            }
            cognitions.put(entry.getKey(), new CharacterCognition(
                    entry.getKey(), experienceService, cogDefaults, socialCfg, desc.constraints(),
                    cogProfile, contextStrategy, cognitionCore, seedResult, ManorConstants.TENANCY_ID,
                    mindMapStoreInstance.isResolvable() ? mindMapStoreInstance.get() : null,
                    ManorTrustEvolutionConfigLoader.load(),
                    situationClassifier));
        }

        if (seeder != null && !cacheRestored) {
            seeder.seedPeople(socialConfigs, ManorConstants.TENANCY_ID);
        }

        if (config.consolidation().enabled() && !cacheRestored) {
            log.info("Running initial consolidation — processing formation memories into behavioral attractors...");
            consolidationScheduler.consolidateNow(ManorConstants.TENANCY_ID);
            log.info("Initial consolidation complete — characters have 'slept' on their memories.");
            if (mmStore != null) {
                try {
                    ConsolidationCache.dump(mmStore, ManorConstants.TENANCY_ID, profileMode.name(), cacheDir);
                } catch (java.io.IOException e) {
                    log.warn("Failed to save consolidation cache", e);
                }
            }
        }
        // Keep significance accumulator suspended during scenario — its background
        // mindMapStore writes contend with renderCognitiveSections() reads via synchronized

        if (mode == io.casehub.examples.manor.model.PlaybookMode.AUTONOMOUS) {
            consolidationScheduler.setSessionPipeline(sessionPipeline);
            for (var entry : world.characters().entrySet()) {
                if (activeSet != null && !activeSet.contains(entry.getKey())) continue;
                sessionPipeline.openSession(entry.getKey(),
                        io.casehub.platform.agent.AgentSessionInit.of(renderPrompt(entry.getKey())));
            }
            if (config.narrator().enabled()) {
                var narratorSession = sessionPipeline.openSession("narrator",
                        io.casehub.platform.agent.AgentSessionInit.of(NarratorSummariser.SYSTEM_PROMPT));
                narratorAgent = new NarratorAgent(
                        compactor, narratorSession, manorChannels, webEventBus,
                        config.narrator().eventThreshold(), config.narrator().timerSeconds());
                narratorAgent.start(world);
                dispatcher.setNarrator(narratorAgent);
            }
            runAutonomousTicks(world, activeSet, actionResolver, dispatcher, sessionPipeline, narratorAgent, cognitions, planEvaluator, cognitionCore);
            sessionPipeline.shutdown();
        } else {
            var invocationService = new AgentInvocationService(sessionLifecycleManager, "claude", 60, 2, 2000);
            runScripted(world, activeSet, actionResolver, dispatcher, invocationService,
                        triggerEvaluator, sceneDirector, narratorAgent, cognitions);
        }

        String reason = world.completionReason() != null ? world.completionReason().name().toLowerCase() : null;
        manorChannels.dispatchScenarioComplete();
        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("completed", reason));

        if (narratorAgent != null) {
            narratorAgent.stop();
            try {
                narratorAgent.thread().join(Duration.ofSeconds(120));
                if (narratorAgent.thread().isAlive()) {
                    log.warn("Narrator thread did not terminate");
                    narratorAgent.thread().interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("Scenario complete" + (reason != null ? " — " + reason : ""));
    }

    private void runAutonomousTicks(WorldState world, java.util.Set<String> activeSet,
                                     ActionResolver actionResolver, ManorEventDispatcher dispatcher,
                                     io.casehub.platform.agent.session.SessionPipeline sessionPipeline, NarratorAgent narratorAgent,
                                     java.util.Map<String, CharacterCognition> cognitions,
                                     ManorPlanEvaluator planEvaluator,
                                     io.casehub.neocortex.cognition.core.CognitionCore cognitionCore) {
        var activeAgents = world.characters().values().stream()
                .filter(c -> activeSet == null || activeSet.contains(c.agentId()))
                .toList();

        var pipelineClassifier = new PipelineSituationClassifier(capsEngine.topology());
        var classificationValidator = new io.casehub.neocortex.caps.engine.ClassificationValidator(capsEngine.topology());
        var inlinePromptBuilder = new io.casehub.neocortex.caps.llm.InlineClassificationPromptBuilder(capsEngine.topology());
        var classificationConfig = config.classification();
        io.casehub.neocortex.caps.engine.ClassificationAuditLog auditLog = null;
        if (classificationConfig.auditEnabled()) {
            auditLog = new io.casehub.neocortex.caps.engine.ClassificationAuditLog(
                    java.nio.file.Path.of(classificationConfig.auditLogPath()));
            log.infof("Classification audit log: %s", classificationConfig.auditLogPath());
        }
        var accumulators = new java.util.HashMap<String, io.casehub.neocortex.caps.engine.DegreeAccumulator>();
        var trajectoryBuffers = new java.util.HashMap<String, java.util.ArrayList<io.casehub.neocortex.caps.TrajectoryEntry>>();
        var classificationDue = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        for (var c : activeAgents) {
            sessionPipeline.openSession("classify:" + c.agentId(),
                    io.casehub.platform.agent.AgentSessionInit.of(pipelineClassifier.systemPrompt()));
            accumulators.put(c.agentId(), io.casehub.neocortex.caps.engine.DegreeAccumulator.fromConfig(classificationConfig.accumulatorConfig()));
            trajectoryBuffers.put(c.agentId(), new java.util.ArrayList<>());
        }

        CognitiveSnapshotRecorder snapshotRecorder = null;
        int snapshotInterval = config.cognitiveSnapshot().intervalTicks();
        if (snapshotInterval > 0) {
            try {
                var snapshotPath = java.nio.file.Path.of("docs/eval/cognitive-snapshots-" + java.time.LocalDate.now() + ".jsonl");
                snapshotRecorder = new CognitiveSnapshotRecorder(cognitionCore, ManorConstants.TENANCY_ID, snapshotInterval, snapshotPath, cognitions);
            } catch (java.io.IOException e) {
                log.error("Failed to create cognitive snapshot recorder", e);
            }
        }

        int tick = 0;
        var previousSituationContexts = new java.util.concurrent.ConcurrentHashMap<String, io.casehub.neocortex.caps.SituationContext>();
        while (!world.isScenarioComplete()) {
            tick++;

            while (world.isPaused() && !world.isScenarioComplete()) {
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
            if (world.isScenarioComplete()) break;

            int currentTick = tick;

            io.casehub.neocortex.cognition.core.SubjectResolver subjectResolver = (aid, tid) -> {
                var ch = world.character(aid);
                if (ch == null) return java.util.Set.of();
                return world.charactersInRoom(ch.currentRoom()).stream()
                        .map(io.casehub.examples.manor.model.CharacterState::agentId)
                        .filter(id -> !id.equals(aid))
                        .collect(java.util.stream.Collectors.toSet());
            };

            long cogStart = System.currentTimeMillis();
            for (var c : activeAgents) {
                if (!c.isActive()) continue;
                var desc0 = agentRegistry.findById(c.agentId(), ManorConstants.TENANCY_ID).orElse(null);
                String situation = c.lastActionResult() != null ? c.lastActionResult() : "You are in the " + c.currentRoom() + ".";
                cognitionCore.tick(c.agentId(), ManorConstants.TENANCY_ID, desc0, subjectResolver, situation);
            }
            log.infof("Cognitive tick for %d agents in %dms", activeAgents.size(), System.currentTimeMillis() - cogStart);

            if (snapshotRecorder != null && snapshotRecorder.shouldCapture(currentTick)) {
                var snapshotAgentIds = activeAgents.stream()
                        .filter(io.casehub.examples.manor.model.CharacterState::isActive)
                        .map(io.casehub.examples.manor.model.CharacterState::agentId)
                        .toList();
                snapshotRecorder.capture(currentTick, snapshotAgentIds);
            }

            var actingThisTick = activeAgents.stream()
                    .filter(io.casehub.examples.manor.model.CharacterState::isActive)
                    .filter(c -> currentTick % cadence(c) == 0)
                    .toList();
            if (actingThisTick.isEmpty()) continue;

            // Phase 1: build observations (render is now purely mechanical — no LLM calls)
            var preCogSections = new java.util.HashMap<String, java.util.List<io.casehub.blocks.summarisation.observation.affordance.ObservationSection>>();
            var preTagSections = new java.util.HashMap<String, java.util.List<String>>();
            for (var c : actingThisTick) {
                var cognition = cognitions.get(c.agentId());
                var nearbyIds = world.charactersInRoom(c.currentRoom()).stream()
                        .map(io.casehub.examples.manor.model.CharacterState::agentId)
                        .filter(id -> !id.equals(c.agentId()))
                        .toList();
                var agentNameMap = new java.util.HashMap<String, String>();
                for (var id : nearbyIds) {
                    var nearby = world.character(id);
                    if (nearby != null) agentNameMap.put(id, nearby.name());
                }
                var sitCtx = previousSituationContexts.getOrDefault(c.agentId(), io.casehub.neocortex.caps.SituationContext.EMPTY);
                var cogFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        cognition.renderCognitiveSections(c, nearbyIds, agentNameMap, sitCtx));
                var tagFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        cognition.renderTaggedSections());
                try {
                    preCogSections.put(c.agentId(), cogFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
                    preTagSections.put(c.agentId(), tagFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    log.warnf("%s: renderCognitiveSections timed out (5s) — skipping", c.agentId());
                    preCogSections.put(c.agentId(), java.util.List.of());
                    preTagSections.put(c.agentId(), java.util.List.of());
                    cogFuture.cancel(true);
                    tagFuture.cancel(true);
                } catch (Exception e) {
                    log.warnf("%s: renderCognitiveSections failed: %s", c.agentId(), e.getMessage());
                    preCogSections.put(c.agentId(), java.util.List.of());
                    preTagSections.put(c.agentId(), java.util.List.of());
                }
            }

            // Phase 1: assemble observations in parallel (no synchronized code)
            var prompts = TickExecutor.buildObservationsParallel(actingThisTick, world, (c, w) -> {
                var cognition = cognitions.get(c.agentId());
                var drain = dispatcher.observationService().drain(c.agentId(), System.currentTimeMillis());
                var reflections = cognition.recallReflections(5);
                var relationships = new java.util.HashMap<String, java.util.List<io.casehub.neocortex.memory.Memory>>();
                for (var other : w.charactersInRoom(c.currentRoom())) {
                    if (!other.agentId().equals(c.agentId())) {
                        var relMems = cognition.recallRelationships(other.agentId(), 3);
                        if (!relMems.isEmpty()) {
                            relationships.put(other.name(), relMems);
                        }
                    }
                }
                var memories = cognition.recallMemories(config.memory().recallLimit());
                var worldProvider = new ManorWorldObservationProvider(c, w, drain);
                var obsPipeline = new io.casehub.blocks.summarisation.observation.affordance.ObservationPipeline(new io.casehub.blocks.summarisation.observation.affordance.PerceptionFilter());
                return new io.casehub.examples.manor.agent.ObservationBuilder(worldProvider, obsPipeline, c.capabilityTags())
                        .withCharacter(c)
                        .withGoals(resolveGoals(c.agentId()))
                        .withDrain(drain)
                        .withMemories(memories, reflections, relationships)
                        .withCognitiveSections(preCogSections.get(c.agentId()))
                        .withTaggedSections(preTagSections.get(c.agentId()))
                        .build() + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION
                        + (classificationDue.contains(c.agentId())
                            ? "\n\n" + inlinePromptBuilder.buildAssessmentInstruction(trajectoryBuffers.getOrDefault(c.agentId(), new java.util.ArrayList<>()))
                            : "");
            }, java.time.Duration.ofSeconds(30));

            // Phase 2: query via adaptive pipeline
            var pipelineResults = TickExecutor.queryPipeline(prompts, sessionPipeline, 3, java.time.Duration.ofSeconds(120));

            // Phase 3: parse responses (outside pipeline — parse errors don't consume tickets)
            var responses = TickExecutor.parseResponses(actingThisTick, pipelineResults);
            log.infof("Tick %d complete (%d agents, %d succeeded)", currentTick, actingThisTick.size(),
                    pipelineResults.values().stream().filter(java.util.Objects::nonNull).count());

            var suppressed = new java.util.HashSet<String>();
            var exchangeTexts = new ArrayList<String[]>();
            var exchangeRunner = new ExchangeRunner(3, 120_000);
            for (var c : actingThisTick) {
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.action() != null && response.action().type() == io.casehub.examples.manor.model.ActionType.PULL_ASIDE) {
                    String targetId = response.action().target();
                    var target = world.character(targetId);
                    if (target == null || !target.isActive() || !target.currentRoom().equals(c.currentRoom()) || suppressed.contains(c.agentId()) || suppressed.contains(targetId) || agentRegistry.findById(targetId, ManorConstants.TENANCY_ID).isEmpty()) {
                        c.setLastActionResult("Could not pull " + targetId + " aside.");
                    } else {
                        suppressed.add(c.agentId());
                        suppressed.add(targetId);
                        var exchangeEvents = exchangeRunner.run(c, target, response.dialogue(), world, sessionPipeline, this::renderPrompt);
                        for (var event : exchangeEvents) {
                            dispatcher.publishDialogue(event, "");
                        }
                        var exchangeText = exchangeEvents.stream()
                                .map(io.casehub.examples.manor.model.ManorEvent::detailedDescription)
                                .filter(d -> d != null && !d.isBlank())
                                .collect(java.util.stream.Collectors.joining("\n"));
                        if (!exchangeText.isBlank()) {
                            exchangeTexts.add(new String[]{exchangeText, c.agentId(), targetId, c.currentRoom()});
                        }
                        c.setLastActionResult("You had a private conversation with " + target.name() + ".");
                        target.setLastActionResult(c.name() + " pulled you aside for a private conversation.");
                    }
                }
            }

            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.dialogue() != null) {
                    String validatedTalkTo = response.talkTo();
                    if (validatedTalkTo != null) {
                        var target = world.character(validatedTalkTo);
                        if (target == null || !target.currentRoom().equals(c.currentRoom())) {
                            validatedTalkTo = null;
                        }
                    }
                    if (validatedTalkTo != null) {
                        var narr = NarrativeEventBuilder.describeDirectedDialogue(c.name(), validatedTalkTo, response.dialogue());
                        var event = new io.casehub.examples.manor.model.ManorEvent.Dialogue(
                                java.time.Instant.now(), c.agentId(), c.currentRoom(),
                                narr.publicText(), narr.detailedText(), validatedTalkTo, response.thinking());
                        dispatcher.publishDialogue(event, response.dialogue());
                    } else {
                        var event = new io.casehub.examples.manor.model.ManorEvent.Dialogue(
                                java.time.Instant.now(), c.agentId(),
                                c.currentRoom(), c.name() + ": " + response.dialogue(),
                                null, null, response.thinking());
                        dispatcher.publishDialogue(event, response.dialogue());
                    }
                }
                if (response.aside() != null) {
                    var event = new io.casehub.examples.manor.model.ManorEvent.Aside(
                            java.time.Instant.now(), c.agentId(),
                            c.currentRoom(), response.aside(), response.thinking());
                    dispatcher.publishAside(event, response.aside());
                }
            }

            for (var ex : exchangeTexts) {
                extractDialogueKnowledge(ex[0], ex[1], ex[2], ex[3], true, world);
            }
            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null || response.dialogue() == null) continue;
                String dialogueText = response.dialogue();
                String validatedTarget = response.talkTo();
                if (validatedTarget != null) {
                    var tgt = world.character(validatedTarget);
                    if (tgt == null || !tgt.currentRoom().equals(c.currentRoom())) {
                        validatedTarget = null;
                    }
                }
                extractDialogueKnowledge(dialogueText, c.agentId(), validatedTarget,
                        c.currentRoom(), false, world);
                var observation = prompts.get(c.agentId());
                cognitionCore.recordInteraction(c.agentId(), ManorConstants.TENANCY_ID,
                        validatedTarget, observation != null ? observation : "", dialogueText, null);
            }

            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.action() != null && response.action().type() != io.casehub.examples.manor.model.ActionType.WAIT) {
                    String departureRoom = c.currentRoom();
                    var result = actionResolver.resolve(c, response.action(), world);
                    var narration = NarrativeEventBuilder.describeRich(c, response.action(), result);
                    if (narration != null) {
                        var actionType = response.action().type();
                        boolean concealed = c.capabilityTags().contains("deception")
                                && (actionType == io.casehub.examples.manor.model.ActionType.STEAL
                                    || actionType == io.casehub.examples.manor.model.ActionType.USE);
                        var enrichedEvent = new io.casehub.examples.manor.model.ManorEvent.Action(
                                java.time.Instant.now(), c.agentId(), c.currentRoom(),
                                narration.publicText(), actionType, response.action().target(),
                                response.action().withItem(),
                                actionType == io.casehub.examples.manor.model.ActionType.MOVE ? departureRoom : null,
                                narration.detailedText(), concealed);
                        dispatcher.publishAction(enrichedEvent, result, c.x());
                    }
                    c.setLastActionResult(result.text());
                    if (result instanceof ActionResult.Failed failure && planEvaluator != null) {
                        String aType = response.action() != null ? response.action().type().name() : "WAIT";
                        String aTarget = response.action() != null ? response.action().target() : "";
                        planEvaluator.reviseOnFailure(c.agentId(), aType, aTarget, failure, currentTick);
                    }
                    String trustTarget = extractTargetAgent(response);
                    if (trustTarget != null) {
                        var trustActionType = response.action().type();
                        boolean trustConcealed = c.capabilityTags().contains("deception")
                            && (trustActionType == io.casehub.examples.manor.model.ActionType.STEAL
                                || trustActionType == io.casehub.examples.manor.model.ActionType.USE);
                        java.util.List<String> witnessIds = world.charactersInRoom(c.currentRoom()).stream()
                            .map(io.casehub.examples.manor.model.CharacterState::agentId)
                            .filter(id -> !id.equals(c.agentId()) && !id.equals(trustTarget))
                            .toList();
                        java.util.List<String> effectiveWitnesses = trustConcealed ? java.util.List.of() : witnessIds;
                        trustEvent.fireAsync(new io.casehub.engine.trust.TrustRelevantAction(
                            c.agentId(), trustTarget, trustActionType.name(),
                            result.text(), effectiveWitnesses, ManorConstants.TENANCY_ID));
                    }
                } else {
                    c.setLastActionResult("You waited and observed.");
                }
                if (response.thinking() != null) {
                    c.setCurrentThinking(response.thinking());
                }
                var cognition = cognitions.get(c.agentId());
                double importance = cognition.computeImportance(response.action() != null ? response.action().type() : null);
                String targetAgentId = extractTargetAgent(response);
                String desc = (response.dialogue() != null ? response.dialogue() + " " : "")
                              + (response.action() != null ? response.action().type() + " " + response.action().target() : "WAIT");
                cognition.recordExperience(c.currentRoom(), desc.strip(), response.thinking(), importance, targetAgentId, currentTick);
            }

            var innerLife = cognitionCore.innerLife();
            for (var c : actingThisTick) {
                var response = responses.get(c.agentId());
                if (response == null) continue;
                var behavior = RegulationTracker.classifyBehavior(response);

                final String observationText;
                if (innerLife != null) {
                    var sb = new StringBuilder();
                    if (response.dialogue() != null) {
                        sb.append(c.name()).append(": ").append(response.dialogue());
                    }
                    if (response.action() != null && response.action().type() != io.casehub.examples.manor.model.ActionType.WAIT) {
                        if (!sb.isEmpty()) sb.append(" ");
                        sb.append(c.name()).append(" ").append(response.action().type().name().toLowerCase());
                        if (response.action().target() != null) sb.append(" ").append(response.action().target());
                    }
                    observationText = sb.isEmpty() ? null : sb.toString();
                } else {
                    observationText = null;
                }

                for (var observer : world.charactersInRoom(c.currentRoom())) {
                    if (observer.agentId().equals(c.agentId())) continue;
                    var observerCognition = cognitions.get(observer.agentId());
                    if (observerCognition != null) {
                        observerCognition.observeCharacterState(c.agentId(), behavior,
                            io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel.NONE);
                    }
                    if (innerLife != null && observationText != null) {
                        agentRegistry.findById(observer.agentId(), ManorConstants.TENANCY_ID).ifPresent(observerDesc -> {
                            var levelEvent = new io.casehub.neocortex.summarisation.LevelEvent<>(
                                    observationText, System.currentTimeMillis(),
                                    new io.casehub.neocortex.summarisation.EventLevel("SOCIAL", 1),
                                    ManorConstants.TENANCY_ID);
                            innerLife.observe(levelEvent, observerDesc);
                        });
                    }
                }
            }

            // Demand-driven classification: degree accumulation + inline/external routing
            int classified = 0;
            for (var c : actingThisTick) {
                if (!c.isActive()) continue;
                var response = responses.get(c.agentId());
                if (response == null) continue;

                // Process inline assessment from prior tick's classificationDue
                if (classificationDue.remove(c.agentId())) {
                    if (response.situationAssessment() != null) {
                        var assessment = response.situationAssessment();
                        log.infof("  %s: inline assessment — %s (%d activations)",
                                c.agentId(), assessment.reasoning(),
                                assessment.activations() != null ? assessment.activations().size() : 0);
                        var activations = assessment.activations().stream()
                            .map(a -> new io.casehub.neocortex.caps.SituationActivation(a.node(), a.confidence()))
                            .toList();
                        var ctx = classificationValidator.validate(activations);
                        previousSituationContexts.put(c.agentId(), ctx);
                        var trajectory = new java.util.ArrayList<>(trajectoryBuffers.get(c.agentId()));
                        trajectoryBuffers.get(c.agentId()).clear();
                        if (!ctx.isEmpty()) {
                            classified++;
                            if (auditLog != null) {
                                try {
                                    auditLog.write(currentTick, c.agentId(), "inline",
                                            trajectory, ctx.activations(), assessment.reasoning());
                                } catch (java.io.IOException e) {
                                    log.warnf("  %s: audit log write failed: %s", c.agentId(), e.getMessage());
                                }
                            }
                        }
                    } else {
                        log.warnf("  %s: classification due but no situationAssessment in response", c.agentId());
                    }
                }

                // Feed degree to accumulator
                double degree = response.situationChangeDegree() != null ? response.situationChangeDegree() : 0.0;
                var accumulator = accumulators.get(c.agentId());
                trajectoryBuffers.get(c.agentId()).add(
                    new io.casehub.neocortex.caps.TrajectoryEntry(currentTick,
                        c.lastActionResult() != null ? c.lastActionResult() : ""));

                if (accumulator != null) {
                    boolean fired = accumulator.accumulate(degree);
                    log.infof("  %s: degree=%.2f fast=%.3f slow=%.3f%s",
                            c.agentId(), degree, accumulator.currentLevel(), accumulator.slowLevel(),
                            fired ? " → CLASSIFICATION DUE" : "");
                    if (fired) {
                        if (classificationModeRegistry.isExternal(c.agentId())) {
                            // External mode: classify immediately via pipeline session
                            var trajectory = new java.util.ArrayList<>(trajectoryBuffers.get(c.agentId()));
                            var description = inlinePromptBuilder.renderTrajectoryText(trajectory);
                            var userPrompt = pipelineClassifier.buildUserPrompt(description,
                                    java.util.Map.of("agentId", c.agentId()));
                            var raw = sessionPipeline.executeSingle("classify:" + c.agentId(),
                                    session -> AgentInvocationService.querySessionRaw(
                                            (io.casehub.platform.agent.session.ManagedSession) session,
                                            userPrompt, "classify:" + c.agentId()),
                                    java.time.Duration.ofSeconds(60));
                            var ctx = pipelineClassifier.parseResponse(raw);
                            previousSituationContexts.put(c.agentId(), ctx);
                            trajectoryBuffers.get(c.agentId()).clear();
                            if (!ctx.isEmpty()) {
                                classified++;
                                log.infof("  %s: external classification — %d activations", c.agentId(), ctx.activations().size());
                                if (auditLog != null) {
                                    try {
                                        auditLog.write(currentTick, c.agentId(), "external",
                                                trajectory, ctx.activations(), null);
                                    } catch (java.io.IOException e) {
                                        log.warnf("  %s: audit log write failed: %s", c.agentId(), e.getMessage());
                                    }
                                }
                            }
                        } else {
                            // Inline mode: mark for assessment in next tick's prompt
                            classificationDue.add(c.agentId());
                        }
                    }
                }
            }
            if (classified > 0) {
                log.infof("Inline classification: %d agents classified", classified);
            }

            if (config.consolidation().enabled()
                    && config.consolidation().intervalTicks() > 0
                    && tick > 0
                    && tick % config.consolidation().intervalTicks() == 0) {
                log.info("Night falls on the mansion. Characters rest and reflect...");
                webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(
                    "Night falls. The characters rest and reflect on the day's events..."));
                consolidationScheduler.consolidateNow(ManorConstants.TENANCY_ID);
                webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(
                    "Dawn breaks. A new day begins..."));
            }

            if (tick >= config.maxTurns()) {
                world.setScenarioComplete(io.casehub.examples.manor.model.CompletionReason.DAWN);
            }

            webEventBus.broadcast(webEventBus.buildSnapshot(world));
            log.infof("Tick %d: %d agents acted", tick, actingThisTick.size());
        }

        if (snapshotRecorder != null) {
            snapshotRecorder.close();
        }
    }

    private void runScripted(WorldState world, java.util.Set<String> activeSet,
                              ActionResolver actionResolver, ManorEventDispatcher dispatcher,
                              AgentInvocationService invocationService,
                              TriggerEvaluator triggerEvaluator, SceneDirector sceneDirector,
                              NarratorAgent narratorAgent,
                              java.util.Map<String, CharacterCognition> cognitions) {
        var actionQueue = new LinkedBlockingQueue<PendingAction>();
        var threads = world.characters().values().stream()
                .filter(c -> activeSet == null || activeSet.contains(c.agentId()))
                .map(c -> {
                    var goals = resolveGoals(c.agentId());
                    var cognition = cognitions.get(c.agentId());
                    return Thread.ofVirtual().name(c.agentId())
                            .uncaughtExceptionHandler((t, e) -> {
                                log.errorf(e, "Character %s crashed", t.getName());
                                world.markCharacterInactive(t.getName());
                            })
                            .start(() -> {
                                String systemPrompt = renderPrompt(c.agentId());
                                new CharacterAgentLoop().run(
                                        c, world, invocationService, null,
                                        systemPrompt, actionQueue, dispatcher, goals,
                                        cognition);
                            });
                })
                .toList();

        while (!world.isScenarioComplete()) {
            try {
                PendingAction pending = actionQueue.poll(5, TimeUnit.SECONDS);
                if (pending == null) continue;
                if (!pending.character().isActive()) {
                    pending.complete(new ActionResult.Failed("Character is no longer active."));
                    continue;
                }
                String departureRoom = pending.character().currentRoom();
                var result = actionResolver.resolve(pending.character(), pending.action(), world);
                var richNarrative = NarrativeEventBuilder.describeRich(pending.character(), pending.action(), result);
                if (richNarrative != null) {
                    var actionType = pending.action().type();
                    boolean concealed = pending.character().capabilityTags().contains("deception")
                            && (actionType == io.casehub.examples.manor.model.ActionType.STEAL
                                || actionType == io.casehub.examples.manor.model.ActionType.USE);
                    var enrichedEvent = new io.casehub.examples.manor.model.ManorEvent.Action(
                            java.time.Instant.now(), pending.character().agentId(),
                            pending.character().currentRoom(), richNarrative.publicText(),
                            actionType, pending.action().target(), pending.action().withItem(),
                            actionType == io.casehub.examples.manor.model.ActionType.MOVE ? departureRoom : null,
                            richNarrative.detailedText(), concealed);
                    dispatcher.publishAction(enrichedEvent, result, pending.character().x());
                }
                pending.character().setLastActionResult(result.text());

                var triggerResult = triggerEvaluator.evaluate(world);
                for (String narratorText : triggerResult.narratorEvents()) {
                    world.addEvent("narrator", null, null, narratorText);
                    manorChannels.dispatchNarration(narratorText);
                    webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(narratorText));
                }
                if (triggerResult.hasSceneStart()) {
                    manorChannels.dispatchSceneEvent(triggerResult.sceneId(), "started");
                    webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scene(triggerResult.sceneId(), "started"));
                    sceneDirector.runScene(triggerResult.sceneId(), world, this::callAgentForScene, narration -> {
                        world.addEvent("narrator", null, null, narration);
                        manorChannels.dispatchNarration(narration);
                        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(narration));
                    });
                    manorChannels.dispatchSceneEvent(triggerResult.sceneId(), "ended");
                    webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scene(triggerResult.sceneId(), "ended"));
                }
                pending.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (var t : threads) {
            try {
                t.join(Duration.ofSeconds(5));
                if (t.isAlive()) { log.warnf("Character %s did not terminate", t.getName()); t.interrupt(); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private java.util.List<io.casehub.eidos.api.AgentGoal> resolveGoals(String agentId) {
        return agentRegistry.findById(agentId, ManorConstants.TENANCY_ID)
                            .map(desc -> desc.goals())
                            .orElse(java.util.List.of());
    }

    private String callAgentForScene(String characterId, String prompt) {
        String systemPrompt = renderPrompt(characterId);
        try {
            return agentProvider.invoke(
                                        AgentSessionConfig.of(systemPrompt, prompt))
                                .filter(e -> e instanceof AgentEvent.TextDelta)
                                .map(e -> ((AgentEvent.TextDelta) e).text())
                                .collect().with(Collectors.joining())
                                .await().atMost(Duration.ofSeconds(120));
        } catch (Exception e) {
            log.warnf("Scene LLM call failed for %s: %s", characterId, e.getMessage());
            return "[" + characterId + " is speechless]";
        }
    }

    private String renderPrompt(String agentId) {
        var desc = agentRegistry.findById(agentId, ManorConstants.TENANCY_ID)
                                .orElseThrow(() -> new IllegalArgumentException("No descriptor: " + agentId));
        var ctx      = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);
        var rendered = renderer.render(desc, ctx);

        if (rendered.coherenceReport() != null
            && rendered.coherenceReport().overall() != CoherenceLevel.ALIGNED) {
            for (var v : rendered.coherenceReport().violations()) {
                log.warnf("[%s] %s coherence %s: %s (declared=%s, implied=%s)",
                          agentId, v.level(), v.axis() != null ? v.axis() : "orientation",
                          v.description(), v.declaredValue(), v.impliedValue());
            }
        }

        return rendered.content();
    }

    private static int cadence(io.casehub.examples.manor.model.CharacterState c) {
        return Math.max(1, (int) (c.thinkDelayMs() / 2000));
    }

    private static String extractTargetAgent(AgentResponse response) {
        if (response.talkTo() != null) {return response.talkTo();}
        if (response.action() == null) {return null;}
        String target = response.action().target();
        if (target == null) {return null;}
        return switch (response.action().type()) {
            case GIVE, STEAL, PULL_ASIDE -> target;
            default -> null;
        };
    }


    private void extractDialogueKnowledge(String text, String speakerId,
                                          String dialogueTargetId, String room, boolean isExchange,
                                          WorldState world) {
        if (!isExtractableDialogue(text)) {return;}
        if (!mindMapExtractorInstance.isResolvable()) {return;}

        var listeners = determineListeners(speakerId, dialogueTargetId, room, isExchange, world);
        if (listeners.isEmpty()) {return;}

        var mindMapExtractor = mindMapExtractorInstance.get();
        var mindMapStore     = mindMapStoreInstance.isResolvable() ? mindMapStoreInstance.get() : null;
        if (mindMapStore == null) {return;}

        var nearbyNames = world.charactersInRoom(room).stream()
                               .map(io.casehub.examples.manor.model.CharacterState::name)
                               .toList();

        io.casehub.neocortex.mindmap.intelligence.ExtractionResult result;
        try {
            result = mindMapExtractor.extract(text, ManorConstants.TENANCY_ID, nearbyNames);
        } catch (Exception e) {
            log.warnf(e, "Dialogue extraction failed for speaker=%s room=%s", speakerId, room);
            return;
        }

        if (result.entities().isEmpty() && result.relationships().isEmpty()) {return;}

        var now = java.time.Instant.now();
        for (var listener : listeners) {
            var confidence = listener.confidenceOrigin() == ConfidenceOrigin.STATED
                             ? io.casehub.neocortex.cognitive.Confidence.stated(0.8, now)
                             : io.casehub.neocortex.cognitive.Confidence.inferred(0.7, now);

            var subgraphId = subgraphIdCache.computeIfAbsent(listener.agentId(), id -> {
                var name = ManorCognitiveSeeder.subgraphName(id);
                var created = mindMapStore.createSubgraph(
                        new io.casehub.neocortex.mindmap.SubgraphInput(name, "cognitive", null),
                        ManorConstants.TENANCY_ID);
                return created != null && !created.isBlank() ? created : name;
            });

            for (var entity : result.entities()) {
                var nodeInput = io.casehub.neocortex.mindmap.NodeInput.of(entity.name(), subgraphId)
                                                                      .withConfidence(confidence)
                                                                      .withProvenance("dialogue-extraction")
                                                                      .withPrincipalId(io.casehub.platform.api.identity.PrincipalId.agent(listener.agentId()));
                if (entity.properties() != null && !entity.properties().isEmpty()) {
                    nodeInput = nodeInput.withProperties(entity.properties());
                }
                if (entity.subgraphType() != null) {
                    nodeInput = nodeInput.withTraits(java.util.Set.of(entity.subgraphType()));
                }
                mindMapStore.addNode(nodeInput, ManorConstants.TENANCY_ID);
            }
        }
    }

    record ListenerInfo(String agentId, ConfidenceOrigin confidenceOrigin) {}

    static List<ListenerInfo> determineListeners(
            String speakerId, String dialogueTargetId, String room,
            boolean isExchange, WorldState world) {
        var listeners = new ArrayList<ListenerInfo>();
        var inRoom    = world.charactersInRoom(room);

        if (isExchange) {
            for (var c : inRoom) {
                if (c.agentId().equals(speakerId) || c.agentId().equals(dialogueTargetId)) {
                    listeners.add(new ListenerInfo(c.agentId(), ConfidenceOrigin.STATED));
                }
            }
        } else if (dialogueTargetId != null) {
            for (var c : inRoom) {
                if (c.agentId().equals(speakerId)) {continue;}
                if (c.agentId().equals(dialogueTargetId)) {
                    listeners.add(new ListenerInfo(c.agentId(), ConfidenceOrigin.STATED));
                } else if (c.capabilityTags().contains("perception")) {
                    listeners.add(new ListenerInfo(c.agentId(), ConfidenceOrigin.INFERRED));
                }
            }
        } else {
            for (var c : inRoom) {
                if (!c.agentId().equals(speakerId)) {
                    listeners.add(new ListenerInfo(c.agentId(), ConfidenceOrigin.STATED));
                }
            }
        }
        return listeners;
    }

    static boolean isExtractableDialogue(String text) {
        if (text == null || text.isBlank()) {return false;}
        String[] words = text.strip().split("\\s+");
        long alphabeticWords = Arrays.stream(words)
                                     .filter(w -> w.replaceAll("[^a-zA-Z]", "").length() >= 2)
                                     .count();
        return text.length() > 10 && alphabeticWords >= 3;
    }

}

