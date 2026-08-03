package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.Compactor;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.SummarisationRunner;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.examples.manor.web.ManorEventBus;
import io.casehub.examples.manor.web.ManorWebSocketEvent;
import io.casehub.platform.agent.AgentProvider;
import org.jboss.logging.Logger;

public final class NarratorAgent {

    private static final Logger     log            = Logger.getLogger(NarratorAgent.class);
    static final         EventLevel NARRATOR_LEVEL = new EventLevel("narrator", 0);

    private final    SummarisationRunner<ManorEvent, String> runner;
    private final    EventStreamBus<String>                  outputBus;
    private volatile boolean                                 stopped;
    private          Thread                                  narratorThread;

    public NarratorAgent(Compactor<ManorEvent> compactor,
                         AgentProvider agentProvider,
                         ManorChannels manorChannels,
                         ManorEventBus webEventBus,
                         int eventThreshold,
                         int timerSeconds) {
        this.outputBus = new EventStreamBus<>();
        if (manorChannels != null || webEventBus != null) {
            outputBus.subscribe(s -> true, event -> {
                if (manorChannels != null) {
                    try {manorChannels.dispatchNarration(event.payload());} catch (Exception e) {
                        log.warn("Qhorus narration dispatch failed", e);
                    }
                }
                if (webEventBus != null) {
                    try {webEventBus.broadcast(ManorWebSocketEvent.narrator(event.payload()));} catch (Exception e) {
                        log.warn("WebSocket narration dispatch failed", e);
                    }
                }
            });
        }
        var policy     = WindowPolicy.of(timerSeconds * 1000L, eventThreshold);
        var summariser = new NarratorSummariser(agentProvider);
        this.runner = new SummarisationRunner<>(policy, compactor, summariser,
                                                outputBus, NARRATOR_LEVEL,
                                                batch -> log.warnf("Narrator summarisation failed, batch size=%d", batch.size()));
    }

    public void collect(ManorEvent event) {
        runner.collect(new LevelEvent<>(event, event.timestamp().toEpochMilli(), NARRATOR_LEVEL));
    }

    public void start(WorldState world) {
        this.stopped        = false;
        this.narratorThread = Thread.ofVirtual().name("narrator-loop")
                                    .start(() -> runLoop(world));
    }

    public void stop() {
        this.stopped = true;
        if (narratorThread != null) {
            narratorThread.interrupt();
        }
    }

    public Thread thread() {
        return narratorThread;
    }

    private void runLoop(WorldState world) {
        while (!stopped && !world.isScenarioComplete()) {
            try {
                runner.tick(System.currentTimeMillis())
                      .toCompletableFuture()
                      .orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                      .exceptionally(ex -> {
                          log.warn("Narrator tick timed out");
                          return null;
                      })
                      .join();
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        runner.flush()
              .toCompletableFuture()
              .orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
              .exceptionally(ex -> {
                  log.warn("Narrator flush timed out");
                  return null;
              })
              .join();
    }

    void testSubscribe(java.util.function.Consumer<String> callback) {
        outputBus.subscribe(s -> true, event -> callback.accept(event.payload()));
    }

    void tickNow() {
        runner.tick(System.currentTimeMillis() + 60_000)
              .toCompletableFuture().join();
    }

    void tickAt(long timestamp) {
        runner.tick(timestamp).toCompletableFuture().join();
    }

    void flushNow() {
        runner.flush().toCompletableFuture().join();
    }
}
