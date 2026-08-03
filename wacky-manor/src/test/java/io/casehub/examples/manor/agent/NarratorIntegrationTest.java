package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.MansionLoader;
import io.casehub.examples.manor.model.ScenarioMode;
import io.casehub.examples.manor.web.ManorEventBus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("llm-eval")
class NarratorIntegrationTest {

    @Inject
    ScenarioOrchestrator orchestrator;
    @Inject
    ManorEventBus        webEventBus;

    @Test
    void narrator_generates_commentary_in_autonomous_mode() throws Exception {
        var narratorEvents = new CopyOnWriteArrayList<String>();
        var latch          = new CountDownLatch(1);

        webEventBus.addListener(event -> {
            if ("narrator".equals(event.type()) && event.content() != null) {
                narratorEvents.add(event.content());
                latch.countDown();
            }
        });

        var world          = MansionLoader.loadWorld();
        var scenarioThread = orchestrator.startScenario(world, ScenarioMode.AUTONOMOUS);

        boolean received = latch.await(120, TimeUnit.SECONDS);
        world.setScenarioComplete(
                io.casehub.examples.manor.model.CompletionReason.TURN_LIMIT);
        scenarioThread.join(Duration.ofSeconds(30));

        assertThat(received)
                .as("At least one narrator event should arrive within 120s")
                .isTrue();
        assertThat(narratorEvents).isNotEmpty();

        String firstNarration = narratorEvents.get(0);
        System.out.println("\n=== NARRATOR OUTPUT ===\n" + firstNarration + "\n");

        assertThat(firstNarration)
                .as("Narrator output should be non-trivial prose")
                .hasSizeGreaterThan(20);
    }
}
