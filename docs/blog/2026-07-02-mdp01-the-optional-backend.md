---
layout: post
title: "The Optional Backend"
date: 2026-07-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [quarkus, backend, authentication, layout-persistence]
series: issue-88-dev-auth-jwt
---

casehub-pages has been 100% TypeScript since the migration from GWT. Every feature —
data pipeline, layout, navigation, rendering — runs in the browser with no server
dependency. That constraint was always intentional: pages is a foundation module that
consumer apps embed via iframe; adding a backend dependency would break every app that
doesn't want one.

The constraint holds. But some capabilities benefit from a server when one exists —
authentication with real `SecurityIdentity` and `@RolesAllowed`, layout persistence
that survives browser clears, server-side data processing that doesn't burn the
client's CPU. The question was never whether to add a backend, but how to add one
without making it mandatory.

The answer is a service abstraction the frontend already uses. `LayoutStore` has
`createLocalLayoutStore()` backed by `localStorage`. A `createRestLayoutStore()`
backed by a REST endpoint implements the same interface. The frontend calls
`layoutStore.save(key, state)` — it doesn't know or care where that goes. The app
wires the execution environment at configuration time. If no backend URL is
configured, everything works as before.

I wanted three modules in the first cut: auth, layout persistence, and a data
processing scaffold. Each is a separate Maven artifact — consumer apps pick what
they need at build time.

**Auth** is the simplest: SmallRye JWT with Quarkus's auto-generated RSA keypair.
`POST /dev/auth/login` accepts a name, mints a JWT with configurable roles and
tenant, returns the token. The endpoint is gated by `@UnlessBuildProfile("prod")` —
it doesn't exist in production builds. In prod, `casehub-platform-oidc` handles
authentication through the standard Quarkus security multiplexing chain. The two
coexist without conflict: they're independent `HttpAuthenticationMechanism` providers,
not competing CDI beans.

**Layout persistence** needed more thought. The SPI is straightforward —
`load(key, tenantId, userId)`, `save(...)`, `delete(...)` — with a `@DefaultBean`
no-op and a SQLite implementation that wins by CDI priority when present. But the
review surfaced a real flaw in my initial design: I'd scoped persistence by tenant
only, not by user. In dev mode where everyone gets `tenant_id=dev`, two developers
would silently overwrite each other's layouts. Adding `userId` from the JWT `sub`
claim fixed it, but it was the kind of bug that wouldn't have surfaced until two
people used the system simultaneously.

The SQLite module follows the `casehub-platform/memory-sqlite` pattern exactly:
xerial JDBC, HikariCP with WAL mode, Flyway migrations at
`classpath:db/layout-sqlite/migration/`. Same lifecycle, same configuration
properties, same pool sizing logic. When a JPA backend materialises, it ships as
a separate module with higher CDI priority.

The frontend side adds `createRestLayoutStore()` and `createDevAuthTokenFn()` to
`pages-runtime`, and two Web Components to `pages-ui`: a `<pages-dev-auth>` login
gate and a `<pages-identity>` switcher. The login gate checks `sessionStorage` for
a valid JWT, renders an overlay if absent or expired, and handles server-side
invalidation through a `pages-auth-expired` custom event. The debounce for layout
saves is now configurable via `SiteOptions.layoutSaveDelayMs` — REST stores pass
2000ms to avoid excessive network calls, but more importantly to avoid the CPU cost
of serialising the entire layout tree on every small interaction.

The backend parent POM inherits from `casehub-parent` like every other casehub repo —
Quarkus 3.32.2, Java 21, managed plugin config. The data module is a scaffold with
just a `pom.xml` and `package-info.java`. Its full design comes later, but the
structure is in place for when the browser-local data processing services get
server-side equivalents.

Thirty-nine files, four Maven modules, two Web Components, and no breaking changes
to any existing consumer. The frontend still works without a backend. That was the
point.
