package io.casehub.examples.manor.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ManorConfigProducer {

    @ConfigProperty(name = "manor.scenario.max-turns", defaultValue = "300")
    int maxTurns;
    @ConfigProperty(name = "manor.observation.verbatim-threshold", defaultValue = "10")
    int verbatimThreshold;
    @ConfigProperty(name = "manor.observation.grouped-threshold", defaultValue = "15")
    int groupedThreshold;
    @ConfigProperty(name = "manor.narrator.enabled", defaultValue = "true")
    boolean narratorEnabled;
    @ConfigProperty(name = "manor.narrator.event-threshold", defaultValue = "5")
    int narratorEventThreshold;
    @ConfigProperty(name = "manor.narrator.timer-seconds", defaultValue = "15")
    int narratorTimerSeconds;
    @ConfigProperty(name = "manor.scenario.active-characters", defaultValue = "")
    java.util.Optional<String> activeCharactersConfig;
    @ConfigProperty(name = "manor.agent.max-concurrent", defaultValue = "5")
    int maxConcurrentAgents;
    @ConfigProperty(name = "manor.reflection.enabled", defaultValue = "true")
    boolean reflectionEnabled;
    @ConfigProperty(name = "manor.reflection.max-unreflected", defaultValue = "5")
    int maxUnreflected;
    @ConfigProperty(name = "manor.reflection.importance-threshold", defaultValue = "3.0")
    double reflectionImportanceThreshold;
    @ConfigProperty(name = "manor.reflection.max-source-memories", defaultValue = "15")
    int maxSourceMemories;
    @ConfigProperty(name = "manor.decay.enabled", defaultValue = "true")
    boolean decayEnabled;
    @ConfigProperty(name = "manor.decay.max-age-days", defaultValue = "7")
    int decayMaxAgeDays;
    @ConfigProperty(name = "manor.decay.min-importance", defaultValue = "0.2")
    double decayMinImportance;
    @ConfigProperty(name = "manor.memory.recall-limit", defaultValue = "20")
    int recallLimit;
    @ConfigProperty(name = "manor.goal.enabled", defaultValue = "true")
    boolean goalEnabled;
    @ConfigProperty(name = "manor.goal.cooldown-ticks", defaultValue = "10")
    int goalCooldownTicks;
    @ConfigProperty(name = "manor.goal.max-new-per-reflection", defaultValue = "2")
    int goalMaxNewPerReflection;
    @ConfigProperty(name = "manor.plan.enabled", defaultValue = "true")
    boolean planEnabled;
    @ConfigProperty(name = "manor.plan.revision.max-generation", defaultValue = "5")
    int planMaxRevisionGeneration;
    @ConfigProperty(name = "manor.disposition.enabled", defaultValue = "true")
    boolean dispositionEnabled;
    @ConfigProperty(name = "manor.disposition.evolution-check-interval", defaultValue = "5")
    int dispositionEvolutionCheckInterval;
    @ConfigProperty(name = "manor.trust.enabled", defaultValue = "true")
    boolean trustEnabled;
    @ConfigProperty(name = "manor.trust.positive-weight", defaultValue = "1.0")
    double trustPositiveWeight;
    @ConfigProperty(name = "manor.trust.negative-weight", defaultValue = "-2.0")
    double trustNegativeWeight;
    @ConfigProperty(name = "manor.personality.weighted-retrieval", defaultValue = "true")
    boolean personalityWeightedRetrieval;
    @ConfigProperty(name = "manor.consolidation.enabled", defaultValue = "true")
    boolean consolidationEnabled;
    @ConfigProperty(name = "manor.consolidation.interval-ticks", defaultValue = "50")
    int     consolidationIntervalTicks;
    @ConfigProperty(name = "manor.cognitive.snapshot-interval-ticks", defaultValue = "0")
    int     cognitiveSnapshotIntervalTicks;
    @ConfigProperty(name = "manor.appraisal.enabled", defaultValue = "false")
    boolean appraisalEnabled;
    @ConfigProperty(name = "manor.mood.decay-minutes", defaultValue = "240")
    int moodDecayMinutes;
    @ConfigProperty(name = "manor.mood.max-displacement", defaultValue = "1.0")
    double moodMaxDisplacement;

    @ConfigProperty(name = "manor.classification.audit-enabled", defaultValue = "true")
    boolean classificationAuditEnabled;
    @ConfigProperty(name = "manor.classification.audit-log-path", defaultValue = "docs/eval/classification-audit.jsonl")
    String classificationAuditLogPath;
    @ConfigProperty(name = "manor.classification.external-characters")
    java.util.Optional<String> classificationExternalCharacters;
    @ConfigProperty(name = "manor.classification.accumulator.allowance", defaultValue = "0.2")
    double accumulatorAllowance;
    @ConfigProperty(name = "manor.classification.accumulator.threshold", defaultValue = "1.0")
    double accumulatorThreshold;
    @ConfigProperty(name = "manor.classification.accumulator.decay", defaultValue = "0.85")
    double accumulatorDecay;
    @ConfigProperty(name = "manor.classification.accumulator.slow-allowance", defaultValue = "0.15")
    double accumulatorSlowAllowance;
    @ConfigProperty(name = "manor.classification.accumulator.slow-threshold", defaultValue = "2.0")
    double accumulatorSlowThreshold;

    @Produces
    @jakarta.inject.Singleton
    public ManorConfig produce() {
        return new ManorConfig(
                maxTurns,
                new ManorConfig.ObservationConfig(verbatimThreshold, groupedThreshold),
                new ManorConfig.NarratorConfig(narratorEnabled, narratorEventThreshold, narratorTimerSeconds),
                new ManorConfig.ReflectionConfig(reflectionEnabled, maxUnreflected, reflectionImportanceThreshold, maxSourceMemories),
                new ManorConfig.GoalConfig(goalEnabled, goalCooldownTicks, goalMaxNewPerReflection),
                new ManorConfig.PlanConfig(planEnabled, planMaxRevisionGeneration),
                new ManorConfig.TrustConfig(trustEnabled, trustPositiveWeight, trustNegativeWeight),
                new ManorConfig.DispositionConfig(dispositionEnabled, dispositionEvolutionCheckInterval),
                new ManorConfig.MemoryConfig(recallLimit, personalityWeightedRetrieval, decayEnabled, decayMaxAgeDays, decayMinImportance),
                new ManorConfig.ConsolidationConfig(consolidationEnabled, consolidationIntervalTicks),
                new ManorConfig.CognitiveSnapshotConfig(cognitiveSnapshotIntervalTicks),
                new ManorConfig.AppraisalConfig(appraisalEnabled),
                new ManorConfig.ClassificationConfig(classificationAuditEnabled, classificationAuditLogPath, classificationExternalCharacters.orElse(""),
                        accumulatorAllowance, accumulatorThreshold, accumulatorDecay, accumulatorSlowAllowance, accumulatorSlowThreshold),
                new ManorConfig.MoodTuningConfig(moodDecayMinutes, moodMaxDisplacement),
                activeCharactersConfig.orElse(""),
                maxConcurrentAgents
        );
    }
}
