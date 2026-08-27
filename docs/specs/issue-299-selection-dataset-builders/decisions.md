## D1: Where selectionSource is declared

**Choice:** On `ExternalDataSetDef` only — not on `RestSourceOptions` or `DataSourceBinding`
**Alternatives:**
- On `RestSourceOptions` — `restSource` can't act on it (no RuntimeContext access), dead weight on a data-layer type
- On both `DataSourceBinding` and `ExternalDataSetDef` — unnecessary since template resolution only exists on the `ExternalDataSetDef` runtime path (`handleDefRequest` in data-pipeline.ts); the `DataSourceBinding` path (`handleBindingRequest`) has no template awareness
**Rationale:** `selectionSource` is a runtime declaration consumed by the ContextConsumer pipeline, which only operates on the `ExternalDataSetDef` path. Placing it on `ExternalDataSetDef` alone keeps the type surface minimal and avoids adding metadata to `DataSourceBinding` where it would never be read.
**Trade-offs:** `detailDataset()` must produce an `ExternalDataSetDef` (not a `DataSourceBinding`) to enter the template-aware code path. This is the correct choice anyway — see D2.
**Exploration:** quick
**Status:** revised (R1 — narrowed from binding+def to def-only based on verified runtime paths)

## D2: detailDataset() output type and signature

**Choice:** Produces an `ExternalDataSetDef` with a template string URL, not a `DataSourceBinding` with a JS function
**Alternatives:**
- `DataSourceBinding` output — `handleBindingRequest` in `data-pipeline.ts` connects the source directly with no ContextConsumer, no template resolution, no `allTemplateVarsResolved()` gating. Template placeholders in the URL would be fetched literally. **Ruled out by R1-01.**
- `(row) => url` function with Proxy-based template generation — can't serialize to YAML, adds Proxy complexity, fragile to debug
**Rationale:** The `handleDefRequest` path in `data-pipeline.ts` registers a `ContextConsumer` that resolves `#{selection.xxx.yyy}` templates, gates fetches via `allTemplateVarsResolved()`, and re-triggers on context changes. `detailDataset()` must produce an `ExternalDataSetDef` to enter this path. Signature: `detailDataset("detail-ds", "events", "/api/ae/#{selection.events.id}/history")` → `{ uuid: dataSetId("detail-ds"), url: "...", selectionSource: "events" }`.
**Trade-offs:** No compile-time type safety on template field names. The master dataset name (`"events"`) appears both as the second parameter and embedded in the URL template — redundant but harmless (selectionSource is metadata per D4). A `:field` shorthand (auto-expanding to `#{selection.source.field}`) could eliminate the redundancy in a future version.
**Exploration:** quick
**Depends on:** D1 (selectionSource on ExternalDataSetDef)
**Status:** revised (R1 — changed output type from DataSourceBinding to ExternalDataSetDef based on verified runtime template resolution paths)

## D3: YAML desugaring path for selectionSource

**Choice:** No desugaring needed — `selectionSource` passes through as a raw property on the dataset object and lands on `ExternalDataSetDef` directly
**Alternatives:**
- Explicit desugaring step in `component-desugar.ts` or `page-parser.ts` — unnecessary since datasets flow as raw objects, not through component desugaring
- Zod schema validation for `selectionSource` — `page-schema.ts` validates datasets as `z.array(z.unknown())` already; adding a dataset-level schema is a broader change outside this issue's scope
**Rationale:** The existing YAML→runtime pipeline passes dataset properties through without transformation. Adding `selectionSource?: string` to `ExternalDataSetDef` is sufficient — no parser or desugaring code needs to change.
**Trade-offs:** No parse-time validation that the referenced master dataset ID exists. Consistent with how all other dataset cross-references work today.
**Exploration:** quick
**Depends on:** D1 (selectionSource on ExternalDataSetDef)
**Status:** captured

## D4: Runtime behaviour of selectionSource

**Choice:** Pure metadata for v1 — `selectionSource` is informational, the URL template `#{selection.xxx.yyy}` does all the runtime work via the existing ContextConsumer pipeline on the `handleDefRequest` path
**Alternatives:**
- Runtime deferred-fetch gating keyed on `selectionSource` — redundant with `allTemplateVarsResolved()` for template URLs; only useful for a non-template URL use case that doesn't exist yet
**Rationale:** The existing template resolution pipeline on the `ExternalDataSetDef` path already handles deferred fetch correctly: when `#{selection.events.id}` is unresolved, `allTemplateVarsResolved()` returns false and the consumer short-circuits. `selectionSource` serves as: (1) documentation of the dependency, (2) enabler for `detailDataset()` sugar, (3) YAML ergonomics, (4) future hook for tooling/validation.
**Trade-offs:** `selectionSource` is not enforced at runtime — a dataset could declare `selectionSource: "foo"` but have no `#{selection.foo...}` templates in its URL. No harm, just misleading metadata. Acceptable for v1.
**Exploration:** quick
**Depends on:** D1 (selectionSource on ExternalDataSetDef)
**Status:** captured
