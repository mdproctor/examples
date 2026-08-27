---
layout: post
title: "Phantom Imports and Redundant Parameters"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [datasource, migration, api-design]
series: issue-143-example-datasource-migration
---

The examples in casehub-pages had been lying for a while. Forty-three TypeScript files importing from `@casehubio/ui` and `@casehubio/data` — packages that don't exist. They were never compiled, never type-checked, and the functions they imported (`inlineDataset`, `dataset`, `groupOp`) had either been removed from exports or never existed as callable functions. The examples were documentation that documented the wrong API.

Phase 8 of the DataSource migration (#143) was supposed to be mechanical: swap `inlineDataset()` for `bind()` + `inlineSource()`, swap `dataset()` for `bind()` + `restSource()`. But tracing the imports back to their roots surfaced two design problems worth fixing first.

The first was `restSource`. Its signature was `restSource(url, dataSetId, options?)` — taking a dataset id that `bind()` already provides. I followed the `dataSetId` through the implementation: it went into `buildDef()` to construct an `ExternalDataSetDef`, which was passed to `extractDataSet()`. But `extractDataSet` accepts `ExtractionDef`, not `ExternalDataSetDef` — and `ExtractionDef` has no `uuid` field. The parameter was dead on arrival. Removed it, changed `buildDef()` to return `ExtractionDef` directly.

The same parameter exists in `sseSource` and `wsSource`, but there it's not redundant — push sources use it as the subscription topic for `subscribe`/`unsubscribe`. Different concern, same name.

The second problem was the phantom imports themselves. `@casehubio/ui` and `@casehubio/data` were shorthand names that never resolved to real packages. The examples also used `groupOp`, `sortOp`, `filterOp` as functions — but these only exist as type interfaces. Meanwhile, pages-ui already exports a full DSL with `lookup`, `groupBy`, `col`, `sortBy`, `filterBy` that does exactly what the phantom functions were supposed to do.

The migration ended up touching 48 files. The examples now import from real packages (`@casehubio/pages-ui`, `@casehubio/pages-data`), use the real DSL, and the data flows through `bind()` into `page()` via the `datasets` option. Net result: 413 fewer lines, mostly from removing `JSON.stringify` wrappers and collapsing the old verbose lookup syntax.

The examples still aren't type-checked. About half of them use a legacy `page()` calling convention — objects and arrays as positional arguments — that doesn't match the current `page(name, ...components, options?)` signature. Making them type-correct requires restructuring those calls, which is Phase 11 cleanup work. Filed as #179.
