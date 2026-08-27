---
layout: post
title: "The Abstraction That Was Missing"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [architecture, push-sources, websocket, sse, lifecycle]
---

The data pipeline had a gap I hadn't seen until three issues forced me to look at it together. #70 (error propagation), #71 (subscription lifecycle), and #74 (SSE source) all pointed at the same root: WebSocket was hardcoded into the pipeline as a special case. No shared interface, no error channel, no lifecycle management. Adding SSE as another special case would have doubled the problem.

The fix was `PushSource` — an interface that both WebSocket and SSE implement. Three methods: `subscribe`, `unsubscribe`, `close`. A required `onError` callback separates error signaling from data events, because errors and data go to different consumers. The pipeline's `manager.apply()` processes data; `target.error` displays errors. Mixing them into one discriminated union would have forced every consumer to filter out a case it doesn't handle.

The design review caught things I'd missed. The original `handleClose` treated all non-reconnectable close codes as permanent errors — including code 1000 (Normal Closure), which is how a server says "goodbye, everything's fine." The fix classifies close codes into three tiers: reconnectable (1001/1006/1011), permanent application errors (4000+), and protocol errors (1002-1015) that get logged but don't propagate. Normal close is silent.

We extracted `processWireMessage` — the function that routes incoming JSON to the right subscription — into a shared module. Both WebSocket and SSE call the same function. The only difference is how they track reconnection: WebSocket passes an `updateSeq` callback that stores the last sequence number for its manual reconnection protocol. SSE passes nothing — the browser's `EventSource` handles reconnection natively via `Last-Event-ID`.

SSE brought its own surprise. The `EventSource` API never signals permanent network failures. If DNS fails, the connection is refused, or TLS errors — `readyState` stays `CONNECTING` and the browser retries indefinitely. Only HTTP-level failures (non-200 status) transition to `CLOSED`. I'd assumed we could detect "server is permanently gone" the way WebSocket does with close codes. We can't. SSE error propagation covers auth rejection and server misconfiguration, but not the network falling over. That's worth knowing if you're building on this.

The lifecycle management was the most interesting piece architecturally. When a component is removed from the DOM, the runtime needs to unsubscribe from any push sources that have no remaining consumers. The trick is detecting removal without coupling to the viz layer. `PagesElement.disconnectedCallback` fires, but the element is already detached — it can't dispatch bubbling events. A `MutationObserver` on the target element watches for subtree removals. When a registered component disappears, `queueMicrotask` defers the cleanup check — if the element is reattached by microtask time (a DOM move, not a removal), it's left alone.

The `DataPipeline` interface was leaking internals. `pool`, `pendingResolutions`, and `refreshTimers` were all public so that `site.ts` could clean them up in `dispose()`. That's the pipeline's job, not the site's. A single `dispose()` method now owns all cleanup — pool release, timer clearing, observer disconnection, abort controllers. The public interface is three methods: `handleDataRequest`, `setResolverCtx`, `dispose`. Nothing else.

Nine issues closed, one design abstraction. The `PushSource` interface, the generic pool, the shared wire message processing — none of these existed before, and SSE slotted in as a second implementation without touching any of the WebSocket code.
