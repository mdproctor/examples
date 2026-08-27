# Distributed Executor Protocol — Orchestrator ↔ Executor Communication with Stepping

> **Issue:** casehubio/parent#418
> **Epic:** casehubio/parent#408 — Cross-Platform Scenario Engine
> **Date:** 2026-08-20
> **Status:** Draft

## 1. Overview

The scenario engine needs a protocol for communication between the central
orchestrator (Pages Java server) and executors (browser, backend services).
Today's `AriaDispatcher` sends individual commands to the browser via push
wire and waits for `CommandResult` — a single-command RPC pattern with no
stepping, no speed control, and no support for service executors.

This spec defines:
1. A **scenario format** with optional hierarchical labels (chapters →
   sections → steps → commands) for demo navigation
2. A **dispatch protocol** with new PushRequest/PushMessage op types for
   sequence dispatch, control, and result reporting
3. A **controller API** (REST/GraphQL/MCP) with a separable UI for
   operating demos from any device
4. A **shared executor library** with CDI annotation-driven action handlers
   for services

### 1.1 Relationship to cross-platform scenario engine design spec

The cross-platform spec (casehub-life, 2026-08-11) defined the original
vision: Pages as central orchestrator making HTTP calls to services, with
`delivery` modes (rest, ui-form, simulated), `endpoint` fields, and a
`ControlChannel` for frontend synchronisation.

This spec **evolves** that architecture based on what was learned during
implementation:

| Cross-platform spec | This spec | Why |
|---------------------|-----------|-----|
| HTTP delivery modes (rest, simulated) | Executor dispatch via push wire | Eliminates HTTP round-trips, enables stepping at the executor level, local CDI event observation |
| `ControlChannel` (ScenarioHost/Remote) | `dispatch-sequence` + `executor-control` | Same purpose, unified with the executor protocol. ControlChannel is superseded. |
| Flat step list with `delivery`/`endpoint` | Hierarchical chapters/sections/steps/commands with `target` | Demo navigation (run-to, stepping at chapter/section level) requires hierarchy |
| Orchestrator makes all calls | Services embed executor library | Executor can observe local CDI events directly instead of polling REST |

The `GraphQLDispatcher` is retained as a fallback for services without a
local executor (§8.3), preserving the HTTP delivery path for incremental
adoption. The cross-platform spec's `data` section, `actor` field, and
trigger semantics are preserved.

## 2. Architecture

### 2.1 Three-layer model

| Layer | Sees | Concern |
|-------|------|---------|
| Scenario format | chapters → sections → steps → commands | Authoring |
| Dispatch protocol | sequences → steps → commands | Execution |
| Controller API | chapters/sections/steps as navigation points | Presentation |

The orchestrator translates between all three: it reads the scenario format,
dispatches sequences to executors via the protocol, and exposes navigation
via the controller API.

### 2.2 Orchestrator and executors

The **Java server** (Pages backend) is the orchestrator in hybrid mode
(server + browser). It owns the trigger graph, step scheduling, speed
control, and inter-executor coordination.

The **TypeScript runtime** can orchestrate in browser-only mode (no server).
The protocol semantics are the same; the transport is local (no WebSocket).

**Executors** are targets that receive step sequences and run them:
- **Browser executor** — enhanced `scenario-handler.ts`, handles ARIA
  commands (click, fill, navigate, assert, wait)
- **Service executors** — backend services (e.g., helpdesk) that embed a
  shared executor library and provide `@ScenarioAction` handlers

A hierarchy of orchestrators is possible — an executor can internally act
as an orchestrator for sub-executors — but this spec focuses on the
single-orchestrator model and designs the protocol to be composable for
future hierarchy.

### 2.3 Transport

All executors communicate with the orchestrator via the existing push wire
protocol (WebSocket at `/ws/push`). Browser executors already use this
channel. Service executors connect as WebSocket clients.

The push wire provides bidirectional communication, topic-based routing
(via `TopicRegistry`), event persistence (via `EventStore`), and
reconnection handling.

## 3. Scenario Format

### 3.1 Hierarchy (all levels optional)

```yaml
# Minimal — no hierarchy, just steps
scenario: quick-seed
steps:
  - label: "Seed tickets"
    target: helpdesk
    commands:
      - {action: create-ticket, data: {subject: "Test", category: HARDWARE}}

# Sections — mid-level structure
scenario: helpdesk-walkthrough
sections:
  - label: "Customer submits"
    steps:
      - label: "Fill support form"
        target: browser
        commands:
          - {action: navigate, value: "#support"}
          - {action: fill, target: {role: textbox, name: Subject}, value: "Laptop won't boot"}
          - {action: click, target: {role: button, name: Submit}}
      - label: "System classifies"
        target: helpdesk
        commands:
          - {action: verify-ticket-exists, await: {match: {status: TRIAGED}}}
  - label: "Specialist resolves"
    steps:
      - label: "Resolve ticket"
        target: helpdesk
        commands:
          - {action: resolve-ticket, data: {resolution: "BIOS reset fixed it"}}

# Full hierarchy for presentation demos
scenario: help-desk-demo
description: "IT help desk — customer to resolution"
speed: 1
on-error: pause
chapters:
  - label: "Customer Reports Issue"
    sections:
      - label: "Customer sends message"
        steps:
          - label: "Submit support request"
            target: browser
            commands:
              - {action: navigate, value: "#support"}
              - {action: fill, target: {role: textbox, name: Subject}, value: "Laptop won't boot"}
              - {action: fill, target: {role: textbox, name: Description}, value: "After the update"}
              - {action: click, target: {role: button, name: Submit}}
          - label: "System creates and classifies ticket"
            target: helpdesk
            commands:
              - {action: verify-ticket-exists, await: {match: {status: TRIAGED, category: HARDWARE}}}
      - label: "Specialist resolves"
        steps:
          - label: "Claim and resolve ticket"
            target: helpdesk
            actor: hw-specialist
            commands:
              - {action: resolve-ticket, data: {resolution: "BIOS reset resolved the boot issue"}}
          - label: "Customer notified"
            target: helpdesk
            commands:
              - {action: verify-notification, await: {match: {to: "Alice Customer", contains: "resolved"}}}
  - label: "Reporting"
    sections:
      - label: "Dashboard reflects resolution"
        steps:
          - label: "Ticket appears as resolved"
            target: browser
            commands:
              - {action: navigate, value: "#dashboard"}
              - {action: wait, target: {role: row, name: "Laptop won't boot"}, state: {aria-label: "Resolved"}}
```

### 3.2 Format rules

| Level | Required | Contains | Navigation |
|-------|----------|----------|------------|
| `scenario` | Always | `chapters`, `sections`, or `steps` (mutually exclusive at top level) | — |
| `chapters` | Optional | `sections` | "Run to chapter" |
| `sections` | Optional | `steps` | "Run to section" |
| `steps` | Always (leaf) | `commands` | "Step" / "Run to step" |
| `commands` | Always (within step) | Action primitives | Execute without pausing |

When `chapters` is present, `sections` and `steps` must be nested inside.
When `sections` is present at top level, `steps` must be nested inside
sections. When `steps` is at top level, no hierarchy — just a flat list of
steps.

Labels must be unique within their level: no two chapters with the same
label, no two sections with the same label within a chapter, no two steps
with the same label within a section. The `runTo` command matches the
first occurrence if labels are ambiguous across levels.

### 3.3 Step schema

```yaml
- name: submit-ticket          # optional, for trigger references
  label: "Submit support form" # required, human-readable
  target: browser              # which executor (required)
  actor: demo-admin            # optional, identity for X-Scenario-Actor
  trigger:                     # optional, when to start this step
    after: verify-classification
    delay: 2000
  commands:                    # one or more commands
    - action: navigate
      value: "#support"
    - action: fill
      target: {role: textbox, name: Subject}
      value: "Laptop won't boot"
    - action: click
      target: {role: button, name: Submit}
```

### 3.4 Command types

Commands are the atomic execution units. They map to executor-specific
actions via `@ScenarioAction` handlers (services) or built-in ARIA
operations (browser).

| Field | Required | Description |
|-------|----------|-------------|
| `action` | Always | Action name — dispatched to matching handler |
| `target` | Per action | ARIA target (browser) or domain target |
| `value` | Per action | String value (fill text, navigation URL) |
| `data` | Per action | Structured payload (domain data) |
| `domain` | Per action | Target service for GraphQL dispatch |
| `await` | Optional | Completion condition — poll until matched |
| `timeout` | Optional | Override default timeout (ms) |

### 3.5 Triggers

Triggers gate step start. They reference other steps by name.

| Trigger | Fires when |
|---------|-----------|
| `{after: "step-name"}` | Named step completes |
| `{after: "step-name", delay: N}` | Named step completes + N ms |
| `{at: N}` | N ms into scenario |
| `{when: {endpoint: "...", match: {...}, poll: 500}}` | Polled condition met |

Steps without triggers fire when their containing section starts (or
immediately if no sections). Within a section, untriggered steps execute
in declaration order. Cross-section triggers use the step's `name` field.

### 3.6 Data section

The top-level `data` section declares external data files, same as the
cross-platform design spec (§3.4):

```yaml
data:
  ticket-classifications:
    - match: "laptop won't boot"
      category: HARDWARE
      priority: HIGH
```

Commands reference data inline or via the top-level `data` map.

## 4. Dispatch Protocol

### 4.1 New PushRequest/PushMessage types

Four new variants in the sealed interfaces:

**PushRequest (client → server):**

```java
record ExecutorRegister(String id, String name,
                        List<String> actions) implements PushRequest {
    public String op() { return "executor-register"; }
}

record StepResult(String id, String sessionId, String stepName,
                  boolean ok, String error,
                  Map<String, Object> result) implements PushRequest {
    public String op() { return "step-result"; }
}
```

**PushMessage (server → client):**

```java
// dispatch-sequence — sends ordered steps to an executor
static String dispatchSequence(String sessionId, String executorId,
                               String stepsJson, double speed,
                               boolean paused)

// executor-control — pause/resume/step/speed
static String executorControl(String sessionId, String command,
                              Double speed)
```

### 4.2 Message flow

```
Executor                          Orchestrator
   |                                    |
   |--- executor-register ------------->|  "I'm helpdesk, I handle [create-ticket, ...]"
   |                                    |
   |<-- dispatch-sequence --------------|  steps: [{name, label, commands}], speed, paused
   |                                    |
   |    [execute step 1 commands]       |
   |--- step-result ------------------->|  stepName, ok, result
   |                                    |
   |    [check control, delay by speed] |
   |                                    |
   |<-- executor-control ---------------|  command: "pause"
   |                                    |
   |    [paused — waiting]              |
   |                                    |
   |<-- executor-control ---------------|  command: "step"
   |                                    |
   |    [execute step 2 commands]       |
   |--- step-result ------------------->|  stepName, ok, result
   |                                    |
   |    [paused after step command]     |
   |                                    |
   |<-- executor-control ---------------|  command: "resume"
   |                                    |
   |    [execute step 3 commands]       |
   |--- step-result ------------------->|  stepName, ok, result (last step)
   |                                    |
```

### 4.3 Dispatch-sequence format

```json
{
  "op": "dispatch-sequence",
  "sessionId": "s-001",
  "executorId": "helpdesk",
  "steps": [
    {
      "name": "create-ticket",
      "label": "System creates ticket",
      "actor": "demo-admin",
      "commands": [
        {"action": "create-ticket", "data": {"subject": "Laptop won't boot", "category": "HARDWARE"}}
      ]
    },
    {
      "name": "verify-classification",
      "label": "Verify ticket classified",
      "commands": [
        {"action": "verify-ticket-exists", "await": {"match": {"status": "TRIAGED"}}}
      ]
    }
  ],
  "speed": 1.0,
  "paused": false
}
```

The executor receives this and:
1. Starts executing if `paused` is false
2. Runs all commands in step 1 without pausing
3. Sends `step-result` for step 1
4. Waits `(1000 / speed)` ms between steps. Speed must be > 0 (use
   `pause` control for zero-speed). Executor clamps to minimum 0.01.
5. Runs step 2, sends result
6. Sequence complete

### 4.4 Executor-control format

```json
{"op": "executor-control", "sessionId": "s-001", "command": "pause"}
{"op": "executor-control", "sessionId": "s-001", "command": "resume"}
{"op": "executor-control", "sessionId": "s-001", "command": "step"}
{"op": "executor-control", "sessionId": "s-001", "command": "speed", "speed": 2.0}
```

| Command | Executor behaviour |
|---------|-------------------|
| `pause` | Complete current step's commands, then pause |
| `resume` | Continue executing remaining steps at current speed |
| `step` | Execute one step (all commands), then pause |
| `speed` | Adjust delay between steps. Must be > 0. Use `pause` for zero |

### 4.5 Step-result format

```json
{
  "op": "step-result",
  "id": "msg-123",
  "sessionId": "s-001",
  "stepName": "create-ticket",
  "ok": true,
  "result": {"ticketId": "T-001", "status": "TRIAGED"}
}
```

The orchestrator tracks sequence progress by matching `stepName` against
the dispatched sequence. No `remaining` counter — the orchestrator already
knows the sequence contents.

### 4.6 Executor-register format

```json
{
  "op": "executor-register",
  "id": "msg-001",
  "name": "helpdesk",
  "actions": ["create-ticket", "resolve-ticket", "verify-ticket-exists", "verify-notification"]
}
```

The orchestrator validates that all `target` values in the scenario match
the `name` field of registered executors. If a required executor is not
connected, the orchestrator waits with timeout before starting.

### 4.7 Inter-executor coordination

The orchestrator manages all cross-executor dependencies via the trigger
graph. When executor A completes a step that triggers steps for executor B,
the orchestrator dispatches a new sequence to B.

No direct executor-to-executor communication.

```
helpdesk executor                 orchestrator              browser executor
       |                              |                           |
       |-- step-result: create-ticket->|                           |
       |                              |  [trigger fires:           |
       |                              |   browser step ready]      |
       |                              |-- dispatch-sequence ------>|
       |                              |                           |
```

### 4.8 Session lifecycle

A session is created when the controller API receives `POST /scenario/start`.
The orchestrator generates a `sessionId` (UUID) and includes it in all
protocol messages. One session at a time per orchestrator.

| Event | Behaviour |
|-------|-----------|
| `POST /scenario/start` | Create session, parse scenario, wait for executors, dispatch first sequences |
| All steps complete | Session ends. Orchestrator broadcasts final state, clears executor queues |
| `POST /scenario/stop` | Session aborts. Orchestrator sends `executor-control: stop` to all executors. Executors discard remaining steps. |
| Orchestrator restart | Session is lost. Executors detect WebSocket disconnect, discard session state, reconnect and re-register |
| Executor reconnect | Executor re-registers. If the session is still active and the executor has pending steps, the orchestrator re-dispatches them |

An executor handles one session at a time. Receiving a `dispatch-sequence`
with a different `sessionId` implicitly ends the previous session's work
for that executor.

### 4.9 Sequence dispatch rules

The orchestrator translates the trigger graph into sequences:

1. **Untriggered steps** in the same section targeting the same executor
   are dispatched as one sequence when the section starts
2. **Triggered steps** are dispatched individually when their trigger fires
3. If multiple triggers fire simultaneously for the same executor, the
   steps are grouped into one sequence
4. An executor can receive a new `dispatch-sequence` while a previous one
   is running. The new sequence is **appended** — the executor completes
   the current sequence first, then starts the new one
5. `executor-control` affects all sequences (current and queued)

This means the orchestrator dispatches eagerly: as soon as a step's
trigger fires, it dispatches. The executor queues sequences and processes
them in order.

### 4.10 Error handling

| Error | Behaviour |
|-------|-----------|
| Command fails within step | Step result `ok: false` with error message. Remaining commands in step skipped. |
| Await timeout | Step result `ok: false`, error: "await timed out". |
| Executor disconnects | Orchestrator marks all pending steps for that executor as failed. Reconnecting executor re-registers and can receive new sequences. |
| Step failure propagation | Orchestrator applies scenario-level `on-error` policy: `continue` (skip dependents transitively — if A fails and C depends on A, C is skipped; if D depends on C, D is also skipped), `stop` (abort all executors), or `pause` (all executors pause, operator intervention). |

## 5. Controller API

### 5.1 REST endpoints

```
POST /scenario/start              body: {name: "help-desk-demo"}
POST /scenario/stop
POST /scenario/pause
POST /scenario/resume
POST /scenario/step               advance one step
POST /scenario/run-to             body: {label: "Specialist resolves"}
POST /scenario/speed              body: {speed: 2.0}
GET  /scenario/state              → {scenario, chapter, section, step, paused, speed, progress}
GET  /scenario/outline            → chapter/section/step tree with labels
```

### 5.2 GraphQL

```graphql
type Mutation {
    scenarioStart(name: String!): ScenarioState
    scenarioStop: ScenarioState
    scenarioPause: ScenarioState
    scenarioResume: ScenarioState
    scenarioStep: ScenarioState
    scenarioRunTo(label: String!): ScenarioState
    scenarioSpeed(speed: Float!): ScenarioState
}

type Query {
    scenarioState: ScenarioState
    scenarioOutline: ScenarioOutline
}

type ScenarioState {
    scenario: String
    chapter: String
    section: String
    step: String
    paused: Boolean!
    speed: Float!
    progress: Float!
}
```

### 5.3 MCP tools

```
scenario_start(name)     — start a scenario
scenario_pause()         — pause
scenario_resume()        — resume
scenario_step()          — advance one step
scenario_run_to(label)   — fast-forward to label
scenario_speed(n)        — set speed
scenario_state()         — current state
scenario_outline()       — chapter/section/step tree
```

MCP tools enable AI agents (Claude via MCP) to control scenarios
programmatically — for automated verification, demo scripting, or
interactive Q&A where an agent steps through a demo on demand.

### 5.4 State broadcast

The orchestrator broadcasts state changes on the `scenario:state` push
wire topic:

```json
{
  "op": "event",
  "topic": "scenario:state",
  "payload": {
    "scenario": "help-desk-demo",
    "chapter": "Customer Reports Issue",
    "section": "Customer sends message",
    "step": "Submit support request",
    "paused": false,
    "speed": 1.0,
    "progress": 0.33
  }
}
```

Any number of controller UIs can listen on `scenario:state` for real-time
updates. This is how a phone controller stays in sync with the laptop
display.

### 5.5 "Run to" semantics

`runTo(label)` searches the scenario's hierarchy for the first matching
label (checking chapter labels, then section labels, then step labels).
All steps before the target execute at maximum speed with no pausing.
When the target is reached, the orchestrator pauses.

If the label is behind the current position, the API returns an error
response with status `already-past` and the current position. Scenarios
are not idempotent — no rewinding. Unknown labels return `not-found`.

### 5.6 Stepping semantics

| Control | What happens |
|---------|-------------|
| `step` | Execute the next step (all its commands). Pause after. |
| `runTo(chapter)` | Fast-forward to the first step of the chapter |
| `runTo(section)` | Fast-forward to the first step of the section |
| `runTo(step)` | Fast-forward to the named step |
| `resume` | Continue at current speed |
| `speed(n)` | Adjust delay between steps. Commands within a step always execute at full speed |

Speed affects the delay between steps, not within them. A form fill
(multiple ARIA commands) always runs at full speed — the operator sees it
happen, then the demo pauses at the next step boundary.

## 6. Shared Executor Library

### 6.1 Module

New module: `casehub-pages-scenario-client` in the pages backend.

Dependencies:
- `casehub-pages-push` (WebSocket client, push wire protocol)
- `casehub-pages-scenario` (ScenarioStep model)
- Quarkus Arc (CDI)
- Jackson (JSON)

### 6.2 CDI action handlers

Services register action handlers:

```java
@ApplicationScoped
public class HelpDeskScenarioActions {

    @Inject TicketService ticketService;
    @Inject TicketClassifier classifier;

    @ScenarioAction("create-ticket")
    Map<String, Object> createTicket(ActionContext ctx) {
        var ticket = ticketService.create(
            ctx.data("subject"), ctx.data("category"));
        return Map.of("ticketId", ticket.id().toString());
    }

    @ScenarioAction("resolve-ticket")
    void resolveTicket(ActionContext ctx) {
        ticketService.resolve(
            ctx.data("ticketId"), ctx.data("resolution"), ctx.actor());
    }

    @ScenarioAction("verify-ticket-exists")
    Map<String, Object> verifyTicketExists(ActionContext ctx) {
        var ticket = ticketService.findByStatus(ctx.awaitMatch("status"));
        return Map.of("ticketId", ticket.id().toString(),
                       "status", ticket.status().name(),
                       "category", ticket.category().name());
    }
}
```

### 6.3 ActionContext

```java
public interface ActionContext {
    String actor();
    String data(String key);
    <T> T data(String key, Class<T> type);
    Map<String, Object> dataMap();
    String awaitMatch(String key);
    Map<String, Object> awaitMatchMap();
}
```

### 6.4 ScenarioExecutorClient (shared library core)

```java
@ApplicationScoped
public class ScenarioExecutorClient {

    @Inject EventBroadcaster broadcaster;

    // Connects to orchestrator's /ws/push as a WebSocket client
    // Sends executor-register with discovered @ScenarioAction methods
    // Receives dispatch-sequence, runs steps via action handlers
    // Sends step-result after each step
    // Responds to executor-control (pause/resume/step/speed)
}
```

At startup:
1. Scans CDI beans for `@ScenarioAction` annotations
2. Connects to the orchestrator's push WebSocket endpoint
3. Sends `executor-register` with name and action list
4. Waits for `dispatch-sequence` messages

### 6.5 Service configuration

```properties
casehub.scenario.executor.name=helpdesk
casehub.scenario.orchestrator.url=ws://localhost:8080/ws/push
casehub.scenario.executor.enabled=true
```

### 6.6 EventConnection evolution

The current `EventConnection` in `pages-data` only handles `op: "ack"`,
`op: "error"`, and `op: "event"` messages. Any other op is silently
dropped (see GE-20260812-5cd146). The new protocol types
(`dispatch-sequence`, `executor-control`) must be handled.

`EventConnection.handleMessage()` must be extended with cases for the new
ops. Each new op dispatches a typed `CustomEvent` on the `eventTarget`:

```typescript
case 'dispatch-sequence':
  eventTarget.dispatchEvent(new CustomEvent('scenario-dispatch', { detail: msg }));
  break;
case 'executor-control':
  eventTarget.dispatchEvent(new CustomEvent('scenario-control', { detail: msg }));
  break;
```

This is a pre-release breaking change to EventConnection's contract —
acceptable per platform maturity stage.

### 6.7 Browser executor evolution

`scenario-handler.ts` evolves to handle the new protocol:

```typescript
interface ScenarioCommand {
  action: string;
  target?: AriaTarget;
  value?: string;
  data?: Record<string, unknown>;
  await?: AwaitCondition;
  timeout?: number;
}

interface DispatchSequence {
  op: 'dispatch-sequence';
  sessionId: string;
  steps: Array<{
    name: string;
    label: string;
    commands: ScenarioCommand[];
  }>;
  speed: number;
  paused: boolean;
}

interface ExecutorControl {
  op: 'executor-control';
  sessionId: string;
  command: 'pause' | 'resume' | 'step' | 'speed';
  speed?: number;
}
```

The existing ARIA actions (click, fill, navigate, assert, wait) become the
browser executor's built-in action set. The handler manages an internal
step queue, responds to control messages, and sends `step-result` per step.

## 7. Controller UI

### 7.1 `<scenario-controller>` web component

A Lit web component that provides the demo operator interface:

- **Outline panel** — chapter/section/step tree, current position
  highlighted. Click any label to "run to" it.
- **Transport controls** — play/pause/step/next-section/next-chapter
  buttons. Speed slider.
- **Status bar** — current chapter → section → step label, progress
  percentage, connection status.

### 7.2 Connection modes

| Mode | How it connects | Use case |
|------|----------------|----------|
| Embedded | Direct reference to orchestrator instance (same page) | Controller inside the demo app |
| Remote | REST API + push wire WebSocket to orchestrator URL | Controller on separate device (phone, tablet) |

In remote mode, the controller connects to:
- REST: `http://{orchestrator}/scenario/*` for commands
- Push wire: `ws://{orchestrator}/ws/push`, listens on `scenario:state`

### 7.3 Standalone page

A minimal HTML page at `/scenario/remote` that loads only the
`<scenario-controller>` component. Open this URL on a phone to get a
presenter remote for a demo running on a laptop.

## 8. Relationship to Existing Code

### 8.1 What changes

| Component | Current | After |
|-----------|---------|-------|
| `ScenarioExecutor` (Java) | Sequential step loop, direct dispatcher calls | Trigger graph, sequence dispatch to executors via protocol |
| `AriaDispatcher` | Single-command push wire, `CommandPayload` | Replaced by dispatch-sequence to browser executor |
| `GraphQLDispatcher` | Direct HTTP POST | Replaced by dispatch-sequence to service executors (or retained for services without local executors) |
| `scenario-handler.ts` | Single-command handler | Sequence handler with step queue and control |
| `PushRequest` | 5 variants (subscribe, unsubscribe, listen, unlisten, command-result) | 7 variants (+executor-register, +step-result) |
| `PushMessage` | event, ack, error | +dispatch-sequence, +executor-control |
| `ScenarioStep` (sealed) | AriaStep, GraphQLStep, SimulatedStep | Evolves to match the chapter/section/step/command hierarchy |

### 8.2 What stays

- Push wire protocol infrastructure (EventBroadcaster, TopicRegistry,
  EventStore, SessionSender, WebSocket endpoint)
- ARIA command execution logic (click, fill, navigate, assert, wait)
- AwaitEngine (polling with match predicates)
- VariableContext (step result interpolation)
- ScenarioParser (extended for new format)

### 8.3 GraphQLDispatcher retention

Services without a local executor (no `@ScenarioAction` handlers) can
still be targeted via the existing GraphQL HTTP dispatch. The orchestrator
falls back to `GraphQLDispatcher` for steps whose `target` has no
registered executor. This enables incremental adoption — not every service
needs a local executor immediately.

## 9. Non-Goals

1. **Scenario recording** — recording live sessions as scenario files is
   out of scope (future phase per cross-platform design spec §10).
2. **Nested orchestrators** — the protocol is composable (same message
   types at every level) but implementing hierarchy is deferred. This spec
   covers single-orchestrator mode.
3. **Scenario editor UI** — authoring/editing scenario files visually is
   not covered. Scenarios are YAML files.
4. **Multi-scenario concurrent execution** — one scenario at a time per
   orchestrator.

## References

- `ScenarioExecutor.java` — current sequential executor
- `AriaDispatcher.java` — existing single-command push wire dispatch
- `GraphQLDispatcher.java` — existing HTTP POST dispatch
- `scenario-handler.ts` — browser executor receiving push wire commands
- `PushRequest.java` — sealed push wire request interface
- `PushMessage.java` — push wire server→client message builder
- `EventBroadcaster.java` — push wire broadcast infrastructure
- Cross-platform scenario engine design spec (casehub-life)
- GE-20260818-78bf96 — push wire subscribe vs listen protocol distinction
- GE-20260818-c61c29 — topicSource adapter bridging push protocol to DataSource
- GE-20260812-5cd146 — EventConnection drops non-event wire messages
