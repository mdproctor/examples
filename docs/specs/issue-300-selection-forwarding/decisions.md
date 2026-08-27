## D1: Where pages-selection-changed is dispatched from

**Choice:** From `site.ts` after `contextManager.updateSelection()` — scan ComponentRegistry for host-panel entries with matching `selectionSource`, dispatch on each panel's DOM element
**Alternatives:**
- ContextConsumer registration at activation time — consumers are template-based (resolve to strings), can't carry raw row data for the event detail
- ContextManager callback API (`onSelectionChange()`) — adds API surface to ContextManager for a single consumer type; unnecessary when site.ts already has the registry and the selection data
**Rationale:** site.ts already handles all framework event dispatch. The ComponentRegistry has entries for every host-panel with its component props (including `selectionSource` once added to `HostPanelProps`). After `updateSelection(datasetId, rowData)`, iterating the registry for matching panels is O(N) over registered components — negligible cost. No ContextManager changes needed.
**Trade-offs:** site.ts grows by ~10 lines. The dispatch is synchronous inside the selection-change handler — if a panel's event listener does expensive work, it blocks the handler. Acceptable for v1; async dispatch (queueMicrotask) is a future option if profiling shows issues.
**Exploration:** quick
**Status:** captured

## D2: How host-panels declare selection interest

**Choice:** `selectionSource?: string` on `HostPanelProps` — same field name as `ExternalDataSetDef` (from #299)
**Alternatives:**
- DOM attribute on the host-panel container — requires scanning DOM rather than registry, slower and fragile
- `configure()` parameter — configure is for panelProps, mixing in framework concerns breaks the separation
**Rationale:** `HostPanelProps` is the type-safe configuration for host-panels, read during activation and stored in the ComponentRegistry entry. Using the same `selectionSource` name as `ExternalDataSetDef` (from #299) provides consistent vocabulary — "selectionSource means this component reacts to selection from dataset X."
**Trade-offs:** None significant. The field is optional and has no effect if the panel doesn't listen for the event.
**Exploration:** quick
**Status:** captured
