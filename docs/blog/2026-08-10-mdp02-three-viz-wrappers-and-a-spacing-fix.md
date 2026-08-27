---
layout: post
title: "Three viz wrappers and a spacing fix that should've been caught years ago"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [visualization, echarts, heatmap, treemap, density, layout, css-grid]
---

Hortora/grove needs two specific visualisations for its curation analytics dashboard: a similarity heatmap (pairwise entry distances) and a domain composition treemap (rectangles sized by entry count, coloured by health). Both are ECharts chart types that ship in the library but had no `PagesChartElement` wrapper — so they couldn't be used from YAML.

The pattern is mechanical at this point. Subclass `PagesChartElement`, register the ECharts modules via `use()`, implement `buildOption()` with the four-stage pipeline (dataset-to-source, build base option, apply chart settings, deep-merge extra), register the custom element, wire up the props type across seven files. We've done this eleven times already for bar, line, pie, scatter, bubble, timeseries, meter, map, timeline, graph. Twelve and thirteen followed the same groove.

The heatmap was straightforward — three-column dataset `[x, y, value]` mapped to ECharts' `[xIndex, yIndex, value]` format with a `VisualMapComponent` colour scale. One decision: position the visualMap vertically on the right side with its bottom aligned to the x-axis line, not the default bottom-left overlap.

The treemap had a subtlety. Flat data (name, value) works as-is. Hierarchical data with a parent column needs tree construction — and here's where ECharts bit us: a parent node with explicit `value: 0` suppresses child auto-summing. The root "Company" node had value 0 from the dataset, so ECharts rendered a single blank rectangle instead of the nested hierarchy. Fix: delete `value` from branch nodes after tree construction so ECharts computes it from children.

The third component broke the ECharts pattern entirely. We needed spatial density rendering — gaussian kernel heatmaps showing intensity along paths, the kind of visualisation Strava uses for popular running routes. ECharts doesn't do this; its `HeatmapChart` is cell-based. After searching for maintained standalone libraries (there aren't many), we picked `@drdreo/heatmap`: zero dependencies, ~6kB gzipped, pure TypeScript, MIT. If the author walks away, 6kB of zero-dep code is trivially replaceable.

`PagesDensityHeatmap` subclasses `PagesElement` directly instead of `PagesChartElement` — no ECharts involved. The component normalises data coordinates to canvas pixel space (the library expects pixel positions), creates the heatmap instance via `createHeatmap()`, and handles resize by destroying and recreating. Two gotchas surfaced during implementation: LitElement custom elements default to `display: inline`, so `height: 450px` silently does nothing — needed `:host { display: block }`. And the library works fine inside shadow DOM, which wasn't guaranteed.

The spacing fix was the real surprise. Every example in the gallery had massive gaps between headers and charts. The root cause was `grid-auto-rows: minmax(min-content, 1fr)` combined with `height: 100%` on the page container. CSS grid distributes remaining space equally across `1fr` tracks — so a 25px title row inflates to 300px+ to match the chart row. Fix: `grid-auto-rows: min-content` with `align-content: start`. This packs rows at the top instead of stretching them to fill the viewport. A second fix — `grid.top: 10` as the ECharts default when no internal title is set — eliminated the 60px of dead space ECharts reserves for a title component that isn't there.

These are platform-level layout fixes that affect every dashboard, not just the new examples. They should've been caught when the first example was built, but nobody noticed because the content was always big enough to fill the viewport.

Next: #302 — a composite component that renders graph topology with density heat along the edges. That's the visualisation grove actually wants: network nodes with traffic intensity visible on the connections. The current density heatmap shows the heat pattern without the topology overlay; the composite component would combine both layers on a single canvas.
