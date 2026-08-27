---
layout: post
title: "Version Drift and the Repos You Forgot"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [versioning, multi-repo, ecosystem]
---

I asked a simple question after the last session's work: are we still aligned with the rest of CaseHub? The answer was no — in two directions. The npm packages had been bumped to 0.3.0, ahead of casehub-parent's 0.2-SNAPSHOT. The Maven backend modules were still at 0.1-SNAPSHOT, behind it.

The fix was mechanical — 7 npm `package.json` files back to 0.2.0, 7 Maven `pom.xml` files forward to 0.2-SNAPSHOT. But the interesting part was tracing the blast radius.

## Who actually depends on us?

I expected DraftHouse and Connectors to be the obvious consumers. DraftHouse doesn't reference any pages modules yet — the typed push SDK adoption is filed but not started. Connectors has exactly one dependency: `casehub-pages-auth` at `0.1-SNAPSHOT` in `chat-demo/pom.xml`. That needed bumping.

AML was the surprise. On its `issue-91-aml-workbench-ui` branch, the webui declares npm dependencies using `file:` protocol references:

```json
{
  "@casehubio/pages-runtime": "file:../../../../../pages/packages/pages-runtime"
}
```

Five levels of `../` to reach a sibling checkout. This means AML's frontend only builds if `casehub-pages` is checked out as a sibling under the same parent directory. Neither repo documents this. The version number change doesn't affect it — `file:` resolves by path, not version — but it's the kind of invisible coupling that breaks someone's first day on the project.

## Making it stick

The version alignment itself is a one-off fix. The rule — that all casehub-pages modules track casehub-parent's major.minor — is a standing constraint. We formalised it as a project protocol: npm packages use `<major>.<minor>.0` matching parent's `<major>.<minor>-SNAPSHOT`, Maven modules use the same SNAPSHOT version. When either moves, downstream consumers with hardcoded versions must move too.

The broader pattern: version drift in a multi-repo ecosystem doesn't announce itself. Each repo bumps independently, and the divergence only surfaces when someone asks a question like "wait, is this still aligned?" Worth asking periodically rather than waiting for a build to break.
