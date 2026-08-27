## D1: Dataset ID identification for selection events

**Choice:** Lookup via `findComponentId()` + `ComponentRegistry` in `site.ts`
**Alternatives:**
- Require explicit `selectionId` attribute on the table — more explicit, but requires config on every master table
- Thread DataSetLookup from parent PagesElement — couples selection to the container, not the table
**Rationale:** `site.ts` already maps DOM elements to dataset IDs via `findComponentId()` (walks up to `[data-component-id]`) → `registry.get(componentId)` → `entry.originalLookup.dataSetId`. Every existing framework event handler uses this pattern. When `selection-change` bubbles up, the same lookup gives the dataset ID with zero config for table authors.
**Trade-offs:** If a table is rendered outside the component registry (standalone, no `pages-data-request`), its selection won't be captured. This is acceptable — standalone tables don't participate in RuntimeContext.
**Exploration:** quick
**Status:** revised (R1 — corrected attribution from "pipeline map" to ComponentRegistry in site.ts)

## D2: Bridge listener location

**Choice:** In `site.ts` alongside existing framework event listeners
**Alternatives:**
- New `selection-bridge.ts` module in pages-runtime — cleaner separation but adds wiring overhead for ~40 lines
- In `data-pipeline.ts` — data-pipeline.ts has zero addEventListener calls; it is a stateless utility that site.ts calls into
**Rationale:** All framework event listeners (`pages-filter`, `pages-sort`, `pages-page`, `pages-data-request`, etc.) are registered in `site.ts`, not `data-pipeline.ts`. The data pipeline exposes handler methods that `site.ts` calls. The `selection-change` listener follows the same pattern: `site.ts` listens, looks up via `findComponentId` + registry, calls `contextManager.updateSelection()`.
**Trade-offs:** `site.ts` grows by one more event handler. Consistent with every other framework event.
**Exploration:** quick
**Status:** revised (R1 — corrected location from data-pipeline.ts to site.ts based on verified codebase structure)
**Depends on:** D1 (uses the same ComponentRegistry lookup)

## D3: RuntimeContext.selection shape

**Choice:** `selection: Readonly<Record<string, Record<string, unknown>>>` — dataset ID maps directly to the selected row's field values
**Alternatives:**
- `SelectionState` wrapper with `{ key: string; row: Record<string, unknown> }` — includes the row key but adds nesting, requiring `#{selection.myDataset.row.name}` instead of `#{selection.myDataset.name}`
**Rationale:** Row data only is minimal, directly walkable by the existing template resolution. `#{selection.adverseEvents.id}` resolves naturally with no extra nesting. The row key is not needed by detail datasets.
**Trade-offs:** No row key in RuntimeContext. If re-selection-by-key is needed later (e.g., for push-driven datasets), a sibling property can be added without breaking template resolution.
**Exploration:** quick
**Status:** captured

**Clarifications (from R1):**
- **`row?` vs `selection`:** `RuntimeContext.row` is per-row rendering context set during cell expression evaluation (e.g., conditional formatting). `selection` is user-selected master row state for driving parameterised detail dataset URLs. Different concepts, different lifecycles.
- **Multi-master:** Multiple datasets can hold selections simultaneously. No ambiguity — each detail dataset's URL template references a specific dataset ID (`#{selection.adverseEvents.id}`), so overlapping selections don't conflict.
- **Multi-select out of scope:** Issue #298 explicitly scopes to single-row selection. The shape maps one dataset → one row. Multi-select is a future concern.

## D4: Selection clearing triggers

**Choice:** Clear on dataset refresh + deselect + page navigation
**Alternatives:**
- Deselect + page nav only — master dataset refresh keeps selection; the row might still be present but detail datasets could show stale data
- All above + cross-filter change — most conservative but redundant since cross-filter changes trigger re-fetch, which is already covered by the dataset refresh trigger
**Rationale:** Dataset refresh means the master table's data has changed (push or re-fetch), so the selected row may no longer exist. Clearing prevents detail datasets from referencing a stale row. Page navigation changes the entire context. Deselect is the explicit user action.
**Trade-offs:** Live-updating master datasets will lose selection on every push update. Users must re-select after a data refresh. For push-driven datasets with frequent updates, this could be annoying — but it's the safe default. Re-selection-by-key can be added as a follow-up if needed.
**Exploration:** quick
**Status:** captured

**Clarifications (from R1):**
- **Filter persistence contrast:** Cross-filters survive refresh because they reference column *values* that are schema-inherent. Selection references a specific *row instance* that may be removed by a refresh. The distinction justifies different clearing behaviour for v1. Re-selection-by-key (attempt to re-select if the row still exists in refreshed data) is a valid enhancement but adds complexity beyond the initial scope.
- **Page scoping:** For v1, selection is keyed globally by dataset ID (not per-page-path like filters). This is sufficient because detail datasets with template URLs only resolve when the page containing them is active. Per-page scoping is a future enhancement if master tables appear on multiple page paths.

## D5: Relationship to existing record-selection-via-filter mechanism

**Choice:** New parallel mechanism — RuntimeContext.selection complements, does not replace, the existing pages-filter record-selection path
**Alternatives:**
- Extend the existing pages-filter record-selection path to also populate RuntimeContext — would couple two distinct data flow models
- Replace pages-filter record-selection entirely — would break existing DataScope-based master-detail patterns
**Rationale:** The existing `pages-filter` handler (site.ts:529-596) detects record selection within a DataScope hierarchy: when a clicked row contains the child scope's `idColumn`, it sets a cross-filter on the child scope's data. This is a *local filtering* mechanism — the child page shares the parent's data source and filters it by selected ID. The new `selection` mechanism is a *parameterised URL* mechanism — detail datasets have template URLs (`#{selection.adverseEvents.id}`) that resolve and trigger server-side fetches from different API endpoints. Both are needed for different use cases.
**Trade-offs:** Two selection mechanisms exist. Table authors need to understand which to use: DataScope `idColumn` for local parent-child filtering, `selection` context for parameterised detail dataset URLs.
**Exploration:** quick (implicit decision surfaced by R1, captured here explicitly)
**Status:** captured
