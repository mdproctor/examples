## D1: Nesting model — Entry owns children directly

**Choice:** `Entry` gains an optional `childContainer?: Container`. When set, the entry is a non-leaf that renders its child Container instead of raw content. The external `FrameState.childContainers: Map<string, Container>` is eliminated. The Container tree is navigable through Entry alone.
**Alternatives:**
- Dual model: Entry declares, external map backs — avoids touching split creation/collapse but creates two sources of truth
- Keep external map, extend to arbitrary entry keys — minimal type changes but doesn't achieve the "Entry as Container" model
**Rationale:** Matches the i3 spec's "everything is a Container" principle. Single source of truth. Tree structure is self-describing — no external registry needed to understand parent-child relationships. The existing `childContainers` map was a stopgap for the split-only case (#312).
**Trade-offs:** Requires refactoring all split creation/collapse logic in `group-organiser-backend.ts` to use `Entry.childContainer` instead of the map. Moderate diff but the split logic already creates child Containers — the change is where they're stored, not how they're created. Creates a circular type reference (Entry → Container → Entry) — resolved by moving the Container interface definition to `types.ts` alongside Entry, since Container is a pure interface with no runtime dependencies.
**Sources:** `packages/pages-runtime/src/frame-sandbox/types.ts` (Entry interface), `packages/pages-runtime/src/frame-sandbox/container.ts` (Container interface), `packages/pages-runtime/src/group-organiser-backend.ts` (FrameState.childContainers)
**Exploration:** quick
**Review:** R1-04 — circular type dependency acknowledged. Resolution: move Container interface to types.ts. Both Entry and Container are data-shape interfaces with no runtime imports; co-locating them in types.ts eliminates the cycle without coupling data and runtime layers.
**Status:** revised

## D2: Nesting trigger — content-area + button

**Choice:** A `+` button inside the active tab's content area converts a leaf entry into a non-leaf. The tab-strip `+` remains "add sibling tab." Clicking the content-area `+` wraps the existing content into the first child of a new child Container, then adds a second empty child — the user sees their original content plus a new tab inside the nested Container.
**Alternatives:**
- Right-click context menu on tab header — more discoverable options but requires context menu infrastructure not yet built
- Tab-strip dropdown splitting "add tab" vs "add nested tab" — adds a click to the common case (sibling add)
**Rationale:** Direct, single-click gesture. Content-area placement makes the spatial relationship clear: "I'm adding inside this tab." Consistent with the issue's design direction. Tab-strip + remains the fast path for the common case (sibling).
**Trade-offs:** Requires injecting a + button into each leaf tab's content area. The button must hide when the entry is already non-leaf (it already has a nested Container with its own tab strip). Must respect `maxDepth` — hidden when depth limit reached. Three levels of + buttons exist (compositor tab bar, frame tab strip, content area) — the content-area button should use a distinct icon or label (e.g., "⊞" or "Nest") to differentiate from sibling-add buttons.
**Depends on:** D1 (Entry owns children — the + creates a childContainer on the active entry)
**Sources:** Issue #345 body (design direction section)
**Review:** R1-08 — visual differentiation acknowledged. Content-area nest button uses distinct affordance.
**Exploration:** quick
**Status:** revised

## D3: Content wrapping — existing content becomes first child

**Choice:** When a leaf entry converts to non-leaf, the existing `contentElement` is detached and re-mounted as the first entry in the new child Container. The user's view is preserved — their content is now inside a tab of the nested Container. A second empty tab is added so the nesting is immediately useful.
**Alternatives:**
- Replace with empty Container — simpler but destructive, user loses current content
- Keep as background, overlay Container — complex and confusing
**Rationale:** Non-destructive. The user clicked + to add something alongside their content, not to replace it.
**Trade-offs:** Moving a DOM element triggers `disconnectedCallback`/`connectedCallback` on web components — ephemeral state (scroll position, selections, ECharts highlights) is lost. To avoid this, the content factory re-creates the content in the child container rather than transferring the DOM element. The data pipeline re-delivers datasets via `pages-data-request`, so data state recovers. Ephemeral state loss is acceptable for this one-time structural operation — consistent with cross-tab frame transfer (workspace-compositor D3) which also accepts re-creation cost.
**Depends on:** D1, D2
**Sources:** `packages/pages-runtime/src/frame-sandbox/container.ts` (contentFactory pattern, Entry.contentElement lifecycle)
**Review:** R1-07 — DOM lifecycle effects acknowledged. Changed from element transfer to content factory re-creation to avoid disconnectedCallback/connectedCallback side effects.
**Exploration:** quick
**Status:** revised

## D4: Depth semantics — full tree depth, unified

**Choice:** `ContainerPolicy.maxDepth` counts from root Container to deepest leaf Container, regardless of whether nesting was created by splits or by entry nesting. The existing `depth` parameter on `createContainer()` already tracks this. Child Containers created via `Entry.childContainer` pass `parentContainer.depth + 1` as their depth.
**Alternatives:**
- Separate depth counters for split nesting vs entry nesting — more flexible but two concepts to reason about, harder to enforce a global limit
**Rationale:** Uniform depth model. The user doesn't care whether nesting came from a split or a tab — depth is depth. The existing `depth` field on Container and the `maxDepth` check in `createContainer()` already implement this correctly for splits; entry nesting plugs into the same mechanism.
**Trade-offs:** The current codebase has inconsistent maxDepth: leaf containers hardcode maxDepth:3, split containers hardcode maxDepth:10. With unified depth counting, maxDepth:3 blocks entry nesting whenever splits are present (root→split→leaf = depth 3, at limit). Resolution: unify all containers to maxDepth:5 via DEFAULT_POLICY. This allows root(1)→split(2)→leaf(3)→entry-nest(4)→entry-nest(5) — two levels of explicit nesting beyond a split, which covers practical use cases without enabling unbounded depth. The hardcoded policies in `group-organiser-backend.ts` should reference DEFAULT_POLICY instead of inline values.
**Sources:** `packages/pages-runtime/src/frame-sandbox/types.ts:3-6` (ContainerPolicy), `packages/pages-runtime/src/frame-sandbox/container.ts:148-153` (depth check), `packages/pages-runtime/src/group-organiser-backend.ts:260,296` (inconsistent hardcoded maxDepth)
**Review:** R1-02 — maxDepth=3 confirmed unusable with splits. Upgraded from footnote to explicit resolution: unify to maxDepth:5, eliminate hardcoded policies.
**Exploration:** quick
**Status:** revised

## D5: Persistence — recursive FrameTabConfig

**Choice:** `FrameTabConfig` gains an optional `children?: ContainerState` field. `ContainerState` contains `layout: Layout` and `tabs: FrameTabConfig[]` — the same recursive shape as the runtime Container tree. When `children` is present, the tab is non-leaf and its content is the serialized child Container. When absent, the tab is a leaf (backward compatible). `FrameLayout.tabs` stays as the top-level field.
**Alternatives:**
- Separate `containerTree?` field on FrameLayout replacing `tabs` — bigger migration, cleaner separation but more work for backward compat
- Rename `tabs` to `entries` with inline recursion — breaks backward compat for field name
**Rationale:** Minimal, backward-compatible extension. Existing layouts without `children` load correctly as flat tab lists. New layouts serialize the full tree. The recursion is natural — a tab's children are just more tabs in a Container. With D1 eliminating the external childContainers map, this also handles split persistence — `ContainerState.layout` can be `splith`/`splitv` and `ContainerState.layoutState` carries split ratios. Frame-level splits were NOT previously persisted; this design fixes that as a side effect.
**Trade-offs:** `FrameTabConfig` gains responsibility for tree structure. The type name ("TabConfig") is slightly misleading for non-leaf entries — consider renaming to `FrameEntryConfig` in a follow-up cleanup issue.
**Depends on:** D1 (Entry.childContainer defines the runtime tree that must be serialized)
**Sources:** `packages/pages-component/src/model/types.ts:91-111` (FrameLayout), workspace-compositor spec §8 (CompositorState pattern)
**Review:** R1-03 — split persistence gap confirmed. ContainerState now explicitly handles both entry nesting AND split nesting (layout can be any Layout including splith/splitv, layoutState carries ratios). This is a scope expansion but a welcome one — previously splits were lost on save/restore.
**Exploration:** quick
**Status:** revised

## D6: Collapse — auto-flatten on single child

**Choice:** When a nested Container's last sibling is closed and only one child remains, the remaining child's content replaces the Container — the entry reverts to a leaf. This is the same behavior as split collapse (where the sibling leaf becomes root when the other is emptied).
**Alternatives:**
- Stay nested, require manual flatten — more predictable but creates unnecessary single-child depth
- Auto-flatten only when empty (zero children) — inconsistent with split behavior
**Rationale:** Consistent with the existing split collapse pattern in `group-organiser-backend.ts:297-311`. Prevents orphaned single-child nesting that adds depth without utility. The user can always re-nest.
**Trade-offs:** Auto-flatten may surprise users who intentionally want a single-tab nested Container (e.g., for layout isolation). But this is an edge case — the common case is that single-child nesting is accidental cleanup residue.
**Depends on:** D1 (collapse operates on Entry.childContainer)
**Sources:** `packages/pages-runtime/src/group-organiser-backend.ts:297-311` (existing split collapse pattern)
**Exploration:** quick
**Status:** captured

## D7: Tree walking — unified traversal via Entry.childContainer

**Choice:** Refactor all tree-walking helpers (`findLeafContainer`, `findContainerWithTab`, `forEachLeafContainer`, `findParentSplitEntry`) to walk via `Entry.childContainer`. The `childMap: Map<string, Container>` parameter is removed from all signatures. Both split children and entry-nested children are reached through the same mechanism: iterate `container.entries`, check `entry.childContainer`, recurse.
**Alternatives:**
- Adapter pattern: build temporary childMap from Entry.childContainer fields — smaller diff but maintains two mental models and the adapter rebuilds on every mutation
- New helpers alongside old with gradual deprecation — safest migration but code duplication
**Rationale:** One traversal pattern for the entire tree. With Entry owning children (D1), there's no reason for an external map parameter.
**Trade-offs:** The refactor is more than parameter removal — the `isSplitLayout()` gate that currently controls recursion disappears. The new recursion condition is: "if any entry has `childContainer`, recurse into it." A tabbed container whose entries have childContainers is no longer a leaf. The leaf detection semantic changes from "not a split layout" to "no entries have children." All ~15 call sites need updated logic, not just parameter drops. `findParentSplitEntry` is renamed to `findParentEntry` since it's no longer split-specific.
**Depends on:** D1 (Entry.childContainer is the traversal mechanism)
**Sources:** `packages/pages-runtime/src/group-organiser-backend.ts:55-128` (existing helpers)
**Review:** R1-01 — refactor complexity acknowledged. Updated from "parameter removal" to accurate description of logic changes. The new traversal is simpler (one condition instead of layout-type branching) but it IS a logic change at every call site, not just a signature change.
**Exploration:** quick
**Status:** revised
