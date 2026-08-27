---
layout: post
title: "Four Follow-Ons and Two Kinds of Grouping"
date: 2026-07-17
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [pages-table, grouped-view, web-components, api-design]
series: issue-189-grouped-view-enhancements
---

Last session closed #188 — PagesGroupedView composing per-group `<pages-table>` elements instead of reimplementing table rendering via innerHTML. Four follow-on issues fell out of that work: column renderers in list mode (#190), synchronized column visibility (#191), cross-group unified selection (#193), and native `groupBy` on pages-table (#189).

The design question that shaped everything was whether native `groupBy` should replace the multi-table composition we'd just built. I initially recommended it — a single ARIA grid with continuous keyboard navigation is objectively better for flat grouped data. But that was wrong-headed. We'd just finished building multi-table composition; proposing to replace it a day later would be bizarre. The right answer is coexistence: `<pages-table groupBy="status">` for flat spreadsheet-style grouping, `<pages-grouped-view>` for rich cases — collapsible sections, multi-level hierarchies, list mode, interstitial content via `renderAfterHeader`. Two tools, two purposes.

With that settled, the implementation order fell out naturally: #190 first (isolated, no API changes), then #191 and #193 (same coordination pattern, building on each other), then #189 last (largest scope, independent rendering path).

The most useful thing to come out of #191 is the external-control property pattern on PagesTable. `selectedKeys` already existed — when set, it overrides `_internalSelectedKeys` via a `willUpdate` sync. I added `hiddenColumns` with exactly the same shape: a public `@property()` that syncs to the private `_hiddenColumnIds` in `willUpdate`. PagesGroupedView sets both on child tables to coordinate state across groups. The pattern is now established for any future cross-table coordination: expose a public property, sync to internal state in `willUpdate`, let the parent manage the source of truth.

Moving `extractGroupBoundaries` and `extractGroupTree` from pages-viz to pages-data was the prerequisite for #189. They're pure data operations — no DOM, no rendering — but they'd been living in pages-viz because that's where PagesGroupedView needed them. Once pages-table also needed them, the right home was obvious. pages-viz re-exports for backward compatibility; pages-data owns the code.

The `groupBy` rendering itself is simpler than I expected. When set, `willUpdate` computes boundaries, the render method iterates by group boundary with a header div before each group's rows, and virtual scroll and pagination are disabled (same guard pattern as `getRowDetail`). No synthetic rows, no tree reuse — group headers are structural elements outside the data model.

One thing worth knowing for anyone testing PagesElement subclasses: vanilla Web Components render synchronously on property set. Setting a callback like `setColumnRenderers()` after `dataSet` means the first render ran without it. Lit components batch updates; PagesElement does not. Set callbacks before triggering render.
