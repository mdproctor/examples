---
layout: post
title: "DataSetManager and the Thin Orchestration Layer"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, gwt-migration, service-layer, type-safety]
---

## DataSetManager and the Thin Orchestration Layer

The DataSetManager is the first component in the TypeScript core that doesn't compute anything itself. Every piece it touches already exists: `resolveFilterTypes` turns unresolved filter leaves into typed ones, `applyOps` runs the filter→group→sort pipeline, and `DataSetLookup` defines the query. The manager's job is connecting them — own a registry, wire the pipeline, slice the result.

I expected this to be a straightforward assembly exercise, and it mostly was. The interesting parts were in the boundaries, not the interior.

The first question was where pagination belongs. The Java `DataSetLookup` carries `rowOffset` and `rowCount` directly on the query object. When I designed the TypeScript `DataSetLookup` in issue #5, I deliberately stripped pagination out — it's a presentation concern, not query semantics. A lookup says "what transformations"; pagination says "how much of the result do I want back." Those are different callers with different reasons to change. The manager closes this gap: `LookupOptions` carries `rowOffset`, `rowCount`, and `referenceDate` as execution-time parameters, separate from the query definition.

The `referenceDate` threading was the session's real discovery. `applyFilter` already accepts an optional `referenceDate` for TIME_FRAME evaluation — but `applyOps` never forwards it. Every TIME_FRAME filter going through `applyOps` silently evaluates against `new Date()`, which makes tests non-deterministic and means the manager can't control when "now" is. The fix is small — `ApplyOpsOptions` with a `referenceDate` field, threaded through to `applyFilter` — but it closes a gap that's been there since issue #2. The type of gap that doesn't cause bugs in production (dashboards want "now") but makes testing unreliable.

The negative offset bug was caught in code review. `Math.min(-3, 5)` returns `-3`, and `Array.slice(-3)` counts from the end of the array. A caller passing `rowOffset: -1` would silently get the last row instead of an error. The Java `DataSetLookup.setRowOffset` throws on negative values — we match that precedent. Validate at the entry point, not inside the private `paginate` function.

TypeScript's `exactOptionalPropertyTypes` flag caught me at the build step. When an interface declares `referenceDate?: Date`, the property must be *absent* or `Date` — not `undefined`. Passing `{ referenceDate: options?.referenceDate }` creates a property with value `undefined`, which fails compilation. The fix is to conditionally construct the object: only include the property when the value is defined. The error message doesn't mention `exactOptionalPropertyTypes`, so the diagnosis isn't obvious.

The manager ended up at about 60 lines of implementation — registry as a `Map`, pipeline as five steps (validate → resolve dataset → resolve filters → execute ops → paginate), factory function as the public construction path. 26 tests cover registry CRUD, the full lookup pipeline with each operation type, pagination edge cases (including `rowCount: 0` and offset beyond dataset length), and every error path. The implementation class isn't exported — consumers see only the `DataSetManager` interface and `createDataSetManager()`.

The pattern emerging across these issues is that each layer does exactly one job. `DataSetLookup` defines queries. `resolveFilterTypes` resolves the type gap between parse time and execution time. `applyOps` executes. `DataSetManager` orchestrates. None of them reaches into another's concerns. When the `referenceDate` gap was found, the fix was adding a parameter to `applyOps` — not working around it in the manager.
