---
layout: post
title: "casehub-pages: The Name Catches Up"
date: 2026-06-20
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [rename, ecosystem, foundation]
series: issue-024-casehub-pages-rename
---

*Part of a series on [#24 — Move to casehubio org](https://github.com/mdproctor/melviz/issues/24).*

melviz started as a fork of dashbuilder — Red Hat's GWT dashboard authoring platform. The goal was to modernise it: strip out GWT, replace the Java rendering pipeline with TypeScript, keep the YAML dashboard format. Nine previous diary entries document that journey, from the first DataSet model through filter engines, Web Components, the loadSite() runtime, and native forms. By the end of the forms work, the TypeScript runtime had reached near feature parity with dashbuilder. The GWT core was dead code — still in the repo, still in the build, but nothing called it.

This session closes the loop. The project is now casehub-pages: a foundational module in the CaseHub platform, with its own GitHub org, ecosystem integration, and a name that reflects what it actually is — a pages rendering runtime, not a fork of something else.

## What changed

The rename touched 1154 files across a single squashed commit. The interesting parts:

**GWT removal.** The `core/` directory — 818 Java files, the Maven build, the Errai CDI container — moved to `_legacy/`. It's still there for reference, but it's no longer a workspace, no longer in the build, no longer wired into webpack. The `build:prod` script that used to chain `yarn build:core:prod &&` before everything else now just builds TypeScript. The CI workflow that ran `mvn clean install` on every push is deleted.

**Package naming.** Every `@melviz/*` and `@casehub/*` package got a `pages-` prefix: `@casehub/pages-data`, `@casehub/pages-ui`, `@casehub/pages-viz`, `@casehub/pages-runtime`. The standalone iframe components got a `pages-component-` prefix (`@casehub/pages-component-echarts`) to distinguish them from shared libraries in the package registry. The old `@melviz/component-api` — the postMessage bridge for iframe components — became `@casehub/pages-iframe-api`, because `pages-api` would have suggested the public API of the entire system.

**Wire protocol.** The `melviz-dataset` postMessage type became `casehub-pages-dataset`. The iframe component path changed from `/melviz/component/` to `/pages/component/`. No external consumers exist, so the breaking change cost nothing.

## Ecosystem integration

casehub-pages is the first non-Maven foundation module in the platform. The `build-all.sh` that orchestrates cross-repo builds now has a `yarn install && yarn build` step alongside the Maven reactor. PLATFORM.md documents the runtime consumption pattern — claudony, drafthouse, devtown, life, and aml embed pages dashboards via iframe at runtime, not as build-time dependencies. This is a different dependency model than the Java modules and needed its own section in the cross-repo dependency map.

The spec went through four review cycles before implementation. Each cycle caught things the previous one missed — the `build:components` glob that would have matched every package after the rename, the examples webpack aliases that nobody remembered existed, the `copy-melviz.js` script buried in examples/scripts/. The fourth cycle found the GitHub Actions workflows that would have broken the moment the history was pushed to the new repo.

I filed two follow-up issues: #36 for auditing and removing `_legacy/` once we're confident everything is ported, and #37 for creating a PLATFORM.md protocol for TypeScript module conventions so the next non-Maven foundation module has guidance.

The naming convention deviation is worth noting: Maven repos use short folder names (`api/`, `runtime/`) because each module lives in its own repo directory. A Yarn monorepo puts everything under `packages/`, where `data/` alone is meaningless. The `pages-` prefix serves the same disambiguation function that the parent directory does in Maven. I documented this as a deliberate deviation rather than leaving it as an unstated conflict with the existing protocol.