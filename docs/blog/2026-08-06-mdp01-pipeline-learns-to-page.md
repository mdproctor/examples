---
layout: post
title: "The pipeline learns to ask for pages"
date: 2026-08-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [pagination, data-pipeline, caching]
---

## The pipeline learns to ask for pages

Server-side pagination sounds mechanical — template a URL, fetch a page, cache it, repeat. The interesting part is where it meets everything the pipeline already does.

The data pipeline in casehub-pages handles a lot: it resolves external datasets from URLs, manages push sources over WebSocket and SSE, tracks sort and filter state per component, does stale-while-revalidate refreshes, and evicts datasets when their consumers leave the DOM. Server pagination has to participate in all of this without breaking any of it.

We started with the foundation pieces: a `ServerPaginationConfig` type on `ExternalDataSetDef`, a `PageCache` with LRU eviction, and a `ServerPaginationManager` that owns URL construction, fetch, and caching. Each standalone, each independently testable. The DSL builder followed — `serverPaginated()` with sensible defaults so the common case (offset/limit with optional sort) is a one-liner.

The real work was wiring the manager into `handleDefRequest` and `pushData`. When a dataset definition carries `serverPagination`, the pipeline has to do several things differently: no `SourceConnector` is created (request-response doesn't fit the connect/disconnect lifecycle), client-side sort and filter operations are stripped with a warning (the server handles those), and the stale-while-revalidate path is bypassed entirely because the page cache manages freshness.

Sort and filter invalidation was where the design had to think hardest. When a user clicks a column header, the pipeline needs to detect that the sort state changed, clear all cached pages (they were sorted by the old column), and re-fetch page 0 with the new sort params in the URL. Same for text filter changes. We track the last sort/order/filter per dataset and compare on every `pushData` call — a change triggers `clearCache` before the fetch.

The wrinkle I hadn't anticipated until implementation: mixed context and pagination variables in the same URL. The template syntax for context variables (`#{filter.region}`) and pagination variables (`{offset}`) is orthogonal — different syntax, different resolvers, resolved in sequence. A URL like `https://api.example.com/#{filter.region}/orders?offset={offset}&limit={limit}` needs the context variables resolved first (by the `ContextManager`), then the pagination variables filled by the `ServerPaginationManager`. When the context changes — the user selects a different region — the page cache is cleared and page 0 re-fetched with the new URL template.

The Zod schema also needed extending. Without it, YAML-declared datasets with a `serverPagination` block would have their config silently stripped by Zod's strict parsing. Two validation refinements: `serverPagination` requires `url` (can't paginate content or join datasets), and it's mutually exclusive with `serverQuery` (which has its own server-side query mechanism).

What's worth noting: the two template syntaxes being orthogonal wasn't a design decision anyone made — it fell out of the existing architecture. Context vars use `#{var}` because they're processed by `resolveTemplate`; pagination vars use `{var}` because they're simple string replacement in the URL. The fact that they compose cleanly is luck more than planning, but it's the kind of luck that validates the original architecture.
