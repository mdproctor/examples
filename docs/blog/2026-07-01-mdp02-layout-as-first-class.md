---
layout: post
title: "Layout as a First-Class Concept"
date: 2026-07-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [layout, serialization, workbench, persistence]
series: issue-76-workbench-layout-serial
---

casehub-pages has had resizable splits since the workbench primitives landed. You could drag a split handle and resize panels. But the resize was a DOM-only mutation — `flex: 0 0 200px` applied directly to the element, no event fired, no state captured. Close the tab, the layout is gone.

That's the wrong architecture. If the runtime doesn't know a state change happened, it can't serialize it, can't restore it, can't persist it. The fix isn't "read the DOM on save" — it's making the state change observable in the first place.

The split resize handler now fires `pages-split-resize` on mouseup with proportional ratios (not pixels — so restoration works on any screen size). The event flows through the same delegation pattern as `pages-filter` and `pages-dock-toggle`. The runtime catches it, updates an internal map, and optionally auto-saves.

The serialization surface is `LayoutState` — split ratios, dock visibility, and a manifest of which hosted panels exist. It's a delta, not a snapshot: if you haven't resized anything, `splits` is empty. The getter captures deviations from component-tree defaults, keeping saved layouts compact.

Persistence is opt-in and pluggable. `LayoutStore` is a three-method contract — `load`, `save`, `delete` — that any backend can implement. A `localStorage` adapter ships built-in. The optional Quarkus backend will provide a REST adapter later. When you configure a store and a key, the runtime auto-loads on init and debounce-saves on every layout change. When you don't, `site.layout` still works for manual export.

The design review caught several things I'd missed. The most interesting: splits inside lazy containers (tabs, accordion) get destroyed and re-created on every tab switch. The restore can't use a snapshot of the original saved state — it has to use the live internal map, because the user might have resized after restoration. A subtler one: when a dock panel is hidden, it measures as 0 pixels during resize. The runtime preserves the last known non-zero ratio so re-opening the panel gives it space rather than collapsing to nothing.

This and the terminal component mean consuming apps now have the three things they need for a workbench: resizable layouts that remember their arrangement, dockable panels that persist their visibility, and hosted Web Components with a stable configure/connect lifecycle. DraftHouse's hand-coded panel shell can now swap to pages primitives without losing any capability.
