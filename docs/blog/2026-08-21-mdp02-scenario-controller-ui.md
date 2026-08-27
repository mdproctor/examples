---
layout: post
title: "Building the Presenter Remote — A Scenario Controller for Live Demos"
date: 2026-08-21
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [scenario-engine, lit, web-components, push-wire, presenter-remote]
series: issue-408-scenario-engine
---

Continuing the [scenario engine series](2026-08-11-mdp01-scenario-format-spec.md).

The distributed executor protocol is done — orchestrator dispatches step sequences to browser and service executors, stepping and speed control work end-to-end. What's missing is the thing a presenter actually holds: a controller UI that shows where you are in the demo and lets you pace it.

I wanted a phone-as-remote experience. Open a URL on your phone, see the scenario outline, tap play/pause, drag a speed slider. The laptop runs the demo; the phone controls it. Two devices, one push wire connection keeping them in sync.

## The Push Wire Gap

The orchestrator already had `GET /scenario/state` for polling, but the controller needs real-time updates — when a step completes, the outline should highlight the new position immediately. That meant adding `scenario:state` broadcast via `EventBroadcaster`, the same infrastructure that powers live data dashboards. Every state mutation — start, pause, resume, step completion, speed change, stop — now pushes the full `ScenarioState` to all listeners on the `scenario:state` topic.

The `stop()` method was interesting. The obvious implementation clears the session and broadcasts idle state. But executors need to know the session ended too — they might have queued sequences still running. The fix: broadcast `executor-control: stop` to all executors *before* clearing the session ID, then broadcast the idle state to controllers. Order matters because `PushMessage.executorControl` requires a non-null session ID.

`runTo()` was similarly incomplete — it was just calling `resume()`. The spec calls for fast-forwarding at maximum speed and pausing when the target step completes. I added a `runToTarget` field that `onStepResult()` checks after each completion. When the target matches, it restores normal speed and pauses. Simple state machine, but it makes the outline's click-to-navigate actually work.

## Two Components, One Controller

The design review pushed us toward extracting a `ScenarioConnectionController` — a Lit `ReactiveController` that manages the push wire lifecycle. Both `<pages-scenario-controller>` (outline + transport) and `<pages-scenario-narrative>` (markdown content) use it. The controller handles mode resolution (embedded with a shared connection vs remote with its own WebSocket), topic listening, state extraction, and REST command dispatch.

I'd initially planned a single LitElement with internal state. The reactive controller emerged when the narrative component needed the same connection lifecycle — at that point, duplicating the listen/unlisten/mode-resolution logic across two components was worse than the abstraction.

The controller renders three sections: an outline tree (with `role="tree"` ARIA markup and position-aware highlighting), a transport bar (play/pause, step, logarithmic speed slider), and a status breadcrumb with connection indicator. Space bar toggles play/pause; right arrow steps forward. The narrative component renders inline markdown with HTML entity escaping — no `unsafeHTML`, no XSS vectors.

## The Outline Model

The backend needed a way to project the hierarchical scenario (chapters → sections → steps → commands) into something the controller can render as a tree. Rather than mirroring the four Java record types (`ScenarioChapter`, `ScenarioSection`, `HierarchicalStep`, `ScenarioCommand`), I used a single recursive `OutlineNode(label, target, children)`. Chapters become nodes with section children; sections become nodes with step children; steps are leaves with a `target` (executor name). One type, any depth.

`NarrativeContent` needed Jackson `@JsonTypeInfo` annotations for the wire format — the sealed interface with `Inline`, `Template`, and `Slide` variants serialises as `{"type": "inline", "markdown": "..."}`. Without the annotations, the type discriminator was missing and the TypeScript side couldn't dispatch on content type.

## The Remote Page

A static HTML file at `/scenario/remote.html`, served by Quarkus from `META-INF/resources`. It loads a standalone ESM bundle (built by esbuild from a `standalone.ts` entry point) and sets `baseUrl` to `window.location.origin`. The controller creates its own `EventConnection` in remote mode — no shared connection needed, because the remote page IS the whole application.

The bundle is 433KB — all of Lit plus the two scenario components and their dependencies. Not tiny, but acceptable for a presenter tool that loads once per demo session.

## What I'd Do Differently

The `_fetchInitialState()` race was avoidable. When the component connects, it fires an async REST fetch for the current state while simultaneously listening for push wire events. If a push event arrives before the fetch resolves, the fetch overwrites the newer state. I added an `Array.isArray()` guard (the outline fetch was hitting the same mock in tests), but the real fix is making push events authoritative — only apply the REST response if no push event has arrived yet. A flag would be cleaner than a type check.

The Vitest decorator configuration caught me off guard. Lit's `@property` and `@state` decorators need `experimentalDecorators: true` in *both* the vitest esbuild config and tsconfig.json. The error message points at a CSS line inside `static styles`, not at the decorator — completely misleading. The existing `pages-ui-components` package had this configured; `pages-aria` didn't because it hadn't needed Lit until now.

## What This Opens Up

The controller is the operator's interface to scenarios, but the scenarios themselves still need a catalogue. Right now `POST /scenario/start` takes raw YAML — there's no `GET /scenario/list` or dropdown in the UI. That's the gap between "presenter remote" and "self-service demo launcher." The next issue (#343) moves scenario push routing into push-runtime for zero-config orchestrator setup, which gets closer to scenarios being a first-class runtime capability rather than something you wire up manually.
