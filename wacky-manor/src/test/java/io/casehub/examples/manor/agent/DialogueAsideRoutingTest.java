package io.casehub.examples.manor.agent;

import io.casehub.examples.manor.engine.MansionLoader;
import io.casehub.examples.manor.model.ManorEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueAsideRoutingTest {

    @Test
    void dialogueRoutesToAllInRoom() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        service.publishEvent(new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: Hello everyone!"));

        for (String charId : List.of("hooded-claw", "ant-hill-mob", "peter-perfect", "dick-dastardly")) {
            var drain = service.drain(charId, System.currentTimeMillis());
            assertThat(drain.currentPartition().eventCount())
                    .as("Character %s should see dialogue", charId)
                    .isEqualTo(1);
        }
    }

    @Test
    void asideRoutesOnlyToSpeaker() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        service.publishEvent(new ManorEvent(Instant.now(), "aside", "hooded-claw",
                "entrance-hall", "Nyah-ha-ha! My fiendish plan!"));

        var hcDrain = service.drain("hooded-claw", System.currentTimeMillis());
        assertThat(hcDrain.currentPartition().eventCount()).isEqualTo(1);

        var penelopeDrain = service.drain("penelope-pitstop", System.currentTimeMillis());
        assertThat(penelopeDrain.currentPartition().eventCount()).isZero();
    }

    @Test
    void speakerSeesOwnDialogue() {
        var world = MansionLoader.loadWorld();
        var service = createService();
        service.init(world);

        service.publishEvent(new ManorEvent(Instant.now(), "dialogue", "penelope-pitstop",
                "entrance-hall", "Penelope: Why, hello!"));

        var drain = service.drain("penelope-pitstop", System.currentTimeMillis());
        assertThat(drain.currentPartition().eventCount()).isEqualTo(1);
    }

    private ObservationService createService() {
        return new ObservationService(
                new ManorObservationRenderer(new MechanicalCompactor(), 10, 15, null));
    }
}
