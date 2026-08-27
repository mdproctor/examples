# Design: pages-viz barrel crash + graph-renderer shadow DOM CSS isolation

**Issues:** #318, #319
**Date:** 2026-08-18
**Branch:** issue-318-viz-shadow-dom-fixes

## Problem

Two independent packaging/styling bugs in `pages-viz` and `graph-renderer`:

1. **#318 — Barrel export crash:** `PagesDensityHeatmap` statically imports `@drdreo/heatmap` at the top level. The barrel `index.ts` re-exports it, so any consumer importing anything from `@casehubio/pages-viz` transitively pulls in `@drdreo/heatmap`. When consumed via Maven SNAPSHOT (which extracts TypeScript sources, not `node_modules`), this crashes every downstream component that imports from `pages-viz` — even if it never uses the density heatmap.

2. **#319 — Shadow DOM CSS isolation:** `injectIsolationStyles()` injects stencil CSS and React Flow control styles into `document.head`. When `<pages-graph-canvas>` is hosted inside a LitElement shadow root (e.g. `casehub-diagram`, `swf-diagram`, `blocks-dag-viewer`), those styles can't cross the shadow boundary. Graph nodes render as unstyled rectangles — no stencil labels, icons, colours, or edge strokes.

## Fix 1: Dynamic import for @drdreo/heatmap (#318)

### Current state

```typescript
// PagesDensityHeatmap.ts:4 — static import
import { createHeatmap, withTooltip, withLegend } from "@drdreo/heatmap";
```

This executes at module evaluation time, before any component is rendered.

### Change

Replace the static import with a dynamic `import()` inside `createInstance()`. Cache the loaded module to avoid repeated resolution.

```typescript
// Lazy-loaded module cache
let heatmapModule: typeof import("@drdreo/heatmap") | undefined;

async function loadHeatmapModule(): Promise<typeof import("@drdreo/heatmap")> {
  if (!heatmapModule) {
    heatmapModule = await import("@drdreo/heatmap");
  }
  return heatmapModule;
}
```

The `updated()` lifecycle method already runs after render and is a natural async boundary. Change `createInstance` to async, and gate heatmap creation on the dynamic import resolving:

```typescript
override updated(): void {
  const container = this._containerRef.value;
  if (!container || !this.props || !this.dataSet) return;
  if (container.offsetWidth === 0 || container.offsetHeight === 0) return;
  void this._updateHeatmap(container);
}

private async _updateHeatmap(container: HTMLDivElement): Promise<void> {
  const mod = await loadHeatmapModule();
  const points = this.extractAndNormalize(this.dataSet!, this.props!, container);
  if (this._heatmap) {
    this._heatmap.setData(points);
  } else {
    this._heatmap = this._createInstance(mod, container, points, this.props!);
  }
}
```

`createInstance` receives the loaded module as a parameter instead of referencing top-level imports. The same pattern applies to `onResize()`, which also creates heatmap instances — it delegates to `_updateHeatmap` instead of calling `createInstance` directly.

### Files changed

- `packages/pages-viz/src/charts/PagesDensityHeatmap.ts` — replace static import with dynamic, refactor `createInstance`, `updated`, and `onResize`

### No barrel or package.json changes

The barrel export in `index.ts` stays as-is. The class export is still eagerly available — only the `@drdreo/heatmap` dependency is deferred. No sub-path export needed.

### blocks-ui Maven SNAPSHOT instructions

For belt-and-suspenders, the blocks-ui repo should also include `@drdreo/heatmap` in its Maven SNAPSHOT extraction so the dependency is available even for consumers that bypass the barrel. Instructions to provide:

> In the Maven packaging configuration that extracts `@casehubio/pages-viz` from the WebJar, add `@drdreo/heatmap` to the list of npm dependencies that get included in `.casehub-packages`. This ensures downstream consumers that do use `<pages-density-heatmap>` have the library available at build time.

The existing Vite alias stub at `examples/src/stubs/drdreo-heatmap.ts` can be removed once both fixes land.

## Fix 2: Shadow-aware CSS injection (#319)

### Current state

```typescript
// css-isolation.ts — global ref-count, always targets document.head
let refCount = 0;

export function injectIsolationStyles(): HTMLStyleElement {
  refCount++;
  const existing = document.head.querySelector('style[data-graph-isolation]');
  // ...
  document.head.appendChild(style);
  return style;
}
```

### Change

Accept an optional `host` element. Use `host.getRootNode()` to determine the injection target — a `ShadowRoot` or `document`. Replace the global `refCount` with a per-root `WeakMap`.

```typescript
interface StyleEntry {
  count: number;
  style: HTMLStyleElement;
}

const styleRoots = new WeakMap<Document | ShadowRoot, StyleEntry>();

function getStyleRoot(host?: HTMLElement): Document | ShadowRoot {
  if (host) {
    const root = host.getRootNode();
    if (root instanceof ShadowRoot) return root;
  }
  return document;
}

```

The injection target for a `ShadowRoot` is the shadow root itself (shadow roots accept `appendChild` for `<style>` elements). For `Document`, it's `document.head`.

```typescript
export function injectIsolationStyles(host?: HTMLElement): HTMLStyleElement {
  const root = getStyleRoot(host);
  const target = root instanceof ShadowRoot ? root : document.head;
  const entry = styleRoots.get(root);

  if (entry) {
    entry.count++;
    entry.style.textContent = getIsolationCSS();
    return entry.style;
  }

  const style = document.createElement('style');
  style.setAttribute('data-graph-isolation', 'true');
  style.textContent = getIsolationCSS();
  target.appendChild(style);
  styleRoots.set(root, { count: 1, style });
  return style;
}

export function releaseIsolationStyles(host?: HTMLElement): void {
  const root = getStyleRoot(host);
  const entry = styleRoots.get(root);
  if (!entry) return;
  entry.count--;
  if (entry.count === 0) {
    entry.style.remove();
    styleRoots.delete(root);
  }
}
```

### GraphCanvas call site

Pass `this` to both inject and release:

```typescript
override connectedCallback(): void {
  super.connectedCallback();
  this._container = document.createElement('div');
  this._container.classList.add(DIAGRAM_ROOT_CLASS);
  injectIsolationStyles(this);  // ← pass self
  // ...
}

override disconnectedCallback(): void {
  // ...
  releaseIsolationStyles(this);  // ← pass self
  super.disconnectedCallback();
}
```

### Backward compatibility

Callers that don't pass `host` get the existing behaviour — injection into `document.head` with document-scoped ref-counting. No breaking change.

### Files changed

- `packages/graph-renderer/src/bridge/css-isolation.ts` — shadow-aware injection, per-root ref-counting
- `packages/graph-renderer/src/bridge/GraphCanvas.ts` — pass `this` to inject/release calls

## Testing

### #318

- Unit test: verify `PagesDensityHeatmap` can be imported without `@drdreo/heatmap` being installed (barrel import doesn't crash)
- Unit test: verify heatmap renders correctly when the dependency is available

### #319

- Unit test: `injectIsolationStyles()` with no host injects into document.head (backward compat)
- Unit test: `injectIsolationStyles(host)` where host is inside a shadow root injects into that shadow root
- Unit test: per-root ref-counting — two GraphCanvas instances in the same shadow root share one style element; releasing both removes it
- Unit test: per-root ref-counting — GraphCanvas instances in different shadow roots get independent style elements

## References

- `packages/pages-viz/src/charts/PagesDensityHeatmap.ts:4` — static import site
- `packages/pages-viz/src/index.ts:48` — barrel re-export
- `packages/graph-renderer/src/bridge/css-isolation.ts` — current injection
- `packages/graph-renderer/src/bridge/GraphCanvas.ts:38` — current call site
- [GE-20260802-01] — CSS isolation via `all: initial` + cascade `@layer`
- [GE-20260810-02] — @drdreo/heatmap integration pattern
- [GE-20260810-03] — Dynamic imports + lazy component loading (Dockview pattern)
- [GE-20260728-01] — Maven SNAPSHOT cross-repo dependency resolution
- [PP-20260705-c7687d] — Web component strategy protocol (sub-path exports)
