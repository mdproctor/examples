# DSL Composition Gaps — Design Spec

**Issue:** casehubio/casehub-pages#317
**Date:** 2026-08-17
**Branch:** issue-317-dsl-composition-gaps

## Overview

Epic #317 identified 5 gaps in the pages DSL. Context gathering revealed that 2 are already implemented (conditional row styling, grouped rows) and the remaining scope splits into 4 work items: missing builder functions (mechanical), KPI metric enhancements, an event timeline component, and a master-detail convenience builder.

## Epic triage

| Gap | Status | Action |
|-----|--------|--------|
| 1. Conditional row styling | Already implemented (`RowStyleRule`, `rowStyle` prop) | Close in epic |
| 2. Grouped rows | Already implemented (`groupBy` prop) | Close in epic |
| 3. Heatmap chart builder | Components exist, builders missing | D3 — add builders |
| 4. KPI metric row | `metricGrid()` exists, sparkline/trend missing | D1 — enhance |
| 5. Timeline | ECharts Gantt exists; event list is new | D4 — new component |
| 6. Master-detail | Selection machinery exists, wiring is verbose | D2 — convenience builder |

## Work item 1: Missing builder functions (D3)

### Problem

Seven component types have prop interfaces and activation registration but no DSL builder function in `builders.ts`. Of these, 4 (badge, countdown, timeline, graph) are also missing from `ComponentTypeRegistry` and have no type guard functions. App builders can't use them from the DSL without dropping to raw component objects.

### Solution

Add builder functions following the established freeze-and-return pattern:

```typescript
export function heatmapChart(props: HeatmapChartProps): TypedComponent<"heatmap-chart"> {
  return freeze({ type: "heatmap-chart" as const, props: { ...props } });
}

export function treemapChart(props: TreemapChartProps): TypedComponent<"treemap-chart"> {
  return freeze({ type: "treemap-chart" as const, props: { ...props } });
}

export function densityHeatmap(props: DensityHeatmapProps): TypedComponent<"density-heatmap"> {
  return freeze({ type: "density-heatmap" as const, props: { ...props } });
}

export function badge(props: BadgeProps): TypedComponent<"badge"> {
  return freeze({ type: "badge" as const, props: { ...props } });
}

export function countdown(props: CountdownProps): TypedComponent<"countdown"> {
  return freeze({ type: "countdown" as const, props: { ...props } });
}

export function timeline(props: TimelineProps): TypedComponent<"timeline"> {
  return freeze({ type: "timeline" as const, props: { ...props } });
}

export function graph(props: GraphProps): TypedComponent<"graph"> {
  return freeze({ type: "graph" as const, props: { ...props } });
}
```

### Registry and type guard completeness

4 components (badge, countdown, timeline, graph) need `ComponentTypeRegistry` entries and `isX()` type guards added to `type-guards.ts` alongside their builders. The heatmap family (heatmap-chart, treemap-chart, density-heatmap) already has these.

| Component | Registry | Type Guard | Action |
|-----------|----------|------------|--------|
| heatmap-chart | ✓ | ✓ | builder only |
| treemap-chart | ✓ | ✓ | builder only |
| density-heatmap | ✓ | ✓ | builder only |
| badge | ✗ | ✗ | builder + registry + guard |
| countdown | ✗ | ✗ | builder + registry + guard |
| timeline | ✗ | ✗ | builder + registry + guard |
| graph | ✗ | ✗ | builder + registry + guard |

### Files changed

- `packages/pages-ui/src/dsl/builders.ts` — add 7 builder functions, add imports for prop types
- `packages/pages-component/src/model/type-guards.ts` — add 4 registry entries and type guards (badge, countdown, timeline, graph)
- `packages/pages-ui/src/dsl/builders.test.ts` — add tests for each builder

### Testing

Each builder gets a test verifying:
- Returns frozen object with correct `type` discriminator
- Props are spread (not aliased) so the source object can't be mutated

## Work item 2: KPI metric enhancements (D1)

### Problem

`metricGrid()` layouts produce a responsive grid of metric cards but lack:
- A single-row horizontal strip mode for dashboard headers
- Sparkline rendering within individual metrics
- Trend indicator (up/down/flat arrow) within individual metrics

### Solution — two parts

#### Part A: metricGrid direction option

Add `direction` option to `metricGrid()`. Add a typed `MetricGridProps` interface to `displayer-types.ts` and a registry entry to `type-guards.ts`. The first argument is an options object if it contains a `direction` field (following the established `isPageOptions` pattern):

```typescript
// In displayer-types.ts
export interface MetricGridProps {
  readonly direction?: "row" | "grid";
}

// In builders.ts
export function metricGrid(
  ...args: [...Component[]] | [MetricGridProps, ...Component[]]
): Component {
  const first = args[0];
  const hasOptions = first != null && typeof first === 'object' && 'direction' in first;
  const options = hasOptions ? first as MetricGridProps : undefined;
  const children = hasOptions ? args.slice(1) as Component[] : args as Component[];
  return {
    type: "metric-grid",
    props: { direction: options?.direction },
    slots: { default: freeze(children) },
  };
}
```

Update `layout.ts` renderer:

```typescript
case "metric-grid": {
  element.style.display = direction === "row" ? "flex" : "grid";
  if (direction === "row") {
    element.style.gap = "var(--pages-space-2, 8px)";
    element.style.flexWrap = "nowrap";
    // children flex equally
    for (const child of element.children) {
      (child as HTMLElement).style.flex = "1 1 0";
    }
  } else {
    element.style.gridTemplateColumns = "repeat(auto-fill, minmax(140px, 1fr))";
    element.style.gap = "var(--pages-space-2, 8px)";
  }
  break;
}
```

#### Part B: PagesMetric sparkline and trend

Add to `MetricProps`:

```typescript
export interface MetricProps extends DataComponentCommon {
  readonly subtype?: "card" | "card2" | "plain-text" | "quota";
  readonly pattern?: string;
  readonly html?: { readonly template?: string; readonly javascript?: string };
  readonly sparklineData?: readonly number[];
  readonly trend?: "up" | "down" | "flat";
}
```

In `PagesMetric.renderContent()`, render sparkline below the value and trend arrow beside it:

- Sparkline: call `renderSparkline(props.sparklineData)` from `@casehubio/pages-ui-components` (dependency already exists in `pages-viz/package.json`)
- Trend arrow: CSS-only indicator (▲/▼/— with semantic colour from `--pages-success-9` / `--pages-danger-9` / `--pages-neutral-9`)

### DSL usage

```typescript
metricGrid(
  { direction: "row" },
  metric({ lookup: lookup("pnl"), title: "Total P&L", trend: "up", sparklineData: [1.2, 1.5, 1.3, 1.8, 2.1] }),
  metric({ lookup: lookup("winRate"), title: "Win Rate", trend: "flat" }),
  metric({ lookup: lookup("tradeCount"), title: "Trades" }),
)
```

### Files changed

- `packages/pages-component/src/model/displayer-types.ts` — add `MetricGridProps`, add `sparklineData`, `trend` to `MetricProps`
- `packages/pages-component/src/model/type-guards.ts` — add `metric-grid` registry entry
- `packages/pages-component/src/renderer/layout.ts` — handle `direction` in metric-grid case
- `packages/pages-viz/src/components/PagesMetric.ts` — render sparkline and trend
- `packages/pages-ui/src/dsl/builders.ts` — update `metricGrid()` signature
- Tests for each file

## Work item 3: Event timeline component (D4)

### Problem

The existing `PagesTimeline` is an ECharts Gantt chart (horizontal duration bars on a time axis). The epic describes a different concept: a vertical/horizontal event list with state markers, expandable detail, and category filtering. blocks-ui's `blocks-timeline` implements this but is domain-coupled.

### Solution

New `PagesEventTimeline` component in `pages-viz` with a `eventTimeline()` DSL builder.

#### Types

Pure data props go in `pages-component/src/model/displayer-types.ts` (matching the existing pattern for all component props). Strategy and node types contain render methods (`renderNode`, `renderDetail`) so they live in `pages-viz` alongside the component — matching how `PagesTimeline` keeps its `TimelineDataItem` local.

**In `pages-component/src/model/displayer-types.ts`** (pure props only):

```typescript
export type EventTimelineLayout = "vertical" | "horizontal" | "compact";

export interface EventTimelineProps extends DataComponentCommon {
  readonly layout?: EventTimelineLayout;
  readonly pageSize?: number;
  readonly strategyKey?: string;
}
```

**In `pages-viz/src/components/event-timeline-types.ts`** (rendering-adjacent types):

```typescript
export type EventNodeStatus = "completed" | "active" | "pending" | "failed" | "skipped";

export interface EventTimelineNode {
  readonly key: string;
  readonly label: string;
  readonly status: EventNodeStatus;
  readonly timestamp?: string;
  readonly actor?: string;
  readonly detail?: unknown;
  readonly category?: string;
}

export interface EventTimelineStrategy<T = unknown> {
  toNodes(data: T): EventTimelineNode[];
  transformData?: (raw: unknown) => T;
  defaultLayout: EventTimelineLayout;
  renderNode?: (node: EventTimelineNode) => unknown;
  renderDetail?: (node: EventTimelineNode) => unknown;
  filterCategories?: string[];
}
```

#### Component (`pages-viz/src/components/PagesEventTimeline.ts`)

- Extends `PagesElement<EventTimelineProps>`
- Accepts `strategy` property for pluggable data transformation (imperative, for programmatic use)
- Accepts `strategyKey` prop (declarative, for DSL use) — resolves via a static registry: `PagesEventTimeline.registerStrategy(key, strategy)`. Built-in default: `"chronological"` (sorts nodes by timestamp, vertical layout)
- Accepts `data` property for direct data binding (alternative to DataSet pipeline)
- Three renderers ported from blocks-timeline's generic rendering (vertical, horizontal, compact)
- Node expansion with `_expandedKeys: Set<string>`
- Category filter bar
- Keyboard navigation (arrow keys through nodes)
- Events via `emitPagesEvent(this, "event-timeline:node-selected", { node, index })`
- CSS using `--pages-*` tokens throughout
- Status colours: completed → `--pages-success-9`, active → `--pages-accent-9`, pending → `--pages-neutral-7`, failed → `--pages-danger-9`, skipped → `--pages-neutral-5`

#### DSL builder

```typescript
export function eventTimeline(props: EventTimelineProps): TypedComponent<"event-timeline"> {
  return freeze({ type: "event-timeline" as const, props: { ...props } });
}
```

#### Registration

- Add `EventTimelineProps` to `ComponentTypeRegistry` in `type-guards.ts`
- Add `isEventTimeline()` type guard
- Add `"event-timeline"` to `DATA_COMPONENT_TYPES` in `activation.ts`

### Files changed

- `packages/pages-component/src/model/displayer-types.ts` — add `EventTimelineProps`, `EventTimelineLayout`
- `packages/pages-component/src/model/type-guards.ts` — add registry entry + guard
- `packages/pages-runtime/src/activation.ts` — add to DATA_COMPONENT_TYPES
- `packages/pages-viz/src/components/event-timeline-types.ts` — new file: `EventTimelineNode`, `EventTimelineStrategy`, `EventNodeStatus`
- `packages/pages-viz/src/components/PagesEventTimeline.ts` — new component
- `packages/pages-viz/src/components/PagesEventTimeline.test.ts` — tests
- `packages/pages-viz/src/index.ts` — export
- `packages/pages-viz/src/custom-elements.ts` — declare
- `packages/pages-ui/src/dsl/builders.ts` — add `eventTimeline()` builder

### Extensibility for blocks-ui

`PagesEventTimeline` is designed so blocks-ui can extend it:

```typescript
// In blocks-ui (future — blocks-ui#123)
@customElement('blocks-timeline')
export class BlocksTimeline extends PagesEventTimeline {
  override configure(props: Record<string, unknown>): void {
    // Add tenancy headers from WorkIdentity
  }
}
```

blocks-ui's existing strategies (`eventChronologyStrategy`, `commitmentLifecycleStrategy`, etc.) are compatible with `EventTimelineStrategy` — same shape, same contract.

## Work item 4: Master-detail convenience builder (D2)

### Problem

Building a master-detail layout requires manually composing `split()` + `dataTable()` with `selection: "single"` + `hostPanel()` with `selectionSource`. The wiring is verbose and error-prone.

### Solution

**Prerequisite:** Add `selection?: SelectionMode` to `DataTableProps` in `displayer-types.ts` and wire the activation callback in `pages-runtime` to set it on the element. Currently `selection` is only an imperative property on `PagesDataTable` — making master-detail declarative requires making selection mode declarative first.

```typescript
export function masterDetail(config: {
  master: TypedComponent<"data-table">;
  detail: TypedComponent<"host-panel">;
  direction?: "horizontal" | "vertical";
  ratio?: [number, number];
}): TypedComponent<"split"> {
  const { master, detail, direction = "horizontal", ratio = [40, 60] } = config;

  const wiredMaster = freeze({
    ...master,
    props: { ...master.props, selection: "single" as const },
  });

  const masterLookup = master.props.lookup;
  const wiredDetail = freeze({
    ...detail,
    props: {
      ...detail.props,
      selectionSource: masterLookup.dataSetId,
    },
  });

  return split(direction, [wiredMaster, wiredDetail], { ratio });
}
```

Type signature enforces `TypedComponent<"data-table">` for master and `TypedComponent<"host-panel">` for detail — prevents silent failures with unsupported component types. `selectionSource` is a top-level property on `HostPanelProps` (not nested in `panelProps`).

### DSL usage

```typescript
masterDetail({
  master: dataTable({ lookup: lookup("strategies"), columns: [{ id: "name" }, { id: "pnl" }] }),
  detail: hostPanel("strategy-detail"),
  ratio: [35, 65],
})
```

### Files changed

- `packages/pages-component/src/model/displayer-types.ts` — add `selection` to `DataTableProps`
- `packages/pages-runtime/src/activation.ts` — wire `selection` prop to element in activation callback
- `packages/pages-ui/src/dsl/builders.ts` — add `masterDetail()` function
- `packages/pages-ui/src/dsl/builders.test.ts` — tests

## Out of scope

- Generalising selection forwarding to non-hostPanel components (separate issue if needed)
- blocks-ui refactoring to extend PagesEventTimeline (blocks-ui#123)
- blocks-ui re-export cleanup (blocks-ui#122)
- Builder factory/generator pattern — current hand-coded builders are more discoverable

## References

- `packages/pages-component/src/model/displayer-types.ts` — all prop type definitions
- `packages/pages-component/src/model/type-guards.ts` — component type registry
- `packages/pages-runtime/src/activation.ts` — DATA_COMPONENT_TYPES activation list
- `packages/pages-ui/src/dsl/builders.ts` — existing builder functions
- `packages/pages-component/src/renderer/layout.ts:60` — metric-grid CSS layout
- `packages/pages-viz/src/components/PagesMetric.ts` — metric component renderer
- `packages/pages-viz/src/charts/PagesTimeline.ts` — existing ECharts Gantt timeline
- `packages/pages-runtime/src/selection-forwarding.ts` — selection event forwarding
- `packages/pages-ui-components/src/sparkline/render-sparkline.ts` — sparkline renderer
- `blocks-ui/components/blocks-timeline/src/types.ts` — TimelineNode, TimelineStrategy
- `blocks-ui/components/blocks-timeline/src/blocks-timeline.ts` — generic rendering reference
- blocks-ui#122 — re-export cleanup issue
- blocks-ui#123 — blocks-timeline refactor issue
- casehubio/casehub-pages#299 — selection dataset builders
- casehubio/casehub-pages#300 — selection forwarding to host-panel
