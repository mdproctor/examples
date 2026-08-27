---
layout: post
title: "The Refresh That Wasn't"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-pipeline, datasource, cache]
series: issue-148-datasource-url-routing
---

The pipeline had a method called `refreshDataSet`. It didn't refresh anything.

It re-delivered cached data from the manager to subscribers — useful when filters or sorts change, but silent poison after a mutation. A panel does a POST to mark a notification as read, the action handler calls `refreshDataSet`, and the user gets the same stale row they just marked. The API name was a lie, and every caller trusted it.

I'd been planning to fix this alongside #148 (DataSourceController endpoint URL routing). Both are S-scale deferred items from the DataSource epic, both touch the data pipeline's relationship with external sources. The URL routing was the simpler half — the controller already had `sourceFactory` callback injection, so the work was a default factory in `pages-data` that routes by URL scheme (`ws://` → `wsSource`, `sse://` → `sseSource`, everything else → `restSource`) and a signature alignment to give `restSource` the same `(url, id, options?)` arity as the other two.

The refresh fix was where the design got interesting. The obvious change — make `refreshDataSet` actually re-fetch from the external source — creates an infinite loop. The pipeline's `DataSetManager` has an `onChanged` callback, and the site wires it to call `refreshDataSet`. Change "refresh" from cache-serve to re-fetch and the cycle is: re-fetch → source delivers data → `manager.apply()` → `onChanged` → `refreshDataSet` → re-fetch → ∞.

An adversarial design review caught this before I wrote a line of implementation code. The fix is a semantic split: `refreshDataSet` means re-fetch from the external source, `deliverDataSet` means re-deliver cached data to subscribers. `onChanged` calls `deliverDataSet`. The cycle breaks because `deliverDataSet` reads from the manager without writing to it.

Once the split existed, three freshness layers fell into place naturally. Layer 1: explicit re-fetch — `refreshDataSet` with four-path routing (push subscriptions skip, DataSource path disconnects and reconnects, parameterised URLs re-evaluate their template, legacy defs re-resolve). Layer 2: a new `pages-refresh-request` event so panels can trigger re-fetch without knowing their dataset ID. Layer 3: stale-while-revalidate at the pipeline level — the `DataSetManager` now tracks insertion timestamps, and `handleDataRequest` checks data age against a TTL (the dataset's `refreshTime` or a 60-second default). Stale data is served immediately while a background re-fetch runs. A `pendingRefreshes` guard prevents multiple components bound to the same dataset from each triggering their own re-fetch.

The `pages-action-complete` handler — the post-mutation refresh event — now delivers fresh data without any caller changes. The bug it had was invisible because the API name said "refresh" and nobody questioned what that meant.
