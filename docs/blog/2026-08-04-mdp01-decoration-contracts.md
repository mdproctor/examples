---
layout: post
title: "Decoration contracts — the layer between structure and state"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [graph-core, graph-renderer, decoration, types]
---

The graph packages had a gap between what the model could describe and what the renderer could show. `GraphModel` handles nodes, edges, containment — the structural skeleton. But runtime state (which binding is executing, which task faulted, where the bottleneck sits) had no representation. Each domain stencil would have to invent its own badge rendering, its own border-override logic, its own overlay math.

The parent spec had already designed the answer: `NodeDecoration` as a generic model in graph-core, consumed by graph-renderer's stencil wrapper. Badge with icon, colour, optional pulse animation and count. Border override. Semi-transparent overlay for heatmaps or highlights. Tooltip. Domain stencils never import state enums — a `RuntimeAdapter` in the domain layer maps `TaskStatus.RUNNING` to `{ badge: { icon: 'play', color: 'green', pulse: true } }`, and the renderer handles the rest.

I started by validating the existing epics before writing new code. Phase 1A (#264) had all five child issues closed, 101 tests passing, but the epic was still open — closed it after confirming every module. Phase 1B (#265) was already closed, but its test suite had 4 of 6 suites failing silently. The culprit: `@xyflow/react` was declared in `package.json` but never installed. Two suites that don't import it passed normally, creating the misleading impression of a code bug rather than a missing dependency. A `yarn install` fixed it, but the partial pass/fail pattern is the kind of thing that wastes real debugging time — the error points at the wrong cause.

The second surprise was subtler. `deregisterGrammar` existed in graph-core's source and was exported from the barrel, but the built `dist/` was stale. TypeScript type-checked fine (the `.d.ts` happened to be current), but at runtime Vitest resolved to `dist/index.js` where the function didn't exist. The error — `(0, deregisterGrammar) is not a function` — gives no hint that a stale build is the problem. Both of these went into the garden as gotchas.

With the validation done, the implementation was straightforward. `NodeDecoration` and `PropertySchema` (a type alias for `JSONSchema7`) went into graph-core's `model.ts`. Then the rendering pipeline: `toReactFlowGraph()` accepts an optional decoration map and merges each node's decoration into `RFNode.data` under a `_decoration` key. The stencil wrapper extracts it, strips it from the properties passed to the render function, and passes it as a second argument. Existing stencils that don't accept a second parameter continue to work — backward compatibility was the hard constraint here.

The decoration wrapper itself renders around the stencil output: a positioned badge with optional pulse keyframes, a CSS border override, a semi-transparent overlay (heatmap orange or highlight blue, intensity-scaled), and a title attribute for tooltips. The pulse animation injects its `@keyframes` lazily into the document head on first use.

I also fixed the `build:packages` script — graph-core and graph-renderer weren't in the build chain at all. CI would have failed on any workspace that tried to build from clean. graph-core goes after `pages-ui-tokens` (no runtime workspace deps), graph-renderer after `pages-data` (depends on graph-core, pages-data, and pages-ui-tokens).

The open question is how blocks-ui will consume this. The `StencilDescriptor` interface in graph-renderer now carries the updated render signature, so any stencil registered through `registerStencil()` can opt into decoration rendering by accepting the second argument. The `RuntimeAdapter` pattern — mapping domain state to `NodeDecoration` — lives in the domain layer, not here. That's the next piece blocks-ui needs to build before execution state overlays work end-to-end.
