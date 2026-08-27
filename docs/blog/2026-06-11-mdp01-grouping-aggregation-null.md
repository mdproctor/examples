---
layout: post
title: "Grouping, Aggregation, and the Null Question"
date: 2026-06-11
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, type-safety, gwt-migration, aggregation]
---

## Grouping, Aggregation, and the Null Question

The filter model established the pattern: discriminated unions, type-level enforcement, SQL null semantics. Now the grouping and aggregation layer extends it into harder territory — splitting datasets into buckets, computing summary values per bucket, and handling the cases where every value in a bucket is null.

The type safety question from the filter work carried over but with different trade-offs. Filters split neatly by column type — `NumericFilter`, `StringFilter`, `DateFilter` — because the filter operations genuinely differ per type (dates have `TIME_FRAME`, strings have `LIKE_TO`). Aggregation doesn't split that cleanly. COUNT, DISTINCT, MIN, MAX, and JOIN all work on any column type. Only SUM, AVERAGE, and MEDIAN require numbers. The split that emerged is `NumericAggregation` vs `UniversalAggregation` — two variants in a union, where the type system prevents you from applying SUM to a TEXT column but doesn't restrict COUNT from anything. Less granular than filters, but honest about where the real constraint boundary is.

The Java code has MIN and MAX in `_numericOnly`, which means you can't find the earliest date or the lexicographic minimum of a label column. That's a bug, not a feature. In the TypeScript model, MIN and MAX are universal — they compare by the natural ordering of whatever type they receive.

The more interesting design problem was grouping strategy. Java's `GroupStrategy` enum has FIXED, DYNAMIC, and CUSTOM (which is dead code — `isColumnTypeSupported()` returns false for everything, and no `IntervalBuilder` is registered for it). DYNAMIC does different things depending on column type: label columns get distinct-value bucketing, date columns get auto-sized date ranges, and number columns... also get distinct-value bucketing. `ClientIntervalBuilderLocator` routes NUMBER to `IntervalBuilderDynamicLabel` unconditionally. There's no numeric range binning anywhere in the Java codebase.

I initially designed `dynamic` to resolve NUMBER to `dynamicRange` with equal-width bins — which would be the right thing for continuous data like temperatures or prices. But that's wrong for categorical numbers like HTTP status codes or priority levels. The engine can't know which case it has. So `dynamic` resolves to `distinct` for numbers, matching Java, and `dynamicRange` on NUMBER is a new capability that must be explicitly requested. The distinction matters because dashboard YAML files in the wild specify `strategy: DYNAMIC` and expect status-code-style grouping.

The YAML parser question pushed me toward a fourth strategy mode. The spec originally said the parser doesn't know column types at parse time, then the YAML mapping table showed `DYNAMIC` resolving to `distinct` or `dynamicRange` based on column type — a direct contradiction. The fix: a `dynamic` mode that the parser produces as-is, which the engine resolves at execution time when the actual column type is known. Same principle as the TimeFrame parser — produce a pure AST, resolve later.

The null handling was where I found the most Java bugs. `AverageFunction` divides by `rows.size()` — total rows including nulls — instead of the count of non-null values. Five rows, all null: Java computes 0/5 = 0. SQL AVG returns NULL. The principled rule that fell out: functions with identity elements (SUM→0, COUNT→0, JOIN→"") return them for empty or all-null input. Functions without identity elements (AVERAGE, MEDIAN, MIN, MAX) return NULL — the result is genuinely undefined. The average of nothing is not zero; it's undefined.

Java's `AbstractFunction.round(value, 2)` also rounds MIN and MAX results to 2 decimal places. The minimum of `[1.005, 2.003]` becomes `1.01` — a value that doesn't exist in the input. Rounding is a display concern. The aggregation engine computes exact IEEE 754 results.

The engine architecture has one structural wrinkle: consecutive GroupOps can't be processed as a simple sequential reduce. When `join: true` creates nested grouping (group by region, then within each region group by product), the second GroupOp needs the original dataset's columns — not the first GroupOp's materialised 2-row output. `applyGroupSequence` maintains row partitions against the original dataset throughout the chain and materialises once after the final GroupOp. This matches what the Java `SharedDataSetOpEngine` does with its `InternalContext`, but expressed as a pure function instead of 719 lines of mutable state with nested inner classes.

The pipeline is Filter→Group→Sort, with the engine enforcing `F*G*S?` ordering. 550 tests across the core package. The date arithmetic — `truncateToInterval` and `advanceByInterval` — lives in a canonical `date-interval.ts` module that `timeframe.ts` now imports from, eliminating the duplication where both would have implemented calendar truncation independently.
