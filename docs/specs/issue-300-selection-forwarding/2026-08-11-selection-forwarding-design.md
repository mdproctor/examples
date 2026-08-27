# Custom Component Selection Forwarding via Host-Panel

**Issue:** casehubio/casehub-pages#300 (part of epic #289)
**Date:** 2026-08-11
**Scope:** #300 only — selection context bridge (#298, done) and dataset builders (#299, done) are separate issues

## Problem

Custom Web Components hosted in `host-panel` manage their own data fetching and rendering. When a master table row is selected, these components need to react — but they have no access to the internal `RuntimeContext` API. There's no event surface for forwarding selection context to hosted components.

## Design

### 1. Type Change — HostPanelProps

Add `selectionSource` to `HostPanelProps` in `packages/pages-component/src/model/component-props.ts`:

```typescript
export interface HostPanelProps {
  readonly typeName: string;
  readonly panelProps?: Readonly<Record<string, unknown>>;
  readonly lookup?: DataSetLookup;
  readonly selectionSource?: string;
}
```

`selectionSource` declares which master dataset's selection this panel reacts to. Uses the same field name as `ExternalDataSetDef.selectionSource` (from #299) for consistent vocabulary across the platform.

### 2. Event Dispatch — site.ts

After `contextManager.updateSelection(datasetId, rowData)` in the existing `selection-change` listener in `site.ts`, dispatch `pages-selection-changed` on matching host-panel elements.

The dispatch logic:

```typescript
function dispatchSelectionToHostPanels(
  registry: ComponentRegistry,
  sourceDatasetId: string,
  selectedRow: Record<string, unknown> | null,
): void {
  for (const [, entry] of registry) {
    if (entry.component.type !== "host-panel") continue;
    const props = entry.component.props as HostPanelProps | undefined;
    if (props?.selectionSource !== sourceDatasetId) continue;
    entry.element.dispatchEvent(new CustomEvent("pages-selection-changed", {
      bubbles: true,
      composed: true,
      detail: { sourceDatasetId, selectedRow },
    }));
  }
}
```

**Call sites in site.ts:**

1. **After `updateSelection(datasetId, rowData)`** in the `selection-change` handler (~line 686): dispatch with the selected row data
2. **After `updateSelection(datasetId, null)`** in the `selection-change` handler (~line 688): dispatch with `null` for deselection
3. **After `clearAllSelections()`** in the page navigation handler (~line 183): dispatch `selectedRow: null` for every host-panel that has any `selectionSource` set
4. **After `updateSelection(datasetId, null)`** in the data refresh path: dispatch with `null` when master dataset refreshes

**No initial mount event:** When a host-panel is activated and no selection exists, no event is dispatched. The component starts in its "no selection" state. The first event fires only when a user selects a row.

### 3. Event Contract

Event name: `pages-selection-changed`

```typescript
interface SelectionChangedDetail {
  readonly sourceDatasetId: string;
  readonly selectedRow: Record<string, unknown> | null;
}
```

- `sourceDatasetId` — the dataset ID whose selection changed (matches the panel's `selectionSource`)
- `selectedRow` — the selected row's field values (same format as `RuntimeContext.selection[id]`), or `null` for deselection

Add to the reserved events table in `docs/protocols/casehub/pages-event-contract.md`:

```
| `pages-selection-changed` | Selection context forwarded to host-panel | Runtime selection bridge (site.ts) |
```

### 4. YAML Surface

Host-panel in YAML gains `selectionSource`:

```yaml
components:
  - host-panel:
      type: cbr-precedents-panel
      selectionSource: adverse-events
      props:
        endpoint: /api/cbr
```

No parser changes needed — `selectionSource` passes through the component desugaring as a prop on the `host-panel` component. The activation code in `activation.ts` reads it from `HostPanelProps`.

### 5. Runtime Behaviour (No Changes to ContextManager)

The dispatch uses the existing `ContextManager.updateSelection()` flow — no changes to ContextManager itself. The dispatch function reads from the ComponentRegistry (already available in site.ts) and dispatches DOM events. The selection data (`rowData`) is already computed by the selection-change handler.

## Scope Boundaries

**In scope (#300):**
- `selectionSource?: string` on `HostPanelProps`
- `dispatchSelectionToHostPanels()` function in site.ts
- Call sites: selection-change handler, page nav clear, data refresh clear
- `pages-selection-changed` in event contract protocol
- Unit tests for dispatch logic
- Integration test: host-panel receives event after selection change

**Out of scope:**
- iframe-isolated component forwarding via postMessage — future enhancement per issue notes
- ContextManager API changes — not needed
- activation.ts changes — `selectionSource` is read from `HostPanelProps` by site.ts, not by the activation code

## Files Modified

| File | Change |
|------|--------|
| `packages/pages-component/src/model/component-props.ts` | Add `selectionSource?: string` to `HostPanelProps` |
| `packages/pages-runtime/src/site.ts` | Add `dispatchSelectionToHostPanels()`, call from selection-change handler + page nav + data refresh |
| `docs/protocols/casehub/pages-event-contract.md` | Add `pages-selection-changed` to reserved events table |

## Testing Strategy

- **Unit:** `dispatchSelectionToHostPanels()` dispatches to matching host-panels only
- **Unit:** deselection dispatches with `selectedRow: null`
- **Unit:** no event dispatched when no host-panels match the source dataset
- **Unit:** page nav clear dispatches null to all host-panels with any selectionSource
- **Edge cases:** host-panel without selectionSource (no event), multiple host-panels with same selectionSource (all receive event), host-panel with selectionSource but no matching selection change (no event)
