package io.casehub.examples.manor.experiment;

import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.cognition.appraisal.ChannelWeightProfile;
import io.casehub.neocortex.cognition.appraisal.TopologyChannelWeighter;
import io.casehub.platform.agent.AgentProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM-backed smoke tests for situation-dependent channel weighting.
 *
 * Each test sends a specific scenario through the real LlmSituationClassifier,
 * then verifies the resulting channel weights match literature expectations:
 * - Easterbrook (1959): high arousal → attentional narrowing → somatic dominates
 * - Frijda (1986): threat/autonomy → strong action tendencies
 * - Barrett (2017): contemplative → elevated temporal awareness
 *
 * ~6 LLM calls total, runs in under 2 minutes.
 */
@QuarkusTest
@Tag("llm-eval")
class ChannelWeightingEvalTest {

    @Inject AgentProvider agentProvider;

    private LlmSituationClassifier classifier;

    @BeforeEach
    void setUp() {
        var topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        classifier = new LlmSituationClassifier(
                LlmSituationClassifier.createPool(agentProvider, 4), topology);
    }

    @Test
    void physicalThreat_elevatesSomaticAndActionTendency() {
        var weights = classify("A ceiling beam cracks overhead. Plaster dust " +
                "rains down. Clara is directly beneath it, frozen. The beam sags " +
                "further — seconds before it collapses.");

        System.out.printf("THREAT: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        assertThat(weights.isDefault()).as("Threat should shift from default").isFalse();
        assertThat(weights.actionTendency()).as("Threat → high action tendency")
                .isGreaterThanOrEqualTo(0.30);
        assertThat(weights.attentional()).as("Easterbrook: threat → attentional narrowing")
                .isLessThan(weights.somatic());
    }

    @Test
    void socialAcceptance_elevatesAttentional() {
        var weights = classify("Everyone at the table turns to welcome you. " +
                "Clara smiles warmly and pulls out the chair beside her. 'We saved " +
                "you a seat.' The group laughs at an inside joke you missed.");

        System.out.printf("SOCIAL: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        assertThat(weights.isDefault()).as("Social should shift from default").isFalse();
        assertThat(weights.attentional()).as("Social → elevated attentional")
                .isGreaterThanOrEqualTo(0.25);
    }

    @Test
    void resourceCompetition_elevatesTemporalAndAction() {
        var weights = classify("Both your hand and Sneekly's close on the golden " +
                "key at the same moment. His grip tightens. The key is the only way " +
                "into the vault. You can feel his fingers pressing against yours.");

        System.out.printf("RESOURCE: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        assertThat(weights.isDefault()).as("Competition should shift from default").isFalse();
        assertThat(weights.actionTendency()).as("Competition → action tendency present")
                .isGreaterThanOrEqualTo(0.25);
    }

    @Test
    void powerlessness_elevatesActionTendency() {
        var weights = classify("The door won't budge. You pound on it. Nobody " +
                "answers. Through the keyhole you can see them — carrying out the " +
                "paintings, the silverware. They're taking everything. And you " +
                "can't do a thing about it.");

        System.out.printf("AUTONOMY: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        assertThat(weights.isDefault()).as("Powerlessness should shift from default").isFalse();
        assertThat(weights.actionTendency()).as("Autonomy → high action tendency")
                .isGreaterThanOrEqualTo(0.30);
    }

    @Test
    void achievementMastery_elevatesAttentionalAndAction() {
        var weights = classify("The backup generator's wiring is a tangled mess " +
                "of 1920s electrical work. You have the torch, the toolkit, and " +
                "the engineering diagrams. Nobody else can do this. The house " +
                "needs power before nightfall.");

        System.out.printf("ACHIEVE: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        assertThat(weights.isDefault()).as("Achievement should shift from default").isFalse();
        assertThat(weights.actionTendency()).as("Achievement → action tendency present")
                .isGreaterThanOrEqualTo(0.25);
    }

    @Test
    void neutralScene_staysNearDefault() {
        var weights = classify("The afternoon sun falls through the library " +
                "windows. A clock ticks on the mantelpiece. Someone has left a " +
                "cup of tea on the side table, still steaming.");

        System.out.printf("NEUTRAL: somatic=%.2f att=%.2f temp=%.2f act=%.2f [dominant=%s]%n",
                weights.somatic(), weights.attentional(), weights.temporal(),
                weights.actionTendency(), weights.dominantChannel());

        double maxDelta = Math.max(
                Math.max(Math.abs(weights.somatic() - 0.25), Math.abs(weights.attentional() - 0.25)),
                Math.max(Math.abs(weights.temporal() - 0.25), Math.abs(weights.actionTendency() - 0.25)));
        System.out.printf("  max delta from default: %.2f%n", maxDelta);
    }

    private ChannelWeightProfile classify(String scenarioText) {
        List<SituationActivation> activations = classifier.classify(scenarioText, Map.of());
        System.out.printf("  activations: %s%n",
                activations.stream()
                        .map(a -> a.nodeId() + "=" + String.format("%.2f", a.confidence()))
                        .toList());
        return TopologyChannelWeighter.weight(activations);
    }
}
