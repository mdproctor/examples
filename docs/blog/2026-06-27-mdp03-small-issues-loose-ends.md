---
layout: post
title: "Small Issues, Loose Ends"
date: 2026-06-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [examples, testing, maps]
---

The last session closed two branches and left a clean backlog. This one picked
up the two S-scale issues — #44 (domain dashboard polish) and #35 (map chart
geo data) — and ran them on a single branch.

## The metric that counted everything

The IoT Fleet Monitor's "Devices Online" metric was querying the `devices` dataset
with `NOT_IN []` — an empty exclusion list. Every device matched. The result was
6/6 even though DEV-006 has been "Offline" in the sensor readings all along.

The `devices` dataset had no status column. The fix was to add one — each device
now carries its latest status — and filter `NOT_IN ["Offline"]` instead. The
metric reads 5. DEV-003 is in "Warning" state but still operational, so it counts
as online.

The interesting thing: the original filter was probably intended as a placeholder
that never got replaced. `NOT_IN []` is a no-op — it excludes nothing. Someone
wrote the structure first and planned to fill in the exclusion values later.

## Raw HTML in YAML dashboards

The People dashboard's YAML wrapped every chart in raw HTML `<div>` tags with
PatternFly CSS classes — `pf-v5-c-card pf-m-compact`. The TS companion uses
`panel()` for the same visual grouping. The YAML parser has a `panel:` shorthand,
but it's a page reference, not a visual container. Different concept, same name.

I removed the raw HTML and moved the titles into each displayer's `general.title`.
The charts already support native titles — the HTML was adding fragile CSS coupling
for a decorative wrapper.

## Testing without sleeping

Four test files had `waitForTimeout` calls — nineteen in total, ranging from
200ms to 2000ms. The `openDashboard()` helper in three files waited 2 seconds
after the dashboard container became visible, hoping the async data resolution
would finish by then.

The replacement pattern checks the actual condition: `waitForFunction` polling
the web component's `dataSet` property through the shadow DOM. Once at least one
`casehub-*` element has data, the dashboard has rendered. For navigation clicks,
the wait targets the specific component type the test is about to assert on. For
the view-state tests, URL hash changes and sort indicators replace the shorter
fixed delays.

The `dataSet` property is the reliable signal. It's set by the data pipeline when
resolution completes — before ECharts renders the canvas. Checking for canvas
would also work but adds a dependency on the rendering step that isn't what the
tests care about.

## Maps: the missing registerMap

ECharts 5 removed bundled map data from the npm package. The chart types,
components, and geo coordinate system all survived the modular split — but the
actual GeoJSON that makes `map: "world"` render continents was quietly dropped.
`CasehubMap.ts` built perfectly valid ECharts options with `map: "world"` in them.
ECharts accepted the options without error. The chart just rendered empty.

The fix lazy-loads world GeoJSON from jsDelivr (pointing at the ECharts 4 package,
which still hosts the data) and calls `registerMap()` before the first render. A
module-level cache prevents duplicate fetches. If the fetch fails, the component
shows an error instead of an empty chart.

The ECharts 4 GeoJSON works with ECharts 5's `registerMap` — it's standard
GeoJSON, not version-specific. The format hasn't changed; it just moved out of
the package.
