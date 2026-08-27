# Decisions — #317 DSL Composition Gaps

## D1: KPI metrics — enhance metricGrid + PagesMetric, no new builder

**Choice:** Enhance the existing `metricGrid()` builder with a `direction: "row" | "grid"` option for single-row strip mode. Add sparkline and trend rendering to `PagesMetric` via new `MetricProps` fields (`sparklineData`, `trend`).
**Alternatives:**
- New `metricRow()` layout builder wrapping `columns()` — rejected because `metricGrid()` already exists as a metric layout builder. Adding another creates near-duplication.
- New `PagesMetricRow` web component — rejected because the gap is in the individual metric rendering (no sparkline/trend), not in layout.
**Rationale:** `metricGrid()` already creates a responsive grid layout (`repeat(auto-fill, minmax(140px, 1fr))`) for metrics. The epic's gap is the lack of compact, visually cohesive metric strips with trend indicators — which requires enhancing PagesMetric's rendering, not adding layout builders. `direction: "row"` gives the horizontal strip mode that `metricGrid()` currently lacks.
**Trade-offs:** Sparkline rendering requires `PagesMetric` (in `pages-viz`) to depend on `renderSparkline` from `pages-ui-components`. Need to verify this dependency edge is acceptable.
**Sources:** `packages/pages-ui/src/dsl/builders.ts:191` (metricGrid), `packages/pages-component/src/renderer/layout.ts:60` (metric-grid CSS), `packages/pages-viz/src/components/PagesMetric.ts`, `packages/pages-ui-components/src/sparkline/render-sparkline.ts`
**Exploration:** quick → revised after decision review (R1-02, R1-16)
**Status:** revised

## D2: Master-detail — convenience builder with hostPanel constraint

**Choice:** `masterDetail()` is a convenience builder that composes `split()` + master component + detail `hostPanel()`, wiring `selection: "single"` on the master and `selectionSource` on the detail automatically. The detail parameter type enforces `TypedComponent<"host-panel">` — not arbitrary components.
**Alternatives:**
- New `PagesDetailLayout` web component — rejected because selection forwarding infrastructure already exists
- Unconstrained detail pane (any component type) — rejected because only host panels receive `pages-selection-changed` today; accepting arbitrary detail panes would silently fail
**Rationale:** The selection machinery exists (#299, #300). The gap is verbose wiring. The builder encapsulates the pattern while enforcing the hostPanel constraint in the type signature so it can't silently fail.
**Trade-offs:** Detail pane must be `hostPanel()`. Generalising to other component types requires extending `selection-forwarding.ts` first — a separate issue.
**Depends on:** D1 indirectly (if a metric is used as a detail display, it must be in a hostPanel)
**Sources:** `packages/pages-runtime/src/selection-forwarding.ts:16` (host-panel filter), `packages/pages-table/src/pages-data-table.ts:1457` (selection-change event), `packages/pages-ui/src/dsl/builders.ts:466` (split builder)
**Exploration:** quick → revised after decision review (R1-05)
**Status:** revised

## D3: Missing builders — sweep all 7 gaps, not just heatmaps

**Choice:** Add builder functions for all 7 component types that have prop types, type guards, and activation but no DSL builder: `heatmapChart()`, `treemapChart()`, `densityHeatmap()`, `badge()`, `countdown()`, `timeline()`, `graph()`.
**Alternatives:**
- Builder factory function `createBuilder<T>(type)` to generate builders programmatically — elegant but obscures the API surface in IDE autocomplete and docs. Hand-coded builders are discoverable and individually documentable.
- Add only heatmap builders (original D3 scope) — rejected because the same gap pattern applies to 4 more components
**Rationale:** All 7 follow the identical freeze-and-return pattern. Sweeping them in one pass closes the entire gap class. Hand-coding is preferred over a factory because the builder functions serve as API surface — they appear in autocomplete, have distinct JSDoc, and are individually importable.
**Trade-offs:** 7 trivial functions added. No architectural cost.
**Sources:** `packages/pages-component/src/model/displayer-types.ts` (BadgeProps:155, CountdownProps:160, TimelineProps:167, GraphProps:195, HeatmapChartProps:174, TreemapChartProps:179, DensityHeatmapProps:184), `packages/pages-runtime/src/activation.ts:53-78`
**Exploration:** quick → revised after decision review (R1-08, R1-09)
**Status:** revised

## D4: Event timeline — new PagesEventTimeline component (distinct from existing PagesTimeline)

**Choice:** New `PagesEventTimeline` component in `pages-viz`, registered as `pages-event-timeline`, extending `PagesElement`. Implements the strategy-pattern event list with vertical/horizontal/compact renderers, node expansion, filtering, and push data source support. DSL builder: `eventTimeline()`. Distinct from the existing `PagesTimeline` (ECharts Gantt chart).
**Alternatives:**
- Enhance existing `PagesTimeline` (ECharts Gantt) to add vertical event list mode — rejected because the two concepts are fundamentally different: PagesTimeline is a data visualization (time axis, duration bars, milestones); PagesEventTimeline is a UI component (discrete event nodes, status markers, expandable details). Merging them would create an incoherent component.
- Name it `PagesTimeline` (collision) — rejected because `pages-timeline` custom element already exists and is registered
**Rationale:** blocks-timeline proved the strategy-pattern design works. The generic rendering (vertical, horizontal, compact layouts, node expansion, filtering, keyboard nav) is domain-free. The `EventTimelineStrategy` interface enables domain-specific data transformation without coupling the component to any domain. blocks-ui#123 filed for future refactor of blocks-timeline to extend this base.
**Trade-offs:** Two timeline-like concepts in pages-viz (`PagesTimeline` for Gantt, `PagesEventTimeline` for event lists). The naming distinction (`timeline` vs `eventTimeline` in the DSL) should be clear enough, but may need documentation.
**Depends on:** D3 (the existing `timeline()` builder covers PagesTimeline; `eventTimeline()` is the new builder)
**Sources:** `packages/pages-viz/src/charts/PagesTimeline.ts` (existing ECharts Gantt), `blocks-ui/components/blocks-timeline/src/types.ts` (TimelineNode, TimelineStrategy), blocks-ui#123
**Exploration:** quick → revised after decision review (R1-11, R1-12)
**Status:** revised
