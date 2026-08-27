---
layout: post
title: "The Dashboard Problem"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [documentation, positioning, cross-repo]
series: issue-64-workbench-primitives
---

casehub-pages had an identity crisis baked into every document it owned.

I noticed it when Claudony's adoption issue came back with a flat rejection. Their assessment: "casehub-pages is a data visualization DSL — tables, charts, lookups, datasets, cross-filtering. These are fundamentally different concerns." They concluded the framework couldn't host their session management console — terminal emulation, SSE streams, WebSocket connections, inline actions. The answer was option 1 (just the build pipeline) or option 2 (DSL only where it fits), and neither was exciting.

They were right about what the docs said. They were wrong about what the framework actually does.

The phrase "foundational dashboard rendering runtime" was everywhere. README line 4. PLATFORM.md's capability ownership table. The public presentation slides. Every app's CLAUDE.md. The examples gallery. ARC42STORIES.MD. Even the workbench primitives design spec — which exists specifically because casehub-pages is evolving beyond dashboards — had a line saying "casehub-pages capability ownership entry in PLATFORM.md unchanged — still 'YAML dashboard rendering.'"

I ran Claude as two parallel audit agents across the entire CaseHub ecosystem. One swept the casehub-pages repo. The other swept every sibling and parent repo. Between them they found the "dashboard" framing in 10+ files across 7 repositories. The self-reinforcing loop was clear: apps read the docs, concluded "dashboard tool," treated it accordingly, which reinforced the framing for the next app.

The fix was a cross-repo documentation sweep. The new one-liner: "a web application framework for the CaseHub platform." The README now has a five-category capabilities table — Architecture, Application Shell, Data, Components, Developer Experience — that surfaces the reactive data pipeline, unified data+event bus, data–display separation, recursive composition, and component hosting alongside the visualization and forms catalogue. A Getting Started section gives opinionated guidance: build with Quinoa, use the TypeScript DSL, start from `loadSite()`, bring your own components via `hostPanel()`, compose recursively, drive requirements upstream.

That last point matters most. The apps were treating casehub-pages as a fixed toolkit to evaluate against their needs — "can it do X? no? then we won't use it." The relationship should be the opposite. DraftHouse needed workbench primitives, so #64 exists. Claudony needs a terminal component, so that can become `@casehub/pages-component-terminal`. The framework evolves from app requirements. The docs now say this explicitly.

Seven repos updated and pushed in one session. Two issues filed: #78 tracks cross-repo follow-up, #79 tracks the examples gallery code rename (the directory is literally called `dashboards/` — that's a structural rename, not a text swap). ARC42STORIES.MD got the same treatment, updating every section from §1 through §13.

The Claudony answer, with the updated docs, is now clear. Not option 1 (just build pipeline) and not option 2 (DSL where it fits). It's: pages provides the workbench shell, layout, data pipeline, and inter-panel communication; Claudony provides custom panels as Web Components. The session grid is a `hostPanel()`. The terminal is a `hostPanel()`. The channel panel is a `hostPanel()`. Pages manages layout, lifecycle, and communication around them. The data visualization DSL is one capability among many — not the whole story.