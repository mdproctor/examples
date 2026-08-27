---
layout: post
title: "The iframe API protocol is a wire contract, not a framework choice"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [protocols, iframe, postMessage]
---

The iframe component API had been sitting in the "if patterns emerge" queue since we wrote the first batch of casehub-pages protocols last session. I'd assumed there was something to formalise around the React rendering pattern — both existing iframe components use React, the dev harness renders React buttons, the entry point pattern is identical across them.

Turns out the interesting question isn't what's inside the iframe. It's what crosses the `postMessage` boundary. The iframe is the third-party extension point — we don't control what runs inside it, and we shouldn't try. A third party could use Lit, vanilla JS, Svelte, or anything else. The React coupling in our reference implementations is legacy, not contract.

That reframing made the protocol scope cleaner. Two protocols, not one: the message format (envelope shape, property keys, serialisation constraints) and the lifecycle sequence (INIT → DATASET ordering, configuration error flow, function call correlation). Neither mentions a rendering library.

The real find was the `Map` bug. `ComponentMessage.properties` is typed as `Map<MessageProperty | string, unknown>`, but `Map` objects don't reliably survive `postMessage` structured clone across real iframe boundaries. The current code works only because `BrowserComponentBus` posts to the same window — `window.postMessage` to self preserves the Map reference in-memory, no cloning happens. In a genuine cross-origin iframe, the data silently disappears. Filed as #130. The fix is straightforward — plain objects instead of Maps for the wire format — but the failure mode is nasty: silent data loss, masked entirely by the development setup.

The terminal component was the other useful data point. It lives under `components/` alongside the iframe components but doesn't use the iframe API at all — it's a vanilla custom element communicating via `CustomEvent`. That confirmed the scope: `components/` is an organisational directory, not an architectural commitment. The iframe protocol covers `postMessage`-based isolation; the existing web-component-strategy protocol covers DOM-embedded elements.
