# Phase 2.7: Live LLM Narrator — Design Spec

**Date:** 2026-08-02
**Issue:** casehubio/examples#9
**Branch:** issue-9-narrator-wiring
**Stacked on:** feat/wacky-manor-poc
**Verdict gate:** Narrator panel shows entertaining LLM-generated running commentary

## Goal

Wire `NarratorAgent` to consume the accumulated event stream and produce live
LLM-generated narration in the Wacky Races narrator style. The narrator is
omniscient — it sees all events across all rooms and generates batched commentary
dispatched to the narrator panel and Qhorus audience channel.

## Design decisions

### Direct collection vs PartitionedObservationService

Issue #9 acceptance criteria originally stated "NarratorAgent receives
accumulated observations from PartitionedObservationService." During design,
this was revised: the narrator receives raw `ManorEvent` objects via direct
`collect()` calls at the same sites that publish to `ObservationService`.

Rationale: `PartitionedObservationService` is room-scoped and
visibility-filtered — it gives each character only events they can perceive.
The narrator is omniscient by design (sees all events, all rooms, all asides).
Routing events through `PartitionedObservationService` would require either
(a) a special "sees everything" observer that defeats the partitioning, or
(b) aggregating all per-character drains, losing compaction benefits.
Direct collection is simpler and architecturally correct — the narrator's
event pipeline is a separate concern from character observations.

The issue acceptance criteria will be updated to reflect this decision.

### Mode applicability

The narrator runs in AUTONOMOUS mode only. In SCRIPTED mode, narration comes
from trigger-fired events (static text from `triggers.yaml`) and scene beat
narration — these are hand-authored to match specific game states. Running the
live LLM narrator alongside would produce double-narration on the same
`/manor/audience` channel. AUTONOMOUS mode has no trigger narration, making
the live narrator the sole source of commentary.

## Architecture

### Component diagram

```
CharacterAgentLoop ──publish──→ ManorEventDispatcher
                                    ├── world.addEvent(event)
                                    ├── observationService.publishEvent(event)
                                    ├── narratorAgent.collect(event) [AUTONOMOUS only]
                                    ├── manorChannels.dispatch*(...)
                                    └── webEventBus.broadcast(...)

ScenarioOrchestrator ──publish──→ (same ManorEventDispatcher)

NarratorAgent
    └── SummarisationRunner<ManorEvent, String>
            ├── WindowPolicy.of(15_000ms, 5 events)
            ├── Compactor<ManorEvent> (wraps MechanicalCompactor)
            ├── NarratorSummariser (Summariser SPI → LLM call)
            └── EventStreamBus<String> (narrator output)
                     │
                     └──subscriber──→ ManorChannels.dispatchNarration()
                                      ManorEventBus.broadcast(narrator(...))
```

### NarratorAgent

Stateful class (not a CDI bean) created by `ScenarioOrchestrator` in
`runScenario()`. Lifecycle is scenario-scoped — same as `ObservationService`
and `CharacterAgentLoop`.

Responsibilities:
1. Owns a `SummarisationRunner<ManorEvent, String>` from casehub-blocks
2. Runs a virtual thread that calls `runner.tick(now)` in a loop
3. Dispatches narrator output to ManorChannels and ManorEventBus via
   an EventStreamBus subscriber

Constructor parameters:
- `Compactor<ManorEvent> compactor`
- `AgentProvider agentProvider`
- `ManorChannels manorChannels`
- `ManorEventBus webEventBus`
- `int eventThreshold` (default 5)
- `int timerSeconds` (default 15)

Internal constants:
- `NARRATOR_LEVEL = new EventLevel("narrator", 0)` — used for both
  input wrapping and output publication

The constructor creates:
- `WindowPolicy.of(timerSeconds * 1000L, eventThreshold)` — seconds
  converted to milliseconds at this single point
- `EventStreamBus<String>` with a subscriber dispatching to ManorChannels
  and ManorEventBus
- `SummarisationRunner<ManorEvent, String>(policy, compactor, summariser,
  outputBus, NARRATOR_LEVEL)`

Public API:
- `collect(ManorEvent event)` — wraps as
  `new LevelEvent<>(event, event.timestamp().toEpochMilli(), NARRATOR_LEVEL)`,
  delegates to runner
- `start(WorldState world)` — starts the narrator virtual thread
- `stop()` — sets a volatile `stopped` flag and interrupts the narrator thread.
  The thread loop checks both `stopped` and `world.isScenarioComplete()`.
  `collect()` remains safe to call after `stop()` — events buffer but may
  not be narrated if the final flush has already run.

### NarratorSummariser

Implements `Summariser<ManorEvent, String>`. The narrator's LLM call.

Receives compacted events (compactor already applied by SummarisationRunner).
Formats events into a prompt, calls `AgentProvider.invoke()` with the narrator
system prompt, returns `List.of(narrationText)`.

Timeout configuration (matching existing `NarratorAgent.narrate()` pattern):
- `AgentSessionConfig.of(systemPrompt, eventPrompt, Duration.ofSeconds(30))`
  — session-level timeout
- `.await().atMost(Duration.ofSeconds(60))` — caller-level Mutiny timeout

These timeouts bound the LLM call to at most 60 seconds. Failure propagates
through SummarisationRunner's `.handle()` block, which logs and continues.

Prompt format — chronological, room-grouped:

```
Narrate what just happened in 2-3 sentences:

[Kitchen]
- The Hooded Claw picked up the Rat Poison
- The Hooded Claw: "Oh, what a lovely kitchen..."

[Ballroom]
- Penelope Pitstop: "Why, this tea service is simply darlin'!"
- Peter Perfect entered the Ballroom
```

All events reaching the summariser originate from the three `collect()` call
sites (action, dialogue, aside), which always carry a non-null `room`.
Defensively, any event with a null room is grouped under `[General]`.

No feedback loop: narrator output dispatches to ManorChannels and
ManorEventBus subscribers directly — it is never fed back through
`collect()`. The `ManorEventDispatcher` does not call `collect()` for
narrator-originated events.

System prompt — the existing narrator prompt from `NarratorAgent.java`:
breathless, alliterative, dramatic, omniscient Wacky Races narrator style
with CAPITAL LETTERS for emphasis, 2-3 sentences max.

### Compactor integration

`MechanicalCompactor` already exists in wacky-manor. It implements the new
`Compactor<ManorEvent>` interface from blocks (casehubio/blocks#83).

The compactor applies two mechanisms:

**Supersession** — later events replace earlier ones sharing the same key:
- MOVE: keyed by `"move:" + characterId` → collapses to final position
- TAKE: keyed by `"take:" + target` → collapses to final take of same object
- INTERACT / USE: keyed by `"object-state:" + target` → collapses to final
  interaction with same object

Events without supersession keys pass through unchanged: WAIT, LOOK, GIVE.

**Dialogue deduplication** — identical dialogue lines (same
`characterId + "::" + description`) are deduplicated, keeping the first
occurrence only.

After supersession and deduplication, results are re-sorted by timestamp.

SummarisationRunner applies the compactor between drain and summarise
automatically.

### ManorEventDispatcher

Centralizes all event fan-out. Every event creation site calls one method on
the dispatcher instead of separately calling WorldState, ObservationService,
NarratorAgent, ManorChannels, and ManorEventBus.

Created by `ScenarioOrchestrator.runScenario()` after ObservationService and
(optionally) NarratorAgent. Passed to `CharacterAgentLoop` in place of
the individual services.

Responsibilities:
1. `world.addEvent(event)` — record in game state
2. `observationService.publishEvent(event)` — route to character accumulators
3. `narratorAgent.collect(event)` — feed narrator pipeline (when present)
4. Channel and WebSocket dispatch based on event type

CharacterAgentLoop receives the dispatcher instead of `ObservationService`,
`ManorChannels`, and `ManorEventBus` individually. This drops its parameter
count from 9 to 7 and removes all knowledge of downstream event consumers
from the character decision loop.

**Known issue — WorldState.addEvent() thread safety:**
`WorldState.eventLog` is a plain `ArrayList<ManorEvent>`. Multiple character
threads and the scenario thread already call `world.addEvent()` concurrently
today (pre-existing, not introduced by this spec). The dispatcher centralizes
these calls but does not add new concurrent callers. The fix belongs in
`WorldState` (e.g. `CopyOnWriteArrayList` or `Collections.synchronizedList()`),
not in the dispatcher — tracked as a separate issue (casehubio/examples#TBD).

### Hybrid trigger

`WindowPolicy.of(15_000, 5)` — narrate after 5 events accumulate OR after
15 seconds since the oldest buffered event, whichever comes first.

- **Bursts:** 5 events accumulate quickly during action → narrate promptly
- **Quiet periods:** Timer fires after 15s → narrate whatever is buffered
- **Empty buffer:** `shouldEmit()` returns false → no wasted LLM calls

Configurable via `application.properties`:
- `manor.narrator.event-threshold` — default `5`
- `manor.narrator.timer-seconds` — default `15`
- `manor.narrator.enabled` — default `true`

When `manor.narrator.enabled=false` (or mode is SCRIPTED):
- `ScenarioOrchestrator` does not create `NarratorAgent`
- `ManorEventDispatcher` receives `null` for the narrator parameter and
  skips the `collect()` call internally
- No narrator thread, no LLM calls, no narration dispatched
- All other scenario behavior unchanged

### Failure handling

SummarisationRunner's `onFailure` handler (casehubio/blocks#84) logs
the failed batch and continues. A missed narration is acceptable — the
next batch covers new events. No retry, no dead-letter.

The EventStreamBus subscriber wraps each dispatch target in independent
try-catch blocks:

```java
outputBus.subscribe(s -> true, event -> {
    try { manorChannels.dispatchNarration(event.payload()); }
    catch (Exception e) { log.warn("Qhorus narration dispatch failed", e); }
    try { webEventBus.broadcast(ManorWebSocketEvent.narrator(event.payload())); }
    catch (Exception e) { log.warn("WebSocket narration dispatch failed", e); }
});
```

If one dispatch target fails, the other still receives the narration.
This isolates Qhorus failures from WebSocket delivery and vice versa.

## Integration with ScenarioOrchestrator

### Creation

In `runScenario()`, after creating `ObservationService` and before launching
character threads:

```java
NarratorAgent narratorAgent = null;
if (narratorEnabled && mode == ScenarioMode.AUTONOMOUS) {
    narratorAgent = new NarratorAgent(
            compactor, agentProvider, manorChannels, webEventBus,
            narratorEventThreshold, narratorTimerSeconds);
    narratorAgent.start(world);
}

var dispatcher = new ManorEventDispatcher(
        world, observationService, narratorAgent,
        manorChannels, webEventBus);
```

`narratorTimerSeconds` is an `int` read from config. The constructor
converts to milliseconds internally via `timerSeconds * 1000L`.

### Event publishing

All event creation sites use the dispatcher. The three existing
`world.addEvent() + observationService.publishEvent()` call sites become
single `dispatcher.publish(event)` calls:

1. `ScenarioOrchestrator.runScenario()` — after action resolution
2. `CharacterAgentLoop.run()` — dialogue events
3. `CharacterAgentLoop.run()` — aside events

The dispatcher internally fans out to all consumers. No call site needs to
know about ObservationService, NarratorAgent, ManorChannels, or ManorEventBus.

### Thread lifecycle

Narrator thread starts after `observationService.init()`, before character
threads launch.

The narrator thread loop:

```java
while (!stopped && !world.isScenarioComplete()) {
    runner.tick(System.currentTimeMillis())
          .toCompletableFuture()
          .orTimeout(90, TimeUnit.SECONDS)
          .exceptionally(ex -> { log.warn("Narrator tick timed out"); return null; })
          .join();
    Thread.sleep(1_000);  // poll interval — WindowPolicy decides when to actually narrate
}
// Final unconditional drain — bypasses WindowPolicy to capture all remaining events
runner.flush()
      .toCompletableFuture()
      .orTimeout(90, TimeUnit.SECONDS)
      .exceptionally(ex -> { log.warn("Narrator flush timed out"); return null; })
      .join();
```

`orTimeout(90s)` is defense-in-depth — the LLM call's own 60-second Mutiny
timeout is the primary bound. `CompletableFuture.join()` is not interruptible
(`join()` internally calls `waitingGet()` which clears the interrupt flag), so
`orTimeout()` ensures the future always completes within a known bound even if
the Mutiny timeout fails. The `.exceptionally()` prevents `join()` from throwing
`CompletionException` when the timeout fires.

`runner.flush()` performs an unconditional drain of the EventAccumulator,
bypassing WindowPolicy. This ensures the final moments of a scenario are always
narrated regardless of event count or timer state. (Requires adding `flush()` to
`SummarisationRunner` — see Dependencies.)

### Shutdown ordering

After the game loop exits (`world.isScenarioComplete()` is true):

1. Dispatch scenario-complete events (Qhorus + WebSocket)
2. Join character threads (5-second timeout each, then interrupt)
   — after this, no new events arrive via `collect()`
3. `narratorAgent.stop()` — sets stopped flag, interrupts sleep
4. Join narrator thread (120-second timeout) — narrator runs its
   final `flush()` before exiting, which may include an LLM call
5. If narrator thread is still alive, interrupt and log warning

Character threads are joined first so the narrator's final flush captures
all events from the scenario's last moments. The 120-second join timeout
accommodates the worst case: a tick in progress (60s LLM timeout) plus
the final flush (60s LLM timeout).

### Aside filtering

The narrator sees aside events (villain monologues, character thoughts).
This is correct — the narrator is omniscient and should narrate these
dramatically. The narrator prompt already says "you know everyone's secrets."

### Interaction with scripted narration

The live narrator runs only in AUTONOMOUS mode. In SCRIPTED mode, narration
comes from trigger-fired narrator events (static text from `triggers.yaml`)
and scene beat narration, which dispatch directly via
`manorChannels.dispatchNarration()`. The NarratorAgent is not started.

**Rationale:** SCRIPTED mode exists for deterministic, repeatable scenarios
used in testing and demo. Trigger narration is hand-authored to match
specific game states. Running the live LLM narrator alongside would produce
double-narration — both a scripted and an LLM-generated narration for the
same event — on the same `manor/audience` channel. Disabling the live
narrator in SCRIPTED mode keeps the narration source clean and the mode's
deterministic guarantee intact.

## Event flow

```
1. Character acts → CharacterAgentLoop
2. Action resolved → ScenarioOrchestrator game loop
3. ManorEvent created with enriched metadata
4. dispatcher.publish(event) — single call site
5. ManorEventDispatcher fans out:
   a. world.addEvent(event) — game state
   b. observationService.publishEvent(event) — character observations
   c. narratorAgent.collect(event) — narrator pipeline [AUTONOMOUS only]
   d. manorChannels / webEventBus — UI dispatch
6. NarratorAgent's SummarisationRunner buffers the event
7. On next tick() where WindowPolicy fires:
   a. Events drained atomically (drainIfReady)
   b. Compactor<ManorEvent> applied (supersession)
   c. NarratorSummariser formats + calls LLM
   d. Narration published to EventStreamBus<String>
8. Bus subscriber dispatches to:
   a. ManorChannels.dispatchNarration() (Qhorus /manor/audience)
   b. ManorEventBus.broadcast(narrator(...)) (WebSocket → UI)
9. Narrator panel renders the narration
```

## Files changed

### New files
- `ManorEventDispatcher.java` — centralized event fan-out
- `ManorEventDispatcherTest.java` — unit test
- `NarratorSummariser.java` — `Summariser<ManorEvent, String>` implementation
- `NarratorSummariserTest.java` — unit test
- `NarratorAgentTest.java` — unit test with real SummarisationRunner, mock LLM

### Modified files
- `NarratorAgent.java` — rewrite from static utility to stateful class
- `MechanicalCompactor.java` — implement `Compactor<ManorEvent>`
- `ManorObservationRenderer.java` — change field type from `MechanicalCompactor` to `Compactor<ManorEvent>`
- `ScenarioOrchestrator.java` — create dispatcher, narrator (AUTONOMOUS only), pass dispatcher to loop
- `CharacterAgentLoop.java` — signature change: accept `ManorEventDispatcher` instead of `ObservationService`, `ManorChannels`, `ManorEventBus`
- `application.properties` — narrator config properties

### Test files
- `NarratorIntegrationTest.java` — `@QuarkusTest` + `@Tag("llm-eval")`, verdict gate

## Testing

### Unit tests (standard suite)

**NarratorSummariserTest** — no CDI, mock AgentProvider.
- Verify prompt formatting: events grouped by room, chronological order
- Verify LLM response returned as `List.of(narrationText)`
- Verify compacted events produce correct prompt (no duplicate MOVEs)
- Verify failure path: AgentProvider throws → CompletionStage fails gracefully

**NarratorAgentTest** — no CDI, real SummarisationRunner, mock AgentProvider.
- Publish 5 events → call tick() → verify narration dispatched to test subscriber
- Publish 2 events → tick() with fresh timestamp → no narration (below threshold)
- Publish 2 events → tick() with timestamp 16s later → narration fires (timer)
- Verify collect() after stop() is safe (no exception)
- Final flush: publish 3 events → stop() → verify final flush narrates remaining events
- Timeout: mock AgentProvider blocks indefinitely → tick orTimeout(90s) fires → no exception, loop continues
- Subscriber exception: one dispatch target throws → other target still receives narration

**ManorEventDispatcherTest** — no CDI, mock targets.
- Fan-out: publish single event → verify all targets called (world, observationService, narratorAgent, manorChannels, webEventBus)
- Null narrator: create dispatcher with null narrator → publish event → no NPE, all other targets called
- Exception isolation: world.addEvent() throws → observationService and narrator still called
- Event type dispatch: verify correct ManorChannels method called per event type (dialogue → dispatchDialogue, aside → dispatchAside, action → position dispatch for MOVE)

### Integration test (llm-eval profile)

**NarratorIntegrationTest** — `@QuarkusTest`, `@Tag("llm-eval")`.
- Start scenario with narrator enabled
- Wait for narrator events on WebSocket
- Assert narrator output contains dramatic language, character references
- Verdict gate: output reads like Wacky Races narrator, not a dry event log

## Config

```properties
# Narrator
manor.narrator.enabled=true
manor.narrator.event-threshold=5
manor.narrator.timer-seconds=15
```

## Dependencies

Requires `casehub-blocks` 0.2-SNAPSHOT with:
- **`Compactor<E>` interface** (casehubio/blocks#83, CLOSED) — merged and
  available in the published 0.2-SNAPSHOT. Hard prerequisite satisfied.
- **`SummarisationRunner.flush()`** (blocks issue to be filed) —
  unconditional drain bypassing `WindowPolicy`, needed for the narrator's
  final drain at scenario end. **Hard prerequisite** — the shutdown sequence
  cannot guarantee final narration without it.
  Implementation: adds a `flush()` method that calls `accumulator.drain()`
  instead of `accumulator.drainIfReady(now)`, then follows the same
  compact → summarise → publish chain as `tick()`. Trivial addition —
  the unconditional `drain()` already exists on `EventAccumulator`.
  Note: blocks#83 delivered `Compactor<E>` but not `flush()`. The published
  0.2-SNAPSHOT jar confirms `SummarisationRunner` has `tick()`, `collect()`,
  `clear()`, `size()` — no `flush()`. A separate blocks issue is required.

Also uses existing:
- `casehub-blocks` (SummarisationRunner, WindowPolicy, EventStreamBus)
- `casehub-platform-agent-api` (AgentProvider)

## Open questions

None — all design decisions resolved during brainstorming.

## References

- POC spec: `wacky-manor/docs/POC-SPEC.md` (Section 1.6 — Narrator)
- Vision: `wacky-manor/docs/VISION.md` (The Narrator section)
- Phase 2.6 spec: observation accumulator design (workspace)
- blocks hardening: casehubio/blocks#82 (Compactor SPI, failure recovery, atomic drain)
- Garden: GE-20260629-e8b16d (EventStreamBus.clear() gotcha)
- Garden: GE-20260724-7b07f5 (direct injection over event bus)
