---
layout: post
title: "The Browser Did the Hard Part"
date: 2026-07-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [popover-api, css-anchor-positioning, theme-picker, lit]
series: issue-245-compact-theme-picker
---

# casehub-pages — The Browser Did the Hard Part

**Date:** 2026-07-30
**Type:** phase-update

---

## What I was trying to achieve: a compact theme picker that doesn't take up toolbar space

The full `pages-theme-picker` renders a select dropdown plus light/dark toggle buttons — fine for a settings panel, wrong for a toolbar. Issue #245 asked for a single icon button that opens a flyout popover with the same controls. The hard parts looked like positioning, click-outside dismissal, focus management, and a11y.

## What we believed going in: we'd need FocusTrapMixin and manual positioning

I expected to pull in `FocusTrapMixin` from `pages-primitives`, write a click-outside handler that respects shadow DOM boundaries (a known pain point — GE-20260713-9e6bf5 documents the `composedPath()` limitation in jsdom), and manage z-index stacking manually. I'd seen enough custom popover implementations to know the pattern: 50-100 lines of JS for positioning, another 30 for dismiss behaviour, a few more for focus restoration.

## The Popover API made most of the code unnecessary

Before writing any implementation, I searched for current platform capabilities. Two Baseline standards changed the equation:

The **Popover API** (Baseline 2025) gives `popover="auto"` on any element: light-dismiss, Escape-to-close, auto `aria-expanded` on the invoker, top-layer rendering, and tab-order repositioning. All browser-native, zero JS.

**CSS Anchor Positioning** (Baseline 2026) handles placement: `anchor-name` on the trigger, `position-anchor` plus `position-area` on the popover, `position-try-fallbacks: flip-block` for viewport edge flipping. Declarative CSS, no Floating UI.

The key question was whether `popovertarget` (which resolves by ID) would work inside a shadow root. The whatwg spec issue #9109 discusses cross-shadow-boundary limitations, and reading that alone would make you think it won't work. It does — both elements are in the same shadow root, which is the same tree for ID resolution purposes. That distinction isn't documented in any Lit or web component guide I could find.

The entire popover — open, close, dismiss, position, focus, a11y — is handled by the browser. We wrote CSS and markup. The JS footprint is the three event handlers that were already there: family change, mode toggle, and theme application.

## Adaptive family selector

One design decision worth noting: the compact popover renders radio buttons when there are 5 or fewer theme families, and switches to a `<select>` dropdown above that threshold. With 2-3 families (the common case), radios give a better at-a-glance experience. With many families, they'd make the popover uncomfortably tall.

Single-family deployments get an even simpler popover — just the light/dark toggle, no family section at all.

## CI was red before we started

The session started with CI broken on main — 17 ESLint `strict-type-checked` errors and 3 test failures, all pre-existing. The lint errors were mechanical: unused imports, floating promises, unnecessary type conversions, an `as any` that should have been a branded type cast. The test failures were a stale chroma threshold in the CaseHub preset test and two theme class assertions using the old naming convention (`pages-theme-dark` instead of `pages-theme-default-dark`).

None were related to #245, but CI had been red for days with every run skipping tests because lint failed first. We fixed them on main before starting the feature branch.

## Where this leaves things

The compact picker works — palette icon with accent dot, popover with family radios and mode toggle, all positioning and dismiss handled by the browser. The `anchor-scope: all` on `:host` prevents anchor name collisions when multiple pickers exist on the same page, which was a gotcha worth recording (GE-20260730-ec4b06).

The Popover API pattern probably has legs beyond the theme picker. Any component that needs a dropdown or flyout — filter chips, column pickers, context menus — could use the same approach. The entire Floating UI dependency category becomes optional when the browser provides positioning, dismiss, and top-layer rendering natively.
