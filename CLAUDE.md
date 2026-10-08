# casehub-examples — Claude Code Project Guide

## Project Type

type: java
**Stage:** pre-release

**DSL parity:** YAML and Java are peer representations — see [DSL Style Guide](https://raw.githubusercontent.com/casehubio/parent/main/docs/DSL-STYLE-GUIDE.md) §YAML/Java Parity Principle

## What This Project Is

Multi-example repository for CaseHub platform modules. Each subdirectory is an independent Quarkus application demonstrating a platform capability.

**Active examples:**
- `helpdesk/` — Helpdesk scenario demo (Pages scenario engine, push WebSocket, case lifecycle)
- `wacky-manor/` — Multi-agent LLM demo with Wacky Races characters
- `ledger-examples/` — Ledger usage examples
- `qhorus-examples/` — Qhorus messaging examples
- `work-examples/` — WorkItems examples

**GitHub repo:** casehubio/examples

**Fork model:** origin = personal fork (`mdproctor/examples`), upstream = blessed (`casehubio/examples`)

## Build and Test

```bash
# Build wacky-manor only (from wacky-manor pom — parent reactor has unresolvable desiredstate deps)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn -f wacky-manor/pom.xml install -Dmaven.test.skip=true -s .mvn/slot-settings.xml

# Run wacky-manor tests (standard suite)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn -f wacky-manor/pom.xml test -s .mvn/slot-settings.xml

# Run LLM evaluation tests (requires API key, non-deterministic)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn -f wacky-manor/pom.xml test -Pllm-eval -s .mvn/slot-settings.xml

# Run wacky-manor dev mode (backend on 8180)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn -f wacky-manor/pom.xml quarkus:dev -Dquarkus.http.port=8180 -s .mvn/slot-settings.xml
# Use curl -4 http://127.0.0.1:8180 for API calls (IPv6 hits Maven launcher, not app)
# For generic character profile: add -Dmanor.scenario.profile=generic
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Never run `mvn install` or `mvn test` without `-pl <module>`.** The repo has many example modules; always target the specific one.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/examples

## Helpdesk

Scenario-driven demo for the Pages scenario engine. Serves the helpdesk UI with push WebSocket, case lifecycle, and interactive tutorials.

```bash
# Run helpdesk dev mode (backend on 8090, demo profile required for scenario endpoints)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl helpdesk -Dquarkus.http.port=8090 -Dquarkus.profile=demo -s .mvn/slot-settings.xml
```

**`-Dquarkus.profile=demo` is required.** Without it, the scenario verification endpoints (`/scenario/verify/*`, `/scenario/bootstrap/*`) are not registered and the "Start Demo" button does nothing.

The helpdesk serves its own UI at `http://127.0.0.1:8090/` — no separate frontend needed.

## Wacky Manor

POC spec: `wacky-manor/docs/POC-SPEC.md`
Vision: `wacky-manor/docs/VISION.md`

Phase 0–2.8 complete. 17 characters across 6 rooms. Phase 2.9 next: scale testing and game mechanics.

**Profiles:** `BASELINE` (default, Wacky Races), `JUNGIAN`, `BELBIN`, `COMPOSITE`, `GENERIC` (renamed characters, no pop-culture refs — for taxonomy validation). Set via `manor.scenario.profile`.

## Evaluation Framework

**Results location:** `wacky-manor/docs/eval/`

Three eval instruments, each measuring different dimensions:

### 1. Drive expression eval (per-drive, scenario-level)
**Script:** `wacky-manor/docs/eval/classify_emotions.py`
**Tracker:** `wacky-manor/docs/CHARACTER-EMERGENCE-FINDINGS.md`
**Profile:** GENERIC (no pop-culture priors)

Scores each drive (scheming, gloating, gallantry, etc.) 1-5 per event across a 200+ event scenario run. Produces per-character, per-drive averages. Baseline: BASELINE0 (mean 3.93).

```bash
# Run GENERIC scenario, save transcript to docs/eval/<run-name>/transcript.json
# Then classify:
python3 wacky-manor/docs/eval/classify_emotions.py docs/eval/<run-name>
```

### 2. Personality emergence eval (per-test, unit-level)
**Test:** `RelationalModelEvalTest` (tag: llm-eval)
**Tracker:** `wacky-manor/docs/eval/RELATIONAL-MODEL-EVAL-TRACKER.md`

14 tests scoring personality consistency 0-5. Single score per test. Current: 68/70.

### 3. Sleep derivation eval (per-dimension, cognitive quality)
**Test:** `SleepDerivationEvalTest` (tag: llm-eval)
**Results:** `wacky-manor/docs/eval/sleep-derivation-eval-*.json`

9 dimensions (behavioral-tendencies, emotional-capacity, somatic-markers, relational-expectations, differentiation) each scored 0-5. Current average: 4.1.

### Running evals
```bash
# Personality + sleep derivation (unit tests, ~15 min)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn -f wacky-manor/pom.xml test -Pllm-eval -Dtest="RelationalModelEvalTest,SleepDerivationEvalTest" -s .mvn/slot-settings.xml

# Drive expression (scenario run + classification, ~30 min)
# Start server with GENERIC profile, wait for 300+ events, save, classify
```

**Dependencies beyond Eidos/Qhorus/Blocks:**
- `casehub-engine-api` — GoalFormationStrategy/GoalRevisionStrategy SPIs for reflection-driven goal lifecycle
- `casehub-neocortex-memory-api` + `casehub-neocortex-memory` — salience-scored memory, reflection, relationship tracking
- `casehub-neocortex-cognitive-index` — CognitiveDerivationEngine for personality-derived cognitive defaults
- `casehub-neocortex-mindmap-intelligence` — ConsolidationScheduler for sleep-cycle memory consolidation
- `casehub-neocortex-caps-engine` + `casehub-neocortex-caps-api` — CAPS behavioral synthesis (disposition-weighted attractor settling)
