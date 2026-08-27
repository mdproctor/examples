---
layout: post
title: "The Platform Gave Us the Modal"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [web-components, a11y, dialog, lit]
series: issue-139-pages-modal
---

I'd been expecting this one to be a slog. Modals have a reputation — focus traps, scroll locking, backdrop click detection, screen reader announcements, z-index wars. Every framework has a modal component and none of them agree on how it should work.

The research changed my mind. The native `<dialog>` element with `showModal()` handles most of it: top-layer rendering (no z-index management at all), `::backdrop` pseudo-element, `inert` on background content, Escape-to-close, implicit `role="dialog"` with `aria-modal="true"`, and focus movement in and out. Baseline since March 2022.

The gaps are real but small. `showModal()` doesn't prevent focus from escaping to the browser chrome — the WAI-ARIA APG says it should wrap, browsers disagree. We already had `FocusTrapMixin` in pages-primitives. Body scroll isn't locked. Backdrop clicks don't close the dialog. These are a few lines each.

The adversarial design review caught the biggest architectural mistake before any code was written. I'd composed `LiveRegionMixin` to announce the modal title to screen readers on open. The reviewer pointed out that `showModal()` makes `document.body` inert — and `LiveRegionMixin` appends its live region to `document.body`. The announcement would be silently swallowed. Native `<dialog>` with `aria-labelledby` already handles the announcement. We dropped `LiveRegionMixin` from the composition chain entirely.

The same review surfaced a second issue worth noting. `FocusTrapMixin` used `document.activeElement` to detect which element had focus — but when an element inside a shadow root has focus, `document.activeElement` returns the shadow host, not the inner element. The comparison `document.activeElement === lastFocusableElement` silently fails every time. The fix is a `getDeepActiveElement()` walk through `shadowRoot.activeElement` chains. This was a pre-existing bug, not specific to the modal — any focus management code in shadow DOM would hit it.

The component landed as a single Lit Web Component in pages-primitives: two variants (`dialog` and `alertdialog`), four size presets, animated entry/exit with `prefers-reduced-motion` support, and all close paths — `requestClose()`, `open = false`, `method="dialog"` form submission — converging on the native `close` event through `_handleNativeClose`. No cleanup duplication across paths.

The thing that surprised me most was how little custom code the modal needed once we leaned on the platform. The CSS is mostly token references. The JavaScript is mostly lifecycle wiring around native APIs. The hard part wasn't building the modal — it was the FocusTrapMixin fix that the modal exposed.
