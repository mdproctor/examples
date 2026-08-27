---
layout: post
title: "The gallery needed a server"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [examples, push-protocol, gallery, quarkus, docker]
---

The examples gallery had a gap. Every dashboard ran client-side — static data, mock fetch responses, no real WebSocket traffic. With durable EventStore backends already landed, the push protocol had no home where someone could actually see it working.

We added a Server tab. The gallery now has two top-level tabs — Client (the existing static samples) and Server (four dashboards that require a running backend). A `requiresServer` flag in `samples.json` gates the split; the generate script propagates it; the gallery filters categories by active tab. URL hash routing carries the tab prefix: `#server/Live%20Data/sample-path` vs `#client/Charts/sample-path`, with bare hashes defaulting to Client for backward compatibility.

The server itself is a minimal Quarkus app under `examples/server/`. It wires `casehub-pages-push-runtime` with the JDBC event store, exposes the push WebSocket at `/ws/push`, and runs a scheduled event generator every two seconds across three demo topics. A `ConnectionRegistry` bean shares the WebSocket connection map between the endpoint and the `SessionSender` bridge — the same pattern the push runtime expects from any consumer. Docker Compose brings up Postgres and the server with a single `docker compose up`.

The interesting part of the data flow is the push delivery fix in `data-pipeline.ts`. When a push event arrives for a dataset, the pipeline now iterates the component registry to find all visualisations subscribed to that dataset and pushes the data down. Without this, the WebSocket connection opened and events arrived, but nothing rendered — the existing fetch-based pipeline had no listener path for push updates.

The branch also carries smart-edge routing for the graph renderer — a `@tisoap/react-flow-smart-edge` integration that pathfinds around nodes instead of drawing straight lines through them. During the close review, Claude caught a coordinate system mismatch in the edge-routing validator: `handleCenter` was using relative `node.position` while `absoluteRect` walked parents for absolute coordinates. For diagrams with nested nodes, the crossing checks were comparing coordinates in different frames. A straightforward fix — `handleCenter` now calls `absoluteRect` — but the kind of bug that only surfaces when someone uses container nodes in a validation test.

The four server dashboards cover the ground the issue specified: live event streaming, disconnect/reconnect with EventStore replay, persistence across server restarts, and wildcard multi-topic subscriptions. Each one exercises a different part of the push protocol that was previously only tested in Java unit tests.
