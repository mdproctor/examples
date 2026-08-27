---
layout: post
title: "Four small things, one interesting design gap"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [event-connection, action-button, animation]
---

The backlog had four issues tagged XS or S. I batched them onto one branch to clear them in a session.

## The spec-only fix

The grouped-view spec (#173) described setting `hidden` on `transitionend` during collapse animation, but didn't guard against the user re-expanding mid-collapse. CSS reverses the transition, `transitionend` fires at the expanded position, and the unguarded handler hides a fully visible section. The row-detail-expansion spec had already solved this with two guards — a property filter (`max-height` vs the shorter `opacity` transition) and a state guard (`.collapsing` class still present). Applied the same pattern. No code change — grouped-view isn't built yet.

## The fire-and-forget that shouldn't have been

The reconnection gap work (#174) was more interesting than expected. `EventConnection` already parsed the server's `gaps` array from the `ListenAck` response — topics where the server couldn't fully replay missed events. But the reconnect path was fire-and-forget: `sendListen()` was called in `ws.onopen` without registering a pending entry, so the ack (and its gaps) were discarded on arrival.

The fix reuses the existing `pending` map mechanism. Instead of creating a new callback path, the reconnect listen registers a pending entry whose `resolve` function calls `setStatus('connected', { gaps })` instead of resolving a Promise. The `connected` status is now deferred until the ack arrives — which is the correct semantics. "Connected" should mean "subscriptions restored and ready to receive events," not just "WebSocket open."

`EventStream.onReconnect` gains a `gaps: string[]` parameter. Consumers can now distinguish a clean reconnect from one with data holes and show a stale-data UX if needed.

## Cursor persistence

The cursor persistence work (#175) was straightforward once #174 landed. A new `cursorStorage` option on `EventConnectionOptions` accepts a `Storage` instance. On construction, persisted cursors seed `topicSeqs`; on each seq update, they're written back. Page reloads reconnect with a `since` map instead of requesting a full snapshot. Storage key is URL-scoped (`pages-ec:<url>`), cleaned on `unlisten` and removed on `close`. Corrupted storage is silently ignored.

## The action button that already existed

The action button issue (#138) described a component that didn't exist — but `PagesActionButton` was already implemented in `pages-viz` with tests, `pages-action-request` dispatch, and runtime integration. The actual gap was narrower: missing `ghost` and `outline` variants, no `disabled` property, no loading spinner. Added all three. The architectural question — whether this should be a Lit component in `pages-primitives` instead of a vanilla component in `pages-viz` — is real but separate from the functional gap.
