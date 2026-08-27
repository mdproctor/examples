---
layout: post
title: "Three Leftovers from View State"
date: 2026-06-25
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-pipeline, text-filter, test-isolation, cleanup]
---

# casehub-pages — Three Leftovers from View State

**Date:** 2026-06-25
**Type:** phase-update

---

## What I was trying to achieve: close the trailing work from #24

The view-state-persistence branch (#24) landed a few hours ago. It centralized sort and pagination into the pipeline, made CasehubTable stateless for those concerns, and extended the URL format. Clean merge, good tests — but CI was red, and two known issues were filed during the design review. Time to close those out before moving on.

## Three problems, one branch

**CI was broken.** The #24 merge changed `ViewState`, `DeepLink`, and `ComponentEntry` types but left three test files referencing the old shapes — `drillDown` on DeepLink (removed), string-based sort fields (now `{columnId, order}` records), `expandedNodes` on ViewState (gone), and missing `hasExplicitId` on ComponentEntry. Straightforward type alignment: remove dead tests, update the rest to match the new interfaces.

**Double render on every data push.** The pipeline sets `totalRows` then `dataSet` sequentially. Both setters called `update()`, so every data push triggered two full renders — the first from totalRows was pure waste since dataSet immediately followed. The fix was a one-liner: remove `update()` from the totalRows setter. The pipeline already sets totalRows before dataSet, so the value is available when the single dataSet-triggered render runs.

**Text filter only searched the current page.** This was the interesting one. CasehubTable had a local `getFilteredRows()` that filtered `dataset.rows` by a text search term. Before #24, the table had all the data and filtered then paginated locally. After #24 centralized pagination in the pipeline, the table only received the current page's rows — so the text filter silently stopped finding matches on other pages. A user typing "Carol" while on page 2 would get zero results even if Carol existed on page 1.

## Moving the filter to where the data lives

The fix was architectural rather than mechanical: move text filtering from the component into the pipeline, alongside the sort and pagination that already live there.

The pipeline needed to know about text filters, so I added `textFilter?: string` to `ComponentState` (alongside the existing `sort` and `page`). CasehubTable now emits a `casehub-text-filter` event instead of filtering locally. The site.ts handler updates ComponentViewState and resets the page to zero — same pattern as sort and page events.

In the pipeline, when a text filter is active, the lookup runs without pagination first. The pipeline then filters the full result by substring match across all cells, and manually paginates the filtered set. `totalRows` reflects the filtered count, so the table's pagination controls show the right numbers.

I also added `textFilter` to `DeepLink` and `ViewState` for URL persistence — same `Record<string, string>` pattern keyed by component ID. The URL serializes it as `tf=componentId:searchTerm`.

```typescript
// The pipeline text filter — runs after column-value filters, before pagination
function applyTextFilter(ds: TypedDataSet, term: string): TypedDataSet {
  const lower = term.toLowerCase();
  const rows = ds.rows.filter(row =>
    row.cells.some(cell =>
      cell.type !== "NULL" && String(cell.value).toLowerCase().includes(lower),
    ),
  );
  return { columns: ds.columns, rows };
}
```

## The closure problem nobody noticed

With filtering in the pipeline, the table's old `rerender(props, dataset)` method became a liability. Click handlers captured `props` and `dataset` from the enclosing `render()` closure. When the pipeline re-pushed fresh data (via `set dataSet`), the table re-rendered correctly — but then the click handler's `rerender()` fired with the stale closure data and overwrote the fresh render.

This had been present before but was masked: the local text filter meant the stale data and fresh data looked the same in most cases. Once filtering moved to the pipeline, the stale closure carried unfiltered data while the pipeline pushed filtered data, making the overwrite visible.

The fix: replace `rerender(props, dataset)` with `this.dataSet = this.dataSet`. This triggers `update()` → `render()` using the current dataset, not the closure's snapshot. The `rerender` method became dead code and was removed entirely.

## The test isolation gotcha

The form-interaction integration tests started failing in a pattern that made no sense: test 4 (which uses text filter) passed, but tests 5-8 all showed "Bob" where they expected "Alice". The data pipeline, event listeners, ComponentViewState — everything was being properly disposed between tests.

The leak was in JSDOM's `location.hash`. When the text filter handler calls `syncUrl("replaceState")`, it writes `#/page/Contacts?tf=table:Bob` into the URL hash. JSDOM doesn't reset `location.hash` between test cases. The next test's `loadSite()` calls `restoreFromUrl(location.hash)` and silently restores the "Bob" filter — the table loads with only Bob's row, and clicking "row 0" selects Bob instead of Alice.

One line in `afterEach` fixed it: `location.hash = ""`.

This gotcha went into the garden as GE-20260625-2c2539. The misdirection is what makes it worth recording — the symptoms point at component state or event handling, not at the test environment. Everything that *should* be cleaned up *is* cleaned up; the leak is in something nobody thinks to clean.

## Where this leaves the pipeline

The data pipeline now owns all three user-interactive state dimensions: cross-filter (column-value clicks), sort, and text filter — all applied before pagination. CasehubTable is genuinely stateless for data transformation. It renders what it receives, emits events for user interactions, and the pipeline handles the rest.

The `_filterText` field still lives on the table instance for the input value display, but that's UI state, not data state. The distinction matters: UI state belongs in the component; data state belongs in the pipeline.
