---
layout: post
title: "Three Tiers for One Design System"
date: 2026-07-28
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [maven, esbuild, static-assets, design-system]
series: issue-247-pre-built-static-assets
---

The Maven SNAPSHOT work from last session (#246) solved cross-repo dependency distribution. But it surfaced a gap: consumer repos with static HTML pages — login forms, register pages, admin screens — still couldn't use `<pages-button>` or get `--pages-*` CSS variables without a bundler. Every consumer would need to invent its own solution. That's a platform smell.

The root problem is that pages-ui-tokens generates theme CSS at runtime via JavaScript, and pages-ui-components requires an ESM bundler to resolve Lit. Both are fine for bundled apps. Neither works for a `<script>` tag in a static HTML page.

I filed #247 and started the design. The key decision was where the pre-built assets should live. Three options: pack them into the existing `casehub-pages-npm` artifact (wrong — that's build-time source, consumed via `unpack` + `portal:`, not classpath-served), bundle them into `casehub-pages-webapp` (wrong — inverts the dependency arrow, forces every login page to pull in the dashboard runtime), or a new dedicated artifact. The third option preserves tier separation: build-time source (`npm`), runtime design system (`static`), runtime dashboard app (`webapp`). Each has a single consumption model. Each can change independently.

The implementation is small. `pages-ui-tokens` already had a CLI that writes theme CSS to `dist/themes/` — it just wasn't called during the normal build. For components, esbuild bundles the tsc output into a single self-contained ESM with Lit inlined — 32KB minified. A new `static-assets/` directory at repo root contains `assembly.sh` (orchestrates `build:tokens`, `build:bundle`, validates the ESM, copies to `META-INF/resources/pages/`) and a Maven pom that packages the result.

The design review caught something I'd missed: `build:tokens` and `build:bundle` must stay separate from the default `build` script. `pack-all.sh` packs all of `dist/` for every non-private workspace package into the npm artifact. Merging the static-asset build steps into `build` would leak theme CSS and the component bundle into `casehub-pages-npm`, where they don't belong. This became a protocol — the first build-pipeline protocol for this project.

The ESM validation was the one genuine gotcha. Lit calls `document.createTreeWalker()` at module init time — during template compilation, not at render. A minimal stub with just `customElements` and `HTMLElement` crashes immediately. The validator needs `createTreeWalker`, `createComment`, `MutationObserver`, `CSSStyleSheet.replaceSync`, and `adoptedStyleSheets` before the import even completes.

Consumer usage is now two lines:
```html
<link rel="stylesheet" href="/pages/tokens/casehub-dark.css">
<script type="module" src="/pages/ui/components.js"></script>
```

The URL namespace uses `/pages/tokens/` and `/pages/ui/` — deliberately distinct from the existing `/pages/component/` path that the webapp uses for iframe components like `llm-prompter`. The `class="pages-theme-casehub-dark"` on an ancestor element is required because the CSS scopes all custom properties under `.pages-theme-{name}`.

The three-tier split is the interesting design point. It's not just about serving files — it's about dependency direction. The design system is lower in the stack than the dashboard app. A login page should depend on the foundation, not the application built on top of it.
