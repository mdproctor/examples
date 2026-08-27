## D1: Public nodes/edges properties on GraphCanvas

**Choice:** Add `@property({ attribute: false }) nodes` and `@property({ attribute: false }) edges` to GraphCanvas. `updated()` distinguishes the model path (triggers `_runLayout()`) from the direct nodes/edges path (skips layout, renders directly). If both `model` and `nodes`/`edges` are set, `model` wins.
**Alternatives:**
- None — this is the standard Lit reactive property pattern; no meaningful alternative exists
**Rationale:** DiagramBaseMixin consumers set pre-layouted React Flow nodes/edges directly. Without reactive properties, setting `.nodes`/`.edges` creates plain instance properties that bypass Lit's update cycle.
**Trade-offs:** Slightly more complex `updated()` logic to handle two input paths. Consumers must choose one path — model OR direct nodes/edges — mixing both defaults to model.
**Sources:** `packages/graph-renderer/src/bridge/GraphCanvas.ts` (current implementation), issue #320 (problem analysis), `docs/specs/issue-259-graph-phase0/2026-08-02-phase0-react-flow-lit-bridge-design.md` (original bridge design)
**Exploration:** quick
**Status:** captured

## D2: Stencil wrapper dimension constraints

**Choice:** Apply ELK-computed `width`/`height` from React Flow `NodeProps` as `max-width`/`max-height` with `overflow: hidden` on the `stencil-decoration-wrapper` div in `stencil-wrapper.tsx`.
**Alternatives:**
- Global CSS constraint on `.react-flow__node` — applies to all nodes, may break nodes that intentionally overflow
**Rationale:** Constraint lives at the rendering layer where the stencil content is. Only applies when ELK dimensions are present — no side effects on nodes without explicit sizing.
**Trade-offs:** Stencil content that exceeds ELK dimensions is clipped. This is correct behaviour — stencils should fit their assigned space.
**Sources:** `packages/graph-renderer/src/stencil-wrapper.tsx:148-166` (current wrapper), `docs/specs/issue-265-graph-renderer/2026-08-03-stencil-wrapper-pipeline-design.md` (stencil rendering pipeline)
**Exploration:** quick
**Status:** captured

## D3: React Flow base CSS bundled into getIsolationCSS()

**Choice:** Import `@xyflow/react/dist/style.css` as a raw string via Vite's `?raw` suffix in `css-isolation.ts`. Concatenate it with the isolation CSS in `getIsolationCSS()`. Remove the CSS import from `ReactFlowApp.tsx`.
**Alternatives:**
- Keep both — import in ReactFlowApp.tsx for light DOM, separate injection for shadow roots — duplicates CSS in document.head for light DOM consumers
**Rationale:** Single source of truth — getIsolationCSS() injects RF base CSS + isolation CSS together. Works for both light DOM (document.head via `injectIsolationStyles()` without host) and shadow roots (via `injectIsolationStyles(host)`). Builds on #319's shadow-aware injection.
**Trade-offs:** React Flow CSS is now bundled as a string constant rather than processed by the CSS pipeline. This is acceptable — the CSS is third-party and static.
**Depends on:** D1 (public properties enable the direct-nodes path that blocks-ui uses; CSS must work in that context)
**Sources:** `packages/graph-renderer/src/bridge/ReactFlowApp.tsx:17` (current CSS import), `packages/graph-renderer/src/bridge/css-isolation.ts` (injection mechanism), GE-20260818-f0257a (shadow-aware CSS injection technique)
**Exploration:** quick
**Status:** captured
