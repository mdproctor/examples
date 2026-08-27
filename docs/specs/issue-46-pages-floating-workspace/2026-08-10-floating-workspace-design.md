# Floating Workspace Component — Design Spec

**Issue:** Hortora/trellis#46
**Date:** 2026-08-10
**Approach:** Hybrid — pages API with pluggable backend and escape hatch (Approach C)

## Overview

Extract the Dockview-based floating workspace from trellis into a reusable `floating-workspace` component in casehub-pages. The component manages floating frames with tabbed content over a main content area — content-agnostic, any pages component can be tab content.

Follows the dock-workbench integration pattern: extends `pages-ui` (builder + YAML desugaring), `pages-component` (types), and `pages-runtime` (engine + backend + activation). No new packages.

## Architectural Context

Three-layer platform model:

- **pages** — generic UI platform (components, layout, workbench, floating workspace)
- **claudony** — AI/dev-tool capabilities built on pages (terminal, MCP, agent — future, Hortora/trellis#48)
- **trellis** — end-user app, thin shell composing pages + claudony

The floating workspace in pages is content-agnostic. Terminal integration stays in trellis (future: moves to claudony). The engine/backend split provides the seam for that migration.

## Key Decisions

- **Engine/backend split.** Engine is pure state, backend is rendering. Default backend wraps Dockview. Backend interface enables future extraction to claudony or replacement.
- **Dockview is an implementation detail.** Lazy-loaded, not exposed in the public API. Escape hatch (`backend.unwrap()`) for consumers needing direct access.
- **Content factory pattern.** Default factory renders pages components via `renderComponent()`. Consumers override for custom content.
- **Re-render safe.** Engine serializes state before dock-workbench teardown, restores after re-render. Same pattern as split ratios and dock state.
- **Companion modules are pure functions.** Z-order, spatial navigation, organisers — usable independently of Dockview or the engine.
- **Playwright e2e tests live in pages.** Failures surface where the component lives, not downstream in trellis.

## 1. Data Model & Types

### New types in `pages-component/src/model/types.ts`

```typescript
export interface FrameTabConfig {
  readonly key: string;
  readonly label: string;
  readonly icon?: string;
  readonly content: Component;
}

export interface FrameConfig {
  readonly key: string;
  readonly tabs: readonly FrameTabConfig[];
  readonly position?: { x: number; y: number };
  readonly size?: { width: number; height: number };
  readonly pinned?: boolean;
}

export interface FloatingWorkspaceConfig {
  readonly centre: Component | Component[];
  readonly frames?: readonly FrameConfig[];
  readonly organisers?: boolean;
}
```

**`organisers`**: when `true` (default), `applyOrganiser()` rearranges frames and emits `pages-frame-organise`. When `false`, `applyOrganiser()` is a no-op — the engine silently ignores the call. Consumers use this to disable organiser presets in contexts where free-form placement is required.

### FloatingWorkspaceProps in `pages-component/src/model/component-props.ts`

```typescript
export interface FloatingWorkspaceProps {
  readonly centre: Component | Component[];
  readonly frames?: readonly FrameConfig[];
  readonly organisers?: boolean;
}
```

Registered in `ComponentTypeRegistry` as `"floating-workspace": FloatingWorkspaceProps` with a corresponding `isFloatingWorkspace()` type guard. The desugaring layer populates `component.props` with this shape from YAML; the builder constructs it from `FloatingWorkspaceConfig` (same shape).

### Frame layout (persistence)

```typescript
export interface FrameLayout {
  readonly key: string;
  readonly order: number;
  readonly position: { x: number; y: number };
  readonly size: { width: number; height: number };
  readonly zIndex: number;
  readonly pinned: boolean;
  readonly hidden: boolean;
  readonly tabs: readonly FrameTabConfig[];
  readonly activeTabKey: string;
}
```

### LayoutState extension

```typescript
export interface LayoutState {
  readonly splits: Readonly<Record<string, readonly number[]>>;
  readonly docks: Readonly<Record<string, boolean>>;
  readonly panels: Readonly<Record<string, PanelEntry>>;
  readonly zones?: Readonly<Record<string, DockZone>>;
  readonly frames?: readonly FrameLayout[];  // NEW
}
```

The centre content is the "main frame" — always present, not floating, fills the workspace area. Additional `frames` float on top as overlays.

## 2. DSL Builder & YAML

### Builder in `pages-ui/src/dsl/builders.ts`

```typescript
export function floatingWorkspace(config: FloatingWorkspaceConfig): TypedComponent<"floating-workspace">
```

Builds a component tree with `type: "floating-workspace"`. Centre content becomes the main frame. Optional frames declared as initial floating overlays.

### YAML format

```yaml
- type: floating-workspace
  centre:
    - type: markdown
      properties:
        content: "## Main editor area"
  frames:
    - key: preview
      tabs:
        - key: preview-tab
          label: Preview
          content:
            type: html
            properties:
              content: "<div>Live preview</div>"
      position: { x: 50, y: 50 }
      size: { width: 400, height: 300 }
```

### Composition with dock-workbench

The floating workspace goes in the dock-workbench centre:

```yaml
- type: dock-workbench
  centre:
    - type: floating-workspace
      centre:
        - type: markdown
          properties:
            content: "## Editor"
      frames: [...]
  left:
    panels: [...]
```

Desugaring converts YAML into a `Component` with `type: "floating-workspace"` and props containing the serialized config. The runtime activation callback initializes Dockview.

## 3. Floating Frame Engine with Pluggable Backend

### Backend contract

```typescript
export interface FloatingFrameBackend {
  attach(container: HTMLElement, contentFactory: ContentFactory): void;
  detach(): void;

  renderFrame(layout: FrameLayout): void;
  removeFrame(key: string): void;
  updatePosition(key: string, pos: { x: number; y: number }): void;
  updateSize(key: string, size: { width: number; height: number }): void;
  bringToFront(key: string): void;

  addTab(frameKey: string, tab: FrameTabConfig): void;
  removeTab(frameKey: string, tabKey: string): void;
  setActiveTab(frameKey: string, tabKey: string): void;

  onFrameMove(cb: (key: string, pos: { x: number; y: number }) => void): void;
  onFrameResize(cb: (key: string, size: { width: number; height: number }) => void): void;
  onTabDragOut(cb: (fromFrame: string, tabKey: string, position: { x: number; y: number }) => void): void;
  onTabReorder(cb: (frameKey: string, tabKeys: string[]) => void): void;

  dispose(): void;
  unwrap(): unknown | null;
}
```

The backend receives the content factory at `attach()` time and stores it internally. Both `renderFrame()` and `addTab()` use the stored factory to create content — no content factory parameter on individual calls, no pre-created HTMLElements. This gives the Dockview implementation a natural integration point: the stored factory feeds Dockview's `createComponent` callback, which creates content lazily as panels are added.

### Engine interface

```typescript
export interface FloatingFrameEngine {
  /** All frames including hidden ones. Hidden frames have hidden: true in their FrameLayout. */
  readonly frames: ReadonlyMap<string, FrameLayout>;

  createFrame(config: FrameConfig): FrameLayout;
  removeFrame(key: string): void;
  hideFrame(key: string): void;
  showFrame(key: string): void;

  addTab(frameKey: string, tab: FrameTabConfig): void;
  removeTab(frameKey: string, tabKey: string): void;
  moveTab(fromFrame: string, tabKey: string, toFrame: string): void;
  setActiveTab(frameKey: string, tabKey: string): void;

  bringToFront(key: string): void;
  togglePin(key: string): void;

  focusDirection(direction: "up" | "down" | "left" | "right"): string | null;

  applyOrganiser(preset: "side-by-side" | "stacked" | "grid" | "main-sidebar" | "focus"): void;

  captureLayout(): readonly FrameLayout[];
  restoreLayout(saved: readonly FrameLayout[]): void;

  dispose(): void;
}
```

**`showFrame(key)` z-index adjustment:** `showFrame()` recreates a hidden frame from preserved config, then calls `bringToFront()` internally. This ensures the shown frame appears on top of the stack, not at its stale z-index from before it was hidden. The emitted `pages-frame-show` event fires before the implicit `pages-frame-focus` from `bringToFront()`.

**`engine.dispose()` contract:** Calls `backend.dispose()`, which iterates all open panels and invokes each content factory `dispose()` callback (renderer-only — see Content transfer semantics), then tears down the Dockview instance. After `dispose()`, the engine is unusable — all method calls throw. `engine.dispose()` does not close sessions or persistent connections; session cleanup is the content factory provider's responsibility.

### Content factory

```typescript
export interface ContentFactoryResult {
  readonly element: HTMLElement;
  readonly dispose?: () => void;
}

export type ContentFactory = (tab: FrameTabConfig) => ContentFactoryResult;
```

Default factory calls `renderComponent()` to render the tab's `content` Component into a container div. Its `dispose` is undefined — DOM removal via the backend triggers the standard pages cleanup chain (MutationObserver → push source unsubscribe, timer cancellation per ARC42STORIES §6). Custom factories return an explicit `dispose` for renderer resources requiring explicit teardown beyond DOM removal (WebGL renderer addons, canvas contexts). Persistent resources (WebSocket connections, session state) are managed by the content factory provider, not by `dispose()` — see Content transfer semantics. The backend calls `dispose()` before removing the element from the DOM during panel close.

### Content lifecycle

The Dockview backend's `createComponent` implementation wraps the stored content factory:
- **init**: calls `contentFactory(tab)` to produce a `ContentFactoryResult`, appends `result.element` to the panel container, stores `result.dispose` if provided
- **dispose**: calls the stored `result.dispose()` if present (teardown renderer resources — WebGL addons, canvas contexts), then removes the content element from the DOM, triggering the standard pages cleanup chain (MutationObserver → push source unsubscribe, timer cancellation per ARC42STORIES §6)

The backend's `removeFrame()` / `removeTab()` calls Dockview's panel close, which invokes `dispose()`. The implementation must remove content from the document — not move it to a detached fragment. Dockview's panel lifecycle contract guarantees `dispose()` is called on panel removal; the backend calls `result.dispose()` then enforces DOM removal to enable garbage collection.

### Content transfer semantics

`moveTab(fromFrame, tabKey, toFrame)` removes the tab from the source frame and adds it to the destination. At the backend level, this triggers `removeTab()` (Dockview panel close → content factory `dispose()` → DOM removal) followed by `addTab()` (content factory invoked → fresh content created). Content is destroyed and recreated, not DOM-reparented — Dockview's panel lifecycle does not support transferring content between groups without destruction.

For stateless content (pages components rendering data views), destroy-and-recreate is transparent. For stateful content (terminal sessions, WebSocket connections), the content factory manages session state independently of DOM lifecycle. The reference pattern is trellis's Terminal/Renderer separation: the `Terminal` instance and WebSocket connection persist across renderer disposal; the content factory creates a new renderer for an existing Terminal when invoked again for the same tab key. Content factories for stateful content maintain a session map keyed by tab key — sessions are created on first invocation and reconnected on subsequent invocations for the same key.

`ContentFactoryResult.dispose()` is renderer-only cleanup in all cases — it releases the renderer and frees GPU resources. It fires during `moveTab()`, re-render teardown (`engine.dispose()`), and permanent engine shutdown. In every case, `dispose()` does the same thing: release the renderer. It never closes sessions, WebSocket connections, or other persistent resources.

Session lifecycle is independent of renderer lifecycle. Content factories for stateful content own a session map (keyed by tab key) that survives all `dispose()` calls. Session cleanup is the responsibility of the content factory provider, triggered by the provider's own lifecycle management. The activation `AbortSignal` (from `LazyPageOptions`) fires during `liveSite.dispose()` and is available to the floating workspace activation callback; custom content factories can register an abort listener to clean up sessions on permanent shutdown. This separation mirrors trellis's Terminal/Renderer model: "Dispose renderer ≠ dispose Terminal."

### Tab resolution during restore

The engine builds a `tabRegistry: ReadonlyMap<string, FrameTabConfig>` from all declared frames' tab configs during activation. During `restoreLayout()`, each persisted tab key is resolved via this registry:

- **Registry match**: tab key found in both saved layout and registry → use registry config (picks up YAML/DSL content updates since last save). Position and layout state come from the saved data.
- **Registry miss (dynamic tab)**: tab key in saved layout but not in registry → use the saved `FrameTabConfig` from `FrameLayout.tabs` directly. Since `FrameLayout.tabs` stores full `FrameTabConfig` objects (including `content: Component`), dynamically-added tabs survive re-renders and page refreshes without a separate registry entry.
- **Extra declared tabs**: tab in registry but not in saved layout → added to its declaring frame at the default position.
- **Corrupted save**: tab key in saved layout has neither registry entry nor valid saved config → tab skipped, warning logged. If all tabs are missing, frame is not created.
- **Duplicate keys across frames**: first declaration wins. Duplicates logged as errors at activation time.

### Factory functions

```typescript
export function createFloatingFrameEngine(
  backend: FloatingFrameBackend,
  savedLayout?: readonly FrameLayout[],
): FloatingFrameEngine;

export async function createDockviewBackend(): Promise<FloatingFrameBackend>;
```

### CSS loading

`createDockviewBackend()` bundles Dockview CSS as a raw string import (`dockview-core/dist/styles/dockview.css`) and injects it as a `<style>` element into the document head synchronously after the dynamic import resolves, before constructing `DockviewComponent`. This matches the trellis pattern (Vite `?raw` import into LitElement styles). CSS injection is idempotent — a `data-pages-dockview-css` attribute prevents duplicate injection on repeated backend creation.

### Frame order assignment

`createFrame()` assigns `order = max(existing orders) + 1`, starting from 0 for the first frame. Declared frames receive order from their array index in `FrameConfig[]`. Order is stable across repositioning — moving or resizing a frame does not change its order. Order survives `captureLayout()` / `restoreLayout()` (it is a field of `FrameLayout`). Organisers use order for spatial arrangement (e.g., `side-by-side` places frames left-to-right by ascending order).

When `FrameConfig.size` is omitted, `createFrame()` applies a default of `{ width: 400, height: 300 }`. When `FrameConfig.position` is omitted, `nextFramePosition()` from `frame-boundaries.ts` computes placement with collision avoidance.

### Tab drag-out flow

The `onTabDragOut` backend callback triggers the most complex frame interaction:

1. Backend fires `onTabDragOut(fromFrame, tabKey, position)` when a tab is dragged outside its frame bounds
2. Activation callback looks up the `FrameTabConfig` from the engine's internal tab registry (populated from declared configs and runtime `addTab` calls)
3. Creates an empty frame: `engine.createFrame({ key: \`frame-${nextFrameId++}\`, tabs: [], position, size: defaultFrameSize })`
4. Moves the tab atomically: `engine.moveTab(fromFrame, tabKey, newFrameKey)` — removes from source, adds to destination
5. Emits `pages-tab-drag-out` with `{ tabKey, fromFrame, position }`
6. If the source frame has no remaining tabs, it is automatically closed (emitting `pages-frame-close`)

`nextFrameId` is a monotonic counter scoped to the engine instance. `defaultFrameSize` is `{ width: 400, height: 300 }` — matches the trellis default.

### Error handling

`createDockviewBackend()` wraps the dynamic import in a try/catch. On failure (broken bundle, code-split load error), the `.catch()` handler:

1. Logs the error to console
2. Renders a `<div class="pages-floating-workspace-error">` with "Floating workspace failed to load" into the container
3. Does not throw — the centre content remains usable, only floating frames are unavailable

### Design decisions

1. **Engine is the source of truth, not Dockview.** The engine holds frame/tab state. Dockview is a rendering backend. On re-render, the engine serializes state, Dockview is destroyed, then recreated from engine state.

2. **Lazy Dockview import.** `await import("dockview-core")` during activation. Zero cost if no `floating-workspace` component is rendered.

3. **`unwrap()` escape hatch.** Returns the raw `DockviewComponent` instance typed as `unknown`. Trellis casts it for Electron-specific needs. Documented as unstable. **Invariant boundary:** Direct mutations to the Dockview instance via `unwrap()` are invisible to the engine. After `unwrap()` mutations: `captureLayout()` and `restoreLayout()` reflect engine state, not Dockview state; z-index tracking uses the engine's counter, which diverges from direct z-index changes; the `frames` map does not reflect panels added or removed through Dockview. Read-only operations and Dockview API calls that don't mutate panel/group state (renderer configuration, event listeners, styling) are safe. Consumers that mutate panel state directly have exited engine state management and must manage their own persistence.

## 4. Runtime Integration & Events

### Activation callback

The activation callback is synchronous (returns `void`) following the existing `createActivationCallback` contract. Async backend initialization follows the `lazy-page` precedent: start async work, render in `.then()`.

When a `floating-workspace` component is encountered during `renderComponent()`:

1. Renders centre content synchronously into the container as the main (non-floating) area
2. Starts async backend creation: `createDockviewBackend()` (lazy import + CSS injection)
3. On backend ready (`.then()`):
   a. Attaches backend to the container element with the default content factory: `backend.attach(container, contentFactory)`
   b. Creates the engine: `createFloatingFrameEngine(backend, savedLayout?)`
   c. Wires backend callbacks to pages events
   d. Renders declared frames from config
   e. Restores saved layout from `LayoutState.frames` or `frameLayoutStash`

Centre content is usable immediately. Floating frames appear once the backend resolves — on first load this is the dynamic import time; on subsequent activations (cached import) resolution is on the next microtask.

### Event contract

| Event | Detail | When |
|-------|--------|------|
| `pages-frame-create` | `{ frameKey, position, size }` | New floating frame created |
| `pages-frame-close` | `{ frameKey }` | Frame removed |
| `pages-frame-hide` | `{ frameKey }` | Frame hidden — content torn down, config preserved for restoration |
| `pages-frame-show` | `{ frameKey }` | Hidden frame recreated from preserved config |
| `pages-frame-focus` | `{ frameKey }` | Frame brought to front |
| `pages-frame-pin` | `{ frameKey, pinned }` | Pin state toggled |
| `pages-tab-move` | `{ tabKey, fromFrame, toFrame }` | Tab moved between frames |
| `pages-tab-drag-out` | `{ tabKey, fromFrame, position }` | Tab dragged out to new frame |
| `pages-tab-reorder` | `{ frameKey, tabKeys }` | Tab order changed within frame |
| `pages-frame-move` | `{ frameKey, position }` | Frame position changed (fires on drag end) |
| `pages-frame-resize` | `{ frameKey, size }` | Frame size changed (fires on resize end) |
| `pages-frame-organise` | `{ preset }` | Layout preset applied |

**Event layering:** Compound operations (tab drag-out) emit both atomic events and a compound event. Atomic events (`pages-frame-create`, `pages-tab-move`, `pages-frame-close`) carry individual state changes and fire for all operations — programmatic and user-initiated. The compound event (`pages-tab-drag-out`) provides semantic context for the user gesture. Consumers use atomic events for state tracking and compound events for UI-level reactions (animations, notifications, analytics). Both fire; consumers handling atomic events do not need compound events for correctness.

### Composition constraints

Nested floating workspaces (a `floating-workspace` component containing another `floating-workspace` as tab content) are unsupported. Inner frame events (`pages-frame-create`, `pages-tab-move`, etc.) bubble through the DOM and are processed by the outer workspace's event handlers as its own frames — the pages event model uses DOM event bubbling for component communication with no scoping mechanism to isolate nested instances. Z-index conflicts and re-render cascades compound the problem. This is not specific to floating workspace — the same limitation applies to nested `dock-workbench` instances.

### Layout persistence

`captureLayout()` in site.ts extended to include `frames`:

```typescript
frames: frameEngine ? frameEngine.captureLayout() : frameLayoutStash ?? undefined
```

When the engine exists, capture live state. When the engine is undefined (async initialization in progress after re-render), return the stashed layout from before teardown. This prevents layout saves during the async window from overwriting frame state with `undefined`.

Optional field — old saved layouts without `frames` load fine. Same backward-compatibility pattern as `zones`.

### Re-render survival

A function-scope `frameLayoutStash` variable (same pattern as `splitRatios` and `dockState`) holds captured frame state across the teardown/re-render cycle.

When dock-workbench fires `pages-dock-rearrange`:

1. If `frameEngine` exists: `frameLayoutStash = frameEngine.captureLayout(); frameEngine.dispose(); frameEngine = undefined`
   `dispose()` releases all backend resources — the backend iterates open panels, calls each content factory `dispose()` callback, tears down the Dockview instance, and detaches from the container. This is necessary because the subsequent `innerHTML = ""` bypasses Dockview's panel close lifecycle; without explicit disposal, renderer resources (WebGL addons, canvas contexts) leak. Default pages component cleanup (MutationObserver per ARC42STORIES §6) still handles standard content via DOM removal.
   If `frameEngine` is undefined (first async activation still in flight): no-op — `frameLayoutStash` retains whatever value it already holds (either the initial seed from `LayoutState.frames`, or undefined if no frames were ever configured)
2. DOM cleared, re-rendered from new component tree
3. Activation callback creates new engine, passes `frameLayoutStash` as saved layout
4. Frames restored to previous positions
5. `frameLayoutStash` cleared after engine is ready

## 5. Frame Chrome & Interaction

### Frame chrome

Injected by the Dockview backend into each frame's titlebar:

| Element | Behavior |
|---------|----------|
| Close dot | Red circle (12px), calls `removeFrame()`, emits `pages-frame-close` |
| Pin button | Pushpin toggle, calls `togglePin()`, pinned frames stay on top (z-index tier 10000+) |
| Drag handle | Titlebar is the drag handle (Dockview native), position updates via `onFrameMove` |

No context menu in the initial extraction. Trellis adds its own via the escape hatch.

### Tab interactions

| Interaction | Behavior |
|-------------|----------|
| Click tab | Switch active tab within frame |
| Drag tab within frame | Smooth reorder (`tabAnimation: 'smooth'`) |
| Drag tab outside frame | Creates new frame at cursor position, emits `pages-tab-drag-out` |
| Drop zone suppression | `dndEdges: false` + CSS overrides — no split indicators within a frame |

### Spatial navigation

`frame-spatial-nav.ts` — directional frame focus by center-point half-plane geometry. Engine exposes `focusDirection()`. Not wired to keyboard shortcuts — consumers wire their own.

### Frame position boundary validation

`frame-boundaries.ts` — position clamping and new-frame positioning:

- `clampPosition(position, size, container) → { x, y }`: constrains frame position to keep the frame within the container bounds. Called during layout restore and on container resize.
- `nextFramePosition(container, frameSize, existing, displacement?) → { x, y }`: radial search algorithm for new frame placement. Centers first frame; subsequent frames offset by displacement with collision avoidance.

On container resize, visible frames are re-clamped to the new bounds via `clampPosition()`. On layout restore, saved positions are clamped to the current container size before being applied — handles the case where the browser window was resized smaller since positions were saved.

### Z-index management

`frame-zorder.ts` — two tiers:

- Normal: 1–9999 (unpinned frames)
- Pinned: 10000+ (pinned frames always on top)

`bringToFront()` increments within tier. Runtime compaction triggered when counter exceeds threshold (5000) — re-indexes all frames to sequential values, preventing unbounded growth. `normalizeForSave()` compacts independently before persistence, ensuring saved values are always minimal. Both compaction points follow the trellis implementation pattern.

## 6. Companion Pure Functions

| Module | Purpose | Interface |
|--------|---------|-----------|
| `frame-zorder.ts` | Z-index management | `bringToFront(frames, key, pinned) → frames` |
| `frame-spatial-nav.ts` | Directional navigation | `findSpatialTarget(frames, current, direction) → key \| null` |
| `frame-organisers.ts` | Layout presets | `applyPreset(frames, canvasSize, preset) → frames` |
| `frame-boundaries.ts` | Position clamping | `clampPosition(pos, size, container) → pos`, `nextFramePosition(container, frameSize, existing) → pos` |

Presets: `side-by-side`, `stacked`, `grid`, `main-sidebar`, `focus`.

All pure functions — no DOM, no Dockview dependency. Imported by the engine, usable independently.

## 7. Examples Gallery

New sample `examples/samples/Layout/Floating Workspace.dash.yaml` peered alongside `Dock Workbench.dash.yaml`.

Demonstrates full composition: dock-workbench shell with tool windows, floating workspace in the centre with multiple frames, each with multiple tabs. Users can drag frames, reorder tabs, drag tabs out, pin frames — all persisted via LayoutStore.

## 8. Testing Strategy

### Unit tests

| File | Coverage |
|------|----------|
| `floating-frame-engine.test.ts` | Frame lifecycle, tab management, z-index, pin, organisers, serialization round-trip, re-render survival |
| `frame-zorder.test.ts` | Tier increment, compaction, `normalizeForSave()` |
| `frame-spatial-nav.test.ts` | Directional navigation, edge cases |
| `frame-organisers.test.ts` | Each preset, empty input |
| `dockview-backend.test.ts` | Attach/detach, renderFrame, position/size update, callback wiring, dispose |
| `site.test.ts` (extend) | Floating workspace activation, frame events, layout round-trip, composition with dock-workbench, re-render survival |
| `builders.test.ts` (extend) | `floatingWorkspace()` config normalization, tree generation |
| `desugar-new-types.test.ts` (extend) | YAML desugaring for `floating-workspace` |

### Playwright e2e tests

`examples/e2e/floating-workspace.spec.ts` — migrated from trellis:

| Test | What it verifies |
|------|-----------------|
| Frame position persists across refresh | Drag, reload, verify restored |
| Drag tab out creates new frame | Tab outside bounds → new frame |
| Second drag-out still works | Regression — no broken state |
| No same-frame drop indicators | No `.dv-drop-target-dropzone` visible |
| Active tab persists across refresh | Switch, reload, verify |
| Pin keeps frame on top | Pin, click other, verify z-index |
| Hide/show round-trip | Hide, show, verify preserved |
| Organiser preset applies | Apply "grid", verify positions |
| Composition with dock-workbench | Frames render, dock panels toggle independently |

Trellis's `workspace-dnd.spec.ts` is deleted — pages owns frame management tests.

## 9. File Impact Summary

| File | Change |
|------|--------|
| **pages-component** | |
| `src/model/types.ts` | Add `FrameTabConfig`, `FrameConfig`, `FloatingWorkspaceConfig`, `FrameLayout`; extend `LayoutState` with `frames?` |
| `src/model/component-props.ts` | Add `FloatingWorkspaceProps` |
| `src/model/index.ts` | Re-export new types |
| **pages-ui** | |
| `src/dsl/builders.ts` | Add `floatingWorkspace()` builder, config normalization |
| `src/dsl/builders.test.ts` | Builder tests |
| `src/dsl/index.ts` | Re-export builder |
| `src/parser/component-desugar.ts` | YAML desugaring for `floating-workspace` |
| `src/parser/desugar-new-types.test.ts` | Desugaring tests |
| **pages-runtime** | |
| `src/floating-frame-engine.ts` | **New** — engine (state manager) |
| `src/floating-frame-engine.test.ts` | **New** — engine unit tests |
| `src/floating-frame-backend.ts` | **New** — backend interface |
| `src/dockview-backend.ts` | **New** — Dockview implementation |
| `src/dockview-backend.test.ts` | **New** — backend tests |
| `src/frame-zorder.ts` | **New** — z-index management |
| `src/frame-zorder.test.ts` | **New** — zorder tests |
| `src/frame-spatial-nav.ts` | **New** — directional navigation |
| `src/frame-spatial-nav.test.ts` | **New** — spatial nav tests |
| `src/frame-organisers.ts` | **New** — layout presets |
| `src/frame-organisers.test.ts` | **New** — organiser tests |
| `src/frame-boundaries.ts` | **New** — position clamping and new-frame positioning |
| `src/frame-boundaries.test.ts` | **New** — boundary tests |
| `src/activation.ts` | Add `floating-workspace` activation |
| `src/site.ts` | Frame event handlers, `captureLayout()` extension, re-render survival |
| `src/site.test.ts` | Extend — floating workspace integration |
| `src/index.ts` | Re-export engine, backend, companions |
| `package.json` | Add `dockview-core: "^7.0.0"` dependency |
| **examples** | |
| `samples/Layout/Floating Workspace.dash.yaml` | **New** — gallery sample |
| `samples.json` | Add Layout category entry |
| `e2e/floating-workspace.spec.ts` | **New** — Playwright e2e tests |
| **docs** | |
| `docs/protocols/casehub/pages-event-contract.md` | Add frame/tab events |

**Total: 15 new files, 10 modified files.**

## 10. What Stays in Trellis

- Terminal content factory + WebSocket connection
- Terminal-specific tab flyout (`<trellis-tab-flyout>`)
- MCP command handler (`handleCommand()`, `getUIState()`)
- Agent SSE state management
- Electron IPC (native window detach/attach, save coordination, WebGL management)
- Keyboard shortcuts
- Picker UI (repos, slots, groups)

Trellis becomes a consumer: imports the floating workspace from pages, provides a terminal content factory (returning `ContentFactoryResult` with explicit `dispose` for WebSocket/xterm teardown), and holds its own backend reference for Electron-specific features via `backend.unwrap()`.

Future consolidation (Hortora/trellis#48): terminal, MCP, agent, picker move to claudony. Trellis becomes thinner.
