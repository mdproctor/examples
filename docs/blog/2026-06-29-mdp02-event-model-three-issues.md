---
layout: post
title: "casehub-pages — The Event Model That Three Issues Needed"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-pipeline, websocket, events]
series: issue-36-accumulate-websocket-datasets
---

# casehub-pages — The Event Model That Three Issues Needed

## What I was trying to solve: three issues, one root cause

Three issues had been open for different reasons — inline dataset expression generators (#36), WebSocket push sources (#52), and WebSocket multiplexing (#53) — but they all hit the same wall: the DataSetManager only knew how to replace a dataset whole or prepend rows. No append-to-end. No replace-by-key. No remove-by-key. And the schema enforced that `accumulate` required a URL, which blocked inline datasets with expression generators entirely.

The `toLowerCase` crash from clinical (#107) landed in the same session, making it a six-issue branch.

## The design: four events instead of two methods

I replaced `register()` and `accumulate()` with a single `apply(id, event)` method accepting a discriminated union: `snapshot`, `append`, `replace`, `remove`. Every mutation source — the HTTP resolver, the expression generator, the WebSocket source, the local adapter's save/delete/create — now produces typed events. The manager applies them uniformly.

This isn't speculative. The event vocabulary matches the WebSocket wire protocol from the chat broadcaster. The existing methods were already implicit events — `register` was a snapshot, `accumulate` was an append with wrong ordering. Making them explicit forced every caller to state what it means.

The append ordering changed: old was `[...new, ...existing]` (prepend), new is `[...existing, ...new]` (chronological). Charts sort by axis and don't care. Chat and log displays benefit from chronological order. The `maxRows` trim flipped from `slice(0, max)` to `slice(-max)` — keep the newest, drop the oldest.

## What the design review caught

The spec went through three adversarial rounds. The reviewer caught a critical logic bug I'd missed: my resolver migration would break all accumulate datasets on first load. The issue was subtle — `append` to a non-existent dataset should be a no-op (the column schema doesn't exist yet), so the first resolution must always produce a `snapshot`, with subsequent ones producing `append`. The fix was a `has()` check in the resolver: first load = snapshot, subsequent = append.

Other catches: duplicate subscribe messages on concurrent component renders, server-initiated close codes needing different reconnection policies (1000 = no reconnect, 4000+ = no reconnect, 1006 = backoff), and a missing single-subscriber fallback for WebSocket messages without a `dataset` field. The last one is tracked as #61 — all known servers include the field, but the spec explicitly required the fallback.

## The expression generator path

The core of #36 is a separate refresh path for `content + expression + accumulate`. On first load, the inline content is parsed normally (the expression is skipped — correct for seeding). On subsequent timer fires, `evaluateGenerator()` calls JSONata with no input data, tabulates the result, and applies it as an `append` event. The schema now allows `refreshTime` on content datasets when `expression` and `accumulate` are both set. WebSocket URLs are rejected for `refreshTime` — polling and push are mutually exclusive.

## WebSocket: source, pool, pipeline

`WebSocketSource` wraps a single connection. It parses incoming JSON (single events or batch arrays), tabulates rows via the existing `toTypedDataSet()`, and dispatches typed events to per-dataset listeners. Wire-name routing handles the case where the YAML `uuid` differs from the protocol's `dataset` field — extracted from the `?dataset=` query parameter on the URL.

`WebSocketPool` keys sources by base URL (scheme + host + port + path, query stripped). Two datasets pointing at `ws://host/ws?dataset=messages` and `ws://host/ws?dataset=presence` share one connection. The pool plugs into the data pipeline's source routing — `ws://` URLs go to the pool instead of the HTTP resolver.

Reconnection uses exponential backoff capped at 30 seconds, but only for transient failures. Server-initiated close with code 1000 or 4000+ stops reconnection entirely.

## The small things

The `toLowerCase` fix (#59) was the simplest: `columnId()` now validates at the boundary, and four `toLowerCase` callsites have `typeof` guards. Clinical's `groupBy([], [col("phase")])` call passed an array where `string | null` was expected — the `as string` cast hid it until `.toLowerCase()` blew up at runtime. Six regression tests.

For #33, accordion and appGrid were already implemented in the TS builder and renderer but missing from the YAML `NAV_TYPE_MAP`. Two lines in the map, two tests, and the Navigation Rebinding example now shows all nine nav types.

For #34, a Team Management example demonstrates `withAccess` (role-gated pages), `JOIN` aggregation (member names concatenated per team), and YAML includes (sub-pages loaded via `src:`).

The branch delivered 44 changed files across 16 commits. Two follow-up issues filed: #61 for the single-subscriber fallback and #62 for append schema validation and duplicate subscribe guards.