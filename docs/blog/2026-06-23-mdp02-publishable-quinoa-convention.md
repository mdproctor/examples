---
layout: post
title: "Making casehub-pages Publishable"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [publishing, quinoa, github-packages, esbuild]
series: issue-26-quinoa-convention
---

*Part of a series on [#26 — Platform convention: Quarkus + TypeScript frontend via Quinoa](https://github.com/casehubio/casehub-pages/issues/26). Previous: [arc42-issue-remap](2026-06-23-mdp01-arc42-issue-remap.md).*

casehub-pages has been a monorepo that only works inside itself. The packages exist — runtime, ui, viz, component, data — but they're workspace-only at version 0.0.1, unpublished, and there's no documented way for a Quarkus host app to consume them. Today that changed.

## The scope problem nobody mentioned

I started with a straightforward plan: publish to GitHub Packages, document the Quinoa integration pattern, provide a template. The spec went through four rounds of review before it was clean, and the biggest blocker was the one I missed entirely in the first draft.

GitHub Packages requires the npm scope to match the org name. The org is `casehubio`. The packages use `@casehub`. These don't match, and no amount of `.npmrc` scope mapping fixes it — that's client-side routing, not server-side ownership. Every package in the monorepo had to be renamed from `@casehub/pages-*` to `@casehubio/pages-*`. That's 190 files touched for a mechanical find-and-replace.

The `@casehubio` scope is ugly. If the org is ever renamed to `casehuborg`, we can republish. But for now it's the only option.

## Who owns custom element registration?

The second design question that took multiple rounds to get right: when a host app calls `loadSite()`, it expects charts and tables to render. Those depend on Web Component custom elements being registered via `customElements.define()` calls in `pages-viz`. But `pages-runtime` — which exports `loadSite()` — didn't trigger the registration. Every consumer had to include a bare `import "@casehubio/pages-viz"` and know why.

That's a footgun, not an API. The fix was one line — add `import "@casehubio/pages-viz"` to `pages-runtime/src/index.ts`. Custom element registration becomes an internal concern. Consumers get a working `loadSite()` with two dependencies: `pages-runtime` and `pages-ui`.

Claude caught the related esbuild issue: without `"sideEffects": true` in `pages-viz`'s `package.json`, esbuild's tree-shaking silently drops the bare import. The charts render as empty containers with zero errors. It's the kind of failure that costs hours to diagnose because there's nothing visibly wrong — the DOM structure is correct, the element tags are right, but `customElements.define()` was never called.

## What shipped

The branch removed the redundant iframe ECharts component (#28) — `pages-viz` already provides 20+ inline chart types, making the iframe version pure dead weight. Then the real work: scope rename, publishing fields (`repository`, `publishConfig`, `sideEffects`, `"files": ["dist"]` for iframe components whose `dist/` is gitignored), version bump to 0.2.0, and a GitHub Actions workflow that publishes on push to main.

The convention doc at `docs/quinoa-convention.md` covers everything a host app needs: add the Quinoa extension, copy the template from `templates/quinoa-host/`, configure two properties, and run `mvn quarkus:dev`. Two npm dependencies. One esbuild config. Host apps that need iframe-isolated components (LLM prompter, SVG heatmap) opt in explicitly — they're not bundled by default.

The template's entry point is what I wanted from the start:

```ts
import { loadSite } from "@casehubio/pages-runtime";
import { page, table, dataset, lookup, groupBy, col, count } from "@casehubio/pages-ui";
```

No bare imports to remember. No internal package structure to know about. TypeScript catches composition errors at build time. Quinoa bundles it into the JAR.

Claudony, devtown, and drafthouse can start integrating now — each gets its own issue for the actual migration.