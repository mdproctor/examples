---
layout: post
title: "Designing the Distributed Executor Protocol"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [scenario-engine, protocol-design, push-wire, executor, demo-infrastructure]
series: issue-408-scenario-engine
---

Continued from [Specifying a Scenario Format Before Building the Engine](2026-08-11-mdp01-scenario-format-spec.md).

The scenario format spec was the easy part — it defines what a scenario says, not how it runs. The harder question is orchestrator-to-executor communication. Today the `ScenarioExecutor` sends individual ARIA commands via push wire and waits for `CommandResult` back. That's a single-command RPC pattern. No stepping, no speed control, no service executors. The helpdesk example can't participate.

I started by framing this as a tension between "central orchestration" (Pages makes HTTP calls to services) and "distributed executors" (services embed local executors that receive script fragments). That framing was wrong. There is no tension. The Java server is the orchestrator — that's settled architecture. The TypeScript side orchestrates in browser-only mode. A hierarchy of orchestrators is possible but not the primary concern. The protocol sits within that settled architecture, not between competing models.

Once I stopped trying to choose an architecture and started designing the protocol, the decisions fell into place quickly. Six of them, all quick-pick:

**Ordered step sequences** as the dispatch unit — not single-step RPC (too chatty, 4 round-trips for a helpdesk scenario) and not full sub-scenarios (too complex for the first cut). The orchestrator groups consecutive steps for the same executor and sends them together. The executor runs them in order, reports per-step progress, responds to control messages. Sweet spot between simplicity and autonomy.

**Push wire WebSocket** for all executors. The browser already uses it. Service executors connect as WebSocket clients — same protocol, same infrastructure, same topic routing. One transport for everything.

**New PushRequest/PushMessage op types** rather than topic-based payloads. Pre-release, so breaking the sealed interface costs nothing. `ExecutorRegister`, `StepResult`, `dispatch-sequence`, `executor-control` — type-safe, exhaustive pattern matching, IDE completion.

**CDI @ScenarioAction annotation** for the service executor contract. A shared library dispatches incoming steps to annotated handler methods. Services add one dependency and write annotated methods — no protocol boilerplate.

The most interesting design input came from thinking about demos as presentations. The scenario format I specified earlier has flat steps. But a demo operator doesn't think in steps — they think in chapters, sections, and moments. "Run to the resolution chapter." "Step through the triage section." "Next step" means "fill out the form and submit" — not "click the first field, type a value, click the second field..."

This led to an optional hierarchical format: **chapters → sections → steps → commands**. All levels optional. A quick seed script has just steps. A full presentation demo has chapters. The key insight: a "step" is the demo-meaningful unit — all its commands execute without pausing. The operator steps between steps, never between commands. Speed affects the delay between steps; commands always run at full speed.

The controller needs to be separable. I want to run a demo from my phone while the audience sees it on my laptop. That means REST/GraphQL/MCP API on the orchestrator, state broadcast on a push wire topic, and a `<scenario-controller>` web component that can run embedded or standalone. MCP tools mean an AI agent could control the demo too — step through it on demand during a Q&A.

We got the first implementation task done — `ExecutorRegister` and `StepResult` records in `PushRequest`, `dispatchSequence` and `executorControl` methods in `PushMessage`. Hit an IntelliJ MCP gotcha along the way: when two Maven modules with the same artifactId exist in one workspace (slot copy vs main repo), `ide_insert_member` silently routes edits to the first-registered module. No error, no warning — `git diff` in the wrong repo was the only tell. Worked around it with patch transfer and captured the gotcha for the garden.

Five implementation tasks remain: hierarchical parser, orchestrator with trigger graph and stepping, browser executor evolution, service executor library, and helpdesk integration. The protocol types are the foundation — everything else builds on them.
