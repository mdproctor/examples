---
layout: post
title: "Five Issues, One Connection"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [websocket, auth, relay, reconnect]
series: issue-56-websocket-robustness
---

The last session shipped the WebSocket event model — `DataSetEvent`, the unified `apply()` method, connection pooling, the whole reactive data pipeline. Five issues came out of that review. All five touched the same narrow surface: how the WebSocket source manages its connection lifecycle. Two were bugs. Three were features the original design explicitly deferred.

The bugs were straightforward. Messages without a `dataset` field were silently dropped instead of routing to the sole subscriber — a single-line condition check conflating "no field present" with "field present but unrecognised." The fix needed a three-way distinction: absent field with one subscriber (fallback), absent field with multiple subscribers (ambiguous — drop), and unrecognised field (unsubscribed broadcast — always drop, regardless of subscriber count). The duplicate subscribe guard was even simpler — an early-return when the dataSetId is already in the subscriptions map.

The append validation in the manager was independent of WebSocket but came from the same review. The `apply()` append path concatenated rows unconditionally. If a server sent rows with a different column count, the dataset became silently inconsistent — errors showed up at render time, not mutation time. The fix rejects the entire event if any row has the wrong cell count. Reject-all, not filter — partial application of a corrupt event is worse than dropping it.

The three features are where the design work was. Authentication, relay proxying, and incremental reconnect all affect how the connection is established and maintained. I wanted to get the layering right — auth and relay are deployment concerns (site-level config), not per-dataset. They belong in `DataProviderConfig`, not on individual dataset definitions.

The `buildConnectionUrl()` function composes the final WebSocket URL from three inputs: the base URL, an optional relay rewrite, and an optional auth token. The design review caught a real problem here — my original implementation used string concatenation to build the URL. That breaks when a relay endpoint already has query parameters. Switching to the `URL` API with `searchParams.set()` fixed it properly.

For incremental reconnect, the source tracks an optional `seq` field from server messages. On reconnect, subscribe messages include `since=<lastSeq>` so the server can replay only what the client missed. Three assumptions had to be stated explicitly: sequences are connection-scoped (not per-dataset), persistent across connections, and all-or-nothing per event type. If the server never sends `seq`, nothing changes — full-snapshot reconnect, identical to before.

The relay is a transparent bidirectional proxy. The client connects to the relay's WebSocket endpoint with the upstream URL encoded as a `target` query parameter. The relay opens a connection to the upstream and forwards messages both ways. From the source's perspective, it's just a different URL. Auth tokens authenticate with the relay, not the upstream — the relay handles upstream credentials server-side. No CaseHub backend implements this yet, but the client contract is defined.

The pool needed a small but important change. It's created eagerly when the pipeline is constructed, but `DataProviderConfig` arrives later via `setResolverCtx()`. A `configure()` method bridges the gap — the pool stores the config and passes it to sources when they're created on first `acquire()`.

One thing surfaced during this session that wasn't about WebSocket at all: the README and CLAUDE.md both positioned casehub-pages as YAML-first. The actual architecture is TypeScript-first — the DSL builders in `pages-ui` are the primary interface, with YAML as a runtime-loading option. `CASEHUB-PAGES.md` already said this correctly ("Always prefer the TypeScript DSL"), but the README led with YAML throughout. Fixed that while we were here.