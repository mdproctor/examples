---
layout: post
title: "Backlog sweep and navigation rebinding"
date: 2026-06-21
type: build-log
entry_type: diary
subtype: diary
projects: [casehub-pages]
tags: [forms, navigation, tree, tiles, sidebar, menu, data-pipeline, prometheus]
series: issue-7-sx-backlog-sweep
---

Cleared the entire S/XS backlog in one branch, then built the navigation distinct rendering feature on the same branch. Twenty-eight commits total.

## The backlog sweep

Nine items knocked out in sequence. The typecheck fixes were mechanical — 19 `Element` → `HTMLElement` narrowing issues across `pages-runtime` test files. The test failures were more interesting: CasehubTable's filter event was sending the full `TypedRow` object but the test expected `rowIndex`. Turned out both are needed — `rowIndex` is the display-order position (wrong after text filtering), `row` is the authoritative record reference. A pre-existing bug where clicking a text-filtered table row selected the wrong record fell out of this fix.

The form infrastructure grew: DataSetOptions in dropdowns (via a proxy VizTarget pattern that dispatches a second data request through the existing single-dataset pipeline), cascading dropdown options (re-request on parent field change with filter operations), save error banners, `beforeunload` dirty-check, `SaveAdapter.delete` and `.create` for both local and REST adapters, and record prev/next navigation.

## Navigation rendering

The design went through five rounds of spec review before implementation. The key architectural decisions: tree and sidebar are self-contained (own their layout via `grid: auto 1fr`, no `targetDivId`), tiles is cards-above-content (like tabs), and `wireTabs` stays shared for tabs/pills/menu since they differ only in CSS.

Tree required the deepest work. `resolveNavGroup` in nav-desugar now synthesises hierarchical `/`-delimited slot names from nested navTree groups when the component type is `tree` — the slot key `"Settings/Profile"` is used for panel lookup while the page lookup stays flat (`"Profile"`). `wireTree` parses these keys at render time to build nested `<ul>/<li>` with expand/collapse. Top-level groups start expanded, nested groups collapsed, and programmatic activation via `activateSlot` auto-expands collapsed parents.

The Navigation Rebinding example now demonstrates three levels of rebinding: top-level pills swap between six nav variants (tree, tabs, menu, sidebar, tiles, carousel), each rendering the same content hierarchy. Inside Sales → Transactions, a secondary pills toggle swaps between table and form views of the same dataset — table shows the full grid, form shows editable fields with prev/next record stepping.

## Prometheus parser fix

A quiet bug in the metrics text parser: `indexOf("}")` found a `}` inside a URI label value (`/api/users/{id}`) instead of the label block's closing brace. The symptom was a SCHEMA_MISMATCH error blaming the Value column — you'd look at the data, see a valid Prometheus line, and be confused. Fix was `lastIndexOf("}")`. The JVM Monitoring dashboard now renders correctly with its mock data.