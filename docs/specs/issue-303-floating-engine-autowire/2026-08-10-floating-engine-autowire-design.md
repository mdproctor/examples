# FloatingFrameEngine: Auto-Wire, Pluggable Chrome, and Fixes

**Issue:** casehubio/casehub-pages#303
**Date:** 2026-08-10

## Overview

The FloatingFrameEngine (landed in #46) requires consumers to manually wire backend callbacks to engine state mutations and DOM event dispatch — identical boilerplate for every consumer. The engine also has a stale-position bug: `captureLayout()` returns creation-time positions because backend move/resize events never update the engine's internal frames map.

This spec addresses four items:
1. **Wire function** — extracts integration boilerplate into a reusable `wireFloatingWorkspace()` function
2. **Pluggable chrome** — `extraButtons` config on `attach()` for consumer-specific titlebar buttons
3. **Pin state + drag lock** — visual pin feedback with accessibility, and drag prevention for pinned frames
4. **Tab-bar drag fix** — empty tab-bar area should not initiate drag

**Out of scope:** Per-frame tiling dropdown (deferred to follow-up issue — see D5 in decisions.md).

## Key Decisions

- **Engine stays pure.** No DOM dependency added. A separate wire function handles all callback→event→state integration. Engine testability preserved.
- **Backend callbacks for close/pin.** `onFrameClose` and `onFramePin` added to `FloatingFrameBackend` alongside existing callbacks. Chrome buttons fire callbacks instead of dispatching DOM events directly.
- **Pin state: opacity + aria-pressed + CSS class.** Three complementary signals for visual, accessible, and consumer-customizable pin indication.
- **Extra buttons on attach().** Global to the backend instance — every frame gets the same buttons.
- **Dockview API first for drag lock and tab-bar fix.** Investigate `group.locked` and config options before CSS overrides.

## 1. Wire Function

### Interface

```typescript
export interface WireHandle {
  readonly engine: FloatingFrameEngine;
  dispose(): void;
}

export function wireFloatingWorkspace(
  backend: FloatingFrameBackend,
  container: HTMLElement,
  savedLayout?: readonly FrameLayout[],
): WireHandle;
```

### Behavior

The wire function:

1. Creates the engine: `createFloatingFrameEngine(backend, savedLayout)`
2. Subscribes to all backend callbacks:
   - `onFrameMove(key, pos)` → `engine.updatePosition(key, pos)`, dispatches `pages-frame-move` with `{ frameKey, position }`
   - `onFrameResize(key, size)` → `engine.updateSize(key, size)`, dispatches `pages-frame-resize` with `{ frameKey, size }`
   - `onFrameClose(key)` → `engine.removeFrame(key)`, dispatches `pages-frame-close` with `{ frameKey }`
   - `onFramePin(key)` → `engine.togglePin(key)`, reads new pinned state from `engine.frames`, calls `backend.updatePinState(key, pinned)`, dispatches `pages-frame-pin` with `{ frameKey, pinned }`
   - `onTabDragOut(fromFrame, tabKey, position)` → creates new frame with unique key, calls `engine.moveTab(fromFrame, tabKey, newKey)`, auto-closes empty source frame, dispatches `pages-tab-drag-out` with `{ tabKey, fromFrame, position }`
   - `onTabReorder(frameKey, tabKeys)` → dispatches `pages-tab-reorder` with `{ frameKey, tabKeys }`
3. Returns a `WireHandle` with the engine reference and a `dispose()` that calls `engine.dispose()`

All CustomEvents dispatched with `bubbles: true, composed: true` on the container element — consistent with the existing event contract.

### Stale-Position Fix

The wire function's `onFrameMove` and `onFrameResize` handlers call `engine.updatePosition()` and `engine.updateSize()` respectively. These are state-only engine methods (no backend calls) that update the internal frames map. After this fix, `captureLayout()` returns positions and sizes that reflect the last drag/resize, not the creation-time values.

### Impact on Activation

The 24 lines of manual callback→CustomEvent wiring in `activation.ts` (lines 718–741) collapse to:

```typescript
const handle = wireFloatingWorkspace(backend, overlayContainer, wsRef?.stash);
if (wsRef) {
  wsRef.engine = handle.engine;
  wsRef.stash = undefined;
}
```

### Impact on Site.ts

The `pages-frame-close`, `pages-frame-pin`, and `pages-tab-drag-out` event handlers (lines 944–979) that call engine methods are removed — the wire function already handled the state mutations. site.ts retains listeners only for `scheduleLayoutSave()` on state-changing events (`pages-frame-close`, `pages-frame-pin`, `pages-frame-move`, `pages-frame-resize`, `pages-tab-drag-out`, `pages-tab-reorder`).

## 2. Backend Interface Changes

### New Callbacks

```typescript
onFrameClose(cb: (key: string) => void): void;
onFramePin(cb: (key: string) => void): void;
```

Added to `FloatingFrameBackend` alongside the existing `onFrameMove`, `onFrameResize`, `onTabDragOut`, `onTabReorder` callbacks.

### New Method

```typescript
updatePinState(key: string, pinned: boolean): void;
```

Called by the wire function after `engine.togglePin()` to update pin visual state and drag lock in the backend.

### Extended Attach Signature

```typescript
export interface FrameButtonConfig {
  readonly icon: string;
  readonly title: string;
  readonly className?: string;
  readonly onClick: (frameKey: string) => void;
}

export interface BackendAttachOptions {
  readonly extraButtons?: readonly FrameButtonConfig[];
}

// Updated signature
attach(container: HTMLElement, contentFactory: ContentFactory, options?: BackendAttachOptions): void;
```

### DockviewBackend Implementation

**`injectFrameChrome` changes:**
- Close dot click handler fires `onFrameClose` callbacks instead of dispatching `pages-frame-close` CustomEvent
- Pin button click handler fires `onFramePin` callbacks instead of dispatching `pages-frame-pin` CustomEvent
- After built-in buttons (close, pin), iterates stored `extraButtons` array. Each `FrameButtonConfig` produces a `<span>` element with:
  - `textContent` or `innerHTML` set from `icon`
  - `title` attribute from `title`
  - `className` from `className` (if provided), prefixed with `frame-extra-btn`
  - `pointerdown` handler calls `stopPropagation()` (prevents drag)
  - `click` handler calls `onClick(frameKey)`

**Chrome button order in titlebar:** close dot → pin button → extra buttons (left to right).

**`updatePinState` implementation:**
- Finds the group's pin button element
- Sets `opacity: 1` (pinned) or `opacity: 0.5` (unpinned)
- Toggles `aria-pressed` attribute (`"true"` / `"false"`)
- Toggles `.frame-pin-active` CSS class
- Applies drag lock (see §4)

**Callback arrays:** `framePinCallbacks` and `frameCloseCallbacks` arrays added alongside existing callback arrays. Cleared in `dispose()`.

## 3. Engine Interface Changes

Two new state-only methods:

```typescript
export interface FloatingFrameEngine {
  // ... existing methods unchanged ...

  updatePosition(key: string, pos: { x: number; y: number }): void;
  updateSize(key: string, size: { width: number; height: number }): void;
}
```

These update the internal `frames` map without calling the backend — the backend already knows the position/size (it reported them). They exist solely for the wire function to keep the engine's state in sync with backend-reported geometry.

Implementation: read the existing `FrameLayout` from the frames map, create a new `FrameLayout` with the updated position/size, set it back. Same immutable-update pattern as `togglePin` and other state mutations.

## 4. Pin Drag Lock

### Primary Approach — Dockview `group.locked`

In `updatePinState(key, pinned)`:

```typescript
const group = frameGroups.get(key);
if (group) {
  group.locked = pinned;
}
```

Dockview's `group.locked` property is in the TypeScript type definitions. If it prevents floating group drag (needs verification during implementation), this is the complete solution.

### Fallback — `pointerdown stopPropagation`

If `group.locked` only prevents tab drag within the group (not floating group repositioning), add a named `pointerdown` handler on the titlebar:

```typescript
function lockDragHandler(e: PointerEvent) {
  e.stopPropagation();
}

// In updatePinState:
const titlebar = el.querySelector(".dv-floating-titlebar");
if (pinned) {
  titlebar.addEventListener("pointerdown", lockDragHandler, { capture: true });
} else {
  titlebar.removeEventListener("pointerdown", lockDragHandler, { capture: true });
}
```

Chrome buttons already have their own `pointerdown stopPropagation` handlers, so they are unaffected. The capture-phase handler on the titlebar prevents drag initiation while preserving hover effects, context menus, and other pointer interactions on the titlebar itself.

## 5. Tab-Bar Drag Fix

### Primary Approach — Dockview Config

Check whether the floating group's `locked` property (from §4) also suppresses tab-bar empty-space drag. If setting `locked` on non-pinned floating groups is inappropriate, check `disableDnd` or panel-level options.

### Fallback — CSS Override

If no Dockview config covers this case, inject CSS alongside the existing Dockview CSS in `createDockviewBackend()`:

```css
.dv-floating-group .dv-tabs-container {
  pointer-events: none;
}
.dv-floating-group .dv-tabs-container .dv-tab {
  pointer-events: auto;
}
```

Exact class names to be verified against Dockview's actual DOM structure during implementation. The CSS is injected into the same `<style>` element (with `data-pages-dockview-css` marker) for idempotency.

## 6. Event Contract Updates

No new events. All existing floating workspace events from the event contract protocol remain unchanged:

| Event | Detail | Dispatched by (before) | Dispatched by (after) |
|-------|--------|----------------------|---------------------|
| `pages-frame-close` | `{ frameKey }` | Backend chrome | Wire function |
| `pages-frame-pin` | `{ frameKey, pinned }` | Backend chrome | Wire function |
| `pages-frame-move` | `{ frameKey, position }` | Activation callback | Wire function |
| `pages-frame-resize` | `{ frameKey, size }` | Activation callback | Wire function |
| `pages-tab-drag-out` | `{ tabKey, fromFrame, position }` | Activation callback | Wire function |
| `pages-tab-reorder` | `{ frameKey, tabKeys }` | Activation callback | Wire function |

The wire function becomes the single dispatcher of all floating workspace DOM events. The event contract protocol (`pages-event-contract.md`) reserved names table is unchanged.

**`pages-frame-pin` detail enrichment:** Previously `{ frameKey }` only. Now includes `{ frameKey, pinned: boolean }` — the new pin state after toggling. Consumers can react to the actual state instead of tracking it themselves.

## 7. Testing Strategy

### Unit Tests

| File | Coverage |
|------|----------|
| `wire-floating-workspace.test.ts` | **New** — callback→event dispatch, engine state sync (position/size), close/pin handling, tab-drag-out flow, dispose cleanup |
| `floating-frame-engine.test.ts` | Extend — `updatePosition()`, `updateSize()` state-only mutations |
| `dockview-backend.test.ts` | Extend — `onFrameClose`/`onFramePin` callback firing, `updatePinState` (opacity, aria-pressed, CSS class, drag lock), `extraButtons` rendering |
| `site.test.ts` | Update — verify simplified event handling (layout save only, no engine mutations) |
| `activation.test.ts` | Update — verify `wireFloatingWorkspace()` integration replaces manual wiring |

### Playwright e2e

Extend `examples/e2e/floating-workspace.spec.ts`:

| Test | What it verifies |
|------|-----------------|
| Position persists after drag | Drag frame, verify `captureLayout()` returns new position (stale-position fix) |
| Pin visual state | Pin frame, verify opacity + aria-pressed on pin button |
| Pinned frame not draggable | Pin frame, attempt drag, verify position unchanged |
| Tab-bar empty area not draggable | Click/drag in empty tab-bar space, verify no frame movement |

## 8. File Impact Summary

| File | Change |
|------|--------|
| **pages-runtime** | |
| `src/floating-frame-backend.ts` | Add `onFrameClose`, `onFramePin`, `updatePinState` to interface; add `FrameButtonConfig`, `BackendAttachOptions` types |
| `src/floating-frame-engine.ts` | Add `updatePosition(key, pos)` and `updateSize(key, size)` to engine interface and implementation |
| `src/wire-floating-workspace.ts` | **New** — wire function + `WireHandle` type |
| `src/wire-floating-workspace.test.ts` | **New** — wire function tests |
| `src/dockview-backend.ts` | Implement new callbacks/methods; `injectFrameChrome` uses callbacks instead of DOM events; `extraButtons` loop; `updatePinState` with drag lock; tab-bar drag fix CSS |
| `src/dockview-backend.test.ts` | Extend — new callback/method tests |
| `src/floating-frame-engine.test.ts` | Extend — updatePosition/updateSize tests |
| `src/activation.ts` | Replace manual wiring with `wireFloatingWorkspace()` call |
| `src/site.ts` | Remove engine-mutation event handlers; retain `scheduleLayoutSave()` listeners only |
| `src/site.test.ts` | Update — reflect simplified event handling |
| `src/index.ts` | Re-export `wireFloatingWorkspace`, `WireHandle`, `FrameButtonConfig`, `BackendAttachOptions` |

**Total: 2 new files, 9 modified files.**
