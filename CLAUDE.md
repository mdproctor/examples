# casehub-examples — Claude Code Project Guide

## Project Type

type: java
**Stage:** pre-release

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
# Build wacky-manor only
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl wacky-manor -s slot-settings.xml

# Run wacky-manor tests (standard suite)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl wacky-manor -s slot-settings.xml

# Run LLM evaluation tests (requires API key, non-deterministic)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl wacky-manor -Pllm-eval -s slot-settings.xml

# Run wacky-manor dev mode (backend on 8180, frontend on 5173)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl wacky-manor -Dquarkus.http.port=8180 -s slot-settings.xml
npm --prefix wacky-manor/src/main/webui run dev
# Use curl -4 http://127.0.0.1:8180 for API calls (IPv6 hits Maven launcher, not app)
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Never run `mvn install` or `mvn test` without `-pl <module>`.** The repo has many example modules; always target the specific one.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/examples

## Helpdesk

Scenario-driven demo for the Pages scenario engine. Serves the helpdesk UI with push WebSocket, case lifecycle, and interactive tutorials.

```bash
# Run helpdesk dev mode (backend on 8090)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl helpdesk -Dquarkus.http.port=8090 -s slot-settings.xml
```

The Pages examples gallery (in casehub-pages) connects to this server for the Server tab — the helpdesk scenario demo will not work without it running. The Pages frontend is served separately via `yarn workspace @casehubio/pages-examples run serve` in the casehub-pages repo.

## Wacky Manor

POC spec: `wacky-manor/docs/POC-SPEC.md`
Vision: `wacky-manor/docs/VISION.md`

Phase 0–2.8 complete. 17 characters across 6 rooms. Phase 2.9 next: scale testing and game mechanics.

**Dependencies beyond Eidos/Qhorus/Blocks:**
- `casehub-engine-api` — GoalFormationStrategy/GoalRevisionStrategy SPIs for reflection-driven goal lifecycle
- `casehub-neocortex-memory-api` + `casehub-neocortex-memory` — salience-scored memory, reflection, relationship tracking
