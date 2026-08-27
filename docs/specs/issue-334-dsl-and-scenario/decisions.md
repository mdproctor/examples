## D1: Display mode selection

**Choice:** `display` property on show-markdown action — `panel` (default) or `modal`
**Alternatives:**
- Separate action (`show-slide`) — clearer intent but more surface area
- Section-level property — less flexible, less repetitive
**Rationale:** One action, one property. Simple and explicit. Defaults to panel for backward compat.
**Trade-offs:** Slightly more complex handler logic to branch on display mode.
**Sources:** scenario-handler.ts:222, issue #365
**Exploration:** quick
**Status:** captured

## D2: Panel display behavior

**Choice:** Fire-and-forget into Guide tab, no auto-open, no auto-switch
**Alternatives:**
- Auto-open + switch — forces the panel open, more intrusive
- Badge notification — subtle but loses passive visibility
**Rationale:** User controls the viewer. If Guide tab is open, content appears naturally. No forced UI state changes.
**Trade-offs:** User might miss content if Guide tab isn't open. Acceptable — it's reference material, not a gate.
**Sources:** User direction
**Exploration:** quick
**Status:** captured

## D3: Guide tab content persistence

**Choice:** Keep last show-markdown content until the next show-markdown replaces it
**Alternatives:**
- Clear on advance — shows content only during the show-markdown step
**Rationale:** Users can glance back at tutorial text while interaction steps (click, fill) run. The Guide tab is a reference panel.
**Trade-offs:** Stale content could confuse if steps are far apart. Mitigated by replacement on next show-markdown.
**Sources:** User direction
**Exploration:** quick
**Status:** captured

## D4: Modal slide behavior

**Choice:** Blocking full-screen overlay with mini controller (slide list, current indicator, click-to-advance)
**Alternatives:**
- Non-blocking modal — doesn't fit presentation format
**Rationale:** Modal slides are a presentation format. The user reads, clicks Next, reads the next slide. The blocking happens entirely in-browser (promise resolves on Next click), so no server roundtrip needed.
**Trade-offs:** Modal blocks all other interaction until dismissed.
**Sources:** User direction
**Exploration:** quick
**Status:** captured

## D5: Modal slide grouping

**Choice:** Consecutive show-markdown steps with `display: modal` auto-group into a deck
**Alternatives:**
- Explicit `deck` property — more ceremony in YAML
**Rationale:** Consecutive sequence is the natural way to define a slide deck. A non-modal step breaks the sequence.
**Trade-offs:** Can't interleave non-modal steps within a single deck. If needed, use separate decks.
**Sources:** User direction
**Exploration:** quick
**Status:** captured

## D6: Outline step icons

**Choice:** Unicode symbols per step type — 📝 show-markdown, 🔆 spotlight, 👆 click, ✍ fill
**Alternatives:**
- Inline SVG — pixel-perfect but more code
- CSS letter badges — minimal but less recognizable
**Rationale:** Lightweight, no asset dependencies. Icons replace ○ for pending steps; ✓ and ● stay for completed/current.
**Trade-offs:** Unicode rendering varies across OS. Acceptable for dev-facing tooling. Unmapped types (select, expand, collapse, wait, assert, ready, navigate) keep ○ as fallback.
**Sources:** User direction
**Exploration:** quick
**Status:** captured

## D7: Deck dispatch strategy

**Choice:** Outline look-ahead — browser uses the already-fetched scenario outline to determine deck boundaries
**Alternatives:**
- Server batch dispatch — more accurate but requires orchestrator changes
- Progressive deck — simplest but no total count in mini controller
**Rationale:** The outline is already fetched by the controller. When a modal step arrives, the browser scans consecutive show-markdown labels in the outline to determine deck size. No server changes needed.
**Trade-offs:** Relies on outline labels matching step names. If outline isn't loaded yet, deck shows without total (progressive fallback).
**Depends on:** D5 (auto-grouping definition)
**Sources:** Decision review finding — D4/D5 tension
**Exploration:** quick
**Status:** captured

## D8: Modal escape behavior

**Choice:** Escape closes the modal deck entirely and skips remaining slides, resuming from the next non-modal step
**Alternatives:**
- No escape — must click through all slides
**Rationale:** Clean exit for users who don't want to read slides. Forcing engagement is frustrating.
**Trade-offs:** Users might accidentally dismiss. Low risk — Escape is intentional.
**Sources:** Decision review finding
**Exploration:** quick
**Status:** captured
