# Floating Workspace UX Polish — Design Spec

**Epic:** casehubio/casehub-pages#309
**Date:** 2026-08-11
**Child issues:** #304 (detach), #305 (organiser toolbar), #306 (snap-to-edge), #307 (keyboard shortcuts), #308 (frame animations)

## Overview

Five UX enhancements to the floating workspace. All build on existing extension points in `pages-runtime` — no new packages, consistent with the workbench-integration-pattern protocol. The wire function (`wireFloatingWorkspace`) and activation callback are the shared integration layer.

### Architectural constraints

- `FloatingFrameBackend` interface not structurally changed — three additive callbacks (`onFrameDragMove`, `onTitlebarDoubleClick`, `getFrameElement`) added; detach is handled above the backend layer
- No Lit dependency added to `pages-runtime` — all new DOM is plain, matching `injectFrameChrome()` patterns
- `FrameLayout` stays flat — two new optional fields (`detached`, `snappedZone`), backward-compatible
- Accessibility beyond `prefers-reduced-motion` and existing `aria-pressed` deferred to #15

### Prerequisite: FrameLayout spread refactor

The engine constructs `FrameLayout` objects by explicitly listing every field in 7+ locations (`showFrame`, `addTab`, `removeTab`, `setActiveTab`, `togglePin`, `updatePosition`, `updateSize`), plus `withLayout` in `frame-organisers.ts`. Adding `detached` and `snappedZone` to `FrameLayout` would be silently stripped at all these sites.

**Before any feature work:** refactor all FrameLayout construction sites to use object spread (`{ ...frame, hidden: false }`) instead of explicit field listing. This eliminates the maintenance trap structurally — new optional fields are automatically preserved.

### Module decomposition

The wire function stays focused on its current responsibility: connecting backend callbacks to engine state and DOM events. New concerns are decomposed into companion modules that the wire function (or activation callback) composes:

| Module | Responsibility |
|--------|---------------|
| `frame-detach-handler.ts` | **New** — child window lifecycle, EventRelay, DetachRegistry, content factory rendering |
| `frame-snap-preview.ts` | **New** — snap preview overlay DOM, drag tracking, zone detection |
| `frame-animations.ts` | **New** — enter/exit CSS injection, Web Animations API bridge, `prefers-reduced-motion` |
| `frame-keyboard.ts` | **New** — keyboard handler factory (already designed as standalone) |

Each module exports a factory function taking the engine, backend, and/or container. The wire function and activation callback compose them.

## 1. Frame Detach (#304)

### Architecture

Detach is handled by a new `frame-detach-handler.ts` module, composed by the wire function. It reuses three utilities from `pages-runtime/src/detach/`: `EventRelay` (event bridging), `DetachRegistry` (tracking), and `copyStyles` (visual consistency). It does NOT use `DetachController` itself — the core detach/reattach logic is a new implementation tailored to Dockview's destroy/recreate lifecycle, using `engine.hideFrame()` / `engine.showFrame()` instead of `adoptNode()`.

`DetachController` remains in use for dock panel detach (a separate, simpler path). Both patterns coexist.

### Detach handler setup

`wireFloatingWorkspace()` gains an optional `detachEnabled?: boolean` parameter (default `true`). When enabled, it creates a `FrameDetachHandler` from `frame-detach-handler.ts` and passes an `extraButtons` entry to `backend.attach()`:

```typescript
const detachBtn: FrameButtonConfig = {
  icon: "\u{1F5D7}",   // 🗗 overlapping windows
  title: "Pop out to new window",
  className: "frame-detach-btn",
  onClick: (frameKey) => handleDetach(frameKey),
};
```

### Detach flow

1. `handleDetach(frameKey)`:
   - Read `FrameTabConfig[]` from `engine.frames.get(frameKey)`
   - Call `engine.hideFrame(frameKey)` — tears down Dockview panel, preserves config
   - Open child window via `window.open("", "_blank", "width=W,height=H")` using the frame's current size
   - Call `copyStyles(document, childWindow.document)` for visual consistency
   - Render content into child window via the content factory (same factory used by the activation callback)
   - Create `EventRelay(childWindow.document, container)` for event bridging
   - Register in `DetachRegistry` keyed by frame key
   - Dispatch `pages-frame-detach` event with `{ frameKey }`

2. `handleReattach(frameKey)`:
   - Stop `EventRelay`, close child window
   - Remove from `DetachRegistry`
   - Call `engine.showFrame(frameKey)` — recreates Dockview panel from preserved config, content factory renders fresh content
   - Dispatch `pages-frame-reattach` event with `{ frameKey }`

3. Child window close detection: poll timer (500ms), same as `DetachController`. On child window close → `handleReattach()`.

4. Reattach button: rendered in child window's body as a fixed-position button, calls `handleReattach()` via the `EventRelay` bridge.

### FrameLayout persistence

```typescript
export interface FrameLayout {
  // ... existing fields ...
  readonly detached?: boolean;
}
```

`captureLayout()` records `detached: true` for detached frames. `restoreLayout()` reopens child windows for frames with `detached: true`.

### Engine changes

`engine.hideFrame()` and `engine.showFrame()` already exist and handle the lifecycle. One addition: `hideFrame()` must preserve the frame's `detached` flag in the internal state so `captureLayout()` can read it.

New engine method:

```typescript
setDetached(key: string, detached: boolean): void;
```

State-only — updates the internal `frames` map. Called by the wire function after detach/reattach.

### Event contract additions

| Event | Detail | When |
|-------|--------|------|
| `pages-frame-detach` | `{ frameKey }` | Frame popped out to child window |
| `pages-frame-reattach` | `{ frameKey }` | Frame returned from child window |

### Cross-decision: detach × snap (D1×D3)

Detaching a snapped frame clears `snappedZone` — the child window has its own viewport. On reattach, the frame recreates without zone constraint. The user re-snaps if desired.

## 2. Organiser Toolbar (#305)

### Architecture

Plain DOM created by the activation callback, rendered at the top of the floating workspace container. Follows the same pattern as `injectFrameChrome()` — no Lit, no framework dependency.

### Toolbar structure

```html
<div class="pages-organiser-toolbar" data-floating-workspace-toolbar>
  <button data-preset="side-by-side" title="Side by side">⬜⬜</button>
  <button data-preset="stacked" title="Stacked">⬜\n⬜</button>
  <button data-preset="grid" title="Grid">⊞</button>
  <button data-preset="main-sidebar" title="Main + Sidebar">⬜▫</button>
  <button data-preset="focus" title="Focus">⬜</button>
</div>
```

### Rendering

Extracted into a standalone `createOrganiserToolbar(engine, container, signal)` function (not inline in the activation callback). Created after the centre container and before the overlay container:

```typescript
const toolbar = document.createElement("div");
toolbar.className = "pages-organiser-toolbar";
toolbar.dataset.floatingWorkspaceToolbar = "";
toolbar.style.cssText = "display:none;padding:4px 8px;gap:4px;align-items:center;background:var(--pages-neutral-2);border-bottom:1px solid var(--pages-neutral-4);";
el.insertBefore(toolbar, overlayContainer);
```

### Behavior

- Each button calls `engine.applyOrganiser(preset, canvasSize)` where `canvasSize` is read from the overlay container's dimensions
- Active preset button gets `.preset-active` CSS class (background highlight)
- Toolbar visibility:
  - Hidden when `organisers: false` in config (never rendered)
  - Hidden when ≤1 visible frame (set `display:none`)
  - Shown when >1 visible frame (set `display:flex`)
- Frame count tracking: subscribe to `pages-frame-create`, `pages-frame-close`, `pages-frame-show`, `pages-frame-hide` events on the container to update visibility
- Dispatches `pages-frame-organise` event with `{ preset }` after applying (already exists in event contract)

### Styling

Uses design tokens: `--pages-neutral-2` background, `--pages-neutral-4` border, `--pages-neutral-9` text. Buttons use `--pages-radius-sm` border radius. Active button uses `--pages-accent-3` background.

## 3. Snap-to-Edge (#306)

### Data model

```typescript
export type SnapZone = "left" | "right" | "top" | "bottom"
  | "top-left" | "top-right" | "bottom-left" | "bottom-right"
  | "full";
```

```typescript
export interface FrameLayout {
  // ... existing fields ...
  readonly snappedZone?: SnapZone;
}
```

### Pure functions in `frame-boundaries.ts`

```typescript
export function snapToZone(
  dragPosition: Position,
  containerSize: Size,
  threshold?: number,
): SnapZone | null;
```

Detects proximity to container edges/corners. Default threshold: 40px. Returns `null` if no edge is within threshold. Corner zones take priority over edge zones when both match.

```typescript
export function zoneToRect(
  zone: SnapZone,
  containerSize: Size,
  gap?: number,
): { position: Position; size: Size };
```

Converts a zone to concrete geometry. Gap defaults to 8 (matching organiser presets). Zone geometry:

| Zone | Position | Size |
|------|----------|------|
| `left` | `(0, 0)` | `(width/2 - gap/2, height)` |
| `right` | `(width/2 + gap/2, 0)` | `(width/2 - gap/2, height)` |
| `top` | `(0, 0)` | `(width, height/2 - gap/2)` |
| `bottom` | `(0, height/2 + gap/2)` | `(width, height/2 - gap/2)` |
| `top-left` | `(0, 0)` | `(width/2 - gap/2, height/2 - gap/2)` |
| `top-right` | `(width/2 + gap/2, 0)` | `(width/2 - gap/2, height/2 - gap/2)` |
| `bottom-left` | `(0, height/2 + gap/2)` | `(width/2 - gap/2, height/2 - gap/2)` |
| `bottom-right` | `(width/2 + gap/2, height/2 + gap/2)` | `(width/2 - gap/2, height/2 - gap/2)` |
| `full` | `(0, 0)` | `(width, height)` |

### Engine changes

New methods:

```typescript
snapFrame(key: string, zone: SnapZone, canvasSize: Size): void;
unsnapFrame(key: string): void;
recomputeSnappedFrames(canvasSize: Size): void;
```

- `snapFrame()`: captures pre-snap position/size internally, sets `snappedZone` on the frame, computes new position/size via `zoneToRect()`, updates internal state, calls `backend.updatePosition()` and `backend.updateSize()`
- `unsnapFrame()`: clears `snappedZone`, restores pre-snap position/size from internally captured state. If no pre-snap state exists (e.g., frame was created already snapped), uses the zone's current geometry as-is.
- `recomputeSnappedFrames()`: iterates all frames with `snappedZone`, recomputes position/size for the new canvas size. Called on container resize.

The engine owns pre-snap state internally — no external shadow map needed. This is consistent with the engine owning all frame geometry state.

### Backend integration

Zone preview overlay during drag:

The `onFrameMove` callback in the wire function calls `snapToZone()` with the drag position. If a zone is detected:
1. Show a semi-transparent overlay div in the overlay container highlighting the zone area
2. On drag end (move callback fires with final position): if zone detected, call `engine.snapFrame(key, zone, canvasSize)`
3. If no zone on drag end: call `engine.unsnapFrame(key)` if the frame was previously snapped

Preview overlay is a single reusable `<div>` with `position:absolute`, `background:var(--pages-accent-3)`, `opacity:0.2`, `border-radius:var(--pages-radius-sm)`, `pointer-events:none`. Positioned via `zoneToRect()`.

**Problem:** The `onFrameMove` callback fires on drag *end*, not during drag. To show a live preview during drag, we need a drag-move signal. Dockview's `fg.overlay.onDidChange` fires continuously during drag — the backend already subscribes to `onDidChangeEnd` for the move callback. We need to also subscribe to `onDidChange` and expose it:

New backend callback:

```typescript
onFrameDragMove(cb: (key: string, pos: { x: number; y: number }) => void): void;
```

Fires continuously during drag (throttled to rAF). The wire function uses this to update the snap preview overlay. This is the only backend interface addition — and it's an additive callback, not a structural change.

### Unsnap on drag

When a snapped frame is dragged away from its zone, the `onFrameDragMove` callback detects that the position is no longer within the zone threshold and calls `engine.unsnapFrame(key)`.

### Double-click to maximise/restore

New backend callback:

```typescript
onTitlebarDoubleClick(cb: (key: string) => void): void;
```

The backend's `injectFrameChrome()` adds a `dblclick` listener on the titlebar that fires this callback. Consistent with `onFrameClose`/`onFramePin` — the backend detects the gesture, the wire function handles the logic.

The snap preview handler (or wire function) subscribes:
- If frame is not snapped to `full`: call `engine.snapFrame(key, "full", canvasSize)`
- If frame is snapped to `full`: call `engine.unsnapFrame(key)` — engine restores pre-snap position/size internally

### Container resize handling

The snap preview module sets up a `ResizeObserver` on the overlay container internally. On resize: calls `engine.recomputeSnappedFrames(newCanvasSize)`. Cleanup via the AbortSignal passed to the module factory.

### Event contract additions

| Event | Detail | When |
|-------|--------|------|
| `pages-frame-snap` | `{ frameKey, zone }` | Frame snapped to zone |
| `pages-frame-unsnap` | `{ frameKey }` | Frame unsnapped |

## 4. Keyboard Shortcuts (#307)

### Module: `frame-keyboard.ts`

Companion module alongside `frame-spatial-nav.ts`, `frame-zorder.ts`, `frame-organisers.ts`, `frame-boundaries.ts`.

```typescript
export function createFrameKeyboardHandler(
  engine: FloatingFrameEngine,
  container: HTMLElement,
  signal: AbortSignal,
): void;
```

Registers a `keydown` listener on `document`, cleaned up via the AbortSignal. Uses `isInTextInput()` to suppress shortcuts when the user is typing in a text field.

### Key bindings

| Action | Binding | Engine method |
|--------|---------|---------------|
| Spatial nav up | `Alt+ArrowUp` | `engine.focusDirection("up")` |
| Spatial nav down | `Alt+ArrowDown` | `engine.focusDirection("down")` |
| Spatial nav left | `Alt+ArrowLeft` | `engine.focusDirection("left")` |
| Spatial nav right | `Alt+ArrowRight` | `engine.focusDirection("right")` |
| Focus frame by index | `Alt+1` through `Alt+9` | Focus frame at order index N-1 |
| Cycle forward | `Alt+]` | Focus next frame by order |
| Cycle backward | `Alt+[` | Focus previous frame by order |
| Close focused frame | `Alt+W` | `engine.removeFrame(focusedKey)` |
| Toggle pin | `Alt+P` | `engine.togglePin(focusedKey)` |

### Focused frame tracking

The module tracks which frame is currently focused. Updated by:
- `pages-frame-focus` events (from `bringToFront()`)
- Keyboard navigation results (the returned key from `focusDirection()`)

When a shortcut targets "the focused frame" and no frame is focused, the shortcut is a no-op.

### `isInTextInput()` extraction

Extract `isInTextInput()` from `packages/pages-primitives/src/a11y/keyboard-shortcut.ts` to a new shared export:

```
packages/pages-primitives/src/a11y/input-guard.ts
```

Exported from `@casehubio/pages-primitives/a11y` sub-path (side-effect free). Both `KeyboardShortcutMixin` and `frame-keyboard.ts` import from there. `pages-runtime` already has `pages-primitives` as... wait — pages-runtime does NOT depend on pages-primitives.

**Correction:** Since pages-runtime has no pages-primitives dependency, the `isInTextInput()` function must be duplicated or placed somewhere both can reach. Options:
- Duplicate the 10-line function in `frame-keyboard.ts` (pragmatic, no dependency change)
- Move it to `pages-component` (framework-agnostic, both packages depend on it)

The function is 10 lines and stable. Duplicate it in `frame-keyboard.ts` — avoids adding a cross-package dependency for a trivial utility.

### Activation wiring

In the activation callback, after `wireFloatingWorkspace()`:

```typescript
createFrameKeyboardHandler(handle.engine, overlayContainer, abortController.signal);
```

## 5. Frame Animations (#308)

### Enter/exit CSS animations

Injected as a separate style element with `data-pages-frame-animations` marker (backend-agnostic). Idempotent injection, same pattern as `data-pages-dockview-css`.

```css
@keyframes frame-enter {
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: scale(1); }
}

@keyframes frame-exit {
  from { opacity: 1; transform: scale(1); }
  to { opacity: 0; transform: scale(0.95); }
}

.frame-entering {
  animation: frame-enter var(--pages-frame-transition-duration, 200ms) ease forwards;
}

.frame-exiting {
  animation: frame-exit var(--pages-frame-transition-duration, 200ms) ease forwards;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .frame-entering, .frame-exiting {
    animation: none;
  }
}
```

### Enter animation

In `dockview-backend.ts`, after `injectFrameChrome()` creates the group element, add `.frame-entering` class. Remove it after `animationend` fires (or immediately if `prefers-reduced-motion` is active).

### Exit animation

In the animation module's close handler (composed by the wire function), before calling `engine.removeFrame()`:
1. Find the group's DOM element
2. Add `.frame-exiting` class
3. Wait for `animationend` event (or skip if `prefers-reduced-motion`)
4. Then call `engine.removeFrame()` which triggers backend panel removal

### Organiser preset and snap animations

When the engine applies a preset or snaps a frame, the wire function calls `element.animate()` on the Dockview floating group element:

```typescript
groupElement.animate(
  [
    { left: `${oldPos.x}px`, top: `${oldPos.y}px`, width: `${oldSize.width}px`, height: `${oldSize.height}px` },
    { left: `${newPos.x}px`, top: `${newPos.y}px`, width: `${newSize.width}px`, height: `${newSize.height}px` },
  ],
  {
    duration: parseInt(getComputedStyle(groupElement).getPropertyValue('--pages-frame-transition-duration') || '200'),
    easing: 'ease',
  },
);
```

Web Animations API runs to completion as a one-shot. No persistent CSS transition declarations — no interference with subsequent drag operations.

### Animation bridge

The animation module needs access to Dockview group DOM elements to run `element.animate()`. New backend method:

```typescript
getFrameElement(key: string): HTMLElement | null;
```

Returns the floating group's root element. Read-only — no state mutation, no architectural concern. Used by the wire function for animation and snap preview positioning.

### `prefers-reduced-motion` handling

```typescript
const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
```

Checked before any JavaScript animation (`element.animate()`). CSS handles its own via the `@media` query. The `matchMedia` object is stored in the animation module scope and can be listened to for runtime changes.

## 6. Event Contract Summary

New reserved framework event names to add to `pages-event-contract.md`:

| Event name | Purpose | Dispatched by |
|------------|---------|---------------|
| `pages-frame-detach` | Frame popped out to child window | Wire function (detach handler) |
| `pages-frame-reattach` | Frame returned from child window | Wire function (reattach handler) |
| `pages-frame-snap` | Frame snapped to edge zone | Wire function (snap handler) |
| `pages-frame-unsnap` | Frame unsnapped from zone | Wire function (unsnap handler) |
| `pages-frame-organise` | Layout preset applied | Already exists |

## 7. Type Changes

### FrameLayout (pages-component)

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
  readonly detached?: boolean;       // NEW — frame is in a pop-out window
  readonly snappedZone?: SnapZone;   // NEW — frame is locked to an edge zone
}
```

Both fields are optional — old saved layouts without them load without issue.

### SnapZone (pages-component)

```typescript
export type SnapZone = "left" | "right" | "top" | "bottom"
  | "top-left" | "top-right" | "bottom-left" | "bottom-right"
  | "full";
```

### FloatingFrameEngine additions

```typescript
setDetached(key: string, detached: boolean): void;
snapFrame(key: string, zone: SnapZone, canvasSize: Size): void;
unsnapFrame(key: string): void;
recomputeSnappedFrames(canvasSize: Size): void;
```

### FloatingFrameBackend additions

```typescript
onFrameDragMove(cb: (key: string, pos: { x: number; y: number }) => void): void;
onTitlebarDoubleClick(cb: (key: string) => void): void;
getFrameElement(key: string): HTMLElement | null;
```

All three are additive — no breaking changes.

## 8. Testing Strategy

### Unit tests

| File | Coverage |
|------|----------|
| `frame-boundaries.test.ts` | Extend — `snapToZone()` (edge detection, corner priority, threshold), `zoneToRect()` (all 9 zones, gap handling) |
| `frame-keyboard.test.ts` | **New** — key binding dispatch, `isInTextInput()` guard, focused frame tracking, no-op when no focus |
| `floating-frame-engine.test.ts` | Extend — `setDetached()`, `snapFrame()`/`unsnapFrame()`, `recomputeSnappedFrames()` |
| `wire-floating-workspace.test.ts` | Extend — detach/reattach flow, snap preview, animation bridge calls |
| `dockview-backend.test.ts` | Extend — `onFrameDragMove` callback, `getFrameElement()`, double-click titlebar |

### Playwright e2e

Extend `examples/e2e/floating-workspace.spec.ts`:

| Test | What it verifies |
|------|-----------------|
| Detach opens child window | Click detach button, verify child window appears with content |
| Reattach restores frame | Reattach from child window, verify frame reappears |
| Organiser toolbar applies preset | Click grid button, verify frame positions |
| Toolbar hidden with 1 frame | Single frame, verify toolbar not visible |
| Snap to left half | Drag to left edge, verify zone preview + snap |
| Snap survives resize | Snap frame, resize browser, verify proportional resize |
| Double-click maximize/restore | Double-click titlebar, verify full zone, double-click again restores |
| Alt+Arrow spatial nav | Press Alt+Right, verify focus moves |
| Alt+1 focuses first frame | Press Alt+1, verify first frame focused |
| Enter animation plays | Create frame, verify opacity transition |
| prefers-reduced-motion | Set preference, verify no animations |

## 9. File Impact Summary

| File | Change |
|------|--------|
| **pages-component** | |
| `src/model/types.ts` | Add `SnapZone` type, add `detached?` and `snappedZone?` to `FrameLayout` |
| **pages-runtime** | |
| `src/frame-boundaries.ts` | Add `snapToZone()`, `zoneToRect()` |
| `src/frame-boundaries.test.ts` | Extend — snap zone tests |
| `src/frame-detach-handler.ts` | **New** — child window lifecycle, EventRelay, DetachRegistry |
| `src/frame-detach-handler.test.ts` | **New** — detach/reattach flow tests |
| `src/frame-snap-preview.ts` | **New** — snap preview overlay, drag tracking, ResizeObserver |
| `src/frame-snap-preview.test.ts` | **New** — snap preview tests |
| `src/frame-animations.ts` | **New** — CSS injection, Web Animations bridge, prefers-reduced-motion |
| `src/frame-animations.test.ts` | **New** — animation tests |
| `src/frame-keyboard.ts` | **New** — keyboard handler factory |
| `src/frame-keyboard.test.ts` | **New** — keyboard handler tests |
| `src/floating-frame-engine.ts` | Spread refactor for all FrameLayout construction sites; add `setDetached()`, `snapFrame()` (with pre-snap capture), `unsnapFrame()` (with pre-snap restore), `recomputeSnappedFrames()` |
| `src/floating-frame-engine.test.ts` | Extend — new engine method tests |
| `src/floating-frame-backend.ts` | Add `onFrameDragMove()`, `onTitlebarDoubleClick()`, `getFrameElement()` to interface |
| `src/dockview-backend.ts` | Implement new callbacks/methods; double-click listener in `injectFrameChrome()`; enter animation class |
| `src/dockview-backend.test.ts` | Extend — new method/callback tests |
| `src/frame-organisers.ts` | Spread refactor for `withLayout()` |
| `src/wire-floating-workspace.ts` | Compose detach handler, snap preview, animation module; pass detach extraButton |
| `src/wire-floating-workspace.test.ts` | Extend — composition tests |
| `src/activation.ts` | Wire `createFrameKeyboardHandler()`, `createOrganiserToolbar()` |
| `src/site.ts` | `scheduleLayoutSave()` for new events (`pages-frame-detach`, `pages-frame-reattach`, `pages-frame-snap`, `pages-frame-unsnap`) |
| `src/index.ts` | Re-export new types and functions |
| **docs** | |
| `docs/protocols/casehub/pages-event-contract.md` | Add detach/reattach/snap/unsnap events to reserved names |
| **examples** | |
| `e2e/floating-workspace.spec.ts` | Extend — new e2e tests |

**Total: 8 new files (+ 4 test files), 12 modified files.**
