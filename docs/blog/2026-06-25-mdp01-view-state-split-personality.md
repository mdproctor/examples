---
layout: post
title: "The Split Personality of State Management"
date: 2026-06-25
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [architecture, state-management, url-persistence]
---

casehub-pages had a coherent architecture for cross-filter state — centralized in the runtime, URL-serialized, restored on reload. But sort and pagination were the opposite: private fields scattered across component instances, invisible to the runtime, lost the moment the page reloaded. Two state management philosophies coexisting in the same runtime.

The issue title was "view state persistence (URL-based)" and the obvious approach was to bolt sort/pagination onto the existing URL format. I wanted to understand why they weren't there already before doing that. The answer was architectural: filters were designed as page-scoped shared state from the start. Sort and pagination were treated as component-internal concerns — each table managed its own `_sortColumn`, `_sortOrder`, `_currentPage` as private fields. The table even had separate code paths for client-side sorting (local `getSortedRows()`) and server-side sorting (emit event, runtime handles it). Two mechanisms for the same operation, diverged by implementation detail.

The design we landed on centralizes sort and pagination into a `ComponentViewState` map — the same pattern as the existing `FilterState`, but keyed by component ID rather than page path. The data pipeline becomes the single place where all state (filters + sort + pagination) is applied before data reaches a component. The table becomes stateless for these concerns: it emits events, reads metadata from what the runtime pushes, and renders what it receives.

Three things surfaced during the design that I hadn't anticipated.

The first was a pre-existing race condition in filter restoration. On page load, the current code renders the component tree first — components fire data requests and receive data with empty filter state — then parses the URL and populates filters. Too late. Components on the default page already have unfiltered data. I'd never noticed because navigation usually masks it: `site.navigate()` triggers re-renders that pick up the filters. But for the root page with filters, the first render is wrong. The fix was an ordering change: populate all state before rendering, not after.

The second was that `site.navigate()` called `history.pushState()` internally, and the `popstate` handler called `site.navigate()`. During back/forward, the URL is already correct — pushing creates a duplicate history entry and silently breaks the Forward button. We extracted `navigateInternal()` for DOM-only slot activation and had the public `navigate()` compose it with `syncUrl("pushState")`. The popstate handler calls `navigateInternal()` directly.

The third was the grid builder's auto-ID assignment. We needed `component.id` to be a pure user-intent signal (set via `withId()`) so the runtime could distinguish "this component opted into URL state persistence" from "this component has an internal wiring ID." But the grid builder was auto-assigning IDs like `grid_0_0_0` to items, conflating the two. The renderer already has a `generateId()` fallback — the grid builder's assignment was redundant and now actively harmful.

The table went from 440 lines to 330. The sort/page event handlers in site.ts collapsed from 25+ lines each to about 8. The pipeline gained sort/pagination application but the complexity moved from scattered handlers into a single function — `pushData()` — which is the right place for it.

URL format extends naturally: `#/page/Sales?filter=region:North&sort=sales-table:Revenue:DESCENDING&page=sales-table:2`. The `withId("sales-table", table({...}))` call opts a component into URL persistence. Without it, sort and pagination still work within the session but don't survive reload. That felt like the right trade-off — explicit opt-in, no auto-generated IDs in URLs.

One known limitation: with pipeline pre-pagination, the table's client-side text filter now searches only the current page's rows, not all rows. The architecturally clean fix is migrating text filtering to the pipeline (#31) — the same centralization that sort and pagination just went through.