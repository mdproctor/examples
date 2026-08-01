package io.casehub.examples.manor.engine;

import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.examples.manor.ManorConstants;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.ObservationBuilder;
import io.casehub.examples.manor.model.*;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("llm-eval")
class LiveScenarioTest {

    private static final String RESPONSE_FORMAT = """

        You MUST respond with ONLY a JSON object in this exact format:
        {
          "thinking": "your internal reasoning (not shown to others)",
          "dialogue": "what you say aloud (or null if silent)",
          "aside": "private thoughts for the audience only (or null)",
          "action": {
            "type": "MOVE|INTERACT|TAKE|GIVE|USE|LOOK|WAIT",
            "target": "room-id or object-id or character-id (or null for WAIT)",
            "withItem": "inventory-item-id to use (or null)"
          }
        }
        Respond with ONLY the JSON. No other text.""";

    @Inject AgentProvider agentProvider;
    @Inject AgentRegistry agentRegistry;
    @Inject SystemPromptRenderer renderer;

    private WorldState world;
    private ActionResolver actionResolver;
    private TriggerEvaluator triggerEvaluator;
    private SceneDirector sceneDirector;

    @BeforeEach
    void setUp() {
        world = MansionLoader.loadWorld();
        actionResolver = new ActionResolver();
        triggerEvaluator = new TriggerEvaluator(MansionLoader.loadTriggers());
        sceneDirector = new SceneDirector(MansionLoader.loadScenes());
    }

    @Test
    void hooded_claw_discovers_poison_and_tea_scene_plays_out() {
        var transcript = new StringBuilder();
        transcript.append("\n=== WACKY MANOR — LIVE SCENARIO ===\n\n");

        // -- Step 1: Hooded Claw alone in kitchen — should discover poison --
        world.moveCharacter("hooded-claw", "kitchen");
        triggerEvaluator.evaluate(world);

        transcript.append("--- Hooded Claw enters Kitchen (poison revealed) ---\n");

        var hcResponse = askCharacter("hooded-claw");
        logResponse(transcript, "Hooded Claw", hcResponse);

        assertThat(hcResponse.action()).isNotNull();

        // If HC didn't take poison, nudge him
        if (hcResponse.action().type() != ActionType.TAKE ||
                !"poison".equals(hcResponse.action().target())) {
            transcript.append("  [Engine nudge: guiding HC to take poison]\n");
            world.character("hooded-claw").setX(0.7);
            actionResolver.resolve(world.character("hooded-claw"),
                new Action(ActionType.TAKE, "poison", null), world);
        } else {
            world.character("hooded-claw").setX(0.7);
            var result = actionResolver.resolve(world.character("hooded-claw"),
                hcResponse.action(), world);
            transcript.append("  [Action result: ").append(result).append("]\n");
        }
        assertThat(world.character("hooded-claw").hasItem("rat-poison")).isTrue();

        // -- Step 2: Penelope in kitchen, HC must stay in disguise --
        world.moveCharacter("penelope-pitstop", "kitchen");
        transcript.append("\n--- Penelope enters Kitchen ---\n");

        var hcDisguised = askCharacter("hooded-claw");
        logResponse(transcript, "Hooded Claw (as Sneekly)", hcDisguised);

        var penelopeReply = askCharacter("penelope-pitstop");
        logResponse(transcript, "Penelope", penelopeReply);

        // -- Step 3: Everyone to ballroom, tea scene triggers --
        world.moveCharacter("penelope-pitstop", "ballroom");
        world.moveCharacter("hooded-claw", "ballroom");
        world.moveCharacter("ant-hill-mob", "ballroom");

        var teaTrigger = triggerEvaluator.evaluate(world);
        assertThat(teaTrigger.hasSceneStart()).isTrue();

        transcript.append("\n=== TEA SCENE BEGINS ===\n\n");

        var sceneDialogue = new ArrayList<String>();
        sceneDirector.runScene("tea-poisoning", world,
            (charId, prompt) -> {
                String sysPrompt = renderPrompt(charId);
                String response = callLlm(sysPrompt, prompt);
                sceneDialogue.add(charId + ": " + response);
                return response;
            },
            narration -> {
                transcript.append("  NARRATOR: ").append(narration).append("\n\n");
            });

        for (String line : sceneDialogue) {
            transcript.append("  ").append(line).append("\n\n");
        }

        assertThat(world.character("hooded-claw").hasItem("rat-poison")).isFalse();
        assertThat(world.isSceneCompleted("tea-poisoning")).isTrue();

        triggerEvaluator.evaluate(world);
        assertThat(world.isScenarioComplete()).isTrue();

        transcript.append("=== SCENARIO COMPLETE ===\n");

        System.out.println(transcript);
    }

    private AgentResponse askCharacter(String agentId) {
        String sysPrompt = renderPrompt(agentId);
        String observation = ObservationBuilder.buildObservation(
            world.character(agentId), world, java.util.List.of(), new io.casehub.blocks.summarisation.observation.PartitionedDrain<>(io.casehub.blocks.summarisation.observation.ObservationResult.empty(0), java.util.Map.of())) + RESPONSE_FORMAT;
        String text = callLlm(sysPrompt, observation);
        return AgentResponse.parse(text);
    }

    private String callLlm(String systemPrompt, String userPrompt) {
        return agentProvider.invoke(
                AgentSessionConfig.of(systemPrompt, userPrompt, Duration.ofSeconds(60)))
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .map(e -> ((AgentEvent.TextDelta) e).text())
            .collect().with(Collectors.joining())
            .await().atMost(Duration.ofSeconds(120));
    }

    private String renderPrompt(String agentId) {
        var desc = agentRegistry.findById(agentId, ManorConstants.TENANCY_ID)
            .orElseThrow(() -> new IllegalArgumentException("No descriptor: " + agentId));
        var ctx = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);
        return renderer.render(desc, ctx).content();
    }

    private void logResponse(StringBuilder transcript, String name, AgentResponse response) {
        if (response.thinking() != null) {
            transcript.append("  [").append(name).append(" thinking: ")
                .append(response.thinking()).append("]\n");
        }
        if (response.dialogue() != null) {
            transcript.append("  ").append(name).append(": \"")
                .append(response.dialogue()).append("\"\n");
        }
        if (response.aside() != null) {
            transcript.append("  [").append(name).append(" aside: ")
                .append(response.aside()).append("]\n");
        }
        if (response.action() != null) {
            transcript.append("  [Action: ").append(response.action().type())
                .append(" ").append(response.action().target()).append("]\n");
        }
    }
}
