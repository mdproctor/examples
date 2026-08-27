## D1: Frame detach — wire function + existing DetachController + hideFrame/showFrame

**Choice:** Wire function adds a detach `extraButton` via `BackendAttachOptions`. On detach: call `engine.hideFrame(key)` (tears down Dockview panel, preserves config), create content in child window via the content factory, wire `EventRelay` and `DetachRegistry` using the existing `DetachController` infrastructure. On reattach: close child window, call `engine.showFrame(key)` (recreates Dockview panel from preserved config). `FrameLayout` gains `detached?: boolean` for persistence. Backend interface unchanged — detach is handled above the backend layer.
**Alternatives:**
- Extend `FloatingFrameBackend` with `onFrameDetach`/`detachFrame`/`reattachFrame` — puts child window management in the wrong layer; makes every future backend implementation understand detach semantics
- Use `adoptNode` pattern from dock panels — fights Dockview's destroy/recreate grain
**Rationale:** DetachController is backend-agnostic by design. `hideFrame()`/`showFrame()` already handle the Dockview panel lifecycle for this exact use case. The wire function is the right integration layer — it already manages all callback→event→state wiring. No backend interface changes needed.
**Trade-offs:** Detach button uses `extraButtons` rather than being built-in chrome. This means consumers who disable `extraButtons` would lose detach — acceptable since `extraButtons` is the designed extension point.
**Exploration:** quick
**Revised:** Yes — decision review R1-01 identified that DetachController is backend-agnostic and hideFrame/showFrame already handle the lifecycle. Backend interface expansion was unnecessary.
**Status:** revised

## D2: Organiser toolbar — plain DOM in activation callback

**Choice:** Plain DOM toolbar created by the activation callback, matching the pattern in `injectFrameChrome()`. A container `<div>` with preset buttons, each calling `engine.applyOrganiser()`. Subscribe to `pages-frame-create`/`pages-frame-close` events to toggle visibility (hidden when ≤1 frame). Active preset highlighted via CSS class. No Lit dependency.
**Alternatives:**
- Lit component `pages-organiser-toolbar` in pages-runtime — pages-runtime has zero Lit dependency; adding one breaches the architectural boundary that keeps Lit at the leaf level (pages-primitives, pages-viz, pages-table)
**Rationale:** The toolbar's "interactive state" is two CSS class toggles (active preset, visibility). `dockview-backend.ts` already builds all frame chrome as plain DOM. pages-runtime is framework-agnostic — adding Lit here violates ARC42STORIES §10.
**Trade-offs:** Slightly more verbose than Lit reactive properties, but consistent with established patterns and avoids a dependency boundary violation.
**Exploration:** quick
**Revised:** Yes — decision review R1-02 identified false claim that pages-runtime depends on Lit. It does not.
**Status:** revised

## D3: Snap-to-edge — zone state in FrameLayout

**Choice:** `FrameLayout` gains `snappedZone?: Zone` where `Zone = "left" | "right" | "top" | "bottom" | "top-left" | "top-right" | "bottom-left" | "bottom-right" | "full"`. When set, position/size are derived from the zone on container resize. Dragging away clears `snappedZone`. Pure functions in `frame-boundaries.ts`: `snapToZone(dragPosition, containerSize, threshold) → Zone | null` and `zoneToRect(zone, containerSize, gap) → { position, size }`. Persisted via `captureLayout()`.
**Alternatives:**
- Separate `FrameSnapRegistry` external to engine — fragile event ordering, must intercept every resize before engine processes it
- Edge-set model (`snappedEdges: Set<"left"|"right"|"top"|"bottom">`) — more extensible but adds complexity for zones not yet needed; the 9-string enum covers all documented requirements
**Rationale:** Zone is a frame property like `pinned` — it affects how position/size are computed. Keeping it in `FrameLayout` means the engine naturally handles resize recomputation. Single source of truth for frame geometry.
**Trade-offs:** Adds a field to the `FrameLayout` type — but it's optional and backward-compatible with saved layouts that lack it.
**Cross-decision note (D1×D3):** Detaching a snapped frame clears `snappedZone` — the child window has its own viewport. On reattach, `showFrame()` recreates from preserved config without zone constraint; the user re-snaps if desired.
**Exploration:** quick
**Status:** captured

## D4: Keyboard shortcuts — Alt-based bindings in frame-keyboard.ts

**Choice:** Alt-based modifier shortcuts to avoid browser interception: `Alt+Arrow` spatial nav, `Alt+1-9` focus by index, `Alt+[`/`Alt+]` cycle frames, `Alt+W` close focused frame, `Alt+P` toggle pin. Module exports a factory function `createFrameKeyboardHandler(engine, container, signal: AbortSignal)` that registers document keydown listeners. Extract `isInTextInput()` from `KeyboardShortcutMixin` to a shared utility in `pages-primitives/a11y` and import it.
**Alternatives:**
- Ctrl-based shortcuts (`Ctrl+Tab`, `Ctrl+Shift+W`) — browser-intercepted, events never reach JavaScript
- Vim-style leader key (`Ctrl+K` then single key) — adds a mode, harder to discover, conflicts with editor keybindings in centre content
**Rationale:** Alt-based modifiers are not intercepted by browsers. All shortcuts require modifiers so they don't fire during text input. No modal state to track. `isInTextInput()` extraction avoids duplication between `KeyboardShortcutMixin` and `frame-keyboard.ts`.
**Trade-offs:** `Alt+Arrow` may conflict with browser history navigation on some platforms (Firefox). Alt shortcuts activate menu bars on Windows — mitigated by `preventDefault()`.
**Exploration:** quick
**Revised:** Yes — decision review R1-04 identified that `Ctrl+Tab` and `Ctrl+Shift+W` are universally browser-intercepted and cannot work. Switched to Alt-based bindings. Also made `isInTextInput()` extraction explicit.
**Status:** revised

## D5: Frame animations — CSS enter/exit + Web Animations API for presets

**Choice:** Enter/exit animations via CSS classes: `.frame-entering` (opacity 0→1, scale 0.95→1) on `renderFrame()`, `.frame-exiting` (opacity 1→0, scale 1→0.95 with `animationend` before DOM removal) on `removeFrame()`. Organiser preset and snap-to-edge position changes use one-shot Web Animations API (`element.animate()`) which runs to completion without persistent CSS transition declarations — no drag interference. `prefers-reduced-motion` disables all. Duration via `--pages-frame-transition-duration` custom property. Animation styles injected into a separate `data-pages-frame-animations` style element (backend-agnostic, not coupled to Dockview CSS).
**Alternatives:**
- Persistent CSS transitions on position/size — conflicts with Dockview's inline style updates during drag; requires fragile drag-start detection (no `onDidChangeStart` signal from Dockview)
- JavaScript rAF interpolation — reimplements browser-native animation, harder for consumers to override
**Rationale:** Enter/exit CSS animations have no drag conflict. Web Animations API for position changes runs to completion as a one-shot — no persistent transition declarations that would animate drag movements. Separate style element keeps animations backend-agnostic.
**Trade-offs:** Web Animations API requires the engine to know the DOM elements for position changes — the wire function must bridge engine state changes to backend DOM. Slightly more wiring than pure CSS transitions.
**Exploration:** quick
**Revised:** Yes — decision review R1-05 identified that persistent CSS transitions conflict with drag (no drag-start signal from Dockview). Switched to CSS enter/exit + Web Animations API for position changes.
**Status:** revised
