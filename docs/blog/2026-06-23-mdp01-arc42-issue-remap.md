---
layout: post
title: "CaseHub Pages — ARC42STORIES Issue Remap"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [documentation, arc42]
---

Remapped all issue references in ARC42STORIES.MD §9 from the previous tracker to the current `casehubio/casehub-pages` GitHub issues.

The ARC42STORIES.MD was generated from a LAYER-LOG.md that tracked work under a different issue numbering scheme. Every `#N` reference in §9 — chapters, backlog, and the §12 risk table — pointed to a tracker that no longer exists.

**What changed:**

- **Chapters 1 and 2** converted from stale issue tables to milestone tables. Both chapters are complete — the issue numbers were noise, the milestone descriptions are the value.
- **Backlog** replaced entirely with the 14 current open issues (#12–#26) from `casehubio/casehub-pages`.
- **§12 risk reference** updated from old `#3` (JSONata bridge) to current `#16` (CSP compliance).

One commit, one file, 43 insertions / 40 deletions. Mechanical but necessary — every `#N` link in the document now resolves to a real GitHub issue.
