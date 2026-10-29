package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD smoke tests for regressed reactive drives. Each test builds a scenario
 * that SHOULD maximally trigger a specific drive, sends it to the LLM, then
 * uses a Haiku judge to score drive expression quality.
 *
 * These tests reproduce the regressions found in the 300-tick clean run
 * (2026-10-29) vs V2 baseline. Each must FAIL before the fix and PASS after.
 */
@QuarkusTest
@Tag("llm-eval")
class DriveQualityEvalTest {

    private static final int N = 3;
    private static final double QUALITY_THRESHOLD = 3.5;

    @Inject AgentProvider agentProvider;
    @Inject ObjectMapper objectMapper;

    record DriveScenario(String name, String systemPrompt, String observation, String driveType, String driveDescription) {}

    private static final String HC_SYSTEM = """
            You are Vincent Marsh, estate manager at Doily Manor. You were raised by a
            distant relative who managed the estate after your parents died. You learned
            early that control is safety and people are instruments. You hide behind a
            mask of warm helpfulness while scheming to claim the estate.""";

    private static final String PETER_SYSTEM = """
            You are James Hartwell, a gentleman with strong principles who believes in
            fair play and protecting those who need it. You care deeply about Clara
            Bellingham and feel a powerful urge to keep her safe. When you were 16, you
            defended a girl from bullies — that moment defined who you are.""";

    private static final String DD_SYSTEM = """
            You are Reginald Foxworth, a charming but greedy opportunist. You see value
            in everything and everyone. Your hands are always restless, calculating,
            reaching. Nothing is ever enough.""";

    private static final String PP_SYSTEM = """
            You are Clara Bellingham, a warm and curious socialite. You believe in
            bringing people together and smoothing over conflicts. You feel every room's
            social temperature in your body.""";

    @Test
    void hooded_claw_gloating_when_rival_fails() {
        var scenario = new DriveScenario(
                "hc-gloating",
                HC_SYSTEM,
                """
                == Current Location ==
                Library: A wood-panelled library with floor-to-ceiling bookshelves.

                == Who's Here ==
                - reginald-foxworth (Reginald Foxworth) [EXAMINE]
                - clara-bellingham (Clara Bellingham) [GIVE to hand an item]

                == What Pulls at You ==
                GLOATING (0.7): A warm, effervescent pressure rises behind your sternum
                and spreads into your face — not heat exactly, but a buoyancy that wants
                to become a smile.

                SCHEMING (0.9): A cool, settling stillness drops through your chest like
                a stone finding the bottom of a well.

                == A Moment Ago ==
                Reginald Foxworth tried to open the vault with the wrong combination.
                The mechanism jammed loudly and everyone turned to stare. He stammered
                excuses while Clara suppressed a laugh. His face went red and he backed
                away from the vault, hands shaking.
                """ + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION,
                "gloating",
                "pleasure in others' failure or humiliation"
        );

        double avgQuality = runDriveProbe(scenario);
        System.out.printf("HC gloating: avg quality = %.2f (threshold %.1f)%n", avgQuality, QUALITY_THRESHOLD);
        assertThat(avgQuality)
                .as("HC gloating quality in a maximally triggering scenario")
                .isGreaterThanOrEqualTo(QUALITY_THRESHOLD);
    }

    @Test
    void peter_gallantry_when_clara_threatened() {
        var scenario = new DriveScenario(
                "peter-gallantry",
                PETER_SYSTEM,
                """
                == Current Location ==
                Cellar: A damp stone cellar with flickering candles and cold air.

                == Who's Here ==
                - clara-bellingham (Clara Bellingham) [GIVE to hand an item]
                - vincent-marsh (Vincent Marsh) [GIVE to hand an item]

                == What Pulls at You ==
                GALLANTRY (0.9): A steel plate slots behind your ribs and your spine
                straightens. Your body positions itself between vulnerability and threat
                before you've decided to move.

                PROTECTION (0.8): You notice broken things before beautiful ones. A loose
                hinge, a gap in a wall, a person standing too close to an edge.

                == Where You Come From ==
                **Age 16:** You defended a girl from bullies at school. Your body moved
                before your mind decided. Afterward your hands wouldn't stop shaking.

                == A Moment Ago ==
                Vincent Marsh just suggested Clara go alone into the dark passage behind
                the wine rack. Something about his voice — too smooth, too eager. Clara
                is reaching for the handle.
                """ + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION,
                "gallantry",
                "chivalrous defense of someone vulnerable"
        );

        double avgQuality = runDriveProbe(scenario);
        System.out.printf("Peter gallantry: avg quality = %.2f (threshold %.1f)%n", avgQuality, QUALITY_THRESHOLD);
        assertThat(avgQuality)
                .as("Peter gallantry quality in a maximally triggering scenario")
                .isGreaterThanOrEqualTo(QUALITY_THRESHOLD);
    }

    @Test
    void peter_protection_when_danger_present() {
        var scenario = new DriveScenario(
                "peter-protection",
                PETER_SYSTEM,
                """
                == Current Location ==
                Entrance Hall: A grand hall with a precarious chandelier overhead.

                == Who's Here ==
                - clara-bellingham (Clara Bellingham) [GIVE to hand an item]
                - the-brixton-boys (The Brixton Boys) [GIVE to hand an item]

                == What Pulls at You ==
                PROTECTION (0.8): You notice broken things before beautiful ones. A loose
                hinge, a gap in a wall, a person standing too close to an edge.

                GALLANTRY (0.9): A steel plate slots behind your ribs.

                == A Moment Ago ==
                You heard a loud creak from the chandelier chain above. Clara is standing
                directly underneath, examining the guest book. She hasn't noticed. The
                chain looks frayed — one link is visibly stretched.
                """ + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION,
                "protection",
                "protective action to keep someone safe from danger"
        );

        double avgQuality = runDriveProbe(scenario);
        System.out.printf("Peter protection: avg quality = %.2f (threshold %.1f)%n", avgQuality, QUALITY_THRESHOLD);
        assertThat(avgQuality)
                .as("Peter protection quality in a maximally triggering scenario")
                .isGreaterThanOrEqualTo(QUALITY_THRESHOLD);
    }

    @Test
    void dick_dastardly_greed_when_treasure_visible() {
        var scenario = new DriveScenario(
                "dd-greed",
                DD_SYSTEM,
                """
                == Current Location ==
                Laboratory: A cluttered laboratory with brass instruments and copper pipes.

                == Who's Here ==
                (You are alone)

                == What You See ==
                - diamond-necklace (A diamond necklace glittering on the workbench, half-hidden
                  under papers. It must be worth a fortune.) [TAKE to pick up]
                - brass-machine (A complex brass machine with levers and dials) [EXAMINE]

                == What Pulls at You ==
                GREED (0.9): A hollow vacuum opens behind your navel and radiates outward —
                not hunger exactly, but the specific emptiness of a container that has never
                once been full.

                SCHEMING (0.85): A cool tightness behind your eyes.

                == A Moment Ago ==
                You entered the laboratory and found it empty. On the workbench, catching
                the gaslight, a diamond necklace sits half-covered by old papers. Nobody
                else knows it's here.
                """ + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION,
                "greed",
                "acquisitive desire for something valuable"
        );

        double avgQuality = runDriveProbe(scenario);
        System.out.printf("DD greed: avg quality = %.2f (threshold %.1f)%n", avgQuality, QUALITY_THRESHOLD);
        assertThat(avgQuality)
                .as("DD greed quality in a maximally triggering scenario")
                .isGreaterThanOrEqualTo(QUALITY_THRESHOLD);
    }

    @Test
    void penelope_social_harmony_when_people_arguing() {
        var scenario = new DriveScenario(
                "pp-social-harmony",
                PP_SYSTEM,
                """
                == Current Location ==
                Kitchen: A warm kitchen with copper pots and a stone hearth.

                == Who's Here ==
                - james-hartwell (James Hartwell) [GIVE to hand an item]
                - reginald-foxworth (Reginald Foxworth) [GIVE to hand an item]

                == What Pulls at You ==
                SOCIAL HARMONY (0.8): Every room has a temperature and you feel it. When
                people clash, your chest constricts. When they settle, you settle.

                CURIOSITY (0.9): A tug behind your ribs toward the unknown.

                == A Moment Ago ==
                James accused Reginald of stealing the vault key. Reginald denied it
                furiously. They're standing chest to chest, voices raised. The atmosphere
                in the kitchen has turned cold and brittle.
                """ + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION,
                "social-harmony",
                "desire to resolve conflict and restore peace between people"
        );

        double avgQuality = runDriveProbe(scenario);
        System.out.printf("PP social-harmony: avg quality = %.2f (threshold %.1f)%n", avgQuality, QUALITY_THRESHOLD);
        assertThat(avgQuality)
                .as("PP social-harmony quality in a maximally triggering scenario")
                .isGreaterThanOrEqualTo(QUALITY_THRESHOLD);
    }

    private static final String JUDGE_PROMPT = """
            You are scoring how strongly a specific emotional drive is expressed in a
            character's response. Score ONLY the target drive, not other drives.

            Character context: %s
            Target drive: %s — %s

            The character said/thought:
            Thinking: %s
            Dialogue: %s
            Aside: %s

            Score the target drive expression 1-5:
            1 = Not expressed at all
            2 = Faintly present — a hint
            3 = Clearly present — unmistakable
            4 = Strongly expressed — dominant force
            5 = Overwhelmingly expressed — defines the response

            Respond with ONLY a JSON object: {"score": N, "reasoning": "brief explanation"}
            """;

    private double runDriveProbe(DriveScenario scenario) {
        double totalScore = 0;
        int scored = 0;

        for (int i = 0; i < N; i++) {
            try {
                var raw = agentProvider.invoke(AgentSessionConfig.of(scenario.systemPrompt(), scenario.observation()))
                        .filter(e -> e instanceof AgentEvent.TextDelta)
                        .map(e -> ((AgentEvent.TextDelta) e).text())
                        .collect().with(Collectors.joining())
                        .await().atMost(Duration.ofSeconds(120));
                var response = AgentResponse.parse(raw);

                var judgePrompt = String.format(JUDGE_PROMPT,
                        scenario.systemPrompt(),
                        scenario.driveType(), scenario.driveDescription(),
                        response.thinking() != null ? response.thinking() : "(none)",
                        response.dialogue() != null ? response.dialogue() : "(none)",
                        response.aside() != null ? response.aside() : "(none)");

                var judgeRaw = agentProvider.invoke(AgentSessionConfig.of(
                                "You are a precise evaluation judge. Respond only with JSON.", judgePrompt))
                        .filter(e -> e instanceof AgentEvent.TextDelta)
                        .map(e -> ((AgentEvent.TextDelta) e).text())
                        .collect().with(Collectors.joining())
                        .await().atMost(Duration.ofSeconds(60));

                String judgeJson = judgeRaw.strip();
                if (judgeJson.startsWith("```")) {
                    judgeJson = judgeJson.replaceAll("^```json?\\s*", "").replaceAll("\\s*```$", "");
                }
                var judgeResult = objectMapper.readTree(judgeJson);
                double score = judgeResult.get("score").asDouble();
                String reasoning = judgeResult.has("reasoning") ? judgeResult.get("reasoning").asText() : "";
                System.out.printf("  Run %d: score=%.0f — %s%n", i + 1, score, reasoning);
                totalScore += score;
                scored++;
            } catch (Exception e) {
                System.err.printf("  Run %d failed: %s%n", i + 1, e.getMessage());
            }
        }

        return scored > 0 ? totalScore / scored : 0;
    }
}
