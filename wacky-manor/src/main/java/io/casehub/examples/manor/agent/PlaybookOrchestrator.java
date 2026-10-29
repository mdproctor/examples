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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    ManorNeedSatisfactionConfigProvider                   needSatisfactionConfigProvider;
    @Inject
    io.casehub.neocortex.memory.seeding.biography.BiographyImportRunner biographyImportRunner;

    @Inject
    io.casehub.platform.agent.session.SessionPoolRegistry sessionPoolRegistry;
    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.platform.agent.claude.max-concurrent-sessions", defaultValue = "12")
    int maxConcurrentSessions;

    private volatile AgentProvider gatedProvider;
    private volatile io.casehub.platform.agent.session.SessionPipeline scenePipeline;
    private final    java.util.Map<String, String> subgraphIdCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.Map<String, String> idReverseMap    = java.util.Map.of();

    private String registryId(String agentId) {
        return idReverseMap.getOrDefault(agentId, agentId);
    }


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

        var sessionLifecycleManager = new io.casehub.platform.agent.session.SessionLifecycleManager(sessionPoolRegistry);
        io.casehub.platform.agent.session.SessionPipeline sessionPipeline;
        if (mode == io.casehub.examples.manor.model.PlaybookMode.AUTONOMOUS) {
            var admission = new io.casehub.platform.agent.gate.AimdAdmissionStrategy(
                    maxConcurrentSessions, 5, java.time.Duration.ofSeconds(10));
            sessionPipeline = new io.casehub.platform.agent.session.SessionPipeline(
                    sessionLifecycleManager, "claude", admission, config.session().clearAtTokens());
        } else {
            sessionPipeline = new io.casehub.platform.agent.session.SessionPipeline(
                    sessionLifecycleManager, "claude", maxConcurrentSessions, config.session().clearAtTokens());
        }
        this.scenePipeline = sessionPipeline;

        var compactor          = new MechanicalCompactor();
        var summariser         = new ManorLlmSummariser(sessionPipeline);
        var obsRenderer        = new ManorObservationRenderer(compactor, config.observation().verbatimThreshold(), config.observation().groupedThreshold(), summariser);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var reflectionSynthesizer = new ManorReflectionSynthesizer(sessionPipeline);
        var reflectionTrigger = new ManorReflectionTrigger(config.reflection().maxUnreflected(), config.reflection().importanceThreshold());
        ManorPlanEvaluator planEvaluator = null;
        if (config.plan().enabled() && config.goal().enabled()) {
            var planFormationStrategy = new ManorPlanFormationStrategy(sessionPipeline);
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

        NarratorAgent narratorAgent = null;

        var dispatcher = new ManorEventDispatcher(
                world, observationService, narratorAgent,
                manorChannels, webEventBus);

        var activeSet = config.activeCharacters().isBlank() ? null
                : java.util.Set.copyOf(java.util.Arrays.asList(config.activeCharacters().split(",")));

        var socialConfigs = ManorSocialConfigLoader.loadForProfile(profileMode.name());

        var reverseIdMap = new java.util.HashMap<String, String>();
        if (profileMode == io.casehub.examples.manor.model.ProfileMode.GENERIC) {
            var idMap = new java.util.HashMap<String, String>();
            for (var entry : world.characters().entrySet()) {
                agentRegistry.findById(entry.getKey(), ManorConstants.TENANCY_ID).ifPresent(desc -> {
                    if (desc.name() != null && !desc.name().isBlank()) {
                        String genericId = WorldState.slug(desc.name());
                        if (!genericId.equals(entry.getKey())) {
                            idMap.put(entry.getKey(), genericId);
                            reverseIdMap.put(genericId, entry.getKey());
                        }
                        entry.getValue().setName(desc.name());
                    }
                });
            }
            world.remapCharacterIds(idMap);
            var remapped = new java.util.HashMap<String, SocialConfig>();
            for (var entry : socialConfigs.entrySet()) {
                String newKey = idMap.getOrDefault(entry.getKey(), entry.getKey());
                remapped.put(newKey, entry.getValue().remapIds(idMap));
            }
            socialConfigs = remapped;
            if (activeSet != null) {
                activeSet = activeSet.stream()
                    .map(id -> idMap.getOrDefault(id, id))
                    .collect(java.util.stream.Collectors.toSet());
            }
            this.idReverseMap = java.util.Map.copyOf(reverseIdMap);
            log.infof("GENERIC ID rewrite: %d characters remapped", idMap.size());
        } else {
            for (var entry : world.characters().entrySet()) {
                agentRegistry.findById(entry.getKey(), ManorConstants.TENANCY_ID).ifPresent(desc -> {
                    if (desc.name() != null && !desc.name().isBlank()) {
                        entry.getValue().setName(desc.name());
                    }
                });
            }
        }

        for (var entry : world.characters().entrySet()) {
            if (activeSet != null && !activeSet.contains(entry.getKey())) {
                entry.getValue().setActive(false);
            }
        }

        var dispositions = new java.util.HashMap<String, SocialConfig.Disposition>();
        for (var entry : socialConfigs.entrySet()) {
            if (entry.getValue().disposition() != null) {
                dispositions.put(entry.getKey(), entry.getValue().disposition());
            }
        }
        needSatisfactionConfigProvider.setDispositions(dispositions);

        if (significanceAccumulator.isResolvable()) {
            significanceAccumulator.get().suspend();
        }

        var cacheDir = java.nio.file.Path.of(System.getProperty("user.home"), "claude", "casehub", "consolidation-cache");
        boolean cacheRestored = false;
        if (config.consolidation().enabled() && mmStore != null) {
            cacheRestored = ConsolidationCache.restore(mmStore, ManorConstants.TENANCY_ID, profileMode.name(), cacheDir);
        }

        var activeSocialConfigs = new java.util.HashMap<String, SocialConfig>();
        for (var entry : world.characters().entrySet()) {
            if (!entry.getValue().isActive()) continue;
            String registryId = reverseIdMap.getOrDefault(entry.getKey(), entry.getKey());
            var desc = agentRegistry.findById(registryId, ManorConstants.TENANCY_ID)
                    .orElseThrow(() -> new IllegalStateException("No Eidos descriptor for character: " + entry.getKey()));
            var tags = desc.capabilities().stream()
                    .flatMap(c -> c.tags().stream())
                    .collect(java.util.stream.Collectors.toSet());
            entry.getValue().setCapabilityTags(tags);
            activeSocialConfigs.put(entry.getKey(), socialConfigs.getOrDefault(entry.getKey(), SocialConfig.empty()));
        }

        var pipeline = ManorCognitivePipelineBuilder
                .create(mmStore, ManorConstants.TENANCY_ID, cbrRecordStore)
                .sessionPipeline(sessionPipeline)
                .config(config)
                .capsEngine(capsEngine)
                .experienceRecorder(experienceRecorder)
                .agentRegistry(agentRegistry)
                .experienceService(experienceService)
                .cognitiveProfile(cogProfile)
                .situationClassifier(situationClassifier)
                .socialConfigs(activeSocialConfigs)
                .seedDrives(!cacheRestored)
                .seedPeople(false)
                .briefingMode(config.briefingMode())
                .build();

        var cognitionCore = pipeline.cognitionCore();
        var cognitions = pipeline.cognitions();

        if (!cacheRestored && mmStore != null) {
            new ManorCognitiveSeeder(mmStore).seedPeople(socialConfigs, ManorConstants.TENANCY_ID);

            var biographyRoot = java.nio.file.Path.of("src/main/resources/META-INF/eidos/biographies");
            if (java.nio.file.Files.isDirectory(biographyRoot)) {
                var loader = new io.casehub.neocortex.memory.seeding.biography.BiographyLoader();
                for (var charEntry : activeSocialConfigs.keySet()) {
                    var charBioDir = biographyRoot.resolve(charEntry);
                    if (java.nio.file.Files.isDirectory(charBioDir)) {
                        var bioProfile = loader.loadAll(charBioDir, charEntry, ManorConstants.TENANCY_ID);
                        biographyImportRunner.run(bioProfile, charEntry, ManorConstants.TENANCY_ID);
                        log.infof("Biography import: %s — %d personality profiles, %d life events, %d goals",
                            charEntry, bioProfile.personalityProfiles().size(),
                            bioProfile.lifeEvents().size(), bioProfile.goals().size());
                    }
                }
            }
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

        try {
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
            } else {
                var invocationService = new AgentInvocationService(sessionLifecycleManager, "claude", 60, 2, 2000);
                runScripted(world, activeSet, actionResolver, dispatcher, invocationService,
                            triggerEvaluator, sceneDirector, narratorAgent, cognitions);
            }
        } finally {
            sessionPipeline.shutdown();
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
        try {
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

            log.infof("Tick %d start — sessions: %d, AIMD window: %d, available: %d",
                    currentTick, sessionPipeline.sessionCount(),
                    sessionPipeline.admission().currentWindow(), sessionPipeline.availablePermits());

            long cogStart = System.currentTimeMillis();
            for (var c : activeAgents) {
                if (!c.isActive()) continue;
                var desc0 = agentRegistry.findById(registryId(c.agentId()), ManorConstants.TENANCY_ID).orElse(null);
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
            var preCogSections = new java.util.HashMap<String, java.util.List<io.casehub.agency.perception.observation.affordance.ObservationSection>>();
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
                var obsPipeline = new io.casehub.agency.perception.observation.affordance.ObservationPipeline(new io.casehub.agency.perception.observation.affordance.PerceptionFilter());
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
            int aimdWindowPre = sessionPipeline.admission().currentWindow();
            int aimdAvailPre = sessionPipeline.availablePermits();
            long queryStart = System.nanoTime();
            var pipelineResults = TickExecutor.queryPipeline(prompts, sessionPipeline, 3, java.time.Duration.ofSeconds(120));
            long queryMs = (System.nanoTime() - queryStart) / 1_000_000;
            int aimdWindowPost = sessionPipeline.admission().currentWindow();

            // Phase 3: parse responses (outside pipeline — parse errors don't consume tickets)
            long succeeded = pipelineResults.values().stream().filter(java.util.Objects::nonNull).count();
            log.infof("Phase: query %dms — %d agents, %d succeeded — AIMD window %d→%d (avail %d pre-query)",
                    queryMs, actingThisTick.size(), succeeded,
                    aimdWindowPre, aimdWindowPost, aimdAvailPre);
            if (succeeded == 0 && !actingThisTick.isEmpty()) {
                log.warnf("Tick %d: all %d queries failed — API likely unreachable, auto-pausing", currentTick, actingThisTick.size());
                world.setPaused(true);
                webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("paused"));
                while (world.isPaused() && !world.isScenarioComplete()) {
                    try { Thread.sleep(30_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    try {
                        var healthResult = sessionPipeline.executeSingle(actingThisTick.getFirst().agentId(),
                                session -> AgentInvocationService.querySessionRaw(
                                        (io.casehub.platform.agent.session.ManagedSession) session, "ping", "health"),
                                java.time.Duration.ofSeconds(15));
                        if (healthResult != null) {
                            log.info("API recovered — auto-resuming");
                            world.setPaused(false);
                            webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("resumed"));
                        } else {
                            log.info("API still unreachable — waiting 30s");
                        }
                    } catch (Exception e) {
                        log.infof("Health check failed: %s — waiting 30s", e.getMessage());
                    }
                }
                continue;
            }
            var responses = TickExecutor.parseResponses(actingThisTick, pipelineResults);
            for (var c : actingThisTick) {
                String prompt = prompts.get(c.agentId());
                String result = pipelineResults.get(c.agentId());
                int promptChars = prompt != null ? prompt.length() : 0;
                int resultChars = result != null ? result.length() : 0;
                log.infof("  %s: prompt %d chars (~%d tokens), response %d chars (~%d tokens)",
                        c.agentId(), promptChars, promptChars / 4, resultChars, resultChars / 4);
            }

            var suppressed = new java.util.HashSet<String>();
            var exchangeTexts = new ArrayList<String[]>();
            var exchangeRunner = new ExchangeRunner(3, 120_000);
            for (var c : actingThisTick) {
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.action() != null && response.action().type() == io.casehub.examples.manor.model.ActionType.PULL_ASIDE) {
                    var target = world.resolveCharacter(response.action().target());
                    String targetId = target != null ? target.agentId() : response.action().target();
                    if (target == null || !target.isActive() || !target.currentRoom().equals(c.currentRoom()) || suppressed.contains(c.agentId()) || suppressed.contains(targetId) || agentRegistry.findById(registryId(targetId), ManorConstants.TENANCY_ID).isEmpty()) {
                        c.setLastActionResult("Could not pull " + (target != null ? target.name() : targetId) + " aside.");
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
                        var talkTarget = world.resolveCharacter(validatedTalkTo);
                        if (talkTarget == null || !talkTarget.currentRoom().equals(c.currentRoom())) {
                            validatedTalkTo = null;
                        } else {
                            validatedTalkTo = talkTarget.agentId();
                        }
                    }
                    if (validatedTalkTo != null) {
                        var talkTargetName = world.character(validatedTalkTo).name();
                        var narr = NarrativeEventBuilder.describeDirectedDialogue(c.name(), talkTargetName, response.dialogue());
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

            // Phase 3b: extract inline mood from character responses
            var moodSignals = new java.util.HashMap<String, io.casehub.neocortex.cognition.mood.MoodSignal>();
            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null || response.dialogue() == null || response.moodImpact() == null) continue;
                var mi = response.moodImpact();
                double p = Math.clamp(mi.pleasure(), -0.3, 0.3);
                double a = Math.clamp(mi.arousal(), -0.3, 0.3);
                double d = Math.clamp(mi.dominance(), -0.3, 0.3);
                String cause = mi.cause() != null ? mi.cause() : "interaction";
                moodSignals.put(c.agentId(),
                        new io.casehub.neocortex.cognition.mood.MoodSignal.InteractionAppraisal(p, a, d, cause));
                log.infof("%s: inline-mood P=%.3f A=%.3f D=%.3f cause=%s", c.agentId(), p, a, d, cause);
            }

            long interactionStart = System.nanoTime();
            int interactionCount = 0;
            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null || response.dialogue() == null) continue;
                String dialogueText = response.dialogue();
                String validatedTarget = response.talkTo();
                if (validatedTarget != null) {
                    var tgt = world.resolveCharacter(validatedTarget);
                    if (tgt == null || !tgt.currentRoom().equals(c.currentRoom())) {
                        validatedTarget = null;
                    } else {
                        validatedTarget = tgt.agentId();
                    }
                }
                extractDialogueKnowledge(dialogueText, c.agentId(), validatedTarget,
                        c.currentRoom(), false, world);
                var observation = prompts.get(c.agentId());
                var moodSignal = moodSignals.get(c.agentId());
                var impact = new io.casehub.neocortex.cognition.core.CognitiveImpact(
                        null, moodSignal, true, null, null);
                cognitionCore.recordInteraction(c.agentId(), ManorConstants.TENANCY_ID,
                        validatedTarget, observation != null ? observation : "", dialogueText, impact);
                if (moodSignal instanceof io.casehub.neocortex.cognition.mood.MoodSignal.InteractionAppraisal ia) {
                    cognitionCore.seedMoodFallback(c.agentId(), ia.pleasureDelta(), ia.arousalDelta(), ia.dominanceDelta());
                }
                interactionCount++;
            }
            log.infof("Phase: recordInteraction — %d agents in %dms",
                    interactionCount, (System.nanoTime() - interactionStart) / 1_000_000);

            // Deferred BDI extraction — off critical path, runs during next tick's query phase
            final var bdiActing = List.copyOf(actingThisTick);
            final var bdiResponses = responses;
            final var bdiSuppressed = suppressed;
            Thread.ofVirtual().name("deferred-bdi-tick-" + currentTick).start(() -> {
                int bdiCount = 0;
                for (var c : bdiActing) {
                    if (bdiSuppressed.contains(c.agentId())) continue;
                    var response = bdiResponses.get(c.agentId());
                    if (response == null || response.dialogue() == null) continue;
                    String target = response.talkTo();
                    if (target != null) {
                        cognitionCore.deferredBdiExtraction(
                                c.agentId(), target, ManorConstants.TENANCY_ID, response.dialogue());
                        bdiCount++;
                    }
                }
                if (bdiCount > 0) {
                    log.infof("Deferred BDI: %d extractions completed (tick %d)", bdiCount, currentTick);
                }
            });

            long actionStart = System.nanoTime();
            for (var c : actingThisTick) {
                if (suppressed.contains(c.agentId())) continue;
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.action() != null && response.action().type() != io.casehub.examples.manor.model.ActionType.WAIT) {
                    String departureRoom = c.currentRoom();
                    var result = actionResolver.resolve(c, response.action(), world);
                    var narrativeAction = resolveNarrativeAction(response.action(), world);
                    var narration = NarrativeEventBuilder.describeRich(c, narrativeAction, result);
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
                    String trustTarget = resolveTargetAgent(response, world);
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
                    c.setLastActionResult("You stood still, watching.");
                }
                if (response.thinking() != null) {
                    c.setCurrentThinking(response.thinking());
                }
                var cognition = cognitions.get(c.agentId());
                double importance = cognition.computeImportance(response.action() != null ? response.action().type() : null);
                String targetAgentId = resolveTargetAgent(response, world);
                String actionTargetName = response.action() != null && response.action().target() != null
                        ? resolveDisplayName(response.action().target(), world) : null;
                String desc = (response.dialogue() != null ? response.dialogue() + " " : "")
                              + (response.action() != null ? response.action().type().verb() + " " + actionTargetName : "waited");
                cognition.recordExperience(c.currentRoom(), desc.strip(), response.thinking(), importance, targetAgentId, currentTick);
            }

            log.infof("Phase: action-resolution %dms", (System.nanoTime() - actionStart) / 1_000_000);

            long tomStart = System.nanoTime();
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
                        sb.append(c.name()).append(" ").append(response.action().type().verb());
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
                        agentRegistry.findById(registryId(observer.agentId()), ManorConstants.TENANCY_ID).ifPresent(observerDesc -> {
                            var levelEvent = new io.casehub.neocortex.summarisation.LevelEvent<>(
                                    observationText, System.currentTimeMillis(),
                                    new io.casehub.neocortex.summarisation.EventLevel("SOCIAL", 1),
                                    ManorConstants.TENANCY_ID);
                            innerLife.observe(levelEvent, observerDesc);
                        });
                    }
                }
            }

            log.infof("Phase: tom-observations %dms", (System.nanoTime() - tomStart) / 1_000_000);

            long classStart = System.nanoTime();
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
                            // External mode: classify immediately via pipeline session (lazy session open)
                            sessionPipeline.openSession("classify:" + c.agentId(),
                                    io.casehub.platform.agent.AgentSessionInit.of(pipelineClassifier.systemPrompt()));
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
            log.infof("Phase: classification %dms (%d classified)", (System.nanoTime() - classStart) / 1_000_000, classified);

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
            var metrics = cognitionCore.tickMetrics();
            log.infof("Tick %d: %d agents acted — sessions: %d, mood %d/%d (%.0f%% LLM), bdi %d/%d (%.0f%% LLM)",
                    tick, actingThisTick.size(), sessionPipeline.sessionCount(),
                    metrics.moodSuccess(), metrics.moodTotal(), metrics.moodSuccessRate() * 100,
                    metrics.bdiSuccess(), metrics.bdiTotal(), metrics.bdiSuccessRate() * 100);
        }
        } finally {
            if (snapshotRecorder != null) {
                snapshotRecorder.close();
            }
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
        return agentRegistry.findById(registryId(agentId), ManorConstants.TENANCY_ID)
                            .map(desc -> desc.goals())
                            .orElse(java.util.List.of());
    }

    private String callAgentForScene(String characterId, String prompt) {
        String systemPrompt = renderPrompt(characterId);
        try {
            var session = scenePipeline.openSession("scene:" + characterId,
                    io.casehub.platform.agent.AgentSessionInit.of(systemPrompt));
            var responseBuilder = new StringBuilder();
            session.query(prompt).subscribe().asStream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .forEach(responseBuilder::append);
            return responseBuilder.toString();
        } catch (Exception e) {
            log.warnf("Scene LLM call failed for %s: %s", characterId, e.getMessage());
            return "[" + characterId + " is speechless]";
        }
    }

    private String renderPrompt(String agentId) {
        var desc = agentRegistry.findById(registryId(agentId), ManorConstants.TENANCY_ID)
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


    private static String resolveTargetAgent(AgentResponse response, WorldState world) {
        String raw = extractTargetAgent(response);
        if (raw == null) {return null;}
        var resolved = world.resolveCharacter(raw);
        return resolved != null ? resolved.agentId() : raw;
    }

    private static String resolveDisplayName(String ref, WorldState world) {
        var resolved = world.resolveCharacter(ref);
        return resolved != null ? resolved.name() : ref;
    }

    private static io.casehub.examples.manor.model.Action resolveNarrativeAction(
            io.casehub.examples.manor.model.Action action, WorldState world) {
        if (action.target() == null) {return action;}
        return switch (action.type()) {
            case GIVE, STEAL, PULL_ASIDE -> new io.casehub.examples.manor.model.Action(
                    action.type(), resolveDisplayName(action.target(), world), action.withItem());
            default -> action;
        };
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

