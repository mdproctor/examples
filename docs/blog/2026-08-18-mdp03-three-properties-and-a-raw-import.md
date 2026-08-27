---
layout: post
title: "Three properties and a raw import"
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [graph-renderer, lit, react-flow, shadow-dom]
series: issue-320-graphcanvas-public-props
---

`GraphCanvas` was designed as a self-contained component: give it a `model`, it runs ELK layout, renders the result. But blocks-ui's diagram components already run their own ELK pipeline — they compute layout with decorations and stencil sizing, then want to pass the pre-layouted React Flow nodes directly. The API didn't support that.

Setting `.nodes` on a LitElement that only declares `@state() private _nodes` creates a plain JavaScript property. Lit never sees it change. The internal `_nodes` stays as an empty array, and React Flow renders zero nodes. The fix is two `@property({ attribute: false })` declarations plus a small change to `_renderReact()` — read from the public property when set, fall back to internal state:

```typescript
nodes: this.nodes ?? this._nodes,
edges: this.edges ?? this._edges,
```

The earlier version copied `this.nodes` into `this._nodes` inside `updated()`, which triggered a Lit double-update warning — setting `@state` during an update schedules another update. Reading both sources in the render path avoids that entirely.

The stencil sizing problem was independent. When GraphCanvas runs ELK via `.model`, it assigns node dimensions, but the stencil web components inside those nodes expand with `width: 100%` of available space. The wrapper div in `stencil-wrapper.tsx` had no constraints. Adding `maxWidth`/`maxHeight` from the React Flow `NodeProps.width`/`height` keeps stencil content within its ELK-assigned bounds.

The last piece is React Flow's base CSS. The `import '@xyflow/react/dist/style.css'` in `ReactFlowApp.tsx` lands in `document.head` via Vite. With the shadow-aware injection from the earlier session, isolation CSS now reaches shadow roots — but the React Flow base styles don't. Without `.react-flow__node { position: absolute }`, nodes stack vertically instead of positioning at their ELK coordinates.

The fix bundles the CSS as a raw string via Vite's `?raw` import suffix and prepends it to `getIsolationCSS()`. One injection mechanism, one `<style>` element per root, all CSS in one place. The `import` statement in ReactFlowApp.tsx is gone.
