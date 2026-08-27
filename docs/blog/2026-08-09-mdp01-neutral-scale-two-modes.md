---
layout: post
title: "One Neutral Scale, Two Modes"
date: 2026-08-09
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [dock-workbench, theming, css, design-tokens]
series: issue-75-tool-window-docking
---

*Continues from [Dock Workbench: Composable Primitives Over Monolithic Controllers](2026-08-04-mdp01-dock-workbench-composable-primitives.md).*

The dock workbench had two tree builders producing the same layout from the same config — one in the YAML parser's `dockWorkbench()`, another in the zone engine's `buildTreeFromZones()`. The zone engine version was strictly better: zone container IDs, height propagation, side stripes with zone-grouped buttons. The old one was dead weight from before the zone engine existed. Consolidation was overdue.

Moving `buildTreeFromZones` and its helpers into `pages-ui` (where `dockWorkbench` lives) let the old body collapse to five lines: normalize, build zone map, build tree, tag with config. The zone engine in `pages-runtime` now imports from `pages-ui` instead of owning the code. One builder, one code path.

The harder problem was making the YAML gallery sample fill its container. The YAML parser wraps everything in page components with grid items, and CSS `height: 100%` cascading through page → slot → grid → dock is fundamentally fragile. Every intermediate wrapper needs explicit height, and any wrapper that doesn't have it breaks the chain. I stopped trying to patch the cascade and went to first principles: when `loadSite` detects a dock workbench config anywhere in the component tree, it replaces root with the zone engine's tree directly. No page wrappers, no grid items, no intermediate CSS to fight. The dock fills edge-to-edge because there's nothing between it and the rendering target.

The visual treatment was the last piece. The fixture had panel styling in its own HTML — borders, radius, background — that the gallery YAML panels didn't have. The right place for it turned out to be neither the builder (inline styles conflict with custom content CSS) nor the content HTML (every consumer would need to duplicate it). `loadSite` injects a `<style>` element when a zone engine is present, targeting dock panels via `[data-component-id^="__zone:"] [data-component-type="deferred"]`. One CSS rule, applied everywhere, no content dependencies.

The theme pattern came from IntelliJ's Island UI. The trick: panels use `var(--pages-neutral-1)` and the chrome (separators, side stripes) uses `var(--pages-neutral-3)`. In dark mode, neutral-1 is the darkest shade and neutral-3 is a step lighter — panels recede, chrome frames them. In light mode, neutral-1 is the lightest shade and neutral-3 is a step darker — panels are white, chrome is gray. Same two variables, both modes, because the OKLCH neutral scale inverts between themes. Seven lines of CSS, no `prefers-color-scheme` media query, no conditional logic.

Named themes in the token pipeline turned out to have a gap: they define CSS variables but don't set `background` or `color` on the theme class element. Only the generic `pages-theme-dark`/`pages-theme-light` rules include those. `loadSite` now bridges this — it detects existing theme classes on ancestor elements and applies `background: var(--pages-neutral-3)` to the target. The dock renders correctly regardless of whether the host page uses a named theme or the generic one.
