---
layout: post
title: "The gap was narrower than it looked"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [runtime, selection, master-detail, context]
---

Five clinical patterns in casehub-clinical have been blocked by the same gap: you can't select a row in one table and have dependent panels fetch their data based on what was selected. AE table to grade history. Deviations to precedents. The kind of master-detail pattern that every enterprise app needs but that currently requires a custom Lit component with hardcoded entity IDs.

The interesting part: the gap is much narrower than it first appears.

`PagesDataTable` already emits `selection-change` events with `composed: true`. `ContextManager` already resolves `#{var}` templates in dataset URLs by walking dot-separated paths against `RuntimeContext`. `ContextConsumer` already defers fetch when template variables resolve to empty strings. The entire deferred-fetch-until-selection-exists mechanism comes free from existing infrastructure. The only missing piece is a bridge — something to catch the table's selection event and write the selected row's data into `RuntimeContext` so the template resolution kicks in.

That bridge turned out to be about 15 lines of actual logic: a `selection-change` listener in `site.ts` (following the exact same pattern as `pages-filter`, `pages-sort`, and `pages-page`), an `updateSelection()` method on `ContextManager`, and a `selection` field on `RuntimeContext`. A detail dataset with a URL like `/api/ae/#{selection.events.id}/grade-history` now resolves and fetches automatically when a row is selected, and defers silently when nothing is selected.

One thing the design review caught that I'd gotten wrong: I assumed the event listeners for framework events lived in `data-pipeline.ts`. They don't — every single one is registered in `site.ts`. The data pipeline is a stateless utility that `site.ts` calls into. The decision review flagged this, and flagged something more important: there's already a record-selection path inside the `pages-filter` handler that detects when a clicked row contains a child DataScope's `idColumn` and sets a cross-filter. The new selection bridge is a parallel mechanism, not a replacement. The existing path does local filtering within a DataScope hierarchy (child page shows a filtered view of the parent's data). The new path does parameterised URL fetching (detail datasets fetch from entirely different API endpoints based on the selected row). Both are needed for different use cases.

The gallery example ended up being the most revealing part. Building it exposed a parser bug: the `type: "markdown"` desugaring path in `component-desugar.ts` doesn't forward `visibleWhen`, while the shorthand form (`markdown: "content"`) does. A six-line omission that's been there since the component was written — nobody noticed because no existing example uses `visibleWhen` on a markdown component via the explicit type form. The workaround is the shorthand syntax; the real fix is a one-line addition to the parser that should land with the next batch.

With the bridge in place, the clinical integration work — wiring the five AE and deviation patterns to real backend data — is unblocked. That's issue #299 (dataset builders and YAML desugaring) and then the clinical side.
