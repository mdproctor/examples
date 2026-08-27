---
title: "YAML Viewer and Visual Feedback — the audience can follow along"
date: 2026-08-23
type: diary
tags: [scenario-engine, yaml, visual-feedback, lit]
---

# YAML Viewer and Visual Feedback

The scenario controller now has two new capabilities that make demos
transparent: a YAML source viewer that tracks execution position, and
visual feedback that highlights what the automation is doing.

## YAML Fly-Out Viewer (#349)

A `<pages-scenario-yaml-viewer>` component fetches the scenario YAML,
tokenizes it for syntax highlighting (regex-based), and maps step
labels to source line ranges using the `yaml` package's AST. As the
scenario runs, the active step's YAML block scrolls into view with a
blue highlight bar.

The viewer floats as a dark-glass panel alongside the controller. The
two panels dock together — drag one, drag both. An undock button
separates them for independent positioning. A detach button pops the
viewer into its own browser window with a standalone mode that fills
the viewport.

Design decisions: zero new dependencies (yaml package already in
pages-aria), regex tokenizer for highlighting + AST for position
tracking (not Prism.js), second floating element rather than tab
within the controller card.

## Visual Feedback (#351)

A `visual-feedback.ts` module provides three functions:
`injectStyles()` for CSS animation injection, `highlightElement()` for
blue pulse outlines on clicks, and `typeText()` for progressive
character-by-character form filling with a green focus ring.

The scenario handler calls these before each command. The executor
stays pure — visual feedback is a presentation concern at the
orchestration layer.

## Cleanup

Removed debug `console.log` from scenario-handler, fixed duplicate
handler creation on WebSocket reconnect, added HTTP status check on
ticket submission.

## Epic Complete

All 17 issues in the scenario engine queue are done. The system runs
end-to-end: YAML scenario file drives browser automation and backend
processing, the controller shows live progress, the YAML viewer
tracks position, and the audience sees every interaction highlighted.
