---
layout: post
title: "Closing the Spec Gaps in pages-table"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [pages-table, a11y, pagination]
---

The pages-table component migrated from blocks-ui months ago with most of the
original spec implemented — virtual scroll, pagination, sorting, selection,
keyboard navigation. Three items from blocks-ui#22 never made the crossing:
a jump-to-page input, a page size selector, and composition with
`RovingTabindexMixin` from pages-primitives.

I wanted to close #129 cleanly, so we went through the spec line by line
against the implementation. The first two gaps were straightforward UI — a
number input in the pagination footer that navigates on Enter (clamped to
valid range), and a `<select>` dropdown for page size with a `page-size-change`
event. The `pageSizeOptions` property defaults to `[10, 25, 50, 100]` but
accepts custom arrays from YAML. One detail worth noting: if the current
`pageSize` isn't in the options list, the component auto-includes it — without
this, the select would show options that don't match the actual state.

The mixin integration was more interesting. `RovingTabindexMixin` is designed
for flat lists — it queries a selector, manages `rovingIndex`, handles arrow
keys with wrapping. A table is a 2D grid with virtual scrolling, shift+arrow
selection, enter/space/escape handlers, and rows that change as you scroll.
The mixin can't handle any of that. The solution: extend from the mixin for
structural composition and tabindex management, but keep the table's own
`_handleKeyDown` on the grid div. Because events bubble from the grid div to
the host, and the table handler calls `stopPropagation()` for handled keys,
the mixin's keyboard handler never fires for anything the table already
handles. The mixin contributes `rovingIndex` (replacing the hand-rolled
`_focusRowIndex`) and the focusin initialisation. One gotcha: arrow keys in
the filter input would bubble to the host and trigger the mixin's row
navigation. A `_onToolbarKeydown` handler that stops propagation for arrow
keys on the toolbar solves it.

While fixing the toolbar, I noticed the filter input was overlapping the
rightmost column header — the toolbar sat inside the header-container flex
layout, making the header grid narrower than the data rows. We moved the
toolbar to its own bar above the column headers and dropped the
`padding-right: 36px` hack from data rows. The column picker also needed
proper dismiss behaviour: click-outside using `this.contains(e.target)` (not
`composedPath()` — that doesn't work in jsdom) and a 400ms mouseleave delay
so the dropdown doesn't vanish the instant the cursor overshoots.

The `composedPath()` issue was worth a garden entry. It's the canonical
recommendation for Shadow DOM event inspection, but jsdom doesn't implement
the path correctly across shadow boundaries. `this.contains()` is the
portable alternative.
