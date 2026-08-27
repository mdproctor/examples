## D1: Auto-wiring architecture — wire function, engine stays pure

**Choice:** Keep the engine as a pure state manager with no DOM dependencies. Extract a `wireFloatingWorkspace(engine, backend, container)` integration function that subscribes to all backend callbacks, updates engine state, and dispatches CustomEvents on the container. The stale-position bug is fixed inside the wire function (updates engine's position/size from backend move/resize callbacks). site.ts retains `scheduleLayoutSave()` listeners on the emitted events.
**Alternatives:**
- Engine auto-wires internally — adds DOM dependency to the engine, sacrifices testability for ~28 lines of boilerplate reduction
- Engine owns persistence too — tighter coupling, eliminates more code but makes engine depend on save semantics
- Keep current layering — no wire function, just fix stale-position in site.ts — preserves status quo but doesn't reduce boilerplate
**Rationale:** The engine's purity is architecturally valuable — tests run without jsdom, the engine can be used in non-DOM contexts, and the state machine behavior is easy to reason about. A wire function extracts the integration boilerplate from activation.ts without contaminating the engine. The wire function is also independently testable.
**Trade-offs:** One more function in the public API. The wire function is tightly coupled to both engine and backend — but that's its job.
**Exploration:** quick
**Revised:** Yes — decision review R1-01/R1-08 identified that converting the engine from a pure state machine to a DOM-coupled event hub sacrifices testability for marginal boilerplate reduction. Wire function preserves purity.
**Status:** revised

## D2: Close/pin signals via backend callbacks

**Choice:** Add `onFrameClose` and `onFramePin` callbacks to the `FloatingFrameBackend` interface. Chrome buttons call these callbacks directly instead of dispatching DOM events. The wire function (D1) subscribes, handles state changes via engine methods, then dispatches CustomEvents.
**Alternatives:**
- Wire function listens to DOM events on container — couples it to DOM event names and relies on backend chrome dispatch order
- Backend emits both callback and DOM event — redundant
**Rationale:** All backend→consumer signals should flow through the same typed callback mechanism. Consistent with onFrameMove/onFrameResize/onTabDragOut/onTabReorder. The wire function becomes the single dispatcher of DOM events, not the backend chrome.
**Trade-offs:** Breaking change to the backend interface — any custom backend implementation must add the two new callbacks. Acceptable because the interface is internal and only one implementation exists (DockviewBackend).
**Exploration:** quick
**Status:** captured

## D3: Pin visual state — opacity + aria-pressed + CSS class

**Choice:** Pin button uses opacity:0.5 when unpinned, opacity:1.0 when pinned. Additionally, `aria-pressed` attribute is toggled for screen reader accessibility, and a `.frame-pin-active` CSS class is added/removed for consumer styling flexibility. Backend updates all three when the pin state changes via `updatePinState`.
**Alternatives:**
- Opacity only — fails WCAG 1.4.1 / 1.4.11 (state communicated solely through opacity)
- Icon swap — clearer signal but harder with emoji
**Rationale:** Three complementary signals: opacity for visual indication, aria-pressed for accessibility, CSS class for consumer styling. Addresses WCAG requirements while keeping the minimal visual style.
**Trade-offs:** Slightly more complex update logic in `updatePinState`, but all three updates are trivial one-liners.
**Exploration:** quick
**Revised:** Yes — decision review R1-03 identified WCAG accessibility failure with opacity-only indication. Added aria-pressed and CSS class.
**Status:** revised

## D4: Extra buttons config on attach() options

**Choice:** `backend.attach(container, contentFactory, { extraButtons: [...] })`. All consumer config in one place. Extra buttons are global — every frame gets the same buttons.
**Alternatives:**
- ChromeFactory injected by activation layer — more flexible but overengineered for the stated need
- Per-frame on renderFrame — more flexible but more complex
- Separate method — allows runtime reconfiguration but adds stateful API
**Rationale:** The common case is host-level buttons (detach-to-window, hide-to-tray) that apply to all frames. The backend injects chrome, so it's the natural layer to accept chrome config. Per-frame buttons are a YAGNI concern — if needed later, extraButtons can be extended to accept a factory function.
**Trade-offs:** No per-frame customization. If a consumer needs different buttons for different frames, they'd need the escape hatch.
**Exploration:** quick
**Status:** captured

## D5: Tiling affordance — deferred to follow-up issue

**Choice:** Defer the per-frame tiling dropdown to a separate issue. Focus #303 on auto-wiring, pluggable chrome, pin state/drag lock, and tab-bar drag fix.
**Alternatives:**
- Titlebar button with dropdown — underspecified behavior for edge cases (what does "Tile Left" mean when another frame is already tiled left? what happens to other frames on "Maximize"? where is pre-tile position stored?)
- Double-click titlebar maximize/restore — simpler but limited
**Rationale:** Per-frame tiling is OS-level window management behavior with significant interaction design complexity. Rushing it as part of a wire-up/chrome issue risks underspecified behavior and a second tiling system alongside the existing organiser presets. A dedicated issue can properly design the interaction model, relationship to organisers, and edge cases.
**Trade-offs:** Issue #303 delivers less than originally scoped. Pin drag lock (the other half of item 3) is still in scope.
**Exploration:** quick
**Revised:** Yes — decision review R1-05 identified that the tiling dropdown duplicates the organiser system and underestimates the complexity of OS-level window tiling.
**Status:** revised

## D6: Pin drag lock — Dockview API first, pointerdown fallback

**Choice:** Investigate Dockview's `group.locked` API first — it's in the TypeScript type definitions and designed for exactly this use case. If available and functional, use it. Fallback to `pointerdown stopPropagation` on the titlebar when the frame is pinned (prevents drag initiation without killing all pointer interactions on the titlebar).
**Alternatives:**
- CSS pointer-events:none — blunt instrument that kills all titlebar interactions, may not prevent drag if Dockview initiates from a parent element
- Intercept and cancel drag events — similar to the fallback but less precise
**Rationale:** First-party API is always preferable to CSS hacks. `group.locked` is purpose-built for preventing drag/drop. The `pointerdown stopPropagation` fallback is more precise than `pointer-events:none` because it only prevents drag initiation while preserving hover, context menu, and other pointer interactions.
**Trade-offs:** Depends on Dockview's `group.locked` actually preventing floating group drag (not just tab drag within a group). Must be verified during implementation.
**Exploration:** quick
**Revised:** Yes — decision review R1-06 identified that pointer-events:none is a blunt instrument and that Dockview has a first-party API (`group.locked`) for this use case.
**Status:** revised

## D7: Tab-bar drag fix — Dockview config first, CSS fallback

**Choice:** Investigate Dockview's configuration options first (`locked`, `disableDnd`, panel-level options) to suppress tab-bar drag in floating groups. If no config option applies, fall back to CSS override on the tab container empty space.
**Alternatives:**
- CSS-only — works but depends on Dockview's internal CSS class names which can break on version bumps
**Rationale:** First-party configuration is more robust than CSS class name dependencies. Dockview has several drag-related options that may cover this case. CSS fallback is acceptable if no config option exists, but should be a last resort.
**Trade-offs:** Implementation requires investigating Dockview's drag-related APIs during development. May discover that no API covers this specific case and CSS is necessary.
**Exploration:** quick
**Revised:** Yes — decision review R1-07 identified that Dockview config options were not investigated before choosing CSS.
**Status:** revised
