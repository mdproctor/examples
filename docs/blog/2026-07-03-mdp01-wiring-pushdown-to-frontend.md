---
layout: post
title: "Wiring Push-Down to the Frontend"
date: 2026-07-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [backend, typescript, data-pipeline, sql-pushdown, ssrf]
series: issue-21-data-module-backend
---

*Part of a series on [#21 — Optional Quarkus backend MVP](https://github.com/casehubio/casehub-pages/issues/21). Previous: [Completing the Data Module](2026-07-02-mdp01-completing-the-data-module.md).*

The backend SQL push-down endpoint existed but nothing in the frontend could call it. The relay path already worked end-to-end — `ServerRelayProvider` POSTs a `DataRequest`, the backend proxies it upstream, the response comes back. But the query endpoint (`/api/dataset/query`) took a `DataSetLookup` with operations attached and returned pre-processed results. Different contract. Different plumbing.

I added `serverQuery` as a fourth source type on `ExternalDataSetDef`, alongside `url`, `content`, and `join`. When a dataset definition says `serverQuery: true`, its `uuid` maps to a registered SQL query on the backend. No URL needed — the frontend sends the full lookup (dataSetId + filter/group/sort operations) and gets back columns and rows with the aggregations already applied.

The tricky part was operation separation in the data pipeline. The pipeline normally fetches raw data, stores it in the manager, and applies filter/group/sort client-side every time a component requests a lookup. For server-query datasets, the YAML-defined operations were already applied in SQL. Re-applying them client-side would corrupt the results — COUNT becomes 1, AVG turns into average-of-averages.

The fix: track which datasets used server-query in a `Set<DataSetId>`, and in `pushData`, start the base operations from `[]` instead of from `lookup.operations`. Interactive cross-filters and user sort still apply client-side on top — you're just filtering or sorting the 50-row aggregated result, not re-running the aggregation. Two branches in the sort logic needed conditioning, plus the expandable component bypass path had its own direct `manager.lookup(lookup)` call that would have silently re-applied everything.

The design review caught something I'd missed in the original backend code from the previous session. Java's `HttpClient.Redirect.NORMAL` follows HTTP redirects inside the client layer — after `validateTarget()` has already run. An attacker-controlled upstream could 302 to `http://169.254.169.254/latest/meta-data/` and the SSRF validation would never see the redirect target. The fix was straightforward: `HttpClient.Redirect.NEVER`. But the failure mode is the kind that only surfaces under adversarial conditions — in normal development, redirects go to legitimate destinations and everything looks fine.

The `DataSetResult` wire format between Java and TypeScript lined up almost perfectly. Backend returns `columns` (with `id`, `name`, `type` strings matching the TypeScript `ColumnType` enum exactly) and `rows` (string arrays). One field rename — Java's `rows` maps to TypeScript's `data` — and the existing `toTypedDataSet()` handles the rest.

What this session added: `ServerQueryClient` (thin HTTP adapter with JWT auth via the same `tokenFn` pattern as the layout store), a resolver route that early-returns before `validate()` (which would reject serverQuery datasets since they have no url/content/join), a `CONFIG_MISSING` error guard for when the deployment omits the config block, and refresh support using stored lookups.

The backend is now a complete data layer — relay for proxying external APIs, push-down for SQL aggregation — and the frontend knows how to talk to both sides.
