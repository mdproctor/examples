---
title: Three iterations to Maven
date: 2026-07-28
author: Mark Proctor
entry_type: note
subtype: diary
project: casehub-pages
tags: [maven, yarn, npm, monorepo, ci, dependency-management]
status: draft
---

# Three iterations to Maven

Cross-repo dependency management for casehub-pages has been through three approaches: published npm packages, local `file:` references, and now Maven SNAPSHOT. Each solved one problem while creating another. The third time might be the last.

## The problem

casehub-pages is a Yarn workspace monorepo — 13 publishable npm packages, consumed by 9 application repos. Every app builds its own frontend bundle from these packages. Getting them from source repo to consumer has been the recurring headache.

## Iteration 1: published npm packages

Standard approach. Publish `@casehubio/pages-*` to GitHub Packages npm, consumers install with version pins. It worked until it didn't — GitHub Packages npm requires authentication even for public packages. Every developer needed a token configured. Every CI needed explicit auth. Friction accumulated until we abandoned it.

## Iteration 2: local `file:` references

Direct path references to sibling repo checkouts. Zero auth, zero publishing ceremony, instant linking during development. The consumer repos had `"@casehubio/pages-data": "file:../../../../../pages/packages/pages-data"` — five levels of `../`.

Three different CI workarounds emerged across nine repos: openclaw used `sed` to rewrite `file:` to `*` before the build. Drafthouse checked out casehub-pages as a sibling and built it from source. AML had no workaround at all — its CI frontend build was effectively broken.

The root issue: `file:` references between Yarn workspace packages only resolve inside the workspace. When a consumer app installs `pages-runtime` via `file:`, it gets a `package.json` whose own dependencies point to `file:../pages-data` — relative paths that make no sense outside the workspace.

## Iteration 3: Maven SNAPSHOT

The conversation started with openclaw's CI being broken and ended with an ADR. The key insight was that Maven SNAPSHOT resolution is a 20-year-old mechanism for exactly this problem — always-latest versioning without manual bumps, works identically in local `.m2` and remote repositories.

But consumer apps don't just need a pre-built webapp JAR. They import individual packages — `pages-runtime`, `pages-data`, `blocks-ui-core` — and compile them into their own webpack bundle. A single `META-INF/resources/` JAR doesn't help.

The solution chains three mechanisms: `yarn pack` to produce distributable tarballs (which resolves `workspace:*` to real version numbers), Maven to transport and resolve them, and Yarn's `portal:` protocol with `resolutions` to redirect all `@casehubio` packages to the Maven-unpacked local files. No npm registry involved at any point.

I discovered along the way that `yarn pack` does *not* resolve `file:` protocol references — only `workspace:*` gets the special treatment. The inter-package refs in casehub-pages were all `file:`, a legacy from before workspace protocol was adopted. Migrating them was a prerequisite that wasn't in the original plan.

## What changed

Twelve repos touched in one session. casehub-pages and blocks-ui got `npm-packages/` Maven modules that pack and publish all their packages as a single Maven artifact. Nine consumer repos got `maven-dependency-plugin:unpack` configurations, `portal:` resolutions in their `package.json`, and simplified CI workflows. The three different CI workarounds — sed hacks, sibling checkouts, broken builds — are all gone, replaced by Maven dependency resolution.

The stale reference sweep afterwards caught documentation across five repos still describing the old approaches. The parent repo's `ui-architecture.md` now documents the three-tier consumption model, and five vestigial `.npmrc` files were removed.

The bet is that Maven SNAPSHOT resolution, being a mechanism that's survived 20 years of real-world use, won't need a fourth iteration. The WebJar pattern for frontend-in-Maven has been around since 2012. Nothing here is novel — just the composition of well-understood pieces applied to our specific cross-repo problem.
