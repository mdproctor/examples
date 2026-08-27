# Design: GraphCanvas public nodes/edges, stencil constraints, React Flow CSS

**Issue:** #320
**Date:** 2026-08-18
**Branch:** issue-320-graphcanvas-public-props

## Problem

`GraphCanvas` only accepts a `model` property — it runs its own ELK layout internally and renders the result. Downstream consumers in blocks-ui (`DiagramBaseMixin`) run their own ELK layout pipeline with decorations and stencil sizing, then want to pass pre-layouted React Flow `nodes`/`edges` directly. Setting `.nodes`/`.edges` on GraphCanvas creates plain instance properties that bypass Lit's reactive system — the internal `@state() private _nodes/_edges` stay as empty arrays and React Flow renders zero nodes.

Two secondary problems compound this:

1. When GraphCanvas does run ELK via `.model`, stencil web components expand beyond their ELK-assigned sizes because the wrapper div has no dimension constraints. Visual result: only the root container node is visible; children overflow and are clipped.

2. The React Flow base CSS (`@xyflow/react/dist/style.css`) is imported in `ReactFlowApp.tsx` and lands in `document.head`. With #319's shadow-aware injection, isolation CSS now reaches shadow roots — but React Flow base styles (`.react-flow`, `.react-flow__node`, `.react-flow__edge`) don't. Nodes inside shadow roots lack position: absolute, pointer-events, and other layout rules.

## Fix 1: Public nodes/edges properties

### Current state

```typescript
// GraphCanvas.ts — private state only
@state() private _nodes: Node[] = [];
@state() private _edges: Edge[] = [];
```

### Change

Add public reactive properties alongside the private state:

```typescript
@property({ attribute: false }) nodes: Node[] | undefined;
@property({ attribute: false }) edges: Edge[] | undefined;

@state() private _nodes: Node[] = [];
@state() private _edges: Edge[] = [];
```

Update `updated()` to distinguish the two input paths:

```typescript
override updated(changed: Map<string, unknown>): void {
  if (changed.has('model') || changed.has('layoutOptions')) {
    void this._runLayout();
  } else if (changed.has('nodes') || changed.has('edges')) {
    this._nodes = this.nodes ?? [];
    this._edges = this.edges ?? [];
  }
  this._renderReact();
}
```

**Priority rule:** `model` triggers `_runLayout()` which overwrites `_nodes`/`_edges`. Direct `nodes`/`edges` only applies when `model` hasn't changed in the same update. Consumers use one path or the other — not both.

### Files changed

- `packages/graph-renderer/src/bridge/GraphCanvas.ts` — add properties, update `updated()`

## Fix 2: Stencil wrapper dimension constraints

### Current state

```tsx
// stencil-wrapper.tsx — no size constraints
<div
  className="stencil-decoration-wrapper"
  style={{ position: 'relative', ...borderStyle }}
  title={decoration?.tooltip}
>
```

### Change

Read `width`/`height` from React Flow `NodeProps` (set by ELK layout via `toReactFlowGraph()`). Apply as dimension constraints on the wrapper:

```tsx
function StencilNode({ id, type, data, parentId, width, height }: NodeProps): React.JSX.Element {
  // ... existing code ...

  const sizeStyle: React.CSSProperties = {};
  if (width != null) {
    sizeStyle.maxWidth = width;
    sizeStyle.width = width;
  }
  if (height != null) {
    sizeStyle.maxHeight = height;
    sizeStyle.height = height;
  }
  if (width != null || height != null) {
    sizeStyle.overflow = 'hidden';
  }

  return (
    <>
      {/* handles */}
      <div
        className="stencil-decoration-wrapper"
        style={{ position: 'relative', ...borderStyle, ...sizeStyle }}
        title={decoration?.tooltip}
      >
        {/* content */}
      </div>
      {/* handles */}
    </>
  );
}
```

When ELK dimensions are absent (`width`/`height` undefined — e.g. nodes without explicit sizing), no constraints are applied. This preserves the current behaviour for unconstrained nodes.

### Files changed

- `packages/graph-renderer/src/stencil-wrapper.tsx` — destructure `width`/`height` from props, apply as constraints

## Fix 3: React Flow base CSS in getIsolationCSS()

### Current state

```typescript
// ReactFlowApp.tsx:17 — CSS lands in document.head via Vite
import '@xyflow/react/dist/style.css';
```

### Change

Import React Flow CSS as a raw string in `css-isolation.ts` and include it in `getIsolationCSS()`:

```typescript
// css-isolation.ts
import reactFlowCSS from '@xyflow/react/dist/style.css?raw';

export function getIsolationCSS(): string {
  const pluginStyles = getRegisteredStyles();

  return `
${reactFlowCSS}

.${DIAGRAM_ROOT_CLASS} {
  all: initial;
  display: block;
  // ... existing isolation CSS
}
// ... rest of isolation CSS
${pluginStyles}
`.trim();
}
```

Remove the CSS import from `ReactFlowApp.tsx` — it's now bundled in the isolation CSS. Both light DOM and shadow root consumers get it via `injectIsolationStyles()`.

The `?raw` suffix is a Vite import convention that returns file content as a string at build time. TypeScript needs a module declaration for this:

```typescript
// In a .d.ts file or inline
declare module '@xyflow/react/dist/style.css?raw' {
  const css: string;
  export default css;
}
```

### Files changed

- `packages/graph-renderer/src/bridge/css-isolation.ts` — import RF CSS as raw string, prepend to isolation output
- `packages/graph-renderer/src/bridge/ReactFlowApp.tsx` — remove CSS import
- `packages/graph-renderer/src/raw-css.d.ts` — type declaration for `?raw` imports (if not already present)

## Testing

### Fix 1

- Unit test: setting `nodes`/`edges` directly triggers `_renderReact()` without `_runLayout()`
- Unit test: setting `model` triggers `_runLayout()` and ignores direct `nodes`/`edges`
- Unit test: setting `nodes` then `model` — model wins

### Fix 2

- Unit test: stencil wrapper applies width/height constraints when NodeProps carries them
- Unit test: stencil wrapper has no constraints when width/height are undefined

### Fix 3

- Unit test: `getIsolationCSS()` output includes React Flow base CSS (`.react-flow` selector present)
- Existing shadow root injection tests continue to pass

## References

- `packages/graph-renderer/src/bridge/GraphCanvas.ts` — primary fix site
- `packages/graph-renderer/src/stencil-wrapper.tsx` — dimension constraints
- `packages/graph-renderer/src/bridge/css-isolation.ts` — CSS injection
- `packages/graph-renderer/src/bridge/ReactFlowApp.tsx` — CSS import removal
- [issue-259-graph-phase0/2026-08-02-phase0-react-flow-lit-bridge-design.md] — original bridge design
- [issue-265-graph-renderer/2026-08-03-stencil-wrapper-pipeline-design.md] — stencil rendering pipeline
- [GE-20260818-f0257a] — shadow-aware CSS injection technique
- [PP-20260705-c7687d] — Web component strategy protocol
- [GitHub #319] — shadow DOM CSS isolation fix (prerequisite, already landed)
