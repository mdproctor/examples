---
layout: post
title: "Melviz Gets a Type System for Filters"
date: 2026-06-10
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, type-safety, gwt-migration]
---

## Melviz Gets a Type System for Filters

The GWT-to-TypeScript migration spec (written yesterday) described 13 filter operations, recursive logical composition, and a TimeFrame parser. Today I turned that spec into running code.

The interesting decision was how far to push the type system. The Java model is flat — `CoreFunctionFilter` carries a `List<Comparable>` and a `CoreFunctionType` enum. You can construct `EQUALS_TO` with a string value against a NUMBER column and nothing catches it until runtime. The TypeScript version uses Level 2 discriminated unions: `FilterExpression` leaf nodes carry a column-type discriminant (`"numeric"` / `"string"` / `"date"`) that pairs with the correct filter type. A `NumericFilter` on a string column is a compile error. The YAML parser will be the construction site where mismatches would otherwise occur — making them type errors means the parser has to prove it got it right.

This came out of three rounds of review. The first draft had a shared `EqualityFilter` with `string | number | Date` as the value type, which defeated the column-type enforcement the rest of the design was built around. Splitting equality per column type and then moving the column type into the FilterExpression discriminant made the whole thing honest.

The TimeFrame parser was the piece I expected to find a library for. The Melviz syntax (`begin[year March] -1year till now`) is bespoke — not Elasticsearch date math, not chrono-node natural language, not anything on npm. It's about 250 lines of parsing and UTC date resolution, with a clean separation: `parseTimeFrame` returns a pure AST with no date computation, `resolveTimeFrame` does the arithmetic with an injected reference date. That separation means the parser is deterministic and testable — no `Date.now()` calls anywhere.

Five null-handling bugs from the Java code got fixed. The Java `isNotEqualsTo(null)` returns `true` because it's `!isEqualsTo(null)` = `!false` = `true`. Same pattern in `LOWER_THAN`, `LOWER_OR_EQUALS_TO`, and `NOT_IN`. The TypeScript version adopts SQL NULL semantics — any comparison with NULL returns false. There's also a `compareTo() == 1` bug in the Java `isGreaterThan` — `String.compareTo()` returns the difference of character values, not specifically 1, so `"z".compareTo("a")` returns 25 and the `== 1` check fails. The fix is using `>` directly.

The LIKE_TO regex compiler ended up more careful than the Java version in two ways: it tracks bracket depth so `%` and `_` inside `[charlist]` expressions aren't replaced, and it anchors with `^...$` because JS `RegExp.test()` matches substrings where Java `String.matches()` requires a full match.

95 tests across three test files. The filter evaluation tests cover all 13 operations, null semantics for each, LIKE_TO bracket and anchoring edge cases, TIME_FRAME with pre-resolved dates, and logical composition including nested NOT(AND(...)), empty AND/OR semantics, and mixed column types in OR.

One deliberate divergence from Java: `Date.setUTCMonth` on January 31 with +1 month overflows to March 3, while Java's `Calendar.add(MONTH, 1)` clamps to February 28. I documented it rather than fixing it — the clamping logic is straightforward but the overflow matches what JS developers expect from the Date API.
