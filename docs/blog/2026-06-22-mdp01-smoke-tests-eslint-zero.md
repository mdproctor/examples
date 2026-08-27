---
layout: post
title: "CaseHub Pages — Smoke Tests and the ESLint Zero"
date: 2026-06-22
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [testing, eslint, typescript, type-safety]
---

# CaseHub Pages — Smoke Tests and the ESLint Zero

**Date:** 2026-06-22
**Type:** phase-update

---

## What I was trying to achieve: clean up trailing items, then close the ESLint debt

Three things had been sitting in the backlog since the previous branch closed. A dead test for a SCREEN component that was never implemented. A missing `.gitignore` for Playwright screenshots that had accumulated in the repo. And the big one: 700 ESLint `strict-type-checked` violations across 11 packages.

The plan was sequential — clear the small items, add gallery smoke tests, then tackle the ESLint sweep. All on one branch.

## What we believed going in: ESLint auto-fix would handle more than it did

I expected auto-fix to knock off maybe 50–100 violations. It got 11. The rest were all manual: null assertions that needed guard clauses, `any` types that needed proper interfaces, `async` functions that never awaited anything, template expressions wrapping non-string values. Every fix was individually trivial, but 700 of them across 131 files is a different kind of problem.

## The smoke tests came first — and they worked immediately

Before touching ESLint, I wanted the gallery covered. The existing tests were hand-written per dashboard — 16 out of 33 had coverage. The approach was data-driven: read `samples.json`, iterate every dashboard, click it in the gallery, check that it loads without error divs and that no component reports ERROR status.

All 33 dashboards pass. The gallery's mock fetch fallback means even Prometheus and Micrometer dashboards render with mock data — no external infrastructure needed. Prometheus Basic is annotated as a known failure (API response format not supported, tracked in #35). Kitchensink has known partial issues with iframe-plugin and map components. Everything else renders clean.

## 700 to zero — the ESLint sweep

The distribution told the story: `no-non-null-assertion` alone was 151 violations. `no-unused-vars` was 104. `restrict-template-expressions` was 76. The top five rules accounted for 80% of the errors.

I fanned out five parallel agents, one per package cluster. Each agent worked independently on non-overlapping files: pages-data (199 errors), pages-runtime (138), pages-viz (102), pages-ui (57), and the remaining seven smaller packages (168). All five converged to zero in their scope. The 14 stragglers were in a single test file (`pages-iframe-api/tests/api.test.ts`) that hadn't been included in any tsconfig project.

The most interesting pattern was in `site.ts` — the largest single file (57 errors). The runtime agent created nine typed interfaces for CustomEvent detail payloads (`DataRequestDetail`, `SlotChangeDetail`, `FilterDetail`, etc.), replacing the old pattern of `(e as CustomEvent).detail.field as VizTarget` with `(e as CustomEvent<DataRequestDetail>).detail`. Compile-time field checking instead of runtime casts.

## The ESLint/TSC type resolution conflict

The one genuinely surprising thing. ESLint's `projectService` and TypeScript's `--build` mode resolve `querySelector()` return types differently. ESLint sees `HTMLElement | null`; TSC `--build` with project references sees `Element | null`. So ESLint's auto-fix removes `as HTMLElement` (unnecessary from its perspective), and then `tsc --build` fails (required from its perspective).

The workaround is to relax `no-unnecessary-type-assertion` in the test file ESLint override. But the workaround also silences legitimate unnecessary assertions. I filed #9 to investigate aligning the two tools' type resolution so the relaxation can be removed.

This went into the garden as GE-20260622-549a11 — it's the kind of thing you'd lose a couple of hours to before realising the two tools disagree at a layer most developers never think about.
