---
layout: post
title: "Expression Evaluation and the Parse-Resolve-Execute Lifecycle"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, type-safety, gwt-migration, jsonata, expression-evaluation]
---

## Expression Evaluation and the Parse-Resolve-Execute Lifecycle

The expression evaluator and DataSetLookup are simpler components than the grouping engine, but they surfaced a design question that restructured the type system: how do you represent a filter expression tree that goes through staged resolution, where the tree shape stays the same but the leaves change?

The JSONata bridge itself is thin — `compile()` returns a `CompiledExpression`, `compileOrCached()` adds a `Map<string, CompiledExpression>` cache, and the evaluator wraps it with null semantics and string coercion. JSONata v2 is unconditionally async (`evaluate()` always returns a Promise, even for `2 + 3`), which I accepted rather than fighting — a future sync library can slot in without changing the evaluator's public API since callers already `await`.

The binding convention was the session's most educational gotcha. JSONata's `evaluate(data, bindings)` takes two parameters. Data paths resolve without prefix (`value`). Bindings resolve with `$` prefix (`$value`). I initially passed the cell value as a binding, which meant every expression needed `$value * 100` instead of `value * 100`. No error, no warning — just undefined results everywhere. The fix is to pass value as data: `evaluate({ value: cellValue })`. Existing Java dashboards use plain `value` in expressions, so the binding approach would have silently broken every one of them.

The more interesting problem was DataSetLookup and how filter expressions move through the system. A YAML dashboard parser doesn't know column types — it produces filter expressions like `{ column: "price", function: "GREATER_THAN", args: ["100"] }` where the args are strings and the column type is unknown. The evaluation engine needs typed expressions — `{ type: "numeric", filter: { fn: "GREATER_THAN", value: 100 } }`. Somewhere between parsing and evaluation, the strings become numbers and the column type gets resolved. The naive approach: add an `"unresolved"` variant to `FilterExpression` and a runtime guard in `applyFilter` that throws if one slips through. That works, but the compiler can't catch the mistake — a developer building a new code path can skip the resolution step and get a runtime error instead of a compile error.

The solution is a parameterized tree type:

```typescript
type FilterExprTree<Leaf> =
  | Leaf
  | { type: "and"; children: readonly FilterExprTree<Leaf>[] }
  | { type: "or"; children: readonly FilterExprTree<Leaf>[] }
  | { type: "not"; child: FilterExprTree<Leaf> };
```

The tree structure is defined once. Leaves are parameterised — `ResolvedLeaf` (numeric, string, date) and `UnresolvedLeaf` (raw function name + string args). `ResolvedFilterExpression = FilterExprTree<ResolvedLeaf>`. `FilterExpression = FilterExprTree<ResolvedLeaf | UnresolvedLeaf>`. The YAML parser produces `FilterExpression`. The resolution step returns `ResolvedFilterExpression`. The evaluation engine only accepts `ResolvedFilterExpression`. Passing unresolved filters to `applyFilter` is a compile error.

This cascades: `ResolvedFilterOp`, `ResolvedDataSetOp`, and `applyOps` takes `readonly ResolvedDataSetOp[]`. The existing code was already constructing resolved leaves exclusively, so the migration was a type annotation change — the values didn't change, only their declared types. Breaking the existing signatures is the point: it forces every future caller to be explicit about resolution state.

DataSetLookup ended up as a two-field interface after the review stripped pagination and metadata. The Java `DataSetLookup.metadata` field has zero consumers — never serialised, never cloned, never compared, never read anywhere. Pagination is a presentation concern that belongs at the service layer, not in the query definition. What remains is `{ dataSetId, operations }` — "which dataset, what transformations." `createLookup()` validates `F*G*S?` ordering at construction, so invalid operation sequences fail at creation time regardless of whether the lookup came from YAML or code.

The YAML parser uses Zod schemas throughout, with filter nodes discriminated by key presence — `column`+`function` for leaf expressions, `and`/`or`/`not` for combinators, mutually exclusive. The parser produces unresolved filter leaves. `resolveFilterTypes()` walks the tree, looks up column types, parses string args to typed values, and rejects invalid combinations per a compatibility matrix (LIKE_TO only on TEXT/LABEL, TIME_FRAME only on DATE). The lifecycle is three stages: parse, resolve, execute, each with its own type signature. The compiler enforces that you can't skip the middle stage.

Along the way: DateFilter was missing `IN` and `NOT_IN` variants that NumericFilter and StringFilter both had. And `compareValues` in `group-eval.ts` used `localeCompare` for string comparison — locale-dependent — while `sort-eval.ts` correctly used `< >` operators for codepoint order. Both fixed.

406 tests across 14 files. The expression evaluator closes issue #4 and the DataSetLookup closes #5. DataSetManager — the service that owns lookup execution, filter resolution, and pagination — is the next layer up.
