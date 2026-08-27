---
title: "The Modal That Blocked Everything"
date: 2026-08-27
author: mdp
entry_type: note
subtype: diary
projects: [casehubio/casehub-pages]
series: issue-334-dsl-and-scenario
tags: [scenario-engine, debugging, web-components]
---

# The Modal That Blocked Everything

The helpdesk scenario — our first end-to-end demo — was stuck. Hit Start Demo, click Resume, watch it fill in Alice's name, describe her laptop issue, submit the ticket... then freeze at 12%. Every time. The scenario engine reported "connected" and "not paused" but nothing moved.

The investigation started with a simpler theory: the engine had bugs. The work item endpoints were returning 500 on claim, start, and complete. But running the `HelpdeskLifecycleTest` — the same full lifecycle, same claim/start/complete calls — came back green. All 200s. The 500s were from a stale server running old artifacts. A restart fixed that.

With the engine confirmed working, the scenario itself was the problem. The architecture has three moving parts: a server-side orchestrator that owns the step state machine, a WebSocket that carries dispatch-sequence messages to the browser, and a handler in the browser that executes DOM actions (click buttons, fill forms, show spotlights). Claude traced the message path — intercepted WebSocket events, verified the handler received dispatches, confirmed it processed `ready` probes correctly. The handler was alive. The server was sending control messages. But no `dispatch-sequence` messages arrived after the initial batch.

The root cause was in the handler's `executeSequence` loop. When a modal slide (like "What is a Case?") is shown, it creates an `activeDeck` — a full-screen overlay with slides, navigation dots, keyboard controls. The handler sends the step-result immediately (the server doesn't wait for the user to read the slide). But the next step might not be a modal — it might be a spotlight, a form fill, or a panel narrative. When a non-modal step encounters an active deck, the loop does this:

```typescript
if (activeDeck) {
    stepQueue.unshift(step);
    await new Promise<void>((resolve) => { resumeResolve = resolve; });
    continue;
}
```

It pushes the step back and waits forever. The deck's dismiss callback resolves `resumeResolve`, but only when the user clicks through all slides or hits Escape. In auto-play mode (after clicking Resume), nobody dismisses the deck. The loop is stuck in an infinite cycle: check activeDeck → still there → re-queue → wait → Resume resolves the wait → check activeDeck → still there.

The fix distinguishes paused from auto-play:

```typescript
if (activeDeck) {
    if (paused) {
        stepQueue.unshift(step);
        await new Promise<void>((resolve) => { resumeResolve = resolve; });
        continue;
    }
    activeDeck.dismiss();
}
```

When stepping manually, the modal blocks until the user reads it — that's the intended experience. When auto-playing, the deck auto-dismisses and the loop continues. We also added `activeDeck.dismiss()` to the Resume and Step control handlers, so resuming while a modal is visible clears it immediately.

After the fix, the scenario ran from 0% to 100% unattended. Alice's ticket submitted, classified as HARDWARE, routed to hw-specialist, claimed, resolved with "BIOS reset resolved the boot loop. Firmware rollback applied." The notification appeared in the dashboard. Case completed.

The compact controller view came out of the same session — the full outline tree was too tall for a floating overlay. It now shows a 3-row sliding window (previous step, current step, next step) with a ⊞/⊟ toggle to expand to the full tree when needed.

The scenario engine is now genuinely usable for demos. But the investigation surfaced a gap: there's no scenario authoring guide. The YAML format, available actions, trigger syntax, modal slides, spotlights — it's all discoverable from the helpdesk example, but nothing is documented as a reference. Any future LLM trying to write a scenario would need to reverse-engineer the format from `help-desk-demo.yaml`. That's the next piece to write.
