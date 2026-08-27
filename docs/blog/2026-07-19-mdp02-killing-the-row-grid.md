---
layout: post
title: "Killing the Row Grid"
date: 2026-07-19
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [pages-table, css-grid, cell-spanning, rendering-model]
series: issue-210-cell-spanning
---

The per-row CSS Grid model in `pages-table` was always a compromise. Each `.row` div was its own `display: grid`, which meant column spanning worked — a cell could stretch across columns within a single row's grid. But row spanning was architecturally impossible. A cell can't cross the boundary between two independent grid containers, no matter how clever the CSS gets.

I looked at how the big players handle this. AG Grid's legacy approach uses `suppressRowTransform` — switching from `translateY` to absolute `top` positioning, then overlaying spanned cells with z-index hacks. Their newer `enableCellSpan` API is cleaner but still restricts colSpan and rowSpan to separate columns. Ant Design takes a different route: `extraRender` with a boundary scan that lazily evaluates cells, creating GC pressure on large datasets. All of them are working around the same fundamental problem — the rendering model wasn't built for spanning.

The answer was obvious once I stopped looking at workarounds: make `.body-content` itself the grid. One `display: grid` with `grid-template-columns` for the column layout and `grid-template-rows: repeat(N, 48px)` for the scrollable height. All cells become direct grid items. A merged cell is just `grid-row: 2 / span 3; grid-column: 2 / span 2`. No position hacks, no z-index layering, no manual height math.

The row wrappers stay in the DOM — they're needed for ARIA (`role="row"`, `tabindex`, `aria-selected`) — but they get `display: contents`, which makes them invisible to layout while keeping them in the accessibility tree. Every cell gets explicit `grid-row` and `grid-column` inline styles. The browser handles scroll positioning natively through the grid tracks.

The cost is that all visual styling that lived on `.row` — hover, selection, striping, focus ring, row-style classes — moves to individual cells. `.row:hover` has no box with `display: contents`, so hover becomes a JS-driven `_hoverRowIndex` state with per-cell classes. Same for selection, striping, detail-expanded. It's more rendering work per cell, but for a viewport of 150 cells, 150 string operations per render cycle is noise.

The SpanMap is a `Map<rowIndex, Map<colId, CellSpan | SuppressedCell>>` — pre-computed in `willUpdate` from per-column `cellSpan` callbacks and `mergeRows` shorthand. Each suppressed cell stores its origin coordinates for O(1) lookup. No backward walk regardless of span size. The virtual scroll engine extends the render window by scanning viewport boundaries — if a cell at `startIndex` is suppressed, it reads the origin row directly and extends the window to include it.

For interactions, a spanned cell's hover highlights all rows in the span range. ArrowDown from a cell with `rowSpan: 3` at row 5 jumps to row 8. A spanned origin cell shows the `selected` class only when every row it covers is selected — partial coverage leaves it unselected, which is visually correct.

The one thing that caught us was the IntelliJ MCP tooling hanging on large method replacements. `ide_replace_text_in_file` runs indefinitely when the replacement text exceeds about 2KB — no error, no timeout, just silence. We fell back to Python scripts at `/tmp/` for the bigger refactors, using method signatures to compute replacement boundaries.

Seven commits, six issues closed (#211–#216). Playwright visual tests (#217) are deferred — they need the full webapp build pipeline and a browser environment, which is a separate session. The implementation itself is done: 244 tests pass, typecheck is clean, and the rendering model is ready for any table that needs cells to span across rows and columns.
