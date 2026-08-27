---
layout: post
title: "The first consumer changes the design"
date: 2026-08-20
entry_type: note
subtype: diary
projects: [casehub-pages, casehub-platform, casehub-connectors, casehub-examples]
tags: [scenario-engine, mcp, platform-design, first-consumer]
series: issue-408-scenario-engine
---

*Continues from [Specifying a Scenario Format Before Building the Engine](2026-08-11-mdp01-scenario-format-spec.md).*

The task was straightforward: add `@McpDomain("helpdesk")` to the helpdesk example app so the scenario executor can drive it via MCP instead of REST. Four operations — bootstrap classifications, inject a ticket, query tickets, query notifications. The kind of thing that should take an hour.

I started by tracing the existing MCP domain system end to end. The platform already has `@McpDomain`, `@PlatformMutation`, `@PlatformQuery` annotations in `platform-api` — clean, zero-dependency, exactly what you'd want. But when I followed the discovery path, the architecture told a different story.

The `GraphQLModelScanner` only discovers domains via `@GraphQLApi` + MicroProfile GraphQL annotations. The platform's own annotations — the ones consumers are supposed to use — are invisible to it. They only work because a `GraphQLResolverProcessor` annotation processor generates `@GraphQLApi` wrapper classes at compile time. The codegen reads Jandex indexes, produces boilerplate resolvers, and the scanner discovers those.

For engine, work, ledger, and qhorus, this makes sense. Those modules genuinely expose GraphQL APIs — the MCP discovery piggybacks on infrastructure that already exists. But for the helpdesk app, which only wants MCP, it means pulling in `quarkus-smallrye-graphql`, the codegen annotation processor, and a Jandex plugin. Three dependencies, build complexity, a GraphQL endpoint the app doesn't want — all to register four operations.

The temptation was to just add the dependencies and move on. The codegen path works, connectors#96 proved it, and the helpdesk app would have been done in twenty minutes. But this is the first non-platform consumer of `@McpDomain`. The pattern established here gets copied by every subsequent example app. If the first consumer needs GraphQL to register MCP operations, every consumer will assume that's the cost.

So I filed platform#243 and the fix landed the same session. The scanner now has two discovery passes: first it checks CDI bean interfaces for `@McpDomain` + `@PlatformMutation`/`@PlatformQuery` directly; then it runs the existing `@GraphQLApi` scan for domains not already found. If a domain is discovered via the interface path, the GraphQL path skips it. Existing consumers are untouched — engine, work, ledger, and qhorus still use hand-written `@GraphQLApi` resolvers. Connectors, which has both an interface and a generated resolver, switches silently to the interface path for MCP while the generated class continues serving GraphQL.

The helpdesk implementation landed cleanly after that. `HelpdeskOperations` interface with four annotated methods, an `HelpdeskOperationsImpl` that delegates to existing services, a `HelpdeskModelEnricher` for the `casehub_model` catalog. The pom picks up just `casehub-platform-api` (already present) and `casehub-platform-mcp`. No codegen. No GraphQL. No Jandex plugin.

Along the way I hit a pre-existing Quarkus startup failure — the Signal connector module (landed recently via connectors#97) declares required `@ConfigProperty` fields that get validated at augmentation time even when the helpdesk app doesn't use Signal. This is a pattern the knowledge garden already documents: Quarkus ARC validates the entire bean dependency chain at build time, regardless of whether the beans are active at runtime. Filed connectors#98, fix landed upstream, removed the workaround.

The broader pattern here is worth naming. The first consumer of an internal API is a design review that no amount of spec writing can replicate. The platform MCP annotations looked right in isolation — clean interfaces, single-responsibility annotations, well-documented intent. But the discovery path was coupled to an implementation detail that only becomes visible when someone tries to use the annotations without the implementation. Specs describe intent; consumers reveal coupling.
