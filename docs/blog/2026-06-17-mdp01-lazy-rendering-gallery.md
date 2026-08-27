---
layout: post
title: "Lazy Rendering and the Gallery at 90%"
date: 2026-06-17
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [lazy-rendering, web-components, cross-filtering, dashbuilder]
---

# Melviz — Lazy Rendering and the Gallery at 90%

**Date:** 2026-06-17
**Type:** phase-update

---

## What I was trying to achieve: make the DashBuilder example gallery presentable

The casehub runtime can parse DashBuilder YAML, render components, and wire up data pipelines. What it couldn't do was render a multi-page tabbed dashboard without creating six copies of every component. The Kitchensink — the canonical DashBuilder demo with 12+ pages of charts, selectors, tables, and navigation — was the acid test. Selectors didn't filter their target charts. The issue was filed as #32.

## What I believed going in: lazy rendering would be the complete fix

The diagnosis seemed clean. `renderNode` renders ALL slot children for interactive containers (tabs, pills, sidebar), then hides inactive ones with `display: none`. The component registry overwrites: `registry.set(componentId, entry)` keeps only the last registration, which is the hidden instance. Filter events update the hidden copy. The visible copy stays stale.

The fix direction was clear too: render only the active slot's children, swap on tab change. Same approach the GWT version used. I designed a swap function pattern where each wire function (wireTabs, wireSidebar, wireCarousel, wireStack) builds a single swap function that owns the slot transition — DOM teardown, lazy rendering, button chrome, event dispatch. Click handlers delegate to it. `activateSlot` delegates to it via a WeakMap registry. One code path for both interactive and programmatic activation.

## Three bugs hiding behind one symptom

The lazy rendering spec went through two review rounds. The code was clean. The tests passed — 148 component tests, 100 runtime tests. I asked to verify the fix in the browser.

Nothing worked. The selector dropdown rendered, but changing it did nothing to the bar chart.

The first bug was in `loadSite`. During implementation, `site.ts` got restructured and `renderComponent()` ended up before the event listener registration. Custom elements dispatch `casehub-data-request` during `connectedCallback` — synchronously, during `appendChild`. With the listeners registered after rendering, every data request event was dispatched into the void. No data loaded. No error appeared. The "no data AND no error" symptom sent me deep into the async pipeline — checking promises, resolver contexts, provider factories — before I finally compared line numbers and found `renderComponent` at line 67, event listeners at line 84.

The second bug: the test environment didn't register custom elements at all. The runtime uses only TYPE imports from `@casehub/viz`, so `customElements.define` never runs. `document.createElement("casehub-bar-chart")` creates a plain HTMLElement with no `connectedCallback`, no props setter, no data dispatch. The cross-filter integration test passed by construction — it was testing dead elements that couldn't do anything wrong because they couldn't do anything at all. Adding `import "@casehub/viz"` as a side-effect import fixed it.

The third bug was architectural. The parser's `resolveNavigation` puts each navTree group's pages into slots on the navigation component. But it also keeps every page in the root `content` slot. The Kitchensink's pages appeared at the top level AND inside their navigation containers — three copies of every Displayer-group page, because three different navigation components (TABS, TREE, MENU) all reference the same group. Filtering navTree-embedded pages from the top-level `content` slot eliminated the duplication. But the slot values still pointed at unresolved page objects — `resolveNavGroup` looks up pages from `childPages` (the unresolved array), not from the resolved output. A recursive `patchSlotReferences` pass fixes the references after all pages are resolved.

## The gallery at 90%

After the three fixes, a Playwright sweep across all 31 dashboards showed 28 rendering fully — charts display data, filters propagate, navigation works, markdown renders, tables paginate. The Kitchensink's selector now filters its bar chart correctly: "All" shows five products, "Computers" shows three.

Three dashboards have remaining issues. Prometheus Basic and HTTP Requests need extraction support for the Prometheus API response format — the mock data exists but the pipeline doesn't understand `{ status, data: { resultType, result } }`. The Kitchensink's map component has a rendering bug, and its iframe-plugin is expected to be empty without an external component server. Both are tracked.

Eight issues closed along the way — some by implementing fixes (#28 fetch binding, #27 tree/menu/tiles navigation types), others by verifying they already worked (#25 global defaults, #26 inline extraction, #31 case-insensitive matching). The validation matrix is committed to `examples/VALIDATION.md`.

The branch has 49 commits and is ready for work-end — squash, code review, push to fork. The context pressure from this session's investigation work makes a fresh start the right call for that ceremony.
