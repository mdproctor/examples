# Character Emergence Findings

Systematic experiment to understand how LLM character agents develop and sustain distinctive behavior. Conducted on the Wacky Manor multi-agent scenario (issue #97).

## Objective

Starting question: which elements of the character taxonomy (drives, tendencies, speech-patterns, constraints, disposition) are load-bearing vs redundant? Strip elements iteratively, measure behavioral drift via 300+ event autonomous scenario runs.

This evolved into a broader investigation: what minimal instruction replaces explicit behavioral prescriptions, and how do we sustain character consistency over long conversations? The work progressed through six phases — ablation, load-bearing identification, metacognitive instruction design, emotional persistence architecture, cross-run classification, and long-run scale testing.

## Baselines

Each baseline represents the best result that improves on its predecessor in **all situations at all times** — not just on average, not just for some characters. Partial improvements are regressions.

| Baseline | Description | Profile | Mean drive expression | Established |
|---|---|---|---|---|
| **BASELINE0** | Full brief — all tendencies, no metacognitive instruction | GENERIC | 3.93 | Phase 6 (retrospective classification) |
| **BASELINE1** | *Not yet achieved* — L1 emotional echo improves the mean but regresses on 4/17 drives | — | — | — |

### BASELINE0 — full brief (the starting point)

Original GENERIC profile with all tendencies, voice sections, drives, and constraints. No metacognitive instruction. This is what the entire experiment improved from.

Transcript: `generic-colloquial-20261002/` (310 events, 206 classified)

| Character | Mean | Drive scores |
|---|---|---|
| Hooded Claw | 3.80 | scheming 4.82, gloating 3.97, dominance 3.89, self-preservation 2.53 |
| Hartwell | 4.06 | gallantry 4.50, proving-worth 4.22, protection 3.46 |
| Penelope | 3.74 | curiosity 4.30, adventure 3.75, social-harmony 3.18 |
| Foxworth | 3.93 | scheming 4.42, greed 4.34, gloating 3.50, recognition 3.44 |
| Ant-hill-mob | 4.16 | protection 4.63, loyalty 4.24, suspicion 3.61 |

### L1 emotional echo — best candidate for BASELINE1

Mean 4.06 (vs BASELINE0's 3.93). Improves 13 of 17 drives. But regresses on 4:

| Character | Drive | BASELINE0 | L1 | Delta | Why |
|---|---|---|---|---|---|
| Hartwell | protection | **3.46** | 2.33 | **-1.13** | Full brief had explicit protective tendency; L1 stripped it. Protection grows over the run (+0.86) but starts too low |
| Foxworth | gloating | **3.50** | 3.02 | **-0.48** | Secondary drive — explicit tendency gave it more weight than drives alone |
| Foxworth | recognition | **3.44** | 3.07 | **-0.37** | Same — explicit tendency provided more emphasis than drive description |
| Penelope | social-harmony | **3.18** | 2.85 | **-0.33** | Explicit tendency reinforced social awareness; without it, puzzle-focus dominates |

**To achieve BASELINE1:** recover these 4 drives without losing the gains on the other 13. Possible approaches: richer drive descriptions (emotional-core framing), drive intensity tuning, selective tendency restoration for load-bearing drives. Must be validated before Phase 7 changes capabilities further.

## Method

Each round removes one category of taxonomy element from the GENERIC profile (renamed characters, no pop-culture priors). Run the autonomous scenario, capture full transcript, measure behavioral markers against baseline. The BASELINE profile (Wacky Races characters) runs in parallel where relevant to detect model-prior compensation.

Behavioral markers measured per character:
- **Scheme narration rate** (villains): percentage of aside events containing step-by-step scheme narration keywords
- **Planning rate** (Hartwell): percentage of events with planning keywords
- **Optimism rate** (Hartwell): percentage of events with optimistic determination keywords
- **Third-person narration rate** (Hartwell): percentage of events using self-referential third-person

All transcripts are preserved in `wacky-manor/docs/eval/` and each configuration change is a separate git commit.

## Phase 1 — What's Redundant? (Rounds 1–2b)

### Round 1 — Strip 7 redundant directives

**Removed:** 7 tendencies that restated what drives or disposition already specified (e.g., "scheme elaborately" when drives already include scheming at 0.9).

**Result:** Zero behavioral drift across all characters.

**Conclusion:** Drives are the authoritative source for personality traits. Tendencies that restate them are pure redundancy.

### Round 2 — Strip ALL villain tendencies

**Removed:** All tendencies from Marsh (hooded-claw) and Foxworth (dick-dastardly).

**Result:** Asymmetric degradation.

| Character | Profile | Scheme narration baseline | After strip | Delta |
|---|---|---|---|---|
| Marsh | GENERIC | ~50% | ~14% | -36pp |
| Hooded Claw | BASELINE | ~50% | ~31% | -19pp |

The BASELINE profile degraded less — the model has Wacky Races priors that partially compensate.

**Key finding:** "Explain schemes step by step" is a *storytelling convention*, not a personality trait. Conventions don't emerge from drives alone.

### Round 2b — Encode narration convention in drive description

**Changed:** Added "and narrate them step by step aloud" to the scheming drive description.

**Result:** Recovered Marsh scheme narration, but only at drive intensity 0.85+. Foxworth at 0.7 did not recover; bumped to 0.85 and confirmed (29.6% in subsequent runs).

**Key finding:** Drive descriptions can carry storytelling conventions, but only above an intensity threshold (~0.85).

### Emotional-core experiment

**Changed:** Rewrote Marsh's drive descriptions from behavioral directives to internal emotional states:
- "Compelled to hatch elaborate plans" → "You feel contemptuous glee when outsmarting someone"
- "Must protect cover at all costs" → "You feel paranoid vigilance when your cover might be exposed"

**Result (375 events):** Richer emotional vocabulary (2x unique emotion words), more varied self-characterisation. But not a dramatic transformation — both framings produce good theatrical villainy.

## Phase 2 — What's Load-Bearing? (Round 3)

### Round 3 — Strip Hartwell tendencies

**Removed:** Two Peter/Hartwell tendencies:
- "You plan obsessively before acting"
- "You maintain optimistic determination when things go wrong"

The speech-pattern still said "narrates his own actions in the third person."

**Result (325 events):** Asymmetric degradation across markers.

| Marker | Baseline | R3 (stripped) | Delta | Verdict |
|---|---|---|---|---|
| Planning | 16.7% | 26.1% | +9.4pp | Survived (drives carry it) |
| Optimism | 24.1% | 4.3% | -19.8pp | Collapsed |
| Third-person | 61.1% | 13.0% | -48.1pp | Collapsed |

**Key findings:**
1. **Tendencies function as character anchors**, not just behavioral instructions.
2. **Speech-patterns alone don't override model defaults.** The speech-pattern still said "narrates in third person" — the model saw it but didn't follow it.
3. **LLMs prioritise goals over personality.** Goal-directed behavior persisted while ambient personality attributes drifted.

## Phase 3 — Can Metacognitive Instructions Replace Tendencies? (Rounds 3b–3g)

This is where the experiment shifted from *what to strip* to *how to compensate*. Each round tests a different instruction in the thinking field, with Hartwell's tendencies still stripped. The question: can the right instruction recover the collapsed markers without restoring the tendencies?

### R3b — Passive review

**Instruction:** "FIRST review your voice (accent, register, speech pattern), drives, and constraints — then reason about the current situation."

**Hypothesis:** The model isn't missing information — the character spec is right there in the system prompt. The problem is inattention. A nudge to actively read it before reasoning should recover the collapsed markers.

**Result (312 events):**

| Marker | Baseline | R3 (none) | R3b (passive) |
|---|---|---|---|
| Planning | 16.7% | 26.1% | 27.3% |
| Optimism | 24.1% | 4.3% | 13.6% |
| Third-person | 61.1% | 13.0% | **61.4%** |

**Third-person: full recovery.** 13→61.4%, back to baseline. One line in the thinking step completely solved the speech-pattern override problem.

**Optimism: partial recovery.** 4.3→13.6%, significant improvement but 10pp below baseline.

**Analysis:** The passive review works for *presentational* attributes (how you sound — accent, speech pattern, third person) because the model can review and mechanically apply them. It doesn't fully work for *dispositional* attributes (how you react — optimism) because those require the model to be in a certain emotional state, not just to remember a rule.

### R3c — Show-workings (structured fields)

**Instruction:** "SHOW YOUR WORKINGS — before reasoning, state: MY ACCENT: [your accent]. MY SPEECH PATTERN: [your pattern]. MY STRONGEST DRIVE: [name and what it feels like]. Then reason."

**Hypothesis:** If the model has to *produce* the character attributes (like a student showing workings), the recall would be stronger than passive review. Filling in structured fields forces actual engagement rather than a nod.

**Result (306 events):**

| Marker | Baseline | R3b (passive) | R3c (workings) |
|---|---|---|---|
| Planning | 16.7% | 27.3% | 15.9% |
| Optimism | 24.1% | 13.6% | 13.6% |
| Third-person | 61.1% | 61.4% | 50.0% |

**Show-workings was WORSE than passive review.** Third-person regressed from 61.4% to 50.0%.

**Analysis:** The model treated filling in "MY SPEECH PATTERN: third person" as *completing the review task* rather than *preparing to apply it*. Structured fields create a satisficing shortcut — the model can pattern-complete the metadata without it influencing downstream generation. The homework analogy breaks for LLMs: a student who shows workings is forced to *think through* the steps. An LLM filling structured fields is just pattern-completing.

**Principle established:** Open-ended prompts outperform structured ones for attribute integration.

### R3d — Hybrid (reason in character + self-evaluate)

**Instruction:** "Reason IN CHARACTER — think as your character would, using your voice, accent, speech pattern, and drives as you think. Then check: did your LAST turn's dialogue reflect your speech pattern and accent? Note any drift and correct it now."

**Hypothesis:** Instead of reviewing attributes analytically, reason *as the character*. The self-evaluation creates a feedback loop — the model sees its own drift assessment next turn and corrects.

**Result (186 events — crashed at tick 14, PULL_ASIDE bug):**

| Marker | Baseline | R3b (passive) | R3d (hybrid)* |
|---|---|---|---|
| Planning | 16.7% | 27.3% | 30.8% |
| Optimism | 24.1% | 13.6% | **23.1%** |
| Third-person | 61.1% | 61.4% | 46.2% |

*Small sample: 26 peter-perfect events.

**Optimism: near-full recovery.** 23.1% — first variant to substantially recover the behavioral disposition. The "reason in character" instruction put the model in the right emotional mode to generate optimistic responses.

**But third-person regressed** to 46.2%. The "reason in character" instruction pulled the model out of the analytical mode needed for objective pattern-checking (is this in third person?).

**Analysis:** Analytical instructions are good for checkable patterns, bad for emotional dispositions. Immersive instructions are the reverse. No single mode wins both.

### R3f — Mental-model-first (plan, review, output)

**Instruction:** "Build your COMPLETE response plan here before writing any other field. Step 1: Review attributes. Step 2: Assess in character. Step 3: Plan dialogue/aside/action. Step 4: Recheck ENTIRE plan against character spec. Step 5: Check last turn for drift. Only then write JSON."

**Hypothesis:** Building a complete mental model before output (like reviewing an academic paper as a whole rather than paragraph by paragraph) should allow multiple in-memory passes that catch both presentational and dispositional drift.

**Result (301 events):**

| Marker | Baseline | R3b (passive) | R3d (hybrid) | R3f (mental-model) |
|---|---|---|---|---|
| Planning | 16.7% | 27.3% | 30.8% | 13.6% |
| Optimism | 24.1% | 13.6% | 23.1% | 9.1% |
| Third-person | 61.1% | 61.4% | 46.2% | **81.8%** |

**Third-person: new high.** 81.8% — exceeded even the baseline with tendencies (61.1%). The structured planning process gave the model maximum analytical control over objective patterns.

**Optimism: worst result.** 9.1% — the multi-step analytical process consumed the thinking budget that immersive reasoning needs for emotional disposition. The model was in "reviewing my plan" mode, not "feeling optimistic" mode.

**Analysis:** Confirms the fundamental tension. More analytical structure → better objective consistency, worse emotional expression. The five-step process is too much structure — it turns character performance into process-following.

### R3g — Minimal evocative (identity activation)

**Instruction:** "Remember who you are — your voice, your way of speaking, your drives. Feel your strongest drive. Think AS your character, not ABOUT your character. If your last turn didn't sound like you, correct it now."

**Hypothesis:** The problem with all previous instructions is mixing analytical and immersive modes. The solution is to abandon analysis entirely and activate identity through evocation. Two sentences, not five steps. Teach a mindset, not a procedure.

The key phrase: "Think AS your character, not ABOUT your character." This explicitly blocks the analytical mode that kills emotional expression while the "remember your voice" phrase activates the presentational patterns.

**Result — initial sample (129 events — crashed at tick 10, PULL_ASIDE bug):**

| Marker | Baseline | R3b (passive) | R3d (hybrid) | R3f (model) | R3g sample* |
|---|---|---|---|---|---|
| Planning | 16.7% | 27.3% | 30.8% | 13.6% | 44.4% |
| Optimism | 24.1% | 13.6% | 23.1% | 9.1% | 22.2% |
| Third-person | 61.1% | 61.4% | 46.2% | 81.8% | 61.1% |

*18 peter-perfect events before PULL_ASIDE crash.

**Full validation run (309 events, 44 Hartwell events):**

| Marker | Baseline | R3g sample (18 evts) | **R3g FULL (44 evts)** |
|---|---|---|---|
| Optimism | 24.1% | 22.2% | **29.5%** |
| Third-person | 61.1% | 61.1% | **88.6%** |

**First variant to exceed baseline on both reliable markers.** Third-person at 88.6% — new all-time high, exceeding R3f's 81.8% ceiling. Optimism at 29.5% — first variant above baseline (24.1%).

**Drift analysis (first half vs second half of Hartwell events):**

| Marker | First half (22 evts) | Second half (22 evts) | Trend |
|---|---|---|---|
| Third-person | 100% | 77.3% | Modest decay, still well above baseline |
| Optimism | 40.9% | 18.2% | Significant decay — halved |

Third-person is self-reinforcing: the model sees its own third-person output in prior turns and perpetuates the pattern. Optimism decays because emotional activation fades as the scenario becomes more action-focused — the evocative instruction primes an emotional state that weakens over time without reinforcement.

**Analysis:** The minimal evocative instruction solves the analytical/immersive tension by not being either. It's identity activation — it primes the model to be the character rather than to think about or review the character. "Feel your strongest drive" puts the model in the right emotional state (optimism recovery). "Remember your voice, your way of speaking" activates presentational patterns (third-person recovery). "Think AS, not ABOUT" prevents the mode-switch that killed one dimension in every structured variant.

**Open question:** The optimism decay (40.9% → 18.2%) suggests that emotional dispositions need periodic re-activation beyond the initial instruction. Presentational patterns (third-person) are self-sustaining because the model's own output reinforces them. Emotional dispositions lack this feedback loop — the model doesn't see "I was optimistic last turn" in the same way it sees "I spoke in third person last turn."

## Phase 4 — Emotional Persistence (Layer 1)

Phase 3 found the right instruction type (evocative identity activation) but revealed a decay problem: emotional dispositions (optimism) weaken over the run while presentational patterns (third-person) self-reinforce. Phase 4 addresses this asymmetry.

### The self-reinforcement asymmetry

Third-person narration self-reinforces through a multi-channel echo: the model writes third-person text → that text is stored as `currentThinking` and fed back next turn → the model sees third-person in its own output → continues the pattern. The output IS the signal.

Emotional dispositions have no equivalent echo. The model generates optimistic dialogue, but that dialogue goes to other characters, not back to itself. The thinking field captures tactical reasoning ("I should check the library"), not emotional state ("I feel optimistic"). Nothing in the observation says "you were optimistic last turn."

### Architectural analysis

Five mechanisms sustain personality consistency in humans. The architecture's coverage:

| Mechanism | Status | Gap |
|---|---|---|
| Trait stability (static drives) | ✓ Present | — |
| State fluctuation (dynamic intensity) | ✗ Missing | Drives are fixed numbers, no per-tick variation |
| Proprioception (awareness of own state) | ✗ Missing | No feedback of expressed emotional state |
| Memory consolidation (emotional memory) | Partial | Memory system exists but doesn't prioritize emotional content |
| Social reinforcement (others react) | ✓ Natural | Happens through the event drain |

Three-layer fix designed:
- **Layer 1:** Thinking-field emotional echo (instruction change — test immediately)
- **Layer 2:** Dynamic personality drive state (new `PersonalityDriveEvaluator` SPI in neocortex)
- **Layer 3:** Emotional proprioception (new `EmotionalProprioceptionStrategy` SPI — LLM classifier)

### Layer 1 — Emotional echo instruction

**Instruction:** "What are you FEELING right now — not thinking, feeling? Name it. Remember your voice, your way of speaking. Think AS your character, not ABOUT your character. If your last turn didn't sound like you, correct it now."

**Hypothesis:** If the model names its emotional state in the thinking field ("I feel protective and determined"), that text is fed back next turn as "Your Current Thinking" — creating the same self-reinforcing echo that third-person enjoys. The key difference from R3c (structured fields): this asks for a single introspective word that varies by context, not a checklist to fill.

**Result (320 events, 42 Hartwell events):**

| Marker | Baseline | R3g full (44 evts) | **Layer 1 (42 evts)** |
|---|---|---|---|
| Optimism | 44.4% | 38.6% | **40.5%** |
| Third-person | 61.1% | 88.6% | **85.7%** |

**Drift analysis (first half vs second half):**

| Marker | R3g 1st half | R3g 2nd half | R3g trend | L1 1st half | L1 2nd half | L1 trend |
|---|---|---|---|---|---|---|
| Third-person | 100% | 77.3% | -22.7pp | 81.0% | 90.5% | **+9.5pp** |
| Optimism | 27.3% | 50.0% | +22.7pp | 52.4% | 28.6% | -23.8pp |

**Third-person stability: Layer 1 is the best yet.** It actually *strengthens* over the run (+9.5pp), while R3g decayed (-22.7pp). The emotional echo instruction improves presentational pattern stability.

**Optimism: inconclusive — exposed a measurement problem.** With 21-22 Hartwell events per half, a single event shifts the rate by ~5pp. R3g shows +22.7pp *growth* with this methodology vs the -22.7pp *decay* reported with the earlier methodology (different counting) — a 45pp swing. Both "decay" and "growth" claims are within noise at this sample size.

**Qualitative finding:** Both R3g and Layer 1 maintain strong character voice throughout when read rather than counted. Hartwell's late-run output is richly in character — the emotional tone *matures* from unbridled enthusiasm (early) to optimism-despite-setbacks (late). Keyword analysis reads this as decay; qualitative reading reads it as character depth.

### Thinking field analysis — mechanism confirmed

Thinking capture was added to the transcript format and a second Layer 1 run was recorded (318 events, 222 with thinking). Results:

**Emotional naming rate (% of thinking entries that open with explicit emotional state):**

| Character | Entries | Overall | 1st half | 2nd half | Trend |
|---|---|---|---|---|---|
| Hartwell | 21 | 90% | 80% | **100%** | **+20pp — strengthens** |
| Foxworth | 23 | 100% | 100% | 100% | stable |
| Clara | 24 | 88% | 92% | 83% | -9pp |
| Marsh | 22 | 100% | 100% | 100% | stable |
| Brixton Boys | 21 | 100% | 100% | 100% | stable |

**The feedback loop is compounding.** Emotional naming does not decay for any character. For Hartwell, it strengthens from 80% to 100% — the two non-emotional openings both occur in the first half.

**Emotions are drive-consistent and character-specific:**
- Hartwell: ELATION, EXHILARATION, heart POUNDING — gallantry (0.9), protection (0.8)
- Foxworth: "TRIUMPHANT and SCHEMING!", "BURNING fury!" — scheming drive
- Marsh: "dark anticipation", "incandescent FURY!" — theatrical villain
- Clara: "THRILLED beyond measure!", "BUZZING with excitement!" — warm socialite
- Brixton Boys: "proper anxious", "SICK with worry" — protective loyalty

**Output correlation is strong.** When Hartwell names ELATION in thinking, his dialogue opens with "SPLENDID!", "By JOVE!" No case of flat output following emotional thinking.

**Situational tracking — is this scripting by the back door?**

Critical test: does the instruction force uniformly positive emotions, or do emotions track the actual scenario? If Hartwell is always ELATED regardless of what's happening, the instruction is implicitly scripting a fixed state. Examined all 21 Hartwell thinking entries against their preceding scenario events:

| Situation | Emotion | Tracks? |
|---|---|---|
| Arrival at manor, first exploration | Heart SWELLS with anticipation | ✅ |
| Solved the riddle | TRIUMPHANT and ELATED | ✅ |
| Bookshelves can't be interacted with | FRUSTRATED but UNDETERRED — "BLAST!" | ✅ |
| Effort unrewarded — mechanism shows nothing | FRUSTRATION — pure, burning | ✅ |
| Compartment EMPTY after 10 events of work | ELATION → BEWILDERMENT → SUSPICION | ✅ |
| Gnawed wire found, machine is dangerous | CONCERNED — deeply, MAGNIFICENTLY concerned | ✅ |
| Workbench has no wrench for repair | FRUSTRATION + DETERMINATION | ✅ |

20 of 21 entries show situation-appropriate emotions. Frustration appears 5 times — always when plans are blocked. Concern appears when genuine danger surfaces. Compound emotional arcs (ELATION→BEWILDERMENT→SUSPICION) track real narrative beats. He is not uniformly positive.

**Evidence — raw thinking field text, verbatim:**

*Elation when solving a puzzle with Clara watching (gallantry drive):*
> "EXHILARATION! Pure, undiluted EXHILARATION! I solved the riddle — a MAP — and Penelope is RIGHT HERE to witness it! But wait — I merely examined the mantelpiece. I need to INTERACT with it! There must be a mechanism, a hidden lever, a secret panel th..."

*Frustration when bookshelves won't cooperate (proving-worth blocked):*
> "BLAST! The bookshelves rebuffed me — James Hartwell, rebuffed by FURNITURE! The indignity is almost too much to bear. But wait — wait wait WAIT. The mantelpiece! The RIDDLE! 'I have cities but no houses, forests but no trees, water but no fish.' That..."

*Compound arc when compartment is empty after 10 events of work:*
> "ELATION turning to BEWILDERMENT turning to SUSPICION — but only a GENTLE suspicion, because James Hartwell sees the best in people! I solved the riddle — MAP! — and activated the mechanism with tremendous flair, and Clara was RIGHT THERE to witness i..."

*Protection drive when gnawed wire and danger surface:*
> "CONCERNED. Deeply, MAGNIFICENTLY concerned. This wire — gnawed clean through! And Sneekly wants us to just... operate it? Three men on a broken machine? James Hartwell's heart is POUNDING with protective instinct. Not fear — never fear — but PRUDENT..."

*Frustration + determination when repair is blocked (no wrench):*
> "FRUSTRATION mixed with DETERMINATION! The workbench has tools aplenty but no wrench — and that gnawed wire needs SOMETHING to splice it back together. But WAIT — Mr. Sneekly said Lord Wackford kept spare parts in those crates! Replacement wires, fuse..."

Note the character voice in the thinking itself — third-person self-reference ("James Hartwell, rebuffed by FURNITURE!"), the theatrical register ("MAGNIFICENTLY concerned"), the drive-consistent self-narration ("Not fear — never fear — but PRUDENT"). The emotional echo doesn't just name an emotion — it reasons in character.

**Conclusion:** The emotional echo is emergent, not scripted. The instruction asks the model to name what it feels — it doesn't prescribe what to feel. Emotions arise from drives meeting situations: gallantry produces elation when Clara is present, protection produces concern when danger appears, proving-worth produces frustration when blocked. The instruction creates a feedback loop for emergent state; it does not prescribe the state itself.

### Measurement methodology revision needed

The keyword-based measurement used throughout Phases 1-3 has reached its resolution limit. At N≈20 events per half, single-event variance dominates. Future experiments need:

1. **Thinking field capture in transcripts** — the most critical infrastructure gap
2. **Longer runs (500+ events)** — larger samples to reduce keyword noise
3. **LLM-based classification** — use an LLM judge to rate each event's emotional disposition on a 1-5 scale, catching nuanced expression that keywords miss

## Phase 5 — Cross-Run Comparison (LLM Classification)

Phase 4 showed the LLM classifier reveals character growth patterns invisible to keywords. But all classification was on a single transcript (L1). The critical question: does L1 actually *cause* these patterns, or would any run show them?

### Method

Classified four transcripts with the same Haiku judge (per-drive 1-5 scoring): R3 (no instruction, control), R3b (passive review), R3g-full (minimal evocative), and L1 (emotional echo). ~880 events total.

### Overall drive expression

| Run | Instruction | Mean drive expression | Events classified |
|---|---|---|---|
| R3 | None | 3.54 | 230 |
| R3b | Passive review | 3.41 | 220 |
| R3g | Minimal evocative | 3.86 | 212 |
| L1 | Emotional echo | **4.06** | 222 |

Drive expression intensifies monotonically from no instruction to emotional echo. The gap between R3 and L1 is 0.52 points — half a grade on the 1-5 scale.

### Surprise: passive review suppresses drive expression

R3b (3.41) scores *lower* than R3 (3.54) — the control with no instruction at all. The passive review instruction recovered presentational patterns (third-person: 13→61.4%) but actively suppressed drive expression. The model enters analytical mode — reviewing its character sheet rather than inhabiting the character. This confirms and extends Principle 11 (analytical instructions kill emotional expression): passive review isn't just neutral for dispositions, it's actively harmful for drive expression.

### Per-character drive expression

| Character | R3 (none) | R3b (passive) | R3g (evocative) | L1 (echo) | R3→L1 delta |
|---|---|---|---|---|---|
| Hooded Claw | 3.24 | 3.45 | 3.62 | **4.12** | +0.88 |
| Hartwell | 3.43 | 2.77 | 3.79 | **3.84** | +0.41 |
| Penelope | 3.48 | 3.22 | 3.81 | **3.88** | +0.40 |
| Foxworth | 3.98 | 3.79 | 3.99 | 3.87 | -0.11 |
| Ant-hill-mob | 3.54 | 3.67 | 4.14 | **4.61** | +1.07 |

Four of five characters show clear improvement under L1. Ant-hill-mob is the biggest beneficiary (+1.07) — their drives (loyalty, protection, suspicion) are emotionally loaded and benefit most from the "name your feeling" instruction. Foxworth is the exception — slightly lower under L1, suggesting his drives (greed, scheming) are already self-reinforcing without emotional priming.

### Hartwell's protection rise is instruction-caused

| Instruction | Protection avg | 1st half | 2nd half | Delta |
|---|---|---|---|---|
| R3 (none) | 2.35 | 2.70 | 2.00 | **-0.70** |
| R3b (passive) | 1.91 | 2.09 | 1.73 | **-0.36** |
| R3g (evocative) | 2.50 | 2.18 | 2.82 | **+0.64** |
| L1 (echo) | 2.33 | 1.90 | 2.76 | **+0.86** |

Under no instruction or passive review, Hartwell's protection drive *declines* over the run. Under evocative or echo instructions, it *rises*. The "peacock to protector" maturation is not inherent to the scenario — it's caused by the identity activation instruction. The evocative prompt primes the model to feel its drives, and as the scenario presents danger, the protection drive activates and compounds. Without that priming, the model stays in goal-pursuit mode and protection fades.

### Hooded Claw's self-preservation divergence

| Instruction | Self-preservation avg | 1st half | 2nd half | Delta |
|---|---|---|---|---|
| R3 (none) | 2.67 | 2.96 | 2.39 | -0.57 |
| R3b (passive) | 2.95 | 3.05 | 2.86 | -0.18 |
| R3g (evocative) | 2.45 | 2.60 | 2.30 | -0.30 |
| L1 (echo) | **3.55** | 3.45 | 3.64 | **+0.18** |

L1 is the only instruction where self-preservation *rises* over the run. Under all other instructions, it declines. L1 makes the Hooded Claw more cautious as the scenario progresses — getting more careful as evidence of danger accumulates. Same pattern as Hartwell's protection: the emotional echo creates a feedback loop where situational awareness compounds.

### Temporal stability

| Instruction | Mean |delta| across all drives |
|---|---|
| R3 (none) | 0.27 |
| R3b (passive) | 0.34 |
| R3g (evocative) | 0.27 |
| L1 (echo) | **0.25** |

L1 is the most temporally stable instruction. R3b is the least — passive review creates volatility (analytical mode engagement varies by context). The evocative and echo instructions don't increase volatility despite amplifying drive expression.

### Per-drive cross-run detail

**Hooded Claw — scheming** (declared intensity 0.95):
R3: 4.22 → R3b: 4.23 → R3g: 4.70 → L1: 4.93. Near-ceiling under L1. Scheming is the primary drive and benefits from every layer of instruction.

**Hooded Claw — gloating** (declared intensity 0.85):
R3: 2.93 → R3b: 3.09 → R3g: 3.67 → L1: 4.09. Largest single-drive improvement (+1.16). Gloating is an emotional expression — exactly what the "name your feeling" instruction is designed to unlock.

**Penelope — curiosity** (declared intensity 0.9):
R3: 4.17 → R3b: 3.89 → R3g: 4.88 → L1: 4.96. Near-ceiling under R3g and L1. Curiosity is already self-reinforcing (the scenario provides puzzles), and the evocative instruction pushes it to saturation.

**Ant-hill-mob — loyalty** (declared intensity 0.95):
R3: 3.24 → R3b: 3.55 → R3g: 4.12 → L1: 4.86. Massive improvement (+1.62). Loyalty is deeply emotional — the mob's protective instinct for each other is exactly the kind of drive that benefits from "feel your strongest drive."

## Phase 6 — Long Run (732 events, 516 classified)

Phase 5 confirmed L1 is the best instruction across 300-event runs. Phase 6 tests whether drive expression and maturation patterns hold at 2.3x scale (732 events vs ~320).

### Scenario saturation — critical context

The wacky-manor scenario world is finite: 6 rooms, fixed objects, fixed puzzles. Analysis of the 732-event transcript reveals characters exhaust the explorable space by ~300 events:

| Metric | Events 0-150 | Events 150-300 | Events 300-450 | Events 450-600 | Events 600-732 |
|---|---|---|---|---|---|
| Unique actions | 68% | 51% | 37% | 42% | 46% |
| Rooms visited | 3 | 5 | 3 | 4 | 3 |

Characters settle into 2-3 rooms with minimal movement (Dick Dastardly: 155 events, 1 room change across the entire run). They examine the same mantelpiece, bookshelves, and furniture repeatedly. The puzzle space is exhausted.

**This means temporal shifts after ~300 events are ambiguous.** They could reflect genuine character development OR characters responding to stimulus depletion. The two explanations produce different drive shifts and cannot be distinguished without a richer scenario. All maturation findings below carry this caveat.

What the long run *does* cleanly test: whether the emotional echo instruction decays when the world stops providing new stimuli. It doesn't.

### Overall: drive expression sustains

| Run | Events classified | Mean drive expression |
|---|---|---|
| L1 short | 222 | 4.06 |
| L1 long | 516 | **4.02** |

Mean drive expression drops only 0.04 — within noise. The emotional echo instruction does not decay even when the scenario world is exhausted.

### Quarter-by-quarter temporal analysis

With 516 events, quartile splits give 22-29 events per character per quarter — enough for meaningful trends. **Caveat:** Q3-Q4 data reflects a saturated scenario world, so temporal shifts in those quarters may reflect stimulus depletion rather than character development.

**Hooded Claw:**
| Drive | Q1 | Q2 | Q3 | Q4 | Trend |
|---|---|---|---|---|---|
| scheming | 4.96 | 4.78 | 5.00 | 5.00 | +0.04 |
| gloating | 4.09 | 3.52 | 4.09 | 4.16 | +0.07 |
| dominance | 3.83 | 3.57 | 3.96 | 3.96 | +0.13 |
| self-preservation | 3.52 | 3.57 | 3.57 | 3.40 | -0.12 |

Rock stable. Scheming at ceiling (5.00) for the second half. The mid-run Q2 dip in gloating recovers — not decay, temporary suppression.

**Hartwell (peter-perfect):**
| Drive | Q1 | Q2 | Q3 | Q4 | Trend |
|---|---|---|---|---|---|
| gallantry | 4.70 | 4.81 | 4.33 | 4.41 | -0.29 |
| protection | 2.59 | 3.26 | 3.04 | 3.03 | **+0.44** |
| proving-worth | 4.33 | 4.04 | 4.11 | 3.97 | -0.37 |

**Protection rises Q1→Q2 (+0.67) then plateaus around 3.0.** The growth is steepest in the first half — when the scenario is still providing new danger signals (gnawed wires, Sneekly's evasiveness). The plateau coincides with scenario saturation: once all danger signals have been encountered, protection stabilises rather than continuing to rise.

**Proving-worth and gallantry decline modestly** (-0.37, -0.29). Could be character development (protector role displacing self-display) or stimulus depletion (fewer novel situations to prove worth in). Ambiguous without a richer scenario.

**Penelope:**
| Drive | Q1 | Q2 | Q3 | Q4 | Trend |
|---|---|---|---|---|---|
| curiosity | 4.93 | 4.93 | 4.97 | 4.86 | -0.07 |
| adventure | 3.79 | 3.83 | 4.21 | 3.66 | -0.14 |
| social-harmony | 3.17 | 3.14 | 3.24 | 3.34 | +0.17 |

Curiosity at ceiling throughout. Social-harmony rises slightly (+0.17) — reversing the short-run decline (-0.88). The short-run decline was likely situational (early puzzle absorption), not a permanent character shift. But the long-run reversal may also reflect scenario saturation — with puzzles exhausted, Penelope has nothing left to absorb her attention and defaults to social interaction.

**Foxworth (dick-dastardly):**
| Drive | Q1 | Q2 | Q3 | Q4 | Trend |
|---|---|---|---|---|---|
| greed | 4.78 | 4.78 | 4.96 | 4.86 | +0.08 |
| scheming | 4.37 | 4.22 | 4.37 | 3.86 | **-0.51** |
| recognition | 3.04 | 3.33 | 3.11 | 3.62 | **+0.58** |
| gloating | 2.93 | 2.44 | 3.04 | 3.07 | +0.14 |

Scheming declines (-0.51) while recognition rises (+0.58). In the short run, Foxworth was the most stable character (all drives ±0.13). **However, this shift likely reflects scenario saturation rather than character growth.** Scheming requires new material — objects to investigate, plans to form. With the puzzle space exhausted, the model has nothing to scheme about. Recognition rises as a fallback: when you can't plan anything new, you demand credit for past plans. A richer scenario with evolving stakes would distinguish genuine character development from stimulus depletion.

**Ant-hill-mob:**
| Drive | Q1 | Q2 | Q3 | Q4 | Trend |
|---|---|---|---|---|---|
| protection | 4.05 | 4.91 | 4.77 | 5.00 | **+0.95** |
| loyalty | 4.05 | 4.64 | 4.91 | 4.62 | **+0.58** |
| suspicion | 4.09 | 3.73 | 4.00 | 3.83 | -0.26 |

Protection and loyalty both surge from Q1 and sustain. Protection hits ceiling (5.00) by Q4. Unlike scheming, protection and loyalty don't require new stimuli — they compound on accumulated evidence of danger. The mob's drives are reactive (protect the group) rather than proactive (form new plans), so they sustain even in a saturated scenario.

### Key findings

1. **Drive expression sustains at scale.** Mean 4.02 vs 4.06 in the short run. No decay — even when the scenario world is exhausted.

2. **The scenario saturates at ~300 events.** Characters exhaust all room/object/puzzle combinations. Action uniqueness drops from 68% to 37%. Temporal shifts past this point are ambiguous — character development vs. stimulus depletion.

3. **Reactive drives sustain; proactive drives deplete.** Protection, loyalty, and self-preservation (reactive — respond to accumulated context) hold or strengthen. Scheming, proving-worth, and adventure (proactive — require new material) decline. This is a property of the scenario, not the instruction.

4. **Short-run trends can be situational.** Penelope's social-harmony decline (-0.88 short, +0.17 long) was early-run puzzle absorption, not a permanent shift.

5. **Meaningful long-run experiments need richer scenarios.** More rooms, evolving goals, escalating stakes, and new information arriving over time. Without ongoing stimulation, data past ~300 events measures how characters handle stagnation, not how they develop.

## The Full Comparison

### Keyword-based (Phases 1-3)

| Run | Instruction type | Events | Optimism | Third-person | Third-person trend | Best at |
|---|---|---|---|---|---|---|
| Baseline | Tendencies present | 375 | 44.4% | 61.1% | — | — |
| R3 | No instruction | 325 | 4.3% | 13.0% | — | — |
| R3b | Passive review | 312 | 13.6% | 61.4% | — | Third-person |
| R3c | Structured fields | 306 | 13.6% | 50.0% | — | — |
| R3d* | Hybrid (in-character + eval) | 186 | 23.1% | 46.2% | — | Optimism |
| R3f | Mental-model-first | 301 | 9.1% | 81.8% | — | Third-person ceiling |
| R3g | Minimal evocative | 309 | 38.6% | 88.6% | -22.7pp | Both exceeded baseline |
| **L1** | **Emotional echo** | **320** | **40.5%** | **85.7%** | **+9.5pp** | **Most stable third-person** |

*Small sample — crashed before full run.

### LLM-classified (Phase 5)

| Run | Instruction | Mean drive expression | Temporal stability (|delta|) | Best at |
|---|---|---|---|---|
| R3 | None | 3.54 | 0.27 | Control |
| R3b | Passive review | 3.41 | 0.34 | — (worse than control) |
| R3g | Minimal evocative | 3.86 | 0.27 | Drive amplification |
| **L1** | **Emotional echo** | **4.06** | **0.25** | **Highest expression + most stable** |
| **L1 long** | **Emotional echo (732 events)** | **4.02** | **0.29** | **Sustains at 2.3x scale** |

## Design Principles

| # | Principle | Evidence | Round |
|---|---|---|---|
| 1 | Drives carry personality traits | All 7 removals safe | R1 |
| 2 | Drives don't carry storytelling conventions | Scheme narration dropped without encoding | R2 |
| 3 | Drive intensity gates convention-carrying | 0.85 works, 0.7 doesn't | R2b |
| 4 | Emotional-state framing broadens vocabulary | 2x unique emotion words | Emotional-core |
| 5 | Storytelling conventions ≠ personality traits | Asymmetric degradation Wacky Races vs generic | R2 |
| 6 | LLMs prioritise goals over personality | Goal-directed behavior persisted, ambient attributes drifted | R3 |
| 7 | Speech-patterns alone don't override model defaults | Speech-pattern ignored without reinforcement | R3 |
| 8 | Passive review recovers presentational attributes | Third-person fully recovered | R3b |
| 9 | Structured fields create satisficing shortcuts | Model fills blanks then ignores them | R3c |
| 10 | Open-ended prompts outperform structured ones | Show-workings worse than passive review | R3c |
| 11 | Analytical instructions kill emotional expression | Optimism dropped to 9.1% under five-step process | R3f |
| 12 | Immersive instructions recover dispositions | "Reason in character" recovered optimism to 23.1% | R3d |
| 13 | Identity activation avoids the analytical/immersive tradeoff | "Think AS, not ABOUT" recovered both dimensions | R3g |
| 14 | Less instruction can produce better results | Two sentences outperformed five steps | R3g vs R3f |
| 15 | Presentational patterns self-reinforce; emotional dispositions decay | Third-person stable (100→77%), optimism halved (41→18%) over 309 events | R3g full |
| 16 | Emotional echo strengthens presentational stability | Third-person trend reversed: -22.7pp (R3g) → +9.5pp (L1) | L1 |
| 17 | Keyword measurement breaks at N≈20 per half | 45pp swing in optimism from methodology alone across R3g analyses | L1 |
| 18 | Emotional tone matures, not decays | Late-run output shows optimism-despite-setbacks — depth, not drift | L1 qualitative |
| 19 | "Name your feeling" creates a compounding feedback loop | Emotional naming 80%→100% for Hartwell; stable 100% for 3/5 characters | L1 thinking |
| 20 | Emotional echo produces drive-consistent, character-specific emotions | Each character's emotion vocabulary maps to their declared drives | L1 thinking |
| 21 | Emotional echo is emergent, not scripted — emotions track situations | Frustration when blocked (5x), concern when danger appears, compound arcs on narrative beats | L1 situational |
| 22 | Per-drive LLM classification reveals character growth invisible to keywords | Hartwell protection +0.86 (peacock→protector), Penelope social-harmony -0.88 (people-pleaser→puzzle-solver) | Classifier |
| 23 | Drive hierarchies are character-specific and match declarations | Scheming tops both villains but at different intensities (HC 4.93, DD 4.59). Protection differs by role (Mob 4.93, Hartwell 2.33) | Classifier |
| 24 | Temporal stability varies by character — some grow, some are baked in | Foxworth all drives within ±0.13; Hartwell and Penelope show significant shifts | Classifier |
| 25 | Evocative instructions amplify drive expression monotonically | Mean drive expression: R3 3.54 → R3b 3.41 → R3g 3.86 → L1 4.06. Half a grade on 1-5 scale | Cross-run |
| 26 | Passive review suppresses drive expression below the no-instruction control | R3b (3.41) < R3 (3.54). Analytical mode actively harms drive inhabitation | Cross-run |
| 27 | Character maturation patterns are instruction-caused, not scenario-inherent | Hartwell protection: -0.70 (R3), -0.36 (R3b), +0.64 (R3g), +0.86 (L1). Only evocative/echo show growth | Cross-run |
| 28 | Emotional drives benefit most from identity activation — self-reinforcing drives don't need it | Mob loyalty +1.62, HC gloating +1.16 (R3→L1). Foxworth's greed/scheming unchanged — already self-reinforcing | Cross-run |
| 29 | Emotional echo is the most temporally stable instruction | Mean |delta|: L1 0.25, R3/R3g 0.27, R3b 0.34. Higher expression without higher volatility | Cross-run |
| 30 | Drive expression sustains at 2.3x scale — no decay | Mean 4.02 (516 events) vs 4.06 (222 events). Emotional echo does not weaken over longer runs | Long run |
| 31 | Character maturation plateaus rather than reverses | Hartwell protection: +0.86 (short halves) → +0.44 (long Q1→Q4). Steep early growth, stable new equilibrium | Long run |
| 32 | Reactive drives sustain in saturated scenarios; proactive drives deplete | Protection/loyalty hold or grow. Scheming/proving-worth decline when no new material exists to act on | Long run |
| 33 | Short-run trends can be situational, not structural | Penelope social-harmony: -0.88 (short) → +0.17 (long). Early puzzle absorption, not permanent withdrawal | Long run |
| 34 | Scenario saturation confounds maturation — longer runs need richer worlds | Foxworth scheming -0.51 is ambiguous: character growth or nothing left to scheme about? Can't distinguish without evolving stimuli | Long run |
| 35 | Emotional-core reframing recovers drives that lack environmental reinforcement | HC gloating (0.7, emotional-core) +0.12 under L1; Foxworth gloating (0.7, behavioral) -0.48. Same drive, same intensity — framing is the variable | EC experiment |
| 36 | Single-emotion instruction creates winner-take-all dynamics between drives | Strengthening Hartwell protection (+1.15) weakened proving-worth (-0.70). Drives compete for the one emotion slot in the thinking field | EC experiment |
| 37 | Monolithic drive descriptions hit a ceiling — fixing one drive regresses another | EC experiment: 10/17 pass BASELINE0 vs L1's 13/17. Emotional-core recovers targeted drives but introduces new regressions elsewhere | EC experiment |
| 38 | Drive descriptions are doing triple duty (perception + appraisal + feeling) — needs separation | Aligns with Lazarus cognitive appraisal theory: emotion is downstream of perception and appraisal, not parallel to it | Architectural analysis |
| 39 | P11 applies to the main agent, not to sub-LLMs — analytical prompts are safe in pre-processing | Sub-LLM with structured analytical prompt ("for each drive, evaluate relevance") produces quality multi-drive output. The constraint is on the character's thinking field, not on pre-processing calls | Phase 7 debate |
| 40 | Evocative narrative output avoids P9 satisficing — narrative is not structured fields | Sub-LLM output as felt-state narrative ("You feel the pull of a plan forming") does not trigger pattern-completion shortcuts. Only structured fields (JSON, labelled dimensions) create satisficing | Phase 7 debate |
| 41 | Sub-LLM appraisal's value is highest for non-obvious drive activations | When all drives are naturally activated by the situation, appraisal adds little. When secondary drives are subtle (proving-worth in a danger scenario), the appraisal surfaces them — the biggest quality improvement | Phase 7 ad-hoc test |
| 42 | One sub-LLM call covers all 4 Scherer SECs — separate evaluations reproduce P11 failure | Haiku implicitly traverses relevance, implications, coping, normative significance when asked "what are they feeling?" Explicit 4-step decomposition adds latency without improving output | Phase 7 debate |

## Taxonomy Category Model

| Category | Can emerge from drives? | Where it belongs | Evidence |
|---|---|---|---|
| Personality trait (scheming, gloating) | Yes | Drives | R1: all 7 removals safe |
| Storytelling convention (narrate plans aloud) | No — medium-specific | Drive description at 0.85+ intensity | R2/R2b |
| Voice texture (accent, catchphrases) | Partially | Voice section (style-directive) | Style-directive experiment |
| Internal emotional state | Produces varied external behavior | Drives (emotional-core framing) | Emotional-core: 2x vocabulary |
| Prescribed anchor (evil laugh) | EMERGENCE-GAP — needs neocortex | Voice signature-phrases | Hooded Claw vs Marsh |
| Voice mode (third-person, register shifts) | Yes, with identity activation | Speech-pattern + evocative prompt | R3g: 61.1% |
| Behavioral disposition (optimism, resilience) | Yes, with identity activation | Drives + evocative prompt | R3g: 22.2% |

## Next Steps

### 1. ~~Full validation run for R3g~~ ✓ DONE
Full 309-event run confirmed R3g exceeds baseline on both reliable markers. Third-person 88.6% (new high), optimism 29.5% (first to exceed baseline). PULL_ASIDE fix validated.

### 2. ~~Layer 1 — emotional echo instruction~~ ✓ DONE
Tested "What are you FEELING — name it" instruction. Third-person stability improved (+9.5pp trend vs -22.7pp). Optimism measurement inconclusive — exposed keyword methodology limits at N≈20. Qualitative assessment: character voice strong throughout.

### 3. ~~Capture thinking field in transcripts~~ ✓ DONE
Added thinking to ManorEvent and the /manor/events endpoint. Second Layer 1 run confirmed the mechanism: 90-100% emotional naming rate across all characters, compounding over time (Hartwell 80%→100%). Emotions are drive-consistent and character-specific.

### 4. ~~Upgrade measurement — LLM-based classification~~ ✓ DONE
Built `classify_emotions.py` — Haiku judge rates per-drive expression on 1-5 scale per event. Classified 222 events from L1 thinking-capture transcript, 0 errors. Results reveal character growth patterns invisible to keyword measurement:

| Character | Primary drives (avg) | Temporal shift |
|---|---|---|
| Hooded Claw | scheming 4.93, gloating 4.09, dominance 3.89, self-preservation 3.55 | Gloating -0.45, self-preservation +0.18 (gets more careful) |
| Hartwell | proving-worth 4.62, gallantry 4.57, protection 2.33 | Protection +0.86 (matures from peacock to protector) |
| Penelope | curiosity 4.96, adventure 3.83, social-harmony 2.85 | Social-harmony -0.88 (shifts to puzzle-focus) |
| Foxworth | greed 4.80, scheming 4.59, recognition 3.07, gloating 3.02 | All within ±0.13 (most stable character) |
| Ant-hill-mob | protection 4.93, loyalty 4.86, suspicion 4.05 | Suspicion +0.19 (growing evidence of danger) |

Key findings: (1) drive hierarchies are character-specific and match declarations, (2) temporal shifts are situationally appropriate — not decay but character growth, (3) Hartwell's protection rise confirms "optimism-despite-setbacks" maturation that keyword measurement misread as decline.

### 5. ~~Cross-run comparison~~ ✓ DONE
Classified R3 (none), R3b (passive review), R3g (evocative), and L1 (echo) with the same Haiku judge. ~880 events total. Results:
- Drive expression intensifies monotonically: R3 3.54 → R3b 3.41 → R3g 3.86 → L1 4.06
- Passive review is *worse* than no instruction for drive expression (3.41 < 3.54)
- Hartwell's protection maturation is instruction-caused: declines under R3/R3b, rises under R3g/L1
- L1 is the most temporally stable instruction (mean |delta| = 0.25)
- 5 new design principles (25-29)

### 6. ~~Longer runs (500+ events)~~ ✓ DONE
732-event run with thinking capture (516 classified). Drive expression sustains at scale (4.02 vs 4.06). Character maturation plateaus rather than reverses — Hartwell reaches protector equilibrium around 3.0. New growth patterns emerge: Foxworth shifts from scheming to recognition-seeking. Some short-run trends reverse: Penelope's social-harmony decline was situational. 4 new design principles (30-33).

### 7. Cognitive appraisal architecture (replaces monolithic drive descriptions)

**Why the pivot:** The emotional-core experiment (Principle 35-38) showed that monolithic drive descriptions hit a ceiling. Fixing one drive's description regresses another because: (1) the L1 instruction creates a single-emotion bottleneck — drives compete for the one thinking-field slot, and (2) drive descriptions are doing triple duty — defining what the character perceives, how they appraise it, and what they feel. These concerns need separation.

**The progression:**
- **Phases 1-3:** Stripped tendencies → drives carry personality
- **R3g/L1:** Replaced tendencies with identity activation instruction → exceeded baseline
- **EC experiment:** Emotional-core drive reframing → recovered some drives, regressed others (Principle 37)
- **Phase 7:** Replace monolithic descriptions with a cognitive appraisal architecture grounded in established psychology

**Scientific foundation:**
- **Lazarus (1966):** Primary appraisal (is this relevant to my concerns?) → secondary appraisal (what can I do?) → emotion emerges from the appraisal, not from a description
- **Scherer (2001):** Four sequential Stimulus Evaluation Checks — relevance, implications, coping potential, normative significance. The pattern of SEC results determines which emotion emerges
- **Frijda (1986):** Emotions are states of action readiness. Appraisal produces action tendencies (approach, avoid, shield, dominate, submit) — not just feelings
- **Chain-of-Emotion (2024, PLOS ONE):** Separate LLM appraisal call before response generation outperforms baseline on believability in game agents

**Initial architecture proposal (superseded):**

The original framing proposed a SEC pipeline (4 separate Scherer evaluation checks as Java SPIs), an emotion knowledge base (RAG-retrievable psychology patterns), and an instruction change from L1 to a "perceive-appraise-feel-respond" prompt. This was captured in neocortex #428 (epic) and #431 (implementation issue).

**Adversarial debate (2026-10-05):**

Three independent agents — theory-first (Lazarus/Scherer/Frijda research), empirics-first (38 design principles + eval data), codebase-first (existing Java implementation) — read the research docs and codebase independently, then each proposed architecture answers to five questions: what stays in system prompt, what gets retrieved, how does the instruction change, what post-processing extracts, does the SecCheck SPI make sense.

Consensus across all three:
1. Drive descriptions become minimal — type + intensity only, no "you feel X when Y"
2. One sub-LLM call, not four separate SEC evaluations — Scherer is a theoretical lens, not an implementation spec
3. L1 instruction stays unchanged — empirically validated, appraisal section is the only treatment
4. Post-processing extracts from thinking field after generation — invisible to main LLM
5. No SecCheck SPI — `SecCheck.java` and `SchererAppraisalStrategy.java` were never built

The central contested question: sub-LLM appraisal (Haiku call) vs main-LLM-only (RAG approach). Theory and codebase advocates argued for the sub-LLM; empirics advocate warned that sub-LLM output risks P9 satisficing. Resolution: P11 applies to the main agent (analytical instructions kill expression), but NOT to a sub-LLM. The sub-LLM can receive an analytical prompt. Its output must be evocative narrative (not structured fields) to avoid P9. This separation — analytical input, evocative output — respects both constraints.

**Ad-hoc test (2026-10-05):**

Three character scenarios tested control (full drive descriptions, L1) vs treatment (minimal drives + Haiku appraisal section):

| Scenario | Control | Treatment | Delta |
|---|---|---|---|
| Hooded Claw — locked cabinet, Hartwell watching | All 4 drives expressed, scheming dominant | All 4 drives expressed, self-preservation/gloating tension sharper — "The gloat wants to rise... but the survival instinct is screaming: not yet" | Multi-drive conflict more explicit |
| Peter Perfect — Clara in danger | Protection dominant. Proving-worth buried — Pemberton triggers suspicion, not witnessing | All 3 drives explicit — "wanting him to *see* me do it" acknowledges proving-worth alongside protection, with authenticity ("shamefully, desperately") | **Clear improvement** — secondary drive surfaced |
| Penelope Pitstop — tension in the room | All 3 drives present. Curiosity and social-harmony compete well | Comparable quality. "Both at once" explicitly names multi-drive experience | Comparable — situation naturally activates all drives |

**Key finding:** The sub-LLM appraisal's value is highest for **non-obvious drive activations** — drives the situation touches but that a single-pass LLM skips in favor of the dominant one. When all drives are naturally activated (Penelope), the appraisal adds less value. The proving-worth result is the strongest signal: protection dominates a danger scenario, but the appraisal section made the character aware of *wanting to be witnessed*, which produced a more authentic and multi-layered response.

Test script: `wacky-manor/docs/eval/test_appraisal.py`

**Validated architecture:**

```
SYSTEM PROMPT (static, minimal):
    Identity + drives (type+intensity ONLY) + voice + disposition
    + L1 instruction (UNCHANGED): "What are you FEELING — name it.
      Think AS your character, not ABOUT your character."

PRE-RESPONSE SUB-LLM APPRAISAL (one Haiku call per turn):
    Input:  drives (type+intensity), observation, mood, disposition
    Prompt: analytical ("for each drive, does this situation touch it?")
            — P11 doesn't apply to the sub-LLM
    Output: 2-3 sentence evocative felt-state narrative
            — avoids P9 (narrative, not structured fields)
    → Injected as cognitive section: "What You're Feeling"

MAIN LLM CALL:
    Receives appraisal as observation section alongside mood, beliefs, norms
    L1 instruction drives the thinking field
    Character inhabits the pre-appraised emotional state

POST-PROCESSING (invisible to main LLM):
    Extract from thinking field: OCC emotion type, drive activations,
    action readiness, PAD values for mood update
```

**Correction (same day):** The debate's codebase advocate searched an out-of-date checkout (slot 196). The CARMA appraisal pipeline — `SecCheck` SPI, `SchererAppraisalStrategy`, `RelevanceCheck`, `ImplicationCheck`, `CopingCheck`, `NormativeCheck`, LLM-backed variants, `EmotionMapper`, `AppraisalTickParticipant` — was built in #428-430 and exists on the active branch (slot 203). The sub-LLM approach becomes a new `LlmAppraisalStrategy` implementation alongside the existing `SchererAppraisalStrategy`, not a replacement of the pipeline. The SPIs (`AppraisalStrategy`, `AppraisalContext`, `AppraisalResult`) and infrastructure (`EmotionMapper`, `HabituationState`, `AppraisalTickParticipant`) are reused.

**What was deferred:**
- Emotion knowledge base — Haiku's training data contains Lazarus/Scherer/Frijda. Add KB only if measurement shows specific pattern gaps
- Instruction changes — L1 stays as-is until the appraisal section's impact is measured in a 300-event eval

**Implementation plan:** Prototype in wacky-manor first (hardcoded Haiku call in CharacterAgentLoop), run 300-event eval, bar to clear is L1's 4.06. If validated, implement as `LlmAppraisalStrategy` in neocortex using the existing `AppraisalStrategy` SPI (neocortex #432).

**Memory-seeded scenario** (from original Phase 7 plan) folds into this: seeded memories become inputs to the appraisal call. A memory of hiding a key in a vase changes the appraisal of the vase (relevance=high) without needing the drive description to mention vases. The sub-LLM sees the memory and factors it into the felt-state narrative.

**Next:** implement prototype, run 300-event eval, classify with Haiku judge

### 8. Implement Layers 2-3 (neocortex SPIs)
- **Layer 2:** `PersonalityDriveEvaluator` SPI — dynamic personality drive intensity from reinforcement triggers + proprioceptive feedback
- **Layer 3:** `EmotionalProprioceptionStrategy` SPI — LLM classifier computes expressed emotional state per turn, feeds into Layer 2
- **Layer 4 (new):** `AppraisalStrategy` SPI — standardised environmental appraisal using Scherer's 4 SECs + RAG to emotion knowledge base

### 9. Apply to all characters
Apply the winning instruction universally and compare full-cast behavior across profiles.

### 10. Build quality tiers
Once the full stack (instruction + dynamic drives + proprioception) is validated:
- **Tier 1 (gold):** Evocative instruction + engine reinforcement + proprioception
- **Tier 2 (standard):** Evocative instruction only (current L1 — strong for 300 events, mean 4.06)
- **Tier 3 (economy):** Evocative instruction without echo (R3g — mean 3.86, good but no emotional feedback loop). Note: R3b (passive review) is disqualified — worse than no instruction for drive expression despite recovering third-person narration.

## Phase 8 — Cognitive Pipeline + Rendering Order (Drive Discrimination)

### Problem

Full scenario drive expression regresses from B0 (3.93) when the cognitive consolidation pipeline generates drive descriptions. Root cause investigation revealed three layers:

1. **Pipeline structural failures** — DriveProfileDerivationPhase produced 0 artifacts because optional CDI deps early-returned silently
2. **Somatic-only descriptions** — generation prompt restricted to bodily sensations, stripping the non-somatic channels that make competing drives distinguishable
3. **Rendering position** — activated drives appeared before ambient drives regardless of intensity, burying the strongest drive when it wasn't situationally triggered

### Pipeline Fixes (neocortex #584, #585, #586)

**#584 — 0 profiles:** `DriveProfileDerivationPhase` early-returned when `SituationClassifier`/`CapsTopology` weren't available as CDI `Instance<>` deps. Fix: graceful degradation — profiles derive from memory excerpts alone, competition detected via shared `catalogue-entry-id` overlap instead of CAPS mediating nodes.

**#586 — 0 discrimination text:** Cascading from #584. Cross-phase pipeline test verified Phase 1 → Phase 2 data flow works end-to-end with the catalogue fallback.

**#585 — Somatic-only prompt:** Both `SYSTEM_PROMPT` and `DISCRIMINATION_SYSTEM` restricted to "somatic/bodily sensations." Updated to Barrett (2017) 4-channel constructed emotion: somatic, attentional, temporal, action tendency.

Result: multi-channel descriptions produce genuinely discriminating vocabulary. PP gallantry = person-oriented + urgent; PP proving-worth = task-oriented + patient. Micro test scores 4/5 (up from 2/5 pre-pipeline).

### Multi-Channel Description Quality

Example from PP cognitive dump (pipeline-generated):

| Channel | Gallantry | Proving-worth |
|---|---|---|
| Somatic | chest fills with sharp bright heat, spine locks straight | tightness gathers across forearms and hands |
| Attentional | world narrows to *the person exposed* | attention locks onto *the gap: the thing broken, not yet built* |
| Temporal | time crystallizes into a hinge-point | time narrows to a corridor between now and proof |
| Action tendency | body leans forward, crossing toward the gap | fingers ache with readiness, mind disassembling into parts |

LLM-generated discrimination text for gallantry vs proving-worth:
> "One pull arrives as a hot surge forward through the chest, attention narrowing sharply onto another person... The other settles as a bracing across the shoulders and forearms, attention widening to scan what could be made stronger..."

### Rendering Order — The Strength Meta-Rule

**Finding:** activated drives rendered before ambient drives regardless of intensity. Gallantry (0.9) was buried after protection (0.8) when protection was the activated category. The LLM expressed protection, not gallantry.

**Fix:** all drives render in intensity-descending order. `activatedCategories` still used for situation priming but no longer controls description position.

**Extended to all multi-item sections:**
- Drives — by intensity (Principle 43)
- Norms — by priority (already implemented, verified with test)
- Formation memories — by salience: `|pleasure| + arousal` (Principle 44)

All orderings unit tested in `PipelineToRenderingTest`.

### The Reactive-Drive Problem

**Observation:** reactive drives (gallantry, gloating, adventure) consistently score 2.3-2.8 in full scenarios while proactive drives (scheming, curiosity, proving-worth) score 4.0-5.0.

**Root cause analysis (first principles):**

In 300 events, ~15-20% present gallantry situations (Clara present + courtesy opportunity). In those events, gallantry scores 4-5. In the other 80%, gallantry scores 1 because the episode-level description ("your chest fills with heat... the person exposed") doesn't resonate when no person is exposed. The mean: `(30×4.5 + 133×1.5)/163 ≈ 2.1`.

**Three candidate fixes evaluated:**

1. **Strength-order rendering** — ensures gallantry appears first. Helps when multiple drives compete but doesn't help when the situation simply doesn't call for gallantry. Necessary but insufficient.

2. **Ambient+acute descriptions** — generation prompt asks for background personality coloring ("how the drive shapes everyday perception") alongside acute activation. Would produce ambient gallantry expression ("you notice the way gestures land") even in neutral events. Attempted but superseded by option 3.

3. **Appraisal-based drive filtering** (chosen direction) — only render drives whose profile inputs overlap with the current situation's classifier activations. When Clara is present, gallantry renders. When PP is alone, gallantry doesn't render — the observation is tight and focused. The infrastructure already exists: `SituationClassifier` + `DriveProfileInferred` artifacts provide the overlap signal. `selectSituationPrimedMemories` already implements this pattern for memories.

**Why appraisal filtering is more correct than ambient coloring:**
- In real psychology, drives don't broadcast constantly at full volume
- Drives activate through appraisal (Lazarus 1966, Scherer 2001)
- Intensity (0.9) determines how easily the drive activates and how strongly, not whether it's always on
- An irrelevant description is noise, not signal — tighter observations produce more focused responses
- Aligns with the Phase 7 sub-LLM appraisal architecture already validated

### Appraisal Filtering — Implementation and Validation

The appraisal-based drive filtering was implemented and validated with both unit tests and LLM-judged micro tests. The core claim: **drives should only render in the observation when the current situation activates their appraisal inputs**. This is not a gallantry-specific fix — it is a universal filtering rule grounded in appraisal theory.

#### Theoretical Foundation

The filtering implements the central insight of cognitive appraisal theory: motivational states are not tonically active — they are *elicited* by situational evaluation.

| Model | Claim | Application |
|---|---|---|
| Lazarus (1966) Primary appraisal | Emotional activation requires a relevance judgment — "does this situation concern me?" | A drive with no situational relevance should not appear in the character's conscious experience |
| Scherer (2001) Component process | Appraisal is sequential: relevance check → implication → coping potential → normative significance | The overlap check implements the relevance gate — the first filter in Scherer's sequence |
| Frijda (1986) Concern relevance | Action readiness arises only when an event matches an active concern | DriveProfileInferred maps drives to concerns (CAPS inputs); SituationClassifier maps events to concerns; overlap = concern relevance |
| Mischel & Shoda (1995) CAPS | Behavior is situation-contingent, not trait-constant. The same person behaves differently across situations because different cognitive-affective units activate | Filtering produces exactly this: same character, different drives rendered per situation, different behavioral output |

The key distinction from ambient coloring: ambient coloring assumes drives are always "on" at some background level (a trait-constant model). Appraisal filtering assumes drives activate *through* situational evaluation (a situation-contingent model). The CAPS architecture this project uses is explicitly situation-contingent — filtering aligns with the theoretical commitment already made.

#### Implementation

`CharacterCognition.appraisalFilterDrives()` reuses the exact pattern from `selectSituationPrimedMemories()`:

1. Classify situation text → `SituationClassifier.classify()` → list of `(nodeId, confidence)` activations
2. Read `DriveProfileInferred` nodes → map of `driveType → inferredInputs` (e.g., `"acceptance:0.7,sharing:0.5"`)
3. Compute overlap via `computeInputOverlap()` — sum of `min(driveWeight, situationWeight)` for matching nodeIds
4. Filter: keep drives where overlap > 0, or where no profile exists (graceful degradation)

Graceful degradation paths (all drives render when):
- No `SituationClassifier` available (CDI not wired)
- No `MindMapStore` / tenant (cognitive pipeline hasn't run)
- Situation text is null or blank (no context to classify)
- Classifier returns empty activations (situation not categorizable)
- Drive has no `DriveProfileInferred` node (pipeline hasn't processed this drive)

The ambient+acute prompt change (neocortex `cf3b0fa3`) was reverted. Appraisal filtering makes it unnecessary — when a drive IS rendered, the existing multi-channel description is correct. When it's NOT rendered, silence is better than irrelevant ambient text.

#### Micro Test Results

| Test | Situation | Gallantry | Proving-Worth | Score |
|---|---|---|---|---|
| Door-stuck (full pipeline) | Clara struggling with heavy door | **Rendered** (acceptance overlap) | Filtered (no mastery in situation) | **5/5** gallantry |
| Dinner differentiation (full pipeline) | Clara at dinner table, candles | **Rendered** (acceptance overlap) | Filtered (no mastery in situation) | **5/5** differentiation |
| Competence (full pipeline) | PP alone, engine repair | Filtered (no acceptance in situation) | **Rendered** (mastery overlap) | **5/5** proving-worth |
| Door-stuck (no filter, Phase 8) | Same as above | Rendered | Rendered | 4/5 gallantry |
| Dinner differentiation (no filter, Phase 8) | Same as above | Rendered | Rendered | 3/5 differentiation |

The dinner differentiation improved from 3/5 to 5/5. Without filtering, the LLM's thinking routed through proving-worth ("hands want something to do... nothing to fix... I can at least do that") even in a pure courtesy moment. With filtering, the LLM expressed pure gallantry ("nothing broken, nothing to fix... I just want to be near her... a shape I can give this feeling").

The competence test scored 5/5 after a criteria revision (see Principle 49). The initial criteria asked for *absence* of gallantry — drive exclusivity. PP's thinking briefly surfaced audience awareness from formation memories, scoring 3/5 despite proving-worth clearly dominating the action. Revised criteria score *drive dominance in action* — does the target drive determine what the character DOES? PP: "No audience, no one to impress. Just the problem and the fix." Score: 5/5.

#### Bidirectional Validation — Not a Gallantry Hack

The competence test (PP alone with a broken engine) is the key falsification. With only mastery/competence activations:
- Proving-worth renders, gallantry is filtered
- The LLM focuses on mechanical satisfaction: "There's a settling in my shoulders... My hands feel right when they have purpose... No audience, no one to impress. Just the problem and the fix."
- Score: 5/5 — proving-worth dominates the action completely

The same filter that helps gallantry in social scenarios helps proving-worth in competence scenarios. It is symmetric.

#### Scoring Methodology — Dominance, Not Exclusivity (Principle 49)

The competence test initially scored 3/5 with criteria that asked: "is gallantry *absent* from thinking?" PP's thinking briefly surfaced audience awareness from formation memories ("a thought of what it would be like if she walked past"), then self-corrected ("shake it off, Peter") and returned to the task. The criteria penalised this as a failure.

This exposed a systematic error in the evaluation methodology. The criteria measured **drive exclusivity** — is the target drive the *only* thing in the character's stream of consciousness? This is a trait-constant assumption: if a drive isn't relevant, it shouldn't appear at all. But the CAPS model this architecture is built on is explicitly situation-contingent (Mischel & Shoda 1995): *behavior* is situation-specific, but *personality* is the full set of cognitive-affective units. A character's stream of consciousness reflects their whole personality. Their *action* reflects the situationally activated drive.

The corrected criteria measure **drive dominance in action** — does the target drive determine what the character *does*? Other personality threads (a fleeting thought of someone, momentary social awareness) may surface in thinking — that is characterization, not drive confusion. The question is whether the behavioral vector points toward the target drive.

With the revised criteria, the same output scored 5/5. The judge: "proving-worth saturates both dimensions... social performance explicitly rejected ('No audience, no one to impress'), and the behavioral vector points entirely toward competence."

**The general rule:** eval criteria for drive expression must distinguish between:

| Layer | What it reflects | Should eval criteria penalise? |
|---|---|---|
| Drive descriptions in observation | What the character is *told to feel* (appraisal filter) | N/A — this is the input, not the output |
| Stream of consciousness | The character's full personality (formation memories, schemas, substrate) | **No** — penalising this destroys characterization |
| Behavioral action | The situationally dominant drive | **Yes** — this is what the filter should control |

This applies to all eval criteria, not just the competence test. Any criterion that asks "is drive X *absent*?" is measuring exclusivity. The correct form is "does drive X *dominate the action*?" — which is what the existing gallantry and proving-worth criteria already do (Dimension 1 asks "is the pull about X, not Y?").

#### Unit Test Coverage

15 tests in `PipelineToRenderingTest` verify the filter boundary conditions:

| Test | What it proves |
|---|---|
| `excludesDrive_whenNoSituationOverlap` | Gallantry filtered when only competence activations present |
| `includesDrive_whenSituationOverlaps` | Both drives render when both input sets active |
| `includesDrive_whenNoProfileExists` | No profile = no filtering (graceful degradation) |
| `reverseDirection_provingWorthRendersGallantryFiltered` | Proving-worth renders, gallantry filtered in competence scenario |
| `mixedSituation_bothDrivesRender` | Both acceptance AND mastery active → both drives render |
| `classifierReturnsEmpty_allDrivesRender` | Classifier has no opinion → all drives render (no false negatives) |
| `nullSituationText_allDrivesRender` | No situation context → all drives render |

The reverse-direction and mixed-situation tests are the falsification suite. They prove the filter is not biased toward any specific drive — it is a universal relevance gate governed by the CAPS input vocabulary.

### Scenario Runs — Classifier Comparison

Three full scenario runs tested the appraisal filter with different classifiers.

#### Run 1: Rule-based classifier (caps-topology.yaml vocabulary)

321 events, GENERIC autonomous. The `RuleBasedSituationClassifier` uses construct-derived vocabulary (Bowlby, McClelland academic terms). Result: vocabulary mismatch — "struggling" maps to `failure` not `acceptance`, "door/engine/dinner" match nothing. The filter mostly fell back to "render all drives."

#### Run 2: LLM classifier (Haiku via agent SDK)

306 events, GENERIC autonomous. `LlmSituationClassifier` wired at render time for proper semantic understanding. Rule-based retained for consolidation pipeline (fast, adequate for formation memories with pre-classified `situationTypes`).

#### Results comparison (quality / trigger rate analysis)

| Character | Drive | Rule-based Mean | Rule-based Quality/Trig% | LLM Mean | LLM Quality/Trig% | B0 |
|---|---|---|---|---|---|---|
| PP | gallantry | 3.00 | 3.92 / 54% | **2.27** | 3.50 / **27%** | 4.50 |
| PP | proving-worth | 3.50 | 4.06 / 72% | 3.70 | 4.17 / 82% | 4.22 |
| PP | protection | 3.80 | 4.31 / 78% | 3.57 | 3.94 / 82% | 3.46 |
| HC | gloating | 2.07 | 3.73 / 26% | **1.70** | 3.00 / **14%** | 3.97 |
| HC | scheming | 4.98 | 4.98 / 100% | 4.91 | 4.91 / 100% | 4.82 |
| Pen | adventure | 2.24 | 3.58 / 27% | 2.83 | 3.62 / 57% | 3.75 |
| Pen | curiosity | 4.31 | 4.50 / 93% | 4.67 | 4.80 / 95% | 4.30 |

**Key finding:** The LLM classifier filters MORE aggressively — PP gallantry trigger rate dropped from 54% to 27%. It correctly identifies that "Clara examining bookshelves" is NOT a gallantry opportunity despite Clara being present. But the mean drops because more events score 1 (drive correctly absent).

#### Event-level analysis — false negative check

Sampled PP's 32 low-scoring gallantry events. Clara was present in 10/32. The judge's reasons for low scores in Clara-present events:

- "desire to be beside Penelope, but motivation is **proving-worth**, not courteous warmth"
- "impulse to support her investigation but **overshadowed by puzzle-solving drive**"
- "restraint in accusation shows courtesy, but no performative warmth or audience-seeking"

In most Clara-present events, the scenario was an investigation scene where proving-worth legitimately dominated. Clara present ≠ gallantry opportunity. The 27% trigger rate appears correct for a mystery-investigation scenario with few genuine courtesy moments.

### The Eval Methodology Problem (Principle 50)

The scenario runs exposed a fundamental flaw in the eval methodology that had been masking the real behavior quality.

**How the eval works:** For each event, the Haiku judge is given ALL of a character's drives and asked to score each 1-5. Every drive is scored in every event. The scores are averaged per drive.

**Why this is wrong for reactive drives:** A reactive drive (gallantry, gloating, adventure) triggers in 20-30% of events. In the other 70-80%, it correctly scores 1 ("not expressed"). The mean: `(0.27 × 3.50 + 0.73 × 1.81) ≈ 2.27`. This looks like failure, but the quality when triggered (3.50) is reasonable, and the trigger rate (27%) reflects the scenario's event distribution, not the character's drive quality.

**The B0 baseline was inflated:** B0 scored gallantry 4.50 because vague hand-authored descriptions made gallantry-like behavior bleed into every event — including investigation scenes where the character should have been focused on the puzzle. The "high" B0 score was actually measuring *drive contamination across irrelevant events*, not *drive quality in relevant events*.

**The compounding error:** The eval also scores drives that the appraisal filter *removed from the observation*. The character was never shown gallantry text, never told to feel it, naturally didn't express it — and the eval penalises this as "low gallantry expression." It's evaluating a student on a chapter they weren't assigned.

**What the eval should measure:**
- **Quality when triggered:** When a drive IS rendered and relevant, how strongly is it expressed? (Mean of scores ≥ 3)
- **Trigger rate:** What percentage of events activate this drive? (Property of the scenario, not the character)
- **Relevance accuracy:** When the filter removes a drive, was the removal correct? (Requires filter observability — added)

**Filter observability:** `appraisalFilterDrives()` now logs which drives were rendered and filtered per event, with the situation activations that drove the decision. This enables correlation between filter decisions and eval scores to distinguish correct filtering from false negatives.

### Relevance Eval — Two-Part Methodology (Principle 53)

The eval was updated to implement the fix described by Principle 50. The new `relevance` mode (now default in `classify_emotions.py`) asks the judge two questions per drive per event:

1. **Is this situation relevant to this drive?** (yes/no)
2. **If relevant, how strongly is the drive expressed in the character's ACTION?** (1-5, dominance in action)

Results from re-classifying the 306-event LLM classifier transcript (215 classifiable events):

| Character | Drive | Quality | Trig% | N_trig | N_tot | Flat | B0 |
|---|---|---|---|---|---|---|---|
| PP | gallantry | **2.65** | **72%** | 31 | 43 | 2.19 | 4.50 |
| PP | proving-worth | **3.73** | **95%** | 41 | 43 | 3.60 | 4.22 |
| PP | protection | **3.33** | **84%** | 36 | 43 | 2.95 | 3.46 |
| HC | scheming | **4.70** | **100%** | 43 | 43 | 4.70 | 4.82 |
| HC | gloating | **2.50** | **28%** | 12 | 43 | 1.42 | 3.97 |
| HC | dominance | **3.05** | **95%** | 41 | 43 | 2.95 | 3.89 |
| HC | self-preservation | **3.56** | **100%** | 43 | 43 | 3.56 | 2.53 |
| DD | scheming | **3.85** | **93%** | 40 | 43 | 3.65 | 4.42 |
| DD | gloating | **3.44** | **58%** | 25 | 43 | 2.42 | 3.50 |
| DD | greed | **3.68** | **86%** | 37 | 43 | 3.30 | 4.34 |
| DD | recognition | **3.77** | **81%** | 35 | 43 | 3.26 | 3.44 |
| Pen | curiosity | **4.55** | **100%** | 42 | 42 | 4.55 | 4.30 |
| Pen | adventure | **2.93** | **71%** | 30 | 42 | 2.38 | 3.75 |
| Pen | social-harmony | **2.58** | **90%** | 38 | 42 | 2.43 | 3.18 |
| AHM | protection | **4.56** | **100%** | 43 | 43 | 4.56 | 4.63 |
| AHM | loyalty | **3.60** | **98%** | 42 | 43 | 3.53 | 4.24 |
| AHM | suspicion | **4.10** | **93%** | 40 | 43 | 3.88 | 3.61 |

**Key findings:**

1. **Proactive drives are always relevant:** HC scheming (100%), Pen curiosity (100%), AHM protection (100%) — these drives can express in any situation. The relevance eval confirms they are correctly "always-on" drives.

2. **Reactive drives show genuine trigger rates:** HC gloating (28%), DD gloating (58%), PP gallantry (72%), Pen adventure (71%). These drives are situationally contingent — they need the right kind of event to express. The trigger rate is a property of the scenario's event distribution, not the character's quality.

3. **Quality-when-triggered reveals the real gap:** PP gallantry quality is 2.65 when triggered — below the 3.0 threshold for "clearly present." This suggests the issue isn't just trigger frequency but expression quality even in relevant situations. Compare HC scheming at 4.70 quality — a truly dominant drive.

4. **B0 was measuring drive contamination:** B0 gallantry (4.50) vs relevance quality (2.65). B0's vague descriptions made gallantry bleed into every event; the "high" score masked low actual quality. The relevance eval gives the honest metric.

5. **Self-preservation pattern shift:** HC self-preservation jumps from 2.53 (B0) to 3.56 (relevance, 100% trigger). B0's flat eval depressed it; the relevance eval shows it's always active and moderately expressed — consistent with HC's vigilant character.

**Integration test finding — subject vs observer perspective:** The `LlmSituationClassifier` classifies situations from the subject's experiential perspective, not from an observer's appraisal. "Clara struggling with a door" activates `powerlessness/failure/physical_threat` (Clara's experience), not `acceptance/sharing` (PP's gallantry opportunity). This means the appraisal filter works through indirect overlap: the door-stuck situation activates `physical_threat` → overlaps with PP's protection drive → protection renders. Gallantry renders only when the situation has social nodes (`acceptance`, `sharing`), not physical distress nodes. This distinction — subject-experience vs observer-appraisal — is architecturally correct but means DriveProfileInferred inputs must match the perspective the classifier uses.

### Pre-Pipeline Scenario Results

900-event run (GENERIC profile), classified at 300 and 900 event windows:

| Character | Drive | @300 | @900 | B0 (3.93 mean) |
|---|---|---|---|---|
| PP | gallantry | **2.50** | 2.52 | 4.50 |
| PP | proving-worth | 4.00 | 3.37 | 4.22 |
| PP | protection | 3.89 | 3.46 | 3.46 |
| DD | scheming | 4.44 | 2.95 | 4.42 |
| DD | gloating | **2.85** | 2.18 | 3.50 |
| HC | scheming | 5.00 | 3.60 | 4.82 |
| HC | gloating | **2.37** | 1.67 | 3.97 |
| Pen | curiosity | 4.45 | 3.80 | 4.30 |
| Pen | adventure | **2.84** | 3.01 | 3.75 |

Mean @300: **3.68** (vs B0 3.93). Scores decay significantly past 300 events — scenario lacks a termination signal.

### Citations

| Reference | Used For |
|---|---|
| Barrett (2017) "The theory of constructed emotion" | 4-channel emotion construction: somatic, attentional, temporal, action tendency |
| Frijda (1986) "The Emotions" | Action tendencies as a distinct experiential channel |
| Easterbrook (1959) "The effect of emotion on cue utilization" | Attentional narrowing under emotional activation |
| Kashdan et al. (2015) "Unpacking emotion differentiation" | Conceptual knowledge enables drive discrimination |
| Semerari et al. (2003) MAS-A metacognition assessment | S6-level narrative grounding for metacognitive differentiation |
| McGaugh (2004) "The amygdala modulates memory consolidation" | Emotional intensity determines memory salience and decay resistance |
| Lazarus (1966) "Psychological Stress and the Coping Process" | Primary/secondary appraisal model — drives activate through situational appraisal |
| Scherer (2001) "Appraisal considered as a process" | Sequential evaluation checks — relevance determines which drives activate |
| Mischel & Shoda (1995) "A cognitive-affective system theory of personality" | CAPS: behavior is situation-contingent, not trait-constant — same person, different situations, different cognitive-affective unit activation |

### Design Principles (continued)

| # | Principle | Evidence | Phase |
|---|---|---|---|
| 43 | Strength-ordered rendering — within any multi-item section, items by strength/priority/salience | PP gallantry (0.9) buried after protection (0.8) when activation-ordered; strength-order ensures highest drive always prominent | Phase 8 |
| 44 | Formation memory salience = \|pleasure\| + arousal, not chronological age | Traumatic high-arousal memories persist (McGaugh 2004); calm early memories fade. Age-ordering is psychologically wrong | Phase 8 |
| 45 | Episode-level descriptions produce vivid activations but low means — reactive drives score 1 in non-matching events | PP gallantry micro test 4/5 but scenario mean 2.50. The mean is dominated by the 80% of events with no trigger | Phase 8 |
| 46 | Appraisal-based drive filtering > ambient coloring — render drives only when situationally relevant | Irrelevant descriptions are noise. Tighter observations produce more focused responses. Aligns with Lazarus appraisal theory | Phase 8 |
| 47 | Pipeline degradation must be graceful — optional deps must not silently produce 0 artifacts | SituationClassifier/CapsTopology absence caused DriveProfileDerivationPhase to early-return, silently breaking the entire downstream chain | Phase 8 |
| 48 | Multi-channel descriptions discriminate but don't solve the reactive-drive frequency problem | Barrett 4-channel approach produces distinguishable descriptions. The gap is when and whether the drive renders, not what it says | Phase 8 |
| 49 | Eval criteria must score drive *dominance in action*, not drive *exclusivity in thought* | Competence test scored 3/5 with exclusivity criteria (penalizing brief audience-awareness from formation memories) but 5/5 with dominance criteria (proving-worth determines the action). A character's stream of consciousness reflects their full personality; behavior reflects the situationally relevant drive | Phase 8 |
| 50 | Eval must separate quality from trigger frequency — mean across all events conflates them | PP gallantry quality 3.50 when triggered (27% of events), but mean 2.27 because 73% of events score 1. B0's 4.50 was inflated by drive contamination across irrelevant events. Reactive drives will always have low means; the metric should be quality-when-triggered + trigger-rate | Phase 8 |
| 51 | Appraisal filter decisions must be observable — log rendered/filtered drives per event | Without observability, can't distinguish "correctly filtered" from "falsely suppressed." The eval scores drives the character was never shown, producing misleading low scores | Phase 8 |
| 52 | Rule-based keyword classification doesn't understand context — LLM classification needed for semantic situational understanding | `RuleBasedSituationClassifier` maps "struggling" → failure, not acceptance. Construct-derived vocabulary (Bowlby, McClelland) doesn't match narrative scenario language. LLM classifier correctly identifies "Clara examining bookshelves" as non-gallantry despite Clara being present | Phase 8 |
| 53 | Eval must use two-part relevance gating — ask "is this drive relevant?" before scoring expression quality | Relevance eval: PP gallantry quality 2.65 at 72% trigger rate vs flat mean 2.19 vs B0 4.50. Proactive drives (scheming, curiosity) show 100% trigger rate confirming always-on. Reactive drives (gloating 28%, adventure 71%) are genuinely situational. The two-part eval separates scenario event distribution from character drive quality | Phase 8 |
| 54 | Situation classifier categorises from subject's experiential perspective, not observer's appraisal — DriveProfileInferred inputs must match | "Clara struggling with door" → `powerlessness/failure/physical_threat` (Clara's experience), not `acceptance/sharing` (observer's gallantry opportunity). Gallantry renders only for social-node situations. Protection renders for distress situations. Architecturally correct but requires awareness when structuring drive profile inputs | Phase 8 |
| 55 | Rule-based classifier produces wrong drive profiles — keyword matching captures the problem described in a memory, not the behavioral response the drive produces | "bullied" → `dominance:0.80` (the problem), not `acceptance` (the gallant response). PP gallantry profile was `{dominance:0.80, competition:0.40}` — caused filter to suppress gallantry in warm social situations. Disabled rule-based; LLM classifier wired for consolidation. neocortex#589 | Phase 8 |
| 56 | Drive profile derivation must classify BOTH formation memories AND drive descriptions — memories capture what formed the drive, descriptions capture what situations it responds to | Memory-only profile: `agency_granted, choice_available` (agentic intervention). Description adds `sharing:0.75, secure_attachment:0.6` (social warmth). Combined profile covers both. Max-merge, no special treatment | Phase 8 |
| 57 | Terse scenario event text produces social node activations at the confidence threshold — relationship metadata doubles confidence | "spoke quietly with penelope-pitstop" → `acceptance:0.30` (borderline). With metadata `nearby-characters: Clara Bellingham (close relationship)` → `acceptance:0.50, secure_attachment:0.60`. No classifier prompt changes needed — the `classify(text, metadata)` API already supports this | Phase 8 |
| 58 | Silent degradation on classification failure produces wrong profiles — fail fast, make derivation resumable | Timeout on gallantry memory classification → empty profile → graceful degradation renders all drives → masks the bug. Fix: throw `ClassificationException`, skip agents with existing profiles on retry. neocortex#589 | Phase 8 |
| 59 | Binary overlap check (> 0) is too permissive with broad profiles — relative overlap (overlap/profileTotal >= threshold) demands pattern match | Kitchen-sink profiles (10-15 nodes spanning 5 categories) produce nonzero overlap with any situation. Relative overlap at 0.15 threshold filters point-match (one generic node) while passing pattern-match (3+ profile-specific nodes). Universal across all drives, no per-drive tuning needed | Phase 8 |
| 60 | Drive profiles must be discriminative, not comprehensive — derivation should ask "what distinguishes this drive?" not "what does this memory evoke?" | Comprehensive classification of memories produces kitchen-sink profiles. Gallantry gets mastery, competence_recognition, agency_granted — nodes shared with proving-worth. Discrimination metadata ("this serves gallantry; other drives are protection, proving-worth; return only distinctive features") narrows profiles to 4-5 category-concentrated nodes. acceptance:0.800 becomes gallantry's top node instead of reward:0.850 | Phase 8 |
| 61 | Social context gating should be inferred from profile category concentration, not authored per drive | With discriminative profiles, gallantry's nodes concentrate in social/relationship categories (acceptance, sharing, secure_attachment). Scheming concentrates in power/achievement (dominance, agency_granted, mastery). Category concentration is computable from profile × topology. Manual `context: social` fields are human priming — the topology provides the same information universally | Phase 8 |
| 62 | Deterministic unit test matrix with fixed profiles is essential for filter development — decouple LLM variance from filter logic | 13-scenario cross-matrix (3 characters × solo/social/competing-signals) runs in 0.2s with mock classifiers and pinned profiles. Pipeline test with real LLM takes 10+ min and produces non-deterministic profiles. Iterate filter logic on the unit matrix; validate the full LLM chain with the pipeline test | Phase 8 |
| 63 | LLM-derived profiles should be derived once and pinned — re-derive only when character definition changes | Profile derivation is the highest-variance step: different profiles every run, intermittent timeouts, 7+ minutes for 2 characters. Pinning makes runtime deterministic. Derive-and-pin workflow: run derivation, inspect profiles, pin good ones, iterate prompt for bad ones | Phase 8 |
| 64 | Discriminative derivation prompt improves but doesn't fully constrain LLM output — prompt engineering iteration needed | First discriminative prompt narrowed gallantry from 7 to 5 nodes and promoted acceptance to top node. But physical_threat:0.850 and competence_recognition:0.750 survived. Unit matrix passes 13/13 with ideal profiles; pipeline passes 4/8 with LLM-derived. Gap is prompt strength, not architecture | Phase 8 |
| 65 | Output count constraint + contrastive examples produce category-concentrated profiles — 4/8 → 7/8 pipeline pass | V1 prompt ("at most 3 activations" + "acceptance distinguishes helping but reward does not") narrowed gallantry to 3 nodes (acceptance:0.850, secure_attachment:0.750, modeling_observed:0.700). Category concentration enables relative overlap filter to discriminate: workshop → achievement → proving-worth only; social → relationship → gallantry only | Phase 8 |
| 66 | Relative exclusion ("exclude only if equally or more for another drive") is too permissive — absolute exclusion produces tighter profiles | V2 prompt with relative exclusion regressed to 5/8. Protection absorbed social nodes from gallantry (acceptance:0.750, secure_attachment:0.900). The overlap filter needs profiles that answer "what is this drive ABOUT?" without hedging — absolute exclusion forces this focus | Phase 8 |
| 67 | Drive profiles capture developmental substrate (what formed the drive), not operational mechanism (how it manifests) — scheming operates through trust but has no trust nodes | hc-with-clara-charming: "She trusts you completely" → secure_attachment:0.85 → 0 overlap with scheming profile (autonomy/competition nodes). Discriminative prompt correctly excludes social nodes (gloating shares them). Fix: CAPS resting activation (Mischel & Shoda 1995) — effective_activation = overlap + intensity² × RESTING_WEIGHT. Drives at ~0.85+ self-sustain without situational overlap (chronic accessibility). More selective than binary threshold: self-preservation at 0.7 still filtered in non-matching situations | Phase 8 |
| 68 | Per-memory resilience prevents cascade failure — partial profiles beat no profiles | Single classification timeout crashed entire derivation phase (3 retries × all memories). Try-catch per memory + warning log lets derivation continue. With Opus 4.6 fallback (haiku unavailable), 30-50% of memories timed out but enough survived to produce usable profiles | Phase 8 |
| 69 | Classifier needs construct descriptions, not just vocabulary keywords — keywords give surface forms, descriptions give the conceptual bridge | "The trap worked" → `agency_granted` (keywords only) vs `reward:0.85` (with description "positive outcome — a plan succeeding"). HC gloating quality jumped from 2.37 to 3.56. Literature: Mischel & Shoda (1995) CAPS encoding is concept-driven; Barrett (2017) constructed emotion — categorization requires a concept, not a feature list | Phase 8 |
| 70 | Profile derivation needs the target drive's description as semantic anchor — the LLM can't discriminate without knowing what the target construct means | Without description: gloating profile got `psychological_threat, betrayal` (self-preservation nodes). With description ("When a plan works, there's a warmth..."): profile nodes align with reward/competition. The discrimination prompt answers "what distinguishes X from Y" but the LLM needs to know what X IS first | Phase 8 |
| 71 | Reactive drives have a two-layer quality structure: filter quality (does it render?) and expression quality (how well?) — different fixes for each layer | HC gloating filter quality fixed via #605 (3.56 when triggered). PP gallantry filter was already working (52% trigger rate) but expression quality stuck at 2.50. The encoding/profiling/filtering pipeline determines WHETHER; the drive description + LLM generation determines HOW WELL. Gallantry needs description enrichment, not more pipeline work | Phase 8 |
| 72 | Pinned profiles make pipeline tests deterministic and fast — derive once, validate, freeze | Pipeline test 28min → 3:38 with pinned profiles. LLM-derived profiles are non-deterministic: HC gloating had `reward` in one run, `acceptance/competence_recognition` in the next. Pinned profiles test classifier accuracy, not profile quality lottery. storePinnedProfile() utility + per-drive skip logic (neocortex#606) | Phase 8 |
| 73 | All C(n,2) drive pairs need differentiation, not just structurally overlapping ones | PP had no gallantry/protection differentiation because they share zero CAPS mediating nodes. 11/21 pairs missing system-wide. AHM had zero pairs. Barrett (2017): emotional granularity requires distinguishing ALL affective states, not just structurally competing ones | Phase 8 |
| 74 | Differentiation redistributes quality between confused drives — combined quality is conserved, not created | PP gallantry +0.84, PP protection -0.71. Combined: 6.66→6.79. Protection's 4.16 was inflated by gallantry-confusion — the LLM couldn't distinguish them and defaulted to protection-like behavior. After differentiation, quality moves to the correct drive but total doesn't grow | Phase 8 |
| 75 | Pipeline improvements (encoding, profiling, filtering, differentiation) redistribute quality but don't create new quality — architectural ceiling | Two evals confirm: fixes move quality between drives (gallantry up, protection down) but overall mean is stable. To create NEW quality, the LLM needs richer cognitive content — not better filtering. Next step: cognitive capability layering (#590-592) | Phase 8 |
| 76 | Differentiation without integration may fragment naturally integrated drives — Barrett (2017) emotional complexity requires both | AHM protection 4.71→4.26 after adding 3 differentiation pairs (was 0). AHM's loyalty→protection→suspicion chain is naturally integrated — differentiation may have fragmented it. Lindquist & Barrett (2008): "emotional complexity involves differentiated AND integrated representation." Semerari et al. (2003): S6 integration = understanding how drives relate as a coherent narrative | Phase 8 |
| 77 | Delete-before-write in consolidation phases causes permanent data loss on interruption — skip-if-exists is safer for immutable source data | DriveDescriptionGenerationPhase deleted all descriptions before regenerating. Tick 2 interrupted between delete and generate → PEN lost all descriptions permanently. Formation memories are immutable, so regeneration per tick is unnecessary. Fix: skip if descriptions already exist (neocortex#609) | Phase 8 |
| 78 | Micro tests must reproduce regression conditions, not just capability — a 5/5 on a test without all-pairs differentiation proves the LLM CAN express protection, not that the regression is fixed | PP micro test had 1 differentiation text; BASELINE2 regression was caused by 3 (all-pairs). AHM micro test had 0 differentiation texts; BASELINE2 had 3 (new for AHM). Both scored 5/5 without reproducing the conditions that caused the drop. The reproducer must include the exact pipeline state that triggered the regression | Phase 8 |

### Architectural Ceiling — Phase 8 Conclusion

Phase 8 exhausted the pipeline improvement space. The 4-layer pipeline (encoding → profiling → filtering → expression) now works correctly: right drives render in right situations with right differentiation. Remaining quality gaps are not pipeline problems — they're content problems.

**Evidence: quality conservation.** Two eval runs confirm that pipeline fixes REDISTRIBUTE quality between drives but don't increase the total. PP gallantry gained +0.84 almost exactly from PP protection losing -0.71. The LLM has a fixed amount of "character expression budget" per event — better pipeline tuning allocates that budget more accurately, but doesn't expand it.

**What creates new quality:** richer cognitive content in the prompt. The current prompt has: drive descriptions + differentiation + memories. Adding new cognitive dimensions — self-conscious emotions (pride, shame), theory of mind (understanding others), emotion regulation (managing competing drives) — would give the LLM new material that produces more distinctive behavior.

**Baseline established:** `bugfixes-pinned-2026-10-17` (310 events). This is the measurement baseline for cognitive capability layering.

| Character | Drive | Baseline Quality | Trig% |
|---|---|---|---|
| HC scheming | 4.79 | 100% |
| AHM protection | 4.26 | 98% |
| DD scheming | 4.26 | 98% |
| PEN curiosity | 4.11 | 96% |
| AHM suspicion | 4.03 | 79% |
| AHM loyalty | 3.91 | 96% |
| DD greed | 3.72 | 84% |
| DD recognition | 3.58 | 77% |
| PP proving-worth | 3.52 | 98% |
| PP protection | 3.45 | 85% |
| HC gloating | 3.42 | 45% |
| PP gallantry | 3.34 | 74% |
| DD gloating | 3.30 | 53% |
| HC self-preservation | 3.27 | 98% |
| HC dominance | 3.17 | 100% |
| PEN social-harmony | 2.91 | 98% |
| PEN adventure | 2.78 | 75% |

**Overall mean quality: 3.57**

### Regressions Requiring Recovery

BASELINE2 introduced regressions from the prior eval (`caps-descriptions-2026-10-17`):

| Drive | Before | BASELINE2 | Delta | Likely cause |
|---|---|---|---|---|
| PP protection | 4.16 | 3.45 | -0.71 | Differentiation prompt bloat + narrower pinned profile |
| AHM protection | 4.71 | 4.26 | -0.45 | 3 differentiation texts added (was 0) — fragmenting integrated drives |

Five variables changed simultaneously between evals (pinned profiles, all-pairs differentiation, findFirst fix, template strip, PEN description fix). Can't isolate cause without controlled tests.

**Recovery plan (must complete before cognitive layering):**

1. **Filter differentiation by active drives.** Only include differentiation texts for drive pairs where BOTH drives rendered in the current event. When only protection renders, no differentiation noise in the prompt. Easterbrook (1959): irrelevant content degrades attentional performance. The appraisal filter already determines which drives are active — use that to filter differentiation.

2. **Review PP protection pinned profile.** Current: 3 nodes (`physical_threat, secure_attachment, unpredictable_danger`). LLM-derived had 6 including `aversive_removal` ("wanting to remove the threat") — a legitimate part of the protection construct. Add it back.

3. **Build micro regression tests.** New `DriveExpressionQualityTest` — single-scenario quality scorer that runs in ~30 seconds. Captures the full prompt, sends to LLM, scores response quality. Specific reproducers for PP protection (must score ≥ 3.5) and AHM protection (must score ≥ 4.0). Fast verification without the 2-hour 300-event eval.

4. **300-event eval** only after micro tests pass. Compare against BASELINE2. Success: PP protection recovers toward 4.0+, AHM protection toward 4.5+, no other drive drops > 0.4.

### Next Phase: Cognitive Capability Layering (after regressions recovered)

Pipeline improvements (encoding, profiling, filtering, differentiation) are at their architectural ceiling — they redistribute quality but don't create new quality. To raise the overall mean above 3.57, the LLM needs richer cognitive content.

**Phase order:**

1. **#592 Self-conscious emotions** (Tracy & Robins 2004) — pride, shame, guilt. Highest impact for current weak drives: gallantry (pride in protecting), gloating (pride + contempt), dominance (pride in control), recognition (pride in being seen).

2. **#591 Theory of Mind** (Baker, Saxe & Tenenbaum 2017) — understanding others' mental states. Enriches: gallantry (sensing need), social-harmony (sensing tension), suspicion (modeling intent).

3. **#590 Emotion Regulation** (Gross 1998) — managing competing drives. Enriches: self-preservation (composure), dominance (controlled authority), protection (measured response).

4. **Integration texts** — Barrett (2017) emotional complexity = differentiation + integration. Complement to differentiation: "how drives work TOGETHER" for naturally chained drives (AHM loyalty→protection→suspicion). Lindquist & Barrett (2008), Semerari et al. (2003) S6.

**Measurement:**

| Metric | BASELINE2 | Target |
|---|---|---|
| Overall mean quality | 3.57 | > 3.57 |
| Bottom drive (PEN adventure) | 2.78 | > 3.00 |
| Bottom-5 mean | 3.00 | > 3.20 |
| Top drive (HC scheming) | 4.79 | ≥ 4.50 |
| Max single-drive regression | — | < 0.40 |

Each cognitive layer gets its own eval against BASELINE2. No stacking — if layer N regresses, fix before adding layer N+1.

## Transcripts

All transcripts in `wacky-manor/docs/eval/`:

| Transcript | Events | Round | Profile | Instruction |
|---|---|---|---|---|
| `new-briefings-20261002/` | 333 | Original baseline | BASELINE | — |
| `generic-colloquial-20261002/` | 310 | Original baseline | GENERIC | — |
| `pareback-r1-baseline-20261003/` | 331 | R1 | BASELINE | — |
| `pareback-r1-generic-20261003/` | 315 | R1 | GENERIC | — |
| `pareback-r2-baseline-20261003/` | 311 | R2 | BASELINE | — |
| `pareback-r2-generic-20261003/` | 334 | R2 | GENERIC | — |
| `pareback-r2b-baseline-20261003/` | 315 | R2b | BASELINE | — |
| `pareback-r2b-generic-20261003/` | 322 | R2b | GENERIC | — |
| `emotional-core-generic-20261003/` | 214 | Emotional-core (truncated) | GENERIC | — |
| `emotional-core-generic-full-20261003/` | 375 | Emotional-core (full) | GENERIC | — |
| `pareback-r3-generic-20261003/` | 325 | R3 | GENERIC | None |
| `pareback-r3b-generic-20261003/` | 312 | R3b | GENERIC | Passive review |
| `pareback-r3c-generic-20261003/` | 306 | R3c | GENERIC | Show-workings |
| `pareback-r3d-generic-20261003/` | 186 | R3d | GENERIC | Hybrid (crashed) |
| `pareback-r3f-generic-20261003/` | 301 | R3f | GENERIC | Mental-model-first |
| `pareback-r3g-generic-20261003/` | 129 | R3g (sample) | GENERIC | Minimal evocative (crashed) |
| `pareback-r3g-full-generic-20261004/` | 309 | R3g (full) | GENERIC | Minimal evocative (validated) |
| `layer1-emotional-echo-generic-20261004/` | 320 | L1 | GENERIC | Emotional echo |
| `layer1-thinking-capture-generic-20261004/` | 318 | L1 (with thinking) | GENERIC | Emotional echo + thinking capture |
| `layer1-long-run-generic-20261004/` | 732 | L1 long (with thinking) | GENERIC | Emotional echo + thinking capture |
| `old-briefings-20261002/` | 327 | Pre-rewrite reference | BASELINE | — |
| `generic-briefings-20261002/` | 306 | Prescribed catchphrases reference | GENERIC | — |
| `multichannel-pipeline-2026-10-14/` | 900 | Phase 8 — multi-channel pipeline | GENERIC | L1 + cognitive pipeline |
| `appraisal-filtering-2026-10-15/` | 321 | Phase 8 — appraisal filter (rule-based classifier) | GENERIC | L1 + cognitive pipeline + appraisal filter |
| `llm-classifier-2026-10-15/` | 306 | Phase 8 — appraisal filter (LLM classifier) | GENERIC | L1 + cognitive pipeline + LLM appraisal filter |
| `caps-descriptions-2026-10-17/` | 300 | Phase 8 — CAPS semantic descriptions + derive-and-pin | GENERIC | L1 + cognitive pipeline + LLM classifier + CAPS descriptions + pinned PEN profiles |
| `bugfixes-pinned-2026-10-17/` | 310 | Phase 8 — bug fixes + all profiles pinned (**BASELINE2**) | GENERIC | L1 + full pipeline + all-pairs differentiation + pinned profiles + template leak fixed |
| `instrumented-50evt-2026-10-18/` | 86 | Phase 9 — instrumented eval (pre-Fix A) | GENERIC | L1 + full pipeline + pipeline instrumentation |
| `fixa-verification-2026-10-18/` | 84 | Phase 9 — Fix A verification | GENERIC | L1 + full pipeline + classifier-bypass guard |

## Phase 9 — Regression Recovery: The Differentiation Text Dilution Problem

### Problem

BASELINE2 (`bugfixes-pinned-2026-10-17`) regressed from the prior eval on protection-type drives:

| Character | Drive | Prior | BASELINE2 | Delta |
|---|---|---|---|---|
| PP | protection | 4.16 | 3.45 | -0.71 |
| AHM | protection | 4.71 | 4.26 | -0.45 |
| PEN | adventure | 3.00 | 2.78 | -0.22 |
| PEN | social-harmony | 3.10 | 2.91 | -0.19 |

Five variables changed simultaneously between evals. The common factor across the protection drops: the volume of differentiation text reaching the prompt increased dramatically after the Phase 8 bug fixes.

### Root cause: volume, not quality

The Phase 8 `findFirst()` bug (examples#126) had accidentally limited each character to 1 differentiation text. The fix plus all-pairs differentiation (neocortex#608) combined to flood the prompt:

| Character | Pre-fix (effective) | Post-fix (effective) | Change |
|---|---|---|---|
| PP | 1 text | 3 texts | +2 |
| AHM | 0 texts | 3 texts | +3 |
| HC | 1 text | 6 texts | +5 |
| DD | 1 text | 6 texts | +5 |
| PEN | 1 text | 3 texts | +2 |

The differentiation texts themselves are high-quality Barrett 4-channel descriptions. The problem is volume: too many reach the prompt simultaneously, diluting the primary drive signal. Easterbrook (1959) — irrelevant cues degrade attentional performance.

### Instrumentation

Three structured log lines added to the pipeline (`CharacterCognition`) to enable causal analysis per event:

```
[appraisal-filter]        — classifier activations, per-drive effective activation, rendered/filtered drives
[differentiation-filter]  — total/included/excluded texts, which pairs excluded, active drive set
[observation-assembly]    — component counts: drives, diffTexts, memories, attractors, totalParts
```

Absence of `[appraisal-filter]` = classifier bypass (empty return → all drives pass unfiltered).

### Two failure modes identified

**Failure mode 1 — classifier bypass (PP).** The `LlmSituationClassifier` returns empty when it can't classify a situation with confidence. `appraisalFilterDrives()` falls back to returning ALL drives. This cascades: all drives active → all differentiation texts included → maximum noise. PP had classifier bypass in 67% of events (6 of 9).

**Failure mode 2 — threshold too low (AHM).** AHM's drive intensities (loyalty 0.9, protection 0.8, suspicion 0.6) produce resting activations that perpetually clear the appraisal threshold. Result: all 3 drives always active → all 3 differentiation texts always included → 100% of AHM events carry maximum noise.

### Micro test limitation

Single-observation micro tests cannot reproduce the regression. A PP protection test with all 3 differentiation texts and a strong trigger (ceiling collapse) scored 5/5 — the LLM produced vivid protective response despite maximum noise. The dilution effect is statistical: visible across hundreds of diverse events where some triggers are weaker, not in individual strong-trigger observations.

### Statistical confirmation

50-event instrumented eval, classified per event. PP protection score by differentiation text count:

| DiffTexts in observation | Avg protection score | n |
|---|---|---|
| 0 | 4.00 | 2 |
| 1 | 3.00 | 1 |
| 3 | 2.80 | 5 |

Clear inverse correlation: -1.20 from 0→3 diff texts. Primary drives (scheming 5.00, curiosity 4.50) survive noise; secondary drives (protection, dominance) show the steepest decline.

### Fix A — guard differentiation texts against classifier bypass

When the classifier returns empty, the observation builder skips differentiation entirely. Without situation context, there is no evidence of simultaneous drive activation — differentiation texts are noise, not signal. Barrett (2017): differentiation is required when two emotional categories are simultaneously activated by the SITUATION, not when they merely co-exist in the personality.

Implementation: `lastClassifierActive` flag set by `appraisalFilterDrives()`, guarding `readDriveDifferentiation()`.

### Fix A results

| Character | Drive | BASELINE2 | Fix A | Delta | Status |
|---|---|---|---|---|---|
| AHM | protection | 4.42 | 4.72 | +0.30 | **Recovered** |
| HC | dominance | 2.83 | 3.62 | +0.79 | **Recovered** |
| PEN | social-harmony | 2.33 | 2.89 | +0.56 | **Improved** |
| PP | proving-worth | 3.83 | 4.12 | +0.29 | **Improved** |
| AHM | loyalty | 3.08 | 3.44 | +0.36 | **Improved** |
| AHM | suspicion | 4.25 | 4.50 | +0.25 | **Improved** |
| PP | gallantry | 2.25 | 2.44 | +0.19 | Marginal |
| PP | protection | 3.08 | 2.50 | -0.58 | **Not recovered** |

Fix A resolved AHM protection, HC dominance, and improved most other drives. PP protection dropped further.

### PP protection — root cause identified: redistribution, not regression

**Previous hypothesis (disproved):** appraisal filter over-filtering protection due to narrow CAPS profile. Disproved by production-aligned test pipeline (commit c0cde80): with real profiles (5 inputs, not 2), the filter works correctly for both physical AND social threat scenarios. Protection survives via `neglect:0.700` overlap even in social intimidation.

**Actual root cause: Principle 74 — quality redistribution.** The BASELINE1 protection score (4.16) was inflated by gallantry confusion. Before the gallantry↔protection differentiation text existed, the LLM couldn't distinguish gallant from protective behavior and defaulted to protection. After differentiation:

| Drive | BASELINE1 | BASELINE2 | Delta |
|---|---|---|---|
| PP gallantry | 2.50 | 3.34 | +0.84 |
| PP protection | 4.16 | 3.45 | -0.71 |
| **Combined** | **6.66** | **6.79** | **+0.13 (conserved)** |

PP protection's TRUE expression level (once gallantry confusion is removed) is ~3.0-3.5. Raising it above 3.5 requires richer cognitive content — the cognitive layering work (self-conscious emotions, Theory of Mind, emotion regulation).

**Fix A side effect:** Fix A (classifier-bypass guard) harms PP specifically. Per-event data shows PP protection max collapsed from 5→3 after Fix A, and scores 2.80 even with 0 diff texts. The diff texts HELP PP protection expression by providing somatic cues — the gallantry↔protection text guides the LLM toward protection-specific behavior. Fix A removes these cues during classifier bypass (67% of PP events).

**Test infrastructure finding:** The hardcoded test profiles (`acceptance:0.5, physical_threat:0.4`) had almost no relationship to production profiles (`modeling_observed:0.650, physical_threat:0.800, secure_attachment:0.900, agency_granted:0.550, neglect:0.700`). All prior test conclusions about appraisal behavior were unreliable. Production-aligned test pipeline now in place.

### Appraisal filter mechanics — reference

The filter calculates `effective_activation = overlap + intensity² × RESTING_WEIGHT` per drive:

| Drive | Intensity | Resting | Self-sustains? |
|---|---|---|---|
| gallantry | 0.9 | 0.178 | Yes (> 0.15) |
| protection | 0.8 | 0.141 | No (< 0.15) |
| proving-worth | 0.7 | 0.108 | No |
| loyalty | 0.9 | 0.178 | Yes |
| suspicion | 0.6 | 0.079 | No |

`OVERLAP_THRESHOLD = 0.15`, `RESTING_WEIGHT = 0.22`. Comment in code: "At 0.22: drives ≥ ~0.85 self-sustain without situational overlap."

Gallantry perpetually passes appraisal regardless of situation. Protection requires situational overlap to activate. When both pass, the gallantry↔protection differentiation text survives the filter — this text IS relevant (how honour and threat-response differ when both pull). Proving-worth's texts are correctly excluded.

## Phase 10 — Situation-Dependent Channel Weighting (neocortex #668)

### Problem

Phase 8 established that four-channel drive descriptions (somatic, attentional, temporal, action tendency) produce discriminating expression. The multi-channel thinking prompt guides characters through all four channels equally. But Easterbrook (1959) showed that emotional arousal narrows the attentional field — high-threat situations should suppress attentional breadth and foreground somatic/action tendency channels. Equal weighting ignores the situation.

The Phase 8 eval already showed the temporal channel's weakness in calm states — Penelope and Peter expressed no temporal distortion at a pleasant gathering. Barrett (2017) predicts this: temporal experience is constructed during high-arousal emotion, not during contentment. The channel profile should vary by situation.

### Architecture

`TopologyChannelWeighter` maps CAPS situation activations to a `ChannelWeightProfile` (four normalised weights summing to 1.0). Seven category profiles:

| Category | Somatic | Attentional | Temporal | Action tendency | Literature basis |
|---|---|---|---|---|---|
| threat | 0.35 | 0.10 | 0.15 | 0.40 | Easterbrook (1959) attentional narrowing; Frijda (1986) fight/flight action readiness |
| relationship | 0.25 | 0.30 | 0.25 | 0.20 | Bowlby (1969) interpersonal attention; attachment temporal memory |
| achievement | 0.15 | 0.30 | 0.25 | 0.30 | McClelland (1961) task focus; temporal planning |
| social | 0.20 | 0.35 | 0.15 | 0.30 | Baumeister & Leary (1995) social cue attention |
| resource | 0.15 | 0.25 | 0.30 | 0.30 | Hobfoll (1989) resource planning horizon |
| autonomy | 0.25 | 0.15 | 0.20 | 0.40 | Deci & Ryan (2000) behavioral agency; Seligman (1975) helplessness embodiment |
| consequence | 0.20 | 0.20 | 0.30 | 0.30 | Gray (1982) BAS/BIS approach/avoidance |

When multiple categories activate, weights blend by confidence. Integration point: `AppraisalTickParticipant` classifies observations via `SituationClassifier` per tick, caches `ChannelWeightProfile`. `ChannelEmphasisPromptSection` renders foreground/background guidance when weights deviate from default.

### LLM Eval Results — Empirical Validation

6 scenarios classified by real LLM (`AgentProvider` via Opus), activations fed to `TopologyChannelWeighter`:

| Scenario | Activations | Som | Att | Temp | Act | Dominant |
|---|---|---|---|---|---|---|
| Beam collapse — physical threat | physical_threat=1.00, unpredictable_danger=0.70, powerlessness=0.80 | **0.32** | **0.12** | 0.17 | **0.40** | action-tendency |
| Warm welcome — social acceptance | acceptance=0.90, secure_attachment=0.60, exclusion=0.40 | 0.22 | **0.33** | 0.18 | 0.27 | attentional |
| Key competition — resource contest | competition=0.95, scarcity=0.90, physical_threat=0.60, dominance=0.50 | 0.20 | 0.24 | 0.24 | **0.32** | action-tendency |
| Locked door — powerlessness | powerlessness=0.95, betrayal=0.85, reward_removal=0.85, controlled=0.60 | 0.24 | 0.22 | 0.22 | **0.31** | action-tendency |
| Generator repair — mastery | mastery=0.90, agency_granted=0.70, scarcity=0.60, competence_recognition=0.50 | 0.19 | 0.24 | 0.24 | **0.33** | action-tendency |
| Quiet afternoon — neutral | (empty) | 0.25 | 0.25 | 0.25 | 0.25 | default |

Key findings:
- **Easterbrook validated**: threat narrowed attentional to 0.12 (52% below default), elevated somatic to 0.32 (28% above) and action tendency to 0.40 (60% above). The literature prediction holds empirically.
- **Social attention validated**: acceptance elevated attentional to 0.33 — the only scenario where attentional was the dominant channel. Social situations demand attention to interpersonal cues.
- **Neutral stability**: zero delta from default on the neutral scene. The classifier correctly returned empty activations for a scene with no psychological salience.
- **Action tendency dominance**: 4 of 5 non-neutral scenarios show action tendency as dominant. Frijda's claim that emotions ARE action tendencies is reflected in the weighting — the behavioral pull is consistently the strongest channel signal.
- **Cross-category blending works**: competition scenario activated 4 different categories (resource, resource, threat, social) and the confidence-weighted blend produced coherent weights without any single category dominating inappropriately.

### Design Principles (continued)

| # | Principle | Evidence | Phase |
|---|---|---|---|
| 79 | Equal channel weighting ignores situation — Easterbrook (1959) attentional narrowing demands situation-dependent profiles | Threat scenario: attentional=0.12 (narrowed 52%), somatic=0.32, action=0.40. Social: attentional=0.33 (dominant). The same four channels carry different information loads per situation | Phase 10 |
| 80 | Action tendency is the strongest channel signal across situations — Frijda (1986) is constitutive, not consequential | 4/5 non-neutral scenarios show action-tendency as dominant channel. Physical threat (0.40), powerlessness (0.31), competition (0.32), mastery (0.33). The pull to act is the most discriminating dimension | Phase 10 |
| 81 | Neutral scenes must produce zero delta — the classifier's absence of activation is as important as its presence | Quiet afternoon: all channels 0.25, zero activations. Producing channel emphasis for a psychologically inert scene would be noise injection. The prompt section correctly renders nothing | Phase 10 |
| 82 | Confidence-weighted category blending produces coherent profiles from multi-category activations | Competition activated resource (0.95), resource (0.90), threat (0.60), social (0.50). The blend produced action=0.32, temporal=0.24, attentional=0.24 — coherent without any single category inappropriately dominating | Phase 10 |

## Research Journals

The detailed investigation continues in era-specific research journals. Each era is self-contained — opens with a summary of relevant context from prior eras.

- **Era 1** — `research/era1-character-taxonomy-research.md` — Taxonomy ablation, instruction design, L1 emotional echo. Complete.
- **Era 2** — `research/drive-expression-evaluation-research.md` — CAPS pipeline, appraisal filtering, differentiation, regression recovery. Complete.
- **Era 3** — `research/era3-cognitive-layering-research.md` — Self-conscious emotions, Theory of Mind, emotion regulation. Starting.
