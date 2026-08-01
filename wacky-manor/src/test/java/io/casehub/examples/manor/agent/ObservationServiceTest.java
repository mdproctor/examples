package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.MansionLoader;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.ManorEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationServiceTest {

    WorldState createWorld() {
        return MansionLoader.loadWorld();
    }

    ObservationService createService() {
        var compactor = new MechanicalCompactor();
        var renderer = new ManorObservationRenderer(compactor, 10, 15, null);
        return new ObservationService(renderer);
    }

    @Test
    void publishEvent_routesToCharactersInSameRoom() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var event = new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: Hello!");
        service.publishEvent(event);

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isEqualTo(1);
    }

    @Test
    void publishEvent_skipsCharactersInDifferentRoom() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        world.moveCharacter("penelope-pitstop", "kitchen");
        var event = new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "kitchen", "Penelope: Hello from the Kitchen!");
        service.publishEvent(event);

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isZero();
    }

    @Test
    void publishEvent_nullRoom_silentlySkipped() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var event = new ManorEvent(Instant.now(), "narrator", null, null,
                "The chandelier creaks ominously!");
        service.publishEvent(event);

        var drain = service.drain("penelope-pitstop", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isZero();
    }

    @Test
    void publishEvent_asideOnlyRoutesToSpeaker() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var event = new ManorEvent(Instant.now(), "aside", "hooded-claw",
                "entrance-hall", "Nyah-ha-ha!");
        service.publishEvent(event);

        var hcDrain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(hcDrain.currentPartition().eventCount()).isEqualTo(1);

        var penelopeDrain = service.drain("penelope-pitstop", System.currentTimeMillis());
        assertThat(penelopeDrain.currentPartition().eventCount()).isZero();
    }

    @Test
    void roomTransition_previousRoomBecomesRemembered() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var event = new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: Hello!");
        service.publishEvent(event);

        world.moveCharacter("hooded-claw", "kitchen");

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isZero();
        assertThat(drain.rememberedPartitions()).containsKey("entrance-hall");
        assertThat(drain.rememberedPartitions().get("entrance-hall").result().eventCount()).isEqualTo(1);
    }

    @Test
    void moveEvent_routesToDepartureRoomObservers() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var moveEvent = new ManorEvent(Instant.now(), "action", "penelope-pitstop",
                "kitchen", "Penelope walked to the Kitchen.",
                ActionType.MOVE, "kitchen", null, "entrance-hall");
        world.moveCharacter("penelope-pitstop", "kitchen");
        service.publishEvent(moveEvent);

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isEqualTo(1);
        assertThat(drain.currentPartition().renderedText()).contains("walked");
    }

    @Test
    void drain_unknownCharacter_returnsEmpty() {
        var world = createWorld();
        var service = createService();
        service.init(world);

        var drain = service.drain("nonexistent", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isZero();
        assertThat(drain.rememberedPartitions()).isEmpty();
    }
}
