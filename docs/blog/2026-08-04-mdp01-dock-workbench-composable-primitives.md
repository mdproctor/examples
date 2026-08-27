# Dock Workbench: Composable Primitives Over Monolithic Controllers

**Date:** 2026-08-04
**Issue:** casehubio/casehub-pages#285

The dock workbench shipped as three focused primitives and a DSL builder, not
as a single orchestrating component. The initial design proposed a
`WorkbenchController` class that would manage dock bars, zone state, lazy
panel rendering, and state persistence in one place. Design review pushed back:
the pages system is built on composable primitives, and a monolithic controller
would be a parallel rendering path at tension with the architecture.

The decomposition that emerged: (1) `deferred` component type — add to
`LAZY_TYPES` in render.ts so `renderNode` skips child recursion, with an
activation callback that renders on first `pages-deferred-render` event.
(2) `exclusive` prop on dock-bar — zone-exclusive panel switching via two
synchronous `pages-dock-toggle` events. (3) Component-level dock-toggle
handler with cascading collapse — operates on `[data-component-id]` elements
directly instead of parent slot containers, enabling panels that share a slot
to be toggled independently.

Each primitive is independently useful. The `dockWorkbench()` builder composes
them into the right tree structure. No new rendering path needed.

The design review caught three critical compound failures: the zone slot model
(`rows` creates a shared slot), the `lazy` rendering mechanism (controlled by
`LAZY_TYPES`, not `LAYOUT_TYPES` as assumed), and a self-perpetuating state
initialization cycle. All three required coordinated fixes — the cross-cutting
review connected failures that no single dimension reviewer identified alone.
