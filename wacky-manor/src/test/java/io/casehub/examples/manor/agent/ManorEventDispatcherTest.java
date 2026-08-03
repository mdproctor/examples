package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.model.ActionResult;
import io.casehub.examples.manor.model.ManorEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManorEventDispatcherTest {

    @Test
    void publishDialogue_adds_event_to_world() {
        var world              = io.casehub.examples.manor.engine.MansionLoader.loadWorld();
        var obsRenderer        = new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var dispatcher = new ManorEventDispatcher(
                world, observationService, null, null, null);

        var event = new ManorEvent(java.time.Instant.now(), "dialogue", "penelope",
                                   "entrance-hall", "Penelope Pitstop: Darlin'!");

        dispatcher.publishDialogue(event, "Darlin'!");

        assertThat(world.allEvents()).contains(event);
    }

    @Test
    void publishAction_adds_event_to_world() {
        var world              = io.casehub.examples.manor.engine.MansionLoader.loadWorld();
        var obsRenderer        = new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var dispatcher = new ManorEventDispatcher(
                world, observationService, null, null, null);

        var event = new ManorEvent(java.time.Instant.now(), "action", "hooded-claw",
                                   "kitchen", "The Hooded Claw picked up the Rat Poison",
                                   io.casehub.examples.manor.model.ActionType.TAKE, "poison", null, null);

        dispatcher.publishAction(event, new ActionResult.Success("Picked up"), 0.7);

        assertThat(world.allEvents()).contains(event);
    }

    @Test
    void publishDialogue_feeds_narrator_when_present() {
        var world              = io.casehub.examples.manor.engine.MansionLoader.loadWorld();
        var obsRenderer        = new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var narrated = new java.util.ArrayList<String>();
        var narrator = new NarratorAgent(new MechanicalCompactor(),
                                         NarratorAgentTest.echoProvider, null, null, 5, 15);
        narrator.testSubscribe(narrated::add);

        var dispatcher = new ManorEventDispatcher(
                world, observationService, narrator, null, null);

        for (int i = 0; i < 5; i++) {
            var event = new ManorEvent(java.time.Instant.now(), "dialogue", "char-" + i,
                                       "entrance-hall", "char-" + i + ": hello " + i);
            dispatcher.publishDialogue(event, "hello " + i);
        }
        narrator.tickNow();

        assertThat(narrated).hasSize(1);
    }

    @Test
    void null_narrator_does_not_npe() {
        var world              = io.casehub.examples.manor.engine.MansionLoader.loadWorld();
        var obsRenderer        = new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var dispatcher = new ManorEventDispatcher(
                world, observationService, null, null, null);

        var event = new ManorEvent(java.time.Instant.now(), "dialogue", "penelope",
                                   "entrance-hall", "Penelope: test");

        dispatcher.publishDialogue(event, "test");

        assertThat(world.allEvents()).contains(event);
    }

    @Test
    void null_channels_and_eventbus_does_not_npe() {
        var world              = io.casehub.examples.manor.engine.MansionLoader.loadWorld();
        var obsRenderer        = new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null);
        var observationService = new ObservationService(obsRenderer);
        observationService.init(world);

        var dispatcher = new ManorEventDispatcher(
                world, observationService, null, null, null);

        var event = new ManorEvent(java.time.Instant.now(), "aside", "hooded-claw",
                                   "kitchen", "Nyah-ha-ha!");

        dispatcher.publishAside(event, "Nyah-ha-ha!");

        assertThat(world.allEvents()).contains(event);
    }
}
