# Workspace Compositor — Design Spec

**Date:** 2026-08-13
**Scope:** Splits, tabs, and accordion view for the floating workspace

## Overview

The current floating workspace is a single implicit tab with one `FloatingFrameEngine`. This design adds a compositor layer above the engine that manages:

- **Regions**: the workspace is optionally split into two independent regions (H or V). Max 2 regions, no nesting.
- **Tabs**: each region holds 1..N named tabs. Each tab is its own workspace with its own `FloatingFrameEngine` and frames.
- **View modes**: each region independently toggles between tab view (one tab visible, fills available space) and accordion view (all tabs visible as collapsible/expandable stacked sections with resizable dividers, per-region scroll).

### Constraints

- Starts as a single tab — backward compatible with today's single workspace
- No nested splits — one split, two regions, that's it
- Content-agnostic — the compositor manages layout, not content (per workbench-integration-pattern protocol)
- Existing `FloatingFrameEngine`, `wireFloatingWorkspace()`, and `FloatingFrameBackend` stay unchanged

## 1. Compositor Architecture (D1)

A new `WorkspaceCompositor` module sits above `FloatingFrameEngine`. The compositor owns:

- Region tree (root is leaf or split)
- Tab lifecycle per region (create, close, rename, reorder, cross-region move)
- View mode toggle (tab ↔ accordion) per region
- Cross-tab frame transfer (coordinate between two engines)
- Persistence of the full compositor state

Each tab creates its own `wireFloatingWorkspace()` call, producing its own engine, backend, toolbar, and keyboard handler. The compositor coordinates between them.

### Module decomposition

| Module | Responsibility |
|--------|---------------|
| `workspace-compositor.ts` | **New** — compositor state, region/tab lifecycle, cross-tab transfer |
| `compositor-renderer.ts` | **New** — DOM rendering: region containers, split resize handle, tab bar, accordion sections |
| `compositor-persistence.ts` | **New** — capture/restore compositor state to/from `LayoutState` |
| `compositor-drag.ts` | **New** — tab drag: reorder within region, drag to edge (split creation), cross-region move, cross-tab frame transfer |

## 2. Region Model (D2)

```typescript
type Region = LeafRegion | SplitRegion;

interface LeafRegion {
  readonly type: "leaf";
  readonly id: string;
  readonly tabs: readonly Tab[];
  readonly activeTabId: string;
  readonly viewMode: "tab" | "accordion";
  readonly accordionHeights?: Readonly<Record<string, number>>;
}

interface SplitRegion {
  readonly type: "split";
  readonly direction: "h" | "v";
  readonly ratio: number;
  readonly children: [LeafRegion, LeafRegion];
}

interface Tab {
  readonly id: string;
  readonly name: string;
}
```

- Root is either a `LeafRegion` (no split) or a `SplitRegion` with exactly two leaf children
- `type` discriminator enables serialization/deserialization
- Max 2 regions — explicit constraint, no N-ary splits
- Tab drag to edge: current leaf becomes a split, dragged tab goes into a new leaf on that side
- Last tab closed: split collapses — sibling leaf becomes the new root

### Runtime state

The compositor maintains a parallel map of `tabId → WireHandle` (the return from `wireFloatingWorkspace()`). Each tab's engine is created lazily when the tab is first activated, and disposed when the tab is closed.

## 3. Tab Bar UI (R4)

Each region renders a tab bar at the top of its container:

```html
<div class="pages-compositor-tabbar" data-region-id="...">
  <div class="tabbar-tabs">
    <button class="tab-header" data-tab-id="..." draggable="true">
      <span class="tab-name" contenteditable="false">Tab 1</span>
      <span class="tab-close">×</span>
    </button>
    <!-- ... more tabs ... -->
  </div>
  <button class="tab-add" title="New tab">+</button>
  <button class="tab-view-toggle" title="Toggle accordion view">☰</button>
</div>
```

- Tab headers are draggable for reorder and cross-region move
- Double-click tab name to rename (sets `contenteditable="true"`, blurs to confirm)
- Close button on each tab (blocked if it's the last tab in the last region — region collapse handles the rest)
- Add button creates a new empty tab with auto-name "Tab N"
- View toggle switches between tab and accordion mode for that region
- Plain DOM, no Lit — consistent with `injectFrameChrome()` and organiser toolbar patterns
- Uses design tokens: `--pages-neutral-2` background, `--pages-neutral-4` border, `--pages-accent-3` active tab

## 4. Tab View Mode

In tab view, only the active tab's workspace is visible. All other tabs' containers are `display: none`. Their engines stay alive — frames, z-order, snap state are preserved. Switching tabs shows/hides containers, no re-rendering.

The active tab's workspace fills the full available height of the region (below the tab bar).

## 5. Accordion View Mode (D5)

In accordion view, all tabs are visible simultaneously as collapsible/expandable stacked sections:

```html
<div class="pages-compositor-accordion" style="overflow-y: auto;">
  <div class="accordion-section" data-tab-id="...">
    <div class="accordion-header">
      <button class="accordion-toggle">▼</button>
      <span class="accordion-name">Tab 1</span>
    </div>
    <div class="accordion-content" style="flex-basis: 400px;">
      <!-- tab's floating workspace renders here -->
    </div>
  </div>
  <div class="accordion-resize-handle"></div>
  <div class="accordion-section" data-tab-id="...">
    <!-- ... -->
  </div>
</div>
```

- Container is `display: flex; flex-direction: column; overflow-y: auto` — per-region scroll
- Each section's content area uses stored height as `flex-basis`
- Collapsed sections set `flex: 0 0 auto` (header-only)
- Drag handles between sections adjust `flex-basis` values
- Accordion heights persist in `LeafRegion.accordionHeights` (keyed by tab ID)
- Switching to tab view preserves accordion heights; tab view always fills available space
- Each section's `FloatingFrameEngine` uses its own overlay container — `canvasSize` from `getBoundingClientRect()` naturally scopes snap zones and organiser presets to the section's dimensions
- Collapsed sections: engine stays alive, overlay is `display: none`
- New tab created in accordion view: section appears expanded at the bottom with default height

## 6. Split Creation and Collapse (D6)

### Split creation — tab drag to edge

When a tab header drag starts, the compositor shows semi-transparent drop zones at the four edges of the current region:

- Top/bottom edges → horizontal split
- Left/right edges → vertical split

Dragging over a zone highlights it. Dropping on a zone:

1. The current `LeafRegion` becomes a `SplitRegion`
2. The existing tabs (minus the dragged tab) stay in one child leaf
3. The dragged tab goes into a new child leaf on the drop side
4. Default ratio: 50/50
5. The new leaf's tab gets its own `wireFloatingWorkspace()` call

Drag priority (highest to lowest):
1. Edge drop zone (split creation)
2. Cross-region tab bar (cross-region tab move)
3. Tab reorder within region

### Split resize

The split has a single resize handle between the two regions. Dragging it adjusts the `ratio` (0.0–1.0). The handle uses the same drag pattern as accordion resize handles.

### Split collapse

When the last tab in a leaf region is closed:
1. All engines in the closing leaf are disposed
2. The sibling leaf becomes the new root
3. The sibling expands to fill the full workspace

## 7. Cross-Tab Frame Transfer (D3)

When a user drags a frame from one tab to another (same or different region):

1. **Snapshot** restorable DOM state from the source frame:
   ```typescript
   interface TransferSnapshot {
     readonly scrollPositions: ReadonlyArray<{ selector: string; top: number; left: number }>;
     readonly focusedSelector: string | null;
     readonly inputValues: ReadonlyArray<{ selector: string; value: string }>;
     readonly selectionRange: { selector: string; start: number; end: number } | null;
   }
   ```
2. **Capture** the frame's `FrameTabConfig[]` from the source engine
3. **Remove** the frame from the source engine (`engine.removeFrame()`)
4. **Create** the frame in the target engine (`engine.createFrame()`) at the drop position
5. **Content factory** re-renders the content in the target engine's container
6. **Restore** snapshot state on the new DOM elements

### Detached frame handling (R2)

If the source frame is detached (in a pop-out window), transfer reattaches it first: close child window → `engine.showFrame()` → then proceed with normal transfer. Detached frames in the target tab are not affected.

### Drop target detection

During frame drag, the compositor checks whether the drag position exits the current tab's container bounds. If it enters another tab's container (in tab view: only the active tab is a valid target; in accordion view: any expanded section), the compositor highlights the target and accepts the drop.

## 8. Persistence Model (D4)

### CompositorState

```typescript
interface CompositorState {
  readonly region: LeafRegionState | SplitRegionState;
}

interface LeafRegionState {
  readonly type: "leaf";
  readonly id: string;
  readonly tabs: readonly TabState[];
  readonly activeTabId: string;
  readonly viewMode: "tab" | "accordion";
  readonly accordionHeights?: Readonly<Record<string, number>>;
}

interface TabState {
  readonly id: string;
  readonly name: string;
  readonly frames: readonly FrameLayout[];
}

interface SplitRegionState {
  readonly type: "split";
  readonly direction: "h" | "v";
  readonly ratio: number;
  readonly children: [LeafRegionState, LeafRegionState];
}
```

### LayoutState extension

```typescript
interface LayoutState {
  readonly splits: Readonly<Record<string, readonly number[]>>;
  readonly docks: Readonly<Record<string, boolean>>;
  readonly panels: Readonly<Record<string, PanelEntry>>;
  readonly zones?: Readonly<Record<string, DockZone>>;
  readonly frames?: readonly FrameLayout[];
  readonly compositor?: CompositorState;  // NEW
}
```

### Migration

- `compositor` absent + `frames` present: load into a single-tab, single-region compositor (backward compat)
- `compositor` present: load from compositor state, ignore `frames`
- Neither present: empty workspace

### Capture/restore flow

`captureCompositorState()`: walks the region tree, calls `engine.captureLayout()` for each tab's engine, assembles `CompositorState`.

`restoreCompositorState()`: walks the `CompositorState`, creates regions/tabs/engines, calls `engine.restoreLayout()` per tab.

## 9. Activation Integration

The floating workspace activation callback in `activation.ts` changes to:

1. Check if a compositor state exists in the seed layout
2. If yes: create a `WorkspaceCompositor`, which manages all region/tab/engine lifecycle
3. If no: create a `WorkspaceCompositor` with a single-tab single-region default (backward compat — behaves identically to today's single engine)

The compositor replaces the current direct `wireFloatingWorkspace()` call. The compositor internally calls `wireFloatingWorkspace()` per tab.

## 10. Event Contract

New compositor-level events dispatched on the workspace container:

| Event name | Detail | When |
|------------|--------|------|
| `pages-compositor-tab-create` | `{ regionId, tabId, name }` | New tab created |
| `pages-compositor-tab-close` | `{ regionId, tabId }` | Tab closed |
| `pages-compositor-tab-rename` | `{ tabId, oldName, newName }` | Tab renamed |
| `pages-compositor-tab-activate` | `{ regionId, tabId }` | Tab switched |
| `pages-compositor-tab-move` | `{ tabId, fromRegionId, toRegionId }` | Tab moved cross-region |
| `pages-compositor-split` | `{ direction, fromRegionId }` | Split created |
| `pages-compositor-collapse` | `{ removedRegionId, survivingRegionId }` | Split collapsed |
| `pages-compositor-view-mode` | `{ regionId, mode }` | View mode toggled |
| `pages-frame-transfer` | `{ frameKey, fromTabId, toTabId }` | Frame moved cross-tab |

## 11. Testing Strategy

### Unit tests

| File | Coverage |
|------|----------|
| `workspace-compositor.test.ts` | **New** — region/tab lifecycle, split/collapse, view mode toggle, cross-tab transfer |
| `compositor-persistence.test.ts` | **New** — capture/restore round-trip, migration from legacy `frames`, discriminator deserialization |
| `compositor-drag.test.ts` | **New** — edge zone detection, drag priority, cross-region tab move |

### Integration tests

- Single tab backward compat: compositor with one tab behaves identically to current single-engine workspace
- Split + tab lifecycle: create split, add tabs, close tabs, collapse split
- Accordion: expand/collapse sections, resize, height persistence, per-region scroll
- Cross-tab frame transfer: frame moves between tabs with scroll position preservation
- Persistence round-trip: full compositor state saves and restores correctly

## 12. File Impact Summary

| File | Change |
|------|--------|
| **pages-component** | |
| `src/model/types.ts` | Add `CompositorState`, `LeafRegionState`, `SplitRegionState`, `TabState` types; add `compositor?` to `LayoutState` |
| **pages-runtime** | |
| `src/workspace-compositor.ts` | **New** — compositor state management, region/tab lifecycle |
| `src/workspace-compositor.test.ts` | **New** — compositor unit tests |
| `src/compositor-renderer.ts` | **New** — DOM rendering for regions, tab bar, accordion |
| `src/compositor-renderer.test.ts` | **New** — renderer tests |
| `src/compositor-persistence.ts` | **New** — capture/restore, migration |
| `src/compositor-persistence.test.ts` | **New** — persistence tests |
| `src/compositor-drag.ts` | **New** — tab drag, frame transfer, edge detection |
| `src/compositor-drag.test.ts` | **New** — drag tests |
| `src/activation.ts` | Modify — compositor integration in floating workspace activation |
| `src/site.ts` | Modify — `scheduleLayoutSave()` for compositor events; `captureLayout()` delegates to compositor |
| `src/index.ts` | Re-export compositor types and factory |
| **docs** | |
| `docs/protocols/casehub/pages-event-contract.md` | Add compositor events to reserved names |

**Total: 8 new files (+ 4 test files), 4 modified files.**
