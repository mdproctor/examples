---
layout: post
title: "Closing the DSL surface gap"
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [dsl, builders, timeline, metrics, master-detail]
series: issue-317-dsl-composition-gaps
---

The pages TypeScript DSL had a curious inconsistency: components existed, rendered correctly, appeared in the activation pipeline — but you couldn't construct them from the builder API. Seven component types (heatmap, treemap, density heatmap, badge, countdown, timeline, graph) had prop interfaces, type guards (some of them), and activation entries, but no `heatmapChart()` or `badge()` function in `builders.ts`. Anyone wanting a heatmap from the DSL had to hand-construct the frozen component object. That's a gap that silently pushes complexity to the app tier.

The fix was mechanical — seven functions following the identical freeze-and-return pattern — but the design review caught that four of those seven also lacked `ComponentTypeRegistry` entries and `isX()` type guards. The gap was wider than it looked. We swept all seven at once and closed the entire class.

The more interesting work was the KPI metric strip and event timeline.

**Metrics got sparklines and trend arrows.** `metricGrid()` already existed as a responsive grid layout, but dashboard headers need a single-row strip — metrics shoulder to shoulder, no wrap. We added a `direction: "row"` option that switches the layout from CSS Grid to flex. Individual metrics gained `sparklineData` (renders an inline SVG sparkline) and `trend` (up/down/flat arrow with semantic colour). The sparkline dependency was already in place — `pages-viz` depends on `pages-ui-components` where `renderSparkline` lives.

**The event timeline needed a name.** `PagesTimeline` already exists — it's an ECharts Gantt chart rendering duration bars on a time axis. What the epic described was different: a vertical event list with status-coloured nodes, expandable detail, and category filtering. We called it `PagesEventTimeline` to avoid the collision and registered it as `pages-event-timeline`. The design comes from blocks-ui's `blocks-timeline`, which proved the strategy-pattern approach: a generic component accepts an `EventTimelineStrategy` that transforms arbitrary data into renderable nodes. Domain-specific strategies (CaseHub event chronology, commitment lifecycle) stay in blocks-ui. We filed blocks-ui#123 for the future refactor where `BlocksTimeline extends PagesEventTimeline`.

A side discovery during context gathering: blocks-ui-core re-exports about 15 symbols from pages packages (`DataSourceMixin`, `emitPagesEvent`, `fetchSource`, etc.) as a convenience barrel from before those utilities migrated to pages. There's no code duplication — just import-path indirection that obscures where things live. blocks-ui#122 filed for the cleanup.

**`masterDetail()` is a composition builder, not a component.** The selection machinery already exists — `PagesDataTable` dispatches `selection-change`, the runtime forwards it to host panels via `pages-selection-changed`, and `detailDataset()` parameterises URLs from selection values. The gap was verbose wiring: you had to manually compose `split()` + `dataTable()` + `hostPanel()` and set `selection: "single"` imperatively. `masterDetail()` encapsulates the pattern and enforces the `hostPanel` constraint in the type signature — the detail pane can't silently be something that doesn't receive selection events.

Making this work declaratively required adding `selection` to `DataTableProps`. Previously it was only an imperative property on the `PagesDataTable` element — fine for programmatic use, invisible to the DSL. The activation callback now wires it.

Two gaps from the original epic — conditional row styling and grouped rows — were already implemented. `RowStyleRule` with expression-based condition evaluation and `groupBy` with collapsible group headers both exist with tests. The epic can close those items without new work.
