---
layout: post
title: "TypeScript quality sweep — three small wins"
date: 2026-06-21
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [typescript, project-references, eslint, branded-types]
series: issue-2-ts-quality-sweep
---

*Part of a series on [#2 — TypeScript project references](https://github.com/casehubio/casehub-pages/issues/2). Previous: [ts-strict-enforcement](2026-06-21-mdp01-ts-strict-enforcement.md).*

Three follow-up issues from the strict mode work, all on one branch.

## Closing the Component type hole

`Component<T, P>` had `P = Record<string, unknown>` without a constraint. That meant `Component<"foo", number>` compiled — props could be a primitive. The fix is `P extends object`, which accepts all named interfaces (the `extends Record<string, unknown>` alternative fails because named interfaces lack index signatures). One line, one `@ts-expect-error` test, done.

## Branded ID constructors

The codebase had ~400 `as DataSetId` and `as ColumnId` casts scattered across 16 files. Each was a bypass of the branded type system — correct in practice, but impossible to audit. I migrated them all to `dataSetId()` and `columnId()` constructor functions. Now there's exactly one `as` cast per type, in the constructor itself. Everything else flows through a function call.

The migration script handled the text replacement but mangled the imports — `verbatimModuleSyntax` makes value imports and type imports separate concerns, and a naive regex can't tell which is which. Claude's script appended `dataSetId` to an `import type` line, producing code that wouldn't compile. I ended up fixing the import resolution by hand for the edge cases. The lookup-parser had local variables named `dataSetId` and `columnId` that shadowed the newly-imported constructors — another thing the script couldn't anticipate.

## Project references — the interesting one

TypeScript project references promise incremental cross-package type checking. The monorepo was running `tsc --noEmit` per package — correct, but rebuilding everything from scratch each time. About 6.6 seconds. Not painful yet, but it scales linearly with package count.

The natural first attempt was `tsc --build --noEmit` with `composite: true`. It failed: "Referenced project may not disable emit." The `--build` flag needs declaration files from upstream packages to resolve types in downstream ones. `--noEmit` prevents that. The documentation doesn't make this constraint clear — `tsc --noEmit` works fine on a composite project individually; only `tsc --build --noEmit` fails.

The fix: `emitDeclarationOnly: true` with a `.typecheck/` outDir. Each package emits only `.d.ts` files to a gitignored cache directory — enough for cross-package resolution, no JS output. Build tsconfigs override with `emitDeclarationOnly: false` and `composite: false` so production builds are unchanged.

Second gotcha: `rootDir` in the base tsconfig resolves relative to the base config's directory, not the extending config's. I set `rootDir: "src"` in `packages/pages-tsconfig/tsconfig.json` and got 200+ "File is not under rootDir" errors — TypeScript was looking for source files under `packages/pages-tsconfig/src/`. The fix is putting `rootDir` in each package individually.

With both issues resolved, `yarn typecheck` now runs `tsc --build` at the root. Second-run typecheck: 0.8 seconds. The `.tsbuildinfo` cache skips unchanged packages entirely.

## ESLint strict-type-checked

Added `@typescript-eslint/strict-type-checked` — the strictest preset available. It flagged 2,311 errors across the monorepo. Test files accounted for most of them (non-null assertions are idiomatic with `noUncheckedIndexedAccess`). After relaxing `no-non-null-assertion` and the `unsafe-*` family for test files, and auto-fixing 149 errors, 653 remain. Filed as #6 for systematic cleanup.

The three TypeScript gotchas from the project references work went into the garden — they'll save time next time someone tries this in a monorepo.
