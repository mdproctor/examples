---
layout: post
title: "The Parser That Ate Its Own Aggregates"
date: 2026-06-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-pipeline, parser, echarts, testing]
---

Three charts in Workforce Analytics rendered empty — donut, stacked bar, and scatter all showed axes and labels but no data. The canvas elements were there, ECharts was initialised, and the existing Playwright tests passed. Everything looked correct from the outside.

The natural suspect was the rendering layer. Maybe the DONUT subtype had a configuration issue. Maybe COLUMN_STACKED mapped differently than BAR. We spent time tracing through ECharts option generation, subtype mapping, and CSS overflow before the trail went cold.

The breakthrough came from dumping `dataSet.columns` in the browser. Both columns in the Headcount donut had `type: LABEL`. The second column — the one carrying `function: COUNT` — should have been `NUMBER`. The aggregate was never computed.

The bug was in `lookup-parser.ts`, specifically in how `parseGroupEntry` classifies columns. Two conditions determine whether a column becomes a "key" (label) or an "aggregate" (computed value): does the source match the group column, and does it have a function like COUNT? The code checked source-match first. When both conditions were true — `source: department` with `function: COUNT` on `columnGroup.source: department` — the key check fired and the function was silently ignored. A two-line priority swap fixed the parser.

A secondary issue compounded the misdirection. `createTypedRow` uses a Map keyed by column ID. Two columns with the same ID (both defaulting to `"department"`) meant `row.cell("department")` always returned the last entry — the Map overwrote on duplicate keys. Even if you suspected a data issue, the Map made both columns return identical values, hiding the real shape of the problem. We fixed `datasetToSource` to use positional access (`row.cells[i]`) instead of name-based lookup.

The Rating Distribution pie appeared to work — but its proportions were wrong. Because both columns were keys (rating values like 2, 3, 4, 5), ECharts used those numbers as pie sizes instead of the actual employee counts. The chart looked plausible by coincidence.

We also reduced the Fleet Monitor's meter gauge radius from 150% to 120%. The half-circle gauges were extending well beyond their containers in narrow columns, clipping the dial at the edges.

The real gap was in the tests. The existing Playwright suite checked for canvas existence — a test that passes whether the chart shows forty data points or zero. We replaced those with tests that inspect the dataset reaching each component: pie charts must have a NUMBER column, scatter charts need numeric x and y, meters need non-zero canvas dimensions. The kind of test that would have caught this on the first run.
