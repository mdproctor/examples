---
layout: post
title: "ExternalDataSetDef and the Shape Problem"
date: 2026-06-13
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, gwt-migration, external-data, jsonata, extraction]
---

## ExternalDataSetDef and the Shape Problem

The previous four issues built the data model from the inside: types, filters, grouping, lookup, manager. Issue #6 faces outward — how does data get in? The Java `ExternalDataSetDef` fetches JSON from URLs, parses CSV files, evaluates JSONata expressions to reshape API responses, and handles Prometheus metrics. The TypeScript version needs to do all of that while running entirely in the browser.

I started with the assumption that a DataProvider abstraction was unnecessary overhead — just call `fetch()` and let CORS be the deployer's problem. That's what the Java GWT code does. But researching how Grafana, Superset, and Metabase handle external data changed my thinking. Every production dashboard tool proxies data fetches through a backend. Melviz doesn't have a backend, but it should support every deployment topology: direct browser fetch, CORS proxy, server-side relay, inline content, and host-injected data via postMessage. The right abstraction isn't about the network call — it's a `DataProvider` interface that each deployment fills differently.

The extraction pipeline was the real design challenge. The Java code has `type` (named presets like PROMETHEUS) and `expression` (custom JSONata), and the spec initially declared them mutually exclusive. Claude's code review caught that this was wrong — the Java `ExternalDataSetClientProvider` applies them sequentially: preset first, custom expression second. A user writes `type: prometheus` to get standard extraction, then adds `expression: "$[value > 100]"` to filter the result. The pipeline is composable, not a precedence-based switch.

This led to a three-stage extraction model: `dataPath` navigates into the response (dot-separated property access), `type` applies the preset's JSONata expression, `expression` applies a custom transform. All three are optional and chain when present. The naming also surfaced a subtle bug in the original spec — Java's `path` field is URL path composition (`new URL(def.getPath(), url)`), not JSON navigation. I renamed it `dataPath` to avoid confusion. The Java URL-path behavior is dropped; the URL field should contain the full path.

JSONata threw a genuine surprise. When a JSONata expression maps over an array and produces exactly one result, the library auto-unwraps the outer array — `[[1686700000, "1"]]` becomes `[1686700000, "1"]`. This is consistent with JSONata's sequence semantics but breaks any downstream code expecting array-of-arrays. The bug only surfaces with single-element data, which is common in development (one metric, one record) and masked in production (many records). The fix is simple — detect `!Array.isArray(data[0])` and re-wrap — but finding it required understanding a behavior that isn't called out in JSONata's documentation.

The preset system ships with six built-in extraction expressions: Prometheus (handling vector, matrix, and scalar result types), Elasticsearch (unwrapping `hits.hits[]._source`), GraphQL Relay (flattening `edges[].node` with dot-separated nested keys), JSON:API (merging `data[].attributes` with `id` and `type`), OData (stripping `@odata.*` annotations from `value[]`), and Kubernetes pod metrics (cross-producting `items` × `containers` into flat rows). Each preset is a single JSONata expression — the registry is immutable after construction, no runtime mutation.

The column inference gate was an intentional divergence from Java. The Java code infers column types from the raw response even when a preset has reshaped the data — the `else if` branches off `expression`, not `type`. Inferring columns from a raw Prometheus JSON structure when the preset has already produced a tabular result is wrong. The TypeScript version always infers from the pipeline output.

What makes this layer different from the previous issues is that it doesn't own the data shape. Filters, grouping, and sorting operate on a known schema — `TypedDataSet` with typed columns. The extraction pipeline has to handle whatever an API returns — nested objects, arrays of arrays, explicit `{columns, values}` structures, CSV text, Prometheus exposition format — and produce the same `TypedDataSet` that the rest of the engine expects. The pipeline's four stages (parse → navigate/extract → tabulate → convert) each have a clear boundary, and the final stage reuses the existing `toTypedDataSet()` from `conversion.ts`. No new conversion logic, no parallel type system.

The accumulate semantics required careful matching to the Java behavior. New data goes first; old rows fill the remaining capacity up to `cacheMaxRows`. This is the opposite of what "append" suggests — it's a new-data-first rolling window where the oldest rows get shed. The schema validation on accumulate prevents a silent corruption path where an API response changes shape between fetches.
