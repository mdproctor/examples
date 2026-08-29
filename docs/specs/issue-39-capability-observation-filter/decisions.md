# Decisions — #39 Capability-Driven Observation Filtering

## D1: Filtering operations scope

**Choice:** Three composable operations — resolution control (same event, different detail), visibility gating (sections hidden for agents lacking capabilities), and interpretive framing (same data, different analytical lens). All three live in blocks as pipeline operations.
**Alternatives:**
- Resolution only — matches the manor's current pattern but too narrow for enterprise/simulation use cases
- Resolution + visibility only — misses the interpretive framing that simulation and adversarial scenarios need
**Rationale:** A universal observation system must handle all three. Enterprise agents need visibility gating (compliance vs scheduling). Simulations need resolution (information asymmetry). Adversarial and analytical agents need interpretive framing. The manor becomes a thin application layer.
**Trade-offs:** More complex pipeline design. Acceptable — the operations compose cleanly and each can be used independently.
**Exploration:** quick
**Status:** captured

## D2: Filtering model — metadata on sections

**Choice:** Sections carry metadata (required tags, resolution tier). The provider emits all sections at maximum resolution, observer-agnostic. A pipeline filter strips or downgrades based on the observer's capabilities. The provider never branches on observer tags.
**Alternatives:**
- Pre-section filtering (provider receives observer capabilities and makes filtering decisions internally) — what the manor does today. Forces every provider to implement filtering logic, couples provider to observer knowledge, prevents composable filtering.
**Rationale:** Observer-agnostic providers describe the world at full fidelity. Filtering is a separate, composable concern in the pipeline. ManorWorldObservationProvider would emit keen observations unconditionally with `requires: ["perception"]` — the pipeline strips them for non-perceptive observers. Clean separation of "what exists" from "who can see it."
**Trade-offs:** ObservationSection gains annotation surface. Providers must learn to annotate sections. Existing providers work unchanged (unannotated sections pass through all filters).
**Depends on:** D1 (three operations — the metadata model must support all three)
**Exploration:** quick
**Status:** captured

## D3: Metadata carrier — wrapper record

**Choice:** `AnnotatedSection` wrapper record that pairs any `ObservationSection` with filtering metadata (required tags, resolution tier, interpretive frame). Providers that don't filter return bare sections. Providers that do return `AnnotatedSection`. The pipeline unwraps after filtering. The `ObservationSection` sealed interface stays untouched.
**Alternatives:**
- Extend the sealed interface with metadata fields — changes the pattern-match surface that `AffordanceRenderer` uses, forces every existing section construction site to update, couples rendering to filtering concerns
**Rationale:** Additive. Existing code constructing bare `ObservationSection` works unchanged. The pipeline handles both bare sections (pass-through) and annotated sections (apply filters). The renderer only sees unwrapped sections — metadata is a pipeline concern, not a rendering concern.
**Trade-offs:** Two types in the pipeline (`ObservationSection` and `AnnotatedSection`) — callers must choose which to emit. Acceptable — the default (bare section) is the simple path.
**Depends on:** D2 (metadata on sections model)
**Exploration:** quick
**Status:** captured

## D4: Pipeline composition — ordered filter stages

**Choice:** `ObservationPipeline` as an ordered list of `ObservationFilter` stages. Each filter receives sections (mix of bare `ObservationSection` and `AnnotatedSection`) + observer capability tags, returns a filtered/transformed list. Three built-in filters ship with blocks: `VisibilityFilter` (remove sections whose requiredTags aren't met), `ResolutionFilter` (downgrade resolution tiers based on missing capabilities), `InterpretiveFilter` (add analytical framing sections when matching capabilities are present). Pipeline unwraps `AnnotatedSection` → bare `ObservationSection` after all stages run.
**Alternatives:**
- Single monolithic filter interface — simpler but doesn't compose; applications needing visibility + interpretation write one combined filter. Not independently testable.
**Rationale:** Composability. The manor uses `VisibilityFilter` alone. An enterprise deployment adds `InterpretiveFilter`. Each filter is testable in isolation. Custom filters slot in at any position. The pipeline is the composition mechanism — applications declare which stages they need.
**Trade-offs:** Pipeline ordering matters — visibility should run before interpretation (no point interpreting sections the observer can't see). Convention or documentation, not enforced by the type system.
**Depends on:** D3 (AnnotatedSection wrapper — filters operate on the wrapper)
**Exploration:** quick
**Status:** captured

## D5: Resolution — alternate sections on the annotation

**Choice:** `AnnotatedSection` carries a `Map<ResolutionTier, ObservationSection>` of pre-computed alternate renderings. The provider emits all tiers it supports. `ResolutionFilter` selects the appropriate tier based on observer capabilities, falling back to the lowest available. The default section (full resolution) is the `section` field; alternates are in the map.
**Alternatives:**
- Resolution callback (`Function<ResolutionTier, ObservationSection>`) — more flexible but harder to serialise, test, and inspect. Deferred computation adds complexity with no clear benefit when the number of tiers is small.
**Rationale:** Data-oriented. Alternate sections are concrete, inspectable, testable. Most sections have one tier (full resolution, no alternates — empty map). The manor's keen/directed split becomes: keen at FULL, directed dialogue at REDUCED, both pre-computed by the provider.
**Trade-offs:** Provider pre-computes all tiers upfront — wasted work if observer always has the capability. Acceptable at POC scale; lazy computation can be added later if profiling justifies it.
**Depends on:** D3 (AnnotatedSection wrapper), D4 (ResolutionFilter stage)
**Exploration:** quick
**Status:** captured
