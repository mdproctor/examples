# Scenario Controller UI — Visual Transport Controls and Outline

> **Issue:** casehubio/casehub-pages#341
> **Epic:** casehubio/parent#408 — Cross-Platform Scenario Engine
> **Date:** 2026-08-21
> **Status:** Draft

## 1. Overview

The scenario engine (issues #418, #332, #340, #342) delivers orchestration,
distributed executor dispatch, and narrative content. The missing piece is a
controller UI that lets a demo operator see the scenario structure, control
execution pace, and optionally view narrative content — from the same device
or a separate one (presenter remote).

This spec covers:
1. Backend additions — `scenario:state` push wire broadcast, `GET /scenario/outline`
2. `<scenario-controller>` — Lit web component with outline tree and transport controls
3. `<scenario-narrative>` — Lit web component for narrative content rendering
4. Standalone `/scenario/remote` page

### 1.1 Constraints

- **Single-session orchestrator:** one scenario at a time per Pages instance
  (ScenarioOrchestrator is `@ApplicationScoped` with volatile fields). The
  controller UI doesn't multi-tenant — it shows whatever's running.
- **REST + push wire only:** no GraphQL or MCP endpoints for the controller.
  Those can be added later if a consumer needs them.
- **Pre-release:** breaking changes to public API are acceptable.

### 1.2 Prerequisites

The following backend gaps must be addressed as part of this issue:

- **`stop()` implementation:** `ScenarioOrchestrator` has no `stop()` method.
  `ScenarioControlResource.stop()` is a no-op. Implement: clear session,
  broadcast `executor-control: stop` to all executors, broadcast idle state.
- **`runTo()` completion:** The current `runTo()` just calls `resume()` —
  it does not override speed to maximum, track the target label, or pause
  when the target is reached. Implement the full semantics from the
  protocol spec §5.5.
- **Jackson annotations on `NarrativeContent`:** The sealed interface needs
  `@JsonTypeInfo` and `@JsonSubTypes` for polymorphic serialization so the
  wire format includes a type discriminator.

## 2. Backend Additions

### 2.1 State broadcast — `scenario:state` topic

The `ScenarioOrchestrator` currently serves state via `GET /scenario/state`
and broadcasts `executor-control` messages to executors, but does not push
state changes to listening controllers. Add state broadcasting.

**What changes in `ScenarioOrchestrator`:**

1. Inject `EventBroadcaster` (from push-runtime).
2. After every state-mutating operation (`start`, `pause`, `resume`, `step`,
   `speed`, `onStepResult`), call:
   ```java
   broadcaster.broadcast("scenario:state", state());
   ```
3. The `EventBroadcaster.broadcast()` serialises `ScenarioState` as the
   event payload and delivers it to all sessions listening on `scenario:state`.

**Wire format — `op: "event"` on topic `scenario:state`:**

```json
{
  "op": "event",
  "topic": "scenario:state",
  "payload": {
    "scenario": "help-desk-demo",
    "chapter": "Customer Reports Issue",
    "section": "Customer sends message",
    "step": "Submit support request",
    "paused": false,
    "speed": 1.0,
    "progress": 0.33,
    "content": {"type": "inline", "markdown": "The customer fills out..."},
    "slides": null
  },
  "seq": 42
}
```

The `content` field is polymorphic. `NarrativeContent` requires Jackson
annotations for wire serialization:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NarrativeContent.Inline.class, name = "inline"),
    @JsonSubTypes.Type(value = NarrativeContent.Template.class, name = "template"),
    @JsonSubTypes.Type(value = NarrativeContent.Slide.class, name = "slide"),
})
public sealed interface NarrativeContent { ... }
```

This produces the `{"type": "inline", "markdown": "..."}` discriminator
shown in the wire format example above.

**When to broadcast:**
- `start()` — initial state after dispatch
- `pause()` — paused=true
- `resume()` — paused=false
- `step()` — after the step executes (paused=true)
- `speed()` — new speed value
- `onStepResult()` — position advances, progress updates
- `stop()` — idle state (null scenario)

### 2.2 Outline endpoint — `GET /scenario/outline`

Add to `ScenarioControlResource`:

```java
@GET
@Path("/outline")
public ScenarioOutline outline() {
    return orchestrator.outline();
}
```

**Response model:**

Rather than maintaining parallel type hierarchies, `outline()` projects
from the existing `HierarchicalScenario` model using a single recursive
node type:

```java
public record OutlineNode(String label, String target,
                          List<OutlineNode> children) {
    public OutlineNode(String label, List<OutlineNode> children) {
        this(label, null, children);
    }
    public OutlineNode(String label, String target) {
        this(label, target, List.of());
    }
}
```

Chapters become nodes with section children. Sections become nodes with
step children. Steps are leaf nodes with a `target` (executor name).
The `orchestrator.outline()` method walks the `HierarchicalScenario`
tree and strips commands, triggers, and content — returning only labels
and structure.

The outline is static per scenario run — just the hierarchy and labels
that the "run to" feature needs.

Returns `404` if no scenario is active.

## 3. `<scenario-controller>` Web Component

### 3.1 Package and registration

Lives in `packages/pages-aria/src/controller/`. The `pages-aria` package
must add `lit` as an explicit dependency (it currently has `pages-primitives`
which depends on Lit transitively, but the direct dependency must be
declared for a package that defines LitElement subclasses).

```
packages/pages-aria/src/controller/
  scenario-controller.ts     — LitElement
  scenario-controller.test.ts
  index.ts                   — export + customElements.define
```

Registered as `<scenario-controller>`. Exported from `pages-aria` index.

### 3.2 Properties

```typescript
@property({ attribute: false })
connection?: EventConnection;        // Embedded mode — shared connection

@property({ attribute: false })
eventTarget?: EventTarget;           // Embedded mode — where push events dispatch

@property()
baseUrl?: string;                    // Remote mode — e.g. "http://localhost:8080"
```

The `eventTarget` property is required in embedded mode because
`EventConnection` does not expose its internal `eventTarget` — it
dispatches `pages-event` CustomEvents on the `PushSourceConfig.eventTarget`
passed at creation time. The host must provide both the connection (for
`listen`/`send`) and the eventTarget (for receiving dispatched events).
This matches `createScenarioHandler`'s existing pattern of accepting both.

In remote mode, the component creates its own `EventConnection` with its
own `EventTarget`, so no external `eventTarget` is needed.

**Mode resolution:**
- If `connection` is set → embedded mode. Use the provided connection for
  listening. Receive events on `eventTarget`. REST base URL is `baseUrl`
  if provided, otherwise `window.location.origin`.
- If only `baseUrl` is set → remote mode. Create an internal
  `EventConnection` with a private `EventTarget`. Derive WebSocket URL
  from baseUrl: `${baseUrl.replace(/^http/, 'ws')}/ws/push`. REST base
  is `baseUrl`.
- If neither → render an error state ("No connection configured").

### 3.3 Internal state

```typescript
interface ControllerState {
  scenario: string | null;
  chapter: string | null;
  section: string | null;
  step: string | null;
  paused: boolean;
  speed: number;
  progress: number;
}

interface Outline {
  scenario: string;
  chapters?: Array<{ label: string; sections: Array<{ label: string; steps: Array<{ label: string; target: string }> }> }>;
  sections?: Array<{ label: string; steps: Array<{ label: string; target: string }> }>;
  steps?: Array<{ label: string; target: string }>;
}
```

All state is reactive (`@state()` decorator) — changes trigger re-render.

### 3.4 Lifecycle

**`connectedCallback()`:**
1. If remote mode → create internal `EventConnection` to `${baseUrl}/ws/push`
2. Listen on `scenario:state` via connection
3. Register event handler for `pages-event` with topic `scenario:state`
4. Fetch initial state: `GET ${restBase}/scenario/state`
5. If state has an active scenario → fetch outline: `GET ${restBase}/scenario/outline`

**`disconnectedCallback()`:**
1. Unlisten `scenario:state`
2. If remote mode → close internal connection

**On `scenario:state` event received:**
1. Update internal state from payload
2. If `scenario` changed (new scenario started) → re-fetch outline
3. If `scenario` is null → clear outline (scenario ended)

### 3.5 Rendering — three sections

**Outline panel (top):**

A collapsible tree showing the chapter/section/step hierarchy. The current
position is highlighted. Each label is clickable — clicking triggers a
"run to" command for that label.

```
▾ Customer Reports Issue          ← chapter (bold)
  ▾ Customer sends message        ← section
    ● Submit support request      ← step (current — highlighted)
    ○ System classifies ticket    ← step (pending)
  ▸ Specialist resolves           ← section (collapsed)
▸ Reporting                       ← chapter (collapsed)
```

Visual states for steps:
- `●` filled circle — current step
- `✓` check — completed (label is before current position in the flat list)
- `○` empty circle — pending

Clicking a label calls `POST ${restBase}/scenario/run-to` with that label.

Outline is empty when no scenario is active — show a "No scenario running"
placeholder.

**Transport bar (middle):**

```
[⏮] [⏪] [⏸/▶] [⏩] [⏭]   ●────────○ 1.0x   33%
 │     │     │      │    │    └─ speed slider    └─ progress
 │     │     │      │    └─ run-to next chapter
 │     │     └──────└─── step / skip section
 │     └─ run-to prev section (disabled if at start)
 └─ restart (disabled — no rewind support)
```

Core controls:
- **Play/Pause toggle** — `POST /scenario/resume` or `POST /scenario/pause`
- **Step** — `POST /scenario/step` (advance one step, then pause)
- **Speed slider** — range input, 0.01x to 10x (matching backend minimum
  clamp of 0.01), sends `POST /scenario/speed` with debounce (250ms).
  Display current speed as label. Logarithmic scale for usable control
  across the wide range.

All transport buttons disabled when no scenario is active.

**Start/stop controls:**

When no scenario is active, show a **Start** button. The controller
fetches available scenarios via `GET /scenario/list` (a new endpoint
returning scenario names from the orchestrator's registered set) or
accepts a YAML text input for ad-hoc start. Clicking Start calls
`POST /scenario/start` with the selected scenario.

When a scenario is active, show a **Stop** button that calls
`POST /scenario/stop`. This enables the full lifecycle from the remote
page without requiring a separate terminal.

**Status bar (bottom):**

Single line showing the current position breadcrumb and connection status:

```
Customer Reports Issue → Customer sends message → Submit support request   ● Connected
```

Connection status reflects `EventConnection.status`:
- `● Connected` (green dot)
- `● Reconnecting` (amber dot, pulsing)
- `● Disconnected` (red dot)

### 3.6 REST command dispatch

All commands are sent via `fetch()`:

```typescript
private async sendCommand(path: string, body?: object): Promise<void> {
  const url = `${this.restBase}/scenario${path}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!resp.ok) {
    // Handle error — update status bar with error message
  }
}
```

The REST response returns `ScenarioState` — but we don't use it for state
updates (the push wire broadcast delivers the canonical state to all
controllers simultaneously). The response is only used for error detection.

### 3.7 Styling

Uses CSS custom properties from `pages-ui-tokens` for consistency:
- `--pages-neutral-*` for text and backgrounds
- `--pages-accent-*` for the current position highlight
- `--pages-success-*` for completed steps
- `--pages-font-*` for typography
- `--pages-space-*` for spacing
- `--pages-radius-*` for border radius
- `--pages-duration-*` for transition timing

Shadow DOM with `:host` sizing to fill its container. The host can control
dimensions via CSS.

### 3.8 Accessibility

- **Outline tree:** `role="tree"` / `role="treeitem"` ARIA markup. Arrow
  key navigation via `RovingTabindexMixin` from `pages-primitives`.
  Enter/Space to expand/collapse and activate "run to".
- **Transport controls:** `Space` toggles play/pause (media convention).
  Buttons have `aria-label` attributes describing their action.
- **Speed slider:** `aria-valuemin`, `aria-valuemax`, `aria-valuenow`,
  `aria-valuetext` (e.g. "1.5x speed").

## 4. `<scenario-narrative>` Web Component

### 4.1 Package and registration

Lives in `packages/pages-aria/src/controller/`.

```
packages/pages-aria/src/controller/
  scenario-narrative.ts
  scenario-narrative.test.ts
```

Registered as `<scenario-narrative>`.

### 4.2 Properties

```typescript
@property({ attribute: false })
connection?: EventConnection;

@property({ attribute: false })
eventTarget?: EventTarget;

@property()
baseUrl?: string;
```

Same connection/mode/eventTarget pattern as `<scenario-controller>`.

### 4.3 Rendering

Listens on `scenario:state` and renders the `content` field based on its type:

**`Inline` (markdown):**
Render markdown to HTML. Use a lightweight renderer — either import an
existing one (marked) or a minimal implementation for the subset needed
(headings, paragraphs, bold, italic, lists, code blocks). The rendered
HTML is set via `innerHTML` on a container with scoped styles.

**`Template` (path + section extraction):**
Fetch the template file from `GET ${restBase}/scenario/content?path=${path}`.
This requires a new endpoint in `ScenarioControlResource` that serves
template files from a configurable content root directory
(`casehub.scenario.content-root`, defaulting to `META-INF/resources/scenario/content/`).
Extract the named section (if specified — find the heading, take content
until the next heading of the same or higher level) and render as markdown.
Cache fetched templates by path — the same file may be referenced by
multiple steps.

**`Slide` (reveal.js reference):**
Render a reference or embed. Details TBD based on the reveal.js integration
— for now, show the slide reference as a link or placeholder.

**No content:**
Show nothing (empty component). The narrative panel is optional — if no
step has narrative content, it takes no space.

### 4.4 Styling

Clean reading typography. Light background, max-width for readability.
CSS custom properties from `pages-ui-tokens`.

## 5. Standalone Remote Page

### 5.1 Location

A static HTML file at:
```
backend/scenario-runtime/src/main/resources/META-INF/resources/scenario/remote.html
```

Served by Quarkus at `/scenario/remote` (or `/scenario/remote.html`).

### 5.2 Content

Minimal HTML that loads the `<scenario-controller>` component in remote
mode:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Scenario Remote</title>
  <style>
    body { margin: 0; font-family: system-ui, sans-serif; }
    scenario-controller {
      display: block; width: 100vw; height: 100vh;
    }
  </style>
</head>
<body>
  <scenario-controller id="ctrl"></scenario-controller>
  <script type="module">
    import '/scenario/controller.js';
    document.getElementById('ctrl').baseUrl = window.location.origin;
  </script>
</body>
</html>
```

The `baseUrl` is set programmatically to `window.location.origin` — the
remote page is served by the same backend that runs the orchestrator.
Setting via script avoids the empty-string problem (`""` is falsy and
doesn't produce valid WebSocket URLs).

### 5.3 Bundle

A separate webpack/esbuild entry point in pages-aria that bundles:
- `scenario-controller.ts` + its Lit dependencies
- Custom element registration

This produces a standalone JS file that can be loaded independently of
the full pages-runtime bundle. The file is placed in
`META-INF/resources/scenario/controller.js` during the build.

## 6. Data Flow

```
                    ┌─────────────────────────┐
                    │  ScenarioOrchestrator    │
                    │  (Java backend)          │
                    ├─────────────────────────┤
      REST ←────── │ /scenario/state          │
      REST ←────── │ /scenario/outline        │
      REST ←────── │ /scenario/pause|resume|… │
                    │                          │
                    │  EventBroadcaster        │
                    │    .broadcast(           │
                    │      "scenario:state",   │
                    │       state())           │
                    └──────────┬──────────────┘
                               │ push wire (WebSocket)
                               │ op: "event"
                               │ topic: "scenario:state"
                               │
             ┌─────────────────┼─────────────────┐
             │                 │                  │
   ┌─────────▼──────┐  ┌──────▼────────┐  ┌─────▼──────────┐
   │ <scenario-     │  │ <scenario-    │  │ <scenario-     │
   │  controller>   │  │  narrative>   │  │  controller>   │
   │ (embedded)     │  │ (embedded)    │  │ (remote/phone) │
   │                │  │               │  │                │
   │ shared         │  │ shared        │  │ own            │
   │ EventConnection│  │ EventConnection│  │ EventConnection│
   └────────────────┘  └───────────────┘  └────────────────┘
```

Multiple controllers and narrative components can listen simultaneously.
The push wire delivers the same state to all of them. Commands are sent
via REST — the response state is not used for rendering (the push wire
broadcast is the canonical source).

## 7. Testing Strategy

### 7.1 Backend (Java)

- **ScenarioOrchestrator broadcast test:** verify `EventBroadcaster.broadcast`
  is called after each state mutation (start, pause, resume, step, speed,
  onStepResult) with correct `ScenarioState` payload.
- **Outline endpoint test:** `GET /scenario/outline` returns correct tree
  structure for chapters/sections/steps scenarios. Returns 404 when no
  scenario is active.

### 7.2 Frontend (TypeScript)

- **`<scenario-controller>` unit tests:**
  - Renders empty state when no scenario is active
  - Updates state from mock `scenario:state` events
  - Highlights current position in outline tree
  - Sends correct REST commands on button clicks
  - Handles connection status changes
  - Embedded vs remote mode connection wiring
- **`<scenario-narrative>` unit tests:**
  - Renders inline markdown content
  - Renders nothing when content is null
  - Handles content type switching

### 7.3 Integration / E2E

- **Playwright MCP:** Start a scenario via REST, verify the controller
  shows the outline, advance steps, verify position updates in real-time
  via the push wire connection.

## References

- [distributed-executor-protocol-design.md](2026-08-20-distributed-executor-protocol-design.md) — §5 Controller API, §7 Controller UI
- ScenarioOrchestrator.java — current orchestrator (no broadcast)
- ScenarioControlResource.java — existing REST endpoints
- ScenarioState.java — state record with NarrativeContent
- EventConnection.ts — push wire client with `listen()` API
- scenario-handler.ts — browser executor (dispatch-sequence/control handling)
- pages-ui-components/src/button/pages-button.ts — existing Lit component pattern
- [GE-20260818-c61c29] — topicSource adapter for push wire → DataSource bridge
- [GE-20260816-e89cda] — composable Lit reactive controllers (evaluated, deferred for single-consumer case)
- [GE-20260812-5cd146] — EventConnection drops non-event messages (addressed — dispatch-sequence/control now handled)
- [GE-20260818-78bf96] — subscribe vs listen protocol distinction
