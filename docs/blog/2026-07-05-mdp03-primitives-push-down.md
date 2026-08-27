---
layout: post
title: "Pushing Primitives Down the Stack"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [architecture, lit, wildcard-matching, web-components]
---

blocks-ui-core accumulated a set of primitives that had nothing to do with blocks-ui's domain — a11y mixins, an event bus, a schema-driven form renderer, an SSE manager. They ended up there because pages didn't have them yet. With pages now the foundation tier, the right move is to push everything domain-agnostic down.

## What moved, and where

The question wasn't just "move it" but "where does each thing land?" blocks-ui-core has a Lit dependency. Pages' core packages are vanilla TypeScript. Adding Lit to `pages-component` would pollute a framework-agnostic package that other packages depend on.

The answer: a new `pages-primitives` package. Standalone Lit library, depends on nothing in pages. Components use `--pages-*` CSS custom properties by convention but have no code coupling to the token generator. `pages-component` stays vanilla and gains only the event helpers (`emitPagesEvent`/`onPagesEvent`) — pure TypeScript, no framework.

The a11y mixins ported cleanly. `RovingTabindexMixin` needed one enhancement: blocks-ui only handled vertical navigation (ArrowUp/ArrowDown). The new filter chips and scope selector are horizontal bars, so `rovingDirection` became an abstract property — `'horizontal' | 'vertical' | 'both'`, mapping to the correct arrow keys per WAI-ARIA.

## Wildcard matching got real

The TopicRegistry supported only trailing `*` — `debate:*` matched anything starting with `debate:`. We split this into two distinct wildcards: `*` for exactly one segment and `**` for zero or more. `debate:*:summary` now matches `debate:abc:summary` but not `debate:abc:def:summary`. The implementation is identical in Java and TypeScript — segment-by-segment comparison, same algorithm, same edge cases.

The breaking change was deliberate. `debate:*` used to mean "any depth" — now it means "one segment." Callers wanting the old behaviour use `debate:**`. No shims, no compatibility layer. The `isValidTopicOrPattern` validation was rewritten from scratch to handle mid-position wildcards and the new `**` constraint (must be last segment).

## Two new components

`<pages-filter-chips>` and `<pages-scope-selector>` — the first components built directly in pages rather than being ported. Both compose `RovingTabindexMixin` with horizontal direction, both use `--pages-*` tokens with fallback values for standalone use.

The SSEManager from blocks-ui was dropped entirely. `EventConnection` already covers connection sharing, exponential backoff, and status tracking. What it lacked — a richer status enum (`connected`/`reconnecting`/`disconnected`) and rAF batching — was added as opt-in enhancements rather than maintaining a parallel abstraction.
