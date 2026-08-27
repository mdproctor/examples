# Three Issues, One Pipeline

*2026-08-03 · casehub-pages · graph-renderer Phase 1B*

The graph-renderer started Phase 1B as a React Flow spike with hard-coded nodes. By the end of this session it consumes a typed graph model, renders Lit templates inside React components, and auto-registers grammar rules — three issues (#271, #273, #272) landed in a single session because the dependency ordering turned out to be wrong in the original plan and fixing it made each piece simpler.

The original batch plan had #272 (StencilDescriptor registry) before #273 (stencil wrapper). That would have forced a temporary `component` field on the descriptor to keep the dev page alive between issues. Reordering — building the wrapper first — meant the registry could ship with `render` only. No transitional API, no backward compat shim, no field to delete later.

The design review caught a real bug before it reached code: the natural React pattern of putting render and cleanup in one `useEffect` destroys lit-html's tracked DOM parts on every update. Splitting into two effects (render on deps change, cleanup on unmount only) preserves the diff-patch capability that makes lit-html efficient. The kind of thing that works in development, passes all tests, and only shows up as a performance cliff with complex templates.

The mapping layer (#271) turned out to be the most straightforward piece — identity transforms plus a parent-detection scan. The interesting design choice was making `GraphCanvas` own the ELK layout internally rather than exposing it as an external step. Model in, rendered graph out. The consumer shouldn't need to know about the rendering pipeline's intermediate types.
