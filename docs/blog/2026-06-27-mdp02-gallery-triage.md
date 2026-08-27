---
layout: post
title: "Gallery Triage — Kitchensink Had to Go"
date: 2026-06-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [gallery, examples, reorganization]
---

The examples gallery had its categories in alphabetical order. "ansible" and "Basic Usage" appeared before Sales, Clinical, IoT — the dashboards that actually show what the runtime can do. A visitor's first impression was a lowercase monitoring integration and a list of toy examples.

The fix was a priority array in `generate-samples.js`. Domain dashboards first, monitoring integrations second, Basic Usage last. Display names get title-cased on the way through — `ansible` becomes Ansible, `modelmesh` becomes ModelMesh. Categories not in the list fall to the end alphabetically, so new directories don't vanish.

The bigger problem was Kitchensink. It was a 13-tab dashboard containing bar charts, pie charts, meters, maps, selectors, metrics, and forms — most of which already existed as standalone examples under Basic Usage. A pie chart in Kitchensink and a table as its own example aren't different in kind, but the gallery presented them as if they were. We dissolved it entirely: related components grouped into tabbed dashboards (Charts covers all subtypes, Selectors and Filters merges three old examples, Metrics and Gauges pairs the two), unique components promoted to standalone (Maps, SVG Heatmap, ECharts Custom). Simple Chart, Filter, and Filter With Table deleted — absorbed into the grouped replacements.

While fixing the generator's file extension filter (it caught `.dash.yaml` and `.yml` but missed plain `.yaml`), two hidden dashboards surfaced: Kepler Metrics and Open Telemetry Basic. Kepler's source repo — `jesuino/melviz-yaml-samples` on GitHub — is gone. The dashboard referenced it for live Prometheus metrics data. We created mock data from scratch: 6 containers across 2 nodes, with energy breakdown by core, DRAM, package, uncore, GPU, and host components. The JSONata expressions in the YAML told us exactly what fields and metric names to generate. Kepler now renders three tabs — Joules by Node, Joules by Container, and a live Monitoring timeseries.

Quarkus Monitoring had a different gap. It polled a metrics endpoint and showed a snapshot — CPU, memory, threads — but no history. The Real Time JVM Monitoring dashboard next to it had historical data and accumulation, making the Quarkus one look broken by comparison. We added two pages: History (four timeseries charts from the existing `jvm-history.json` mock data) and Live (accumulating timeseries with 3-second refresh). The memory bar charts on the Current Status page were empty — the LIKE_TO filter used the old Java parser's label format (`area="heap",id="G1 Old Gen"`) instead of the TS parser's format (`heap, G1 Old Gen`). A one-line filter change fixed both heap and nonheap charts.

The gallery now has 42 dashboards across 15 categories, with domain examples leading and reference material at the bottom. Every dashboard that was previously broken by missing data or wrong label formats now renders.
