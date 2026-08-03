---
layout: post
title: "Wacky Manor — When the Narrator Finds Its Voice"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-examples]
tags: [llm, multi-agent, narrator, summarisation, event-driven, casehub-blocks]
---

# Wacky Manor — When the Narrator Finds Its Voice

**Date:** 2026-08-03
**Type:** phase-update

---

## The problem: multi-agent systems produce events, not stories

Five LLM characters are scheming, sneaking, and stumbling through a haunted
mansion. Every turn produces events — dialogue, movement, asides, interactions.
A human watching the UI sees a wall of text: disconnected actions scrolling past
faster than they can read.

What's missing is the voice that ties it together. The breathless cartoon narrator
who says: "And JUST when our heroes thought the Kitchen was SAFE, the Hooded Claw
slithers in with a smile SO sinister it could curdle MILK!"

This is Phase 2.7 of Wacky Manor — wiring a live LLM narrator that consumes the
event stream and generates running commentary in real time.

## Why this matters beyond a cartoon demo

The narrator pattern is a general multi-agent architecture concern: **how do you
turn a stream of low-level agent events into a coherent higher-level summary,
continuously, without blocking the agents that produce the events?**

This shows up in:
- **Monitoring dashboards** that narrate system events instead of showing raw logs
- **Meeting summarisers** that periodically synthesise discussion threads
- **Incident timelines** that turn alert sequences into readable narratives
- **Game commentary** systems for any real-time simulation

The narrator is the first consumer of the summarisation pipeline that isn't a
character agent — it's an observer. That distinction drove most of the design
decisions.

## The design question: where does the narrator get its events?

The obvious answer is "from the same observation pipeline the characters use."
But the observation pipeline (`PartitionedObservationService`) is deliberately
scoped — it gives each character only events visible from their room, filtered
by aside privacy. The narrator is omniscient. Routing everything through a
visibility-filtered pipeline would require either a special "sees everything"
observer (defeating the point of partitioning) or aggregating all per-character
drains (losing compaction benefits).

The answer: **direct collection on a separate pipeline.** The narrator receives
raw `ManorEvent` objects at the same call sites that publish to the character
observation service, but accumulates them independently. Two pipelines, same
source, different consumers — characters get room-scoped filtered observations,
the narrator gets everything.

## What already existed in casehub-blocks

The surprise in this build was how much infrastructure already existed. The
`SummarisationRunner` in casehub-blocks implements exactly the narrator's core
loop: accumulate events → trigger on a policy (count or timer) → compact →
summarise → publish output. It was designed for the L1-L2 summarisation
pipeline but the pattern is identical.

The narrator is essentially: a `SummarisationRunner<ManorEvent, String>` with
a narrator-specific `Summariser` implementation that calls an LLM.

But the narrator also surfaced gaps in blocks that needed filling before it
could work:

| Gap | What was missing | Fix |
|-----|-----------------|-----|
| **Compaction SPI** | No hook for mechanical event deduplication before LLM summarisation | Added `Compactor<E>` interface — applied between drain and summarise |
| **Failure recovery** | If the LLM call failed, drained events were silently lost | Added `onFailure` handler — logs batch, continues |
| **Final drain** | `tick()` respects `WindowPolicy` — at shutdown, remaining events below threshold were lost | Added `flush()` — unconditional drain bypassing the policy |
| **Thread safety** | `tick()` wasn't synchronized — concurrent callers could race on drain | Synchronized `tick()`, added atomic `drainIfReady()` |

Seven issues total (casehubio/blocks#82), shipped in a single epic before the
narrator implementation started. The narrator drove platform improvements — the
demo-as-requirements-driver pattern working exactly as intended.

## The architecture that emerged

The design review (4 dimensions, 45 issues, $37) reshaped the spec significantly.
Two changes stand out:

**ManorEventDispatcher** — the review's structure dimension identified that
the narrator created a triple-publish pattern: every event site called
`world.addEvent()`, `observationService.publishEvent()`, and
`narratorAgent.collect()` separately. The fix: a centralized dispatcher that
receives one `publish()` call and fans out to all consumers. This dropped
`CharacterAgentLoop`'s parameter count from 9 to 7 and eliminated all knowledge
of downstream event consumers from the character decision loop.

**AUTONOMOUS-only mode** — the review's coherence dimension caught that running
the live narrator alongside SCRIPTED mode's trigger-fired narration would produce
double commentary on the same channel. The fix: the narrator only starts in
AUTONOMOUS mode, where no trigger narration exists. Clean separation.

## The hybrid trigger

The narrator can't call the LLM per-event — too expensive, too spammy. But a
pure timer misses bursts and narrates silence. The solution is
`WindowPolicy.of(15_000, 5)`: narrate after 5 events accumulate OR after 15
seconds since the oldest buffered event, whichever comes first.

- **Bursts** (5+ events in quick succession): narrate promptly
- **Quiet periods** (< 5 events, 15s passes): narrate whatever is buffered
- **Empty buffer**: `shouldEmit()` returns false — no wasted LLM calls

The narrator thread polls `tick()` every second. `WindowPolicy` decides when to
actually fire. Between tick and narration, `MechanicalCompactor` collapses
superseded events (5 MOVE events for the same character become 1) so the LLM
prompt stays compact.

## What I learned

**Platform gaps surface at consumption boundaries.** The summarisation pipeline
worked fine for its original use case. The narrator — a different kind of
consumer — found 7 gaps. Building diverse consumers is how you find the missing
abstractions.

**Design review pays for itself in architecture.** ManorEventDispatcher wasn't in
the original spec. It emerged from a structure review that cost $10. The
alternative — discovering the coupling during implementation and refactoring
after the fact — would have cost more in both time and test rework.

**The narrator pattern is a reusable block.** Accumulate → hybrid trigger →
compact → LLM summarise → dispatch. The wacky-manor-specific parts are the
compactor rules and the narrator prompt. Everything else is generic
`SummarisationRunner` infrastructure from blocks. A future monitoring narrator
or meeting summariser would swap the `Summariser` implementation and compactor
rules, reusing the rest.

## What's next

Phase 2.7's verdict gate: "narrator panel shows entertaining LLM-generated
running commentary." The integration test exists (`NarratorIntegrationTest`,
`@Tag("llm-eval")`). Running it with a live LLM is the verdict — does the
narrator sound like a cartoon, or like a log file?

After that: Phase 2.8 (NPC system — scripted fixtures that drive quests) and
Phase 2.9 (scale to 6 rooms — the full mansion).
