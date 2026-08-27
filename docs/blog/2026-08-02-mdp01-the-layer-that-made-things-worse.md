---
layout: post
title: "The Layer That Made Things Worse"
date: 2026-08-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [css, web-components, react-flow, lit, css-isolation, graph-editor]
---

CaseHub is entirely Lit Web Components. React Flow is the best open-source graph editor. Making them coexist required disabling Shadow DOM on the canvas — which meant finding another way to isolate CSS.

The obvious answer is `@layer`. Put the library CSS in a layer, and host globals can't touch it. Every article on CSS isolation says this. The spec we wrote said it. It's wrong.

## The Cascade Trap

CSS cascade layers have an ordering rule that contradicts every intuition about isolation: **unlayered CSS beats layered CSS.** Not the other way around.

If your host page has `* { box-sizing: border-box }` (unlayered, as virtually all existing CSS is), and you put your library CSS in `@layer diagram.reactflow`, the host's declaration wins. Always. Regardless of specificity. Layers only control priority *between* layers — they have zero effect on unlayered content.

The spec we'd written for the graph editor prescribed `@layer` as the primary isolation mechanism. It would have produced the exact opposite of isolation. Every host global reset — `button { appearance: none }`, body font stacks, box model overrides — would have leaked straight through into React Flow's controls, minimap, and zoom buttons.

The fix is three mechanisms, each doing one job:

**`all: initial` on the container** — the only thing that actually blocks inherited host styles in light DOM. Resets everything including custom properties, which is the price of actual isolation.

**Scoped `all: revert` on children** — `.diagram-root * { all: revert }` at specificity 0,1,0 beats the host's universal selectors. Reverts every child element to browser defaults. React Flow's own CSS, loaded afterward, overrides these via source order.

**Theme re-injection** — `all: initial` killed the design tokens along with the host styles. We call `applyTheme()` from `pages-ui-tokens` on the container element, which injects a `<style>` *inside* the container. Because it appears later in document order than the head stylesheet's `all: initial`, the token declarations win. No manual token list — the theme API already knows every token.

Source order replaces `@layer` for internal cascade control. Later imports win at equal specificity. Same effect, without the unlayered-beats-layered trap.

## The Bridge Is Smaller Than You Think

The Lit-to-React bridge itself is roughly fifty lines. A Lit element skips Shadow DOM (`createRenderRoot() { return this; }`), creates a container div, mounts a React root in `connectedCallback`, unmounts in `disconnectedCallback`, and re-renders in `updated()`. Lit properties flow in as React props. React callbacks flow out as `pages-event` CustomEvents with `composed: true`.

```typescript
@customElement('pages-graph-canvas')
export class GraphCanvas extends LitElement {
  @property({ attribute: false }) nodes: Node[] = [];
  @property({ attribute: false }) edges: Edge[] = [];

  override createRenderRoot(): HTMLElement { return this; }

  override connectedCallback(): void {
    super.connectedCallback();
    this._container = document.createElement('div');
    this._container.classList.add('diagram-root');
    injectIsolationStyles(this._container);
    applyTheme(getTheme(document.documentElement) || 'default-light', this._container);
    this.appendChild(this._container);
    this._root = createRoot(this._container);
    this._renderReact();
  }
  // ...
}
```

One subtlety: `applyTheme()` dispatches a `pages-theme-change` event with `bubbles: true`. If the bridge listens for this event on `document.documentElement` to track host theme changes, it catches its own container's theme application bubbling up — infinite loop. The fix is filtering by `e.target === document.documentElement`.

## Why v12, Not v11

The parent design spec chose React Flow v11 (`reactflow` npm, React 17) for compatibility with pages' current React 17. But the same spec says React is isolated to `graph-renderer` — it bundles its own React, separate from the iframe components. There's no version constraint.

React Flow v12 (`@xyflow/react`) requires React 18, has better TypeScript generics, subscription-based reactivity, and is actually maintained. v11 is bug fixes only. Starting with v12 means zero migration later. The ~3KB bundle difference is noise.

## Plugin-First from Day One

Everything is registered, nothing is hardcoded. A `NodeTypeDescriptor` carries a component and optional CSS. `registerNodeType()` adds it to a Map. `getNodeTypes()` builds the `nodeTypes` prop for React Flow. The spike's own sample nodes go through this interface — the same path that `graph-stencil-case` will use in Phase 2 to register Binding, Worker, Milestone, Goal, and SubCase stencils.

This matters because the graph renderer is framework infrastructure — it should know nothing about CaseHub domain types. When a SWF stencil package or a marketplace work registry needs to contribute node types, the mechanism is already there.

## The Parser Round-Trip Held

The other Phase 0 gate: does the `yaml` npm package (CST-preserving, v2+) round-trip CaseHub YAML files without losing semantics? The engine uses Jackson + SnakeYAML. Expression strings like `${ .document.contentType }` with their `${` syntax could trip either parser's quoting heuristics.

It held. Every fixture — full case definitions, expression strings with comparison operators and null checks, multiline block scalars, mixed quoting styles — round-tripped through `parseDocument()` → `toString()` and parsed identically by both `js-yaml` (same YAML 1.1 spec as SnakeYAML) and Jackson itself. The YAML-as-source-of-truth design stands.

## What This Opens

Phase 0 was a validation gate. Both hard gates passed: React Flow renders inside Lit with correct CSS isolation, and the YAML round-trip works across parsers. The graph renderer package exists with a production-quality bridge, plugin registry, ELK layout adapter, and CSS isolation — not a throwaway spike.

Phase 1A (graph-core: model, stencil grammar, constraint validator) and Phase 1B (graph-renderer: full rendering pipeline, custom node wrapping) can now run in parallel. The bridge code is the foundation of 1B. The registry API evolves into the `StencilDescriptor` contract. The CSS isolation is validated and final.

The deeper question is whether `@layer` as an isolation strategy is a widespread misconception or just a gap in how it's taught. The cascade ordering is per-spec and intentional — layers are for organizing your own CSS, not for defending against someone else's. Every web component project embedding a third-party library in light DOM will hit this wall.
