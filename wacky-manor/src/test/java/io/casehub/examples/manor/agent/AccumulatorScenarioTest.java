package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.MansionLoader;
import io.casehub.examples.manor.engine.WorldState;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.ManorEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccumulatorScenarioTest {

    @Test
    void hcMovesToKitchen_entranceHallBecomesRemembered() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        service.publishEvent(new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: What a lovely foyer!"));

        world.moveCharacter("hooded-claw", "kitchen");
        service.publishEvent(new ManorEvent(Instant.now(), "action", "hooded-claw",
                "kitchen", "Sneekly walked to the Kitchen.",
                ActionType.MOVE, "kitchen", null, "entrance-hall"));

        service.publishEvent(new ManorEvent(Instant.now(), "dialogue", "hooded-claw",
                "kitchen", "Sneekly: What have we here..."));

        var drain = service.drain("hooded-claw", System.currentTimeMillis());

        assertThat(drain.currentPartition().eventCount()).isGreaterThan(0);
        assertThat(drain.currentPartition().renderedText()).contains("What have we here");

        assertThat(drain.rememberedPartitions()).containsKey("entrance-hall");
        assertThat(drain.rememberedPartitions().get("entrance-hall").result().renderedText())
                .contains("lovely foyer");
    }

    @Test
    void roomReturn_cachedMemoryPersists() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        service.publishEvent(new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: Hello!"));

        world.moveCharacter("hooded-claw", "kitchen");
        service.drain("hooded-claw", System.currentTimeMillis());

        world.moveCharacter("hooded-claw", "entrance-hall");

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isZero();
    }

    @Test
    void mechanicalCompaction_removesSupersededMoves() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        world.moveCharacter("penelope-pitstop", "kitchen");
        service.publishEvent(new ManorEvent(Instant.now(), "action", "penelope-pitstop",
                "kitchen", "Penelope walked to the Kitchen.",
                ActionType.MOVE, "kitchen", null, "entrance-hall"));

        world.moveCharacter("penelope-pitstop", "ballroom");
        service.publishEvent(new ManorEvent(Instant.now(), "action", "penelope-pitstop",
                "ballroom", "Penelope walked to the Ballroom.",
                ActionType.MOVE, "ballroom", null, "kitchen"));

        world.moveCharacter("penelope-pitstop", "entrance-hall");
        service.publishEvent(new ManorEvent(Instant.now(), "action", "penelope-pitstop",
                "entrance-hall", "Penelope walked to the Entrance Hall.",
                ActionType.MOVE, "entrance-hall", null, "ballroom"));

        var drain = service.drain("hooded-claw", System.currentTimeMillis());
        String rendered = drain.currentPartition().renderedText();
        assertThat(rendered).contains("Entrance Hall");
        assertThat(rendered).doesNotContain("Kitchen");
    }

    private ObservationService createService() {
        return new ObservationService(
                new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null));
    }
}
