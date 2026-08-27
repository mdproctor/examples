# Recursive Container Model — Design Spec

**Date:** 2026-08-23
**Issue:** #345
**Scope:** Entry-level Container nesting, tree-walking refactor, persistence

## Overview

The Container migration (#312) landed the i3-style Container model at the frame level — each frame has a `rootContainer` with split-based child Containers. But entries within a Container are still flat `Entry` objects (`{ key, label, contentElement }`). This creates a gap: when a user clicks `+` inside a tabbed container's content area, they get a sibling tab, not a child inside the active tab.

This design closes the gap by giving `Entry` an optional `childContainer`, making the full tree self-describing. A leaf entry renders content directly; a non-leaf entry renders its child Container. The external `FrameState.childContainers: Map<string, Container>` is eliminated in favour of Entry-owned children.

### Constraints

- Backward compatible — existing layouts without nesting load and render unchanged
- Unified depth model — `maxDepth` applies to the full tree regardless of nesting source
- Content-agnostic — nesting is a layout concern, not a content concern (per content-agnostic-workbench protocol)
- Consistent collapse — auto-flatten follows the same pattern as split collapse

## 1. Entry Extension (D1)

The `Container` interface moves from `container.ts` to `types.ts` to co-locate it with `Entry` and avoid a circular type dependency. Both are pure interfaces with no runtime imports.

`Entry` gains an optional field:

```typescript
export interface Entry {
  readonly key: string;
  readonly label: string;
  contentElement?: HTMLElement | undefined;
  contentDispose?: (() => void) | undefined;
  meta?: PerLayoutMeta;
  childContainer?: Container | undefined;  // NEW
}
```

When `childContainer` is set:
- The entry is a **non-leaf** — it renders its child Container instead of raw content
- `contentElement` and `contentDispose` are cleared (the child Container manages its own content)
- The child Container receives `depth: parentContainer.depth + 1`

When `childContainer` is absent or undefined:
- The entry is a **leaf** — it renders content via the `ContentFactory` as today

### Leaf/non-leaf invariant

An entry is always in one of two states: leaf (has contentElement, no childContainer) or non-leaf (has childContainer, no contentElement). The conversion functions enforce this — `containerizeEntry()` clears content before setting childContainer, and `flattenEntry()` clears childContainer before re-creating content via the factory.

## 2. Nesting Trigger — Content-Area + Button (D2, D3)

### User gesture

A nest button (visually distinct from the tab-strip `+` — uses a "⊞" icon or "Nest" label to avoid confusion with the sibling-add and compositor-tab buttons) appears inside each leaf tab's content area. The tab-strip `+` remains "add sibling tab."

- **Visible:** when the entry is a leaf AND `depth < maxDepth`
- **Hidden:** when the entry is already non-leaf (it already has a nested Container with its own tab strip) OR when `depth >= maxDepth`

### Conversion: leaf → non-leaf (containerizeEntry)

When the nest button is clicked on entry `E` in Container `C`:

1. Dispose `E.contentElement` via `E.contentDispose()` (do NOT transfer the DOM element — re-mounting triggers disconnectedCallback/connectedCallback on web components, losing ephemeral state)
2. Create a wrapped entry `W = { key: generateKey(), label: E.label }` (no contentElement — the content factory re-creates it). Copy content identity: `W._content = E._content` (the `_content` property carries the `Component` descriptor that the content factory uses to reconstruct the element)
3. Create a new empty entry `N = { key: generateKey(), label: 'Tab 2' }`
4. Create a child Container `child = createContainer({ entries: [W, N], layout: 'tabbed', depth: C.depth + 1, policy: C.policy, contentFactory: ..., onCollapse: (remaining) => flattenEntry(E, remaining) })`
5. Set `E.childContainer = child`
6. Clear `E.contentElement`, `E.contentDispose`, and `E._content`
7. Mount the child Container into the entry's content area — the content factory re-creates `W`'s content in the new location

The user sees their existing content (re-created via content factory) in the first tab of a new nested tabbed Container, plus an empty second tab. The data pipeline re-delivers datasets via `pages-data-request`, so data state recovers. Ephemeral state (scroll position, ECharts highlights) is lost — acceptable for a one-time structural operation, consistent with cross-tab frame transfer (workspace-compositor D3).

### Content factory delegation

The `contentFactory` for the child Container delegates to the parent's factory for leaf entries. For non-leaf entries (recursive nesting), it mounts the entry's `childContainer`. This is the same pattern used by `createSplitContainer` today — its content factory checks `childContainers.get(entry.key)` and mounts the child. After D1, the check becomes `entry.childContainer`.

## 3. Depth Enforcement (D4)

`ContainerPolicy.maxDepth` counts the full tree depth from root to deepest leaf, regardless of nesting source (split or entry). The existing check in `createContainer()`:

```typescript
if (depth > policy.maxDepth) {
  throw new Error(`Cannot create group at depth ${depth} — maximum nesting depth is ${policy.maxDepth}`);
}
```

...already enforces this. Child Containers created via `Entry.childContainer` pass `parentContainer.depth + 1` as their depth, just as split children do.

The content-area `+` button hides itself when `container.depth >= container.policy.maxDepth`, preventing the user from attempting nesting that would fail.

### DEFAULT_POLICY and maxDepth unification

The current codebase has inconsistent maxDepth values: `createLeafContainer` hardcodes `maxDepth: 3`, `createSplitContainer` hardcodes `maxDepth: 10`. With unified depth counting, maxDepth:3 blocks entry nesting whenever splits are present (root→split→leaf = depth 3, at limit).

Resolution:
- `DEFAULT_POLICY.maxDepth` changes from 3 to **5**
- `DEFAULT_POLICY.allowedLayouts` stays as `["free", "tabbed", "accordion"]` — leaf containers
- A separate `SPLIT_POLICY` constant adds `["splith", "splitv"]` to the allowed set — split containers use this because their layout is set at creation time, not via the user-facing layout switcher
- All containers reference these constants instead of hardcoded inline values
- This allows root(1)→split(2)→leaf(3)→entry-nest(4)→entry-nest(5) — two levels of explicit nesting beyond a split

## 4. Collapse — Auto-Flatten (D6)

When a nested Container's last sibling entry is closed and only one child remains, the entry auto-flattens:

### flattenEntry(parentEntry, remainingChildEntry)

1. Unmount the child Container
2. Copy content identity: `parentEntry._content = remainingChildEntry._content`
3. Dispose the remaining child's content element (do NOT transfer DOM — same rationale as containerizeEntry)
4. Set `parentEntry.childContainer = undefined`
5. Dispose the child Container
6. Re-create content via the content factory in the parent's tab area (the factory reads `_content` to reconstruct the element)

This mirrors the existing split collapse pattern (`group-organiser-backend.ts:297-311`) where the surviving child's Container replaces the split Container as `state.rootContainer`.

### Collapse callback

The child Container is created with an `onCollapse` callback (same mechanism splits use) that triggers `flattenEntry()`. When the child's organiser detects it has collapsed to one entry, it calls `onCollapse(remainingEntry)` — the parent intercepts and flattens.

## 5. Tree-Walking Refactor (D7)

All tree-walking helpers are refactored to walk via `Entry.childContainer`, eliminating the `childMap` parameter:

### Before (current)

```typescript
function findLeafContainer(
  container: Container,
  childMap: Map<string, Container>,
  predicate?: (c: Container) => boolean,
): Container | null {
  if (isSplitLayout(layout)) {
    for (const entry of container.entries) {
      const child = childMap.get(entry.key);
      if (child) { /* recurse */ }
    }
  }
}
```

### After

```typescript
function findLeafContainer(
  container: Container,
  predicate?: (c: Container) => boolean,
): Container | null {
  // Recurse into any child containers first
  for (const entry of container.entries) {
    if (entry.childContainer) {
      const found = findLeafContainer(entry.childContainer, predicate);
      if (found) return found;
    }
  }
  // A container is a leaf target if it has any leaf entries (entries without childContainer)
  const hasLeafEntries = container.entries.some(e => !e.childContainer);
  if (hasLeafEntries && (!predicate || predicate(container))) return container;
  return null;
}
```

**Mixed containers:** A tabbed container can have both leaf entries (no childContainer) and non-leaf entries (with childContainer). This is the normal state after nesting — only one entry is nested, its siblings remain leaves. The leaf test checks for leaf **entries**, not whether the container has **any** children. A container with 3 tabs where 1 is nested and 2 are leaves is still a valid leaf target for tab operations — the 2 leaf entries are directly visible content.

### Affected helpers

| Helper | Change |
|--------|--------|
| `findLeafContainer` | Remove `childMap` param, walk `entry.childContainer` |
| `findContainerWithTab` | Remove `childMap` param, walk `entry.childContainer` |
| `forEachLeafContainer` | Remove `childMap` param, walk `entry.childContainer` |
| `findParentSplitEntry` | Remove `childMap` param, walk `entry.childContainer`. Rename to `findParentEntry` since it's no longer split-specific |

### Call site migration

All ~15 call sites in `group-organiser-backend.ts` need updated logic, not just parameter drops. The `isSplitLayout()` gate that currently controls recursion disappears — the new condition is `entry.childContainer` presence. A tabbed container whose entries have childContainers is no longer a leaf. Each call site must be individually verified. The `state.childContainers` field is removed from `FrameState`.

## 6. Split Creation Refactor

`createSplitContainer` currently stores child Containers in `state.childContainers`. After D1, it stores them on the entries:

### Before

```typescript
for (const { key, child } of childEntries) {
  state.childContainers.set(key, child);
}
```

### After

```typescript
const entries: Entry[] = childEntries.map(({ key, child }) => ({
  key,
  label: key,
  childContainer: child,
}));
```

The content factory for the split Container changes from `state.childContainers.get(entry.key)` to `entry.childContainer`:

```typescript
contentFactory: (entry: Entry) => {
  if (entry.childContainer) {
    const el = document.createElement('div');
    el.style.cssText = 'display:flex;flex-direction:column;height:100%;';
    entry.childContainer.mount(el);
    return { element: el, dispose: () => entry.childContainer!.dispose() };
  }
  return { element: document.createElement('div') };
},
```

### Split collapse refactor

The `onCollapse` callback changes from reading `state.childContainers.get(remainingEntry.key)` to reading `remainingEntry.childContainer`:

```typescript
onCollapse: (remainingEntry) => {
  const remainingChild = remainingEntry.childContainer;
  if (remainingChild) {
    remainingChild.unmount();
    remainingEntry.childContainer = undefined;
    // ... promote remainingChild as rootContainer
  }
}
```

## 7. Persistence (D5)

### Cross-package type placement

`Layout` is a simple string union (`"free" | "tabbed" | ...`) with no runtime dependencies. It moves from `pages-runtime/src/frame-sandbox/types.ts` to `pages-component/src/model/types.ts` alongside the persistence types that need it (`ContainerState`, `FrameLayout`). `pages-runtime` re-exports it for backward compatibility. This avoids a circular package dependency (pages-component → pages-runtime → pages-component).

### Type extension

```typescript
// In pages-component/src/model/types.ts

export type Layout = "free" | "tabbed" | "accordion" | "splith" | "splitv" | "content";

export interface FrameTabConfig {
  readonly key: string;
  readonly label: string;
  readonly content: Component | null;  // null for non-leaf entries
  readonly children?: ContainerState;  // NEW — present for non-leaf entries
}

export interface ContainerState {
  readonly layout: Layout;
  readonly tabs: readonly FrameTabConfig[];
  readonly layoutState?: unknown;
}

export interface FrameLayout {
  // ... existing fields ...
  readonly containerTree?: ContainerState;  // NEW — full recursive tree including root layout
}
```

**Root layout persistence (R1-03):** `FrameLayout` gains `containerTree?: ContainerState`. When present, it captures the full Container tree including the root container's layout type (which may be `splith`/`splitv` — unrepresentable in `viewMode`). When absent, the flat `tabs` array and `viewMode` are used (backward compat). `containerTree` supersedes `tabs` when present.

**FrameTabConfig.content (R1-09):** Changed to `Component | null`. Non-leaf entries serialize `content: null` — the child Container owns the content, not the entry.

`ContainerState.layout` can be any `Layout` value including `splith`/`splitv` — this handles both entry nesting AND split nesting persistence. Frame-level splits were NOT previously persisted; this design fixes that as a side effect. `ContainerState.layoutState` carries layout-specific state (split ratios, accordion heights, etc.).

### Engine-backend persistence bridge

The engine and backend are architecturally separated: the engine manages `FrameLayout` state, the backend manages the runtime Container tree. To bridge this for persistence:

`FloatingFrameBackend` gains a new method:
```typescript
captureContainerTree(frameKey: string): ContainerState | undefined;
```

The backend walks `FrameState.rootContainer` recursively, serializing each Container and its entries into `ContainerState`. Returns `undefined` for flat single-container frames (no nesting or splits).

`captureLayout()` in the engine calls `backend.captureContainerTree(frameKey)` for each frame and sets `FrameLayout.containerTree` from the result.

`restoreLayout()` passes `containerTree` to the backend's `renderFrame()`, which recursively creates the Container tree from the serialized state.

### Capture

`captureContainerTree()` in the backend walks the Container tree recursively. For each entry:
- Leaf: serialize as `{ key, label, content: entry._content }` (unchanged)
- Non-leaf: serialize as `{ key, label, content: null, children: { layout, tabs: [...recurse...], layoutState } }`

### Restore

`renderFrame()` in the backend reads `containerTree`. For each level:
- No `children`: create leaf entry with content factory (unchanged)
- Has `children`: create child Container from `children.layout` and `children.tabs`, set on entry

### Backward compatibility

Existing saved layouts have no `containerTree` or `children` fields. They load via the existing flat `tabs` + `viewMode` path — exactly as today. No migration needed.

## 8. Testing Strategy

### Unit tests

| File | Coverage |
|------|----------|
| `container.test.ts` | Entry.childContainer lifecycle, containerize/flatten, depth propagation |
| `group-organiser-backend.test.ts` | Refactored tree-walking helpers, split creation with entry-owned children, cross-frame drag with nested trees |

### Integration tests

- Leaf → non-leaf conversion preserves existing content
- Non-leaf → leaf auto-flatten on last sibling close
- Nested depth enforcement (+ button hidden at maxDepth)
- Split + entry nesting combined (split at depth 2, entry-nest at depth 3)
- Persistence round-trip: nested layout saves and restores correctly
- Backward compat: old flat layout loads unchanged

## 9. File Impact Summary

| File | Change |
|------|--------|
| **packages/pages-component** | |
| `src/model/types.ts` | Move `Layout` type here (from pages-runtime). Add `ContainerState` interface. Add `containerTree?: ContainerState` to `FrameLayout`. Add `children?: ContainerState` to `FrameTabConfig`. Change `FrameTabConfig.content` to `Component \| null`. |
| **packages/pages-runtime** | |
| `src/frame-sandbox/types.ts` | Move `Container` interface here (from container.ts). Add `childContainer?: Container` to `Entry`. Add `ContainerConfig` interface. Remove `Layout` (re-export from pages-component). Update `DEFAULT_POLICY.maxDepth` to 5. Add `SPLIT_POLICY`. |
| `src/frame-sandbox/container.ts` | Remove `Container`/`ContainerConfig` interface definitions (moved to types.ts). Add `containerizeEntry()` and `flattenEntry()` helpers. Content factory checks `entry.childContainer`. |
| `src/group-organiser-backend.ts` | Remove `childContainers` from `FrameState`. Refactor tree-walking helpers (remove `childMap` param, fix mixed-container leaf detection). Refactor `createSplitContainer`, `splitFrame`, `handleEmptyLeaf`. Add nest button injection. Add `captureContainerTree()` to backend API. |
| `src/floating-frame-engine.ts` | Update `captureLayout()` to call `backend.captureContainerTree()`. Update `restoreLayout()` to pass `containerTree` to backend. |
| `src/floating-frame-backend.ts` | Add `captureContainerTree(frameKey: string): ContainerState \| undefined` to `FloatingFrameBackend` interface. |

**Total: 6 modified files, 0 new files.**

## References

- Issue #345 — Recursive Container model
- Issue #312 — Container tree migration (predecessor, landed)
- `packages/pages-runtime/src/frame-sandbox/types.ts` — Entry, ContainerPolicy, Layout types
- `packages/pages-runtime/src/frame-sandbox/container.ts` — Container interface, createContainer
- `packages/pages-runtime/src/frame-sandbox/split-strategy.ts` — split layout with collapse callback
- `packages/pages-runtime/src/group-organiser-backend.ts` — FrameState, tree-walking helpers, split creation
- `packages/pages-component/src/model/types.ts` — FrameLayout, FrameTabConfig persistence types
- `docs/protocols/casehub/content-agnostic-workbench.md` — content-agnostic principle
- `docs/protocols/casehub/workbench-integration-pattern.md` — three-package integration pattern
- Workspace compositor spec — D1-D6 predecessor decisions
