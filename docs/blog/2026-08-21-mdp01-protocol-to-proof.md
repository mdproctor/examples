---
layout: post
title: "From Protocol to Proof — The Distributed Executor Chain"
date: 2026-08-21
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [scenario-engine, distributed-executor, push-wire, e2e, helpdesk]
series: issue-408-scenario-engine
---

Continued from [Designing the Distributed Executor Protocol](2026-08-20-mdp02-distributed-executor-protocol.md).

Last session designed the protocol. This session built it end to end — and proved it works over WebSocket with a real Quarkus service executing scenario actions.

The implementation followed the protocol spec's natural layers. Parser first: `HierarchicalParser` reads the chapter/section/step/command YAML into records. I chose `ObjectMapper` with `YAMLFactory` over the streaming `JsonParser` approach the old `ScenarioParser` uses — tree navigation is cleaner when the format has four levels of nesting. The records are immutable (Java records, `List.copyOf`, `Map.copyOf`), the parser is stateless.

The orchestrator came next. `ScenarioOrchestrator` takes a `SessionSender`, parses YAML, validates that all target executors are registered, partitions steps into sequences by target (via `SequencePartitioner`), and dispatches. The partitioner is the simplest piece — group consecutive steps for the same executor — but it's the hinge between the scenario's narrative structure and the wire protocol's dispatch units. Control messages (pause/resume/step/speed) broadcast to all connected executors. A REST controller wraps the orchestrator for the presenter API.

The browser executor evolution was straightforward. `EventConnection` needed two new cases in `handleMessage` — `dispatch-sequence` and `executor-control` dispatch as `CustomEvent`s on the event target. The scenario handler grew from a single-command responder to a sequence executor with a step queue, pause state, and speed-paced delays between steps. The old single-command protocol stays for backward compat.

The service executor library (`casehub-pages-scenario-client`) is where the design paid off. `@ScenarioAction("create-ticket")` on a CDI bean method — that's the entire service-side contract. `ActionRegistry` scans beans, `ScenarioExecutorClient` receives dispatch-sequence messages, invokes the matching handler, sends step-result back. One gotcha: `getDeclaredMethods()` on a CDI proxy class returns nothing — Quarkus generates subclasses, and the annotations live on the original class. Traversing the superclass chain fixed it.

Adding stepping to the service executor was the last real design decision. I'd initially skipped it — service actions are fast, so speed delays seemed unnecessary. But demos need the audience to see intermediate state. If the helpdesk executor creates and resolves a ticket in the same millisecond, the dashboard never shows it in TRIAGED. Speed pacing between steps gives the UI time to render each stage. The executor now runs on a virtual thread with `ReentrantLock`-based pause/resume and `Thread.sleep` for speed delays.

The e2e test proved the full chain works. A `@QuarkusTest` connects a `java.net.http.WebSocket` client to the helpdesk app's `/push` endpoint. The client creates a `ScenarioExecutorClient` with the injected `HelpDeskScenarioActions` bean, using the WebSocket as the sender — so `executor-register` flows through WebSocket on connect, and step results flow back through WebSocket after each action. POST to `/scenario/start` triggers the orchestrator to dispatch, the executor runs the actions (ticket creation triggers the full engine — classification, work item creation), step results advance the orchestrator's progress.

Along the way we added two features to the scenario format itself. Data modes — bulk, stepped, and stream — let a command deliver datasets paced by the scenario's speed rather than dumping everything at once. And narrative content: each chapter, section, and step can carry markdown that the controller UI renders as the demo advances — inline text for quick demos, section extraction from a larger document for structured presentations, or reveal.js slide references for polished conference talks. A demo that narrates itself.

The controller UI is the remaining piece. The backend is ready — REST endpoints, push wire state broadcast with content inheritance, reveal.js slide references in the state object. The frontend component that renders the outline, transport controls, and narrative panel is next.
