# Structured Personality Composition in LLM Agents

Every LLM agent has a personality. Most of the time it's an accident — whatever
emerges from the collision of system prompt, training data, and the current
conversation. When agents need stable dispositions that compose across a
multi-agent system — when a team of agents must have distinct cognitive styles
that interact predictably — accident is not good enough. Psychology has spent a
century building frameworks for exactly this problem. The question is not whether
to use them, but how to make them compose.

This paper describes an architecture for structured personality composition: a
coordinate system where multiple psychology frameworks project onto shared axes
and compose through independent signal channels. The architecture is implemented
in the Eidos platform, tested through a four-layer experiment with five
characters, and evaluated by ten LLM-based judges. The evidence supports additive
composition across orthogonal frameworks while revealing a significant gap between
personality specification and behavioral control.


## 1. The Problem: Personality Without Architecture

The standard approach to agent personality is a paragraph of natural language in
the system prompt: "You are a cautious, detail-oriented analyst who values
precision." This works for a single agent. It fails the moment a second agent
enters the system.

The problem is composition. Two agents described in natural language have no
shared coordinate system. One is "cautious" and the other is "careful" — are they
the same? One "values teamwork" and another "prefers collaboration" — do they
conflict or reinforce? There is no way to answer these questions without parsing
prose, which returns us to the ambiguity the encoding was supposed to resolve.

Multi-agent systems need personality encodings that can be compared, composed, and
queried. An orchestrator needs to know: does this agent's conflict style
complement or clash with its partner's? Does the team have coverage across
cognitive styles? Is there a gap in role allocation? None of these questions are
answerable when personality lives in unstructured text.

What's needed is architecture: a shared coordinate system that multiple
personality frameworks can project onto, composition rules that determine what
stacks and what conflicts, and a rendering pipeline that converts structured
disposition into effective system prompts. That architecture is what I set out to
build.


## 2. Five Axes and Two Channels: The Coordinate System

The core design insight is that all personality frameworks, despite their surface
diversity, make claims along a small number of behavioral dimensions. The Eidos
platform normalises these into five disposition axes:

| Axis | Terms | What it governs |
|------|-------|-----------------|
| SOCIAL_ORIENTATION | collaborative, independent, facilitative | How collaborative or independent |
| RULE_FOLLOWING | strict, principled, flexible | How rigidly rules are followed |
| RISK_APPETITE | conservative, measured, bold | How risk-tolerant |
| AUTONOMY | directed, semi-autonomous, autonomous | How self-directed |
| CONFLICT_MODE | competing, collaborating, compromising, avoiding, accommodating | How conflict is handled |

The first four axes use terms from the Conscientiousness vocabulary, grounded in
Big Five personality science — the most replicated personality model in
psychology, validated across 50+ countries and measured definitively by the NEO
PI-R (Costa & McCrae, 1992). This is not an arbitrary choice. Big Five provides
the strongest empirical foundation available for personality dimension selection.
The Conscientiousness vocabulary IS Big Five, operationalised for agent
disposition encoding. There is no separate Big Five vocabulary module because the
axis terms ARE Big Five terms.

The fifth axis — CONFLICT_MODE — uses Thomas-Kilmann terms because no Big Five
dimension adequately captures conflict behavior. Thomas and Kilmann (1974) model
five strategies along two dimensions (assertiveness x cooperativeness), derived
from the Blake-Mouton Managerial Grid. This provides exactly the granularity
needed for multi-agent disagreement scenarios.

**The two signal channels.** Not all frameworks express personality the same way.
Some describe cognitive or behavioral style — *how the agent thinks and acts*.
Others describe team roles — *what the agent contributes*. These are different
kinds of claim, and conflating them produces incoherent encodings.

The axis channel carries disposition: a framework maps its terms onto the five
axes via `axisExactMatch()`, projecting its vocabulary onto Conscientiousness and
Thomas-Kilmann terms. Jungian cognitive functions, DISC behavioral types, and
Thomas-Kilmann conflict modes all use this channel. The result is a normalised
five-axis disposition profile that can be compared across agents regardless of
which framework produced it.

The prompt channel carries role: a framework's terms — labels and descriptions —
render directly into the system prompt as natural language. Belbin team roles and
SVO workflow roles use this channel. They answer "what does this agent
contribute?" rather than "how does this agent behave?"

These channels are independent. An agent with a Belbin Co-ordinator slot (prompt
channel) and a Jungian Te-dominant disposition (axis channel) carries both signals
without interference — the role describes what it does for the team, the
disposition describes how it thinks. This independence is what makes
cross-framework composition non-redundant.


## 3. Eleven Frameworks, Six Vocabularies

The platform maps eleven established frameworks to the `AgentDescriptor` data
model. Six are implemented as vocabulary enums with CDI-based registration; five
serve as reference — informing axis design or providing conceptual grounding
without separate implementation. The selection criteria: each implemented
framework must contribute signal that no other implemented framework provides.
Each excluded framework was excluded for a specific reason.

### Disposition vocabularies (axis channel)

**Jungian Cognitive Functions** provide the deepest cognitive model — eight
functions (Ti, Te, Fi, Fe, Si, Se, Ni, Ne) with full five-axis projection via
`axisExactMatch()`. Each function maps to specific Conscientiousness terms per
axis, plus a Thomas-Kilmann conflict mode. Functions carry continuous weights
[0.0-1.0], structural rules (shadow pairing, dominant-auxiliary category
constraints), and personality evolution semantics. Based on Carl Jung's
typological model (*Psychological Types*, 1921), operationalised for LLM agents
by the JPAF paper (arXiv:2601.10025), which demonstrates 100% MBTI alignment
across GPT-4, Llama, and Qwen using function-level specification. Independent
validation via activation steering (arXiv:2607.20803, July 2026) confirms
function-level personality control in LLMs. Jungian is the deepest framework in
the system — it subsumes DISC and projects onto all five axes.

**MBTI Types** exist as a convenience layer, not a standalone vocabulary. Each of
the sixteen types decomposes into its dominant and auxiliary Jungian cognitive
functions via `specializes()`. `MbtiTypeTerm.INTP.defaultProfile()` generates an
eight-function weight distribution automatically. The type label is emergent from
the weighted function stack — never injected directly. This resolves the MBTI
validity problem, which I address in detail in Section 4.

**DISC** maps four behavioral quadrants — Dominance, Influence, Steadiness,
Conscientiousness — with full axis projection via `axisExactMatch()`. Originated
by William Marston (1928), DISC has low scientific validity: it is essentially a
quadrant simplification of Big Five Extraversion x Agreeableness. But its
imprecision is bounded — it correlates with established dimensions, which limits
how wrong it can be. DISC is included because it is widely adopted in workplace
settings and provides a simpler entry point than Jungian for users who think in
four quadrants rather than eight functions. It is explicitly redundant with
Jungian; using both creates contradictory disposition encodings.

**Thomas-Kilmann** models five conflict strategies (competing, collaborating,
compromising, avoiding, accommodating) along two dimensions: assertiveness and
cooperativeness. It is the only framework in the system that addresses
CONFLICT_MODE — no personality framework covers conflict behavior adequately.
Essential for multi-agent scenarios involving negotiation, disagreement, or
deference.

### Slot vocabularies (prompt channel)

**Belbin Team Roles** define nine team contribution roles — Plant, Resource
Investigator, Co-ordinator, Shaper, Monitor Evaluator, Teamworker, Implementer,
Completer Finisher, Specialist. Based on Meredith Belbin's research at Henley
Management College (*Team Roles at Work*, 1993), observing real management teams
over nine years. Belbin answers "what does this agent contribute to a team?" and
operates exclusively through the prompt channel. There is no `axisExactMatch()`
on `BelbinTerm` — the role description carries the personality signal, not a
five-axis projection. This is deliberate. Belbin roles describe team
contribution, not cognitive style. Forcing them through axis projection would
conflate what an agent does with how it thinks.

**SVO** provides three lightweight agent workflow roles — Coordinator, Performer,
Evaluator — describing function within multi-agent orchestration.
Platform-specific rather than psychology-derived.

### Reference frameworks (informing, not implemented)

**Big Five / OCEAN** is not a separate framework — it IS the disposition axis
system. The Conscientiousness vocabulary terms are Big Five-grounded. No separate
implementation needed.

**Situational Leadership** (Hersey & Blanchard, 1969) describes how a leader
adapts to a follower's development level. Used backwards as a conceptual framing
for the autonomy axis: the S1 Directing to S4 Delegating arc provides an
intuitive image of the directed-to-autonomous progression. The value is the
mental model, not theoretical authority.

**Kirton Adaption-Innovation** (Kirton, 1976) models a single cognitive style
dimension from Adaptor (works within structure) to Innovator (challenges
assumptions). Its endpoints align with existing RULE_FOLLOWING (strict/flexible)
and RISK_APPETITE (conservative/bold) terms — confirming these axes from an
independent theoretical basis without adding new vocabulary.

**O\*NET and SFIA** are occupational competence frameworks — sources for
capability vocabulary (skill names), not personality. Orthogonal to disposition.
O\*NET Knowledge and Skill category names inform `AgentCapability.name` values.

### Excluded

**Margerison-McCann** covers the same conceptual territory as Belbin — team
contribution roles — with incompatible terminology. Implementing both creates
contradictory slot vocabulary. Belbin has broader global adoption and more
independent research.


## 4. The Jungian Rehabilitation: From Measurement Failure to Specification Success

MBTI has been the punching bag of personality science for decades. The critiques
are well-earned: four binary dichotomies (I/E, S/N, T/F, J/P) impose categorical
boundaries on continuous distributions. Roughly half of test-takers receive a
different type after one month. The Society for Industrial and Organizational
Psychology does not recommend MBTI for personnel decisions. As a measurement
instrument, it fails.

But these are critiques of measurement, not of the underlying type system. Jung's
original cognitive function model — which MBTI popularised and then simplified
into oblivion — describes eight cognitive processes with distinct behavioral
signatures. The JPAF paper demonstrates that function-level personality
specification produces 100% MBTI alignment across three model families, with
trait activation accuracy above 90% and personality shift accuracy of 100% for
capable models.

The distinction is epistemological. For humans, personality is measured from
observed behavior, and the assessment instrument introduces error. For LLM
agents, personality is specified — declared via structured data and injected
through the system prompt. No assessment instrument, no test-retest reliability
concern, no measurement error. The instability that invalidates MBTI for human
assessment does not exist when the type is specified rather than measured.

The Eidos implementation makes this concrete. `MbtiTypeTerm.ENFJ` does not inject
the label "ENFJ" into a prompt. It decomposes through `specializes()` into its
dominant function (Fe) and auxiliary function (Ni), each with continuous weights.
The eight-function weight distribution provides the behavioral specification. The
MBTI type is emergent — an observable property of the function stack, not an
identity imposed on the agent.

**Weight tiers and structural rules.** Functions carry continuous weights, not
binary presence. The weight range segments into dominant (0.31-1.00), auxiliary
(0.06-0.30), and undifferentiated (below 0.06). A reinforcement delta of 0.06
governs learning; a decay factor of 0.20 governs disuse. This makes personality
dynamic — functions strengthen with use and weaken without it.

Structural rules constrain which combinations are valid. Every function has a
shadow — its opposite-attitude counterpart (Ti/Te, Fi/Fe, Si/Se, Ni/Ne). Shadow
activation signals personality evolution, not random variation. Valid
dominant-auxiliary pairs require opposite categories: a Judging dominant needs a
Perceiving auxiliary. `compatibleAuxiliaries()` enforces this. A Ti-dominant,
Te-auxiliary profile is structurally invalid.

This is not cosmetic taxonomy. The function stack, with its weights and structural
constraints, provides the raw material for personality evolution — a domain where
Big Five's trait stability is a limitation and Jungian's dynamic model is an
advantage.


## 5. Composition: What Stacks, What Conflicts, What Wastes

Composition is the engineering challenge that motivated this work. Two personality
frameworks applied to the same agent can be additive (each contributes
independent signal), redundant (they claim the same territory), or inadvisable
(their combination produces unreliable encodings). The compatibility matrix makes
these relationships explicit.

**Additive pairs** operate through different channels or address different
constructs:

| Pair | Why additive |
|------|-------------|
| Jungian + Belbin | Cognitive style (axis channel) + team role (prompt channel) |
| Belbin + DISC | Role assignment (slot) + behavioral style (disposition) |
| Belbin + Big Five | Role + stable trait — orthogonal |
| Big Five + Thomas-Kilmann | Personality trait + conflict strategy — different constructs |
| O\*NET + Big Five | Occupational competence (capabilities) + behavioral trait |

**Redundant pairs** project onto the same axes or cover the same conceptual
territory:

| Pair | Why redundant |
|------|--------------|
| Jungian + DISC | Both project onto the same five axes; Jungian is strictly deeper |
| Jungian + Conscientiousness | Jungian projects onto all Conscientiousness axes via `axisExactMatch` |
| DISC + Big Five | DISC is a quadrant simplification of Big Five ExA |
| Belbin + Margerison-McCann | Same conceptual territory, contradictory terminology |
| SFIA + O\*NET | Both occupational competence; SFIA is an IT-specific subset |

**Inadvisable:** MBTI (human-assessed) combined with anything. Low test-retest
reliability contaminates any vocabulary built on human-assessed types.
Agent-specified MBTI (via Jungian function stacks) does not have this problem.

**Hierarchical:** MBTI (agent-specified) + Jungian is not composition but
decomposition. Types emerge from function stacks via `specializes()`. The
relationship is vertical.

**The composition principle:** additive composition happens across orthogonal
channels or constructs. Redundancy happens within the same channel when two
frameworks project onto the same axes. The channel architecture makes this
predictable — if two frameworks both use `axisExactMatch()`, they compete for the
same axis values. If one uses the axis channel and the other uses the prompt
channel, they contribute independent signal.

### Four named combination patterns

The **Belbin Profile** combines a Belbin slot with Conscientiousness disposition.
The slot answers "what does this agent contribute?" and the disposition answers
"how does it behave?" — independent questions with independent answers. The
conflict mode axis uses Thomas-Kilmann terms via an axis-level vocabulary
override.

The **Belbin + DISC Profile** pairs a Belbin slot with DISC disposition. Additive
when the DISC type diverges from the role's implied disposition: a Co-ordinator
(facilitative, measured) who is also a D-type (independent, bold) diverges on
both social orientation and risk appetite. The DISC type reveals behavioral style
the Belbin role does not predict.

The **Occupational Profile** pairs O\*NET or SFIA capabilities with
Conscientiousness disposition — technical competence plus behavioral traits. An
agent defined by what it can do and how it tends to behave, without a team role.

The **Jungian Profile** uses a weighted function stack with auto-derived axes. All
five disposition axes project from the function weights via `axisExactMatch()`. No
explicit axis values needed — the disposition emerges from the cognitive profile.
This pattern is best suited for agents that need personality evolution, because
the function stack provides structural rules for valid transitions.

**Anti-patterns worth naming:** DISC type names used as slot values (confuses
behavioral style with role assignment); Belbin role keys used as disposition
values (vocabulary category error); Thomas-Kilmann modes mapped to non-conflict
axes (conflict assertiveness is not risk tolerance); `delegation=true` inferred
from DISC Dominance (delegation is platform-semantic, not personality-semantic).


## 6. From Disposition to Governance

Personality frameworks describe how agents think and behave. Multi-agent platforms
need more than behavioral description — they need operational governance. Does the
agent's autonomy level imply supervision? Can it spawn sub-agents? What latency
expectations follow from its capabilities?

`BehavioralExpectations` bridges disposition and operational behavior through
three derivation methods.

`escalationExpected()` examines the autonomy axis. A `directed` agent implies
supervision — events should escalate to an orchestrator. An `autonomous` agent
implies the opposite. The vocabulary terms carry operational semantics: `directed`
is not just a personality label, it is a routing signal.

`delegationExpected()` checks whether the disposition profile includes delegation
authority. Only Belbin Co-ordinator sets `delegation=true` in the current
implementation. DISC D-types assign tasks but often maintain tight oversight; that
is not the same as sub-agent spawning. Delegation is platform-semantic, not
personality-semantic.

`latencyBound()` derives latency expectations from declared capabilities. A
reasoning-heavy capability implies longer processing. An agent with real-time
interaction capabilities implies tighter bounds.

This connection is what makes structured disposition encoding more than a
prompting convenience. When personality is structured data with defined
vocabularies, the platform can derive routing, escalation, delegation, and
resource allocation from it — operational behavior becomes personality-informed
rather than separately configured.


## 7. Testing the Hypothesis: A Four-Layer Experiment

The hypothesis: composition adds meaningful behavioral signal, and the additive
signal comes at a measurable cost. I tested this using five characters from the
Wacky Manor scenario — Penelope Pitstop (ESFJ), Hooded Claw (ENTJ), Ant Hill
Mob (ISFP), Dick Dastardly (ESTP), and Peter Perfect (ENFJ) — each with
detailed character briefings that include speech patterns, mannerisms, and
narrative tendencies.

Each character ran through an autonomous poisoned-tea scenario at four personality
layers:

**BASELINE** — character briefing only, no personality framework.

**JUNGIAN** — briefing plus a weighted Jungian cognitive function profile. Each
character's declared MBTI type decomposes into its function stack: Penelope as
Fe-dominant/Si-auxiliary, Hooded Claw as Te-dominant/Ni-auxiliary, Dastardly as
Se-dominant/Ti-auxiliary.

**BELBIN** — briefing plus a Belbin team role. Each character receives a role
aligned with their narrative function: Penelope as Teamworker, Hooded Claw as
Shaper, Dastardly as Resource Investigator.

**COMPOSITE** — briefing plus both Jungian disposition and Belbin role. The full
composition through two independent channels.

Three autonomous runs per layer. Twelve runs total. Two evaluation dimensions:
MBTI alignment (does the rendered prompt present as the declared type?) and
function activation (does the agent's behavior activate expected cognitive
functions?).


## 8. What the Evidence Shows

Thirty-one tests, all passing. Every run completes and produces results. The
differences across layers are in how the agents reason, not whether they succeed.

**Composition adds real behavioral signal.** Average turns to resolution: Baseline
6.3, Jungian 8.7 (+2.4), Belbin 7.3 (+1.0), Composite 10.7 (+4.4). The
composite increase (4.4 turns) is close to the sum of individual increases (3.4),
with a 1.0-turn interaction effect. Each framework adds cognitive context to the
system prompt — more context means more for the LLM to process before deciding.
The frameworks do not cancel each other out. They stack. The agent deliberates
more with richer personality. This is a real behavioral effect, not noise.

**The disposition pipeline works.** MBTI alignment evaluates the prompt: does the
rendered system prompt read as the declared MBTI type? Seven of ten profiles
aligned in the final run, eight of ten in the first. The personality framework
reaches the rendered prompt and presents correctly. The encoding pipeline — from
vocabulary terms through axis projection to prompt rendering — delivers what it
promises.

| Character | Expected | Jungian alignment | Composite alignment |
|-----------|----------|-------------------|---------------------|
| Penelope | ESFJ | Aligned | Not aligned |
| Hooded Claw | ENTJ | Aligned | Aligned |
| Ant Hill Mob | ISFP | Aligned | Aligned |
| Dastardly | ESTP | Aligned | Aligned |
| Peter Perfect | ENFJ | Not aligned | Not aligned |

**Function activation reveals where composition helps.** Function activation
evaluates the response: does the agent's behavior exhibit expected cognitive
functions? The results vary by function type, and the pattern is informative:

| Character | Target functions | Baseline TAA | Jungian TAA | Belbin TAA | Composite TAA |
|-----------|-----------------|-------------|-------------|------------|---------------|
| Penelope | Fe, Si | 0.5 | 0.5 | 0.5 | 1.0 |
| Hooded Claw | Te, Ni | 0.5 | 0.0 | 0.0 | 0.0 |
| Ant Hill Mob | Fi, Se | 0.5 | 0.5 | 0.5 | 1.0 |
| Dastardly | Se, Ti | 0.5 | 0.0 | 0.0 | 0.0 |
| Peter Perfect | Fe, Ni | 0.0 | 0.5 | 1.0 | 0.5 |

Fe (Extraverted Feeling — social harmony, group attunement) activates reliably
across all profiles, including baseline. The character briefings already encode
Fe-like behavior. The framework reinforces a signal the briefing already carries.

Ni (Introverted Intuition — convergent insight, singular foresight) never
activates. The FunctionActivationJudge consistently classifies it as Ne
(divergent possibility exploration). Both involve pattern recognition; an LLM
judge may systematically prefer the Ne label. This may be a judge calibration
problem rather than a framework limitation — a hypothesis the evaluation
infrastructure can test directly.

Te and Ti activation is inconsistent across profiles. Rich character briefings
overwhelm the systematic thinking disposition — a character described as
"theatrical villain who schemes elaborately" produces Ne-like behavior regardless
of a Te disposition.

**Composition genuinely improves some activations.** Composite TAA is higher than
any single-framework layer for Penelope (0.5 to 1.0) and Ant Hill Mob (0.5 to
1.0). For feeling and sensing functions, composition adds signal the LLM acts on.
The composition is not just additive overhead — it shifts behavior in the
direction the framework specifies.

**Composite variance is a feature.** The composite layer shows the highest
turn variance across runs: 6 to 13 turns, against Baseline's 6 to 7. A richer
personality model makes behavior less predictable — the agent sometimes
deliberates extensively and sometimes acts quickly. For compliance or safety
applications, this argues for simpler encodings. For creative or emergent
behavior — which is what this scenario demands — the variance is the point.
Predictability and personality richness trade off against each other.

**The briefing dominance finding is actionable.** Character briefings — rich with
speech patterns, mannerisms, and narrative tendencies — are the dominant
personality signal. The framework adds real but secondary signal. This is not an
architecture problem — it is a calibration problem. The framework delivers signal
to the prompt; the briefing currently overwhelms it for thinking and intuition
functions. The path to full alignment is engineering work: briefing-framework
coherence validation, stronger integration mechanisms, and judge calibration
(Section 9).

**Peter Perfect reveals a coherence gap.** The ENFJ profile expects Judging
behavior — structured, decisive, closure-seeking. But Peter Perfect's briefing
describes him as "gallant, volunteering, tunnel vision on Penelope" — which reads
as spontaneous and adaptive (Perceiving). The briefing contradicts the
disposition. A pre-flight coherence validator would flag this tension before the
experiment runs, letting the descriptor author resolve it. The J/P failure is
evidence that briefing-framework coherence checking is needed, not that the
framework cannot express J.

**The evaluation infrastructure.** The experiment used two of the platform's ten
evaluation judges. The full suite covers: MBTI alignment (MbtiAlignmentJudge),
cognitive function activation (FunctionActivationJudge), disposition term
presence in prompts (DispositionPresenceJudge), trait expression in output
(TraitExpressionJudge), behavioral profile match (BehavioralJudge), vocabulary
richness (VocabularyExpressivenessJudge), general prompt quality (PromptJudge),
profile proximity measurement (ProximityJudge), cross-agent differentiation
(PairContrastJudge), and personality evolution correctness
(PersonalityEvolutionJudge). Each judge uses LLM-based evaluation — personality
assessment in language models requires language model judges.


## 9. What Remains Open

The architecture is designed for extension. Adding a new framework means creating
an enum implementing `VocabularyTerm`, implementing `axisExactMatch()` for
axis-channel frameworks, and registering via a `VocabularyRegistrar` CDI bean.
The vocabulary registry discovers new vocabularies at startup. No platform code
changes required.

**Frameworks worth adding.** Full Big Five — currently only Conscientiousness is
implemented. Adding Openness, Extraversion, Agreeableness, and Neuroticism as
explicit vocabularies would provide the most scientifically validated personality
encoding available, and would subsume DISC entirely. Enneagram — nine types with
growth/stress dynamics that map to personality evolution (orthogonal to Jungian:
motivation versus cognition). Strength Deployment Inventory — relationship-focused
conflict behavior, complementary to Thomas-Kilmann. Values frameworks (Schwartz,
Rokeach) — what agents optimise for, not how they think. This last would require
a new disposition axis, VALUE_ORIENTATION, which is a larger architectural change.

**Frameworks not worth adding.** StrengthsFinder's 34 talent themes overlap
heavily with capabilities — talent is not personality. HEXACO adds
Honesty-Humility to Big Five, but for LLM agents honesty is a system constraint,
not a personality trait. Dark Triad measures pathological personality —
inappropriate for production agent design, though potentially relevant for
adversarial testing.

**Closing the gap: a concrete roadmap.** The experiment establishes three things:
the disposition encoding pipeline works (MBTI alignment), composition adds
measurable behavioral signal (additive latency, improved function activation for
feeling/sensing types), and rich briefing text currently overwhelms disposition
for thinking/intuition functions. The remaining gap is a calibration problem, not
an architecture problem. Closing it requires six specific engineering tasks:

**1. Briefing-framework coherence validation.** Build a pre-flight check that
detects when briefing text contradicts the disposition profile. Peter Perfect's
"gallant, volunteering, tunnel vision" reads as Perceiving, conflicting with
ENFJ's Judging disposition. A coherence validator flags this before experiments
run, letting the descriptor author resolve the tension. This is the single
highest-value improvement — it catches the class of problem the experiment
surfaced.

**2. Minimal briefing experiment.** Run the same twelve-run experiment with sparse
briefings ("you are an agent named Hooded Claw") to isolate the framework's
independent contribution. If function activation shifts with minimal briefings,
the framework works but rich briefings override it. If it does not shift, the
framework needs stronger integration. The current experiment cannot distinguish
these cases because every character has rich briefing text.

**3. Belbin axis implementation.** Belbin currently contributes only through the
prompt channel — slot label text rendered into the system prompt. Implementing
`axisExactMatch()` for Belbin roles would give it a structural axis channel. The
role would feed into disposition axis derivation alongside Jungian functions. The
compatibility matrix already defines the implied axis values per role: Shaper maps
to independent, flexible, bold, autonomous. This is a bounded implementation task
— the mappings are documented.

**4. Judge calibration.** The Ni/Ne misclassification may be a judge problem. Both
functions involve pattern recognition; an LLM judge may systematically prefer the
Ne label because divergent exploration is easier to identify in text than
convergent insight. Validate the FunctionActivationJudge against human evaluators.
Design Ni-specific scenarios that force singular convergent answers — not
possibility exploration.

**5. Stronger integration mechanisms.** The personality framework currently adds
context to the system prompt. For stronger signal: constrain response format based
on the dominant function (Te agents produce structured plans, not brainstorms);
use structured generation to enforce function-consistent outputs; add
function-specific instructions in the observation builder ("as a Te-dominant
thinker, prioritise systematic execution over creative exploration"). These are
prompt engineering refinements within the existing architecture, not architectural
changes.

**6. Full Big Five implementation.** Currently only Conscientiousness is a
vocabulary. Adding Openness, Extraversion, Agreeableness, and Neuroticism as
explicit vocabularies would provide the most scientifically validated personality
encoding available and would subsume DISC entirely. The mappings are
well-documented in personality science literature — this is medium-effort
implementation, not research.

**Activation steering as an alternative path.** Activation steering
(arXiv:2607.20803) demonstrates function-level personality control through model
internals rather than prompting. If activation vectors for cognitive functions can
be identified reliably, the vocabulary composition system could select activation
vectors rather than generate prompt text — bypassing prompt-level calibration
entirely. The vocabulary system produces the specification; activation steering
would produce the control. This is a longer-term research direction that
complements the six engineering tasks above.

**Academic validation.** Five characters across twelve runs demonstrates the
composition mechanism. Statistical significance for behavioral effects requires
larger-scale studies — more agents, more diverse scenarios, and the minimal
briefing experiment described above. The JPAF paper's methodology (100% alignment
across three model families) provides a template. The Eidos evaluation framework
— ten judges covering alignment, activation, presence, expression, behavior,
vocabulary, quality, proximity, contrast, and evolution — provides the
measurement infrastructure. The architecture is in production. The composition
mechanism works. What comes next is closing the calibration gap and scaling the
evidence.


## References

- Jung, C. G. (1921). *Psychological Types*. Rascher Verlag.
- Marston, W. M. (1928). *Emotions of Normal People*. Kegan Paul.
- Hersey, P. & Blanchard, K. H. (1969). Life cycle theory of leadership.
  *Training and Development Journal*, 23(5), 26-34.
- Thomas, K. W. & Kilmann, R. H. (1974). *Thomas-Kilmann Conflict Mode
  Instrument*. Xicom.
- Kirton, M. J. (1976). Adaptors and innovators: A description and measure.
  *Journal of Applied Psychology*, 61(5), 622-629.
- Rao, A. S. & Georgeff, M. P. (1991). Modeling rational agents within a
  BDI-architecture. In *Proceedings of KR*.
- Costa, P. T. & McCrae, R. R. (1992). *Revised NEO Personality Inventory (NEO
  PI-R) and NEO Five-Factor Inventory (NEO-FFI) Professional Manual*. PAR.
- Belbin, R. M. (1993). *Team Roles at Work*. Butterworth-Heinemann.
- Huang, J. et al. (2025). JPAF: LLM-based personality assignment framework.
  *arXiv:2601.10025*.
- Li, W. et al. (2026). Activation steering for cognitive function control in
  LLMs. *arXiv:2607.20803*.
