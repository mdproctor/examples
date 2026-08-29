# Capability-Driven Observation Filtering — Platform Pattern for Multi-Agent Perception

**Date:** 2026-08-28
**Status:** Approved
**Branch:** issue-39-capability-observation-filter
**Issue:** casehubio/examples#39
**Depends on:** casehubio/examples#48 (Observation SPI — complete), casehubio/examples#38 (Interaction chains — complete)

## Goal

Elevate the manor's hard-coded `observerTags` filtering into a composable,
platform-level observation pipeline in casehub-blocks. Providers emit
world state at full fidelity. A pipeline of filters applies visibility
gating, resolution control, and interpretive framing based on the
observer's declared capabilities. The manor becomes a thin application
layer; any agent application gets capability-driven perception for free.

**Verdict gate:** ManorWorldObservationProvider emits all sections
unconditionally with capability annotations. An ObservationPipeline
with VisibilityFilter produces the same output as today's `if
(tags.contains("perception"))` branching. ResolutionFilter demonstrates
tiered fallback. All existing ObservationBuilderTest assertions pass.

---

## 1. Three Composable Operations

The pipeline supports three independent operations that compose in order:

| Operation | What it does | Example |
|-----------|-------------|---------|
| **Visibility** | Removes sections the observer lacks capability to see | Compliance signals hidden from scheduling agents |
| **Resolution** | Substitutes a lower-detail rendering of the same information | "X carefully positioned Y" → "X is near Z's workspace" |
| **Interpretation** | Adds analytical framing sections for capable observers | "Pattern: 3 similar failures in 48 hours" added for analysis-capable agents |

Each operation is implemented as an `ObservationFilter` stage. Applications
compose their pipeline from the stages they need. The manor starts with
visibility only. Enterprise deployments add resolution and interpretation.

---

## 2. AnnotatedSection — Metadata Wrapper

`ObservationSection` stays untouched — a sealed interface with `EntityGroup`,
`TextBlock`, and `ItemList`. Filtering metadata lives in a separate wrapper:

```java
package io.casehub.blocks.summarisation.observation.affordance;

public record AnnotatedSection(
    ObservationSection section,
    Set<String> requiredTags,
    Map<ResolutionTier, ObservationSection> resolutions,
    String interpretiveFrame
) {
    public static AnnotatedSection requiring(ObservationSection section,
                                              Set<String> tags) {
        return new AnnotatedSection(section, tags, Map.of(), null);
    }

    public static AnnotatedSection withResolution(ObservationSection fullSection,
                                                   Set<String> tags,
                                                   ResolutionTier fallbackTier,
                                                   ObservationSection fallbackSection) {
        return new AnnotatedSection(fullSection, tags,
                Map.of(fallbackTier, fallbackSection), null);
    }
}
```

**Fields:**

- `section` — the default (full resolution) `ObservationSection`
- `requiredTags` — visibility gate: observer must have ALL these tags
  to see this section. Empty set = always visible (equivalent to bare
  section).
- `resolutions` — alternate renderings keyed by `ResolutionTier`. Empty
  map = no resolution fallback (section is removed if visibility fails).
  When the observer lacks requiredTags but resolutions has entries, the
  `ResolutionFilter` selects the appropriate fallback instead of removing.
- `interpretiveFrame` — hint for the `InterpretiveFilter`. Names the
  analytical frame this section participates in (e.g., `"analytical"`,
  `"tactical"`, `"social"`). Null = no interpretive framing.

### 2.1 ResolutionTier

```java
public enum ResolutionTier {
    FULL,
    REDUCED,
    SUMMARY
}
```

`FULL` is the default `section` field. `REDUCED` and `SUMMARY` are
progressively lower-detail alternates. Providers emit whichever tiers
they support. Most sections emit FULL only (no resolution scaling).

### 2.2 Backward Compatibility

Providers that don't use filtering continue to return bare
`ObservationSection` instances. The pipeline handles both:

- Bare `ObservationSection` → passes through all filters unchanged
- `AnnotatedSection` → filters apply, then unwrap to bare section

`AnnotatedSection implements ObservationSection` with `header()`
delegating to the wrapped section. This allows mixed lists —
`WorldObservationProvider.worldSections()` return type stays
`List<ObservationSection>` unchanged. Providers return a mix of bare
sections and `AnnotatedSection` in the same list. The renderer and
pipeline use `instanceof` to distinguish them.

---

## 3. ObservationFilter and Pipeline

### 3.1 Filter Interface

```java
package io.casehub.blocks.summarisation.observation.affordance;

@FunctionalInterface
public interface ObservationFilter {
    List<ObservationSection> filter(List<ObservationSection> sections,
                                     Set<String> observerTags);
}
```

Each filter receives the current section list and the observer's
capability tags. Returns a (possibly modified) section list. Filters
are pure functions — no side effects, no state between calls.

### 3.2 ObservationPipeline

```java
public class ObservationPipeline {

    private final List<ObservationFilter> stages;

    public ObservationPipeline(ObservationFilter... stages) {
        this.stages = List.of(stages);
    }

    public List<ObservationSection> apply(List<ObservationSection> sections,
                                           Set<String> observerTags) {
        var current = sections;
        for (var stage : stages) {
            current = stage.filter(current, observerTags);
        }
        return unwrap(current);
    }

    private List<ObservationSection> unwrap(List<ObservationSection> sections) {
        return sections.stream()
                .map(s -> s instanceof AnnotatedSection a ? a.section() : s)
                .toList();
    }
}
```

The pipeline runs stages in order, then unwraps any surviving
`AnnotatedSection` entries to bare `ObservationSection` for the
renderer. Ordering convention: visibility → resolution → interpretation.

### 3.3 Built-in Filters

#### VisibilityFilter

Removes `AnnotatedSection` entries whose `requiredTags` are not a
subset of the observer's tags. Bare sections and `AnnotatedSection`
with empty `requiredTags` pass through.

```java
public class VisibilityFilter implements ObservationFilter {
    @Override
    public List<ObservationSection> filter(List<ObservationSection> sections,
                                            Set<String> observerTags) {
        return sections.stream()
                .filter(s -> {
                    if (s instanceof AnnotatedSection a) {
                        return observerTags.containsAll(a.requiredTags());
                    }
                    return true;
                })
                .toList();
    }
}
```

#### ResolutionFilter

For `AnnotatedSection` entries whose `requiredTags` are not met but
have resolution alternatives, substitutes the best available
alternative. Runs AFTER VisibilityFilter in the recommended ordering
— VisibilityFilter removes sections with no fallback; ResolutionFilter
downgrades sections that have one.

```java
public class ResolutionFilter implements ObservationFilter {
    @Override
    public List<ObservationSection> filter(List<ObservationSection> sections,
                                            Set<String> observerTags) {
        return sections.stream()
                .map(s -> {
                    if (s instanceof AnnotatedSection a
                            && !observerTags.containsAll(a.requiredTags())
                            && !a.resolutions().isEmpty()) {
                        // Select best available fallback tier
                        for (var tier : ResolutionTier.values()) {
                            if (tier != ResolutionTier.FULL
                                    && a.resolutions().containsKey(tier)) {
                                return a.resolutions().get(tier);
                            }
                        }
                    }
                    return s;
                })
                .toList();
    }
}
```

**Interaction with VisibilityFilter:** When both filters are in the
pipeline, the recommended ordering is `VisibilityFilter` first,
`ResolutionFilter` second. VisibilityFilter removes sections with no
fallback. ResolutionFilter then only sees sections that either passed
visibility or have resolution alternatives. However, this means
VisibilityFilter must NOT remove sections that have resolution
alternatives — it should leave them for ResolutionFilter to downgrade.

The shipped implementation combines visibility and resolution into
one filter: `PerceptionFilter`. Single pass — tags met → keep full
section; tags not met + resolutions available → downgrade; tags not
met + no resolutions → remove. No ordering bugs between separate
filters. The pipeline composes at the stage level:
`PerceptionFilter` is one stage, application-provided
`InterpretiveFilter` implementations are others.

The separate `VisibilityFilter` and `ResolutionFilter` descriptions
above explain the conceptual operations. `PerceptionFilter` is the
concrete class that ships.

#### InterpretiveFilter

Adds or transforms sections based on the observer's analytical
capabilities. Unlike visibility and resolution (which reduce), this
filter can ADD sections — pattern detection, trend analysis, strategic
summaries.

This filter is application-provided, not built into blocks. Blocks
provides the `ObservationFilter` interface; each domain implements its
own interpretive logic. The `interpretiveFrame` field on
`AnnotatedSection` is a hint that interpretive filters can use to
identify sections they should transform.

---

## 4. Integration with ObservationBuilder

`ObservationBuilder` gains an optional `ObservationPipeline` parameter:

```java
public static String buildObservation(WorldObservationProvider worldProvider,
                                      ObservationPipeline pipeline,
                                      Set<String> observerTags,
                                      CharacterState character,
                                      List<AgentGoal> goals,
                                      PartitionedDrain<String> drain,
                                      List<Memory> memories,
                                      List<Memory> reflections,
                                      Map<String, List<Memory>> relationshipMemories) {
    var sections = new ArrayList<ObservationSection>();
    sections.addAll(worldProvider.worldSections());
    // ... character state, cognitive sections as before ...

    // Apply pipeline if present
    var filtered = pipeline != null
            ? pipeline.apply(sections, observerTags)
            : sections;

    return RENDERER.renderObservation(filtered);
}
```

Callers that don't use filtering pass `null` for pipeline and
`Set.of()` for tags — identical behavior to today.

---

## 5. Manor Migration

### 5.1 ManorWorldObservationProvider Changes

The provider becomes observer-agnostic. Instead of branching on
`observerTags`, it emits all sections unconditionally with annotations:

```java
@Override
public List<ObservationSection> worldSections() {
    var sections = new ArrayList<ObservationSection>();
    Room room = world.room(character.currentRoom());

    sections.add(locationSection(room));
    sections.add(exitsSection(room, world));
    sections.add(objectsSection(character, world));
    sections.add(charactersSection(character, world));

    if (!drain.rememberedPartitions().isEmpty()) {
        sections.add(rememberedSection(drain, world));
    }

    // Keen observations: full resolution for perception-capable,
    // directed dialogue as reduced fallback
    var keen = keenObservationsSection(character, world);
    var directed = directedDialogueSection(character, world);
    if (keen != null || directed != null) {
        sections.add(AnnotatedSection.withResolution(
                keen != null ? keen
                     : ObservationSection.items("Keen Observations", null, List.of()),
                Set.of("perception"),
                ResolutionTier.REDUCED,
                directed));
    }

    return sections;
}
```

The `observerTags` constructor parameter is removed. The provider no
longer knows or cares about the observer.

### 5.2 Caller Changes

```java
// ScenarioOrchestrator — before
var worldProvider = new ManorWorldObservationProvider(c, world, drain, c.capabilityTags());
String observation = ObservationBuilder.buildObservation(worldProvider, c, goals, ...);

// ScenarioOrchestrator — after
var worldProvider = new ManorWorldObservationProvider(c, world, drain);
var pipeline = new ObservationPipeline(new PerceptionFilter());
String observation = ObservationBuilder.buildObservation(
        worldProvider, pipeline, c.capabilityTags(), c, goals, ...);
```

The pipeline is constructed once and reused across ticks. The observer
tags come from the character's capabilities at call time.

---

## 6. Seed Capability Tag Vocabulary

Convention, not enforced by code. Documented in blocks javadoc:

| Tag | Operation | Meaning |
|-----|-----------|---------|
| `perception` | Resolution | Richer sensory detail about events |
| `analysis` | Interpretation | Pattern detection, trend identification |
| `social` | Resolution | Read social dynamics, hidden motivations |
| `deception` | Visibility | Detect deceptive behavior |
| `tactical` | Interpretation | Strategic assessment of situations |
| `domain:<name>` | Visibility | Domain-specific information (e.g., `domain:compliance`, `domain:medical`) |

Applications define additional tags. The vocabulary is open — any
string is a valid tag. The seed vocabulary establishes naming
conventions (lowercase, single-word or `prefix:name` for namespaced
tags).

---

## 7. Cross-Repo Work

### 7.1 casehub-blocks

**New types:**
- `AnnotatedSection` — record in `io.casehub.blocks.summarisation.observation.affordance`
- `ResolutionTier` — enum in same package
- `ObservationFilter` — `@FunctionalInterface` in same package
- `ObservationPipeline` — class in same package
- `PerceptionFilter` — built-in filter combining visibility + resolution

**Modified:**
- None — all existing types unchanged

### 7.2 casehub-examples/wacky-manor

**Modified:**
- `ManorWorldObservationProvider` — remove `observerTags` constructor param, emit annotated sections
- `ObservationBuilder` — add pipeline + observerTags parameters
- `ScenarioOrchestrator` — construct pipeline, pass tags separately
- `CharacterAgentLoop` — same pattern
- `ExchangeRunner` — pass null pipeline (no filtering in exchanges)
- `LiveScenarioTest`, `AutonomousScenarioRunner` — update call sites
- `ObservationBuilderTest` — update for new signature, add pipeline tests

---

## 8. Test Plan

### Unit Tests (in blocks)

| Test class | Coverage |
|-----------|----------|
| `AnnotatedSectionTest` | Construction, factory methods, empty requiredTags passthrough |
| `VisibilityFilterTest` | Tags met → keep, tags not met → remove, bare sections pass through, empty requiredTags pass through |
| `ResolutionFilterTest` | Tags not met + resolutions → downgrade to best tier, tags met → keep full, no resolutions → pass through |
| `PerceptionFilterTest` | Combined visibility + resolution: full/reduced/removed paths |
| `ObservationPipelineTest` | Multi-stage composition, unwrap after filtering, null pipeline passthrough |

### Unit Tests (in manor)

| Test class | Coverage |
|-----------|----------|
| `ManorWorldObservationProviderTest` | Emits annotated keen/directed section unconditionally, no observer tag branching |
| `ObservationBuilderTest` | Pipeline integration, perceptive observer sees keen, non-perceptive sees directed, all existing assertions pass |

### Integration Tests

Existing `AccumulatorScenarioTest`, `DialogueAsideRoutingTest` etc.
continue to pass — the pipeline is additive and existing behavior is
preserved through the PerceptionFilter.

---

## References

- `wacky-manor/src/main/java/io/casehub/examples/manor/agent/ManorWorldObservationProvider.java` — current observer-aware provider being refactored
- `wacky-manor/src/main/java/io/casehub/examples/manor/agent/ObservationBuilder.java` — assembly point gaining pipeline parameter
- `blocks/src/main/java/io/casehub/blocks/summarisation/observation/affordance/ObservationSection.java` — sealed interface staying unchanged
- `blocks/src/main/java/io/casehub/blocks/summarisation/observation/affordance/WorldObservationProvider.java` — provider SPI
- `eidos/api/src/main/java/io/casehub/eidos/api/AgentCapability.java` — capability tags source
- `docs/specs/issue-48-extract-observation-spi/2026-08-21-extract-observation-spi-design.md` — observation SPI foundation
- casehubio/examples#38 — interaction chains (validated the pattern)
- casehubio/examples#39 — this issue
