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
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScenarioOrchestrator {

    private static final Logger log = Logger.getLogger(ScenarioOrchestrator.class);

    @Inject
    AgentProvider                               agentProvider;
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


    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.scenario.max-turns", defaultValue = "300")
    int maxTurns;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.observation.verbatim-threshold", defaultValue = "10")
    int verbatimThreshold;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.observation.grouped-threshold", defaultValue = "15")
    int groupedThreshold;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.narrator.enabled", defaultValue = "true")
    boolean narratorEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.narrator.event-threshold", defaultValue = "5")
    int narratorEventThreshold;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.narrator.timer-seconds", defaultValue = "15")
    int narratorTimerSeconds;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.scenario.active-characters", defaultValue = "")
    java.util.Optional<String> activeCharactersConfig;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.agent.max-concurrent", defaultValue = "5")
    int                        maxConcurrentAgents;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.reflection.enabled", defaultValue = "true")
    boolean                    reflectionEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.reflection.max-unreflected", defaultValue = "5")
    int                        maxUnreflected;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.reflection.importance-threshold", defaultValue = "3.0")
    double                     reflectionImportanceThreshold;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.reflection.max-source-memories", defaultValue = "15")
    int                        maxSourceMemories;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.decay.enabled", defaultValue = "true")
    boolean                    decayEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.decay.max-age-days", defaultValue = "7")
    int                        decayMaxAgeDays;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.decay.min-importance", defaultValue = "0.2")
    double                     decayMinImportance;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.memory.recall-limit", defaultValue = "20")
    int                        recallLimit;
    @Inject
    ManorGoalFormationStrategy goalFormationStrategy;
    @Inject
    ManorGoalRevisionStrategy  goalRevisionStrategy;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.goal.enabled", defaultValue = "true")
    boolean                    goalEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.goal.cooldown-ticks", defaultValue = "10")
    int                        goalCooldownTicks;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.goal.max-new-per-reflection", defaultValue = "2")
    int                        goalMaxNewPerReflection;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.plan.enabled", defaultValue = "true")
    boolean                    planEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.plan.revision.max-generation", defaultValue = "5")
    int                        planMaxRevisionGeneration;

    @Inject
    ManorPlanRevisionStrategy  planRevisionStrategy;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.disposition.enabled", defaultValue = "true")
    boolean                    dispositionEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.disposition.evolution-check-interval", defaultValue = "5")
    int                        dispositionEvolutionCheckInterval;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.trust.enabled", defaultValue = "true")
    boolean                    trustEnabled;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.trust.positive-weight", defaultValue = "1.0")
    double                     trustPositiveWeight;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.trust.negative-weight", defaultValue = "-2.0")
    double                     trustNegativeWeight;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.personality.weighted-retrieval", defaultValue = "true")
    boolean                    personalityWeightedRetrieval;

    @Inject
    io.casehub.eidos.api.BehavioralSignalStore  behavioralSignalStore;
    @Inject
    io.casehub.eidos.api.DispositionSignalStore dispositionSignalStore;


    private volatile AgentProvider gatedProvider;



    public Thread startScenario(WorldState world, io.casehub.examples.manor.model.ScenarioMode mode) {
        var triggers         = MansionLoader.loadTriggers();
        var scenes           = MansionLoader.loadScenes();
        var triggerEvaluator = new TriggerEvaluator(triggers);
        var sceneDirector    = new SceneDirector(scenes);
        var actionResolver   = new ActionResolver();

        return Thread.ofVirtual().name("scenario-loop")
                     .start(() -> runScenario(world, triggerEvaluator,
                                              sceneDirector, actionResolver, mode));
    }

    private void runScenario(WorldState world,
                             TriggerEvaluator triggerEvaluator,
                             SceneDirector sceneDirector,
                             ActionResolver actionResolver,
                             io.casehub.examples.manor.model.ScenarioMode mode) {
        manorChannels.initChannels();
        manorChannels.dispatchScenarioStart();
        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("started"));
        webEventBus.broadcast(webEventBus.buildSnapshot(world));

        var compactor          = new MechanicalCompactor();
        var summariser         = new ManorLlmSummariser(agentProvider);
        var obsRenderer        = new ManorObservationRenderer(compactor, verbatimThreshold, groupedThreshold, summariser);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var reflectionSynthesizer = new ManorReflectionSynthesizer(gatedProvider);
        var reflectionTrigger = new ManorReflectionTrigger(maxUnreflected, reflectionImportanceThreshold);
        ManorPlanEvaluator planEvaluator = null;
        if (planEnabled && goalEnabled) {
            var planFormationStrategy = new ManorPlanFormationStrategy(gatedProvider);
            planEvaluator = new ManorPlanEvaluator(planFormationStrategy, planRevisionStrategy,
                caseMemoryStore, ManorConstants.TENANCY_ID,
                agentId -> world.character(agentId), planMaxRevisionGeneration);
        }
        ManorGoalEvaluator goalEvaluator = null;
        if (goalEnabled) {
            goalEvaluator = new ManorGoalEvaluator(goalFormationStrategy, goalRevisionStrategy,
                agentRegistry, caseMemoryStore, ManorConstants.TENANCY_ID,
                goalCooldownTicks, goalMaxNewPerReflection, planEvaluator);
        }
        var experienceService = new AgentExperienceService(experienceRecorder, caseMemoryStore,
            ManorConstants.TENANCY_ID, reflectionSynthesizer, reflectionTrigger,
            reflectionEnabled, decayEnabled, decayMaxAgeDays, decayMinImportance,
            maxSourceMemories, recallLimit, goalEvaluator, planEvaluator);

        ManorTrustProvider trustProvider = null;
        if (trustEnabled) {
            trustProvider = new ManorTrustProvider(trustPositiveWeight, trustNegativeWeight);
        }
        ManorDispositionRecorder dispositionRecorder = null;
        ManorPersonalityEvolution personalityEvolution = null;
        if (dispositionEnabled) {
            dispositionRecorder = new ManorDispositionRecorder(behavioralSignalStore,
                dispositionSignalStore, ManorConstants.TENANCY_ID);
            personalityEvolution = new ManorPersonalityEvolution(dispositionSignalStore,
                ManorConstants.TENANCY_ID, dispositionEvolutionCheckInterval);
        }

        NarratorAgent narratorAgent = null;
        if (narratorEnabled && mode == io.casehub.examples.manor.model.ScenarioMode.AUTONOMOUS) {
            narratorAgent = new NarratorAgent(
                    compactor, agentProvider, manorChannels, webEventBus,
                    narratorEventThreshold, narratorTimerSeconds);
            narratorAgent.start(world);
        }

        var dispatcher = new ManorEventDispatcher(
                world, observationService, narratorAgent,
                manorChannels, webEventBus);

        var activeSet = activeCharactersConfig
                .filter(s -> !s.isBlank())
                .map(s -> java.util.Set.copyOf(java.util.Arrays.asList(s.split(","))))
                .orElse(null);

        for (var entry : world.characters().entrySet()) {
            if (activeSet != null && !activeSet.contains(entry.getKey())) {continue;}
            var desc = agentRegistry.findById(entry.getKey(), ManorConstants.TENANCY_ID)
                    .orElseThrow(() -> new IllegalStateException("No Eidos descriptor for character: " + entry.getKey()));
            var tags = desc.capabilities().stream()
                    .flatMap(c -> c.tags().stream())
                    .collect(java.util.stream.Collectors.toSet());
            entry.getValue().setCapabilityTags(tags);
        }

        var invocationService = new AgentInvocationService(agentProvider, 60, 2, 2000);

        if (mode == io.casehub.examples.manor.model.ScenarioMode.AUTONOMOUS) {
            runAutonomousTicks(world, activeSet, actionResolver, dispatcher, invocationService, narratorAgent, experienceService, planEvaluator, trustProvider, dispositionRecorder, personalityEvolution);
        } else {
            runScripted(world, activeSet, actionResolver, dispatcher, invocationService,
                        triggerEvaluator, sceneDirector, narratorAgent);
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
                                     AgentInvocationService invocationService, NarratorAgent narratorAgent,
                                     AgentExperienceService experienceService, ManorPlanEvaluator planEvaluator,
                                     ManorTrustProvider trustProvider,
                                     ManorDispositionRecorder dispositionRecorder,
                                     ManorPersonalityEvolution personalityEvolution) {
        var activeAgents = world.characters().values().stream()
                .filter(c -> activeSet == null || activeSet.contains(c.agentId()))
                .toList();

        int tick = 0;
        while (!world.isScenarioComplete()) {
            tick++;

            while (world.isPaused() && !world.isScenarioComplete()) {
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
            if (world.isScenarioComplete()) break;

            int currentTick = tick;
            var actingThisTick = activeAgents.stream()
                    .filter(io.casehub.examples.manor.model.CharacterState::isActive)
                    .filter(c -> currentTick % cadence(c) == 0)
                    .toList();
            if (actingThisTick.isEmpty()) continue;

            var responses = new java.util.concurrent.ConcurrentHashMap<String, AgentResponse>();
            var latch = new java.util.concurrent.CountDownLatch(actingThisTick.size());
            for (var c : actingThisTick) {
                Thread.ofVirtual().name(c.agentId() + "-tick-" + currentTick).start(() -> {
                    try {
                        var drain = dispatcher.observationService().drain(c.agentId(), System.currentTimeMillis());
                        var reflections = experienceService.recallReflections(c.agentId(), 5);
                        var relationships = new java.util.HashMap<String, java.util.List<io.casehub.neocortex.memory.Memory>>();
                        for (var other : world.charactersInRoom(c.currentRoom())) {
                            if (!other.agentId().equals(c.agentId())) {
                                var relMems = experienceService.recallRelationships(c.agentId(), other.agentId(), 3);
                                if (!relMems.isEmpty()) {
                                    relationships.put(other.name(), relMems);
                                }
                            }
                        }
                        var memories = experienceService.recall(c.agentId(), recallLimit);
                        if (personalityWeightedRetrieval && !memories.isEmpty()) {
                            memories = io.casehub.neocortex.memory.personality.PersonalityWeightedRetrieval
                                .reweight(memories, new io.casehub.neocortex.memory.personality.PersonalityWeights(
                                    java.util.Map.of(new io.casehub.neocortex.memory.MemoryDomain("manor"), 1.0)), java.time.Instant.now());
                        }
                        var worldProvider = new ManorWorldObservationProvider(c, world, drain);
                        String observation = ObservationBuilder.buildObservation(
                                worldProvider, c, resolveGoals(c.agentId()), drain,
                                memories, reflections, relationships);
                        String userPrompt = observation + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
                        String systemPrompt = renderPrompt(c.agentId());
                        responses.put(c.agentId(), invocationService.invoke(systemPrompt, userPrompt, c.agentId()));
                    } catch (Exception e) {
                        log.errorf(e, "%s: tick %d error", c.agentId(), currentTick);
                        responses.put(c.agentId(), AgentResponse.idle());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try { latch.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            log.infof("Tick %d complete (%d agents)", currentTick, actingThisTick.size());

            var suppressed = new java.util.HashSet<String>();
            var exchangeRunner = new ExchangeRunner(3, 120_000);
            for (var c : actingThisTick) {
                var response = responses.get(c.agentId());
                if (response == null) continue;
                if (response.action() != null && response.action().type() == io.casehub.examples.manor.model.ActionType.PULL_ASIDE) {
                    String targetId = response.action().target();
                    var target = world.character(targetId);
                    if (target == null || !target.isActive() || !target.currentRoom().equals(c.currentRoom()) || suppressed.contains(c.agentId()) || suppressed.contains(targetId)) {
                        c.setLastActionResult("Could not pull " + targetId + " aside.");
                    } else {
                        suppressed.add(c.agentId());
                        suppressed.add(targetId);
                        var exchangeEvents = exchangeRunner.run(c, target, response.dialogue(), world, invocationService, this::renderPrompt);
                        for (var event : exchangeEvents) {
                            dispatcher.publishDialogue(event, "");
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
                        var event = new io.casehub.examples.manor.model.ManorEvent(
                                java.time.Instant.now(), "dialogue", c.agentId(), c.currentRoom(),
                                narr.publicText(), null, null, null, null, narr.detailedText(), false, validatedTalkTo);
                        dispatcher.publishDialogue(event, response.dialogue());
                    } else {
                        var event = new io.casehub.examples.manor.model.ManorEvent(
                                java.time.Instant.now(), "dialogue", c.agentId(),
                                c.currentRoom(), c.name() + ": " + response.dialogue());
                        dispatcher.publishDialogue(event, response.dialogue());
                    }
                }
                if (response.aside() != null) {
                    var event = new io.casehub.examples.manor.model.ManorEvent(
                            java.time.Instant.now(), "aside", c.agentId(),
                            c.currentRoom(), response.aside());
                    dispatcher.publishAside(event, response.aside());
                }
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
                        var enrichedEvent = new io.casehub.examples.manor.model.ManorEvent(
                                java.time.Instant.now(), "action", c.agentId(), c.currentRoom(),
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
                    if (dispositionRecorder != null) {
                        dispositionRecorder.record(c.agentId(), response.action().type(), result);
                    }
                    if (trustProvider != null) {
                        String target = extractTargetAgent(response);
                        if (target != null) {
                            var actionType = response.action().type();
                            if (actionType == io.casehub.examples.manor.model.ActionType.STEAL) {
                                trustProvider.recordNegative(c.agentId());
                            } else if (actionType == io.casehub.examples.manor.model.ActionType.GIVE) {
                                trustProvider.recordPositive(c.agentId());
                            }
                        }
                    }
                } else {
                    c.setLastActionResult("You waited and observed.");
                }
                if (response.thinking() != null) {
                    c.setCurrentThinking(response.thinking());
                }
                double importance = importanceForAction(response);
                String targetAgentId = extractTargetAgent(response);
                String desc = (response.dialogue() != null ? response.dialogue() + " " : "")
                              + (response.action() != null ? response.action().type() + " " + response.action().target() : "WAIT");
                experienceService.ingest(c.agentId(), c.currentRoom(),
                    desc.strip(), response.thinking(), importance, targetAgentId, currentTick);
                if (personalityEvolution != null) {
                    personalityEvolution.checkAndEvolve(c.agentId(), currentTick);
                }
            }

            if (tick >= maxTurns) {
                world.setScenarioComplete(io.casehub.examples.manor.model.CompletionReason.DAWN);
            }

            webEventBus.broadcast(webEventBus.buildSnapshot(world));
            log.infof("Tick %d: %d agents acted", tick, actingThisTick.size());
        }
    }

    private void runScripted(WorldState world, java.util.Set<String> activeSet,
                              ActionResolver actionResolver, ManorEventDispatcher dispatcher,
                              AgentInvocationService invocationService,
                              TriggerEvaluator triggerEvaluator, SceneDirector sceneDirector,
                              NarratorAgent narratorAgent) {
        var actionQueue = new LinkedBlockingQueue<PendingAction>();
        var threads = world.characters().values().stream()
                .filter(c -> activeSet == null || activeSet.contains(c.agentId()))
                .map(c -> {
                    var goals = resolveGoals(c.agentId());
                    return Thread.ofVirtual().name(c.agentId())
                            .uncaughtExceptionHandler((t, e) -> {
                                log.errorf(e, "Character %s crashed", t.getName());
                                world.markCharacterInactive(t.getName());
                            })
                            .start(() -> {
                                String systemPrompt = renderPrompt(c.agentId());
                                new CharacterAgentLoop().run(
                                        c, world, invocationService, null,
                                        systemPrompt, actionQueue, dispatcher, goals);
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
                    var enrichedEvent = new io.casehub.examples.manor.model.ManorEvent(
                            java.time.Instant.now(), "action", pending.character().agentId(),
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

    private static double importanceForAction(AgentResponse response) {
        if (response.action() == null) {return 0.5;}
        return switch (response.action().type()) {
            case STEAL -> 0.9;
            case USE -> 0.8;
            case TAKE, GIVE, PULL_ASIDE -> 0.7;
            case INTERACT -> 0.6;
            case MOVE -> 0.3;
            case LOOK -> 0.2;
            case WAIT -> 0.1;
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

}

