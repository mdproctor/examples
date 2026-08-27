---
layout: post
title: "The rename that found three bugs"
date: 2026-07-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [webpack, dark-mode, oklch, web-components]
---

What started as a mechanical rename — propagating `text-input` → `input` and `dropdown` → `select` across example YAML files — turned into something more useful when I actually opened the examples gallery to verify the changes.

The form fields were gone. Not broken — *gone*. The data table rendered, the number input rendered, but the text inputs, selects, checkboxes, and textareas were invisible. `customElements.get('pages-input')` returned `undefined`. The elements existed in the DOM as empty tags with zero height and no shadow root. No console errors. No webpack warnings.

Claude dug into the bundle and confirmed: the form component code wasn't there. `grep -c "pages-input" casehub-bundle.js` returned 0. The `pages-ui-components` package declares `sideEffects` correctly in its `package.json`. The imports are in `activation.ts`. The webpack aliases resolve to the right files. Everything looks right — and the components silently vanish.

The root cause: webpack aliases bypass `sideEffects` resolution. When an import resolves through `resolve.alias` to an absolute filesystem path, webpack doesn't walk up the directory tree to find the `package.json`. Without the `sideEffects` declaration, production-mode tree shaking treats the bare `import "@casehubio/pages-ui-components/input"` as safe to eliminate. The fix is a one-liner — a module rule marking the alias-resolved paths as side-effectful:

```javascript
{ test: /pages-ui-components[\\/]dist[\\/]/, sideEffects: true }
```

This is distinct from the `import type` erasure gotcha we hit last month (GE-20260629-ebdb0a). Same symptom — missing custom elements — completely different mechanism. That one is TypeScript removing imports before webpack sees them. This one is webpack seeing the imports and deciding they're disposable.

Two smaller things surfaced while clicking through the gallery. The accordion component starts all sections expanded, but the CSS chevron rotation depends on a `[data-expanded]` attribute that's only set on click — never on initial render. So the arrows point right while the content is open. One line: `header.setAttribute("data-expanded", "")` in the initial setup loop.

The data table filter had a "Go" button that did nothing useful — `filterText` is a reactive Lit property that triggers filtering on every keystroke. The button was vestigial. Removed it along with its CSS and test assertion.

After the bugs, I swept all 65 example YAML files for hardcoded hex colours that break in dark mode. The Grouped Roadmap was the worst — light green and white backgrounds painted directly onto the dark surface. The fix follows the same oklch overlay pattern from #238: backgrounds use `oklch(L C H / 0.10-0.15)` which tints whatever surface they sit on rather than painting an opaque slab, and text uses mid-lightness `oklch(0.55 C H)` which reads against both light and dark. Roughly 207 replacements across 15 files — legend swatches, pill maps, rowStyle conditions, inline HTML styles, SVG fills, and chart series colours.

The theme picker's "compact mode" came up during the visual check. It currently just hides the family dropdown and shows sun/moon icons inline — useful but not what you'd want in a toolbar. Filed #245 for a proper flyout: single icon button, popover with theme family + light/dark toggle.
