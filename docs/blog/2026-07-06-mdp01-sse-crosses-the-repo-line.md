---
layout: post
title: "SSE Crosses the Repo Line"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [sse, cross-repo, publishing]
---

# casehub-pages — SSE Crosses the Repo Line

**Date:** 2026-07-06
**Type:** phase-update

---

## What I was trying to achieve: move SSEManager from blocks-ui to pages and make it work with named SSE events

SSEManager has been sitting in blocks-ui-core since blocks-ui was first built — a general-purpose SSE connection manager with URL pooling, RAF batching, and exponential backoff reconnection. It only handles unnamed SSE events via `onmessage`. The notification UI design review surfaced an incompatibility: server endpoints using the standard SSE `event:` field — `notification`, `unread-count`, `notification-updated` on a single connection — get silently dropped. The browser dispatches named events to `addEventListener`, not `onmessage`.

The goal was three things at once: extract SSEManager from blocks-ui into pages-data (#131), extend it with named event support, and get blocks-ui consuming the published package. Another session was starting on the data table (#22) in blocks-ui, so the SSE work had to land cleanly without stepping on table code.

## What we believed going in: the extraction would be the hard part

I expected the tricky bit to be figuring out where SSEManager belongs in pages-data and how to wire the exports. The package already has dataset-specific SSE infrastructure (`createSseSource` in `dataset/external/sources/`) that uses `addEventListener` for its own wire protocol events. SSEManager is different — it's general-purpose, not dataset-coupled. We put it at `packages/pages-data/src/sse/` as a separate top-level module, keeping it out of the dataset pipeline hierarchy.

The actual hard part was publishing.

## Publishing turned out to be the real obstacle

The implementation went smoothly — SSEManager with a new `eventNames` option on `subscribe()`, per-handler options tracking so reconnection rebuilds the correct listener type, 38 tests. The design decision that mattered: named event handlers get their own `addEventListener` registrations per event name, stored in a `boundListeners` map on each handler entry. On reconnect, we clear and re-attach all listeners rather than copying `onmessage` references (which was the original approach and would have silently failed for named events).

Then we hit publishing. `@casehubio/pages-data` was supposedly already published at 0.2.0 and 0.3.0, but blocks-ui had never consumed it. Yarn 4's npm auth turned out to be the real puzzle. I put the token in `~/.yarnrc.yml` — Yarn silently ignored it because the project-level `.yarnrc.yml` also declares the `casehubio` scope. The project scope takes complete precedence, no property merging. Then `yarn npm whoami` reported "No authentication configured" even after fixing the config — GitHub Packages doesn't implement the `whoami` endpoint. I only discovered the auth was actually working by trying `npm publish` directly.

The fix: `${GH_PACKAGES_TOKEN}` env var interpolation in the project-level `.yarnrc.yml`. Token never in source control, works across repos. Published as 0.2.1 to stay aligned with Maven's `0.2-SNAPSHOT` convention.

## blocks-ui migration was the easy part

With pages-data published, the blocks-ui side was mechanical. Remove `src/sse/` from blocks-ui-core (source, tests, barrel export — 220 lines deleted). Add `@casehubio/pages-data@^0.2.1` as a dependency of work-item-inbox. Update two import lines. Fix the `MockSSESource` in the examples app — its `addEventListener` was a no-op, which would have silently broken named event subscriptions.

Both repos build and test cleanly. The other session working on the data table in blocks-ui sees no interference — SSE code and table code live in different directories entirely.

## Where this leaves the migration

SSEManager was item 1 of 8 in blocks-ui#21 (the full migration to pages packages). The remaining items — tokens, mixins, SchemaForm, DatasetContract, BlocksTheme, visual regression — are independent of SSE and can be done in any order. The `GH_PACKAGES_TOKEN` pattern established here means the auth problem won't repeat for the next pages package that blocks-ui needs to consume.
