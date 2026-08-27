---
layout: post
title: "Specifying a Scenario Format Before Building the Engine"
date: 2026-08-11
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [scenario-engine, specification, yaml, demo-infrastructure]
series: issue-408-scenario-engine
---

The scenario engine epic (#408) spans seven repos and could easily become a coordination disaster. I decided to start with the two specification issues before touching any code — the YAML format spec and the demo SPI convention. Both are documentation tasks, but they force the hard design questions early.

The cross-platform design spec already existed, written during the life UI design work. It covers the format in detail but left several holes that only surface when you try to formalise it as a standalone protocol. The review feedback caught nine of them.

The sharpest was DataTrigger. The spec described it as "fires when dataset matches predicate" but never settled where the evaluation happens. Client-side DataSet from #140? Server-side polling? For cross-platform orchestration where the backend owns the trigger graph, there's only one coherent answer: server-side polling against the target service's API. Once you accept that, DataTrigger and `await: { endpoint, match }` converge syntactically — both poll an endpoint and match a predicate. The difference is semantic: a trigger gates step start, an await gates step completion. That's a design signal worth catching before writing parser code.

The `fill: { from: data }` resolution was another gap. The spec showed `fill: { from: data }` in examples without defining how scenario data fields map to DOM form fields. The resolution convention — iterate `data` keys, find elements by `data-field` attribute, dispatch by element type — is mechanical but needs to be spelled out. Without it, every executor implementation would invent its own convention.

The error model got an `on-error` field (`continue`, `stop`, `pause`) because the original "failed steps don't abort" policy is fine for long-running scenarios with independent branches, but terrible for demos where a failed seed step means the audience watches garbage. `stop` for demos, `pause` for live demos where recovery beats restart, `continue` for verification runs. Three values, one field, backward-compatible default.

File distribution was the real gap nobody noticed. The spec said each target service loads scenario data files at startup — but from where? The scenario files live in pages. Having each service independently parse the scenario YAML to load pull-mode data means distributing files to N services. The cleaner answer: the executor pushes data via `POST /scenario/bootstrap` at startup. Target services only need a bootstrap endpoint. Files stay in one place.

The demo SPI convention document formalises what clinical's `DemoDataSeeder` does ad hoc: `@Alternative @Priority(300) @IfBuildProfile("demo")`. Pull mode serves from bootstrapped data; push mode accepts injections. The priority allocation table (300 for demo connectors, 200 for shared demo identity, 100 for OIDC) prevents the CDI priority collisions that happen when every repo picks its own number.

One thing the convention surfaced: clinical's existing `DemoCurrentPrincipal` uses `@IfBuildProfile("dev")` with a fixed identity. The scenario engine needs `@IfBuildProfile("demo")` with a header-reading identity (`X-Scenario-Actor`). These are different profiles for different purposes — dev is "I'm developing locally with fixed credentials," demo is "the scenario executor is driving me with per-step actor switching." The shared `DemoCurrentPrincipal` belongs in `casehub-platform-api`, not reimplemented per app.

Two protocol documents landed in parent, the design spec got its amendments, and the queue advanced to #410 (now also done) and then #311 — the actual executor implementation. The spec work paid for itself: every ambiguity caught here is a cross-repo misalignment prevented later, when the cost of fixing it involves code in seven repos instead of prose in one.
