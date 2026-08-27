---
title: "TypeScript Strict Enforcement — Maximum Type Safety Across All Packages"
date: 2026-06-21
author: Mark Proctor
tags: [typescript, type-safety, architecture, casehub-pages]
---

# TypeScript Strict Enforcement

Enforced maximum TypeScript strict mode across all 12 packages in the casehub-pages
monorepo. The work went deeper than tsconfig flags — it restructured the component
type system to eliminate type erasure at package boundaries.

## The Problem

The monorepo had two tiers of TypeScript strictness. Five core packages used `strict: true`
with extra flags. Five legacy packages inherited a weaker base with only three strict
flags. No monorepo-wide type check existed, and webpack's `transpileOnly: true` made
type errors in iframe components invisible to CI. All test files were excluded from
`tsc --noEmit`, meaning the 119 `as any` casts lived in files no type checker ever saw.

More fundamentally, `Component.props: Record<string, unknown>` erased all type information
at the component boundary. Builders cast typed props DOWN to `Record<string, unknown>`,
tests cast back UP via `as any`. Both directions were symptoms of the same root cause.

## The Architecture Fix

The component model layer was incomplete. `pages-component` owned the `Component` interface
and layout props but data component props (`BarChartProps`, `TableProps`) and form input
props lived in `pages-ui` (the parser). This forced `pages-viz` (the renderer) to depend
on `pages-ui` — the renderer depended on the parser just to access type definitions.

We moved all props types to `pages-component`, completing it as the component model layer.
`Component` became generic: `Component<T extends string, P>` with a `ComponentTypeRegistry`
mapping type strings to their props interfaces. Type guards narrow both the `type` field
(to a string literal) and `props` (to the mapped type) simultaneously.

The existing `ComponentTypeRegistry` and `getProps()` infrastructure was already half-built —
a base registry in pages-component, an extended one in pages-ui with a cast-widened re-export.
Unifying eliminated the cast and the split.

Result: `pages-viz` no longer depends on `pages-ui`. Both independently depend on
`pages-component` (the component model) and `pages-data` (the data model). Clean
Layer 0 → Layer 1 architecture.

## What Changed

- **Generic Component<T, P>** with unified `ComponentTypeRegistry` (37 component types)
- **All DSL builders** return `TypedComponent<T>` — 22 production `as unknown as` casts eliminated
- **All 121 test `as any` casts** eliminated via branded constructors, type guards, `getProps()` narrowing, `HTMLElementTagNameMap` declarations
- **Single authoritative base tsconfig** — `strict: true`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax`, `noUncheckedIndexedAccess`, plus 6 more flags
- **tsconfig split**: `tsconfig.json` (includes tests) + `tsconfig.build.json` (excludes tests)
- **Root `yarn typecheck`** runs across all packages, added to CI
- **Legacy TS 4.6.2 → 5.6.0** for 5 packages
- **Gallery fixes**: kitchen sink cleaned up, dead URLs replaced, mock data corrected

## Issues Filed

- [#2](https://github.com/casehubio/casehub-pages/issues/2) — TypeScript project references (incremental cross-package checking)
- [#3](https://github.com/casehubio/casehub-pages/issues/3) — ESLint strict type-checked rules
- [#4](https://github.com/casehubio/casehub-pages/issues/4) — Post-strict follow-ups (P constraint, internal DataSetId casts, test narrowing pattern)
- [#5](https://github.com/casehubio/casehub-pages/issues/5) — Navigation components need visually distinct rendering
