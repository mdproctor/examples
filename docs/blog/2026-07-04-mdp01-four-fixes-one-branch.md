---
layout: post
title: "Four fixes, one branch"
date: 2026-07-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [csp, caching, refactoring, auth]
series: issue-16-platform-fixes-batch
---

I wanted to clear the small-issue backlog — four S-scale fixes that had been sitting open since the data module landed. Each one independent, each one well-understood, none individually worth a branch of its own. So I batched them.

## The auth gap nobody noticed

`ServerRelayProvider` was sending requests to an `@Authenticated` endpoint with no auth headers. It worked in dev mode because Quarkus dev services bypass security — the kind of bug that ships perfectly until production. The fix was already staring at us: `ServerQueryClient`, built a day earlier, had the exact pattern — `tokenFn` in the constructor, `Authorization: Bearer` injection, 401 event dispatch. We copied it verbatim. Three files changed, the relay path now authenticates identically to the query path.

## Caffeine on the server

Both `/api/dataset/fetch` and `/api/dataset/query` were hitting upstream on every request. The caching design had one non-obvious constraint: tenant isolation. Cache keys include `tenantId` from the JWT — without that, tenant A's dashboard could return tenant B's data from cache.

The per-entry TTL design went through an interesting evolution during review. The original spec had static config (`casehub.pages.data.cache.ttl.<dataSetId>=120`), which requires admin foreknowledge of every dataset ID. The adversarial review caught this: datasets are defined at runtime by dashboard authors, not at deploy time by admins. We replaced it with a TTL derivation hierarchy — the frontend passes `refreshTimeSeconds` as a cache hint derived from the dataset's `refreshTime` config. A dashboard with `refreshTime: "30s"` automatically gets a 30-second cache TTL. No admin config needed, no stale-data surprise when refresh timers fire.

## Fourteen loops become zero

The push loop duplication had been growing since the first external data source was added. Every new data path — WebSocket push, parameterised URLs, server-query refresh, generator accumulation, URL polling — copied the same six-line pattern: iterate the component registry, match by dataset ID, extract the filter group, call `pushData`. Thirteen sites at count. Fourteen after the design review found one more hiding in the initial resolution `.then()` callback.

The fix is structural: `DataSetManager.onChanged` already fires for every mutation. We wired it to call `pipeline.refreshDataSet(id)`, which encapsulates the push loop as a single method. The five data-pipeline.ts loops disappeared entirely — `manager.apply()` fires `onChanged`, which fires `refreshDataSet`. The six site.ts loops each collapsed to a one-liner. Two filter handlers kept their scoping logic unchanged — same-page component matching, `selfApply` checks, filter group discrimination. That logic IS the value of those handlers; abstracting it away would hide the policy.

The result: any new data path that goes through `manager.apply()` automatically pushes to subscribers. The bug class "forgot to add the push loop" no longer exists.

## Killing unsafe-eval

`cell-extract.ts` had a `new Function("value", ...)` call aliased as `FunctionCtor` to dodge the linter. It's the same CSP violation either way — `unsafe-eval` required in the Content Security Policy header, blocking deployment in any strict CSP environment.

The JSONata bridge was already built and cached in `pages-data`. The migration meant making `applyCellExpression` async (JSONata's `evaluate()` returns a Promise) and propagating that through `datasetToSource` and every component that uses column expressions — 8 chart types, `PagesTable`, `PagesMetric`. The chart components got a generation counter in `PagesChartElement.render()` to discard stale async results; the table and metric use fire-and-forget DOM updates.

The expression syntax changes from JavaScript to JSONata. Arithmetic and ternaries are identical. String operations shift: `value.toUpperCase()` becomes `$uppercase(value)`, `value.replace(...)` becomes `$replace(value, ...)`. We audited all 18 example YAML files and found mechanical equivalents for every expression in use. The capability that's gone is arbitrary JavaScript execution — which was the whole point.

The design review surfaced something I hadn't considered: the async overhead. One Promise per cell with an expression means 3,000 promises for a 1000-row table with three expression columns. For pure JSONata expressions the promises resolve synchronously — microtask scheduling overhead, not I/O — but the spec now carries a benchmark requirement: under 10ms for that scenario, with a fallback to column-level batching if exceeded.
