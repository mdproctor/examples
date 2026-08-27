---
layout: post
title: "Four small things that make the platform real"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [theming, table-export, loading-states, llm-docs]
series: issue-17-small-issues-and-llm-docs
---

## Four small things that make the platform real

The Quinoa convention gave casehub-pages a delivery mechanism. Host apps can now depend on `@casehubio` packages and render dashboards inside Quarkus. What they couldn't do — until today — was theme them, export data from tables, or get anything better than "Loading…" while data resolved.

These are the kind of gaps you don't notice when building the engine. You notice them the moment someone tries to use it.

### Theming: CSS custom properties with presets

The viz components already used `var(--casehub-font)`, `var(--casehub-bg)`, and friends throughout their shadow DOM styles. But `interactive.ts` — the navigation layer — had 30+ hardcoded hex values. Tab borders were `#d0d5dd`. Active accent was `#4f46e5`. Tree nav hover was `#f2f4f7`. None of these responded to theming.

The fix is a `theme.ts` module that defines a `CasehubTheme` interface (13 tokens), provides `LIGHT_THEME` and `DARK_THEME` presets, and exports `applyTheme(element, theme)` which sets every CSS custom property on the container. `interactive.ts` now references `var(--casehub-accent)` instead of `#4f46e5`, so everything — tabs, sidebar, tree nav, tiles — follows the theme.

`site.ts` had a minimal dark mode that set exactly two properties. It now calls `applyTheme()` with the full preset, and `LiveSite` exposes `setTheme("dark")` for programmatic switching. ECharts charts get their theme re-initialised through the component registry.

### Table export: CSV and clipboard

`tableToCsv()` converts a `TypedDataSet` to RFC 4180 CSV — quotes fields with commas, newlines, or carriage returns, escapes double quotes. `downloadCsv()` creates a Blob and triggers a download via a temporary anchor element. `copyToClipboard()` wraps the clipboard API.

When `csvExport: true` is set on a table, the toolbar gains download and copy buttons. The copy button shows a brief checkmark on success.

### Loading and error states

The base `CasehubElement` had two placeholder methods: `renderLoading()` returned "Loading…" as text, `renderError()` returned the error message as text. Both now render structured HTML inside the shadow DOM.

Loading shows three skeleton bars with a CSS pulse animation — all using theme-aware `--casehub-bg-alt` for the bar colour. Error shows an icon, the message, and a retry button. The retry button clears the error state and re-fires `casehub-data-request`, so the data pipeline tries again. If the component has no `lookup` (standalone usage), the retry button is omitted — there's nothing to retry.

### LLM documentation

The fourth piece is `docs/CASEHUB-PAGES.md` — a comprehensive integration guide written for LLMs building applications on top of casehub-pages. It covers quick start, the TS DSL vs YAML decision, every component type, the full data operations API, cross-filtering, theming, forms, grid layout, and best practices.

The goal is specific: an LLM working in drafthouse or claudony should be able to read this single file and correctly compose dashboards without human guidance. The TypeScript DSL is the promoted interface — YAML exists for runtime-defined dashboards but the DSL gives type safety and composability that YAML can't.

With the Quinoa convention published and these four gaps closed, the three host repos — drafthouse, claudony, devtown — can now start real integration work.