---
layout: post
title: "Native Forms: From Brainstorming to Working Master-Detail"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [forms, architecture, testing]
series: 34-native-forms
---

*Part of a series on [#34](https://github.com/mdproctor/melviz/issues/34). Previous: [Runtime Improvements](2026-06-18-mdp01-runtime-improvements.md).*

The original DashBuilder had forms. Melviz didn't — the Kitchensink "Forms" tab pointed at a `uniforms` external component that was never registered. The tab rendered empty. I wanted to fix that properly: native form inputs that participate in the existing data pipeline, not an iframe island.

## The Design

The core principle fell out of the brainstorming quickly: a form is just a page with editable components. No separate "form" abstraction. A page declares a `dataScope` (which dataset, which column is the record identity), a `save` configuration (trigger mode, adapter name), and its components bind to dataset columns. Some components are editable (text-input, dropdown, checkbox), some are display-only (charts, metrics). The page doesn't distinguish — it just binds.

This meant forms plugged into the existing `casehub-data-request` / `handleDataRequest` / `pushData` pipeline. Form inputs get implicit `DataSetLookup` objects derived from their page's `dataScope`, fire data requests like any chart, and receive datasets the same way. One pipeline, not two.

The save system sits at the page level: `EditState` tracks dirty fields per pagePath, `casehub-field-change` events flow from form inputs to the runtime, and configurable triggers (auto-save with debounce, save-on-blur, explicit button, manual) fire a pluggable `SaveAdapter`. Two adapters ship: `local` (in-memory dataset mutation) and `rest` (HTTP PATCH back to the dataset URL).

For master-detail, I reused the cross-filter system. Parent table click emits a filter; child form page receives filtered data showing one record. But making that work exposed real gaps in the architecture.

## Where It Got Interesting

The spec went through three review rounds. The first caught 16 issues — the most critical being that the cross-filter system is strictly page-scoped (`getActiveFilterOps` queries exact pagePath) with no hierarchy. A table on "Employee List" emitting a filter doesn't reach form inputs on "Employee List/Employee Form". The second review caught 8 more issues including a race condition: switching records while a save timer is pending would apply the old record's edits to the new record. The third caught a compound filter bug where `$ref` bindings and ancestor filter collection interfere when datasets differ.

All valid, all fixed in the spec before implementation started.

But the real education came when I tested the actual running app. Three bugs surfaced that no spec review caught:

**Page-ref inlining collapses the page hierarchy.** When `- page: Contact Form` resolves via `page-ref`, the form page gets inlined into the parent's grid `items[]`. Pages in `items` don't get their own pagePath segment — they inherit the parent's. So the table and form inputs share a pagePath. After a save, the post-save re-push applies the record-selection filter to the table too, dropping it from 3 rows to 1.

The fix: inlined pages now get their own pagePath segment (using the page name), and record-selection filters are stored at the child form page's path, not the table's. The table never sees the filter.

**Table `rowIndex` breaks after text filtering.** `CasehubTable` emits `rowIndex` in filter events — the display row index. After the user types "Bob" in the search box, the display shows only Bob as row 0. Clicking it emits `rowIndex: 0`. But `ds.rows[0]` in the full dataset is Alice. The table now emits the actual `TypedRow` object reference instead of an index.

**`flushSave` must run before the filter update, not after.** When switching records, the old record's edits need to be saved using the old record's identity. If the filter updates first, `flushSave` reads the new record's ID and saves the edit against the wrong row.

Each of these was caught by real DOM interaction tests — clicking actual shadow DOM table cells, typing in filter inputs, waiting for auto-save timers. The synthetic event tests I started with (emitting fake `casehub-filter` events) passed fine because they bypassed the actual component rendering.

## The TS/YAML Toggle

Every example in the gallery now has a TypeScript DSL companion file alongside the YAML. The source code panel defaults to showing the TS version with a toggle button. An equivalence smoke test verifies that YAML and TS produce identical custom element trees, so the full interaction test suite covers both formats without running twice.

The TS version of the Contact Manager is noticeably cleaner than the YAML — type-safe field references, IDE completion on builder functions, no string-based type mapping. I expect TS to become the recommended format for form-heavy dashboards.

The interesting tension going forward is between the page-ref inlining model (which collapses page hierarchy for layout simplicity) and the filter isolation model (which needs page hierarchy for correctness). Right now the fix is targeted — inlined pages get their own pagePath. But as forms get more complex (nested subforms with `$ref` bindings, cascading dropdowns, record creation), the hierarchy question will come back.
