---
layout: post
title: "Two packaging fixes and a WeakMap"
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [shadow-dom, dynamic-import, css-isolation, web-components]
---

The pages-viz barrel export has a problem. `PagesDensityHeatmap` statically imports `@drdreo/heatmap` at the top of the file. The barrel re-exports it. So any downstream consumer importing *anything* from `@casehubio/pages-viz` — even just a type or a form input — transitively pulls in `@drdreo/heatmap`. When that consumer gets its pages-viz source files via Maven SNAPSHOT (which extracts TypeScript, not `node_modules`), the import crashes the entire application. In blocks-ui, this took down the examples gallery completely — the bootstrap crashed on the notification page import chain, which touches pages-viz for `FieldSchema`.

The fix is a dynamic `import()`. The `@drdreo/heatmap` module loads lazily when a `<pages-density-heatmap>` element actually renders, not when the barrel is evaluated. The component already defers heatmap creation to Lit's `updated()` lifecycle, so the async boundary fits without restructuring — `updated()` calls `void this._updateHeatmap(container)`, which awaits the module load and caches it for subsequent renders. The barrel export stays unchanged; consumers that never render a density heatmap never pay for the dependency.

The second fix is in graph-renderer. `injectIsolationStyles()` injects stencil CSS and React Flow control overrides into `document.head`. When `<pages-graph-canvas>` lives inside a LitElement shadow root — which is the default for the diagram components in blocks-ui — those styles can't cross the shadow boundary. The graph data is correct (ELK layout positions nodes, edges connect properly), but everything renders as unstyled rectangles. No labels, no colours, no edge strokes. The zoom controls appear as dark squares.

The interesting part of the fix is the ref-counting. The old code used a single global `refCount` integer. Multiple `GraphCanvas` instances shared one style element in `document.head`, and the last one to disconnect removed it. That model doesn't work when style elements live in different shadow roots — two diagram components in different shadow roots need independent style elements with independent lifetimes.

```typescript
const styleRoots = new WeakMap<Document | ShadowRoot, StyleEntry>();

function getStyleRoot(host?: HTMLElement): Document | ShadowRoot {
  if (host) {
    const root = host.getRootNode();
    if (root instanceof ShadowRoot) return root;
  }
  return document;
}
```

A `WeakMap` keyed on the root node — `Document` or `ShadowRoot` — gives per-root ref-counting with no global mutable state. Two components in the same shadow root share one style element. Components in different shadow roots get independent copies. When a shadow root is detached and garbage-collected, its `WeakMap` entry goes with it. Callers that don't pass a host element get the old behaviour — `document.head` injection with document-scoped counting.

Both fixes are backward-compatible. No public APIs changed. The barrel export path is identical. Existing callers of `injectIsolationStyles()` without arguments still work. The blocks-ui Vite stub for `@drdreo/heatmap` can be removed once the dynamic import lands.
