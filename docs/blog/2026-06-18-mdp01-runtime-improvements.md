---
layout: post
title: "Runtime Improvements: Tree Navigation, URL Encoding, and Lazy Pages"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [melviz]
tags: [navigation, lazy-page, url-encoding]
series: issue-17-runtime-improvements
---

The runtime code review (#13) left a backlog of four findings. Three were surgical fixes; one — lazy page activation — turned out to have more depth than it looked.

## The navigate() tree-walk

The existing `navigate()` in `site.ts` did a flat `querySelectorAll` across the entire DOM for interactive containers. Two problems: it hardcoded six container types while `INTERACTIVE_TYPES` in the navigation module had nine (tree, menu, and tiles were silently excluded), and the global query could misfire with duplicate slot names at different nesting depths. The fix walks the Component tree model instead of the DOM. `walkNavigate` descends the tree following path segments through interactive containers, using the container's `component.id` to locate its DOM element for activation. The search is model-driven; the activation is still DOM-touching. One ordering contract worth noting: each segment's `activateSlot` call must complete before the next segment is processed, because lazy containers render the next level's DOM synchronously during the swap.

## URL encoding

`serializeToUrl` inserted the page path raw — filter columns and values were encoded, but page path segments were not. A page name with a space, `?`, or `#` would break URL parsing. The fix splits on `/`, encodes each segment individually, and mirrors the decode on the parse side.

## Lazy page activation

This is the interesting one. The `lazy-page` component type had types and a type guard since the initial model spec, but the fetch/parse/render flow was never wired — a scope cut during the #13 implementation. The original site runtime spec (§7.4) had already designed the resolution flow including extending `PagePathMap`, `DataSetScope`, and `PageIndex`, so the design was there — we just needed to implement it.

Three activation paths emerged during spec review. Path A handles re-activation: when a lazy container swaps away from a slot containing a lazy page, `innerHTML = ""` destroys the DOM. When the user swaps back, a fresh div is created and `onNode` fires. The resolved content root is in `lazyPageResolutions` — re-render it immediately, no fetch. Path B handles a YAML cache hit: a different Component pointing to the same `href` gets the cached text, parses it into a fresh Component tree (independent identity — critical because `PagePathMap` keys on object identity), and renders synchronously. Path C is the async path: fetch, cache the YAML text, parse, extend all three runtime maps, register in `lazyPageResolutions`, render.

The cache key distinction matters. `lazyPageResolutions` is keyed by Component identity and controls map-extension deduplication. The YAML text cache is keyed by resolved URL and controls fetch deduplication. Rendering always happens on all three paths — the early spec draft had a "skip if resolved" check that would have left re-activated containers permanently empty.

## Accordion initial state

`wireAccordion` didn't explicitly set panel visibility — panels started with whatever `display` value they had from DOM creation. Adding `panel.style.display = ""` makes the "all sections expanded" contract explicit rather than relying on browser defaults.

## The extension APIs

The branch adds `extendPagePathMap`, `extendDataSetScope`, and `extendPageIndex` — thin wrappers around existing private walker functions that allow lazy-page resolution to extend runtime data structures incrementally. These are the building blocks that make tree integration work without rebuilding the entire map on every lazy-page fetch.

One known limitation: an initial deep-link through an unresolved lazy page (e.g. `#/page/Sales/Details` where Details is inside a lazy page) stops at the boundary. The fetch is in-flight when `navigate()` runs synchronously. The content renders when the fetch completes, but the URL isn't re-applied.
