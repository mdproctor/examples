---
layout: post
title: "The Stale Position Bug and the Purity Question"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [floating-workspace, architecture, state-management]
series: issue-303-floating-engine-autowire
---

The FloatingFrameEngine landed as a clean separation: engine holds state, backend renders Dockview, activation wires events between them. Three layers, clear boundaries. One problem: every consumer duplicates the same 24 lines of callback→event wiring. And a subtler problem — the engine never learns where frames actually end up after a drag.

`captureLayout()` returns creation-time positions because backend move/resize callbacks dispatch DOM events that site.ts catches, but nobody tells the engine. The frames map is a lie. Drag a frame, reload, and it snaps back to where it was born.

The obvious fix: make the engine auto-wire everything. Pass it the container element, let it subscribe to backend callbacks, update its own state, dispatch its own events. One function call replaces 24 lines of boilerplate. We started there.

Claude's decision review caught the problem. The engine is currently pure — no DOM types, no HTMLElement, no CustomEvent dispatch. Tests run with a trivial mock backend, no jsdom. Auto-wiring would convert it from a state machine into a DOM-coupled event hub. The reviewer's exact framing: "conflating interface dependency with concrete dependency." The engine depends on `FloatingFrameBackend` (a TypeScript interface), not on the DOM. That's architecturally valuable.

The fix was a `wireFloatingWorkspace()` function — a mediator that owns the wiring concern without contaminating the engine. It subscribes to all backend callbacks, updates engine state (two new state-only methods: `updatePosition` and `updateSize`), and dispatches CustomEvents on the container. The engine stays testable. The boilerplate still collapses. Both properties survive.

The backend interface gained two new callbacks (`onFrameClose`, `onFramePin`) alongside the existing move/resize/drag-out/reorder set. Chrome buttons now fire typed callbacks instead of dispatching DOM events directly — the wire function is the single source of all floating workspace CustomEvents. And `attach()` gained an `extraButtons` config so consumers can add host-specific titlebar buttons (detach-to-window, hide-to-tray) without subclassing or using the escape hatch.

Pin state was also wrong: the pushpin emoji had a static `opacity:0.5` regardless of whether the frame was pinned. Now `updatePinState` sets opacity, toggles `aria-pressed`, and adds a `.frame-pin-active` CSS class — three signals covering visual, accessible, and consumer-customisable indication. Pinned frames also get drag-locked via Dockview's `group.locked` API with a `pointerdown stopPropagation` fallback.

The tab-bar drag bug — empty space right of tab labels initiating a frame drag — turned out to be a CSS fix. `pointer-events: none` on the floating group's tab container, `pointer-events: auto` on the actual tab elements. Two lines of injected CSS.

The per-frame tiling dropdown from the original issue scope got deferred. The decision review correctly identified it as OS-level window management complexity being underestimated in a "lightweight dropdown" proposal. A follow-up issue can properly design the interaction model.
