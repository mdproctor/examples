# CaseHub Visual Diagram Editor — Design Spec

**Date:** 2026-08-01
**Status:** Draft
**Scope:** Visual viewer and editor for CaseHub case definitions, with SWF drill-down and runtime overlay

---

## 1. Problem

CaseHub case definitions are authored in YAML. There is no visual tool to view, navigate, or edit the structure of a case — its bindings, workers, milestones, goals, sub-cases, human tasks, or embedded SWF workflows. Users need:

- A **read-only viewer** that renders a case definition as a directed graph with auto-layout — Workers as nodes, Bindings as edges, Milestones/Goals as markers
- A **property editor** that lets users select a node or edge and edit its properties in a side panel
- A **structural editor** that lets users add, remove, and replace elements at specific points (auto-layout recalculates)
- A **runtime overlay** that projects live execution state (TaskStatus across all 9 states, MilestoneLifecycleStatus across all 5 states, heatmaps) onto the same graph
- A **multi-model view** that can drill into SWF workflows embedded inside Workers, rendering them in the same canvas
- **Pluggable work stencils** discovered at runtime from marketplace YAML definitions

Free-form drag-and-drop is explicitly deferred. Auto-layout and structural editing come first.

## 2. Decisions

### 2.1 YAML is the source of truth

The editor reads and writes YAML. There is no intermediate JSON graph model. The flow is:

```
YAML → parse (yaml npm, CST-preserving) → domain objects → domain adapter → React Flow nodes/edges → ELK layout → render
Edit → mutate domain objects → serialize (yaml npm, CST-preserving) → write YAML via persistence backend
```

The `yaml` npm package (v2+) uses a Concrete Syntax Tree (CST) that preserves comments, formatting, quoting style, and key ordering. Parse with `parseDocument()` to retain the CST; mutations update nodes in-place; `toString()` emits YAML with original formatting preserved for untouched sections. This ensures round-trip fidelity — editing one property does not rewrite the entire file.

**Cross-parser fidelity:** The Java engine uses Jackson + SnakeYAML. Expression strings like `${ .document.contentType }` may be quoted differently. Phase 0 must include a cross-parser compatibility test: parse→serialize with the `yaml` npm package, then parse with Jackson, and verify semantic equivalence.

**What the editor visualizes:** The domain adapter (`toGraph()`) produces a logical graph from the YAML definition structure. This is the *definition-time* view — what the author wrote, not what the engine produces at runtime.

The graph shows:
- **Bindings** as the primary behavioral nodes (each binding declares: trigger → target)
- **Workers** as containers that own capabilities
- **Milestones** and **Goals** as landmark nodes
- **SubCases** and **HumanTasks** as binding-target detail

Edges represent relationships derivable from the YAML: binding-to-worker (via capability name matching), goal-to-completion criteria, sub-case references. The domain adapter resolves string-based references (e.g., `binding.capability: "review"` → worker whose `capabilities` array includes `"review"`) into graph edges.

The engine's planning decomposition — `PlanItemDefinition.Compound` / `PlanItemDefinition.Primitive` hierarchy, `CompletionSemantics`, per-compound strategy — is a *runtime* concept. It is NOT what the definition-view editor visualizes. A future planning-view adapter could project the engine's `CasePlanModel` state as a separate graph, but this is not in scope for Phases 1–6. The runtime overlay (Phase 7) projects `TaskStatus` badges onto definition-view nodes, not a separate planning graph.

### 2.2 React Flow + Lit bridge (now), @xyflow/lit (later)

The CaseHub frontend is Lit 3.x Web Components. React Flow v12 (`@xyflow/react`, React 18) is the most capable open-source graph editor library (MIT). The bridge pattern is straightforward:

- Skip Shadow DOM on the wrapper component (`createRenderRoot() { return this; }`)
- Mount React Flow via `ReactDOM.createRoot` in `connectedCallback`
- Pass Lit properties as React props; React callbacks emit Lit custom events
- ~50 lines of bridge code, ~45KB bundle overhead for React + ReactDOM

**React version:** React Flow v12 (`@xyflow/react`) requires React 18. React is isolated to `graph-renderer` — it bundles its own React, separate from pages' React 17 (used only inside iframe component documents). There is no version constraint between them. v12 has better TypeScript types (generic node/edge), subscription-based reactivity, and active development. *(Corrected from v11 in Phase 0 spike — see casehub-pages specs/issue-259-graph-phase0/)*

**Shadow DOM topology:** Two components skip Shadow DOM; three keep it:

| Component | Shadow DOM | Rationale |
|-----------|-----------|-----------|
| `casehub-diagram` | **Skipped** | Layout shell with no CSS of its own to protect. Skipping ensures React Flow portals and document-level event handlers from the canvas face no shadow boundary above them. |
| `casehub-diagram-canvas` | **Skipped** | React Flow relies on document-level event handlers, portal-based controls (minimap, controls panel), and CSS-in-JS injection incompatible with Shadow DOM. |
| `casehub-diagram-palette` | Enabled | Self-contained Lit component; Shadow DOM provides CSS encapsulation. |
| `casehub-diagram-properties` | Enabled | Self-contained Lit component; Shadow DOM provides CSS encapsulation. |
| `casehub-diagram-toolbar` | Enabled | Self-contained Lit component; Shadow DOM provides CSS encapsulation. |

The palette, properties, and toolbar are custom elements rendered as light DOM children of the shell. Each creates its own shadow root internally for style encapsulation. The canvas renders React Flow content directly in light DOM — no shadow boundary between the canvas content and the document. This means React Flow CSS injected into `document.head` applies normally, portals render at `document.body` without crossing shadow boundaries, and event handlers work without retargeting.

The component emits `pages-event` with `composed: true` for consistency, though no shadow boundary needs crossing for the shell or canvas.

**CSS isolation strategy** (required because Shadow DOM is disabled on the canvas). Three mechanisms, each with a distinct role:

1. **`all: initial` on the container** (`.diagram-root { all: initial; display: block; position: relative; width: 100%; height: 100%; }`) — resets all inherited host styles including custom properties. This is the only mechanism that blocks inherited host CSS in light DOM. The container requires a parent with explicit height (React Flow needs known dimensions).

2. **Scoped `all: revert` on children** (`.diagram-root * { all: revert; }`) — specificity 0,1,0 beats the host's `* { box-sizing }` (0,0,0) and element resets like `button { appearance: none }` (0,0,1). Reverts host author styles on container children to browser defaults. React Flow CSS (loaded after, with class selectors) overrides via source order.

3. **`applyTheme()` on the container** — calls `applyTheme(currentTheme, container)` from `@casehubio/pages-ui-tokens`, which injects a `<style>` inside the container and sets the theme class. Because this `<style>` appears later in document order than the head stylesheet's `all: initial`, the theme token declarations win. No manual token list — `applyTheme()` already knows every token.

**`@layer` is NOT used for host isolation.** In the CSS cascade, unlayered CSS has higher priority than layered CSS for normal declarations. Pages' global resets are unlayered. Putting our CSS in `@layer` would make it *weaker* than the host. Source order controls internal CSS cascade (React Flow base → plugin styles → decoration overrides). *(Corrected from original @layer approach in Phase 0 spike — see casehub-pages specs/issue-259-graph-phase0/)*

Phase 0 validated this isolation against Pages shell globals — hard gate passed.

**CSS contract for stencil templates** (rendered inside the light-DOM canvas):
- Stencil templates MUST use **inline styles** or **CSS custom properties** (`--pages-*` tokens available via `applyTheme()`) for all styling. No `<style>` blocks.
- graph-renderer wraps each stencil template into a React Flow custom node container. Plugin-contributed styles are injected via `NodeTypeDescriptor.defaultStyle` at registration time, positioned in the cascade after React Flow base CSS.
- Different stencil packages (graph-stencil-case, graph-stencil-swf, work registry stencils) MUST NOT have selector collisions.

React is isolated to a single package (`graph-renderer`). When @xyflow/lit is built (or if the xyflow project ships one), the renderer swaps with no impact on the graph model, stencils, or domain adapters.

**Rationale against alternatives:**
- **@xyflow/system (headless core)**: Not viable standalone — it's a utility layer (25% of total logic), not a rendering framework. Building a Lit renderer on it = replicating Svelte Flow (~7,700 LOC).
- **Cytoscape.js**: Canvas-based rendering with no public custom node drawing API. Node rendering limited to CSS shapes and SVG-as-bitmap backgrounds. No stencil, palette, or editor UI.
- **GoJS / JointJS+**: Commercial licenses ($3,400–4,000/dev). Ruled out.
- **JointJS open-source**: MPL 2.0. Ruled out (must be MIT/BSD/Apache).
- **Lienzo port**: 6–10 person-weeks to port GWT → TypeScript. Strategic option for the future but premature now.

**SWF team alignment:** Same rendering framework as the SWF editor. Shared knowledge, same mental model for graph editing, potential for shared custom node utilities.

### 2.8 SWF editor reuse opportunities

The SWF editor (`@openworkflowspec/diagram-editor`) uses React Flow + ELK — the same rendering stack we've chosen. Beyond the shared framework, specific reuse opportunities:

| Asset | Package | Reuse how |
|-------|---------|-----------|
| **SWF YAML parser + FlatGraph builder** | `@openworkflowspec/sdk` | Direct npm dependency for `graph-stencil-swf`. The SDK parses SWF YAML, validates it, classifies node types, and produces a `FlatGraph` (nodes, edges, parent containment). No need to write our own SWF parser. |
| **ELK layout wiring** | SWF editor `react-flow/` | Pattern reference — how they map containment to ELK `INCLUDE_CHILDREN` and translate ELK output to React Flow positions. |
| **React Flow custom node patterns** | SWF editor `react-flow/nodes/` | Pattern reference — their custom node component structure, prop patterns, and state handling. |
| **YAML ↔ graph round-trip** | SWF editor `core/workflowSdk.ts` | Pattern reference — their approach to parsing YAML into domain objects and constructing the graph. |

The `@openworkflowspec/sdk` dependency means the SWF team owns the parser. When the SWF spec evolves, the SDK updates, and our drill-down view picks up changes without maintaining SWF parsing logic. This reduces Phase 5 effort — `graph-stencil-swf` becomes an adapter + stencil templates, not a parser + adapter + stencils.

**All parsing is client-side.** The `@openworkflowspec/sdk`, `yaml` npm package, and all domain adapters run entirely in the browser. The only server interaction is the persistence backend's read/write of YAML strings. Parsing, graph construction, layout, rendering, validation, and editing are all browser-only.

### 2.3 Two tiers of stencils

**Structural stencils (compile-time)** — the graph grammar. Fixed set per domain:
- Case (definition view): Binding, Worker, Milestone, Goal, SubCase
- SWF: Call, Switch, Raise, Catch, Entry, Exit (drill-down view)

| Stencil | Shape | Key visuals |
|---------|-------|-------------|
| **Binding** | Rounded rectangle | Trigger type icon (contextChange/cloudEvent/schedule/scopeActivated), target type badge (capability/subCase/humanTask), when-condition preview |
| **Worker** | Container rectangle | Capability list, executor type indicator (SWF/agent/sequence/external), description |
| **Milestone** | Diamond | Condition preview, SLA indicator (integrates with existing `sla-indicator` component), entryCriteria badge |
| **Goal** | Hexagon terminal | Kind badge (success/failure/custom), condition preview |
| **SubCase** | Double-bordered rectangle | Namespace/name/version, I/O mapping indicator, M-of-N group badge (groupId/requiredCount/totalInGroup) |

HumanTask is a binding target, not a standalone YAML element — it is rendered as the target detail within a Binding node (title, candidateGroups, outcomes, SLA).

Each structural stencil defines:
- Connection rules (e.g., "Goal has zero outgoing edges", "Binding has 0..1 outgoing capability edges — 0 when the target Worker is external/unresolved")
- Port cardinality (e.g., "Worker has 0..* inbound capability edges")
- Property schema (JSON Schema scoped to the stencil's YAML element — see §3.3)
- Rendering template (function of node state → `StencilTemplate` from lit-html; wrapped into React Flow custom nodes by graph-renderer)

### 2.3a Edge model

Edges represent relationships derived from the YAML definition:

| Edge type | Source → Target | Derivation |
|-----------|----------------|------------|
| **Capability dispatch** | Binding → Worker | Binding.capability string matches Worker.capabilities[] entry |
| **SubCase spawn** | Binding → SubCase (sub-node) | Binding.subCase object present |
| **HumanTask creation** | Binding → HumanTask (sub-node) | Binding.humanTask object present |
| **Completion criteria** | Goal → Case completion | GoalExpression references goal by name |

Edges are derived by the domain adapter during `toGraph()` — they are not stored separately in YAML. The adapter resolves string references (capability names) into graph edges. Unresolvable references (binding references a capability no in-definition worker owns) are shown as **informational annotations** (dashed edge with "external?" label), not warnings — CaseHub workers can be registered externally at runtime, so an unresolvable in-definition reference is normal, not an error. When a runtime connection is available (Phase 7), the editor can validate against the full worker registry to distinguish external capabilities from typos.

**Grammar vs YAML validation for capability edges:** The graph grammar (StencilGrammar) validates **graph topology** — relationships between visible nodes. The Binding JSON Schema (`oneOf: [capability, subCase, humanTask]`) validates **YAML structure** — that a binding always has a target. These are separate concerns: a Binding always has a target in the YAML, but the graph edge to a Worker may not exist if the target Worker is external. The Binding's grammar therefore allows 0..1 outbound capability edges (0 = external/unresolved capability, 1 = resolved to an in-definition Worker). The `applyEdit()` write path enforces YAML validity (the binding must specify a capability, subCase, or humanTask), not graph edge existence. Constraint validation passes for a binding with `capability: "external-review"` even when no Worker in the definition owns that capability.

ELK layout uses hierarchical mode. Workers are containers; their capabilities are internal labels (not separate nodes). Bindings connect to workers via capability edges.

### 2.3b Property categories

YAML properties not represented as graph nodes are handled as property-panel fields:

| Category | Properties | Where shown |
|----------|-----------|-------------|
| **Case-level** | `planningStrategy`, `decompositionStrategy`, `completion`, `authorization`, `cbr`, `use` (secrets/configMaps), `episodic`, `semanticData`, `layers`, `signals`, `types`, `labels` | Case root property panel (top-level selection or dedicated toolbar section) |
| **Binding-level** | `name`, `on` (trigger), `when`, `conflictResolverStrategy`, `outcomePolicy`, `inputProjectionOverride`, `contextWrite`, `producedKeys`, `lifecycleScope`, `participation`, `executionMode` | Binding node property panel |
| **Worker-level** | `name`, `description`, `capabilities`, `executionPolicy`, `sequence`, agent/SWF config | Worker node property panel |
| **Milestone-level** | `name`, `description`, `condition`, `entryCriteria`, `slaDuration`, `slaStartFrom` | Milestone node property panel |
| **Goal-level** | `name`, `description`, `kind`, `condition` | Goal node property panel |

Phase 3 (property editing) implements schema-driven form generation from these categories.

**Work stencils (runtime-discoverable)** — the leaf vocabulary. What a Worker actually executes:
- Discovered from marketplace YAML at configurable URLs
- Grouped by category (connectors/messaging, ai/agents, human/tasks, etc.)
- Each defines: icon, property schema, sync/async, input/output contract
- Rendered as Worker sub-visuals with work-type-specific detail

### 2.4 Persistence is pluggable

```typescript
type ReadResult =
  | { status: 'ok'; yaml: string; version: string }
  | { status: 'not_found'; uri: string }
  | { status: 'parse_error'; message: string; raw: string }
  | { status: 'schema_error'; errors: ValidationError[]; yaml: string; version: string };

interface PersistenceBackend {
  read(uri: string): Promise<ReadResult>;
  write(uri: string, yaml: string, expectedVersion: string): Promise<WriteResult>;
}

type WriteResult =
  | { status: 'ok'; version: string }
  | { status: 'conflict'; currentVersion: string };
```

`read()` returns a discriminated union so the editor can route to create-new, show-error, or show-with-warnings flows. `write()` takes an `expectedVersion` (opaque string — Git SHA, ETag, last-modified timestamp) for optimistic concurrency: if the backing store has changed since `read()`, `write()` returns `conflict` and the editor shows a merge/overwrite dialog. The in-memory backend uses a monotonic counter; the Git backend uses commit SHA.

**Conflict resolution:** When `write()` returns `conflict`, the editor presents a **yours/theirs/cancel** dialog:
- **Overwrite** — force-write the local version, discarding the remote changes (re-issues `write()` with the current version)
- **Reload** — discard local changes, reload the remote version via `read()`, re-run `toGraph()`
- **Cancel** — dismiss the dialog, keep editing without saving

This is deliberately NOT a three-way merge. CST-preserving YAML three-way merge (preserving comments, key ordering, quoting style across both versions) is extremely complex and out of scope. The dialog makes the user explicitly choose which version to keep. A future phase could add visual side-by-side diff of the two YAML documents to help the user decide.

Backends: Git (read/write GitHub URLs), Electron (local filesystem), REST API, in-memory (playground). The editor doesn't know or care where the YAML lives.

### 2.5 All TypeScript is type-safe

- Types generated from the CaseDefinition JSON Schema (`engine/schema/src/main/resources/schema/CaseDefinition.yaml`)
- Type-safe overlays for all library integrations (React Flow, ELK, js-yaml)
- No `any` types. Strict mode throughout.

**Prerequisite:** Verify the JSON Schema is current against the Java domain model. The schema may be stale after the stages removal and recent model changes.

### 2.6 Undo/redo — YAML-snapshot model

The undo model is **YAML-snapshot-based**: each edit operation (property change, structural add/remove/replace) pushes the previous YAML string onto an undo stack. Undo restores the previous YAML, re-parses, and re-runs `toGraph()`. Redo pushes forward.

- **Undo unit:** One logical edit operation — a property change, an add-node, a remove-node, a replace-node. Batch edits (e.g., "replace node and reconnect edges") are a single undo unit.
- **Stack location:** `casehub-diagram` component owns the undo stack. It is not in graph-core (domain-agnostic) or the domain adapter (stateless transformer).
- **Stack depth:** Bounded to 50 entries to limit memory. Older entries are discarded.
- **Concurrency interaction:** The undo stack is cleared when the document is reloaded from persistence (e.g., after conflict resolution — the user chose "Reload" in the yours/theirs/cancel dialog). Undo never crosses a persistence boundary.
- **Not Git-based:** Undo is in-session, not "revert to last commit". Every individual edit is undoable without requiring a save/commit cycle.
- **Keyboard shortcuts:** Ctrl+Z (undo), Ctrl+Shift+Z (redo) — standard editor bindings, handled by `casehub-diagram`.

This is simpler than a command pattern or graph-model-level undo and works because YAML is the source of truth — every undo state is a complete, self-consistent YAML document.

### 2.7 Runtime overlay — lightweight, not a separate view

Same graph, same layout. Runtime data is projected as decoration:
- Node state badges (all 9 `TaskStatus` values — see table below)
- Heatmaps for usage frequency (hot/cold path colouring)
- Active planning highlighting (which bindings the planning strategy is evaluating)
- Worker execution indicators (SWF workflow progress inside a Worker)
- Milestone progression (full `MilestoneLifecycleStatus`: PENDING → ACTIVE → COMPLETED | FAILED | CANCELLED)
- CaseContext data preview on hover

**TaskStatus badge visuals** (from `io.casehub.api.model.TaskStatus`):

| State | Badge | Visual treatment |
|-------|-------|-----------------|
| PENDING | Gray outline | Empty circle, no fill — work defined, not yet started |
| RUNNING | Green pulse | Animated green ring — actively executing |
| DELEGATED | Blue arrow | Right-pointing arrow badge — control passed to external actor |
| SUSPENDED | Yellow pause | Pause icon — execution paused, resumes without re-dispatch |
| COMPLETED | Green checkmark | Filled green check — finished successfully |
| FAULTED | Red exclamation | Red circle with exclamation — system failure, deadline breach, or gate rejection |
| REJECTED | Orange X | Orange X mark — actor deliberately refused the work |
| OBSOLETE | Strikethrough | Dimmed node with strikethrough — context changed, work became irrelevant |
| CANCELLED | Gray slash | Gray diagonal slash — deliberate stop by human or system |

**Generic decoration model** (graph-core — domain-agnostic):
```typescript
interface NodeDecoration {
  badge?: { icon: string; color: string; pulse?: boolean; count?: number };
  border?: { style: string; color: string };
  overlay?: { type: 'heatmap' | 'highlight'; intensity: number };
  tooltip?: string;
}
```

Domain stencil packages (graph-stencil-case) provide a `RuntimeAdapter` that maps domain state to decorations: `TaskStatus.RUNNING` → `{ badge: { icon: 'play', color: 'green', pulse: true } }`. graph-core and graph-renderer never import domain state enums.

**PlanItem-to-Binding aggregation:** A single Binding can fire multiple times (e.g., `validate-on-upload` fires on every document upload), producing multiple PlanItems with the same `bindingName`. The `RuntimeAdapter` aggregates these per-binding using an **active-worst-first** strategy:

1. If any PlanItem is in an active state, show the worst active status (priority: SUSPENDED > DELEGATED > RUNNING > PENDING)
2. If all PlanItems are terminal, show the most recent terminal status (by `createdAt`)
3. When multiple PlanItems exist, `badge.count` shows the total and `tooltip` shows the full breakdown (e.g., "3 completed, 1 faulted, 1 running")

The stencil rendering function takes optional decoration:
```typescript
render(node: GraphNode, decoration?: NodeDecoration): StencilTemplate
```

No decoration → design mode. Decoration present → overlay visuals.

## 3. Architecture

### 3.1 Package structure

```
Pages (framework-level, domain-agnostic)
│
├── @casehubio/graph-core
│   ├── Graph model (nodes, edges, containment tree)
│   ├── StencilGrammar registry (connection rules, port cardinality — SPI)
│   ├── Constraint validator (enforces registered grammar rules)
│   ├── PropertySchema contract (JSON Schema for stencil property panels)
│   ├── NodeDecoration model (generic badge/border/overlay — no domain enums)
│   ├── Persistence SPI (PersistenceBackend interface)
│   └── Edit operations (add, remove, replace node — validated against grammar)
│
├── @casehubio/graph-renderer
│   ├── React Flow v11 bridge (Lit wrapper, React lifecycle management)
│   ├── ELK layout integration (hierarchical auto-layout)
│   ├── StencilDescriptor registry (rendering templates keyed by stencil type)
│   ├── Stencil template → React Flow custom node wrapping
│   ├── Decoration renderer (badge, heatmap, pulse from NodeDecoration)
│   └── Mode switching (design / runtime)
│
└── @casehubio/graph-work-registry
    ├── Marketplace YAML loader (fetch + parse work stencil descriptors)
    ├── Work stencil schema (name, category, icon, properties, I/O contract)
    ├── Category index (grouped palette data)
    └── PropertySchema definitions per work stencil (consumed by casehub-diagram-properties)

blocks-ui (domain-specific, consumes Pages)
│
├── @casehubio/graph-stencil-case
│   ├── Case domain adapter
│   │   ├── toGraph(caseYaml): GraphModel — parse YAML, produce nodes/edges
│   │   │   Resolves capability string references into binding→worker edges
│   │   └── applyEdit(model, edit): CaseYaml — apply structural edit, serialize back
│   ├── TypeScript types generated from CaseDefinition.yaml JSON Schema
│   ├── Structural stencils: Binding, Worker, Milestone, Goal, SubCase
│   ├── Stencil templates per node type (Lit TemplateResult, with state variants)
│   └── Case runtime overlay adapter (TaskStatus badges, milestone progression)
│
├── @casehubio/graph-stencil-swf
│   ├── Depends on @openworkflowspec/sdk (SWF YAML parsing + FlatGraph builder)
│   ├── SWF domain adapter (sdk.buildFlatGraph() → graph model adapter)
│   ├── Workflow step stencils (call, switch, raise, catch, entry, exit)
│   ├── Stencil templates per step type
│   ├── Drill-down integration (expand SWF Worker → sub-graph of workflow steps)
│   └── SWF runtime overlay adapter (step execution state, lineage)
│
└── casehub-diagram (Lit components for blocks-ui)
    ├── <casehub-diagram> — layout shell (composition root)
    │   Assembles sub-components, persistence backend config,
    │   case-explorer integration (entity-detail renderer — see §3.5)
    ├── <casehub-diagram-canvas> — graph rendering surface
    │   Mounts graph-renderer, stencil sets; Shadow DOM skip is HERE ONLY
    ├── <casehub-diagram-palette> — stencil/work type palette
    │   Structural stencils + work registry results; Shadow DOM enabled
    ├── <casehub-diagram-properties> — property panel
    │   Schema-driven form from selected node's PropertySchema; Shadow DOM enabled
    └── <casehub-diagram-toolbar> — mode switch, zoom, layout reset
        Shadow DOM enabled
```

### 3.5 Relationship to case-explorer

blocks-ui's `case-explorer` (#87) is a registration-based entity browser for case *instances* — runtime entities with lifecycle status, commands, and relationships. The diagram editor visualizes case *definitions* — the YAML structure that defines what a case type can do.

They are complementary views of different data:

| Concern | case-explorer | casehub-diagram |
|---------|--------------|-----------------|
| Subject | Case instances (runtime) | Case definitions (design-time) |
| Data source | REST API (EntityInstance) | YAML (CaseDefinition) |
| Navigation | Entity list → detail → tree | Graph canvas with pan/zoom |
| Editing | Commands (dynamic, per-instance) | Property editing, structural editing |

**Integration point:** `casehub-diagram` can be registered as an `entity-detail` renderer for the `caseDefinition` entity type in case-explorer. When a user selects a case definition in case-explorer's entity-list, the diagram renders as the detail view. This uses case-explorer's existing three-tier renderer resolution — no changes to case-explorer are needed.
```

### 3.2 Data flow

```
                    ┌─────────────────────┐
                    │  Persistence Backend │
                    │  (Git/File/REST)     │
                    └──────────┬──────────┘
                               │ YAML string
                    ┌──────────▼──────────┐
                    │  Domain Adapter      │
                    │  (Case or SWF)       │
                    │  parse ↔ serialize   │
                    └──────────┬──────────┘
                               │ Domain objects
                    ┌──────────▼──────────┐
                    │  graph-core          │
                    │  GraphModel          │
                    │  (nodes, edges,      │
                    │   containment)       │
                    └──────────┬──────────┘
                               │ validated graph
                    ┌──────────▼──────────┐
                    │  graph-renderer      │
                    │  ELK layout          │
                    │  React Flow render   │
                    │  + runtime overlay   │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  casehub-diagram     │
                    │  (Lit component)     │
                    │  palette + panel +   │
                    │  toolbar             │
                    └─────────────────────┘
```

### 3.3 Stencil definition contract

**`StencilTemplate`** is a `TemplateResult` or `SVGTemplateResult` from lit-html. Stencils are authored in Lit (since they live in blocks-ui). The rendering pipeline in graph-renderer wraps each `StencilTemplate` into a React Flow custom node component — the stencil author never touches React.

**`PropertySchema`** is a standard JSON Schema object scoped to the stencil's YAML element. It reuses the CaseDefinition.yaml JSON Schema `$defs` directly — e.g., the Binding stencil's PropertySchema is the `#/$defs/Binding` schema subset. Complex types (HumanTask with `oneOf` constraints, Trigger with exclusive `oneOf`) are handled by JSON Schema's existing composition keywords. Phase 3's schema-driven form generation consumes PropertySchema via the same pipeline as blocks-ui's existing `schema-form` component in pages-primitives.

```typescript
import { TemplateResult, SVGTemplateResult } from 'lit-html';
import { JSONSchema7 } from 'json-schema';

type StencilTemplate = TemplateResult | SVGTemplateResult;
type PropertySchema = JSONSchema7;

// Registered with graph-core — structural rules only, no rendering
interface StencilGrammar {
  type: string;                           // e.g. "binding", "worker", "milestone"
  connections: {
    inbound: { min: number; max: number; allowedFrom: string[] };
    outbound: { min: number; max: number; allowedTo: string[] };
  };
}

// Registered with graph-renderer — full descriptor including rendering.
// graph-renderer auto-registers the embedded grammar with graph-core
// on StencilDescriptor registration — single source of truth, no
// separate manual grammar registration needed.
interface StencilDescriptor {
  type: string;                           // matches StencilGrammar.type
  label: string;
  icon: string;
  grammar: StencilGrammar;                // auto-registered with graph-core by graph-renderer
  properties: PropertySchema;
  render: (node: GraphNode, decoration?: NodeDecoration) => StencilTemplate;
}
```

```typescript
interface WorkStencil {
  name: string;                           // e.g. "send-email"
  displayName: string;
  category: string;                       // e.g. "connectors/messaging"
  icon: string;
  async: boolean;
  properties: PropertySchema;
  input: JSONSchema7;
  output: JSONSchema7;
}
```

Work stencils are **declarative metadata only** — they contain no executable render functions. They are discovered from marketplace YAML definitions at configurable URLs, and loading arbitrary executable code from external URLs is a security concern that the declarative model avoids entirely.

**Default rendering:** graph-renderer provides a built-in `defaultWorkStencilRenderer` that renders any WorkStencil using its declarative metadata: icon, displayName, category badge, async/sync indicator, and a summary of the I/O contract. This default renderer is sufficient for all marketplace-discovered work types. Custom visual treatment for specific work types (if ever needed) would be implemented as built-in stencil templates in the codebase, not loaded from external URLs.

### 3.4 Execution ownership boundary

The viewer must visually distinguish:

- **Case-controlled zone** — bindings, milestones, goals, completion evaluation. The Case engine owns lifecycle.
- **Worker-controlled zone** — once a binding dispatches to a Worker, the Worker owns execution. If the Worker runs a SWF workflow, the workflow has its own state machine.
- **The boundary** — a PlanItem transitions PENDING → DELEGATED when handed to a Worker (`PlanItem.markDelegated()` uses `compareAndSet(PENDING, DELEGATED)`; RUNNING → DELEGATED is rejected). Visually: a containment border change (different stroke/fill for delegated nodes) or an explicit delegation indicator. In the definition view, this boundary is shown as the edge between a Binding and its target Worker.

## 4. Implementation Order

### Phase 0 — Prerequisites

**Must complete before any editor work starts.**

| Task | Effort | Notes |
|------|--------|-------|
| Verify CaseDefinition.yaml JSON Schema against current Java model | S | Schema may be stale after stages removal |
| Generate TypeScript types from verified schema | S | Use json-schema-to-typescript or similar |
| Cross-parser YAML compatibility test | S | Parse→serialize with `yaml` npm, then parse with Jackson, verify semantic equivalence (§2.1) |
| Spike: React Flow + Lit bridge proof of concept | M | Validates: (1) bridge pattern with Pages design tokens, (2) CSS isolation without Shadow DOM against Pages shell global resets (§2.2 hard gate), (3) ELK layout integration |

### Phase 1 — Foundation (graph-core + graph-renderer)

**The domain-agnostic platform. No CaseHub-specific code.**

```
Epic 1A: graph-core                    Epic 1B: graph-renderer
├── Graph model (nodes, edges, tree)   ├── React Flow Lit bridge
├── Stencil registry                   ├── ELK layout integration
├── Constraint validator               ├── Custom node rendering pipeline
├── Edit operations (add/remove/       ├── Pan/zoom/select interaction
│   replace with validation)           ├── Node selection → event emission
└── Persistence SPI                    └── Basic toolbar (zoom, reset layout)
```

**1A and 1B can run in parallel** — graph-core is the data model, graph-renderer is the visual layer. They integrate at the end when the renderer consumes the graph model.

### Phase 2 — Case Stencil (read-only viewer)

**First visual output. Render a real case definition.**

```
Epic 2: graph-stencil-case (viewer)
├── Case domain adapter: toGraph() — YAML → graph model
│   Resolves capability references into binding→worker edges
├── Structural stencils with Lit templates
│   ├── Binding (trigger type icon, target type badge, when-condition preview)
│   ├── Worker (capability list, executor type indicator)
│   ├── Milestone (diamond shape, condition, SLA indicator via sla-indicator)
│   ├── Goal (terminal node, kind badge: success/failure/custom)
│   └── SubCase (recursive, I/O mapping indicator, M-of-N group badge)
├── Connection rules registered with graph-core
├── Edge derivation (capability dispatch, subCase spawn, completion criteria)
└── casehub-diagram Lit component (viewer mode only)
```

**Milestone:** render the `document-processing.yaml` example case as a visual graph.

### Phase 3 — Property Editing

**Click a node, edit its properties.**

```
Epic 3: Property editing
├── Property panel component (Lit, in casehub-diagram)
├── Schema-driven form generation from stencil PropertySchema
│   ├── Flat properties: pages-viz schema-form (string, number, boolean, enum, date)
│   └── Complex properties: domain-specific form components in graph-stencil-case
│       ├── Trigger type selector (oneOf: contextChange/cloudEvent/schedule/scopeActivated)
│       ├── Target type selector (oneOf: capability/subCase/humanTask)
│       ├── Worker execution type (anyOf: SWF/agent/sequence)
│       └── Nested object editors (outcomePolicy, executionPolicy, cbr)
├── Bidirectional binding: panel edits → domain model → YAML
├── Validation feedback (red borders, error messages)
└── Persistence: save edited YAML via backend
```

**Property rendering strategy:** CaseDefinition schemas use deeply nested structures with `oneOf` and `anyOf` composition (HumanTask, Trigger, Worker execution types). The existing `pages-viz` schema-form component handles flat fields (string, number, boolean, enum, date) but does not support nested objects or JSON Schema composition keywords. Rather than blocking on a generic nested-form solution (pages issue #222), Phase 3 uses a **two-tier approach**:

1. **Flat properties** (name, description, condition, when, etc.) render via schema-form directly
2. **Complex properties** (Trigger with `oneOf`, Worker with `anyOf`, HumanTask with exclusive modes) render via domain-specific form components in graph-stencil-case that understand the CaseDefinition schema structure

This is a better fit than a generic nested-form renderer because CaseDefinition's complex properties have domain-specific UX requirements (e.g., trigger type radio buttons with conditional sub-forms) that a generic renderer would handle poorly. Schema-form handles the common case; domain components handle the domain-specific case.

### Phase 4 — Structural Editing

**Add, remove, replace nodes. Auto-layout recalculates.**

```
Epic 4A: Structural editing           Epic 4B: Persistence backends
├── Palette component (structural     ├── Git backend (GitHub URL read/write)
│   stencils as draggable/clickable)  ├── In-memory backend (playground)
├── Add node at point (validated      └── Electron file backend (later)
│   against containment rules)
├── Remove node (with dependency
│   check — warn if connected)
├── Replace node (swap type,
│   preserve connections where valid)
├── applyEdit() in domain adapter
└── YAML round-trip (edit → serialize
    → parse → verify unchanged)
```

**4A and 4B can run in parallel.**

### Phase 5 — SWF Drill-Down

**Expand a SWF Worker to see workflow steps.**

```
Epic 5: graph-stencil-swf
├── Add @openworkflowspec/sdk as dependency (SWF YAML parsing + FlatGraph)
├── Domain adapter: sdk.buildFlatGraph() → graph model (adapter only, no parser)
├── Workflow step stencils (call, switch, raise, catch, entry, exit)
├── Drill-down trigger (expand Worker with SWF workflow → sub-graph)
├── casehub:dispatch trace lines (workflow step → Case capability)
└── Collapse back to single Worker node
```

### Phase 6 — Work Registry

**Runtime-discovered work stencils from marketplace.**

```
Epic 6: graph-work-registry
├── Marketplace YAML descriptor schema
├── URL-based discovery (fetch + parse + register)
├── Category index and palette integration
├── Work stencil → Worker node binding
├── Custom property forms per work type
└── Marketplace configuration UI (manage URLs)
```

### Phase 7 — Runtime Overlay

**Live execution state projected onto the design-time graph.**

```
Epic 7: Runtime overlay
├── Runtime data source via casehub-pages PushSource
│   ├── PushPool.acquire() for connection pooling (one connection per origin)
│   ├── PushSourceError.permanent for transient vs fatal error classification
│   ├── Auto-reconnect with full-snapshot re-subscription
│   └── Staleness indicator when connection is lost (gray overlay badge)
├── RuntimeAdapter: PlanItem[] → NodeDecoration per binding
│   ├── Active-worst-first aggregation for many-to-one PlanItem-to-Binding mapping
│   └── Tooltip shows full PlanItem status breakdown per binding
├── TaskStatus badges on nodes (all 9 states via NodeDecoration)
├── Milestone progression indicators (full MilestoneLifecycleStatus: 5 states)
├── Heatmap colouring (usage frequency)
├── Active binding highlighting (which bindings the planning strategy is evaluating)
├── CaseContext data preview on hover
└── Design ↔ Runtime mode toggle in toolbar
```

## 5. Parallelisation Map

```
Phase 0 (prerequisites)
    │
    ▼
Phase 1A (graph-core) ──────┐
Phase 1B (graph-renderer) ──┤ parallel
    │                       │
    ▼                       ▼
    └───────┬───────────────┘
            │ integrate
            ▼
        Phase 2 (case stencil — viewer)
            │
            ▼
        Phase 3 (property editing)
            │
            ▼
    Phase 4A (structural editing) ──┐
    Phase 4B (persistence backends)─┤ parallel
            │                       │
            ▼                       ▼
            └───────┬───────────────┘
                    │
          ┌─────────┼──────────┐
          ▼         ▼          ▼
      Phase 5    Phase 6    Phase 7    ← all three parallel
      (SWF)      (registry) (runtime)
```

**Maximum parallelism points:**
- Phase 1: 2 agents (graph-core + graph-renderer)
- Phase 4: 2 agents (structural editing + persistence)
- Phase 5/6/7: 3 agents (SWF stencil + work registry + runtime overlay)

**Sequential gates:**
- Phase 0 must complete before Phase 1 (types + spike)
- Phase 2 requires both 1A and 1B (first visual integration)
- Phase 3 requires Phase 2 (need a rendered graph to select nodes)
- Phase 4 requires Phase 3 (property editing validates the domain adapter round-trip)

## 6. Epic Sizing

| Phase | Epic | Size | Complexity | Parallel? |
|-------|------|------|-----------|-----------|
| 0 | Schema verification | XS | Low | — |
| 0 | Type generation | XS | Low | — |
| 0 | React Flow + Lit bridge spike | S | Med | — |
| 1A | graph-core | M | Med | Yes (with 1B) |
| 1B | graph-renderer | M | High | Yes (with 1A) |
| 2 | Case stencil (viewer) | L | High | No |
| 3 | Property editing | M | Med | No |
| 4A | Structural editing | L | High | Yes (with 4B) |
| 4B | Persistence backends | S | Low | Yes (with 4A) |
| 5 | SWF drill-down | M | Med | Yes (with 6, 7) |
| 6 | Work registry | M | Med | Yes (with 5, 7) |
| 7 | Runtime overlay | M | High | Yes (with 5, 6) |

## 7. Open Questions

1. **SWF visual language** — should the SWF drill-down render workflow steps in CaseHub's visual language (unified) or adopt SWF community visual conventions (familiar to SWF users)? Recommendation: CaseHub visual language for consistency, since SWF is always experienced through CaseHub.

2. **blocks-ui package structure** — new workspace packages (recommended) vs subdirectories in existing blocks-ui structure. Recommendation: new workspace packages for type-safe boundaries and independent consumption.

3. **@xyflow/lit timeline** — when to invest in a native Lit binding for xyflow. Not before Phase 4 is complete. Could be contributed upstream to the xyflow project.

4. **Lienzo port** — strategic option for full canvas control. Revisit after the React Flow approach proves out or hits limitations. 6-10 person-week investment. Provides canvas-level drawing API, scene graph, hit-testing, pan/zoom — but requires maintaining alone.

5. **YAML divergences from SWF** — Confirm the full list of CaseHub YAML conventions that diverge from the Serverless Workflow spec (known: CaseHub-specific fields like `bindings`, `milestones`, `goals`, `capabilities`, `cbr`, `authorization`, `episodic`). Ensure the domain adapter handles both correctly.

## 8. Non-Goals

- Free-form drag-and-drop node positioning (deferred — auto-layout first)
- Collaborative multi-user editing (future consideration)
- Code generation from visual model (the YAML IS the model)
- Animation of execution playback (runtime overlay shows current state, not history)
- Mobile/touch-first editing (desktop-first)
