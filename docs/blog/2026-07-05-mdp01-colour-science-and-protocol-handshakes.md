---
layout: post
title: "Colour science and protocol handshakes"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [design-tokens, push-protocol, oklch]
series: issue-101-tokens-and-push-ops
---

pages had a flat 13-token theme system — hardcoded hex values for `--pages-accent`, `--pages-bg`, `--pages-text` and a few friends. Good enough for prototyping, wrong for a foundation tier that other packages inherit from. blocks-ui had already solved this properly: 12-step colour scales in OKLCH space, derived from three inputs (base hue, accent hue, chroma) with Radix's perceptual lightness mapping. I decided to adopt the whole system rather than cherry-pick — colours, spacing, typography, elevation, motion, radius, density.

The interesting design choice was how little input the system needs. A `ThemeConfig` is four numbers. From those, you get 72 colour tokens across six semantic hues, where step 3 is a hover background, step 9 is a primary action surface, and step 12 is body text. OKLCH's perceptual uniformity means a warning-3 background and an info-3 background feel the same visual weight even though they're different hues. The old system couldn't do this — each colour was hand-picked independently.

The migration touched 21 files and deleted the entire old `theme.ts`. No compatibility aliases. `--pages-accent` became `--pages-accent-9`, `--pages-bg` became `--pages-neutral-1`, and every consumer was forced to be explicit about which step in the scale they meant. The adversarial design review caught one real bug in the mapping table: `--pages-bg-hover` and `--pages-bg-selected` had both been assigned to accent step 4, making hover and selection visually identical. Selected moved to step 5.

The push protocol work was architecturally different but shared a design principle: fix the protocol, don't protect callers. The existing wire ops — subscribe, unsubscribe, listen, unlisten — were all fire-and-forget. A client that sent `listen(["typo-topic"])` got silence. I added a required `id` field to every client→server message and ack/error responses from the server, making it a general correlation layer rather than a listen-specific patch. This meant breaking every existing wire call, which is fine — the platform has no external consumers.

The three push protocol features were designed as entangled rather than independent. Ack/error (#107) is the foundation — wildcard matching (#105) uses it to report invalid patterns, and event replay (#106) uses it to signal replay completion and gap detection. Wildcard matching adds prefix-based `*` patterns to `TopicRegistry`, splitting its internals into exact and wildcard maps with an O(n) scan over patterns at broadcast time. Event replay introduces an `EventStore` SPI with a default in-memory ring buffer — per-topic monotonic sequence numbers, bounded eviction, and a `topics()` method that lets wildcard expansion discover topics created during a client's disconnection.

The `seq` field changed from String to Long. The old string format (`"seq-43"`) can't be ordered, which makes replay impossible. A small breaking change with a disproportionate architectural benefit — every caller that passed a string seq is now forced to use a number, and the type system enforces it.

On the client side, `EventConnection.listen()` went from fire-and-forget void to `Promise<ListenAck>`. The connection tracks per-topic sequence numbers internally and on reconnect, builds a `since` map using two-phase construction: first seed exact topics at their known positions (or zero for full replay), then overlay concrete positions from the seq tracker that match current registrations. The server replays events before sending the ack — so when the Promise resolves, the client knows it's caught up.
