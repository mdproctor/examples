---
layout: post
title: "Four Domains, One Gallery"
date: 2026-06-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [examples, gallery, spec-review, playwright]
series: issue-14-domain-example-dashboards
---

# casehub-pages — Four Domains, One Gallery

**Date:** 2026-06-26
**Type:** phase-update

---

*Part of a series on [#14 — Add domain-specific example dashboards to the gallery](https://github.com/casehubio/casehub-pages/issues/14). Previous: [Three Leftovers from View State](2026-06-25-mdp01-three-leftovers-view-state.md).*

## The gallery had a credibility problem

Thirty-six dashboards across eleven categories, and nearly every one was a Prometheus endpoint, a JVM monitor, or a Kepler energy scraper. The builder API has pie charts, scatter charts, area charts, meter gauges, CSS grid layout, panel containers, sidebar navigation, form inputs with readonly fields, property substitution, calendar-based grouping — and the gallery showed almost none of it. A visitor browsing the examples would conclude this is a monitoring-only toolkit.

I wanted to fix that with four dashboards from genuinely different domains: Sales, IoT, People analytics, and Clinical patient tracking. Each dashboard would be the primary example of features no existing dashboard demonstrates. Not a scattershot — a coverage matrix where every gap gets at least one hit.

## What the spec review caught

I wrote the spec, then reviewed it against the actual codebase. Three things I believed to be true were wrong.

**The map chart doesn't render.** `CasehubMap.ts` references `mapName: "world"` and builds an ECharts geo option, but nobody ever calls `echarts.registerMap()` with the geographic JSON data. Without that call, the chart renders empty — no error, no warning, just a blank div. The ARC42STORIES already flagged this in the risk table. I'd planned to put a world map with device markers on the IoT dashboard's front page. Replaced it with a device table and filed #35 to fix the geo data loading.

**Inline accumulate is a no-op.** The Accumulate Flag example uses `accumulate: true` on an inline dataset with an expression that should generate new rows on each refresh. I traced the full resolution path: `extraction.ts:214` explicitly skips expression evaluation when both `content` and `accumulate` are present. The comment says "expression is a refresh generator, not a transform" — describing intended behaviour that isn't implemented. The component's refresh timer just re-pushes cached data. Filed #36.

**`selfapply` was silently broken.** The Kitchensink uses lowercase `selfapply` in its filter configuration. The TypeScript interface defines `selfApply` (camelCase). The runtime at `site.ts:487` reads `selfApply`. The lowercase property is silently ignored — the filter never self-applies. Every new dashboard in the spec used the correct camelCase.

## What got built

Four dashboards, each `.dash.yaml` and `.ts` companion:

**Sales Dashboard** — sidebar navigation across four pages (Overview, Pipeline, Trends, Deals). Demonstrates `groupByCalendar` with MONTH and QUARTER, property substitution (`${GoalsFunction}` switching the aggregation function), `selfApply` on a label selector, donut charts, stacked bars, and horizontal bars.

**IoT Fleet Monitor** — sidebar navigation, three pages, dark mode globally. Three meter gauges with explicit `end` values (the Pressure gauge at hPa scale needs `end: 1060` — the default of 100 would peg it). Area stacked charts for temperature history, smooth lines for humidity. Static data — no live simulation, because that's broken (#36).

**Workforce Analytics** — single page using `grid(3, ...)` with `at()` placement and `panel()` containers. Scatter chart (tenure vs salary), horizontal bar chart, donut, pie, and a table with `csvExport: true`. The only dashboard using CSS grid layout instead of rows/columns.

**Patient Tracker** — tabs navigation, three pages. The form page demonstrates what Contact Manager doesn't: readonly fields mixed with editable fields on the same form, and master-detail driven by filter notification rather than tab navigation. The markdown component shows ward protocol notes — the first use of `markdown()` outside a DevOps context.

## The publish workflow was broken too

The CI workflow (`publish-packages.yml`) used `npm publish` to push packages to GitHub Packages. But the packages use Yarn's `workspace:*` protocol for cross-references. `npm publish` ships the `package.json` verbatim — consumers outside the workspace get `"@casehubio/pages-data": "workspace:*"` as a dependency version, which npm can't resolve. Switched to `yarn npm publish --access restricted`, which resolves `workspace:*` to real versions in the published tarball.

The `npm view` check that guards against re-publishing also needed `NODE_AUTH_TOKEN` — the Yarn auth token alone doesn't satisfy the `.npmrc` that `setup-node` creates. Both tokens now set.

## Playwright tests for regression

Fourteen spot tests across the four dashboards. Each test opens the dashboard in the gallery and inspects the shadow DOM — counts charts with canvas elements, verifies table row counts, checks meter rendering, confirms dark mode background colour, clicks sidebar and tab navigation to verify page switching, and validates that markdown content mentions clinical terms rather than DevOps jargon.

The existing smoke test already covered "loads without errors" for all dashboards via `samples.json` auto-discovery. The new tests are more specific: they verify the right components rendered on the right pages.

The gallery now shows that casehub-pages handles sales pipelines, sensor monitoring, HR analytics, and clinical patient tracking — not just Prometheus scrapers.
