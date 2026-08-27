# Selection Context Bridge — RuntimeContext Integration

**Issue:** casehubio/casehub-pages#298 (part of epic #289)
**Date:** 2026-08-10
**Scope:** #298 only — selection-driven dataset builders (#299) and host-panel forwarding (#300) are separate issues

## Problem

`PagesDataTable` emits `selection-change` events. `ContextManager` resolves `#{var}` templates in dataset URLs. Nothing bridges the two — no code captures a table's selection and writes it into `RuntimeContext` so that detail datasets with parameterised URLs auto-refetch.

Five casehub-clinical master-detail patterns (AE → grade history/precedents/regrade, deviations → precedents/PI responses) are blocked by this gap.

## Relationship to Existing Record-Selection

The `pages-filter` handler in `site.ts` already has a record-selection path (lines 529-596): when a clicked row contains a child DataScope's `idColumn`, it sets a cross-filter on the child scope's data. This is a **local filtering** mechanism — the child page shares the parent's data source and filters it by selected ID.

The new `selection` mechanism is a **parameterised URL** mechanism — detail datasets have template URLs (`#{selection.adverseEvents.id}`) that resolve and trigger server-side fetches from different API endpoints. Both are needed for different use cases. This design does not modify or replace the existing path.

## Design

### 1. RuntimeContext Extension

Add `selection` field to `RuntimeContext` in `packages/pages-component/src/context/types.ts`:

```typescript
export interface RuntimeContext {
  readonly filter: Record<string, readonly string[]>;
  readonly datasets: Record<string, DataSetSnapshot>;
  readonly page: { readonly name: string; readonly path: string };
  readonly params: Record<string, string>;
  readonly row?: Record<string, unknown>;
  readonly selection: Readonly<Record<string, Record<string, unknown>>>;
}
```

- Keyed by dataset ID → selected row's field values
- `EMPTY_CONTEXT` gets `selection: {}`
- Template resolution walks dot paths: `#{selection.adverseEvents.id}` resolves by accessing `context.selection.adverseEvents.id`

**`row?` vs `selection`:** `row` is per-row rendering context set during cell expression evaluation (conditional formatting, in-cell expressions). `selection` is user-selected master row state for driving parameterised detail dataset URLs. Different concepts, different lifecycles.

### 2. ContextManager.updateSelection()

Add to `ContextManager` in `packages/pages-runtime/src/context-wiring.ts`:

```typescript
updateSelection(datasetId: string, row: Record<string, unknown> | null): void
```

- `row` non-null: sets `context.selection[datasetId]` to the row data
- `row` null: deletes `context.selection[datasetId]`
- Calls `evaluateAll()` after updating — triggers all ContextConsumers to re-resolve their templates

### 3. Event Listener in site.ts

Add a `selection-change` listener in `site.ts` alongside the existing framework event listeners, following the same pattern as `pages-filter`, `pages-sort`, etc.

On `selection-change` event:
1. Call `findComponentId(e)` to get the component ID from the DOM
2. Look up `registry.get(componentId)` to get the `ComponentEntry`
3. Extract `entry.originalLookup.dataSetId` as the selection key
4. Read `detail.selectedRows[0]` for single-row selection, or null if `selectedRows` is empty
5. Convert `TypedRow` to `Record<string, unknown>` (extract cell values by column)
6. Call `contextManager.updateSelection(datasetId, rowData)`

If `componentId` is not found or `originalLookup` is missing, ignore the event silently (table is not pipeline-managed).

### 4. Deferred Fetch (No New Code)

When no selection exists for a dataset, `#{selection.myDataset.id}` resolves to `""` via the existing `resolveTemplate` dot-path walker. `allTemplateVarsResolved()` returns `false`, and the `ContextConsumer` apply callback short-circuits (line 627-629 in data-pipeline.ts). Detail datasets don't fetch until a selection exists.

When a selection is made, `evaluateAll()` re-runs all consumers. The template now resolves to a real value, `allTemplateVarsResolved()` returns `true`, and the consumer proceeds to fetch the detail dataset with the resolved URL.

### 5. Selection Clearing

**On dataset refresh:** When `pushData()` delivers new data for a master dataset (whether from server re-fetch or push update), clear any selection for that dataset ID by calling `contextManager.updateSelection(datasetId, null)`. This happens in the data delivery path in `site.ts` — the same place that calls `contextManager.updateDataset()`.

**On deselect:** When `selection-change` fires with empty `selectedRows`, the listener calls `updateSelection(datasetId, null)` — clearing naturally.

**On page navigation:** When the page changes (via `updatePage()`), clear all selection state: `context.selection = {}`.

### 6. TypedRow → Record Conversion

`SelectionChangeDetail.selectedRows` contains `TypedRow` objects (from pages-data). These need conversion to plain `Record<string, unknown>` for RuntimeContext. Extract cell values by iterating the row's columns:

```typescript
function typedRowToRecord(row: TypedRow): Record<string, unknown> {
  const record: Record<string, unknown> = {};
  for (const col of row.columns) {
    const cell = row.cell(col.id);
    record[col.id as string] = cell.type === 'NULL' ? null : cell.value;
  }
  return record;
}
```

## Scope Boundaries

**In scope (#298):**
- RuntimeContext.selection field
- ContextManager.updateSelection()
- selection-change listener in site.ts
- Selection clearing on refresh/deselect/page nav
- Unit tests for all of the above

**Out of scope:**
- YAML `selectionSource` desugaring → #299
- `detailDataset()` builder API → #299
- host-panel `pages-selection-changed` event forwarding → #300
- Multi-row selection — explicitly scoped out per issue #298
- Re-selection-by-key after dataset refresh — future enhancement
- Per-page-path selection scoping — future enhancement if needed

## Files Modified

| File | Change |
|------|--------|
| `packages/pages-component/src/context/types.ts` | Add `selection` to `RuntimeContext`, update `EMPTY_CONTEXT` |
| `packages/pages-runtime/src/context-wiring.ts` | Add `updateSelection()` to `ContextManager` |
| `packages/pages-runtime/src/site.ts` | Add `selection-change` event listener, clear selection on page nav and data refresh |
| `docs/protocols/casehub/pages-event-contract.md` | Add `selection-change` to reserved framework event names table |

## Testing Strategy

- **Unit:** `updateSelection` set/clear, template resolution for `#{selection.x.y}`, `allTemplateVarsResolved` with selection templates, TypedRow→Record conversion
- **Integration:** selection-change event → RuntimeContext update → ContextConsumer re-evaluation → resolved URL change
- **Edge cases:** no component ID found (silent ignore), empty selectedRows (clear), page nav clears selection, dataset refresh clears selection, deselect clears and detail dataset stops fetching
