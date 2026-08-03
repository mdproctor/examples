package io.casehub.examples.manor.agent;

import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentRegistry;
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

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "manor.scenario.max-turns", defaultValue = "60")
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

        var actionQueue = new LinkedBlockingQueue<PendingAction>();
        int turnCount   = 0;

        var threads = world.characters().values().stream()
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
                                                        c, world, agentProvider, systemPrompt, actionQueue,
                                                        dispatcher, goals);
                                            });
                           })
                           .toList();

        while (!world.isScenarioComplete()) {
            try {
                PendingAction pending = actionQueue.poll(5, TimeUnit.SECONDS);
                if (pending == null) {continue;}

                String departureRoom = pending.character().currentRoom();
                ActionResult result = actionResolver.resolve(
                        pending.character(), pending.action(), world);

                String narrative = NarrativeEventBuilder.describe(
                        pending.character(), pending.action(), result);
                if (narrative != null) {
                    var enrichedEvent = new io.casehub.examples.manor.model.ManorEvent(
                            java.time.Instant.now(), "action",
                            pending.character().agentId(), pending.character().currentRoom(),
                            narrative,
                            pending.action().type(), pending.action().target(),
                            pending.action().withItem(),
                            pending.action().type() == io.casehub.examples.manor.model.ActionType.MOVE ? departureRoom : null);
                    dispatcher.publishAction(enrichedEvent, result, pending.character().x());
                }

                pending.character().setLastActionResult(result.text());

                if (mode == io.casehub.examples.manor.model.ScenarioMode.SCRIPTED) {
                    var triggerResult = triggerEvaluator.evaluate(world);

                    for (String narratorText : triggerResult.narratorEvents()) {
                        world.addEvent("narrator", null, null, narratorText);
                        manorChannels.dispatchNarration(narratorText);
                        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(narratorText));
                    }

                    if (triggerResult.hasSceneStart()) {
                        manorChannels.dispatchSceneEvent(triggerResult.sceneId(), "started");
                        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scene(triggerResult.sceneId(), "started"));
                        sceneDirector.runScene(
                                triggerResult.sceneId(), world,
                                this::callAgentForScene,
                                narration -> {
                                    world.addEvent("narrator", null, null, narration);
                                    manorChannels.dispatchNarration(narration);
                                    webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.narrator(narration));
                                });
                        manorChannels.dispatchSceneEvent(triggerResult.sceneId(), "ended");
                        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scene(triggerResult.sceneId(), "ended"));
                    }
                }

                if (mode == io.casehub.examples.manor.model.ScenarioMode.AUTONOMOUS) {
                    turnCount++;
                    if (world.hasEffect("tea-service", "rat-poison")) {
                        world.setScenarioComplete(io.casehub.examples.manor.model.CompletionReason.POISONED);
                    } else if (turnCount >= maxTurns) {
                        world.setScenarioComplete(io.casehub.examples.manor.model.CompletionReason.TURN_LIMIT);
                    }
                }

                pending.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        String reason = world.completionReason() != null ? world.completionReason().name().toLowerCase() : null;
        manorChannels.dispatchScenarioComplete();
        webEventBus.broadcast(io.casehub.examples.manor.web.ManorWebSocketEvent.scenario("completed", reason));

        for (var t : threads) {
            try {
                t.join(Duration.ofSeconds(5));
                if (t.isAlive()) {
                    log.warnf("Character %s did not terminate", t.getName());
                    t.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

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

        log.info("Scenario complete" + (reason != null ? " — " + reason : ""));}

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
        var ctx = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);
        return renderer.render(desc, ctx).content();
    }
}
