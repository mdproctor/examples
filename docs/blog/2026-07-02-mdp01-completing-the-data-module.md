---
layout: post
title: "Completing the Data Module"
date: 2026-07-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [backend, quarkus, sql, data-provider]
---

The backend shipped last session with auth and layout persistence, but the data module was a placeholder — just a `package-info.java`. The original #21 issue described a SQL data provider with push-down operations, and I wanted to finish the story before moving on.

The frontend already had the protocol figured out. A `ServerRelayProvider` in the TypeScript data layer POSTs a `DataRequest` to a configurable endpoint and expects a `FetchResult` back. The backend's job is to implement the other end of that conversation — plus a second endpoint that pushes filter, group, and sort operations down to SQL instead of computing them client-side.

I split it into two Maven modules, following the same pattern as layout/layout-sqlite. The core `data` module holds the SPI, REST resource, relay proxy, and all the DTOs. The `data-sql` module implements the SPI using Quarkus Agroal. A deployer who only wants the relay proxy includes one module; SQL push-down requires the second.

The interesting design constraint was the filter expression tree. The frontend sends a recursive AND/OR/NOT structure with typed leaves — numeric, string, date, and an "unresolved" form that carries raw column ID, function name, and string arguments. The Java side models this as a sealed interface with seven record subtypes and Jackson polymorphic deserialization. The SQL builder walks the tree recursively, emitting parameterized WHERE clauses with bind variables for every value.

Base queries are configured server-side, not user-supplied. The frontend sends a `dataSetId` like `"sales-summary"` and the backend resolves it to a registered query via `casehub.pages.data.sql.queries.<name>.query`. The query is wrapped as a subquery and operations appended: `SELECT ... FROM (<base>) AS _ds WHERE ... GROUP BY ... ORDER BY ...`. Column identifiers are validated against an allowlist derived from the base query's metadata, and all column names are ANSI-quoted. Three layers of SQL injection prevention — the sort of thing you'd rather get right on the first pass.

The relay proxy needed SSRF protection: scheme restriction (http/https only), an optional host allowlist, and a private IP block that resolves the target hostname and rejects loopback and site-local addresses. Simple to implement, easy to forget.

After the data module landed, I did a gap analysis against the original GWT DashBuilder. The project is at roughly 80% feature parity. Every visualization type, every layout primitive, the complete data pipeline, cross-filtering, URL deep linking — all done, and several capabilities (WebSocket/SSE sources, client-side caching, the workbench primitives) go beyond what the GWT version ever had. The remaining 20% falls into five areas: backend data providers (Prometheus, Elasticsearch, Kafka), the layout editor, Module Federation plugin loading, E2E testing, and accessibility.

I created six issues for the uncovered gaps and organized everything — the new issues plus the existing tracked work — under epic #95. Fifteen child issues, five categories, a clear inventory of what's left.

The data module is the last piece that makes the backend feel like a real system rather than a scaffold with auth bolted on. Auth gives you identity, layout gives you persistence, data gives you the reason anyone would run a backend in the first place.
