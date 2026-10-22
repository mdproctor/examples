package io.casehub.examples.manor.agent;

public record ManorConfig(
        int maxTurns,
        ObservationConfig observation,
        NarratorConfig narrator,
        ReflectionConfig reflection,
        GoalConfig goal,
        PlanConfig plan,
        TrustConfig trust,
        DispositionConfig disposition,
        MemoryConfig memory,
        ConsolidationConfig consolidation,
        CognitiveSnapshotConfig cognitiveSnapshot,
        AppraisalConfig appraisal,
        ClassificationConfig classification,
        String activeCharacters,
        int maxConcurrentAgents
) {
    public record ObservationConfig(int verbatimThreshold, int groupedThreshold) {}

    public record NarratorConfig(boolean enabled, int eventThreshold, int timerSeconds) {}

    public record ReflectionConfig(boolean enabled, int maxUnreflected, double importanceThreshold,
                                   int maxSourceMemories) {}

    public record GoalConfig(boolean enabled, int cooldownTicks, int maxNewPerReflection) {}

    public record PlanConfig(boolean enabled, int maxRevisionGeneration) {}

    public record TrustConfig(boolean enabled, double positiveWeight, double negativeWeight) {}

    public record DispositionConfig(boolean enabled, int evolutionCheckInterval) {}

    public record MemoryConfig(int recallLimit, boolean personalityWeightedRetrieval, boolean decayEnabled,
                               int decayMaxAgeDays, double decayMinImportance) {}

    public record ConsolidationConfig(boolean enabled, int intervalTicks) {}

    public record CognitiveSnapshotConfig(int intervalTicks) {}

    public record AppraisalConfig(boolean enabled) {}

    public record ClassificationConfig(boolean auditEnabled, String auditLogPath, String externalCharacters,
                                        double accumulatorAllowance, double accumulatorThreshold,
                                        double accumulatorDecay, double accumulatorSlowAllowance,
                                        double accumulatorSlowThreshold) {
        public boolean isExternal(String agentId) {
            if (externalCharacters == null || externalCharacters.isBlank()) return false;
            return java.util.Arrays.stream(externalCharacters.split(","))
                    .map(String::strip)
                    .anyMatch(agentId::equals);
        }

        public io.casehub.neocortex.caps.engine.DegreeAccumulatorConfig accumulatorConfig() {
            return new io.casehub.neocortex.caps.engine.DegreeAccumulatorConfig(
                    accumulatorAllowance, accumulatorThreshold, accumulatorDecay,
                    accumulatorSlowAllowance, accumulatorSlowThreshold);
        }
    }
}
