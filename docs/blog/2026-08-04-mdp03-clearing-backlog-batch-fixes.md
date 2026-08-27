---
layout: post
title: "Clearing the backlog — four fixes in a batch"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-table, group-eval, terminal, dsl]
---

The visual diagram editor foundation epic is done. We closed #258 and #280 today — all four pages-side phases shipped (graph-core, graph-renderer, graph-work-registry). The remaining diagram editor work lives in blocks-ui now.

With the epic closed, we grabbed five S-scale issues and batched them onto one branch. Four landed; one got deferred.

## The Lit reflect trap

The data-table fix looked trivial — paginated mode was inheriting `height: 100%` from the host, filling the parent instead of sizing to content. Strip the height from `:host`, add it to `:host([mode="scroll"])`, done.

Except `:host([mode="scroll"])` matched nothing. The `mode` property was set correctly at runtime — `this.mode === 'scroll'` returned true — but the CSS attribute selector was looking at the DOM attribute, which didn't exist.

Lit's `@property({ type: String })` accepts values from attributes but does not reflect them back by default. `reflect` defaults to `false`. So the property lives in JavaScript-land, and the CSS selector — which matches on DOM attributes — never sees it. The fix is `@property({ type: String, reflect: true })`. For `auto` mode, which resolves at runtime based on row count, we also needed a `willUpdate` toggle on `style.height`.

This one caught us because most frameworks auto-reflect string properties. Lit doesn't, for performance reasons. The failure is completely silent — no error, no warning, the selector just never matches.

## group-eval: stop throwing, start degrading

The `UNKNOWN_COLUMN` throw in `group-eval.ts` was the single remaining place where a missing column crashed the whole dashboard. The #234 fix in `conversion.ts` had already set the pattern — `console.warn` and return `NULL`. We applied the same treatment across all six call sites in `findColumnInDataset`, making each caller bail gracefully: skip the validation, return empty intervals, or fall back to a default type.

## Terminal focus management

`PagesTerminal` creates the xterm.js instance and the container div, but every consumer was independently wiring up document-level mousedown listeners to handle click-to-focus — both known consumers had listener leak bugs. A `mousedown` listener scoped to the container and a `tabindex` on the div is all it takes. The component owns the terminal lifecycle; it should own focus too.

## DSL gaps

Added a `tiles()` navigation builder (same pattern as `tabs()`/`sidebar()`), `pattern` on `MetricProps` for number formatting, and `number[]` to `InlineData` for histogram data. The remaining gaps in #278 — `site()`, `appGrid()`, `div()`, navigation option variants — either don't exist as component types or need design decisions before they can be typed.

## What got deferred

#12 (lazy on-demand pagination) looked S-scale from the outside but is genuinely M/High. It needs a `DataSource` wrapper that fetches pages on demand, integrates with the table's `page-change` event, and caches previously fetched pages. The building blocks exist — the table already emits page-change with offset/count, REST sources support totalPath — but the caching strategy and composition with sort/filter need a real design pass.
