# Tabbed Viewer and Modal Slides

**Branch:** issue-334-dsl-and-scenario
**Issue:** casehubio/casehub-pages#365
**Date:** 2026-08-25

## Summary

Extend the scenario YAML viewer into a tabbed panel (Source + Guide) and
add a full-screen modal slide deck mode for presentation-format markdown.
Add type-specific icons to the scenario outline.

## YAML Syntax

The `show-markdown` action gains a `display` property:

```yaml
# Panel mode (default) — fire-and-forget into Guide tab
- show-markdown:
    content: |
      ## Introduction
      Tutorial text here.

# Modal mode — blocking full-screen slide
- show-markdown:
    display: modal
    content: |
      ## Welcome
      Full-screen presentation slide.

# File reference with section extraction
- show-markdown:
    file: tutorial/guide.md
    section: "REST API"

# Modal with file reference
- show-markdown:
    display: modal
    file: tutorial/guide.md
    section: "Overview"
```

`display` defaults to `panel`. Consecutive `display: modal` steps auto-group
into a slide deck.

## Component Architecture

### Tabbed Viewer

`PagesScenarioYamlViewer` gains a tab bar and Guide tab. No new components.

```
PagesScenarioYamlViewer
├── Tab bar: [Source] [Guide]
├── Source tab: existing YAML rendering (unchanged)
├── Guide tab: markdown rendering (from PagesScenarioNarrative)
└── Drag, dock, undock, detach, resize — carry both tabs
```

**Guide tab behavior:**
- Listens for `scenario-narrative` events on `eventTarget` (reactive binding
  via `willUpdate`, same pattern as the narrative component fix)
- Content persists until the next `show-markdown` replaces it
- No auto-open, no auto-switch — user controls the viewer
- Empty state: "No guide content" (italic, muted)

**Markdown rendering:** Lift `_renderMarkdown`, `_fetchTemplate`, and
`_extractSection` from `PagesScenarioNarrative` into the viewer. These
are pure rendering methods with no component-specific dependencies.

**`PagesScenarioNarrative` stays unchanged** — it's still used by the
connection-based content path. The viewer is a second consumer of
`scenario-narrative` events.

### Modal Slide Deck

When `display: modal`, the scenario handler creates a full-screen overlay
managed entirely in-browser.

**Overlay structure:**

```
┌──────────────────────────────────────────────────┐
│  ← Back                            Slide 2 of 4 │
│──────────────────────────────────────────────────│
│                                                  │
│  ## REST API Integration                         │
│                                                  │
│  Tickets don't only come from the web form...    │
│                                                  │
│──────────────────────────────────────────────────│
│  ○ Intro  ● REST API  ○ Routing  ○ Summary       │
│                                    [Next →]       │
└──────────────────────────────────────────────────┘
```

**Mini controller:** Shows slide names (from step labels), current slide
indicator, dot navigation, Next button.

**Flow:**

1. First modal step arrives in `executeAriaCommand`
2. Handler checks the scenario outline for consecutive modal steps at the
   current position — determines deck size (N) and slide labels
3. Handler creates the overlay, renders slide 1, shows "Slide 1 of N"
4. Handler sets `_activeDeck` state on the handler instance
5. Step-result sent, returns void (non-blocking)
6. Server dispatches the next step (next modal slide)
7. Handler's `executeAriaCommand` detects `_activeDeck` is set and the
   incoming step is `display: modal` — updates the overlay content to the
   next slide instead of creating a new overlay
8. **Next** button dispatches step/resume via the eventTarget to advance
   the scenario (triggers server to dispatch the next step)
9. Last slide's Next or a non-modal step arriving → dismisses overlay,
   clears `_activeDeck`
10. **Escape** or **Back on slide 1** → dismisses overlay, clears
    `_activeDeck`, remaining deck steps execute silently (no overlay)

The modal returns void (non-blocking from `executeSequence`'s perspective).
The overlay manages its own lifecycle. When dismissed, it dispatches
`scenario-narrative-dismiss` for consistency.

**Deck metadata from outline:** The outline (already fetched by the
controller) provides step labels and action types. The handler receives
the outline data via a shared reference or re-fetches it. When a modal
step arrives, it scans forward in the outline from the current position
to count consecutive `show-markdown` + `display: modal` steps. This
gives the mini controller its total count and slide labels without
requiring the server to batch steps.

**Single-slide deck:** When N=1, simplify the UI — no dot navigation,
just content with a dismiss button. The mini controller is omitted.

**Handler state:**

```typescript
interface ActiveDeck {
  total: number;
  current: number;
  labels: string[];
  overlay: HTMLElement;
}
let _activeDeck: ActiveDeck | null = null;
```

### Outline Type Icons

The controller's `_renderNode` adds a type icon between the status
indicator and the label.

**Icon mapping:**

| Action | Icon |
|--------|------|
| `show-markdown` | 📝 |
| `spotlight` | 🔆 |
| `click` | 👆 |
| `fill` | ✍ |
| `navigate` | ➜ |
| unmapped | ○ |

Icons show for pending (○) and current (●) steps. Completed steps keep ✓.

**Data source:** Extend the server's `/scenario/outline` response to
include an `action` field on leaf nodes. The controller reads it and maps
to an icon. Unknown actions fall back to ○.

## Data Flow

### Panel mode

```
YAML step (display: panel)
  → scenario-handler.ts executeAriaCommand
  → dispatch CustomEvent('scenario-narrative', { type, markdown, path, section })
  → return void (non-blocking)

PagesScenarioYamlViewer (Guide tab)
  → willUpdate binds eventTarget listeners
  → _onNarrative stores content in _guideContent state
  → Guide tab renders markdown
  → Content persists until next scenario-narrative event
```

### Modal mode

```
First YAML step (display: modal)
  → scenario-handler.ts executeAriaCommand
  → check outline for consecutive modal steps → deck size N
  → create overlay, render slide 1, set _activeDeck
  → return void (non-blocking), send step-result

Subsequent modal steps (while _activeDeck is set)
  → executeAriaCommand detects _activeDeck
  → update overlay content to next slide
  → return void, send step-result

Next button in mini controller
  → dispatch step/resume on eventTarget
  → server dispatches next step → cycle continues

Non-modal step arrives (or Escape)
  → dismiss overlay, clear _activeDeck
  → execute non-modal step normally
```

## Files Changed

| File | Change |
|------|--------|
| `packages/pages-aria/src/controller/scenario-yaml-viewer.ts` | Add tab bar, Guide tab, markdown rendering, event listener |
| `packages/pages-aria/src/server/scenario-handler.ts` | Branch on `display` property, modal overlay creation, deck look-ahead |
| `packages/pages-aria/src/controller/scenario-controller.ts` | Type icons in `_renderNode`, icon mapping |
| `packages/pages-aria/src/scenario/parser.ts` | Pass `display` property through parser |
| Server: scenario outline endpoint | Add `action` field to leaf nodes |

## Testing

- **Unit:** YAML viewer renders Guide tab with markdown content
- **Unit:** Modal deck groups consecutive modal steps from queue
- **Unit:** Outline icon mapping for all action types
- **E2E:** Panel mode — show-markdown content appears in Guide tab
- **E2E:** Modal mode — full-screen deck with Next/Back/Escape
- **E2E:** Mixed — panel and modal steps in same scenario

## References

- `packages/pages-aria/src/controller/scenario-yaml-viewer.ts` — existing viewer
- `packages/pages-aria/src/controller/scenario-narrative.ts` — markdown rendering source
- `packages/pages-aria/src/server/scenario-handler.ts:222` — show-markdown handler
- `packages/pages-aria/src/controller/scenario-controller.ts:270` — outline rendering
- Issue casehubio/casehub-pages#365 — show-markdown action
- decisions.md D1-D8 — all design decisions
