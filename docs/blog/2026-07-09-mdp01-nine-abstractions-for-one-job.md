---
layout: post
title: "Nine Abstractions for One Job"
date: 2026-07-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [datasource, architecture, component-model]
---

I counted nine distinct abstractions in casehub-pages for getting data to a component.
DataSource, DataSink, DataReceiver, VizTarget, DataEndpointMixin, SSEManager,
EventStream, EventStreamPool, the DataPipeline itself. Some were genuinely different
concerns. Several were the same concern wearing different hats.

The trigger was #145 — blocks-ui components need to work in two modes: standalone
(fetch your own data via an endpoint) and hosted (the pages pipeline pushes data to
you). The gap between those two worlds was papered over with separate mechanisms that
shared no code. A blocks-ui component would extend `DataEndpointMixin` for standalone
and implement `DataReceiver` for hosted, with no common lifecycle management between
them.

The original issue proposed seven fixes. When I actually read the code, two of those
fixes referenced things that don't exist (`DataEndpointMixin` in pages — it's in
blocks-ui-core; `EventStreamController` — nobody's ever written one). A third
proposed merging SSEManager with EventStream, which would have been a category error —
they speak different protocols. SSEManager wraps standard EventSource with URL-keyed
pooling. EventStream speaks the pages push wire protocol with topic subscription
commands. Different server contracts, different abstractions.

The real problem was simpler than seven items suggested. There was one missing piece:
a state machine that every component could use, regardless of whether data came from
the pipeline or from a standalone fetch. And the source factories (`restSource`,
`sseSource`, `wsSource`) leaked pipeline infrastructure into what should have been
standalone operations — `restSource` required a full `ResolverContext` with
DataSetManager, providerFactory, presetRegistry, and capabilities. A component that
just wanted to fetch JSON from a URL had to construct the entire pages runtime first.

The fix was five deliverables that replaced the original seven.

`DataReceiver` gained a `loading` property. Previously components derived loading
state from `_dataset === undefined && !_error` — which works for initial load but
can't distinguish "waiting for first data" from "refreshing with stale data
available." Making it explicit means the pipeline can signal "I'm fetching" without
clearing the stale dataset.

`VizTarget` moved from pages-runtime to pages-component. It was defined in
`data-pipeline.ts` alongside the pipeline implementation, but it's a component
contract — it says what a component can receive. Putting it next to `DataReceiver`
where component authors can reach it without pulling in the runtime.

`DataSourceController` is the core deliverable — a framework-agnostic class that
implements VizTarget and manages the full data lifecycle. Loading, error, dataset,
mutual-clearing invariant, source connect/disconnect, all four DataSetEvent types
(snapshot, append, replace, remove). No Lit dependency, no DOM dependency, just a
state machine with an `onChange` callback. PagesElement now delegates to it internally.
blocks-ui will write a thin Lit mixin that wraps it — the controller does the state
management, the mixin does the Lit `@state()` reactivity.

All four network source factories became standalone. `restSource` went from
`restSource(url, ctx: ResolverContext, dataSetId, options)` to
`restSource(url, dataSetId, options)`. Internally it uses `fetch()` directly and
`extractDataSet()` with built-in presets. No DataSetManager, no providerFactory.
`sseSource` and `wsSource` got default pool singletons instead of requiring an
explicit PushPool parameter. The pipeline still passes its configured pools when
it needs shared connections or auth config — but a standalone component doesn't
need to know pools exist.

EventStream got an `onReconnect` callback. When an SSE or WebSocket connection drops
and recovers, components doing initial-fetch-plus-delta-listen need to know they may
have missed events during the gap. The infrastructure was already there —
`EventConnection` has `onStatusChange` — it just wasn't wired through the pool layer
to the stream.

The design review caught a real gap I'd missed: the controller's `connectSource` only
handled snapshot events in the initial spec. Streaming sources (`sseSource`, `wsSource`)
send append, replace, and remove events. Without materialisation logic, those events
would have been silently dropped. The reviewed spec now has the controller handling all
four event types with validation guards matching the DataSetManager's own apply logic.

What blocks-ui gets out of this: `DataSourceController` is the single abstraction they
wrap. Their `DataSourceMixin` becomes a thin Lit adapter that delegates to the
controller. Domain concerns like `WorkIdentity` threading stay in the adapter — the
controller can't anticipate them, and shouldn't try. The SSE story stays separate too:
components that need raw event routing (notification-bell, work-item-inbox) keep
composing SSEManager alongside the controller. Different concerns, different
compositions.
