## D1: PagesDensityHeatmap — dynamic import to defer @drdreo/heatmap

**Choice:** Dynamic `import("@drdreo/heatmap")` inside the component methods that use it, replacing the static top-level import. The barrel export in `index.ts` stays unchanged.
**Alternatives:**
- Remove from barrel, require deep import — clean isolation but breaking change for existing consumers
- Barrel removal + dynamic import — over-engineered, no other component in the barrel has this pattern yet
**Rationale:** Solves the crash without breaking any consumer's import path. The async boundary fits naturally since heatmap creation is already deferred to `updated()`. Pattern is established in the codebase (Dockview lazy loading).
**Trade-offs:** Slightly more complex component code (async init, cached module reference). Consumers that do use the density heatmap pay a one-time dynamic import cost on first render.
**Sources:** `packages/pages-viz/src/charts/PagesDensityHeatmap.ts:4` (static import site), `packages/pages-viz/src/index.ts:48` (barrel export), GE-20260810-03 (Dockview lazy import pattern), GE-20260810-02 (@drdreo/heatmap integration notes)
**Exploration:** quick
**Status:** captured

## D2: CSS isolation — shadow-aware injection with per-root ref-counting

**Choice:** Modify `injectIsolationStyles(host?: HTMLElement)` to detect the root node via `host.getRootNode()` and inject into the appropriate target (ShadowRoot or document.head). Ref-counting becomes per-root using a `WeakMap<Document | ShadowRoot, { count: number; style: HTMLStyleElement }>`. `releaseIsolationStyles(host?)` mirrors the pattern. `GraphCanvas.connectedCallback()` passes `this` as the host.
**Alternatives:**
- Duplicate styles into both document.head and shadow root — wastes memory, confusing ownership semantics, harder cleanup
**Rationale:** Correct for any nesting depth, no global state leaks, handles multiple GraphCanvas instances in different shadow roots. Falls back to document.head when no host is provided, preserving existing light-DOM behaviour.
**Trade-offs:** Slightly more complex ref-counting logic. Callers must pass the host element for shadow DOM support — existing callers that don't pass it get the old behaviour.
**Sources:** `packages/graph-renderer/src/bridge/css-isolation.ts` (current implementation), `packages/graph-renderer/src/bridge/GraphCanvas.ts:38` (call site), GE-20260802-01 (CSS isolation pattern), issue #319 (shadow DOM host analysis)
**Exploration:** quick
**Status:** captured
