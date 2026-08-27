---
title: The extraction that found its layers
date: 2026-08-23
project: casehub-pages, blocks-ui
issues: [casehubio/casehub-pages#354, casehubio/blocks-ui#126]
---

The audit-trail-viewer at 695 lines had been doing three jobs: rendering a
filter bar, managing a data table with expansion, and handling ledger-specific
concerns like Merkle verification and attestation badges. IoT needed a
filterable event table and could not reuse any of it.

The extraction split the component across two platform layers. A standalone
filter bar landed in casehub-pages as a UI primitive -- type chips extracted
from dataset column values, a custom entity dropdown with keyboard navigation,
and date range inputs, all behind a single `filter-change` event that carries
a `FilterState` object. The component renders nothing when no filter properties
are set, so consumers opt in per section.

The second layer -- `blocks-event-trail` in blocks-ui -- composes the filter
bar with pages-table and adds DataSourceMixin lifecycle. It owns the
client-side filter application (chip and entity filters against raw entries)
while delegating date range to server-side query parameters via
`resolveEndpoint()`. A `data-loaded` event exposes raw entries so parent
components like audit-trail-viewer can still render domain-specific detail
content (attestation badges, Merkle digests) without the TypedRow abstraction
getting in the way.

The refactored audit-trail-viewer dropped to 273 lines. The verification
banner keeps its own DataSourceAdapter because its lifecycle is independent
of the entries fetch. Everything else delegates to blocks-event-trail.

The design review caught the root architectural issue before implementation:
the TypedRow boundary hides raw entry fields, so `getRowDetail` callbacks
cannot render attestation detail unless raw entries are available. The
`data-loaded` event pattern resolved this -- the component fires the event
in both endpoint fetch and inline data modes, and consumers listen to
receive the original array alongside the TypedDataSet.

Three other review findings shaped the final design. `FilterState` switched
from `Set<string>` to `readonly string[]` for JSON serializability and to
prevent shared mutable state via `EMPTY_FILTER_STATE`. The `client-filter`
attribute on pages-table was nearly dropped during the extraction -- only
the robustness dimension caught the regression. And the stale data bug
(filters running on old entries after a date-range re-fetch) was fixed by
calling `_applyFilters()` inside the `createSourceFactory` callback after
new data arrives.

60 tests across three components, all passing. The filter bar alone has 27.
