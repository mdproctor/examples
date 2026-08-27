---
layout: post
title: "Event mode grows up"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [push, event-mode, lit, architecture]
series: issue-125-event-mode-push-api
---

*Part of a series on [#125 — event-mode push API](https://github.com/casehubio/casehub-pages/issues/125).*

The event-mode push wire protocol has been fully functional for a while — `EventConnection`, `PushMessage.event()`, `TopicRegistry`, `EventStore` all work end-to-end. The problem was never capability. It was that nobody knew the capability existed. The connectors team built a separate SSE endpoint for notifications because `createEventConnection` wasn't documented anywhere discoverable, and the server-side boilerplate to broadcast an event was three steps that every producer had to repeat.

Three things landed today.

**EventBroadcaster** wraps the three-step broadcast ceremony — append to EventStore, build the wire message, route to all interested connections — into a single `broadcast(topic, payloadJson)` call. It lives in `casehub-pages-push` as a plain Java class with a `SessionSender` functional interface for transport decoupling. No Quarkus, no CDI, no jackson-databind. The wildcard guard was a design review catch — `TopicRegistry.connections()` walks the trie treating each segment as a literal, so broadcasting to `notification:**` would silently match nothing. Now it throws.

**EventStream** is the client-side counterpart — a framework-agnostic subscription manager in `pages-data` that wraps `EventConnection` with connection pooling, per-topic reference counting, buffer capping, and an optional `parse` function for runtime type safety. The connection pool was the interesting design problem. Multiple `EventStream` instances sharing one WebSocket need per-topic subscriber counts so that `unlisten()` only fires when the *last* subscriber for a topic disconnects. Without that, disconnecting one component kills the event feed for every other component listening to the same topic.

The adversarial design review caught the biggest architectural issue before any code was written: putting a Lit `ReactiveController` directly in `pages-data` would violate ARC42STORIES §10 — "Lit at the leaf level." The fix was a two-part design: `EventStream` stays framework-agnostic in the data layer, and a thin **EventStreamController** adapter in `blocks-ui-core` wires `onChange` to `host.requestUpdate()`. The adapter is twelve lines of delegation. The framework boundary is exactly where it should be.

The pattern is worth naming: framework-agnostic core with a thin framework adapter. `SSEManager` in `pages-data` followed the same shape without making it explicit. `EventStream` makes the seam visible — `onChange` is the bridge, and any framework can wire it. Lit gets the adapter today because that's what `blocks-ui` uses; React or vanilla consumers call `connect()` / `disconnect()` directly and read `stream.latest` in whatever lifecycle hook they prefer.
