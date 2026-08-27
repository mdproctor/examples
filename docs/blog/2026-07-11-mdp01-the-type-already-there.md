---
layout: post
title: "The Type That Was Already There"
date: 2026-07-11
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [typeddataset, table, type-safety, component-model]
---

The table had its own type system because the pipeline didn't give it one.

`ColumnDef<R>` — with `getValue: (row: R) => unknown`, `render: (value, row) => TemplateResult`,
`compare`, `sortable`, `filterable`, `width`, `align` — existed as a bundled contract that did
three jobs at once. Data extraction (what is the cell value?), cell rendering (how do you display
it?), and presentation config (how wide, is it sortable?). It existed because `DataReceiver.dataSet`
was typed `unknown`. The pipeline handled TypedDataSet internally — `DataSourceController` stored
it, the append/replace/remove handlers constructed it — but the interface-level declaration said
`unknown`. Everything downstream inherited the loss.

Fix the contract to `TypedDataSet | undefined` and the entire parallel type system becomes
redundant. The data extraction concern moves to `TypedRow.cell(columnId)` — cells are already
projected, with type discriminants (`NUMBER`, `TEXT`, `DATE`, `LABEL`, `NULL`) that the table can
dispatch on for default formatting. The rendering concern becomes `columnRenderers: ReadonlyMap<ColumnId, ColumnRenderer>` — a map from column ID to a function that receives the typed cell, the
full row, and the column metadata. The presentation concern becomes `TableColumnConfig` — label
overrides, width, sortability, alignment, custom comparators.

Three independently-varying concerns that ColumnDef had bundled into one. Data schema is set by the
pipeline. Rendering is set by the consumer. Presentation is set by the layout. They change for
different reasons and at different times.

The package rename from `pages-data-table` to `pages-table` looks cosmetic but isn't. The
activation system creates elements via `pages-${component.type}` — for `type: "table"`, it
creates `<pages-table>`. The old `PagesTable` in pages-viz was already removed. Nothing was
registered for that tag name. `document.createElement("pages-table")` produced a dead element.
The rename makes the Lit component register as `pages-table`, which is the first time activation
has had a working table element to resolve to.

The sort rewrite is worth noting for what it deletes. The old comparator took `ColumnDef` and
extracted values via `col.getValue(row)` — raw `unknown` values compared by dispatching on the
column's string type (`'text'`, `'number'`, `'date'`). The new comparator takes `TypedRow` objects
directly. It calls `row.cell(columnId)`, gets back a discriminated `CellValue`, and dispatches on
`cell.type` — an enum, not a string. NULL handling falls out naturally: `if (cellA.type === 'NULL')
return 1` is the entire nulls-last implementation.

For in-memory domain data that doesn't come through the pipeline, `fromRows<R>()` absorbs what
`getValue` used to do — but at construction time, not render time. You call
`fromRows(capabilities, [{ id: columnId("tag"), type: TEXT, getValue: c => c.tag }])` and get back
a TypedDataSet with proper TypedRow instances. Domain-typed extractors run once. The table receives
the same uniform input regardless of whether data came from a REST endpoint, a WebSocket push, or
an in-memory array.

The three-concern split has a quiet consequence for the blocks-ui consumer migration that
follows. Every component that currently builds a `ColumnDef[]` array and passes raw objects as
`rows` will instead declare its columns in `DataSourceControllerOptions`, let the pipeline
produce TypedDataSet, and pass `columnRenderers` only for cells that need custom rendering.
The common case — column name as header, cell value formatted by type — needs zero configuration.
