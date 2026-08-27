---
layout: post
title: "The Gallery That Wouldn't Render"
date: 2026-07-12
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Pages]
tags: [gallery, runtime, data-layer, examples]
---

# CaseHub Pages — The Gallery That Wouldn't Render

**Date:** 2026-07-12
**Type:** phase-update

---

## What I was trying to achieve: every example renders

The examples gallery is the primary showcase for CaseHub Pages — 59 samples across 13 categories. After the restructure and TypedDataSet migration, a walk-through revealed 13 broken examples. Some empty, some erroring, some half-working. I wanted a clean sweep: open each one, understand why it fails, fix it.

## What we believed going in: mostly YAML data issues

I expected the fixes to be almost entirely YAML — wrong column types, missing inline data, stale references. That turned out to be half the story. The other half was runtime bugs that only surfaced because the gallery exercises code paths the test suite doesn't cover.

## Four runtime fixes nobody asked for

The most interesting fix was `type: "columns"` — a layout component that the desugar pipeline didn't recognise. Every YAML file using `type: columns` with child `columns:` rendered as `data-component-type="unknown"`. The Action Button example, the Dynamic Visibility conditional sections, any chart side-by-side — all silently empty. The fix was twelve lines in `component-desugar.ts`: detect `type: columns`, parse the span distribution, produce a grid with correctly placed child components.

The DATE column parser rejected epoch milliseconds as strings. `new Date("1718460000000")` returns Invalid Date in JavaScript — it looks numeric but the Date constructor treats it as an unparseable date string. Only `new Date(1718460000000)` (the number, not the string) works. Two lines: `Number(value)` first, fall back to string parsing if NaN.

Tree tables disabled pagination entirely because flat row slicing breaks parent-child relationships. The fix: `paginateTreeByRoots` slices by root node count, then collects all expanded children for the paged roots. Page boundaries never split a parent from its children.

The table filter input was absolutely positioned over the header row — on narrow tables or tables with many columns, it overlapped the rightmost headers. Changed the header container to flex layout so the toolbar sits beside the columns instead of on top of them.

## Twelve dashboards that needed data

Seven monitoring examples (Ansible, Backstage, Jupyter, Kepler, ModelMesh, OpenTelemetry) plus GitHub Repos, Prometheus HTTP, and Real Time JVM all pointed at external URLs with no inline fallback. Each got representative datasets — realistic metric values, proper column schemas, pre-transformed where the original relied on JSONata expressions the inline source ignores.

The Prometheus-format labels needed cleaning too. The raw `type="TCPSocketWrap"` format leaked into chart axes and table cells. Every monitoring dataset got its labels stripped to clean values.

## The dock-bar that lost its panels

The dock-bar component was removed from the gallery because its `panelId` references didn't resolve to anything. The root cause: `desugarComponent` dropped the `id` field from data components during type normalisation. A split layout with `id: charts-panel` on a bar-chart lost that ID, so the dock toggle event couldn't find its target element. One line fix — carry `raw.id` through to the desugared output. Then restore the dock-bar tab with a working split + toggle demo.

## 205 chart titles moved off canvas

The last sweep was mechanical but important: chart `title:` properties render inside the ECharts canvas, making them unselectable and inconsistent with the rest of the component model. Moved all 205 titles across 30 files to standalone `type: title` components placed above their charts.

The gallery renders. Every category has content. The interactive features — filter, sort, expand, toggle, paginate — work as described. A few examples still depend on iframe components that don't exist yet (#155 was converted to native charts as a workaround), but nothing is empty and nothing errors.
