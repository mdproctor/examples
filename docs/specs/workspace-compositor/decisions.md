## D1: Compositor architecture — separate layer above FloatingFrameEngine

**Choice:** New WorkspaceCompositor module that manages regions, tabs, and view mode. Each tab owns its own FloatingFrameEngine. The compositor coordinates cross-tab frame transfer, tab lifecycle, and persistence. Existing engine and wire function stay unchanged.
**Alternatives:**
- Extend FloatingFrameEngine to be multi-workspace aware (frames gain workspaceId, engine filters by active workspace) — mixes frame management and workspace management concerns, every method needs workspace-awareness
**Rationale:** The engine is well-scoped to single-workspace frame management. Multi-workspace coordination (cross-tab drag, region splits, view mode toggle) is a distinct concern that belongs in a compositor layer. Clean composition, no modification of existing code.
**Trade-offs:** New module with its own state management. Cross-tab frame drag requires coordinated remove-from-source + create-in-target across two engines.
**Exploration:** quick
**Status:** captured

## D2: Region model — binary split node

**Choice:** Binary split node: `Region = LeafRegion | SplitRegion`. LeafRegion holds tabs, viewMode, accordionHeights. SplitRegion has direction (h/v), ratio (single number for resize), and exactly two LeafRegion children. No nesting enforced structurally — children typed as `[LeafRegion, LeafRegion]`, not `[Region, Region]`. Tab drag to edge converts a leaf into a split. Last tab closed collapses the split (sibling becomes root).
**Alternatives:**
- Flat list of regions with layout coordinates — simpler data model but resize becomes coordinate math, collapse requires recalculating all positions
- Reuse existing split() component — leverages existing resize handles but couples compositor to component tree lifecycle
**Rationale:** Matches the "top-level only, no nesting" constraint exactly. Single ratio number gives clean resize. Collapse is trivial — sibling leaf becomes root.
**Trade-offs:** Max 2 regions — intentional constraint. If 3+ regions are ever wanted, the model must change to N-ary children. Rigid by design.
**Clarifications (R1):** 2-region max is explicit. One split = two regions. No nesting, no N-ary. Both leaf and split carry a `type: "leaf" | "split"` discriminator for serialization (R3).
**Exploration:** quick
**Status:** captured

## D3: Cross-tab frame transfer — compositor-coordinated with state snapshot

**Choice:** Compositor-coordinated transfer: snapshot restorable state (scroll position, focus, input selection, input values) from source frame DOM → remove from source engine → create in target engine at drop position via content factory → restore snapshot state on new DOM. A `TransferSnapshot` interface captures practical ephemeral state, extensible over time.
**Alternatives:**
- DOM adoption via adoptNode() — preserves all DOM state but fights Dockview's panel lifecycle (destroy/recreate grain). The detach feature already rejected adoptNode in favour of hide/show + content factory (D1 in floating-ux-polish spec).
**Rationale:** Consistent with existing tab-drag-out pattern (destroy old, create new). Content factory ensures content is always reconstructable. State snapshot covers the user-visible ephemeral state (scroll, focus, selection) without reaching into backend internals.
**Trade-offs:** Internal component state (Lit reactive properties, web component internal state) is not captured — only DOM-level ephemeral state. Acceptable because content factory re-renders from the same config and the data pipeline re-delivers datasets via `pages-data-request`, so component state reconstructs from props/data. Scroll and cursor position are the things users actually notice losing.
**Depends on:** D1 (compositor layer owns the coordination)
**Exploration:** quick
**Status:** captured

## D4: Persistence — new compositor field on LayoutState

**Choice:** Add `compositor?: CompositorState` to `LayoutState`. `CompositorState` contains the region tree (leaf or split), each leaf has tabs (id, name, frames), activeTabId, viewMode (tab/accordion), and accordionHeights. When `compositor` is present, `LayoutState.frames` is ignored. When absent, the old `frames` array loads into a single-tab single-region — backward compatible.
**Alternatives:**
- Encode compositor state inside the existing `frames` array with metadata markers — brittle, semantically overloaded, harder to reason about
**Rationale:** Clean field, clean migration path. Old layouts without `compositor` work as before. New layouts carry the full compositor tree. No ambiguity about which field is authoritative.
**Trade-offs:** Adds a new top-level field to LayoutState. Layouts saved with compositor state cannot be read by older versions (forward compat is not a concern — pre-release project).
**Depends on:** D2 (region model defines the shape)
**Exploration:** quick
**Status:** captured

## D5: Accordion view — CSS flexbox with drag handles

**Choice:** Accordion sections are flex items in a `flex-direction: column` container with `overflow-y: auto` (per-region scroll). Each section has a header (tab name + collapse toggle) and a content area containing its tab's floating workspace. Collapsed sections use `flex: 0 0 auto` (header-only). Expanded sections use stored height as `flex-basis`. Drag handles between sections adjust `flex-basis` values. Each section's FloatingFrameEngine uses its own overlay container — `canvasSize` from `getBoundingClientRect()` naturally scopes snap zones and organiser presets to the section's dimensions. Collapsed engines stay alive but overlay is `display: none`.
**Alternatives:**
- Absolute positioning with calculated offsets — more manual layout math, no benefit over flexbox for a vertical stack
**Rationale:** Simple CSS model. Resize is updating flex-basis. Per-section engine scoping happens naturally via the overlay container's dimensions. Per-region scroll via `overflow-y: auto` on the flex container.
**Trade-offs:** None significant — flexbox is the right tool for a resizable vertical stack.
**Depends on:** D2 (region model), D1 (each tab owns its own engine)
**Exploration:** quick
**Status:** captured

## D6: Split creation — tab drag to edge with preview overlay

**Choice:** When a tab header drag starts, the compositor shows semi-transparent drop zones at the four edges of the current region (top/bottom for H split, left/right for V split). Dragging over a zone highlights it. Dropping on a zone creates a split with the dragged tab in a new leaf region on that side, default 50/50 ratio. Reuses the proximity detection concept from `snapToZone()` in `frame-boundaries.ts`. Drag priority: edge drop zone > tab reorder within region > cross-region tab move.
**Alternatives:**
- Explicit split button then tab move — two-step process, needs empty region placeholder UI, less discoverable
**Rationale:** Direct drag-to-edge with preview is the pattern users expect from IDEs (VS Code, IntelliJ). Single gesture. The snap preview overlay pattern already exists in the codebase.
**Trade-offs:** Requires intercepting tab header drag events before the tab reorder handler. Priority ordering must be correct or edge drops will be swallowed by reorder.
**Depends on:** D2 (split creates a SplitRegion from a LeafRegion)
**Exploration:** quick
**Status:** captured
