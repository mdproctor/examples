# Parameter Tuning Report — Elastic Session Scheduler (#138)

## Context

Full GENERIC-profile scenario run with all cognitive components active: SessionPipeline,
CDI bean chain, dual-rate accumulator, CognitivePipelineBuilder, LlmAppraisalStrategy,
InnerLifeOrchestrator.observe(), demand-driven classification.

## Run 1 — Pre-tuning Baseline

**Config:** max-sessions=12, gate-max=10, appraisal disabled, mood decay=4h, accumulator threshold=1.0

**Events:** 193 across 14 ticks | **Characters:** 5 (GENERIC profile) | **Profile:** penelope-pitstop, hooded-claw, dick-dastardly, peter-perfect, ant-hill-mob

### Findings

| Issue | Observation | Root Cause |
|-------|-------------|------------|
| Mood ceiling | All characters P/A/D ≥ 0.97 | Appraisal disabled + 4h decay constant too slow for 2min tick rate |
| Affiliation saturation | Affiliation = 1.0 for ALL characters (including villains) | Every character interaction counts as social evidence, overwhelms tier capacity |
| Static character drives | Zero adaptation from seed values across 14 ticks | LLM classification falls back to heuristic; accumulator threshold too high for reduced signal rate |
| Session starvation | 50% cognitive ticks heuristic-only (7/14) | 20 BDI fallbacks, 9 UserModel fallbacks; pool size (12) consumed by 5 character + narrator sessions |
| No character differentiation | Identical mood profiles across heroes and villains | Combined effect of disabled appraisal + session starvation |

### Session Pool Contention

CognitionCore catches AgentSessionLimitException with NO retry, falling back immediately.
AgentInvocationService (scenario character calls) has retry+backoff. Result: scenario calls
always win the pool; cognitive pipeline starves.

## Tuning Applied

| Parameter | Before | After | Rationale |
|-----------|--------|-------|-----------|
| `casehub.platform.agent.claude.max-concurrent-sessions` | 12 | 20 | 5 chars + narrator + 5 classify + cognitive pipeline = 14+ sessions needed |
| `casehub.platform.agent.gate.concurrency.max` | 10 | 18 | Gate must not bottleneck below pool max |
| `manor.appraisal.enabled` | false | true | LlmAppraisalStrategy evaluates situations against character drives and disposition |
| `manor.mood.decay-minutes` | 240 (hardcoded 4h) | 20 | decayTimeConstant/10 must be < tick interval for decay to fire |
| `manor.mood.max-displacement` | 1.0 (hardcoded) | 1.0 | Made externally configurable |
| `manor.classification.accumulator.threshold` | 1.0 | 0.5 | Faster drive adaptation with reduced LLM signal rate |
| `manor.classification.accumulator.slow-threshold` | 2.0 | 1.0 | Slow channel fires sooner for gradual drift detection |
| `manor.classification.accumulator.allowance` | 0.2 | 0.15 | Less noise filtering, more sensitivity |
| `manor.classification.accumulator.slow-allowance` | 0.15 | 0.10 | Less noise filtering on slow channel |
| `manor.cognitive.snapshot-interval-ticks` | 10 | 5 | More frequent observations for tuning |
| `manor.reflection.max-unreflected` | 5 | 3 | Earlier reflection triggers |
| `manor.goal.cooldown-ticks` | 10 | 5 | Faster goal formation cycles |

## Run 2 — Tuned Parameters

**Config:** max-sessions=20, gate-max=18, appraisal enabled, mood decay=20min, accumulator threshold=0.5

**Events:** 238+ across 15+ ticks (in progress) | **Session errors:** 0

### Improvements

| Metric | Run 1 | Run 2 | Change |
|--------|-------|-------|--------|
| Session limit errors | 20+ BDI, 9 UserModel | 0 | Pool headroom resolved starvation |
| LLM cognitive tick rate | 50% (7/14) | 57% (8/14) | Moderate improvement |
| Mood range (Penelope) | P=0.989 (ceiling) | P=0.674→0.951 | Natural fluctuation visible |
| Mood differentiation | All chars ≥ 0.97 | Range: -0.09 to 0.95 | Villains and protectors differ |
| Dominant drive (villains) | Affiliation (wrong) | Competence (correct) | Dick Dastardly, Hooded Claw now competence-driven |

### Character Mood Profiles at Tick 15

| Character | Pleasure | Arousal | Dominance | Delta P | Character |
|-----------|----------|---------|-----------|---------|-----------|
| Ant-Hill-Mob | -0.09 | +0.91 | -0.38 | -0.18 | Protectors struggling — feeling diminished, high vigilance |
| Dick Dastardly | +0.74 | +0.79 | +0.74 | -0.17 | Schemer meeting resistance — pleasure declining |
| Hooded Claw | +0.67 | +0.60 | +0.79 | +0.07 | Calm villain in disguise — highest dominance, low arousal |
| Penelope | +0.95 | +0.95 | +0.95 | -0.00 | Happy explorer — plateau near ceiling |
| Peter Perfect | +0.50 | +0.91 | +0.91 | -0.09 | Trying hard — moderate satisfaction, high arousal |

## Completed Run — Final Results (50 ticks, DAWN)

**514 events** | **50 ticks** | **6 rooms explored** | **111 classification audit entries**

### Mood Dynamic Ranges

| Character | Pleasure Range | Arousal Range | Dominance Range | Character |
|-----------|---------------|---------------|-----------------|-----------|
| Ant-Hill-Mob | [-0.34, +0.65] | [+0.38, +0.95] | [-0.38, -0.06] | Widest pleasure range, persistently negative dominance |
| Dick Dastardly | [+0.61, +0.95] | [+0.45, +0.95] | [+0.43, +0.95] | Consistently positive, moderate variation |
| Hooded Claw | [+0.24, +0.83] | [+0.18, +0.95] | [-0.32, +0.95] | MASSIVE dominance range — narrative arc captured |
| Penelope | [+0.60, +0.98] | [+0.59, +0.98] | [+0.60, +0.98] | Mostly positive, one significant dip |
| Peter Perfect | [+0.45, +0.96] | [+0.46, +0.96] | [+0.59, +0.96] | Moderate variation, mostly positive |

### Narrative Arcs Captured

1. **Hooded Claw's fall** — Dominance peaked at +0.95 (tick 20) then crashed to -0.32 (tick 35).
   His disguise failed and he lost control. Pleasure remained moderate throughout — accepting
   the situation but no longer dominant.

2. **Ant-Hill-Mob's suffering** — Pleasure hit -0.34 (tick 35), the most negative of any
   character. Then recovered to +0.65 by tick 50. Classic protector arc: distress → relief.

3. **Penelope's setback** — Stable at 0.95+ then dipped to 0.60 (tick 35) before recovering.
   Correlates with the Hooded Claw reveal timing.

4. **Dick Dastardly's independence** — Remained high throughout, decoupled from Hooded Claw's
   narrative. Pursuing his own schemes.

### System Health

| Metric | Run 1 | Run 2 | Improvement |
|--------|-------|-------|-------------|
| Session limit errors | 29+ | 6 | 79% reduction |
| LLM cognitive tick rate | 50% | 34% | Lower overall but pool not the bottleneck |
| Events generated | 193 (14 ticks) | 514 (50 ticks) | Full scenario completion |
| Mood dynamic range | 0.02 (all near 0.99) | 1.33 (Hooded Claw D) | 66x improvement |
| Character differentiation | None | Strong | Villains, protectors, heroes clearly distinct |
| Classification audit entries | 0 | 111 | Demand-driven classification active |

### Remaining Issues

1. **Affiliation still saturates** for social characters (Penelope, Peter, Ant-Hill-Mob).
   The SOCIAL need tier resting level (0.4) and slow decay rate (0.08) allow unbounded
   social evidence to push affiliation to 1.0. Fix: make NeedSatisfactionConfig externally
   configurable (currently hardcoded in ManorConsolidationBeans).

2. **Character drives still static.** Accumulator threshold lowered but no adaptation
   visible across 50 ticks. The LLM classifier needs to run more consistently — 34% LLM
   tick rate means most ticks generate no classification signals.

3. **Mood ceiling for positive characters.** Penelope plateaus at 0.95+ because positive
   appraisals (+0.3 max) dominate the 20-minute decay. Structurally correct (consistently
   positive interactions → high mood) but reduces dynamic range. Could tune maxDisplacement
   or reduce appraisal delta range.

## Code Changes

- `SessionPoolBeans`: PoolDeclaration max now reads from `casehub.platform.agent.claude.max-concurrent-sessions`
- `ManorConfig`: Added `MoodTuningConfig(decayMinutes, maxDisplacement)` with `toMoodConfig()` method
- `ManorConfigProducer`: Added `manor.mood.decay-minutes` and `manor.mood.max-displacement` properties
- `PlaybookOrchestrator`: Uses `config.mood().toMoodConfig()` instead of `MoodConfig.defaults()`
