# Decisions — Scenario Engine Example Applications

## D1: Example applications replace standalone demo SPI impls

**Choice:** Build progressive example applications in casehub-examples instead of standalone demo SPI implementations in casehub-connectors
**Alternatives:**
- Build DemoChatPlatform + DemoCalendarPlatform as separate connector modules — solves infrastructure but has no consumer
- Build demo impls first, example apps later — supply-driven, wrong ordering
**Rationale:** The demo-spi-convention.md describes a pattern, not a build list. Demo impls only have value when an application needs them. Example apps drive demand for demo impls, not the other way around.
**Trade-offs:** No reusable shared demo modules across apps initially — each app contains its own demo alternatives. If multiple apps need the same demo impl, extract to a shared module later.
**Exploration:** quick
**Status:** captured

## D2: First example application — IT help desk (thin slice)

**Choice:** IT help desk as the first example — message in, case created, triaged, assigned, resolved, notification out
**Alternatives:**
- Order processing — classic BPM but abstract, less engaging
- Expense approval — too simple, limited connector diversity
- Pet clinic — too narrow
**Rationale:** Universally understood domain. Natural connector diversity (chat inbound, notification outbound). Exercises core platform stack: engine, work, qhorus, connectors. Scenario writes itself.
**Trade-offs:** Help desk is a well-trodden example domain — risk of feeling generic. Offset by the scenario-driven demo angle.
**Exploration:** quick
**Status:** captured

## D3: Every LLM integration point must have an SPI boundary

**Choice:** All LLM calls must sit behind an SPI with CDI alternative selection. Scripted/lookup demo impl activated by @IfBuildProfile("demo") reads pre-defined responses from scenario data.
**Alternatives:**
- Hardwire LLM calls, skip demo mode for AI steps — breaks deterministic demo capability
- Mock at test level only, not at profile level — can't run demos without LLM
**Rationale:** Scenario files must define both inputs AND expected AI outputs. Demo mode runs fully self-contained (no LLM, no external services). Live mode uses real LLM and treats scenario expectations as verification assertions.
**Trade-offs:** Every AI integration point needs an interface + CDI wiring. Small overhead per integration point but enforces clean architecture.
**Exploration:** quick
**Depends on:** D1 (example apps drive which LLM integration points need the SPI boundary first)
**Status:** captured

## D4: Demo alternatives live inside the example app, not in connector repos

**Choice:** Each example app contains its own @Alternative @IfBuildProfile("demo") implementations for the connectors and LLM providers it uses
**Alternatives:**
- Shared demo modules in connector repos (chat-demo/, calendar-demo/) — premature extraction, no second consumer yet
- Shared demo module in casehub-examples — possible later once patterns stabilise
**Rationale:** Simplest thing that works. Demo alternatives are trivial (in-memory storage, scripted responses). Extract to shared modules only when multiple apps need the same impl.
**Trade-offs:** Some duplication if two example apps use the same connector. Acceptable at this stage — duplication is cheaper than premature abstraction.
**Exploration:** quick
**Depends on:** D1
**Status:** captured

## D5: Progressive capability coverage — each slice introduces new platform capabilities

**Choice:** Example set designed as a capability coverage matrix. Each slice introduces new platform capabilities while staying small enough to understand in isolation. Capabilities may be composed/batched across slices.
**Alternatives:**
- One large reference app covering everything — overwhelming, not progressive
- Strictly one capability per slice — too many trivial examples, artificial separation
**Rationale:** End users learn progressively. Full capability coverage also means full scenario tool coverage. Composing related capabilities per slice keeps the set manageable.
**Trade-offs:** Requires upfront thought about which capabilities group well. Curriculum may evolve as the platform evolves.
**Exploration:** quick
**Depends on:** D2 (first slice establishes the pattern)
**Status:** captured

## D6: Two run modes from same scenario file

**Choice:** Each scenario file drives both demo mode (fully scripted, no external dependencies) and live mode (real connectors + LLM, scenario as verification harness)
**Alternatives:**
- Separate demo scripts and test scripts — duplication, drift
- Demo-only scenarios with separate test suites — misses the dual-purpose insight
**Rationale:** "The demo that tests itself" — a single scenario file serves as both scripted demo and automated verification. Demo mode uses pre-defined responses; live mode compares real responses against expectations.
**Trade-offs:** Scenario format must support both expected-response definitions (for demo) and verification assertions (for live). Already covered by the scenario format spec (#409).
**Exploration:** quick
**Status:** captured

## D7: Close #93, restructure epic #408 queue

**Choice:** Close #93 (Demo SPI alternatives — ChatPlatform + CalendarPlatform) as premise was wrong. Create new issue(s) under epic #408 for example applications. Reassess #94 (BankFeed/Email SPIs) — valid but independent of scenario engine epic.
**Alternatives:**
- Redefine #93 in place — confusing git/issue history
- Keep #93 open with changed scope — misleading title
**Rationale:** The scope change is fundamental. A clean new issue communicates the actual work clearly.
**Trade-offs:** Orphaned #93 in issue history. Minor — close with explanation.
**Exploration:** quick
**Depends on:** D1
**Status:** captured

---

# Scenario Controller UI Decisions (#341)

## D14: REST endpoint for scenario outline data

**Choice:** Add `GET /scenario/outline` to `ScenarioControlResource` returning the parsed chapter/section/step hierarchy. Controller fetches once on scenario start.
**Alternatives:**
- Inline full outline in every `scenario:state` push wire message — bloats every state update with static data that only changes on new scenario start
- REST initial + push delta for position changes — more complex for marginal bandwidth savings on a local-network use case
**Rationale:** The outline tree is static per scenario run. A single REST fetch on start is simple, cacheable, and doesn't bloat real-time state updates.
**Trade-offs:** One extra REST call on scenario start. Negligible.
**Exploration:** quick
**Status:** captured

## D15: Push wire broadcast for real-time state updates

**Choice:** Add `scenario:state` topic broadcast to `ScenarioOrchestrator` via `EventBroadcaster`. Controller listens via `EventConnection.listen(['scenario:state'])`. Broadcast on every state change (step completion, pause, resume, speed change).
**Alternatives:**
- REST polling `GET /scenario/state` on an interval — laggy, wastes bandwidth, doesn't support multi-device sync
**Rationale:** Real-time state is required for the "presenter remote" use case (phone controlling laptop display). Multiple controllers must stay in sync. Push wire is the existing infrastructure for this — no new transport needed.
**Trade-offs:** Requires backend change to ScenarioOrchestrator — adding EventBroadcaster injection and broadcast calls after each state mutation.
**Sources:** ScenarioOrchestrator.java, EventBroadcaster.java, GE-20260818-c61c29 (topicSource adapter)
**Exploration:** quick
**Depends on:** D9 (push wire as universal executor transport)
**Status:** captured

## D16: Controller component lives in pages-aria package

**Choice:** `<scenario-controller>` and `<scenario-narrative>` live in `packages/pages-aria` alongside existing scenario code (parser, runner, scenario-handler).
**Alternatives:**
- New `pages-scenario-controller` package — clean boundary but adds a package for a single component
- `pages-ui-components` — contains general UI primitives (button, input); scenario controller is domain-specific, not a general primitive
**Rationale:** pages-aria already owns all browser-side scenario concerns. The controller is the UI counterpart of the scenario system. Keeping them together avoids cross-package imports for shared types (DispatchSequence, ScenarioState).
**Trade-offs:** pages-aria grows beyond pure ARIA concerns. Acceptable — the package name reflects its origin, not its ceiling.
**Exploration:** quick
**Status:** captured

## D17: Property injection for connection mode

**Choice:** Embedded mode: host passes an existing `EventConnection` via property. Remote mode: host passes a `baseUrl` string and the component creates its own connection internally.
**Alternatives:**
- Reactive controller — `ScenarioConnectionController` managing the EventConnection; more structured but adds indirection for a two-mode problem with one consumer
- Auto-detect — controller checks for pages-runtime context; implicit, harder to test, magical
**Rationale:** Clean API surface. The host decides the mode, the controller doesn't care. Testable — pass a mock connection in tests.
**Trade-offs:** Embedded hosts must pass the connection explicitly. Minor wiring cost.
**Sources:** GE-20260816-e89cda (composable Lit controllers — pattern considered but rejected for single-consumer case)
**Exploration:** quick
**Status:** captured

## D18: Separate <scenario-narrative> component

**Choice:** Narrative content rendering is a separate `<scenario-narrative>` LitElement, not part of `<scenario-controller>`.
**Alternatives:**
- Integrated in controller — single component with outline + transport + narrative. Simpler for the common case but locks narrative display to the controller's layout.
**Rationale:** Narrative and transport controls serve different audiences. A presenter remote (phone) needs transport controls but not narrative. The main display might show narrative without transport. Separate components let hosts position them independently. Both listen to the same `scenario:state` push wire topic.
**Trade-offs:** Two components to wire instead of one. Offset by layout flexibility.
**Exploration:** quick
**Status:** captured

## D19: Standalone remote page served by Quarkus backend

**Choice:** A static HTML file served at `/scenario/remote` by the Java backend (via `META-INF/resources`). Loads only the `<scenario-controller>` component. No webapp build dependency.
**Alternatives:**
- Part of the webapp webpack bundle — couples the remote to the main app build
- In examples gallery — semantically wrong; the remote is operational tooling, not an example
**Rationale:** The remote page is a single HTML file loading a web component. No build step beyond the component itself. Available wherever the backend runs.
**Trade-offs:** The component's JS bundle must be independently loadable — needs a standalone entry point or ESM import.
**Exploration:** quick
**Depends on:** D16 (component in pages-aria)
**Status:** captured

## D20: Single LitElement architecture (not composable controllers)

**Choice:** `<scenario-controller>` is one self-contained LitElement. Internal state managed by reactive properties updated from push wire events. REST commands sent by internal methods. No controller extraction.
**Alternatives:**
- Composable reactive controllers (per GE-20260816-e89cda) — ScenarioStateController + ScenarioCommandController composed by host. More structured but over-engineered when there's only one host consuming the controllers.
**Rationale:** One component, one purpose. Extract controllers later if a second consumer appears. The simplest thing that works.
**Trade-offs:** If another host needs the same state management, we'd refactor to controllers. YAGNI applies — no second consumer exists.
**Sources:** GE-20260816-e89cda (composable Lit controllers pattern — evaluated, deferred)
**Exploration:** quick
**Status:** captured

---

# Distributed Executor Protocol Decisions (#418)

## D8: Orchestrator dispatches ordered step sequences to executors

**Choice:** The orchestrator groups consecutive steps bound for the same executor and dispatches them as an ordered sequence. The executor runs them in order, reports per-step progress. The orchestrator sends control messages (pause/resume/speed/step) that affect execution pace within the sequence.
**Alternatives:**
- Single-step RPC — orchestrator sends one step at a time, waits for result, decides next. Simpler but chatty (4 helpdesk steps = 4 round-trips). All sequencing in orchestrator.
- Sub-scenario with triggers — executor manages its own trigger graph and sequencing. Most autonomous but complex. The protocol should be composable enough that this can be added later (hierarchy of orchestrators) without redesigning the core.
- Progressive (start with RPC, add fragments later) — lower upfront complexity but risks protocol redesign.
**Rationale:** Ordered sequences reduce round-trips for co-located work (e.g., helpdesk: inject → verify → resolve → verify as one dispatch). Executors have enough autonomy to manage internal pacing without the complexity of local trigger evaluation. The orchestrator remains authoritative for the trigger graph and inter-executor coordination.
**Trade-offs:** Executors need lifecycle state (idle → running → paused → complete) and per-step progress reporting. More complex than single-step RPC. But the protocol should be composable — same message types at every level — so hierarchy can be layered on later.
**Sources:** ScenarioExecutor.java (current sequential executor), AriaDispatcher.java (existing single-command push protocol), scenario-handler.ts (browser executor), cross-platform scenario engine design spec §5
**Exploration:** quick
**Status:** captured

## D9: Push wire WebSocket as universal executor transport

**Choice:** All executors (browser and service) connect to Pages' existing `/ws/push` WebSocket endpoint and communicate via the push wire protocol. Service executors connect as WebSocket clients. Executor-specific topics (e.g., `scenario:helpdesk:dispatch`, `scenario:helpdesk:control`) route messages. Reuses EventBroadcaster, TopicRegistry, reconnection, and topic matching.
**Alternatives:**
- Dedicated `/ws/scenario` WebSocket endpoint — cleaner separation but duplicates connection management, topic routing, and reconnection logic
- HTTP-based (REST dispatch + callback) — no persistent connection, simpler deployment, but no real-time control messages. Stepping/pause would need polling — incompatible with interactive demo experience
**Rationale:** The push wire protocol already provides bidirectional WebSocket communication with topic routing, event persistence (EventStore), reconnection, and wildcard matching. The browser executor (scenario-handler.ts) already uses it. Extending to service executors is a natural evolution — same protocol, same infrastructure, same message patterns.
**Trade-offs:** Service executors become WebSocket clients of Pages. This inverts the typical server→service direction. Acceptable for the scenario use case — the orchestrator (Pages) is the coordinator, executors register by connecting. Services need the push client library as a dependency.
**Sources:** AriaDispatcher.java (existing push wire usage), scenario-handler.ts (browser executor using push wire), EventBroadcaster.java, PushRequest.java (CommandResult already exists), GE-20260818-78bf96 (push wire subscribe vs listen protocol distinction)
**Exploration:** quick
**Depends on:** D8
**Status:** captured

## D10: New op types in PushMessage/PushRequest sealed interfaces

**Choice:** Add new op types to the PushMessage and PushRequest sealed interfaces for scenario protocol messages: `dispatch-sequence`, `executor-control`, `step-result`, `executor-register`. Type-safe, IDE completion, exhaustive pattern matching in switch expressions.
**Alternatives:**
- Topic-based payloads using existing `event` op — avoids sealed interface changes but loses type safety. Scenario messages are untyped JSON payloads, no compiler help.
- Single `scenario` envelope op — one sealed interface change, scenario protocol self-contained inside. Partial type safety but still needs internal discriminated union parsing.
**Rationale:** Pre-release platform — breaking changes cost nothing. The right design is type-safe protocol messages with exhaustive matching. Adding ops to the sealed interface is the cleanest way to get that. Every consumer that switches on `PushRequest` will get a compile error if they don't handle the new variants — that's a feature, not a cost.
**Trade-offs:** Changes the push protocol's sealed interface. All existing PushRequest switch expressions must add cases. Pre-release, this is free.
**Sources:** PushRequest.java (existing sealed interface with Subscribe, Unsubscribe, Listen, Unlisten, CommandResult), PushMessage.java
**Exploration:** quick
**Depends on:** D9
**Status:** captured

## D11: CDI annotation-driven action handlers for executor contract

**Choice:** Service executors register action handlers via `@ScenarioAction("action-name")` annotations on CDI beans. A shared executor library dispatches incoming steps to matching handlers, manages lifecycle and control, and reports results back to the orchestrator.
**Alternatives:**
- Executor interface — service implements `ScenarioExecutor.execute(Step)` and dispatches internally via switch. More explicit, but puts action routing in every service instead of the shared library.
- CDI event bridging — incoming steps fired as `@ObservesAsync` CDI events. Natural CDI integration but control messages (pause/resume/speed) don't map to the CDI event model. Lifecycle management becomes implicit.
**Rationale:** Mirrors the CDI `@Observes` pattern that CaseHub developers already know. The shared library handles protocol, lifecycle, and control. Services only implement domain-specific action methods. Minimal boilerplate per service.
**Trade-offs:** Annotation scanning at startup. Action name is a string — typos compile but fail at dispatch time. Mitigated by startup validation (executor library checks all registered actions against the scenario before starting).
**Exploration:** quick
**Depends on:** D8, D9
**Status:** captured

## D12: Nested YAML hierarchy — chapters → sections → steps → commands

**Choice:** Scenario files use a nested structure: chapters contain sections, sections contain steps, steps contain commands. A "step" is the demo-meaningful unit (e.g., "fill out the support form") — all commands within a step execute without pausing. Labels on chapters, sections, and steps serve as navigation points for "run to" and stepping.
**Alternatives:**
- Flat steps with label annotations — steps remain a flat list, each annotated with chapter/section. More flexible for concurrent triggers but denormalizes labels (repeated on every step). Doesn't match how a presenter thinks about a demo script.
**Rationale:** The nested structure matches the demo narrative: chapters are major topic transitions, sections are sub-narratives, steps are individual demo moments. The presenter thinks in terms of "run to chapter 2" or "step through this section." The hierarchy is the natural authoring model.
**Trade-offs:** The trigger graph from the cross-platform design spec (concurrent steps connected by triggers) operates within/across sections, not across the flat list. Triggers reference steps by name within their section scope, or by fully-qualified path for cross-section references. This is more structured than the flat model but enforces the narrative order.
**Sources:** Cross-platform scenario engine design spec §3 (current flat step format), §5.2 (speed control modes)
**Exploration:** quick
**Depends on:** D8
**Status:** captured

## D13: Separable controller with REST/GraphQL/MCP API and push-wire state broadcast

**Choice:** The orchestrator exposes a controller API (REST, GraphQL, MCP tools) for scenario control: start, pause, resume, step, runTo(label), nextSection, nextChapter, speed. A `<scenario-controller>` UI connects to this API and receives real-time state updates via push wire (`scenario:state` topic). The controller UI can be embedded in the demo app OR run on a separate device (phone controlling a laptop display).
**Alternatives:**
- Embedded-only controller — simpler but can't be used from a separate device. Limits the demo operator to the same screen as the audience.
- Direct WebSocket commands only — no REST/GraphQL API. Controller UI must speak push wire protocol. Excludes programmatic control from CI, MCP agents, or simple curl commands.
**Rationale:** Demos are presented — the operator needs a "presenter remote" that works from any device with a browser. REST/GraphQL gives programmatic access (CI verification, MCP agent control). Push wire delivers real-time position updates to all connected controllers.
**Trade-offs:** Three API surfaces (REST, GraphQL, MCP) to maintain. But the implementation is thin — all delegate to the same orchestrator methods. The MCP tools reuse the existing casehub-pages-mcp module pattern.
**Exploration:** quick
**Depends on:** D12
**Status:** captured

---

# Scenario Controller Display Modes (#347)

## D21: Compact mode layout — pill + expand

**Choice:** Collapsed pill showing play/pause icon, scenario name, progress %. Click expands to full outline + transport controls in a floating card.
**Alternatives:**
- Always-visible transport — larger footprint, outline behind toggle. Better for active stepping but takes more screen space.
- Mini controller bar — horizontal bar anchored to bottom. No outline access in compact mode.
**Rationale:** Minimal footprint when not actively stepping. The overlay should be unobtrusive during self-running demos but provide full control when needed. The remote.html standalone page already covers the full-control use case.
**Trade-offs:** Requires a click to access transport controls.
**Sources:** PagesScenarioController render structure (scenario-controller.ts:134-236)
**Exploration:** quick
**Status:** captured

## D22: Scope — compact overlay only, defer bar/detached

**Choice:** Implement only `compact` mode for #347. Bar and detached modes deferred to a follow-up issue.
**Alternatives:**
- All modes in one pass — more complete but delays visible results.
**Rationale:** Get the overlay working in the helpdesk UI now. Future modes can be added to the same `mode` property without API changes.
**Trade-offs:** Only two modes (`full`, `compact`) initially.
**Exploration:** quick
**Depends on:** D21
**Status:** captured

---

# YAML Fly-Out Viewer Decisions (#349)

## D23: Include detach support

**Choice:** Include detach — YAML viewer pops out into a separate window with its own push wire connection
**Alternatives:**
- Defer detach — simpler, but removes a key demo transparency feature
- Panel only, no card integration — standalone component but no in-page toggle from controller
**Rationale:** The YAML viewer as a standalone component with its own push wire connection makes detach nearly free — `window.open()` with a minimal page that loads the same component. The architecture for standalone viewing IS the architecture for detach.
**Trade-offs:** Cross-window lifecycle management (close detection, reconnect on window focus)
**Sources:** casehubio/casehub-pages#349, scenario-connection-controller.ts (existing push wire pattern)
**Exploration:** quick
**Status:** captured

## D24: yaml CST for highlighting and position tracking

**Choice:** yaml package CST (Concrete Syntax Tree) parsing — already a dependency, zero additions
**Alternatives:**
- Prism.js (~16KB) — battle-tested highlighting but needs separate position-tracking logic and adds a new dependency
- Custom YAML tokenizer — minimal footprint but reinvents what the yaml package already provides
**Rationale:** The yaml package's `parseDocument()` returns AST nodes with `.range` properties (start offset, value end, node end). A single parse gives both syntax tokens for highlighting AND source position mapping for step tracking. Zero new dependencies.
**Trade-offs:** Highlighting fidelity limited to what we tokenize from the AST. Acceptable for structurally simple scenario YAML.
**Sources:** yaml package docs, packages/pages-aria/package.json (existing dependency)
**Exploration:** quick
**Status:** captured

## D25: Second independent floating element for UI layout

**Choice:** Separate draggable floating YAML viewer alongside the controller
**Alternatives:**
- Tab within the card — Outline|Source tabs in the existing 280px card, compact but cramped for reading YAML
- Expand the card — card widens to ~600px for side-by-side, single element but large
**Rationale:** The controller is a floating overlay, not a panel. Attaching a panel to it would look inconsistent. A second floating element is natural — both float independently, both are draggable. The viewer IS the standalone component whether floating on-page or in its own window. Consistent with the detach architecture (D23).
**Trade-offs:** Two floating elements can overlap. Needs sensible initial positioning (viewer to the left of the controller).
**Depends on:** D23 (detach support determines the component must be standalone)
**Sources:** scenario-controller.ts (existing compact floating overlay pattern, drag implementation)
**Exploration:** quick
**Status:** captured

---

# Visual Feedback Decisions (#351)

## D26: Separate visual-feedback module

**Choice:** New `visual-feedback.ts` module with `highlightElement()`, `typeText()`, `injectStyles()`. scenario-handler.ts calls it before/after each command. Executor stays pure.
**Alternatives:**
- In command-executor.ts — couples visual concerns into the pure ARIA executor
- Inline in scenario-handler.ts — makes the handler larger, harder to test feedback independently
**Rationale:** The executor resolves targets and dispatches DOM events. Visual feedback is a presentation concern orthogonal to execution. A separate module can be tested independently (does the CSS inject? does typing animate?) without wiring up the full scenario handler.
**Trade-offs:** scenario-handler gains a dependency on visual-feedback. Minor — both live in the same package.
**Sources:** command-executor.ts (pure executor pattern), scenario-handler.ts (orchestration layer)
**Exploration:** quick
**Status:** captured

## D27: Typing animation is scenario-only

**Choice:** `typeText()` in visual-feedback module as an async alternative to `fill()`. scenario-handler uses `typeText()` during execution. The executor's `fill()` stays instant.
**Alternatives:**
- Always animate — replace fill() with async progressive version. Simpler but makes all ARIA fills slow, including tests.
**Rationale:** Tests and programmatic use need instant fill. Only demo scenarios benefit from the typing animation. Keeping fill() synchronous preserves the executor's simplicity and test performance.
**Trade-offs:** scenario-handler must know when to use typeText vs fill — but it already knows it's running a scenario, so this is natural.
**Depends on:** D26 (visual-feedback module owns typeText)
**Sources:** command-executor.ts:fill (current synchronous implementation)
**Exploration:** quick
**Status:** captured
