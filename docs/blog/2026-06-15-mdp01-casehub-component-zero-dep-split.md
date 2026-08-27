---
layout: post
title: "Extracting @casehub/component — The Zero-Dep Split"
date: 2026-06-15
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [typescript, component-model, web-components, css-grid, extraction]
---

## Extracting @casehub/component — The Zero-Dep Split

The `@casehub/ui` package had a dependency problem. It bundled two things that shouldn't live together: generic component primitives (`Component`, `GridItem`, `AccessControl`, slots) that any web UI can compose over, and data-specific types (`DataSetLookup`, `ChartSettings`, `BarChartProps`) that pull in `@casehub/data`. Claudony and DraftHouse both need the component primitives. Neither needs the data engine.

The extraction itself was straightforward — `types.ts` and `component-props.ts` are zero-dep files that move wholesale. The interesting question was where to draw the line. `FilterSettings`, `DrillDown`, and `RefreshSettings` have no `@casehub/data` imports, but they feel like data-pipeline concerns. I put them in `@casehub/component`. They describe component behaviour (refresh polling, cross-component filtering, drill-down navigation), not data operations. Claudony's session cards will want refresh intervals. DraftHouse's panels will want filter groups. These are behavioural primitives, not data primitives.

The type guard split forced a TypeScript puzzle I hadn't anticipated. `@casehub/component` exports `getProps<T extends keyof ComponentTypeRegistry>()` with a 17-entry registry. `@casehub/ui` extends that registry with 14 more chart/data types. Re-exporting `getProps` doesn't widen the generic constraint — TypeScript resolves constraints at the declaration site, not the import site. Declaration merging works for interfaces but not function generics. The fix is a type assertion: `export const getProps = baseGetProps as <T extends keyof ExtendedRegistry>(...)`. Single implementation, widened type at the consumer level. Claude's spec review caught this gap — the original spec hand-waved "re-exports base getProps with extended registry" without addressing the TypeScript limitation.

## The Layout Renderer

The second half of the issue was a CSS Grid renderer — a function that takes a `Component` tree and produces DOM. Every component gets a `<div>` with `data-component-type`, `data-component-id`, and `data-component-props` attributes. Layout types (`grid`, `columns`, `rows`, `sidebar`, `panel`, `app-grid`) get CSS applied. Unknown types (charts, content, custom Claudony components) get empty activation containers. The site runtime fills them in later.

The renderer owns interactivity for tabs, pills, accordion, and carousel — visibility toggling via `display: none`/`display: block`. These are layout operations, not data binding. Event delegation on the tab bar container; listeners die when the target is cleared. `tree` and `menu` are too complex for the renderer (nested expand/collapse, keyboard navigation) — they get empty containers and the site runtime activates Web Components for them.

Three spec review findings from Claude shaped the design in ways I wouldn't have caught:

The DSL's slot names didn't match what the renderer expected. `rows()` and `panel()` produced `slots.content` — the renderer expected `slots.default` (the Web Components convention for the unnamed default slot). And `stack()` aliased `rows()`, returning `type: "rows"` — but the renderer treats rows (flex column, all visible) and stack (display toggling, one visible) as distinct layout types. Both were one-line fixes, but the renderer would have silently produced empty containers for every component built by the DSL.

The `stack` component's CSS strategy contradicted itself — `grid-template-areas: "main"` combined with `display: none` makes no sense, because hidden elements don't participate in grid layout. Simplified to a plain container with display toggling. No grid needed.

ID generation for the DOM needed a non-obvious separator. The spec initially used `_` in tree-path IDs (`root_main_0`), but a slot named `a_b` would collide with parent `root_a` slot `b`. Switched to `::` — slot names are semantic strings like "Sales" or "nav", never containing `::`.

The whole thing — extraction, DSL fixes, renderer, interactive layouts — lands as a single branch with 672 tests across the three `@casehub` packages. `@casehub/ui` re-exports everything from `@casehub/component`, so existing consumers see no change. New consumers import the zero-dep package directly.
