---
title: "Three Primitives, One Pipeline"
date: 2026-06-30
author: mdp
projects: [casehub-pages]
tags: [architecture, web-components, workbench, event-bus]
---

casehub-pages started as a dashboard renderer — YAML in, charts out. DraftHouse, Claudony, and DevTown each needed more: a workspace shell with panels, docking, and inter-panel communication. Each hand-coded its own shell. DraftHouse's `panel-registry.js` had a comment at the top: "First draft of what `@casehub/ui`'s component type registry will be." Time to make that real.

## The dock insight

The initial design had two layout primitives: a dock frame (fixed positions with collapse/expand) and a split (resizable proportions). IntelliJ's model clarified the relationship. A dock isn't a layout primitive — it's a strip of buttons that toggles content in a split region. The dock bar IS the collapsed state. One concept, not two.

This led to three composable primitives instead of a monolithic workbench component:

- **`split`** — resizable flex layout with drag handles. Decoration on the existing grid/flex infrastructure, not a new layout engine.
- **`dockBar`** — icon strip that toggles panel visibility by component ID. Separate from the split because single responsibility matters more than API conciseness.
- **`hostPanel`** — mounts registered external Web Components. `configure(props)` before `appendChild()`, duck-typed protocol, fail-soft with visible error for unregistered types.

All three are recursive — any nests inside any other, and inside existing layout types. The component tree is already recursive; these are new types in the same model.

## The pipeline unification

The event bus was the hardest question. DraftHouse uses three communication patterns: DOM events, a shared SSE pub-sub bus, and direct method calls. Pages already has a data pipeline — WebSocket sources push dataset mutations (`append`, `replace`, `remove`) to bound components.

The obvious design: build a separate event bus alongside the data pipeline. The right design: extend the existing pipeline with one new operation.

```json
{ "op": "append", "dataset": "metrics", "rows": [...] }
{ "op": "event", "topic": "selection-changed", "payload": { "line": 42 } }
```

Same connection. Same lifecycle. The `op` field routes to the right consumer — dataset ops go through the data pipeline, event ops dispatch as `pages-event` DOM custom events. No separate pub-sub infrastructure.

I checked whether any other framework does this. Grafana has both an EventBus (cross-panel UI events) and Grafana Live (real-time data push) — two separate systems over the same WebSocket. Retool and Streamlit don't have native event buses at all. The unified model is architecturally novel, and it's simpler than the alternative.

## CSS Grid doesn't collapse

The split uses flex layout, not CSS Grid. This wasn't the original plan — `columns` uses CSS Grid, and split is conceptually "columns with drag handles." But CSS Grid `fr` tracks don't collapse when children are `display: none`. The track stays allocated. No error, no warning — just dead space where the hidden panel was.

Flex redistributes automatically. When a dock toggle hides a panel, siblings absorb the space. This is why split deliberately diverges from columns' CSS model. The divergence is load-bearing.

## What this enables

DraftHouse's shell migration is now a dependency swap, not a rewrite. The panels (diff viewer, debate feed, review tracker, context gauge) don't change. The hand-coded flexbox layout, the draggable dividers, the panel toggle buttons — all replaced by `split()`, `dockBar()`, and `hostPanel()` from the pages DSL. Same panels, different shell infrastructure.

The same primitives work for Claudony, DevTown, and any future CaseHub application. The workbench is composable — each app assembles its own layout from the same building blocks.