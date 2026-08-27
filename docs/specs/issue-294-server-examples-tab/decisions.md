## D1: Gallery tab mechanism

**Choice:** Top-level tabs above the sidebar — "Client" and "Server" tabs switching the entire category sidebar between two disjoint sample sets
**Alternatives:**
- Server badge per category — single flat sidebar with filter toggle; mixes concerns, harder to see what needs a backend
- Separate route — two independent gallery pages; duplicates the gallery shell, harder to maintain
**Rationale:** Clean separation between client-only and server-dependent demos. User immediately knows which mode they're in. Connection status indicator in the Server tab gives clear feedback.
**Trade-offs:** samples.json needs a `requiresServer` field per category.
**Sources:** examples/src/app.js (current gallery structure), examples/samples/ (28 existing categories)
**Exploration:** quick
**Status:** captured

## D2: Backend app location

**Choice:** `examples/server/` — co-located with the gallery
**Alternatives:**
- `backend/examples/` — follows Java module structure but separates from the gallery it serves
**Rationale:** One `docker-compose.yml` in `examples/` starts everything. The server exists to serve the gallery, not as a platform module.
**Trade-offs:** Java code outside `backend/` — acceptable because it's a demo app, not a platform module.
**Sources:** examples/ (gallery root), backend/ (platform modules)
**Exploration:** quick
**Status:** captured

## D3: Server-tab demo scope

**Choice:** All 4 dashboards — live stream, reconnect replay, persistence, multi-topic
**Alternatives:**
- Core 2 only (live + reconnect) — misses persistence and wildcard demos
- Just live stream — minimal but doesn't showcase the protocol's depth
**Rationale:** Full showcase demonstrates the push protocol's complete value: real-time delivery, resilience on reconnect, durability across restarts, and topic routing.
**Trade-offs:** More work upfront; all 4 dashboards need a working backend with Postgres + Redis.
**Sources:** Issue #294 body, #113 (durable EventStore — landed)
**Exploration:** quick
**Status:** captured
