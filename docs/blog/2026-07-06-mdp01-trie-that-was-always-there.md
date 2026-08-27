---
layout: post
title: "A trie that was always there"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [trie, performance, TopicRegistry, push]
---

The issue said "only when profiling demonstrates the O(n) scan is a bottleneck." I nearly deferred it on those grounds — current scale is under 100 patterns. But the more I looked at `TopicRegistry`, the more the trie felt like a design correction rather than a performance optimization.

Topics are colon-delimited hierarchical strings: `debate:abc:summary`. The existing code stored them in two flat `ConcurrentHashMap`s — one for exact topics, one for wildcard patterns. Every `connections()` call linearly scanned the wildcard map, calling `matches()` on each entry. The data structure didn't reflect the data shape.

A trie mirrors the segment hierarchy directly. Each node's children map holds the next segment. `*` is just another child key. `**` is stored as a connection set on the parent node (not a child — it matches zero or more remaining segments, so it's collected at every level during the walk). The walk forks into exact and `*` branches at each depth, giving O(2^d) worst case — but since wildcard patterns are sparse, most levels have only an exact child, so it's effectively O(d).

The design review caught several things I'd missed. The complexity claim was wrong — I'd written O(d) without accounting for the star-branch forking. The `collectAllDescendantTopics` helper was referenced in the spec but never defined. The `**` removal has a subtle asymmetry: the terminal node for a `**` pattern is the parent of where `**` would be, not a child. And the memory trade-off needed explicit treatment — the trie creates O(d) nodes per topic vs O(1) map entries, and empty nodes persist after unlisten.

The implementation was 128 lines in, 42 lines out. All 49 existing tests passed on the first run without modification, plus one new test the review identified: `matchedTopics` with `**` matching zero trailing segments (the prefix itself). `collectAllDescendantTopics` must check the starting node's own connections — an implementation that only visits children would silently miss this case.

The two flat maps are gone. One trie root, one reverse index for connection cleanup. The public API is identical.
