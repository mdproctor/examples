---
layout: post
title: "Writing rules from the garden"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [protocols, design-tokens, web-components, event-contract, garden]
series: issue-118-ts-pages-protocols
---

The pages codebase has been accumulating conventions — token naming, event contracts, component strategy — but none of them lived anywhere a fresh session could find them. They were implicit in the code. This session made them explicit.

I started by examining how protocols already work across the CaseHub ecosystem. The garden has two platform-wide protocols (API interface taxonomy, routing strategy convention), both Java-focused. qhorus has 28 project-level protocols for its own domain. Pages had exactly one: version alignment with parent.

The question was where these new protocols belong. Garden-level protocols apply across repos — every repo with an `api/` module follows the API taxonomy. But CSS token naming, Lit component patterns, and `pages-event` contracts are pages-specific. No other repo defines OKLCH scales or authors Lit components. The answer was project-level, with one exception: two conventions that are genuinely universal to any Web Component project — Lit's immutable collection pattern and the `bubbles + composed` requirement for shadow DOM event crossing.

Those two became the first entries in a new `web/` namespace in the garden — the first non-Java protocol namespace. That felt like it deserved explicit rationale, and the design review agreed: the reviewer flagged it as architecturally significant, not routine.

The design review itself was thorough. Seventeen issues across four rounds. The reviewer caught real problems: the spec claimed "all inter-component communication uses `pages-event`" when the runtime actually has fifteen distinct framework event names (`pages-data-request`, `pages-filter`, `pages-sort`, etc.) that are separate CustomEvents. It also caught that the chroma reduction table had wrong range boundaries, and that the `GE-20260705-7c80f2` garden entry I kept referencing doesn't actually exist in the garden repo. Sixteen of the seventeen issues were verified in the spec; one was accepted as out of scope (the `@casehub/` vs `@casehubio/` package prefix inconsistency — real, but a different fix).

While the review ran, I audited the codebase for visual alignment. The token system is well adopted in the core — pages-primitives, pages-component, and pages-viz all use `--pages-*` tokens properly. But the examples gallery is entirely off-token (hardcoded `#667eea` gradients, raw px values), and the auth widgets use `#007bff` blues that don't match any semantic token. Filed #124 for the cleanup.

That audit led to a broader conversation about blocks-ui. The token system originated in pages and was backported to blocks-ui during early development. blocks-ui#21 is about dropping that copy and depending on pages-ui-tokens directly. The visual patterns blocks-ui established (text hierarchy at specific neutral steps, interactive states at accent-3/9, card styles with neutral-1 backgrounds) are pages patterns — blocks-ui was just the first to use them at scale.

The most interesting part of the session was a debate about the notification store in casehub-connectors. Their team had pushed back on using pages' push infrastructure, arguing that pages-data forces a columnar TypedDataSet model and notifications are rich domain objects. They're half right: TypedDataSet is the wrong data model for notifications. But they were conflating the data model with the transport. `EventConnection` and `PushMessage.event()` are completely payload-agnostic — the tabular assumption is confined to `PushSource`, and `EventConnection` explicitly bypasses it. The notification store could use `createEventConnection` today and receive rich nested payloads without flattening anything.

What's missing is convenience: a server-side `EventBroadcaster` that wraps the three-step append→route→send pattern into one call, and a Lit `EventStreamController` that manages subscription lifecycle reactively. Filed #125 as an epic with three children (#126 broadcaster, #127 controller, #128 documentation).

The documentation gap is the real lesson. The connectors team built a separate SSE endpoint because they didn't know `createEventConnection` existed. A capability that isn't discoverable doesn't exist.
