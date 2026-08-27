# Example Applications — Progressive Platform Learning + Scenario Demos

> **Epic:** casehubio/parent#408 — Cross-Platform Scenario Engine
> **Replaces:** #93 (Demo SPI alternatives — premise was wrong)
> **Repo:** casehub-examples (standalone examples, not synced from source repos)
> **Audience:** Platform builders, new adopters, demo presenters

## Overview

Build a progressive set of example applications in casehub-examples. Each
is a thin slice of a problem domain that showcases specific CaseHub platform
capabilities. Each ships with scenario files that serve dual purpose:
demo mode (fully scripted, no external dependencies) and live mode (real
integrations, scenario as verification harness).

The examples collectively form a capability coverage matrix — when all
platform capabilities are covered, the scenario tool is also fully exercised.

## Principles

1. **Demand-driven demo alternatives.** Demo SPI impls are not built
   speculatively. Each example app contains its own `@Alternative
   @IfBuildProfile("demo")` implementations for exactly the connectors
   and LLM providers it uses.

2. **Every LLM integration point behind an SPI.** All classification,
   routing, embedding, and generation calls must sit behind an SPI with
   CDI alternative selection. The demo alternative returns pre-defined
   responses from scenario data. The SPI boundary is mandatory; the
   scripted impl is optional (you can run with a real LLM). Where the
   platform already provides an SPI (e.g. `AgentProvider`), use it —
   add a demo-profile implementation, don't create parallel boundaries.

3. **Two modes, one file.** Each scenario file drives both demo mode
   (all external dependencies scripted) and live mode (real connectors
   and LLM, scenario expectations become verification assertions).
   Live-mode verification asserts **outcomes, not outputs** — verify
   downstream state changes (ticket categorized, work item assigned),
   not LLM text (which is non-deterministic).

4. **Thin slices, not reference apps.** Each example stays small enough
   to understand in isolation. Capabilities may be composed within a
   slice, but no slice tries to cover everything. These are not devtown
   — they are focused demonstrations.

## Capability Coverage Matrix

Each slice introduces platform capabilities. Future slices fill gaps.

| Slice | Domain | Capabilities Introduced | Status |
|-------|--------|------------------------|--------|
| 1 | IT Help Desk | engine (case lifecycle), work (human task), connectors (chat inbound, notification outbound), demo profile, scenario basics | **this spec** |
| 2 | TBD | qhorus (agent channels, speech acts), multi-agent collaboration | planned |
| 3 | TBD | ledger (audit trail, trust scoring), AI routing | planned |
| 4 | TBD | IoT (device events), workers (automated dispatch) | planned |
| 5 | TBD | neocortex (CBR retrieval), memory, learning from history | planned |

Slices 2–5 are directional — domains and groupings will be refined as
slice 1 establishes the pattern. The goal is coverage, not a fixed
curriculum.

## Slice 1: IT Help Desk

### What it demonstrates

A customer sends a message. The system creates a support case, classifies
the issue, assigns it to a specialist, the specialist resolves it, and
the customer is notified. Five steps, universally understood.

### Platform capabilities exercised

| Capability | Module | How it's used |
|-----------|--------|---------------|
| Case lifecycle | casehub-engine | Ticket case: open → triage → assigned → resolved |
| Human task | casehub-work | WorkItem for the specialist to claim and resolve |
| Chat inbound | casehub-connectors (chat-spi) | Customer message triggers case creation |
| Notification outbound | casehub-connectors | Resolution notification to customer |
| Demo profile | casehub-platform-api | `@IfBuildProfile("demo")` gates all demo alternatives |
| Scenario execution | casehub-pages | ScenarioExecutor drives the demo end-to-end |

### Domain model

Minimal — just enough to be credible.

```
Ticket
  id: UUID
  subject: String
  description: String
  category: enum (HARDWARE, SOFTWARE, ACCESS, OTHER)
  priority: enum (LOW, MEDIUM, HIGH, URGENT)
  status: enum (OPEN, TRIAGED, ASSIGNED, RESOLVED, CLOSED)
  customerRef: String      -- external sender ID from inbound message
  assigneeId: String       -- specialist actor ID
  resolution: String       -- free text
  createdAt: Instant
  resolvedAt: Instant
```

No persistence — in-memory for the example. The case engine manages
lifecycle state; the ticket record is domain data attached to the case.

### Case definition

```
CaseDefinition: help-desk-ticket
  trigger: InboundMessage (connectorType = "chat")
  
  Stage 1 — Triage:
    TaskDefinition: classify-ticket
      type: automated (agent)
      input: message content
      output: category + priority
      SPI: TicketClassifier (demo impl returns lookup from scenario data)
  
  Stage 2 — Assignment:
    TaskDefinition: assign-specialist
      type: automated
      input: category
      output: assigneeId
      routing: by category (HARDWARE→hw-team, SOFTWARE→sw-team, etc.)
  
  Stage 3 — Resolution:
    TaskDefinition: resolve-ticket
      type: human (WorkItem)
      assignee: from stage 2
      completion: specialist submits resolution text
  
  Stage 4 — Notification:
    TaskDefinition: notify-customer
      type: automated
      input: resolution text + customerRef
      action: send message via chat/notification connector
```

### Demo alternatives (inside the example app)

| What | SPI | Demo impl |
|------|-----|-----------|
| Chat platform | `ChatPlatform` | In-memory `DemoChatPlatform` — `Messaging` native (records outbound for verification), all other sub-capabilities degraded. No pre-loaded message history — chat is event-driven, not data-driven. |
| Chat inbound | `InboundTranslator` | `DemoInboundTranslator` — translates `InboundMessage` to `ReceivedMessage` for connector type `"demo"` |
| Chat injection | (REST resource) | `ChatInjectionResource` at `/scenario/inject/chat` — receives POST from executor, fires `InboundMessage` CDI event through the standard `ChatInboundAdapter` pipeline |
| Ticket classifier | `TicketClassifier` (new, app-local) | Lookup from scenario data: message content → (category, priority). No LLM. |
| Notification sender | `Connector` or chat `Messaging` | Records outbound notifications in memory for verification |
| Current principal | `CurrentPrincipal` | Shared `DemoCurrentPrincipal` from platform (reads `X-Scenario-Actor` header) |

`DemoCurrentPrincipal` placement: must NOT go in `casehub-platform-api`
(zero-external-dependency boundary). Lives in `casehub-platform` or the
example app itself until a shared module is justified.

### Scenario file

```yaml
scenario: help-desk-basic
description: "Customer reports a hardware issue, specialist resolves it"
speed: 1
on-error: stop

data:
  ticket-classifications:
    - match: "laptop won't boot"
      category: HARDWARE
      priority: HIGH

steps:
  - name: customer-message
    action: customer-sends-message
    delivery: simulated
    target: chat
    data:
      from: "Alice Customer"
      channelId: "support"
      text: "My laptop won't boot after the update last night"

  - name: verify-case-created
    action: verify-ticket-exists
    delivery: rest
    endpoint: GET /tickets
    trigger: { after: customer-message, delay: 2000 }
    await:
      endpoint: GET /tickets
      match: { status: "TRIAGED", category: "HARDWARE" }

  - name: specialist-resolves
    action: specialist-resolves-ticket
    delivery: rest
    endpoint: PUT /tickets/{id}/resolve
    trigger: { after: verify-case-created }
    actor: hw-specialist
    data:
      resolution: "BIOS reset resolved the boot issue. No hardware damage."

  - name: verify-notification
    action: verify-customer-notified
    delivery: rest
    endpoint: GET /scenario/verify/notifications
    trigger: { after: specialist-resolves, delay: 1000 }
    await:
      endpoint: GET /scenario/verify/notifications
      match: { to: "Alice Customer", contains: "resolved" }
```

### Module structure

```
casehub-examples/
  helpdesk/
    pom.xml                    -- Quarkus app, depends on engine, work, chat-spi, platform-api
    src/main/java/
      io/casehub/examples/helpdesk/
        model/
          Ticket.java
          TicketCategory.java
          TicketPriority.java
          TicketStatus.java
        spi/
          TicketClassifier.java          -- SPI interface
        engine/
          HelpDeskCaseDefinition.java    -- case definition
        rest/
          TicketResource.java            -- REST API
        demo/                            -- @IfBuildProfile("demo") alternatives
          DemoChatPlatform.java
          DemoInboundTranslator.java
          DemoTicketClassifier.java       -- lookup from scenario data
          ScenarioBootstrapResource.java  -- POST /scenario/bootstrap/helpdesk
          ChatInjectionResource.java      -- POST /scenario/inject/chat
          VerificationResource.java       -- GET /scenario/verify/*
    src/main/resources/
      scenarios/
        help-desk-basic.yaml
    src/test/java/
      ...                               -- tests verify scenario produces expected state
```

### Implementation notes

**Chat → case bridge.** No existing platform bridge translates
`ReceivedMessage` CDI events into engine case creation. The example app
implements its own `@ObservesAsync ReceivedMessage` handler that creates
a help desk case. This is app-specific domain logic (not every chat
message should create a case — only in this app's context).

**DemoCurrentPrincipal.** Lives in the example app itself for now. One
class, trivially simple. Extract to a shared module only when a second
example app needs it.

### What this slice does NOT cover

- No persistence (in-memory only)
- No pages frontend / dashboard
- No SLA enforcement or escalation
- No multi-agent collaboration (single automated agent + single human)
- No trust scoring or audit trail
- No CBR / learning from past tickets

These are left for subsequent slices that build on this foundation.

## Issue Restructuring

### Close
- **#93** (Demo SPI alternatives — ChatPlatform + CalendarPlatform) — premise wrong; demo impls are demand-driven by example apps, not standalone deliverables

### Reassess
- **#94** (New SPIs — BankFeedPlatform + EmailPlatform) — valid SPI work but independent of the scenario engine epic; remove from #408 queue
- **#109** (Life household scenario files) — still valid but depends on a working scenario executor (#311) and may benefit from the example app pattern established here
- **#149** (Migrate DemoDataSeeder to scenario format) — still valid, app-specific

### Create
- New issue under #408: "Slice 1 — IT help desk example application with scenario file"
- Optionally: umbrella issue for the example application curriculum
