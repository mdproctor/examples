---
layout: post
title: "The Interface That Was Already There"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [interfaces, data-pipeline, web-components, host-panel]
series: issue-109-export-configurable-panel
---

The `host-panel` activation code had a line I'd walked past dozens of times:

```typescript
const panelAny = panel as unknown as { configure?: (p: Record<string, unknown>) => void };
```

That's the entire hosting contract for every Web Component mounted via `host-panel`. No interface, no JSDoc, no export. If you wanted to build a hosted panel, you read `activation.ts` line 267 and reverse-engineered the protocol from a cast.

The other gap was data. Viz components — charts, tables, metrics — participate in the dataset pipeline automatically. They dispatch `pages-data-request` in `connectedCallback`, the pipeline resolves the lookup, and data arrives via `element.dataSet`. Host panels get none of this. They receive static props via `configure()` and then they're on their own. The blocks-ui team built a parallel fetch layer with SSE subscriptions because the pipeline was invisible to them.

Two interfaces fix both problems. `ConfigurablePanel<P>` formalizes the hosting contract — call timing, re-configuration semantics, props content. `DataReceiver` formalizes the minimal data delivery contract — `dataSet` and `error` with a mutual-clearing invariant. They're orthogonal: a panel can be configurable without being data-aware, or (less commonly) data-aware without configure.

The interesting design question was where to cut `VizTarget`. The pipeline's delivery target had six properties, but tracing every `set` call through `pushData()` revealed that `theme` was never set by the pipeline at all — `site.ts setTheme()` does its own cast with a `"buildOption" in vizEl` guard. Historical accident from modelling VizTarget after PagesElement's property surface rather than what the pipeline actually writes. Removing it was a free cleanup.

That left `totalRows`, `activePage`, `activeSort` as pagination/sort feedback that only tables consume. The decomposition: `DataReceiver` (dataSet + error) is the base, `VizTarget` extends it with the pagination/sort fields. Host panels implement `DataReceiver`; the pipeline keeps its `VizTarget` parameter unchanged.

The bridge uses a proxy adapter — the same pattern already proven for multi-dataset components (GE-20260621-f0563a). The activation creates a plain object that implements `VizTarget` by forwarding `dataSet`/`error` to the panel and no-opping the rest. Zero pipeline changes. The proxy sits in the `ComponentRegistry` like any other `vizElement`, so host panels automatically get refresh timers, push updates, and cross-filter re-delivery.

Claude's design review caught two things I'd missed. First, the `handleSubtreeRemoved` cleanup function in the data pipeline casts `entry.vizElement as unknown as HTMLElement` — fine when every vizElement is a `PagesElement`, but the proxy is a plain object. The fix: use `entry.element` (the wrapper div), which is always an HTMLElement regardless of whether the vizElement is a real component or a proxy. Second, adding `implements DataReceiver` to `PagesElement` fails under `strict: true` — setter contravariance rejects `TypedDataSet | undefined` as narrower than `unknown`. The class structurally satisfies the interface but nominally rejects it. Non-obvious enough that I submitted it to the garden.

The `ComponentEntry.vizElement` type widening from `PagesElement<VizComponentProps>` to `VizTarget` was the most satisfying change. Every callsite already used it as `VizTarget` — they accessed `.dataSet`, `.error`, or passed it to `pushData(target: VizTarget)`. The narrow type was the thing preventing host panels from participating in the pipeline. Fixing the type was fixing the design.

Epic #111 is now closed. The two remaining items — #109 (ConfigurablePanel interface) and #110 (dataset bridge) — land together because they share the same architectural insight: the hosting boundary and the data delivery boundary are two faces of the same contract surface.
