---
layout: post
title: "Cross-Repo Coordination — When a Single PR Isn't Enough"
date: 2026-07-31
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-devtown]
tags: [case-definition, coordination, merge-queue, cross-repo]
---

Multi-repo changes break every existing merge tool. GitHub doesn't support atomic cross-repo PRs. Merge queues operate per-repo. When the third of five repos fails to merge, you have three repos with diverged state and no clear owner of the cleanup. The coordinated change case definition is CaseHub's answer to that problem.

## The shape of the problem

A cross-repo change is a set of PRs across multiple repositories that must land together or not at all. The individual PRs have already passed review — the coordination concern is distinct from the review concern. What the coordinator needs to guarantee:

1. Every PR passes its own review lifecycle before any merge begins
2. Repos merge sequentially — if the third fails, the first two get reverted
3. If automated rollback itself fails (merge conflicts during revert), a human gets paged
4. The entire decision chain — who approved, what merged, what rolled back — is auditable

That fourth point is why a shell script won't do. You can write sequential merge logic in fifty lines of bash. What you can't do in bash is produce a tamper-evident record of every coordination decision that survives legal scrutiny six months later.

## How the case definition works

The `coordinated-change.yaml` declares this as a set of goals, not a sequence of steps:

```yaml
goals:
  - name: all-repos-merged
    kind: success
    condition: >-
      .mergeResults != null and
      (.mergeResults | all(.status == "success"))

  - name: merge-failed
    kind: failure
    condition: >-
      .mergeResults != null and
      (.mergeResults | any(.status == "failed")) and
      ((.rollbackResults != null and
        (.rollbackResults | all(.status == "success")))
        or .rollbackEscalation != null
        or .abandoned == true)
```

Two things worth noticing. First, the `merge-failed` goal doesn't fire when the merge fails — it fires when the entire recovery chain resolves. The merge fails, rollback runs, and only when rollback either succeeds or gets escalated to a human does the case reach its terminal state. This is goal-based completion, not step-based: the engine doesn't care about the order of events, only whether the goals are satisfied.

Second, the binding conditions that trigger work are declarative JQ expressions evaluated against the case context. When `allReviewsCompleted` becomes true, the merge binding fires. When `mergeResults` contains a failure and `rollbackResults` is null, the rollback binding fires. No orchestration code decides "what happens next" — the blackboard drives it.

## Parent-child: two tiers of case management

The real design is two-tier. `CoordinatedChangeService.start()` creates a parent coordinated-change case and then kicks off individual PR review cases for each repo in the change set. Each child case goes through the full PR review lifecycle independently — code analysis, security review, human approval gates, the works.

`CoordinatedChangeObserver` watches for child case lifecycle events. When a child case completes successfully, the observer signals the parent. When all children complete, it sets `allReviewsCompleted` on the parent's context — which triggers the merge binding. If any child faults, the observer signals `reviewFaulted` on the parent, which terminates the coordination.

The tracker holds the parent-child mapping in memory, and `CoordinatedChangeTrackerHydrator` rebuilds it from durable case state on startup. Coordinated changes survive server restarts.

## Merge and rollback as worker functions

The `CoordinatedChangeCaseHub` augments the YAML definition with two worker functions — `coordinated-merge` and `coordinated-rollback`. The merge worker iterates repos sequentially, calling `MergeClient.merge()` for each. If any merge fails, it returns immediately with partial results — the already-merged repos are recorded, and the rollback binding fires.

The rollback worker reverses the process: for each successfully merged repo, it calls `RevertClient.revert()` with a commit message that traces the coordination failure. If a revert itself fails — merge conflicts during the rollback, typically — the `rollback-human-escalation` binding fires, creating a WorkItem for the repo maintainers with a four-hour SLA.

That escalation path is important. Automated coordination is the happy path. The case definition also models the unhappy paths — all the way down to "a human has to manually clean up a partially reverted cross-repo change." Most merge queue designs stop at the happy path and leave the failure modes to ad hoc incident response.

## Why this matters beyond devtown

The coordinated change case definition is interesting not because of what it does — sequential merge with rollback is conceptually simple — but because of what it demonstrates about the CaseHub approach.

Every coordination tool I've seen treats multi-repo merges as a workflow: step 1, step 2, step 3, error handler. CaseHub treats it as a set of declared goals with binding conditions. The difference shows up when reality deviates from the plan. A workflow has a pre-determined error path. A goal-based system evaluates what's true right now and fires whatever binding matches. When the rollback fails and a human resolves it manually, the case doesn't need an explicit "manual resolution" step wired into a workflow graph — the `merge-failed` goal's condition simply becomes true once `rollbackEscalation` is populated.

This scales to coordination problems well beyond merges. Clinical trial multi-site data submission. Regulatory filings that span jurisdictions. Any domain where "all of these must succeed, or we unwind everything" is the requirement. The coordinated change case definition is the first complete demonstration of that pattern on the CaseHub platform.
