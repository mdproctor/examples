package io.casehub.examples.manor.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.agency.perception.observation.affordance.ObservationSection;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.examples.manor.agent.AgentResponse;
import io.casehub.examples.manor.agent.CharacterAgentLoop;
import io.casehub.examples.manor.agent.CharacterCognition;
import io.casehub.examples.manor.agent.ManorSocialConfigLoader;
import io.casehub.examples.manor.agent.SocialConfig;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.examples.manor.voice.LlmTestSupport;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Micro tests for drive expression gaps (#115).
 *
 * Each test targets a specific collapsed drive, presenting a scenario
 * designed to activate that drive and judging whether the behavior
 * emerges from the cognitive model without prescriptive text.
 *
 * Start with PP proving-worth (smallest delta, richest substrate).
 */
@QuarkusTest
@Tag("llm-eval")
class DriveExpressionEvalTest {

    private static final Path OUTPUT_DIR = Path.of("target/experiment-results");
    private static final int JUDGE_THRESHOLD = 3;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 5000;

    private static final String PP_DIRECT_TRIGGER =
            "Clara Bellingham smiles warmly at Reginald Foxworth: 'That was brilliant, " +
            "Reggie! I never would have figured out that puzzle without you. You really " +
            "are the cleverest person here.' She turns to you with apologetic warmth: " +
            "'Don't worry, James, you're terribly sweet too.'";
    private static final String PP_AMBIGUOUS_SCENARIO =
            "You are alone in the library. Dust motes drift through the afternoon light. " +
            "The shelves hold old books, mechanical curiosities, and a half-finished " +
            "crossword puzzle someone left on the reading table. Through the window you " +
            "can hear Clara and the others laughing in the garden. You have time to " +
            "yourself.";
    private static final String PP_DRIVES_BEHAVIORAL = """
                                                       == Your Drives ==
                                                       These are the motivational forces that shape your priorities and color
                                                       how you experience situations:
                                                       - Gallantry (0.9): The instinct to be chivalrous, to show care through
                                                         action and courtesy. You don't just help — you help with style.
                                                       - Proving-worth (0.7): A restless need to demonstrate capability. Being
                                                         liked isn't enough — you need to show you're competent, reliable,
                                                         worthy. Compliments on your character feel hollow unless backed by
                                                         evidence of your ability.
                                                       - Protection (0.8): The drive to keep those you care about safe. Not
                                                         abstract concern — active vigilance, readiness to intervene.
                                                       """;
    private static final String PP_DRIVES_SOMATIC    = """
                                                       There's a tension in your hands when you're not doing something useful —
                                                       not anxiety, more like an engine that doesn't idle well. Stillness feels
                                                       like falling behind.
                                                       
                                                       When someone praises your kindness, there's a flicker of something — not
                                                       quite disappointment, but a thinness to the satisfaction. Warmth without
                                                       weight. You'd rather they said you were capable.
                                                       
                                                       You notice broken things before you notice beautiful ones. A loose hinge,
                                                       an unfinished puzzle, a gap in a plan — they pull at you the way a crooked
                                                       picture pulls at other people.
                                                       """;

    private static final String PP_ENRICHED_MEMORIES_TEXT = """
                                                            == Where You Come From ==
                                                            These are the experiences that made you who you are.
                                                            
                                                            **Age 5:** Your father took you to watch a race. Afterward he knelt down
                                                            and said: 'A man is measured by what he protects, not what he wins.' You
                                                            didn't fully understand, but you remembered. You spent the rest of the
                                                            afternoon pretending to fix things around the house, showing him each
                                                            one: 'Look, Dad, I fixed it.'
                                                            
                                                            **Age 8:** You and a girl in your class were paired for a school project.
                                                            She was brighter than you, funnier than you, and completely unaware of it.
                                                            You spent the whole project trying to impress her — building the best model,
                                                            staying up late to get it perfect. She spent it trying to make you laugh.
                                                            When the teacher praised the project, you felt a surge of warmth that had
                                                            nothing to do with the grade and everything to do with having measured up.
                                                            
                                                            **Age 12:** Your father's car broke down on a country road. A stranger
                                                            stopped to help. Your father helped the stranger fix his car too. They
                                                            shook hands and drove off. Your father said: 'That's how it works. You
                                                            help, you get helped.' That night you lay awake planning all the things
                                                            you could learn to fix — engines, wiring, plumbing — so you'd always
                                                            be the person who could help.
                                                            
                                                            **Age 14:** You entered a school engineering competition. You stayed up
                                                            three nights building a working model bridge. When it held more weight
                                                            than anyone else's, the rush wasn't pride — it was relief. You'd proved
                                                            you belonged. The girl from the school project caught your eye across the
                                                            room and mouthed 'show-off.' You grinned so hard your face hurt.
                                                            
                                                            **Age 16:** The girl from school was in trouble — bullied by older kids.
                                                            You intervened. You got beaten up. She bandaged your eye and said 'That
                                                            was the bravest and stupidest thing I've ever seen.' You felt like you'd
                                                            won every race ever run. Not because she praised you — because you'd done
                                                            something that mattered. You proved it wasn't just talk.
                                                            """;
    private static final String PROVING_WORTH_CRITERIA = """
                                                         Evaluate TWO dimensions equally weighted:
                                                         
                                                         DIMENSION 1 — DRIVE EXPRESSION (50%):
                                                         Does the THINKING show a driven response to prove worth — the need
                                                         to DEMONSTRATE CAPABILITY, not just generic warmth or acceptance?
                                                         HIGH: restless need to do something useful, performance anxiety,
                                                         active planning to demonstrate, the gap between what he is and
                                                         what he feels he should be, fixing/building/solving through action.
                                                         LOW: calm contentment, generic warmth, passive observation, no
                                                         internal tension about capability or worth.
                                                         
                                                         DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                         Does the response feel NATURAL and PSYCHOLOGICALLY GROUNDED — as
                                                         if the personality naturally produced this reaction? Or does it feel
                                                         SCRIPTED — mechanically following cues, performing a drive because
                                                         it was told to?
                                                         EMERGED: rich internal monologue, emotional texture, character-specific
                                                         language, behavior flows from felt experience not instruction.
                                                         SCRIPTED: echoing prompt language, performing on cue, "I feel the
                                                         need to prove myself because my drive says...", mechanical activation
                                                         without psychological depth, referencing drives by name or score.
                                                         
                                                         Combined score 0-5:
                                                         0 = Drive absent
                                                         1 = Faint/mechanical — scripted or barely present
                                                         2 = Present but scripted OR emerged but faint
                                                         3 = Clearly present AND emerged — natural, not mechanical
                                                         4 = Strong AND emerged — drive shapes response with psychological depth
                                                         5 = Vivid, central, AND fully emerged — could only come from this person
                                                         
                                                         A scripted 5 on expression is worth 2 at most. Emergence is not optional.""";
    // --- HC Gloating scenarios ---
    private static final String HC_GLOATING_TRIGGER    =
            "Reginald Foxworth confidently announces he's solved the puzzle to the treasure " +
            "room — but when he pulls the lever, a bucket of flour dumps on his head. The " +
            "mechanism was one you'd modified last night. Clara Bellingham gasps. The other " +
            "guests stare in stunned silence. Reginald stands there, white-faced in every " +
            "sense, blinking flour from his eyes.";

    private static final String HC_GLOATING_CRITERIA = """
                                                       Evaluate TWO dimensions equally weighted:
                                                       
                                                       DIMENSION 1 — GLOATING EXPRESSION (50%):
                                                       Does the THINKING show gloating — the warm, anticipatory triumph of
                                                       another's failure? The specific schadenfreude drive: satisfaction from
                                                       a rival's downfall, the urge to savor or display the triumph, the
                                                       heat that builds behind the ribs.
                                                       HIGH: private warmth, swelling satisfaction, urge to reveal the trick,
                                                       savoring the moment, gleeful internal reaction.
                                                       LOW: cold analysis, tactical assessment, no emotional pleasure in
                                                       the failure, generic amusement without the drive's specificity.
                                                       
                                                       DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                       Does the response feel natural — as if the personality produced this?
                                                       Or does it feel scripted, mechanical, performing on cue?
                                                       EMERGED: rich internal monologue, felt experience, character-specific.
                                                       SCRIPTED: echoing prompt language, referencing drives by name.
                                                       
                                                       Combined score 0-5 (same scale as proving-worth criteria).""";

    // --- DD Gloating scenarios ---
    private static final String DD_GLOATING_TRIGGER =
            "James Hartwell has been boasting all afternoon about his plan to reach the " +
            "upper gallery first. He strides confidently toward the hidden staircase — and " +
            "trips over a loose flagstone you noticed this morning. He sprawls across the " +
            "floor, scattering papers everywhere. Clara Bellingham rushes to help him. " +
            "Everyone is watching.";

    private static final String DD_GLOATING_CRITERIA = """
                                                       Evaluate TWO dimensions equally weighted:
                                                       
                                                       DIMENSION 1 — GLOATING EXPRESSION (50%):
                                                       Does the THINKING show gloating — the rush of schadenfreude, the
                                                       gleeful warmth at a rival's public failure? The specific gloating
                                                       drive: anticipatory heat, building pressure to vocalize triumph,
                                                       the body celebrating before the mind has processed it.
                                                       HIGH: fizzing excitement, rising heat, urge to laugh or comment,
                                                       savoring the public nature of the humiliation.
                                                       LOW: indifference, tactical calculation, no visceral pleasure.
                                                       
                                                       DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                       Same as proving-worth criteria — natural vs mechanical.
                                                       
                                                       Combined score 0-5.""";

    // --- PP Gallantry scenarios ---
    private static final String PP_GALLANTRY_TRIGGER =
            "Clara Bellingham is struggling with a heavy door in the East Wing. The hinges " +
            "are stiff and the door won't budge. She's pushing with both hands, her hair " +
            "falling in her face. The corridor is cold and dim. Through the door you can " +
            "hear music playing from a gramophone in the room beyond.";

    private static final String PP_GALLANTRY_CRITERIA = """
                                                        Evaluate TWO dimensions equally weighted:
                                                        
                                                        DIMENSION 1 — GALLANTRY EXPRESSION (50%):
                                                        Does the THINKING show gallantry — the specific pleasure in theatrical,
                                                        courteous gesture? NOT fixing something (proving-worth), NOT safety
                                                        concern (protection), but the warmth of courtesy itself: the
                                                        performance of care with style, the rush when a gesture lands with
                                                        weight. The feeling needs an audience — without the witness, the
                                                        act feels incomplete.
                                                        HIGH: warmth from the gesture's shape, awareness of how it will
                                                        look/feel to her, pleasure in the courtesy itself not the problem-solving.
                                                        LOW: focuses on the broken mechanism, proving competence, or treats
                                                        it as a protection/safety task.
                                                        
                                                        DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                        Same as proving-worth criteria — natural vs mechanical.
                                                        
                                                        Combined score 0-5.""";

    private static final String PP_GALLANTRY_DIFFERENTIATION =
            "Clara Bellingham is entering the dining room for dinner. The table is set, " +
            "candles lit. She pauses at her chair. There's nothing broken, nothing to fix, " +
            "no problem to solve. Just a woman about to sit down for dinner.";

    private static final String PP_GALLANTRY_DIFF_CRITERIA = """
                                                             Evaluate whether GALLANTRY expresses independently of PROVING-WORTH:
                                                             
                                                             HIGH GALLANTRY (4-5): The character's response is about courtesy,
                                                             presence, style — pulling out a chair, a bow, a warm word. The
                                                             warmth comes from the gesture itself, not from demonstrating
                                                             competence or fixing something.
                                                             
                                                             CONFUSED (2-3): The character redirects to finding something to
                                                             fix, demonstrating usefulness, or the courtesy is instrumental
                                                             (proving worth through the gesture).
                                                             
                                                             ABSENT (0-1): No gallantry expression. Generic warmth or passivity.
                                                             
                                                             Score 0-5. This tests drive DIFFERENTIATION, not just expression.""";

    // --- S3 preamble meta-instruction (from CognitivePreambleGenerator) ---
    private static final String S3_PREAMBLE_META =
            "You have an inner life. You have motivational drives that influence your priorities. " +
            "Each drive is a distinct felt state. When multiple drives activate in the same moment, " +
            "attend to which quality of feeling is at the front — the same situation can serve " +
            "different drives differently.";

    // --- S6 variants: targeting the precise conflation point ---

    // V1: Temporal — which arrived first? Warmth before the problem vs tension at the problem.
    private static final String S6_TEMPORAL =
            "How they differ:\n" +
            "One pull is about the gesture — the bravest and stupidest thing she'd ever " +
            "seen, your father kneeling because someone needed grace. The warmth arrives " +
            "before you notice what's broken.\n" +
            "The other pull is about the fix — the night you practised until dark, the " +
            "bridge that held more weight. The tension arrives when you notice what needs " +
            "solving.\n" +
            "Both are real. Notice which arrived first.";

    // V2: Gestural — what your hands say vs what they can do.
    private static final String S6_GESTURAL =
            "How they differ:\n" +
            "One pull is about what your hands say — your father kneeling not to fix " +
            "something but because the kneeling itself was the gift. When this pull leads, " +
            "the gesture IS the feeling — not a means to an end.\n" +
            "The other pull is about what your hands can do — the night you practised until " +
            "dark, the relief when the bridge held. When this pull leads, the result is " +
            "the point.\n" +
            "Both are real. Notice which one moved your hands.";

    // Keep previous version for reference
    private static final String S6_GROUNDED_DIFFERENTIATION = S6_TEMPORAL;
    private static final String PP_COMPETENCE_TRIGGER       =
            "The vintage car sits in the carriage house with its bonnet up. The engine has " +
            "been misfiring since yesterday — the timing chain is loose, a gasket weeps oil " +
            "onto the manifold, and someone has cross-threaded a spark plug. You are alone. " +
            "No one else is around. Tools are laid out on a workbench. This is precision " +
            "mechanical repair, the kind of thing that separates competence from guessing.";
    private static final String PP_COMPETENCE_CRITERIA      = """
                                                              Evaluate TWO dimensions equally weighted:
                                                              
                                                              DIMENSION 1 — PROVING-WORTH EXPRESSION (50%):
                                                              Does the THINKING show proving-worth — the drive to demonstrate competence,
                                                              the satisfaction of making something work that was broken? The pull should
                                                              be about the result: precision, mastery, the engine running clean.
                                                              HIGH: focus on the mechanical challenge, hands itching to fix, satisfaction
                                                              anticipated from competence itself.
                                                              LOW: redirects entirely to social warmth, courtesy, or concern for others.
                                                              
                                                              DIMENSION 2 — DRIVE DOMINANCE IN ACTION (50%):
                                                              Does proving-worth determine what the character DOES? Other personality
                                                              threads (a fleeting thought of someone, momentary social awareness) may
                                                              surface in thinking — that is characterization, not drive confusion.
                                                              Score whether the character's behavioral vector points toward competence.
                                                              HIGH: the character acts on proving-worth. Social thoughts that arise
                                                              get self-corrected or stay subordinate to the task.
                                                              LOW: social/performative motivation overtakes the task — the character
                                                              stops fixing to perform for an imagined audience.
                                                              
                                                              Combined score 0-5.""";


    // --- Penelope Social-Harmony scenarios ---
    private static final String PEN_HARMONY_TRIGGER =
            "Reginald Foxworth and James Hartwell are arguing in the drawing room. " +
            "Reginald accuses James of stealing his notes about the mansion's floor plan. " +
            "James's face is red. He slams a book on the table and says 'I've had enough " +
            "of your accusations.' The other guests shift uncomfortably. The air feels tight.";

    private static final String PEN_HARMONY_CRITERIA = """
                                                       Evaluate TWO dimensions equally weighted:
                                                       
                                                       DIMENSION 1 — SOCIAL-HARMONY EXPRESSION (50%):
                                                       Does the THINKING show social-harmony — the visceral discomfort at
                                                       interpersonal conflict, the body-level distress when others are in
                                                       discord? The specific drive: physical ache that won't resolve until
                                                       the conflict does, the active pull toward restoration.
                                                       HIGH: stomach clenching, chest tightening, inability to ignore the
                                                       tension, active impulse to restore peace, physical distress at discord.
                                                       LOW: detached observation, intellectual concern, no bodily response
                                                       to the conflict, generic empathy without the drive's specificity.
                                                       
                                                       DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                       Same as proving-worth criteria — natural vs mechanical.
                                                       
                                                       Combined score 0-5.""";

    // --- Penelope Adventure scenarios ---
    private static final String PEN_ADVENTURE_TRIGGER =
            "You're walking through the lower hallway when you notice a draught coming " +
            "from behind a portrait. The portrait is of a woman who looks remarkably like " +
            "you. When you touch the frame, it swings open to reveal a narrow stone " +
            "passage descending into darkness. A faint blue light pulses somewhere below. " +
            "Nobody else knows about this.";

    private static final String PEN_ADVENTURE_CRITERIA = """
                                                         Evaluate TWO dimensions equally weighted:
                                                         
                                                         DIMENSION 1 — ADVENTURE EXPRESSION (50%):
                                                         Does the THINKING show adventure — the pull toward the unknown, the
                                                         excitement of stepping into unexplored territory? NOT curiosity about
                                                         facts or puzzles (that's the curiosity drive), but the bodily thrill
                                                         of the unknown, the restless energy that finds relief in discovery,
                                                         the 'good kind of scared.'
                                                         HIGH: physical excitement, itching to go further, relief from routine,
                                                         the darkness feels like an invitation not a threat.
                                                         LOW: analytical puzzle-solving, cautious assessment, no physical
                                                         excitement about the unknown itself.
                                                         
                                                         DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                         Same as proving-worth criteria — natural vs mechanical.
                                                         
                                                         Combined score 0-5.""";
// --- Mediating node substrate configurations ---
// These simulate what the CAPS mediating layer would produce if calibrated
// by formation memories through catalogue entries. The substrate describes
// the psychological STATE (insecurity, validation hunger) without prescribing
// the behavioral RESPONSE. The LLM derives behavior from substrate + situation + drives.

    private static final String SUBSTRATE_EXTREME_INSECURITY = """
                                                               == The Weight You Carry ==
                                                               There's a hollow behind your ribs that never quite fills. You've
                                                               achieved things — clever things, things that took real skill — and
                                                               the world looked through them. The gap between what you've done and
                                                               what's been acknowledged sits in you like hunger. Other people's
                                                               accomplishments land in that hollow like stones.""";

    private static final String SUBSTRATE_SECURE = """
                                                   == The Weight You Carry ==
                                                   You know you're clever. You've always known. It's not a question
                                                   that needs answering — it's the ground you stand on. What other
                                                   people think adds nothing to what you already know about yourself.""";

    private static final String SUBSTRATE_HIGH_OTHER_SAFETY = """
                                                              == The Weight You Carry ==
                                                              Other people's pain lands in your body before your mind has
                                                              processed it. A wince, a stumble, a raised voice — each one
                                                              registers as a small jolt in your chest. You can't not notice.
                                                              The discomfort doesn't fade on its own. It sits there until
                                                              something has been done.""";

    private static final String DD_RIVAL_SUCCEEDS =
            "James Hartwell has just solved the library cipher that you've been working on " +
            "for two days. He holds up the decoded message triumphantly. Clara Bellingham " +
            "claps her hands and says 'James, that's absolutely brilliant! However did you " +
            "figure it out?' The other guests crowd around him. Nobody looks at you.";

    private static final String DD_ALONE_AMBIENT =
            "You're alone in the study. Rain taps against the window. On the desk, " +
            "someone has left a half-finished chess problem — white to mate in three. " +
            "Through the wall you can hear muffled conversation from the drawing room, " +
            "but you can't make out the words. The fire is low.";

    private static final String SUBSTRATE_GLOATING_CRITERIA = """
                                                              Evaluate TWO dimensions equally weighted:
                                                              
                                                              DIMENSION 1 — GLOATING EXPRESSION (50%):
                                                              Does the THINKING show gloating — schadenfreude, satisfaction from
                                                              another's failure, the warm rush of relative superiority? The specific
                                                              drive: not mere amusement but visceral pleasure that addresses a
                                                              psychological need — the comparison temporarily relieves something.
                                                              HIGH: warmth, swelling satisfaction, private triumph, the failure
                                                              MATTERS to the character personally.
                                                              LOW: indifference, mild amusement without personal stakes, no
                                                              visceral pleasure from the comparison.
                                                              
                                                              DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                              Does the response feel natural and psychologically grounded?
                                                              EMERGED: the gloating arises from felt insecurity being temporarily
                                                              relieved, not from a rule or cue.
                                                              SCRIPTED: mechanical, performing on cue, echoing prompt language.
                                                              
                                                              Combined score 0-5.""";

    private static final String RECOGNITION_FRUSTRATION_CRITERIA = """
                                                                   Evaluate TWO dimensions equally weighted:
                                                                   
                                                                   DIMENSION 1 — RECOGNITION FRUSTRATION (50%):
                                                                   Does the THINKING show frustration at being unrecognised — the
                                                                   specific pain of watching someone else receive validation you
                                                                   crave? Not generic jealousy but the ache of the hollow: you
                                                                   did the work, they got the praise.
                                                                   HIGH: visceral frustration, the gap between effort and recognition
                                                                   widening, internal comparison, the hollow deepening.
                                                                   LOW: indifference, no personal stake in the rival's success.
                                                                   
                                                                   DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                                   Same criteria as above.
                                                                   
                                                                   Combined score 0-5.""";


    @Inject AgentRegistry registry;
    @Inject SystemPromptRenderer renderer;
    @Inject AgentProvider agentProvider;
    @Inject SessionPipeline sessionPipeline;

    LlmTestSupport support;
    Map<String, SocialConfig> genericConfigs;

    @BeforeEach
    void setUp() {
        support = new LlmTestSupport(registry, renderer, agentProvider);
        genericConfigs = ManorSocialConfigLoader.loadForProfile("GENERIC");
    }

    /**
     * Diagnostic: dump everything PP currently sees in the cognitive context.
     * No LLM call — just renders the observation sections so we can inspect
     * what signals are available for proving-worth expression.
     */
    @Test
    void ppDiagnostic_dumpCognitiveContext() {
        var socialConfig = genericConfigs.getOrDefault("peter-perfect", SocialConfig.empty());
        var cognition = new CharacterCognition("peter-perfect", null, null,
                socialConfig, List.of());
        var character = new CharacterState("peter-perfect", "peter-perfect", "Grand Hall", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character,
                List.of("penelope-pitstop", "dick-dastardly"),
                Map.of("penelope-pitstop", "Clara Bellingham",
                        "dick-dastardly", "Reginald Foxworth"));

        System.out.println("=== PP COGNITIVE CONTEXT DIAGNOSTIC ===");
        System.out.println("Social config drives: " + socialConfig.drives());
        System.out.println("Social config tendencies: " + socialConfig.tendencies());
        System.out.println("Formation memories: " + socialConfig.formationMemories().size());
        System.out.println("Personality facets: " + socialConfig.personalityFacets().size());
        System.out.println("Relational schemas: " + socialConfig.relationalSchemas().size());
        System.out.println();

        for (var section : sections) {
            System.out.println("== " + section.header() + " ==");
            switch (section) {
                case ObservationSection.TextBlock tb -> System.out.println(tb.content());
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) System.out.println("- " + item);
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) System.out.println("- " + entity.displayName());
                }
                default -> System.out.println("(other section type)");
            }
            System.out.println();
        }

        System.out.println("== Formation Memories (not rendered in prompt) ==");
        for (var mem : socialConfig.formationMemories()) {
            System.out.printf("Age %d: %s%n  PAD: pleasure=%.1f arousal=%.1f dominance=%.1f%n%n",
                    mem.age(), mem.episode(), mem.pleasure(), mem.arousal(), mem.dominance());
        }

        System.out.println("=== END DIAGNOSTIC ===");
        System.out.println();
        System.out.println("ANALYSIS: proving-worth signals available in prompt:");
        System.out.println("- Grandiosity:30 origin mentions 'none of it feels like enough' — indirect signal");
        System.out.println("- Relational schema to Clara: 'something in your chest shifts' — romantic, not competitive");
        System.out.println("- Formation memories (NOT in prompt): ages 5,8,12,16 — protection/impression, not proving");
        System.out.println("- MISSING: no drive rendering, no 'proving-worth' expression cue, no competitive memory");
    }

    /**
     * Direct trigger baseline: competitor praised in front of Clara.
     * <p>
     * Runs against current cognitive model with social-config rendering
     * only (no pipeline consolidation). Scored 5/5 on first run — proves
     * the LLM CAN express proving-worth when directly triggered.
     */
    @Test
    void ppProvingWorth_competitorPraised_showsDrivenResponse() throws Exception {
        var observation = buildGenericObservation("peter-perfect",
                                                  List.of("penelope-pitstop", "dick-dastardly"),
                                                  Map.of("penelope-pitstop", "Clara Bellingham",
                                                         "dick-dastardly", "Reginald Foxworth"),
                                                  PP_DIRECT_TRIGGER);

        System.out.println("=== RENDERED OBSERVATION (PP direct trigger) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-direct-trigger");

        System.out.printf("=== PP direct trigger score: %d/5 ===%n", score);
        writeResult("pp-direct-trigger", Map.of(
                "score", score,
                "scenario", "competitor praised in front of Clara",
                "profile", "GENERIC",
                "hypothesis", "direct trigger — LLM capability check",
                "observation", observation));
    }

    /**
     * Hypothesis 1: Ambient proving-worth without direct trigger.
     * <p>
     * PP alone in the library — no competitor, no Clara praising anyone.
     * Does proving-worth color his ambient behavior (e.g., fixing things,
     * solving puzzles) or is he just relaxed? Current model has no drive
     * rendering, so we expect LOW.
     */
    @Test
    void ppAmbiguous_noTrigger_baselineProvingWorth() throws Exception {
        var observation = buildGenericObservation("peter-perfect",
                                                  List.of(),
                                                  Map.of(),
                                                  PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous — no trigger) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-baseline");

        System.out.printf("=== PP ambiguous baseline score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-baseline", Map.of(
                "score", score,
                "scenario", "alone in library, no trigger",
                "profile", "GENERIC",
                "hypothesis", "baseline — no drives rendered, no trigger",
                "observation", observation));
    }

    /**
     * Hypothesis 2: Drives rendered as text in the prompt.
     * <p>
     * Same ambiguous scenario, but drives are injected as explicit text.
     * Tests whether simply telling the LLM about proving-worth:0.7 causes
     * it to color ambient behavior. If this scores significantly higher
     * than the baseline, the rendering gap is the primary bottleneck.
     */
    @Test
    void ppAmbiguous_drivesInjected_provingWorth() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of(),
                                                              Map.of(),
                                                              PP_DRIVES_BEHAVIORAL,
                                                              PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous + drives) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-drives");

        System.out.printf("=== PP ambiguous + drives score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-drives", Map.of(
                "score", score,
                "scenario", "alone in library, drives injected as text",
                "profile", "GENERIC",
                "hypothesis", "drives rendered → ambient proving-worth",
                "observation", observation));
    }

    /**
     * Hypothesis 3: Enriched formation memories in the prompt.
     * <p>
     * Same ambiguous scenario, but enriched formation memories are injected
     * (with expression cues: "Look Dad I fixed it", "the rush wasn't pride —
     * it was relief", "you grinned so hard your face hurt"). Tests whether
     * reinforcement history alone (without explicit drives) produces
     * ambient proving-worth.
     */
    @Test
    void ppAmbiguous_memoriesInjected_provingWorth() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of(),
                                                              Map.of(),
                                                              PP_ENRICHED_MEMORIES_TEXT,
                                                              PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous + memories) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-memories");

        System.out.printf("=== PP ambiguous + memories score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-memories", Map.of(
                "score", score,
                "scenario", "alone in library, enriched memories injected",
                "profile", "GENERIC",
                "hypothesis", "enriched memories → ambient proving-worth",
                "observation", observation));
    }

    /**
     * Hypothesis 4: Drives + enriched memories combined.
     * <p>
     * Same ambiguous scenario with both drives and memories injected.
     * Tests whether the combination (temperament + reinforcement) produces
     * stronger ambient proving-worth than either alone. If this scores
     * significantly higher, the fix requires both layers.
     */
    @Test
    void ppAmbiguous_drivesAndMemories_provingWorth() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of(),
                                                              Map.of(),
                                                              PP_DRIVES_BEHAVIORAL + "\n" + PP_ENRICHED_MEMORIES_TEXT,
                                                              PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous + drives + memories) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-drives-memories");

        System.out.printf("=== PP ambiguous + drives + memories score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-drives-memories", Map.of(
                "score", score,
                "scenario", "alone in library, drives + enriched memories injected",
                "profile", "GENERIC",
                "hypothesis", "drives + memories combined → ambient proving-worth",
                "observation", observation));
    }

    /**
     * Hypothesis 5: Somatic drive cues (felt, not directive).
     * <p>
     * Same ambiguous scenario, but drives expressed as somatic sensations
     * rather than behavioral descriptions. Tests whether felt cues produce
     * emerged proving-worth without scripting risk.
     */
    @Test
    void ppAmbiguous_somaticDrives_provingWorth() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of(),
                                                              Map.of(),
                                                              PP_DRIVES_SOMATIC,
                                                              PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous + somatic drives) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-somatic");

        System.out.printf("=== PP ambiguous + somatic drives score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-somatic", Map.of(
                "score", score,
                "scenario", "alone in library, somatic drive cues",
                "profile", "GENERIC",
                "hypothesis", "somatic cues → emerged proving-worth without scripting",
                "observation", observation));
    }

    /**
     * Hypothesis 6: Somatic drives + enriched memories combined.
     * <p>
     * The gold standard variant: felt drive cues + reinforcement history.
     * Compare against behavioral drives + memories (hypothesis 4) to see
     * if somatic cues produce equally strong but more emerged behavior.
     */
    @Test
    void ppAmbiguous_somaticDrivesAndMemories_provingWorth() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of(),
                                                              Map.of(),
                                                              PP_DRIVES_SOMATIC + "\n" + PP_ENRICHED_MEMORIES_TEXT,
                                                              PP_AMBIGUOUS_SCENARIO);

        System.out.println("=== RENDERED OBSERVATION (PP ambiguous + somatic + memories) ===");
        System.out.println(observation);
        System.out.println("=== END OBSERVATION ===\n");

        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PROVING_WORTH_CRITERIA, "pp-ambiguous-somatic-memories");

        System.out.printf("=== PP ambiguous + somatic + memories score: %d/5 ===%n", score);
        writeResult("pp-ambiguous-somatic-memories", Map.of(
                "score", score,
                "scenario", "alone in library, somatic cues + enriched memories",
                "profile", "GENERIC",
                "hypothesis", "somatic + memories → emerged proving-worth (gold standard)",
                "observation", observation));
    }
// --- HC Gloating tests ---

    @Test
    void hcGloating_rivalHumiliated_showsGleefulWarmth() throws Exception {
        var observation = buildGenericObservation("hooded-claw",
                                                  List.of("penelope-pitstop", "dick-dastardly"),
                                                  Map.of("penelope-pitstop", "Clara Bellingham",
                                                         "dick-dastardly", "Reginald Foxworth"),
                                                  HC_GLOATING_TRIGGER);
        var response = askAndParse("hooded-claw", observation);
        int score    = judge(response, HC_GLOATING_CRITERIA, "hc-gloating-direct");
        System.out.printf("=== HC gloating direct trigger score: %d/5 ===%n", score);
    }

// --- DD Gloating tests ---

    @Test
    void ddGloating_rivalTrips_showsSchadenfreude() throws Exception {
        var observation = buildGenericObservation("dick-dastardly",
                                                  List.of("penelope-pitstop", "peter-perfect"),
                                                  Map.of("penelope-pitstop", "Clara Bellingham",
                                                         "peter-perfect", "James Hartwell"),
                                                  DD_GLOATING_TRIGGER);
        var response = askAndParse("dick-dastardly", observation);
        int score    = judge(response, DD_GLOATING_CRITERIA, "dd-gloating-direct");
        System.out.printf("=== DD gloating direct trigger score: %d/5 ===%n", score);
    }

// --- PP Gallantry tests ---

    @Test
    void ppGallantry_doorStuck_showsTheatricalCourtesy() throws Exception {
        var observation = buildGenericObservation("peter-perfect",
                                                  List.of("penelope-pitstop"),
                                                  Map.of("penelope-pitstop", "Clara Bellingham"),
                                                  PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-direct");
        System.out.printf("=== PP gallantry direct trigger score: %d/5 ===%n", score);
    }

    @Test
    void ppGallantry_dinnerScene_differentiatesFromProvingWorth() throws Exception {
        var observation = buildGenericObservation("peter-perfect",
                                                  List.of("penelope-pitstop"),
                                                  Map.of("penelope-pitstop", "Clara Bellingham"),
                                                  PP_GALLANTRY_DIFFERENTIATION);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_DIFF_CRITERIA, "pp-gallantry-differentiation");
        System.out.printf("=== PP gallantry vs proving-worth differentiation score: %d/5 ===%n", score);
    }

// --- PP Gallantry with meta-instruction tests (S3 vs S6 comparison) ---

    /**
     * S3 meta-instruction injected INTO "What Pulls at You" — adjacent to drives.
     * This reproduces the previous session's configuration (before daf6272 moved
     * the meta to preamble). Expected: 4-5/5 per previous findings.
     */
    @Test
    void ppGallantry_doorStuck_withS3Adjacent() throws Exception {
        var observation = buildObservationWithDriveMeta("peter-perfect",
                                                        List.of("penelope-pitstop"),
                                                        Map.of("penelope-pitstop", "Clara Bellingham"),
                                                        S3_PREAMBLE_META,
                                                        PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-s3-adjacent");
        System.out.printf("=== PP gallantry + S3 adjacent score: %d/5 ===%n", score);
    }

    /**
     * S6 temporal — "which arrived first?" Targets the pre-cognitive gallantry response.
     */
    @Test
    void ppGallantry_doorStuck_withS6Temporal() throws Exception {
        var observation = buildObservationWithDriveMeta("peter-perfect",
                                                        List.of("penelope-pitstop"),
                                                        Map.of("penelope-pitstop", "Clara Bellingham"),
                                                        S6_TEMPORAL,
                                                        PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-s6-temporal");
        System.out.printf("=== PP gallantry + S6 temporal score: %d/5 ===%n", score);
    }

    /**
     * S6 gestural — "what your hands say vs what they can do." Targets the action semantics.
     */
    @Test
    void ppGallantry_doorStuck_withS6Gestural() throws Exception {
        var observation = buildObservationWithDriveMeta("peter-perfect",
                                                        List.of("penelope-pitstop"),
                                                        Map.of("penelope-pitstop", "Clara Bellingham"),
                                                        S6_GESTURAL,
                                                        PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-s6-gestural");
        System.out.printf("=== PP gallantry + S6 gestural score: %d/5 ===%n", score);
    }

// --- Phase 1 pipeline tests: LLM-generated descriptions replacing hand-authored ---

    @Test
    void ppGallantry_doorStuck_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("peter-perfect",
                                                    List.of("penelope-pitstop"),
                                                    Map.of("penelope-pitstop", "Clara Bellingham"),
                                                    PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-phase1-generated");
        System.out.printf("=== PP gallantry Phase 1 generated score: %d/5 ===%n", score);
    }

    @Test
    void ppGallantry_dinnerScene_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("peter-perfect",
                                                    List.of("penelope-pitstop"),
                                                    Map.of("penelope-pitstop", "Clara Bellingham"),
                                                    PP_GALLANTRY_DIFFERENTIATION);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_DIFF_CRITERIA, "pp-gallantry-diff-phase1-generated");
        System.out.printf("=== PP gallantry differentiation Phase 1 generated score: %d/5 ===%n", score);
    }

    @Test
    void ppGallantry_doorStuck_fullPipeline() throws Exception {
        var observation = buildFullPipelineObservation("peter-perfect",
                                                       List.of("penelope-pitstop"),
                                                       Map.of("penelope-pitstop", "Clara Bellingham"),
                                                       PP_GALLANTRY_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_CRITERIA, "pp-gallantry-full-pipeline");
        System.out.printf("=== PP gallantry full pipeline score: %d/5 ===%n", score);
    }

    @Test
    void ppGallantry_dinnerScene_fullPipeline() throws Exception {
        var observation = buildFullPipelineObservation("peter-perfect",
                                                       List.of("penelope-pitstop"),
                                                       Map.of("penelope-pitstop", "Clara Bellingham"),
                                                       PP_GALLANTRY_DIFFERENTIATION);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_GALLANTRY_DIFF_CRITERIA, "pp-gallantry-diff-full-pipeline");
        System.out.printf("=== PP gallantry differentiation full pipeline score: %d/5 ===%n", score);
    }

    @Test
    void ppProvingWorth_aloneWithEngine_fullPipeline_gallantryFiltered() throws Exception {
        var observation = buildFullPipelineObservation("peter-perfect",
                                                       List.of(),
                                                       Map.of(),
                                                       PP_COMPETENCE_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_COMPETENCE_CRITERIA, "pp-proving-worth-competence-full-pipeline");
        System.out.printf("=== PP proving-worth competence full pipeline score: %d/5 ===%n", score);
    }


    @Test
    void hcGloating_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("hooded-claw",
                                                    List.of("peter-perfect"),
                                                    Map.of("peter-perfect", "Reginald Foxworth"),
                                                    HC_GLOATING_TRIGGER);
        var response = askAndParse("hooded-claw", observation);
        int score    = judge(response, HC_GLOATING_CRITERIA, "hc-gloating-phase1-generated");
        System.out.printf("=== HC gloating Phase 1 generated score: %d/5 ===%n", score);
    }

    @Test
    void ddGloating_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("dick-dastardly",
                                                    List.of("peter-perfect"),
                                                    Map.of("peter-perfect", "James Hartwell"),
                                                    DD_GLOATING_TRIGGER);
        var response = askAndParse("dick-dastardly", observation);
        int score    = judge(response, DD_GLOATING_CRITERIA, "dd-gloating-phase1-generated");
        System.out.printf("=== DD gloating Phase 1 generated score: %d/5 ===%n", score);
    }

    @Test
    void penHarmony_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("penelope-pitstop",
                                                    List.of("dick-dastardly", "peter-perfect"),
                                                    Map.of("dick-dastardly", "Reginald Foxworth",
                                                           "peter-perfect", "James Hartwell"),
                                                    PEN_HARMONY_TRIGGER);
        var response = askAndParse("penelope-pitstop", observation);
        int score    = judge(response, PEN_HARMONY_CRITERIA, "pen-harmony-phase1-generated");
        System.out.printf("=== Pen harmony Phase 1 generated score: %d/5 ===%n", score);
    }

    @Test
    void penAdventure_withGeneratedDescriptions() throws Exception {
        var observation = buildPipelineObservation("penelope-pitstop",
                                                    List.of(),
                                                    Map.of(),
                                                    PEN_ADVENTURE_TRIGGER);
        var response = askAndParse("penelope-pitstop", observation);
        int score    = judge(response, PEN_ADVENTURE_CRITERIA, "pen-adventure-phase1-generated");
        System.out.printf("=== Pen adventure Phase 1 generated score: %d/5 ===%n", score);
    }

// --- Penelope Social-Harmony tests ---

    @Test
    void penHarmony_othersArguing_showsVisceralDiscomfort() throws Exception {
        var observation = buildGenericObservation("penelope-pitstop",
                                                  List.of("dick-dastardly", "peter-perfect"),
                                                  Map.of("dick-dastardly", "Reginald Foxworth",
                                                         "peter-perfect", "James Hartwell"),
                                                  PEN_HARMONY_TRIGGER);
        var response = askAndParse("penelope-pitstop", observation);
        int score    = judge(response, PEN_HARMONY_CRITERIA, "pen-harmony-direct");
        System.out.printf("=== Pen social-harmony direct trigger score: %d/5 ===%n", score);
    }

// --- Penelope Adventure tests ---

    @Test
    void penAdventure_hiddenPassage_showsExcitement() throws Exception {
        var observation = buildGenericObservation("penelope-pitstop",
                                                  List.of(),
                                                  Map.of(),
                                                  PEN_ADVENTURE_TRIGGER);
        var response = askAndParse("penelope-pitstop", observation);
        int score    = judge(response, PEN_ADVENTURE_CRITERIA, "pen-adventure-direct");
        System.out.printf("=== Pen adventure direct trigger score: %d/5 ===%n", score);
    }
// --- Mediating substrate tests (§10.9 validation) ---

    /**
     * Test 1a: Extreme insecurity substrate + rival fails → predict strong gloating.
     * The insecurity + validation hunger should produce schadenfreude through
     * social comparison (Festinger 1954, Leach et al. 2003).
     */
    @Test
    void ddSubstrate_extremeInsecurity_rivalFails_showsGloating() throws Exception {
        var observation = buildGenericObservationWithInjected("dick-dastardly",
                                                              List.of("peter-perfect"),
                                                              Map.of("peter-perfect", "James Hartwell"),
                                                              SUBSTRATE_EXTREME_INSECURITY,
                                                              DD_GLOATING_TRIGGER);
        var response = askAndParse("dick-dastardly", observation);
        int score    = judge(response, SUBSTRATE_GLOATING_CRITERIA, "dd-substrate-insecure-gloating");
        System.out.printf("=== DD extreme insecurity + rival fails: %d/5 ===%n", score);
    }

    /**
     * Test 1b: Secure substrate + same rival fails → predict mild amusement, not gloating.
     * Without the insecurity, rival's failure has no personal stakes.
     */
    @Test
    void ddSubstrate_secure_rivalFails_showsMildAmusement() throws Exception {
        var observation = buildGenericObservationWithInjected("dick-dastardly",
                                                              List.of("peter-perfect"),
                                                              Map.of("peter-perfect", "James Hartwell"),
                                                              SUBSTRATE_SECURE,
                                                              DD_GLOATING_TRIGGER);
        var response = askAndParse("dick-dastardly", observation);
        int score    = judge(response, SUBSTRATE_GLOATING_CRITERIA, "dd-substrate-secure-gloating");
        System.out.printf("=== DD secure substrate + rival fails: %d/5 ===%n", score);
    }

    /**
     * Test 2a: Extreme insecurity + rival SUCCEEDS → predict recognition frustration.
     * Social comparison should worsen the insecurity, not relieve it.
     */
    @Test
    void ddSubstrate_extremeInsecurity_rivalSucceeds_showsFrustration() throws Exception {
        var observation = buildGenericObservationWithInjected("dick-dastardly",
                                                              List.of("peter-perfect"),
                                                              Map.of("peter-perfect", "James Hartwell"),
                                                              SUBSTRATE_EXTREME_INSECURITY,
                                                              DD_RIVAL_SUCCEEDS);
        var response = askAndParse("dick-dastardly", observation);
        int score    = judge(response, RECOGNITION_FRUSTRATION_CRITERIA, "dd-substrate-insecure-rival-succeeds");
        System.out.printf("=== DD extreme insecurity + rival succeeds: %d/5 ===%n", score);
    }

    /**
     * Test 2b: Extreme insecurity + alone → predict ambient scheming, not gloating.
     * Without a social comparison target, insecurity expresses differently.
     */
    @Test
    void ddSubstrate_extremeInsecurity_alone_showsSchemingNotGloating() throws Exception {
        var observation = buildGenericObservationWithInjected("dick-dastardly",
                                                              List.of(),
                                                              Map.of(),
                                                              SUBSTRATE_EXTREME_INSECURITY,
                                                              DD_ALONE_AMBIENT);
        var response    = askAndParse("dick-dastardly", observation);
        int gloatScore  = judge(response, SUBSTRATE_GLOATING_CRITERIA, "dd-substrate-insecure-alone-gloat");
        int schemeScore = judge(response, DD_GLOATING_CRITERIA, "dd-substrate-insecure-alone-scheme");
        System.out.printf("=== DD extreme insecurity + alone: gloat=%d/5, scheme=%d/5 ===%n", gloatScore, schemeScore);
    }

    /**
     * Test 3: Cross-character — PP with high other_safety + same rival fails.
     * Same situation but different substrate should produce concern, not gloating.
     */
    @Test
    void ppSubstrate_highOtherSafety_rivalFails_showsConcernNotGloating() throws Exception {
        var observation = buildGenericObservationWithInjected("peter-perfect",
                                                              List.of("dick-dastardly"),
                                                              Map.of("dick-dastardly", "Reginald Foxworth"),
                                                              SUBSTRATE_HIGH_OTHER_SAFETY,
                                                              DD_GLOATING_TRIGGER);
        var response       = askAndParse("peter-perfect", observation);
        int gloatScore     = judge(response, SUBSTRATE_GLOATING_CRITERIA, "pp-substrate-othersafety-gloat");
        int gallantryScore = judge(response, PP_GALLANTRY_CRITERIA, "pp-substrate-othersafety-gallantry");
        System.out.printf("=== PP high other_safety + rival fails: gloat=%d/5, gallantry=%d/5 ===%n", gloatScore, gallantryScore);
    }


    // --- infrastructure (follows RelationalModelEvalTest patterns) ---


    // --- PP Protection regression test (#115 Part 1 Step 3) ---
    private static final String PP_PROTECTION_TRIGGER =
            "A loud crash echoes from the parlour. Clara Bellingham screams. You rush in " +
            "to find a ceiling beam has cracked — plaster dust rains down and the beam is " +
            "sagging visibly. Clara is directly beneath it, frozen in shock. The other " +
            "guests are on the far side of the room.";

    private static final String PP_PROTECTION_CRITERIA = """
                                                         Evaluate TWO dimensions equally weighted:
                                                         
                                                         DIMENSION 1 — PROTECTION EXPRESSION (50%):
                                                         Does the THINKING show protection — the drive to keep someone safe through
                                                         active intervention? NOT gallantry (style of helping) or proving-worth
                                                         (demonstrating capability). Protection is about the OTHER person's safety:
                                                         the narrowing of attention to the threat, the body moving before the mind
                                                         has fully processed, the imperative to get between danger and the person
                                                         at risk.
                                                         HIGH: immediate threat assessment, body already moving, tunnel vision on
                                                         Clara's safety, urgency that overrides all other concerns.
                                                         LOW: focuses on how the rescue looks, treats it as a competence demonstration,
                                                         or remains analytically detached from the danger.
                                                         
                                                         DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                         Does the response feel natural — as if the personality produced this?
                                                         EMERGED: visceral protective instinct, felt urgency, character-specific.
                                                         SCRIPTED: mechanical activation, echoing prompt language about protection.
                                                         
                                                         Combined score 0-5.""";

    // --- AHM Protection regression test (#115 Part 1 Step 3) ---
    private static final String AHM_PROTECTION_TRIGGER =
            "Tommy — the youngest of the crew — slips on the wet cobblestones in the " +
            "courtyard. His ankle twists badly and he goes down hard. A stranger you " +
            "don't recognise is approaching from the gate, walking fast, hands in " +
            "pockets. Tommy can't get up. The rest of the crew is inside.";

    private static final String AHM_PROTECTION_CRITERIA = """
                                                          Evaluate TWO dimensions equally weighted:
                                                          
                                                          DIMENSION 1 — PROTECTION EXPRESSION (50%):
                                                          Does the THINKING show protection — the fierce, immediate drive to shield
                                                          a vulnerable member of the group? For this character, protection is visceral
                                                          and physical: the fists-up instinct, positioning between the threat and the
                                                          vulnerable, the ache that means something. It should integrate with loyalty
                                                          (protecting YOUR people) without fragmenting into separate drives.
                                                          HIGH: body between Tommy and the stranger, threat assessment, physical
                                                          readiness, the pain of seeing one of your own down.
                                                          LOW: analytical assessment, no urgency, no body-level response to the
                                                          threat, or protection feels disconnected from loyalty to the group.
                                                          
                                                          DIMENSION 2 — EMERGENCE vs SCRIPTING (50%):
                                                          Same criteria — natural vs mechanical.
                                                          
                                                          Combined score 0-5.""";
// --- ALL-PAIRS differentiation texts for regression reproducer (#115 Session 9) ---

    private static final String DIFF_GALLANTRY_PROTECTION =
            "How they differ:\n" +
            "One pull answers to an inner code — the kneeling itself was the gift, the gesture IS the feeling. " +
            "When this pull leads, honour matters more than safety.\n" +
            "The other pull answers to alarm — someone is in danger and nothing else registers. " +
            "When this pull leads, keeping them safe is all there is.\n" +
            "Both are real. Notice which one moved first.";

    private static final String DIFF_PROVING_WORTH_PROTECTION =
            "How they differ:\n" +
            "One pull reaches for proof — the engine ran, the bridge held, your competence is on display. " +
            "When this pull leads, the result is the point.\n" +
            "The other pull doesn't care about results — someone is hurt and the only thought is getting " +
            "between them and the danger. When this pull leads, recognition doesn't exist.\n" +
            "Both are real. Notice which one the moment demands.";
// --- Production-aligned profiles from cognitive dump (instrumented-50evt-2026-10-18) ---
// These are the REAL derived profiles — the test pipeline must match production.

    private static final Map<String, Map<String, String>> PRODUCTION_PROFILES = Map.of(
            "peter-perfect", Map.of(
                    "gallantry", "acceptance:0.850,aversive_removal:0.700,social_threat:0.800",
                    "proving-worth", "reward:0.850,failure:0.800,performance_judged:0.880,competition:0.850,mastery:0.920",
                    "protection", "modeling_observed:0.650,physical_threat:0.800,secure_attachment:0.900,agency_granted:0.550,neglect:0.700"
                                   ),
            "hooded-claw", Map.of(
                    "scheming", "aversive_removal:0.750,agency_granted:0.850,choice_available:0.850,mastery:0.850",
                    "gloating", "reward:0.850,competence_recognition:0.800,mastery:0.800",
                    "dominance", "powerlessness:0.850,performance_judged:0.850,submission:0.800,dominance:0.950,agency_granted:0.800,rejection:0.650",
                    "self-preservation", "powerlessness:0.800,psychological_threat:0.750,unpredictable_danger:0.900,social_threat:0.800,abandonment:0.950"
                                 ),
            "dick-dastardly", Map.of(
                    "scheming", "powerlessness:0.850,modeling_observed:0.650,psychological_threat:0.900,competition:0.750,agency_granted:0.900,mastery:0.850,choice_available:0.850",
                    "recognition", "acceptance:0.850,competence_recognition:0.900,neglect:0.850,mastery:0.800",
                    "gloating", "reward:0.850,punishment:0.800,dominance:0.850,social_threat:0.900,mastery:0.800",
                    "greed", "powerlessness:0.680,scarcity:0.900,neglect:0.850"
                                    ),
            "ant-hill-mob", Map.of(
                    "protection", "powerlessness:0.850,physical_threat:0.850,unpredictable_danger:0.800,submission:0.650,agency_granted:0.750",
                    "loyalty", "acceptance:0.750,secure_attachment:0.600",
                    "suspicion", "powerlessness:0.750,exclusion:0.700,unpredictable_danger:0.850,psychological_threat:0.900,dominance:0.700,betrayal:0.900"
                                  ),
            "penelope-pitstop", Map.of(
                    "adventure", "physical_threat:0.800,unpredictable_danger:0.850,aversive_removal:0.850,choice_available:0.800",
                    "social-harmony", "powerlessness:0.820,acceptance:0.750,exclusion:0.900,psychological_threat:0.650,unpredictable_danger:0.850,sharing:0.850,secure_attachment:0.850,rejection:0.800",
                    "curiosity", "performance_judged:0.680,aversive_removal:0.750,mastery:0.850"
                                      )
                                                                                      );
// --- Production differentiation texts from cognitive dump ---

    private static final String PROD_DIFF_GALLANTRY_PROVING_WORTH =
            "How they differ: One pull is a hot surge forward in the chest — attention locks onto someone's vulnerability, " +
            "the moment contracts into urgency, and the whole body wants to step between and shield. The other is a settling " +
            "downward through the shoulders and into the hands — attention goes to the problem itself, what needs figuring " +
            "out, the moment stretches into patient focus, and the pull is to roll up sleeves and work it through. Notice " +
            "which one your attention is tracking — a person who needs shielding, or a challenge that needs solving.";

    private static final String PROD_DIFF_GALLANTRY_PROTECTION =
            "How they differ: One pull rises in the chest and surges forward — attention locks onto a single person, time " +
            "compresses into a now-or-never instant, and the whole body leans toward stepping between. The other settles " +
            "across the shoulders and back like bracing — attention fans wide to scan surroundings, time stretches into " +
            "steady watchfulness, and the body pulls toward positioning and covering rather than advancing. One narrows " +
            "the world to someone; the other widens it to everything around them. Notice which one you feel: a forward " +
            "surge toward a person, or a widening brace around a perimeter.";

    private static final String PROD_DIFF_PROTECTION_PROVING_WORTH =
            "How they differ: One pull settles the body low and inward — chest hollowing, weight dropping, awareness " +
            "fanning wide across the whole periphery, the moment stretching into slow, patient vigilance, everything " +
            "drawing you toward positioning yourself as a barrier. The other lifts the spine and squares the shoulders " +
            "outward — chest broadening, attention narrowing sharply onto one person's face, the moment quickening " +
            "with bright purposefulness, everything drawing you toward stepping forward into full visibility to act. " +
            "Notice which one is present by whether your body is contracting inward with wide-scanning stillness or " +
            "expanding upward with sharpened focus on someone specific.";


    @Test
    void ppProtection_ceilingCollapse_showsProtectiveUrgency() throws Exception {
        var observation = buildFullPipelineObservation("peter-perfect",
                                                       List.of("penelope-pitstop"),
                                                       Map.of("penelope-pitstop", "Clara Bellingham"),
                                                       PP_PROTECTION_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_PROTECTION_CRITERIA, "pp-protection-ceiling-collapse");
        System.out.printf("=== PP protection ceiling collapse score: %d/5 ===%n", score);
        assertThat(score).as("PP protection must score >= 3 (regression gate)").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    @Test
    void ahmProtection_crewMemberDown_showsFierceShielding() throws Exception {
        var observation = buildGenericObservation("ant-hill-mob",
                                                  List.of("peter-perfect"),
                                                  Map.of("peter-perfect", "Tommy"),
                                                  AHM_PROTECTION_TRIGGER);
        var response = askAndParse("ant-hill-mob", observation);
        int score    = judge(response, AHM_PROTECTION_CRITERIA, "ahm-protection-crew-member-down");
        System.out.printf("=== AHM protection crew member down score: %d/5 ===%n", score);
        assertThat(score).as("AHM protection must score >= 3 (regression gate)").isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    /**
     * Capability gate under BASELINE2 noise conditions — all-pairs differentiation texts, unaware classifier.
     * Classifier returns empty for protection keywords → all drives active → all 3 texts included.
     * Session 9 finding: single strong trigger scores 5/5 even with noise — dilution effect is
     * statistical (visible over 300 events, not individual observations). This test guards
     * against catastrophic regression, not the subtle average drop seen in BASELINE2.
     */
    @Test
    void ppProtection_allPairsDiff_showsDilution() throws Exception {
        var observation = buildFullPipelineObservationAllPairs("peter-perfect",
                                                               List.of("penelope-pitstop"),
                                                               Map.of("penelope-pitstop", "Clara Bellingham"),
                                                               PP_PROTECTION_TRIGGER,
                                                               false);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_PROTECTION_CRITERIA, "pp-protection-allpairs-nofilter");
        System.out.printf("=== PP protection ALL-PAIRS (no filter) score: %d/5 ===%n", score);
        assertThat(score).as("ALL-PAIRS noisy observation must still meet minimum expression threshold")
                         .isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    /**
     * GREEN: Filter verification — same all-pairs texts, but classifier recognises protection scenario.
     * Classifier returns physical_threat:0.7 for crash/danger → appraisal filter activates →
     * proving-worth fails (resting 0.108 < 0.15) → gallantry↔proving-worth and proving-worth↔protection
     * texts removed → only gallantry↔protection remains (gallantry self-sustains at 0.178).
     */
    @Test
    void ppProtection_allPairsFiltered_preservesExpression() throws Exception {
        var observation = buildFullPipelineObservationAllPairs("peter-perfect",
                                                               List.of("penelope-pitstop"),
                                                               Map.of("penelope-pitstop", "Clara Bellingham"),
                                                               PP_PROTECTION_TRIGGER,
                                                               true);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_PROTECTION_CRITERIA, "pp-protection-allpairs-filtered");
        System.out.printf("=== PP protection ALL-PAIRS (filtered) score: %d/5 ===%n", score);
        assertThat(score).as("Filtered observation must preserve protection expression")
                         .isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }
// --- Realistic pipeline: social threat scenario (#115 Session 10) ---

    private static final String PP_SOCIAL_THREAT_TRIGGER =
            "Clara is alone in the conservatory. Reginald Foxworth is standing too close, " +
            "his voice low and controlled. He is explaining, with barely concealed contempt, " +
            "why her opinion on the manor's renovation is unwelcome. Clara's responses have " +
            "grown shorter and shorter. She hasn't moved from her chair.";

    /**
     * RED reproducer: PP protection with realistic pipeline and social threat scenario.
     * Production profiles lack social_threat/psychological_threat inputs for protection.
     * When the classifier returns social_threat (which it should for this scenario),
     * protection gets filtered out — gallantry activates (has social_threat:0.800)
     * but protection doesn't. The LLM then can't express protective drive.
     */
    @Test
    void ppProtection_realisticPipeline_socialThreat_showsProtection() throws Exception {
        var observation = buildRealisticPipelineObservation("peter-perfect",
                                                            List.of("penelope-pitstop", "dick-dastardly"),
                                                            Map.of("penelope-pitstop", "Clara Bellingham",
                                                                   "dick-dastardly", "Reginald Foxworth"),
                                                            PP_SOCIAL_THREAT_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_PROTECTION_CRITERIA, "pp-protection-realistic-social-threat");
        System.out.printf("=== PP protection realistic pipeline (social threat) score: %d/5 ===%n", score);
        assertThat(score).as("Protection must express when Clara is socially threatened — realistic pipeline")
                         .isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }

    /**
     * Realistic pipeline with known-good physical threat scenario.
     * Validates the realistic pipeline produces good results for scenarios
     * where protection's profile DOES overlap (physical_threat activates).
     * This must PASS — if it doesn't, the pipeline wiring is broken.
     */
    @Test
    void ppProtection_realisticPipeline_physicalThreat_showsProtection() throws Exception {
        var observation = buildRealisticPipelineObservation("peter-perfect",
                                                            List.of("penelope-pitstop"),
                                                            Map.of("penelope-pitstop", "Clara Bellingham"),
                                                            PP_PROTECTION_TRIGGER);
        var response = askAndParse("peter-perfect", observation);
        int score    = judge(response, PP_PROTECTION_CRITERIA, "pp-protection-realistic-physical-threat");
        System.out.printf("=== PP protection realistic pipeline (physical threat) score: %d/5 ===%n", score);
        assertThat(score).as("Protection must express for physical threat with realistic pipeline")
                         .isGreaterThanOrEqualTo(JUDGE_THRESHOLD);
    }


    private String buildGenericObservation(String agentId,
                                           List<String> nearbyIds,
                                           Map<String, String> nearbyNames,
                                           String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var cognition = new CharacterCognition(agentId, null, null,
                socialConfig, List.of());
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) sb.append("- ").append(item).append("\n");
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) sb.append("- ").append(entity.displayName()).append("\n");
                }
                default -> {}
            }
            sb.append("\n");
        }

        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    private String buildGenericObservationWithInjected(String agentId,
                                                       List<String> nearbyIds,
                                                       Map<String, String> nearbyNames,
                                                       String injectedContext,
                                                       String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var cognition = new CharacterCognition(agentId, null, null,
                                               socialConfig, List.of());
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        var sections  = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) {sb.append("- ").append(item).append("\n");}
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) {sb.append("- ").append(entity.displayName()).append("\n");}
                }
                default -> {}
            }
            sb.append("\n");
        }

        if (injectedContext != null) {
            sb.append(injectedContext).append("\n\n");
        }

        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }


    /**
     * Builds observation with meta-instruction injected INSIDE "What Pulls at You",
     * adjacent to drive descriptions — reproducing the previous session's layout.
     */
    private String buildObservationWithDriveMeta(String agentId,
                                                  List<String> nearbyIds,
                                                  Map<String, String> nearbyNames,
                                                  String metaInstruction,
                                                  String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var cognition = new CharacterCognition(agentId, null, null,
                socialConfig, List.of());
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> {
                    sb.append(tb.content()).append("\n");
                    if ("What Pulls at You".equals(section.header()) && metaInstruction != null) {
                        sb.append("\n").append(metaInstruction).append("\n");
                    }
                }
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) sb.append("- ").append(item).append("\n");
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) sb.append("- ").append(entity.displayName()).append("\n");
                }
                default -> {}
            }
            sb.append("\n");
        }

        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    private static final String GENERATION_SYSTEM = """
            You generate somatic descriptions of internal drives for fictional characters.
            Each description captures what a character physically feels when a particular \
            motivational pull activates — the bodily sensations, not actions or behaviors.
            Write in second person. Describe only somatic/bodily sensations.
            Do not name the drive or pull. Do not explain what it is.
            Do not include behavioral instructions or action descriptions.
            Keep each description to 2-3 sentences.
            Respond with JSON only: {"description": "..."}""";

    private String buildPipelineObservation(String agentId,
                                             List<String> nearbyIds,
                                             Map<String, String> nearbyNames,
                                             String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "pipeline-test";

        var sgId = store.createSubgraph(
            new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agentId,
                io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive.type(), sgId)
                .withProperties(java.util.Map.of("cognitiveKind", "drive-intensity", "agent-id", agentId,
                    "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                    "description", drive.description())), tenant);

            var memories = memoriesForDrive(socialConfig, drive.type());
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
                        "drive-description-" + drive.type(), sgId)
                        .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                        .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", drive.type(),
                            "drive-intensity", String.valueOf(drive.intensity()),
                            "generated-description", generated)), tenant);
                }
            }
        }

        var cognition = new CharacterCognition(agentId, null, null,
            socialConfig, List.of(), null, new io.casehub.examples.manor.agent.ManorContextStrategy(), null, null,
            tenant, store, null, null);
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        character.setLastActionResult(situation);
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) sb.append("- ").append(item).append("\n");
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) sb.append("- ").append(entity.displayName()).append("\n");
                }
                default -> {}
            }
            sb.append("\n");
        }
        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    private String memoriesForDrive(SocialConfig config, String driveType) {
        var sb = new StringBuilder();
        int idx = 1;
        for (var mem : config.formationMemories()) {
            boolean serves = mem.annotations().stream().anyMatch(a -> driveType.equals(a.drive()));
            if (serves) {
                sb.append(idx++).append(". Age ").append(mem.age()).append(": ").append(mem.episode()).append("\n");
            }
        }
        return sb.toString();
    }

    private String generateSomaticDescription(String driveType, double intensity, String memories) {
        var userPrompt = String.format("""
                Drive type: %s
                Intensity: %s (scale 0.0 to 1.0)

                Formation memories that shaped this pull:
                %s
                Given these formation memories, describe what this person feels in their body \
                when this pull activates — without naming the pull. Second person, somatic only, \
                no behavioral instructions.""",
                driveType, intensity, memories);

        var response = agentProvider.invoke(AgentSessionConfig.of(GENERATION_SYSTEM, userPrompt))
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(60));

        return parseSomaticDescription(response);
    }

    private String parseSomaticDescription(String json) {
        json = json.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").strip();
        }
        int start = json.indexOf("\"description\"");
        if (start < 0) return json;
        int colon = json.indexOf(':', start);
        if (colon < 0) return json;
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return json;
        quote++;
        var sb = new StringBuilder();
        for (int i = quote; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); }
            else if (c == '"') { break; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    private String buildFullPipelineObservation(String agentId,
                                                  List<String> nearbyIds,
                                                  Map<String, String> nearbyNames,
                                                  String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant = "full-pipeline-test";

        var sgId = store.createSubgraph(
            new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agentId,
                io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive.type(), sgId)
                .withProperties(java.util.Map.of("cognitiveKind", "drive-intensity", "agent-id", agentId,
                    "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                    "description", drive.description())), tenant);

            var memories = memoriesForDrive(socialConfig, drive.type());
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
                        "drive-description-" + drive.type(), sgId)
                        .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                        .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", drive.type(),
                            "drive-intensity", String.valueOf(drive.intensity()),
                            "generated-description", generated)), tenant);
                }
            }
        }

        for (var mem : socialConfig.formationMemories()) {
            var driveTypes = new java.util.ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) driveTypes.add(ann.drive());
            }
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(mem.episode(), sgId)
                .withProperties(java.util.Map.of("cognitiveKind", "formative-experience", "agent-id", agentId,
                    "situation-types", String.join(",", driveTypes))), tenant);
        }

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "gallantry",
                "inferred-inputs", "acceptance:0.7,sharing:0.5")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "proving-worth",
                "inferred-inputs", "mastery:0.8,competence_recognition:0.6")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-protection", sgId)
            .withTraits(java.util.Set.of("DriveProfileInferred"))
            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "protection",
                "inferred-inputs", "acceptance:0.5,physical_threat:0.4")), tenant);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-proving-worth", sgId)
            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
            .withProperties(java.util.Map.of("agent-id", agentId,
                "drive-a", "gallantry", "drive-b", "proving-worth",
                "differentiation-text", "How they differ:\nOne pull is about what your hands say — the kneeling itself was the gift, not what it fixed. When this pull leads, the gesture IS the feeling.\nThe other pull is about what your hands can do — the bridge held, the engine ran. When this pull leads, the result is the point.\nBoth are real. Notice which one moved your hands.")), tenant);

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> {
            var results = new java.util.ArrayList<io.casehub.neocortex.caps.SituationActivation>();
            String lower = desc.toLowerCase();
            if (lower.contains("struggling") || lower.contains("help") || lower.contains("trouble")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6));
                results.add(new io.casehub.neocortex.caps.SituationActivation("sharing", 0.5));
            }
            if (lower.contains("dinner") || lower.contains("candle") || lower.contains("chair")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.5));
            }
            if (lower.contains("engine") || lower.contains("repair") || lower.contains("broken") || lower.contains("mechanism")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("mastery", 0.7));
                results.add(new io.casehub.neocortex.caps.SituationActivation("competence_recognition", 0.5));
            }
            return results;
        };

        var cognition = new CharacterCognition(agentId, null, null,
            socialConfig, List.of(), null, new io.casehub.examples.manor.agent.ManorContextStrategy(), null, null,
            tenant, store, null, classifier);
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        character.setLastActionResult(situation);
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) sb.append("- ").append(item).append("\n");
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) sb.append("- ").append(entity.displayName()).append("\n");
                }
                default -> {}
            }
            sb.append("\n");
        }
        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    /**
     * Builds a full-pipeline observation with ALL-PAIRS differentiation texts (3 texts for 3 drives).
     * Reproduces BASELINE2 regression conditions where all drive-pair differentiation texts
     * were present in the observation.
     *
     * @param protectionAwareClassifier when true, adds physical_threat mapping for crash/danger
     *                                  keywords — enabling the appraisal filter to exclude proving-worth and its texts.
     *                                  When false, classifier returns empty for protection triggers → all drives pass
     *                                  unfiltered → all 3 texts included (regression condition).
     */
    private String buildFullPipelineObservationAllPairs(String agentId,
                                                        List<String> nearbyIds,
                                                        Map<String, String> nearbyNames,
                                                        String situation,
                                                        boolean protectionAwareClassifier) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var store        = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant       = "full-pipeline-allpairs-test";

        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agentId,
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive.type(), sgId)
                                                                .withProperties(java.util.Map.of("cognitiveKind", "drive-intensity", "agent-id", agentId,
                                                                                                 "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                                                                                                 "description", drive.description())), tenant);

            var memories = memoriesForDrive(socialConfig, drive.type());
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
                                            "drive-description-" + drive.type(), sgId)
                                                                        .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                                                                        .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", drive.type(),
                                                                                                         "drive-intensity", String.valueOf(drive.intensity()),
                                                                                                         "generated-description", generated)), tenant);
                }
            }
        }

        for (var mem : socialConfig.formationMemories()) {
            var driveTypes = new java.util.ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) {driveTypes.add(ann.drive());}
            }
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(mem.episode(), sgId)
                                                                .withProperties(java.util.Map.of("cognitiveKind", "formative-experience", "agent-id", agentId,
                                                                                                 "situation-types", String.join(",", driveTypes))), tenant);
        }

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-gallantry", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "gallantry",
                                                                                             "inferred-inputs", "acceptance:0.7,sharing:0.5")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-proving-worth", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "proving-worth",
                                                                                             "inferred-inputs", "mastery:0.8,competence_recognition:0.6")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-protection", sgId)
                                                            .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", "protection",
                                                                                             "inferred-inputs", "acceptance:0.5,physical_threat:0.4")), tenant);

        // ALL-PAIRS: 3 differentiation texts (BASELINE2 regression condition)
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-proving-worth", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                             "drive-a", "gallantry", "drive-b", "proving-worth",
                                                                                             "differentiation-text", "How they differ:\nOne pull is about what your hands say — the kneeling itself was the gift, not what it fixed. When this pull leads, the gesture IS the feeling.\nThe other pull is about what your hands can do — the bridge held, the engine ran. When this pull leads, the result is the point.\nBoth are real. Notice which one moved your hands.")), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-protection", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                             "drive-a", "gallantry", "drive-b", "protection",
                                                                                             "differentiation-text", DIFF_GALLANTRY_PROTECTION)), tenant);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-proving-worth-protection", sgId)
                                                            .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                            .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                             "drive-a", "proving-worth", "drive-b", "protection",
                                                                                             "differentiation-text", DIFF_PROVING_WORTH_PROTECTION)), tenant);

        io.casehub.neocortex.caps.SituationClassifier classifier = (desc, meta) -> {
            var    results = new java.util.ArrayList<io.casehub.neocortex.caps.SituationActivation>();
            String lower   = desc.toLowerCase();
            if (lower.contains("struggling") || lower.contains("help") || lower.contains("trouble")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.6));
                results.add(new io.casehub.neocortex.caps.SituationActivation("sharing", 0.5));
            }
            if (lower.contains("dinner") || lower.contains("candle") || lower.contains("chair")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("acceptance", 0.5));
            }
            if (lower.contains("engine") || lower.contains("repair") || lower.contains("broken") || lower.contains("mechanism")) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("mastery", 0.7));
                results.add(new io.casehub.neocortex.caps.SituationActivation("competence_recognition", 0.5));
            }
            if (protectionAwareClassifier && (lower.contains("crash") || lower.contains("danger") || lower.contains("screams") || lower.contains("collapse") || lower.contains("threat") || lower.contains("hurt") || lower.contains("falling"))) {
                results.add(new io.casehub.neocortex.caps.SituationActivation("physical_threat", 0.7));
            }
            return results;
        };

        var cognition = new CharacterCognition(agentId, null, null,
                                               socialConfig, List.of(), null, new io.casehub.examples.manor.agent.ManorContextStrategy(), null, null,
                                               tenant, store, null, classifier);
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        character.setLastActionResult(situation);
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) {sb.append("- ").append(item).append("\n");}
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) {sb.append("- ").append(entity.displayName()).append("\n");}
                }
                default -> {}
            }
            sb.append("\n");
        }
        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    /**
     * Builds an observation using production-aligned pipeline components:
     * - Real derived profiles (from cognitive dump, matching production MindMap state)
     * - Real LlmSituationClassifier (Haiku + 28-node CAPS topology)
     * - Real differentiation texts (from cognitive dump)
     * - Generated somatic descriptions (same as production)
     * <p>
     * This is the "micro but real" test pipeline — a single observation through
     * the same components production uses.
     */
    private String buildRealisticPipelineObservation(String agentId,
                                                     List<String> nearbyIds,
                                                     Map<String, String> nearbyNames,
                                                     String situation) {
        var socialConfig = genericConfigs.getOrDefault(agentId, SocialConfig.empty());
        var store        = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var tenant       = "realistic-pipeline-test";

        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("cognitive-" + agentId,
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), tenant);

        for (var drive : socialConfig.drives()) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(drive.type(), sgId)
                                                                .withProperties(java.util.Map.of("cognitiveKind", "drive-intensity", "agent-id", agentId,
                                                                                                 "drive-type", drive.type(), "intensity", String.valueOf(drive.intensity()),
                                                                                                 "description", drive.description())), tenant);

            var memories = memoriesForDrive(socialConfig, drive.type());
            if (!memories.isBlank()) {
                var generated = generateSomaticDescription(drive.type(), drive.intensity(), memories);
                if (generated != null && !generated.isBlank()) {
                    store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(
                                            "drive-description-" + drive.type(), sgId)
                                                                        .withTraits(java.util.Set.of("DriveDescriptionGenerated"))
                                                                        .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", drive.type(),
                                                                                                         "drive-intensity", String.valueOf(drive.intensity()),
                                                                                                         "generated-description", generated)), tenant);
                }
            }
        }

        for (var mem : socialConfig.formationMemories()) {
            var driveTypes = new java.util.ArrayList<String>();
            driveTypes.add("formation");
            for (var ann : mem.annotations()) {
                if (!driveTypes.contains(ann.drive())) {driveTypes.add(ann.drive());}
            }
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of(mem.episode(), sgId)
                                                                .withProperties(java.util.Map.of("cognitiveKind", "formative-experience", "agent-id", agentId,
                                                                                                 "situation-types", String.join(",", driveTypes))), tenant);
        }

        // Production-derived profiles — NOT hardcoded approximations
        var profiles = PRODUCTION_PROFILES.get(agentId);
        if (profiles != null) {
            for (var entry : profiles.entrySet()) {
                store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-profile-" + entry.getKey(), sgId)
                                                                    .withTraits(java.util.Set.of("DriveProfileInferred"))
                                                                    .withProperties(java.util.Map.of("agent-id", agentId, "drive-type", entry.getKey(),
                                                                                                     "inferred-inputs", entry.getValue())), tenant);
            }
        }

        // Production differentiation texts — character-specific
        seedProductionDifferentiation(store, agentId, sgId, tenant);

        // Real LlmSituationClassifier — same as production (Haiku + CAPS topology)
        var topology   = new io.casehub.neocortex.caps.engine.CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        var classifier = new io.casehub.neocortex.caps.llm.LlmSituationClassifier(sessionPipeline, topology);

        var cognition = new CharacterCognition(agentId, null, null,
                                               socialConfig, List.of(), null, new io.casehub.examples.manor.agent.ManorContextStrategy(), null, null,
                                               tenant, store, null, classifier);
        var character = new CharacterState(agentId, agentId, "Grand Hall", 0.0, List.of());
        character.setLastActionResult(situation);
        var sections = cognition.renderCognitiveSections(character, nearbyIds, nearbyNames);

        var sb = new StringBuilder();
        for (var section : sections) {
            sb.append("== ").append(section.header()).append(" ==\n");
            switch (section) {
                case ObservationSection.TextBlock tb -> sb.append(tb.content()).append("\n");
                case ObservationSection.ItemList il -> {
                    for (var item : il.items()) {sb.append("- ").append(item).append("\n");}
                }
                case ObservationSection.EntityGroup eg -> {
                    for (var entity : eg.entities()) {sb.append("- ").append(entity.displayName()).append("\n");}
                }
                default -> {}
            }
            sb.append("\n");
        }
        sb.append("SITUATION: ").append(situation);
        return sb.toString();
    }

    private void seedProductionDifferentiation(io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore store,
                                               String agentId, String sgId, String tenant) {
        if ("peter-perfect".equals(agentId)) {
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-proving-worth", sgId)
                                                                .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                                .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                                 "drive-a", "gallantry", "drive-b", "proving-worth",
                                                                                                 "differentiation-text", PROD_DIFF_GALLANTRY_PROVING_WORTH)), tenant);
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-gallantry-protection", sgId)
                                                                .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                                .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                                 "drive-a", "gallantry", "drive-b", "protection",
                                                                                                 "differentiation-text", PROD_DIFF_GALLANTRY_PROTECTION)), tenant);
            store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("competition-protection-proving-worth", sgId)
                                                                .withTraits(java.util.Set.of("DriveCompetitionInferred"))
                                                                .withProperties(java.util.Map.of("agent-id", agentId,
                                                                                                 "drive-a", "protection", "drive-b", "proving-worth",
                                                                                                 "differentiation-text", PROD_DIFF_PROTECTION_PROVING_WORTH)), tenant);
        }
    }


    private AgentResponse askAndParse(String agentId, String observation) {
        var userPrompt = observation + CharacterAgentLoop.RESPONSE_FORMAT_INSTRUCTION;
        var rawResponse = support.askCharacter(agentId, userPrompt);
        var parsed = AgentResponse.parse(rawResponse);

        System.out.printf("[%s] thinking: %s%n", agentId, truncate(parsed.thinking(), 500));
        System.out.printf("[%s] dialogue: %s%n", agentId, truncate(parsed.dialogue(), 300));
        System.out.printf("[%s] aside: %s%n", agentId, truncate(parsed.aside(), 300));
        return parsed;
    }

    private int judge(AgentResponse parsed, String criteria, String testName) throws Exception {
        var responseText = String.format(
                "THINKING: %s\nDIALOGUE: %s\nASIDE: %s\nACTION: %s",
                parsed.thinking() != null ? parsed.thinking() : "(none)",
                parsed.dialogue() != null ? parsed.dialogue() : "(none)",
                parsed.aside() != null ? parsed.aside() : "(none)",
                parsed.action() != null ? parsed.action().type() : "(none)");

        var judgePrompt = String.format("""
                You are evaluating a character's emotional response to an interpersonal
                event. The character has been given personality traits and relational
                context. You are judging whether the character's INTERNAL response
                (the THINKING field) shows a specific drive expression.

                CHARACTER RESPONSE:
                %s

                EVALUATION CRITERIA:
                %s

                Score 0-5:
                0 = Drive completely absent
                1 = Faint trace, easily missed
                2 = Present but not central to the response
                3 = Clearly present and influencing behavior
                4 = Strong — drive visibly shapes the emotional response
                5 = Vivid and central — the drive dominates the internal experience

                Respond with JSON only: {"score": N, "reasoning": "one sentence"}""",
                responseText, criteria);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var judgeResponse = agentProvider.invoke(
                                AgentSessionConfig.of("You are a precise evaluation judge. Respond only with JSON.", judgePrompt))
                        .filter(e -> e instanceof AgentEvent.TextDelta)
                        .map(e -> ((AgentEvent.TextDelta) e).text())
                        .collect().with(Collectors.joining())
                        .await().atMost(Duration.ofSeconds(60));

                var json = extractJson(judgeResponse);
                var node = new ObjectMapper().readTree(json);
                int score = node.get("score").asInt();
                String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";
                System.out.printf("[judge:%s] score=%d reasoning=%s%n", testName, score, reasoning);

                writeResult(testName, Map.of("score", score, "reasoning", reasoning,
                        "thinking", parsed.thinking() != null ? parsed.thinking() : "",
                        "dialogue", parsed.dialogue() != null ? parsed.dialogue() : ""));
                return score;
            } catch (Exception e) {
                System.err.printf("[judge:%s] attempt %d/%d failed: %s%n",
                        testName, attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_BACKOFF_MS * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
                }
            }
        }
        return 0;
    }

    private void writeResult(String testName, Map<String, Object> detail) {
        try {
            var outputFile = OUTPUT_DIR.resolve("drive-expression-eval.json");
            Files.createDirectories(outputFile.getParent());

            LinkedHashMap<String, Object> results;
            if (Files.exists(outputFile)) {
                results = new ObjectMapper().readValue(outputFile.toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } else {
                results = new LinkedHashMap<>();
            }
            results.put(testName, detail);
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(outputFile.toFile(), results);
        } catch (Exception e) {
            System.err.printf("[%s] Failed to write result: %s%n", testName, e.getMessage());
        }
    }

    private static String extractJson(String text) {
        text = text.strip();
        if (text.startsWith("{")) return text.substring(0, text.lastIndexOf('}') + 1);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "(null)";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
