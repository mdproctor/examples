---
layout: post
title: "The Event Protocol That Was Always Wrong"
date: 2026-06-24
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [cross-filter, events, typescript, echarts]
---

The `casehub-filter` event has been working since day one of the TypeScript migration. Selectors emit it, the runtime listens, filtered data flows to listening components. The flow works. The contract underneath it was a mess.

Four different emitters — chart, table, selector, iframe plugin — each populated the event differently. Tables sent the actual row object. Charts sent a positional index. Selectors sent a different positional index. The runtime compensated with fallback logic: `const row = eventRow ?? ds.rows[rowIndex]`. That fallback breaks the moment any client-side filtering or sorting reorders the display rows, because `rowIndex` is a display-order position, not a stable record identity. I'd already hit this in a previous session — it became garden entry GE-20260621-fe3944, the "table filter selects wrong record after text filtering" gotcha.

The issue was #20: "chart click → cross-filter event protocol." The description says to extend the existing `casehub-filter` event so charts can emit it. Charts already emitted it — the real problem was that nobody emitted it well.

## What the event contract should have been from the start

A discriminated union. Apply events carry the column, the value, and the row. Reset events carry only the column. The emitter resolves everything at dispatch time — it has the data, it knows what was clicked, it can look up the row and extract the value right there. The runtime should never guess.

```typescript
type CasehubFilterDetail = CasehubFilterApply | CasehubFilterReset;

interface CasehubFilterApply {
  readonly columnId: string;
  readonly value: string;
  readonly row: TypedRow;
  readonly reset: false;
  readonly group: string | undefined;
}
```

`rowIndex` is gone entirely. Not optional, not deprecated — removed. The garden gotcha was the argument: if a fragile positional index exists alongside the authoritative row reference, someone will use the wrong one.

## The design review caught things I wouldn't have found in implementation

The spec went through four review rounds. The first caught that ECharts' `selectedMode: 'single'` fights with manual `dispatchAction` — they both try to manage selection state, and for multi-series charts `selectedMode` highlights one bar segment while the filter applies to the entire category. We switched to `highlight`/`downplay`, which have no built-in click behaviour and support highlighting all series at a dataIndex simultaneously.

The second round caught that my formula for deriving the series count — "column count minus one" — is wrong for scatter and bubble charts, which map multiple columns to a single series. The fix: read the series count from the live chart option after `setOption()`. Always correct regardless of chart type.

The third round found a genuine design hole: the record selection reset path was undefined. Apply events carry a `row`, so the runtime can try `row.cell(idColumn)` to decide whether this is record selection or cross-filtering. Reset events carry no row. I'd specified the apply path but left the reset path as "clear filter at the appropriate path" without saying how the runtime determines which path. The fix: use the dataset's column schema (`ds.columns.some(c => c.id === childScope.idColumn)`) — same first principle as the apply path, but at the schema level.

The final round caught that tables clear their selection state unconditionally on data re-push, while charts preserve it when the selected value still exists in the new data. The asymmetry breaks selfApply tables — the filter stays active in FilterState but the visual feedback disappears. And it caught that the IframePlugin checked for a valid row index before checking the reset flag, silently swallowing reset messages with invalid row indices.

## Record selection is no longer table-only

The old runtime had `const isTableClick = entry.component.type === "table"` as a hard gate. Only tables could trigger DataScope record selection. A bar chart showing regions with a child detail panel couldn't select a record — the click was always cross-filtering.

The guard is gone. The runtime now infers the path from the data shape: if the emitter's row contains the DataScope's `idColumn`, it's record selection. If the column is missing (`TypedRow.cell()` throws `DataSetError("UNKNOWN_COLUMN")`), it falls through to cross-filtering. No configuration needed. A chart whose dataset happens to include the id column gets record selection automatically.

## Toggle was the missing UX primitive

Click a bar to filter. Click it again to... nothing. The filter stays. The only way to clear it was to use a separate selector with an "All" option, or reload the page.

Every emitter now tracks `_selectedValue: string | undefined`. Click the same value → emit reset, clear the highlight. Click a different value → emit the new apply. The tracking is value-based, not index-based, so it survives data re-pushes where row objects and indices change. Charts use ECharts `highlight`/`downplay` for visual feedback. Tables add a `.selected` CSS class and call `rerender()` after updating selection state — necessary because tables are skipped during their own event re-push unless `selfApply` is set.

The implementation touched 16 files across `pages-viz` and `pages-runtime`, replacing 200 lines with 2253. Most of the growth is test coverage — toggle state machines, data re-push preservation, column-switch semantics, NULL cell guards. The runtime filter listener actually got shorter.