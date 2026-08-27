---
layout: post
title: "Where does the pagination go?"
date: 2026-08-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [architecture, pagination, data-pipeline, design]
---

Pages loads every row from every REST API into the browser, then slices pages client-side. For a dashboard with 50 rows, nobody notices. For one with 50,000, every page load transfers the entire dataset, parses it, stores it in the DataSetManager, and then throws away 49,975 rows to show you page 1.

The fix is server-side pagination — fetch only the page you're looking at, cache what you've seen, fetch the next page when you navigate. The interesting part wasn't the pagination itself. It was figuring out where in the existing architecture the fetching logic should live.

## The two candidates

The data pipeline in Pages has a clear layered structure: `DataSource` (fetch data) → `SourceConnector` (lifecycle) → `DataSetManager` (cache) → `pushData()` (deliver to components). A pagination wrapper around `DataSource` feels like the right place — encapsulate the page cache, expose a `fetchPage()` method, let the pipeline call it.

Except `DataSource` is `connect(sink) / disconnect()`. That's it. No `fetchPage()`. No request-response pattern. It's a push interface — the source decides when to send data. Adding `fetchPage()` would break every existing source implementation for a feature most of them don't need.

The alternative: put the pagination logic in the pipeline itself. The pipeline already handles `pages-page` DOM events, tracks page state in `componentViewState`, resolves template URLs, and manages the DataSetManager cache. Every integration point is already there.

## The code told the answer

I traced the actual event flow through the codebase. When a user clicks "Next Page":

1. The table emits `pages-page` with `{ offset: 25, count: 25 }`
2. `site.ts` receives it, updates `componentViewState` with the new page number
3. Calls `pipeline.handleDataRequest()`
4. Pipeline's `pushData()` reads the page state, calls `manager.lookup({ rowOffset: 25, rowCount: 25 })`
5. The manager slices the in-memory dataset and returns 25 rows

Every step happens in the pipeline. The `DataSource` doesn't participate in page navigation at all — it fired once on connect and hasn't been heard from since.

A middleware wrapper would sit outside this event path. The pipeline would still need to: detect the wrapper, skip the normal `pushData()` path, call `fetchPage()` on the wrapper instead, handle the async response, and manage cache invalidation when sort or filter changes. The wrapper encapsulates the fetch — but the pipeline does all the orchestration anyway. That's indirection without encapsulation.

## What we built

A `ServerPaginationManager` that the pipeline creates and owns. It handles URL construction from templates (`https://api.example.com/items?offset={offset}&limit={limit}&sort={sort}`), fetches pages via the standard `fetch` API, and caches results in an LRU `PageCache`. The pipeline routes `pages-page`, `pages-sort`, and `pages-filter` events to the manager instead of slicing from the DataSetManager.

The `PageCache` itself is simple — a `Map<string, CachedPage>` keyed by a composite of offset, limit, sort, and filter state. Sort or filter changes invalidate the entire cache (because a sorted page 1 is different data, not a re-ordering of the cached page 1). LRU eviction keeps memory bounded. Navigating back to a previously fetched page is instant.

The corrupted view protection is the part I'm most satisfied with. If a dataset is configured for server pagination, the pipeline blocks client-side sort and filter operations with a warning. Without this, a component that sorts client-side on a server-paged dataset would produce "sorted page 1" instead of "page 1 of sorted data" — a subtly wrong result that looks correct until you compare it with the full dataset.

## Where it stands

The foundation is committed: types, cache, manager, DSL builder. The pipeline wiring (Task 5) is next — connecting the manager to `pushData()` and the event handlers in `site.ts`. The design is spec'd, reviewed, and planned. The remaining work is integration, not invention.

The approach generalises beyond REST. Cursor-based pagination (GraphQL), prefetch (background fetch of page N+1), and server-side text filter all compose with the same manager — different URL template patterns, same cache and event flow. Those are future issues, but the architecture doesn't need to change for them.
