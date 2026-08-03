package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.model.ManorEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class NarratorAgentTest {

    static final AgentProvider echoProvider = new AgentProvider() {
        @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("DRAMATIC narration!"));
        }
        @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void collect_5_events_then_tick_dispatches_narration() {
        var dispatched = new ArrayList<String>();
        var agent = new NarratorAgent(
                new MechanicalCompactor(), echoProvider,
                null, null, 5, 15);
        agent.testSubscribe(dispatched::add);

        for (int i = 0; i < 5; i++) {
            agent.collect(new ManorEvent(Instant.now(), "action", "char-" + i,
                    "kitchen", "event " + i));
        }
        agent.tickNow();

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0)).isEqualTo("DRAMATIC narration!");
    }

    @Test
    void below_threshold_does_not_narrate() {
        var dispatched = new ArrayList<String>();
        var agent = new NarratorAgent(
                new MechanicalCompactor(), echoProvider,
                null, null, 5, 15);
        agent.testSubscribe(dispatched::add);

        for (int i = 0; i < 3; i++) {
            agent.collect(new ManorEvent(Instant.now(), "action", "char-" + i,
                    "kitchen", "event " + i));
        }
        agent.tickAt(System.currentTimeMillis());

        assertThat(dispatched).isEmpty();
    }

    @Test
    void timer_fires_when_events_below_threshold_but_old_enough() {
        var dispatched = new ArrayList<String>();
        var agent = new NarratorAgent(
                new MechanicalCompactor(), echoProvider,
                null, null, 5, 15);
        agent.testSubscribe(dispatched::add);

        agent.collect(new ManorEvent(Instant.now(), "action", "char-0",
                "kitchen", "event 0"));
        agent.tickAt(System.currentTimeMillis() + 16_000);

        assertThat(dispatched).hasSize(1);
    }

    @Test
    void collect_after_stop_does_not_throw() {
        var agent = new NarratorAgent(
                new MechanicalCompactor(), echoProvider,
                null, null, 5, 15);
        agent.stop();
        agent.collect(new ManorEvent(Instant.now(), "action", "x", "kitchen", "event"));
    }

    @Test
    void flush_captures_remaining_events_below_threshold() {
        var dispatched = new ArrayList<String>();
        var agent = new NarratorAgent(
                new MechanicalCompactor(), echoProvider,
                null, null, 5, 15);
        agent.testSubscribe(dispatched::add);

        for (int i = 0; i < 3; i++) {
            agent.collect(new ManorEvent(Instant.now(), "action", "char-" + i,
                    "kitchen", "event " + i));
        }
        agent.flushNow();

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0)).isEqualTo("DRAMATIC narration!");
    }
}
