package io.casehub.examples.manor.web;

import io.casehub.eidos.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CharacterProfileDTOTest {

    @Test
    void projects_basic_fields() {
        var descriptor = AgentDescriptor.builder()
            .agentId("test-agent").name("Test Agent")
            .slotVocabulary("urn:casehub:vocab:belbin")
            .dispositionVocabulary("urn:casehub:vocab:jungian")
            .slot("shaper")
            .disposition(AgentDisposition.builder()
                .dispositionProfile(new DispositionValue("te", 0.35), new DispositionValue("ni", 0.2))
                .build())
            .tenancyId("test-tenancy").briefing("A test agent briefing.")
            .build();

        var dto = CharacterProfileDTO.from(descriptor, null, null);

        assertThat(dto.agentId()).isEqualTo("test-agent");
        assertThat(dto.name()).isEqualTo("Test Agent");
        assertThat(dto.slot()).isEqualTo("shaper");
        assertThat(dto.briefing()).isEqualTo("A test agent briefing.");
        assertThat(dto.dispositionProfile()).hasSize(2);
        assertThat(dto.dispositionProfile().getFirst().term()).isEqualTo("te");
    }

    @Test
    void filters_private_goals() {
        var goals = List.of(
            new AgentGoal("public-goal", "A public goal",
                GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of()),
            new AgentGoal("private-goal", "A private goal",
                GoalPriority.PRIMARY, Visibility.PRIVATE, List.of(), java.util.Map.of()));

        var descriptor = AgentDescriptor.builder()
            .agentId("test").name("Test").slot("shaper")
            .disposition(AgentDisposition.builder().build())
            .tenancyId("t").goals(goals).build();

        var dto = CharacterProfileDTO.from(descriptor, null, null);
        assertThat(dto.goals()).hasSize(1);
        assertThat(dto.goals().getFirst().name()).isEqualTo("public-goal");
    }

    @Test
    void filters_private_constraints() {
        var constraints = List.of(
            new AgentConstraint("public-c", "Public", Visibility.PUBLIC, ConstraintSeverity.HARD),
            new AgentConstraint("private-c", "Private", Visibility.PRIVATE, ConstraintSeverity.SOFT));

        var descriptor = AgentDescriptor.builder()
            .agentId("test").name("Test").slot("shaper")
            .disposition(AgentDisposition.builder().build())
            .tenancyId("t").constraints(constraints).build();

        var dto = CharacterProfileDTO.from(descriptor, null, null);
        assertThat(dto.constraints()).hasSize(1);
        assertThat(dto.constraints().getFirst().name()).isEqualTo("public-c");
    }
}
