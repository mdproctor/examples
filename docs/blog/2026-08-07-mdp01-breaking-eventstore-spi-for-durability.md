---
layout: post
title: "Breaking the EventStore SPI to Make It Durable"
date: 2026-08-07
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [push-protocol, event-store, cdi, redis, jdbc, spi-design]
---

The push protocol's `EventStore` was designed to be replaced — the CDI `@DefaultBean` on `InMemoryEventStore` was an explicit invitation. Issue #113 takes that invitation: PostgreSQL and Redis Streams as drop-in durable backends, activated by classpath presence alone.

I broke the SPI in two places. `StoredEvent` gains an `Instant createdAt` because durable stores need timestamps for time-based retention, and callers benefit from knowing when events were stored. `EventStore.replay()` gains an `int limit` parameter because a JDBC store with a year of events shouldn't return all of them in one call. The InMemoryEventStore's ring buffer made this invisible — bounded capacity was doing the limiting implicitly. With durable stores, the SPI has to do it explicitly.

The CDI priority ladder maps cleanly. InMemoryEventStore stays as the `@DefaultBean` functional fallback. JDBC gets plain `@ApplicationScoped` (Tier 2) — add the Maven dependency, it displaces the in-memory default. Redis gets `@Alternative @Priority(1)` (Tier 3) — if you somehow put both on the classpath, Redis wins. Each is a separate Maven module, each self-contained. No configuration flags, no `@IfBuildProperty`, no consumer-side wiring. The classpath IS the configuration.

The Redis data model turned up something I didn't expect. Redis Streams with explicit IDs map beautifully to application-level sequence numbers — `XADD key <seq>-0 payload <json>` gives you a clean long-to-stream-ID mapping, and `XRANGE key (<sinceSeq>-0 +` with the exclusive `(` prefix is an exact match for the `seq > sinceSeq` replay semantics. The design review caught the trap: `XTRIM MINID` interprets stream IDs as millisecond timestamps. With seq-based IDs, `MINID 42` means "entries older than January 1st, 1970 at 00:00:00.042" — not "entries with seq less than 42." All three review dimensions flagged it independently, which is a good signal that it's genuinely non-obvious. The fix is inelegant but correct: iterate with `XRANGE`, check the `createdAt` field, `XDEL` expired entries.

The SPI changes are landed — 9 files, all 127 tests across push and push-runtime green. The two durable modules (JDBC and Redis) are fully designed and planned but not yet implemented.
