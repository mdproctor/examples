---
layout: post
title: "From Static to Reactive — Context Resolution for casehub-pages"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [context-resolution, web-components, echarts, runtime, reactivity]
---

casehub-pages has been a static dashboard runtime since its GWT-to-TypeScript migration. YAML defines datasets, components render, filters cross-propagate — but everything is fixed at parse time. The URL in a dataset definition can't reference a filter value. A panel can't hide itself based on what the user selected. A markdown block can't interpolate "5 patients in ICU" from live data.

The clinical trial demo changed that. casehubio/clinical needed dashboards where selecting a ward filters the patient list, hides irrelevant panels, rewrites dataset URLs to fetch ward-specific data, and lets users POST actions back to the server. Every one of these is a runtime concern — the dashboard adapts to context that doesn't exist until the user interacts with it.

I designed these as a single unified model rather than four separate mechanisms. A `RuntimeContext` object holds filter state, dataset snapshots, page state, and URL parameters. Components reference it via `#{path}` templates — a syntax deliberately distinct from the existing parse-time `${}` property substitution. One template parser, one expression evaluator, one reactivity engine. The expression evaluator is a recursive descent parser — no `eval()`, no `new Function()` — because CSP compliance (#16) was already a known risk.

The `visibleWhen` property was the most interesting design question. The obvious implementation — `display: none` — leaves the component in the DOM, still fetching data, running timers, evaluating templates. The adversarial review caught this immediately. We ended up with a suspension model: hidden components get the `hidden` attribute, their refresh timers stop, dataset fetches are suppressed, and template evaluation freezes. Only the `visibleWhen` expression itself continues evaluating so the component can wake up when its condition becomes true.

Content components (alert banners, action buttons) needed a different base class than data components. `PagesElement` gates rendering on `this._dataset` — a content component with no dataset would be stuck in a permanent loading state. `PagesContentElement` gives them shadow DOM and props without data machinery. The action button dispatches a `pages-action-request` event that the runtime catches, executes via an `ActionExecutor` with the host-provided `fetch`, and triggers dataset refresh on success. Form inputs got the same mechanism via a `submit` prop — fire-and-forget POST on Enter.

The seven new visualization components follow the established patterns. Badge and countdown extend `PagesElement`. Timeline uses ECharts `type: 'custom'` with `renderItem` for horizontal duration bars. Graph uses ECharts `type: 'graph'` with force-directed layout, deriving nodes from the edge dataset. The expandable tree-table was the most complex table enhancement — the pipeline normally owns pagination, but a tree-table needs all rows to paginate by root count. We added a pipeline bypass: when `expandable` is present, the pipeline delivers everything and the component handles pagination internally.

One gotcha worth documenting: ECharts `api.value(N)` for out-of-range indices returns `0`, not `undefined`. The timeline's `renderItem` was checking `api.value(3) !== undefined` on a 3-element array — always truthy, rendering every item as a diamond milestone instead of a bar. The fix was trivial once found, but the symptom (all diamonds, no bars) gave no hint about the cause.

The naming question surfaced mid-session: should the class prefix be `Casehub*` or `Pages*`? The packages are `@casehubio/pages-*`, but the classes were `CasehubElement`, `CasehubBarChart`. I wanted `Pages*` for consistency — and it's the right answer long-term — but clinical was actively building against the current API. We filed #55 for the rename and did it as a separate branch before returning to the epic. All new components were born with `Pages*` names.

The branch lands 12 issues worth of capabilities in a single coherent architecture. Everything is domain-agnostic — the clinical app will be the first consumer, but any casehub-pages dashboard can wire a button to any REST endpoint, hide a panel based on any filter, or interpolate any dataset value into a markdown block.