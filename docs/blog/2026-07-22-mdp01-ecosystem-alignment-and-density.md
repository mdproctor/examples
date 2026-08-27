---
layout: post
title: "Ecosystem alignment and the density problem"
date: 2026-07-22
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [npm, ecosystem, table, metrics, ux]
---

## The version coordination tax

I wanted all the ecosystem repos aligned on the same foundation packages — pages and blocks-ui. The published npm versions were stale (0.2.3, weeks behind), and three repos (claudony, devtown, openclaw) were still pinning semver ranges against the registry. Every time we improved something in pages, nobody downstream saw it.

The fix was straightforward: convert everything to `file:` references pointing to sibling repos on disk. Five repos already did this (aml, clinical, chat-app, iot, drafthouse). We brought the remaining three inline, plus blocks-ui itself — 20 package.json files across blocks-ui's core and components, all pointing at `../../../pages/packages/<pkg>`. The template in pages got updated too.

The decision behind this: npm has no SNAPSHOT equivalent. Publishing multiple times a day and coordinating versions across consumers is untenable. `file:` refs during development, BOM enforcement (parent#392) for releases. Every repo now resolves against local source.

## Fixing what #199 claimed to fix

The table toolbar compaction (#199) had been "done" — tests passed, commit message said "eliminates toolbar row." But the toolbar was still visually there, a full-width row above the headers. The tests checked that `.kebab-zone` existed inside `.header-container` and that `.filter-bar-input` was present. Both true. Both meaningless — the old `.toolbar` div was still in the template, CSS still rendered it as a row.

The real fix: remove the `.toolbar` div entirely, position the kebab absolutely in the top-right of the header container, and make the filter bar appear only on demand via a "Show filter" toggle in the kebab menu. The filter bar now has Go and X buttons. I added tests that assert the *absence* of the old structure — `expect(querySelector('.toolbar')).toBeNull()` — which is what should have been there from the start.

## The density problem

Looking at DevTown running live, the UX issues are obvious. The Merge Queue tab shows seven metrics — all zeros — each on two lines (label above, large number below), stacked vertically down the full page width. That's the plain-text metric subtype, supposedly the leanest option. It's still too much space for what should be `Queue Depth: 0`.

I built a Metric Patterns example in the gallery showing all four subtypes side by side: card (huge bordered box), card2 (horizontal), plain-text (stacked), quota (progress bar). Even plain-text burns vertical space for a single integer.

The real issue isn't the metric component's styling — it's using the wrong component entirely. DevTown's Workbench panel fetches system-health data and renders four values from it using four separate metric widgets. What it needs is a compact label:value grid. We explored whether to add a new subtype, a metricGrid layout, or a dedicated statGrid component. The answer turned out to be simpler: a lightweight table.

PagesTable is heavy — filters, sorting, pagination, virtual scroll, cell spanning, tree rows, CSV export. Using it to render four label:value pairs is wrong. But a purpose-built stat grid is just reimplementing a stripped-down table. The right answer is a new `SimpleTable` component in pages-viz that extends PagesElement, gets a dataset via the pipeline, and renders rows and columns with no chrome. A display variant (like `summary` — no headers, tight rows, bold values) gives you the compact stat view without a new component per use case.
