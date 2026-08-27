# Scenario Controller Compact Overlay Mode

**Issue:** casehubio/casehub-pages#347
**Epic:** casehubio/parent#408 — Cross-Platform Scenario Engine
**Date:** 2026-08-22
**Status:** Draft

## Problem

The scenario controller (`<pages-scenario-controller>`) renders as a full-page layout. When embedded in a demo app like the helpdesk, it needs to float as a compact overlay so the app UI remains fully visible and interactive underneath.

## Design

### Mode property

Add a `mode` property to `PagesScenarioController`:

| Mode | Behaviour |
|------|-----------|
| `full` | Current layout — outline tree, transport bar, status bar. Default. |
| `compact` | Floating overlay — collapsed pill, expands to full controls on click. |

The property is a Lit `@property()` reflected as an attribute (`<pages-scenario-controller mode="compact">`).

### Compact mode — collapsed pill

A fixed-position pill in the bottom-right corner:

```
┌──────────────────────────┐
│ ▶ help-desk-basic   67%  │
└──────────────────────────┘
```

- Play/pause icon (clickable — toggles pause)
- Scenario name (or "No scenario" when idle)
- Progress percentage
- Background: semi-transparent dark with backdrop-filter blur
- Position: `fixed`, bottom-right, `z-index: 9999`
- Draggable via pointer events (pointerdown/pointermove/pointerup on the pill)
- Click anywhere on the pill (except the play/pause icon) toggles expanded state

### Compact mode — expanded

The pill expands into a floating card showing the full outline + transport:

```
┌──────────────────────────┐
│ ✕  help-desk-basic       │  ← header with close button
├──────────────────────────┤
│ Customer submits         │
│   ● Create ticket        │
│   ○ Verify classified    │
│ Specialist resolves      │
│   ○ Resolve ticket       │
├──────────────────────────┤
│ ⏸  ⏩  ═══●══  1.0x  67%│
└──────────────────────────┘
```

- Same floating position as the collapsed pill
- Card width: 280px, max-height: 60vh with overflow scroll
- The outline and transport are the same render methods as `full` mode — no duplication
- Close button (✕) collapses back to pill
- Clicking outside the card also collapses
- Draggable via the header bar

### Drag implementation

CSS `position: fixed` with `left`/`top` updated via pointer events:

1. `pointerdown` on drag handle → record offset, set `pointercapture`
2. `pointermove` → update `left`/`top` style
3. `pointerup` → release capture

No external library needed. The drag handle is the pill (collapsed) or the header bar (expanded).

### Helpdesk integration

Embed in `helpdesk/index.html`:

```html
<pages-scenario-controller mode="compact" baseurl="..."></pages-scenario-controller>
<script type="module">import '/scenario/controller.js';</script>
```

The `baseurl` attribute is set inline. The `firstUpdated()` lifecycle (fixed in this session) ensures the connection is established after Lit initializes the property.

### What doesn't change

- `ScenarioConnectionController` — same connection logic for both modes
- `ScenarioState`, `OutlineNode` interfaces — unchanged
- Push wire protocol — unchanged
- `remote.html` — unchanged (uses `mode="full"` by default)

## Implementation scope

### Files to modify

- `packages/pages-aria/src/controller/scenario-controller.ts` — add `mode` property, pill render, expand/collapse state, drag handler, conditional CSS
- `examples/helpdesk/.../index.html` — embed the controller with `mode="compact"`

### Files unchanged

- `scenario-connection-controller.ts` — no changes
- `remote.html` — already works as standalone

## Test plan

1. **Full mode unchanged** — existing render without `mode` attribute produces the same layout
2. **Compact collapsed** — renders pill with scenario name and progress
3. **Compact expand/collapse** — click toggles, ✕ closes
4. **Drag** — pointerdown/move/up repositions the overlay
5. **Live integration** — helpdesk index.html shows floating controller, scenario runs underneath

## References

- `packages/pages-aria/src/controller/scenario-controller.ts` — current controller component
- `packages/pages-aria/src/controller/scenario-connection-controller.ts` — connection logic
- `examples/helpdesk/src/main/resources/META-INF/resources/index.html` — helpdesk UI
- D21 — compact mode layout decision (pill + expand)
- D22 — scope decision (compact only)
