---
layout: post
title: "The First Web Component"
date: 2026-07-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [terminal, xterm, web-component, websocket]
series: issue-80-terminal-component
---

## What I was trying to achieve: a reusable terminal for the platform

Every CaseHub host app that talks to Claude needs a terminal. Claudony built one — `session.html` plus 830 lines of vanilla JS wiring xterm.js to a tmux-backed WebSocket. It works. But drafthouse will need one too. And devtown. And anything else that surfaces a Claude session.

The issue (#80) was clear: make it a stock pages component. One import, one `hostPanel()` call, done.

## What I believed going in: this would be a thin wrapper

xterm.js does the rendering. The WebSocket does the transport. The component just connects the two. I expected the props interface from the issue — `wsUrl`, `fontSize`, `fontFamily`, `scrollback`, `fitToContainer` — to hold up mostly as written.

I was half right.

## The chicken-and-egg problem and the protocol mismatch

The first real question was who owns the WebSocket. xterm.js doesn't — `AttachAddon` receives an already-created `WebSocket` and wires four lines of piping. So the consumer creates it? Can't — the WebSocket URL needs terminal dimensions (`/ws/{id}/{cols}/{rows}`), and dimensions don't exist until the terminal is mounted and fitted. The consumer doesn't know `{cols}` and `{rows}` at the time it constructs the URL.

URL templates solved this cleanly: the consumer writes `ws://host/ws/session-1/{cols}/{rows}`, and the component replaces `{cols}` and `{rows}` after fitting. The component owns the WebSocket lifecycle — create, reconnect, teardown — because it's the only entity that knows both the URL template and the current dimensions.

The design review found deeper problems. The most interesting: claudony's server sends `{"type":"session-expired"}` as a plain text message on the WebSocket when a tmux session dies. The original spec had the component discriminating between terminal output and control messages by checking if the text starts with `{` and parses as JSON with a `type` field. Claude pointed out the collision: `echo '{"type":"session-expired"}'` in a terminal would be swallowed. Any program that outputs JSON would occasionally have its output eaten.

The fix was to move session expiry out of band entirely — WebSocket close code 4001 (RFC 6455 reserves 4000-4999 for application use). One line changes on the server (`conn.closeAndAwait(new CloseReason(4001, "session-expired"))` instead of `conn.sendTextAndAwait(...)`) and the terminal data path becomes pure: every text message is terminal output, no parsing, no discrimination.

## What the component actually is

A custom element — `pages-component-terminal` — that extends `HTMLElement` directly. Not an iframe component (the existing echarts and llm-prompter components use iframes with a postMessage bridge, but terminal output is a raw byte stream, not structured JSON datasets). Not a shadow DOM component (xterm.js creates its own complex DOM structure and manages its own CSS; shadow DOM would complicate CSS loading for no benefit).

The lifecycle is straightforward: mount → create xterm.js Terminal → fit to container → replace URL template placeholders → connect WebSocket → wire bidirectional piping. Container resize triggers a refit and a `terminal-resize` event. Disconnection triggers exponential backoff reconnection (with `terminal.reset()` before each attempt, because the server replays capture-pane history on every new connection). Close code 4001 is permanent — no reconnect, dispatch `terminal-disconnected` with `reason: "session-expired"`.

One public method: `sendInput(text)` — sends text through the WebSocket as if the user typed it. This is the integration point for compose overlays, paste-from-clipboard, and mobile key bars. The existing `terminal.js` uses `terminal.paste()` for this; the component wraps it behind a clean API so consumers don't need direct terminal access.

Everything communicates via `pages-event` CustomEvents with a discriminated `detail.topic` field — consistent with the workbench primitives spec's inter-panel communication pattern.

## Where this leaves us

The terminal is the first Web Component in `components/`. The existing three components there are all iframe components (React + webpack + postMessage). This one is plain TypeScript compiled with `tsc` — no webpack, no React, no iframe API. It's a different pattern, and probably the one future components should follow when they don't need iframe isolation.

Claudony's migration path is a `registerPanel("terminal", "pages-component-terminal")` call and a `hostPanel("terminal", { wsUrl: ... })` in its layout tree. The standalone `session.html` page can eventually go away, replaced by a terminal panel embedded directly in the dashboard alongside the session grid.
