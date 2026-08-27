# Design: Controller YAML Fly-Out Viewer (#349)

## Context

The scenario controller shows an outline tree of the scenario structure. For demo
transparency, operators want to see the actual YAML source alongside execution with
the current position highlighted. The YAML viewer should be independently positionable
(not attached to the controller card) and detachable to a separate window.

## Architecture

### Component: `<pages-scenario-yaml-viewer>`

A Lit component in `packages/pages-aria` that renders syntax-highlighted YAML source
with live position tracking. Connects to the push wire independently via
`ScenarioConnectionController` — the same reactive controller pattern used by
`<pages-scenario-controller>`.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `connection` | `EventConnection` | Injected push wire (embedded mode) |
| `eventTarget` | `EventTarget` | Injected event target (embedded mode) |
| `baseUrl` | `string` | REST/WS base URL (remote/detached mode) |
| `scenario` | `string` | Scenario name — used to fetch YAML source |

One of (`connection` + `eventTarget`) or `baseUrl` must be provided. Same two-mode
pattern as `PagesScenarioController` (D17).

### YAML source and position tracking

1. On scenario start (or when `scenario` property is set), fetch YAML from
   `GET /scenarios/${scenario}.yaml`
2. Parse with `yaml.parseDocument(source)` — AST nodes carry `.range`
   (array of `[startOffset, valueEndOffset, nodeEndOffset]`)
3. Walk the document's AST:
   - Find `sections[].steps[]` entries
   - For each step, extract `label` and `name` values
   - Record line ranges: convert byte offsets to line numbers via a precomputed
     offset→line lookup array
4. Build `Map<string, LineRange>` mapping step labels/names to
   `{startLine, endLine}` in the source
5. On `scenario:state` push event, look up `state.step` in the map, scroll to
   that range, apply the `yaml-active` highlight class

The position map is rebuilt only when the scenario source changes (new scenario start).

### Syntax highlighting

Render the YAML source as pre-formatted text with syntax-aware CSS classes. The
highlighting is line-based — each line is a `<div>` containing `<span>` elements
with token classes.

Tokenize using a lightweight regex-based approach on the raw source lines (the yaml
package's CST API is more complex than needed for highlighting — CST is used only
for position tracking where structural accuracy matters):

| Token | Pattern | CSS class |
|-------|---------|-----------|
| Comment | `#.*$` | `yaml-comment` |
| Key | `^\s*[\w-]+(?=:)` | `yaml-key` |
| String value (quoted) | `"[^"]*"` or `'[^']*'` | `yaml-string` |
| Boolean/number | `true\|false\|null\|\d+` | `yaml-literal` |
| Punctuation | `:` `-` `\|` `>` | `yaml-punct` |
| Active step block | Lines within active range | `yaml-active` (background) |

CSS uses the same dark-glass palette as the controller card.

### Floating overlay rendering

Same visual treatment as the compact controller card:
- Background: `rgba(15, 23, 42, 0.95)` with `backdrop-filter: blur(12px)`
- Border radius, shadow, and color scheme matching the controller
- Independently draggable (reuses the pointer-capture drag pattern from
  `PagesScenarioController`)
- Default position: offset to the left of the controller
- Size: 360px wide, max-height 60vh, scrollable content area
- Header with scenario name, pop-out button (⧉), and close button (✕)
- Content area: scrollable `<pre>` with highlighted YAML lines

### Controller integration

The `PagesScenarioController` gains:
- A `</>` toggle button in the compact card header
- `@state() private _yamlOpen = false` — tracks viewer visibility
- When toggled, creates (or shows/hides) a `<pages-scenario-yaml-viewer>` element
  as a sibling in the DOM. The controller passes its connection and eventTarget
  to share the push wire.

The viewer element is created once and cached — toggle shows/hides it. The viewer
receives `scenario` from the controller and shares the same `ScenarioConnectionController`
state events.

### Detach to window

A pop-out button (⧉) in the viewer header opens a new browser window:

1. `window.open('/scenario/yaml-viewer', ...)` with appropriate dimensions
2. The pop-out page is a minimal HTML file served from
   `backend/scenario-runtime/src/main/resources/META-INF/resources/scenario/yaml-viewer.html`
3. It loads `controller.js` (the existing esbuild bundle) and creates
   `<pages-scenario-yaml-viewer baseurl="..." scenario="...">` — the viewer
   connects independently via its own push wire
4. The in-page viewer hides when the pop-out opens
5. The controller polls `popoutWindow.closed` on an interval (every 500ms).
   When the pop-out closes, the in-page viewer reappears and polling stops.

The pop-out URL includes the scenario name and base URL as query params so the
page can configure the component without external state.

### Standalone exports

`pages-aria/src/controller/standalone.ts` updated to export the new component:
```typescript
export { PagesScenarioYamlViewer } from './scenario-yaml-viewer.js';
```

The esbuild bundle (`dist/controller.js`) includes the viewer — any page that
loads `controller.js` can use `<pages-scenario-yaml-viewer>`.

## Files

| File | What |
|------|------|
| `packages/pages-aria/src/controller/scenario-yaml-viewer.ts` | Lit component — rendering, highlighting, scroll tracking, drag |
| `packages/pages-aria/src/controller/yaml-highlighter.ts` | YAML tokenizer (regex) + AST position mapper (yaml parseDocument) |
| `packages/pages-aria/src/controller/yaml-highlighter.test.ts` | Tests for tokenizer and position mapper |
| `packages/pages-aria/src/controller/scenario-yaml-viewer.test.ts` | Component tests |
| `packages/pages-aria/src/controller/scenario-controller.ts` | Updated — add toggle button, viewer lifecycle |
| `packages/pages-aria/src/controller/standalone.ts` | Updated — export YamlViewer |
| `backend/scenario-runtime/.../scenario/yaml-viewer.html` | Minimal pop-out page for detach mode |

## Testing

- **yaml-highlighter.test.ts**: tokenizer produces correct spans for keys, values,
  comments, quoted strings, booleans. Position mapper returns correct line ranges
  for step labels in a sample scenario YAML.
- **scenario-yaml-viewer.test.ts**: component renders YAML content, highlights
  active step on state change, scrolls to active block, shows/hides on toggle.
- **scenario-controller.test.ts**: existing tests updated — verify toggle button
  renders, viewer element created on toggle.

## References

- casehubio/casehub-pages#349 — issue
- `packages/pages-aria/src/controller/scenario-controller.ts` — existing controller
- `packages/pages-aria/src/controller/scenario-connection-controller.ts` — push wire pattern
- `packages/pages-aria/package.json` — yaml dependency
- D23, D24, D25 in decisions.md — detach, yaml CST, floating layout decisions
