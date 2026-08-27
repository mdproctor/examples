---
layout: post
title: "CaseHub Pages — The Last Rename"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [rename, api, migration]
---

# CaseHub Pages — The Last Rename

**Date:** 2026-06-29
**Type:** phase-update

---

## What I was trying to achieve: complete the identity rename from Casehub to Pages

The melviz → casehub-pages migration (#24) landed the package names, folder structure, repo move, and ecosystem integration weeks ago. But every class was still `CasehubBarChart`, every custom element tag `<casehub-bar-chart>`, every CSS variable `--casehub-bg`. Issue #55 covers the final surface: rename the `Casehub*` prefix to `Pages*` across the entire public API.

## Six surfaces, one principle

The scope breaks down cleanly into six rename surfaces. The issue comment I'd written for the clinical team turns out to be the implementation checklist:

| Surface | Example old → new | Count |
|---------|------------------|-------|
| Base/component classes | `CasehubElement` → `PagesElement` | 22 classes |
| Custom element tags | `<casehub-bar-chart>` → `<pages-bar-chart>` | 19 tags |
| Events | `casehub-filter` → `pages-filter` | 11 event names |
| CSS custom properties | `--casehub-bg` → `--pages-bg` | 13 variables |
| CSS classes | `.casehub-sidebar` → `.pages-sidebar` | 11 classes |
| Type names | `CasehubFilterApply` → `PagesFilterApply` | 6 types |

The principle: every user-facing string that said "casehub" should say "pages" — because the packages are `@casehubio/pages-*`, not `@casehubio/casehub-*`. The `casehub` prefix was an artefact of the intermediate migration step.

## The substring trap

A Python script handled the bulk rename — 37 file renames via `git mv` and text replacement across 75 files. The first pass caught most references. The second pass caught event names the first missed (`casehub-text-filter`, `casehub-save-error`, `casehub-record-navigate`).

The third pass was fixing the script's own damage. The replacement `casehub-page` → `pages-page` matched as a substring of `casehub-pages`, turning every occurrence of the project name into `pages-pages`. README, ARC42STORIES, CLAUDE.md, docs — all corrupted. The wire protocol type `casehub-pages-dataset` became `pages-pages-dataset`.

The fix was straightforward — replace `pages-pages` back to `casehub-pages` in every doc file, then fix the wire protocol string individually. But it's a reminder that ordered substring replacement without word-boundary guards will find the one collision you didn't think of.

## What it is now

75 files changed, 1,106 insertions, 1,106 deletions — perfectly symmetric, as a rename should be. Build passes, typecheck clean, 1,510 tests pass. The two pre-existing test failures (PagesMap mock gap, deleted Kitchensink fixture) are unchanged.

Consumer repos will need a mechanical find-replace on their event listeners, CSS custom properties, and DOM queries when they upgrade. The builder API (`page()`, `barChart()`, `loadSite()`) is untouched — no prefix, no rename needed.
