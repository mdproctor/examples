# Design: Visual Feedback for Automated UI Interactions (#351)

## Context

When the browser executor automates UI interactions during scenario demos,
there is no visual feedback. Fields change silently, buttons activate without
indication. The audience cannot follow what is happening.

## Architecture

A new `visual-feedback.ts` module in `packages/pages-aria/src/executor/`
provides three functions. The scenario handler calls them before/after each
command. The executor (`command-executor.ts`) stays pure.

### `injectStyles(): void`

Injects a `<style id="scenario-feedback-styles">` tag into `document.head`.
Idempotent — checks for existing tag. Contains CSS for highlight animations
and focus rings. Uses `!important` to override application styles.

### `highlightElement(el: Element, type: 'click' | 'fill' | 'select'): void`

Adds `scenario-highlight` CSS class to the element. Schedules removal after
400ms. The class triggers a pulse animation (outline + expanding box-shadow).

### `typeText(el: HTMLInputElement | HTMLTextAreaElement, value: string, speed?: number): Promise<void>`

Progressive character reveal:
1. Focus the element, add `scenario-typing` class
2. Loop: `el.value = value.slice(0, i)`, dispatch `input` event, wait
   `speed` ms (default 40ms per character)
3. Final character: dispatch `change` event, remove `scenario-typing`

## CSS Animations

```css
.scenario-highlight {
  outline: 2px solid rgba(56, 189, 248, 0.8) !important;
  outline-offset: 2px;
  animation: scenario-pulse 0.4s ease-out;
}
@keyframes scenario-pulse {
  0% { box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.4); }
  100% { box-shadow: 0 0 0 8px rgba(56, 189, 248, 0); }
}
.scenario-typing {
  outline: 2px solid rgba(134, 239, 172, 0.8) !important;
  outline-offset: 2px;
  box-shadow: 0 0 8px rgba(134, 239, 172, 0.3);
}
```

## Integration

`scenario-handler.ts` modified to call visual feedback before each command:
1. Import `resolveTarget` from executor (already exported)
2. Import `injectStyles`, `highlightElement`, `typeText` from visual-feedback
3. Before command execution: resolve target, call `injectStyles()` once,
   call `highlightElement(el, type)`
4. For `fill`: use `typeText(el, value)` instead of `fill(target, value)`.
   Dispatch input/change events as part of typeText.
5. For `click`/`select`: highlight then execute normally

## Files

| File | What |
|------|------|
| `packages/pages-aria/src/executor/visual-feedback.ts` | injectStyles, highlightElement, typeText |
| `packages/pages-aria/src/executor/visual-feedback.test.ts` | Tests |
| `packages/pages-aria/src/server/scenario-handler.ts` | Modified — visual feedback integration |

## Testing

- `injectStyles()`: injects style tag, is idempotent
- `highlightElement()`: adds class, removes after delay
- `typeText()`: progressively fills value, dispatches events
- `scenario-handler`: existing tests still pass with visual feedback wired in

## References

- casehubio/casehub-pages#351 — issue
- `packages/pages-aria/src/executor/command-executor.ts` — pure executor (unchanged)
- `packages/pages-aria/src/server/scenario-handler.ts` — orchestration layer
- D26, D27 in decisions.md
