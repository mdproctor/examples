package io.casehub.examples.manor.agent;

import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ManorGoalEvaluatorTest {

    private ManorGoalEvaluator evaluator;
    private AtomicReference<AgentDescriptor> registeredDescriptor;
    private GoalFormationProposal nextProposal;

    @BeforeEach
    void setUp() {
        registeredDescriptor = new AtomicReference<>();
        nextProposal = new GoalFormationProposal(List.of(
                new GoalFormationProposal.ProposedGoal("protect-tea",
                        "Prevent poisoning", GoalPriority.SECONDARY, "Danger observed", java.util.Map.of())),
                "rationale");

        GoalFormationStrategy formationStrategy = ctx -> nextProposal;

        var descriptor = AgentDescriptor.builder()
                .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                .slot("manor-character")
                .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                        GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                .build();

        AgentRegistry registry = new TestRegistry(descriptor);

        CaseMemoryStore memoryStore = new TestMemoryStore();

        evaluator = new ManorGoalEvaluator(formationStrategy, null,
                registry, memoryStore, "wacky-manor", 10, 2);
    }

    @Test
    void evaluate_registers_new_goals_on_descriptor() {
        evaluator.evaluate("hc", 1, List.of("danger observed"), Map.of());
        assertThat(registeredDescriptor.get()).isNotNull();
        assertThat(registeredDescriptor.get().goals()).hasSize(2);
        assertThat(registeredDescriptor.get().goals().stream()
                .map(AgentGoal::name).toList())
                .containsExactlyInAnyOrder("eliminate", "protect-tea");
    }

    @Test
    void evaluate_sets_private_visibility_on_formed_goals() {
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        var formedGoal = registeredDescriptor.get().goals().stream()
                .filter(g -> g.name().equals("protect-tea")).findFirst().orElseThrow();
        assertThat(formedGoal.visibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void evaluate_defaults_priority_to_secondary() {
        nextProposal = new GoalFormationProposal(List.of(
                new GoalFormationProposal.ProposedGoal("new-goal",
                        "A goal", null, "reason", java.util.Map.of())), "");
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        var formedGoal = registeredDescriptor.get().goals().stream()
                .filter(g -> g.name().equals("new-goal")).findFirst().orElseThrow();
        assertThat(formedGoal.priority()).isEqualTo(GoalPriority.SECONDARY);
    }

    @Test
    void evaluate_rejects_duplicate_goal_names() {
        nextProposal = new GoalFormationProposal(List.of(
                new GoalFormationProposal.ProposedGoal("eliminate",
                        "Duplicate", GoalPriority.SECONDARY, "reason", java.util.Map.of())), "");
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get()).isNull();
    }

    @Test
    void evaluate_respects_capacity_cap() {
        var manyGoals = new ArrayList<GoalFormationProposal.ProposedGoal>();
        for (int i = 0; i < 15; i++) {
            manyGoals.add(new GoalFormationProposal.ProposedGoal(
                    "goal-" + i, "G" + i, GoalPriority.SECONDARY, "reason", java.util.Map.of()));
        }
        nextProposal = new GoalFormationProposal(manyGoals, "");
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get().goals().size()).isLessThanOrEqualTo(10);
    }

    @Test
    void evaluate_respects_max_new_per_reflection() {
        var threeGoals = List.of(
                new GoalFormationProposal.ProposedGoal("a", "A", GoalPriority.SECONDARY, "r", java.util.Map.of()),
                new GoalFormationProposal.ProposedGoal("b", "B", GoalPriority.SECONDARY, "r", java.util.Map.of()),
                new GoalFormationProposal.ProposedGoal("c", "C", GoalPriority.SECONDARY, "r", java.util.Map.of()));
        nextProposal = new GoalFormationProposal(threeGoals, "");
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get().goals()).hasSize(3);
    }

    @Test
    void evaluate_skips_when_within_tick_cooldown() {
        evaluator.evaluate("hc", 1, List.of("first"), Map.of());
        registeredDescriptor.set(null);
        evaluator.evaluate("hc", 5, List.of("second"), Map.of());
        assertThat(registeredDescriptor.get()).isNull();
    }

    @Test
    void evaluate_runs_after_cooldown_expires() {
        evaluator.evaluate("hc", 1, List.of("first"), Map.of());
        registeredDescriptor.set(null);
        nextProposal = new GoalFormationProposal(List.of(
                new GoalFormationProposal.ProposedGoal("second-goal",
                                                       "Another goal", GoalPriority.SECONDARY, "new insight", java.util.Map.of())), "");
        evaluator.evaluate("hc", 20, List.of("second"), Map.of());
        assertThat(registeredDescriptor.get()).isNotNull();
        assertThat(registeredDescriptor.get().goals().stream()
                                       .map(AgentGoal::name).toList()).contains("second-goal");
    }

    @Test
    void evaluate_does_not_register_when_no_new_goals() {
        nextProposal = new GoalFormationProposal(List.of(), "nothing new");
        evaluator.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get()).isNull();
    }

    @Test
    void evaluate_revision_abandons_goal() {
        io.casehub.api.spi.routing.GoalRevisionStrategy revisionStrategy = ctx ->
                                                                                   new io.casehub.api.spi.routing.GoalRevisionProposal(List.of(
                                                                                           new io.casehub.api.spi.routing.GoalRevisionProposal.RevisedGoal(
                                                                                                   "eliminate", io.casehub.api.spi.routing.GoalRevisionAction.ABANDON,
                                                                                                   null, "No longer relevant")), "");
        nextProposal = new io.casehub.api.spi.routing.GoalFormationProposal(List.of(), "");
        var evalWithRevision = new ManorGoalEvaluator(ctx -> nextProposal, revisionStrategy,
                                                      new TestRegistry(AgentDescriptor.builder()
                                                                                      .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                                                                                      .slot("manor-character")
                                                                                      .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                                                                   GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                                                                                      .build()),
                                                      new TestMemoryStore(), "wacky-manor", 10, 2);
        evalWithRevision.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get()).isNotNull();
        assertThat(registeredDescriptor.get().goals()).isEmpty();
    }

    @Test
    void evaluate_revision_completes_goal() {
        io.casehub.api.spi.routing.GoalRevisionStrategy revisionStrategy = ctx ->
                                                                                   new io.casehub.api.spi.routing.GoalRevisionProposal(List.of(
                                                                                           new io.casehub.api.spi.routing.GoalRevisionProposal.RevisedGoal(
                                                                                                   "eliminate", io.casehub.api.spi.routing.GoalRevisionAction.COMPLETE,
                                                                                                   null, "Goal achieved")), "");
        nextProposal = new io.casehub.api.spi.routing.GoalFormationProposal(List.of(), "");
        var evalWithRevision = new ManorGoalEvaluator(ctx -> nextProposal, revisionStrategy,
                                                      new TestRegistry(AgentDescriptor.builder()
                                                                                      .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                                                                                      .slot("manor-character")
                                                                                      .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                                                                   GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                                                                                      .build()),
                                                      new TestMemoryStore(), "wacky-manor", 10, 2);
        evalWithRevision.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get()).isNotNull();
        assertThat(registeredDescriptor.get().goals()).isEmpty();
    }

    @Test
    void evaluate_revision_revises_description() {
        io.casehub.api.spi.routing.GoalRevisionStrategy revisionStrategy = ctx ->
                                                                                   new io.casehub.api.spi.routing.GoalRevisionProposal(List.of(
                                                                                           new io.casehub.api.spi.routing.GoalRevisionProposal.RevisedGoal(
                                                                                                   "eliminate", io.casehub.api.spi.routing.GoalRevisionAction.REVISE,
                                                                                                   "Neutralize Penelope before dawn", "More specific")), "");
        nextProposal = new io.casehub.api.spi.routing.GoalFormationProposal(List.of(), "");
        var evalWithRevision = new ManorGoalEvaluator(ctx -> nextProposal, revisionStrategy,
                                                      new TestRegistry(AgentDescriptor.builder()
                                                                                      .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                                                                                      .slot("manor-character")
                                                                                      .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                                                                   GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                                                                                      .build()),
                                                      new TestMemoryStore(), "wacky-manor", 10, 2);
        evalWithRevision.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(registeredDescriptor.get()).isNotNull();
        assertThat(registeredDescriptor.get().goals()).hasSize(1);
        assertThat(registeredDescriptor.get().goals().get(0).description())
                .isEqualTo("Neutralize Penelope before dawn");
    }

    @Test
    void evaluate_abandon_ingests_memory() {
        io.casehub.api.spi.routing.GoalRevisionStrategy revisionStrategy = ctx ->
                                                                                   new io.casehub.api.spi.routing.GoalRevisionProposal(List.of(
                                                                                           new io.casehub.api.spi.routing.GoalRevisionProposal.RevisedGoal(
                                                                                                   "eliminate", io.casehub.api.spi.routing.GoalRevisionAction.ABANDON,
                                                                                                   null, "No longer relevant")), "");
        nextProposal = new io.casehub.api.spi.routing.GoalFormationProposal(List.of(), "");
        var memStore = new TestMemoryStore();
        var evalWithRevision = new ManorGoalEvaluator(ctx -> nextProposal, revisionStrategy,
                                                      new TestRegistry(AgentDescriptor.builder()
                                                                                      .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                                                                                      .slot("manor-character")
                                                                                      .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                                                                   GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                                                                                      .build()),
                                                      memStore, "wacky-manor", 10, 2);
        evalWithRevision.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(memStore.storedInputs).isNotEmpty();
        assertThat(memStore.storedInputs.stream()
                                        .anyMatch(i -> i.text().contains("Abandoned goal") && i.text().contains("eliminate")))
                .isTrue();
    }

    @Test
    void evaluate_complete_ingests_memory() {
        io.casehub.api.spi.routing.GoalRevisionStrategy revisionStrategy = ctx ->
                                                                                   new io.casehub.api.spi.routing.GoalRevisionProposal(List.of(
                                                                                           new io.casehub.api.spi.routing.GoalRevisionProposal.RevisedGoal(
                                                                                                   "eliminate", io.casehub.api.spi.routing.GoalRevisionAction.COMPLETE,
                                                                                                   null, "Goal achieved")), "");
        nextProposal = new io.casehub.api.spi.routing.GoalFormationProposal(List.of(), "");
        var memStore = new TestMemoryStore();
        var evalWithRevision = new ManorGoalEvaluator(ctx -> nextProposal, revisionStrategy,
                                                      new TestRegistry(AgentDescriptor.builder()
                                                                                      .agentId("hc").name("Hooded Claw").tenancyId("wacky-manor")
                                                                                      .slot("manor-character")
                                                                                      .goals(List.of(new AgentGoal("eliminate", "Eliminate Penelope",
                                                                                                                   GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), java.util.Map.of())))
                                                                                      .build()),
                                                      memStore, "wacky-manor", 10, 2);
        evalWithRevision.evaluate("hc", 1, List.of("insight"), Map.of());
        assertThat(memStore.storedInputs).isNotEmpty();
        assertThat(memStore.storedInputs.stream()
                                        .anyMatch(i -> i.text().contains("Completed goal") && i.text().contains("eliminate")))
                .isTrue();
    }


    private class TestRegistry implements AgentRegistry {
        private AgentDescriptor current;

        TestRegistry(AgentDescriptor initial) {this.current = initial;}

        @Override
        public Optional<AgentDescriptor> findById(String id, String tenancy) {
            return Optional.of(current);
        }

        @Override
        public void register(AgentDescriptor d) {
            current = d;
            registeredDescriptor.set(d);
        }

        @Override
        public java.util.List<io.casehub.eidos.api.AgentMatch> find(io.casehub.eidos.api.AgentQuery query) {
            return List.of();
        }
    }

    private static class TestMemoryStore implements CaseMemoryStore {
        final java.util.List<io.casehub.neocortex.memory.MemoryInput> storedInputs = new java.util.ArrayList<>();

        @Override
        public java.util.List<io.casehub.neocortex.memory.Memory> query(MemoryQuery q) {
            return List.of();
        }

        @Override
        public String store(io.casehub.neocortex.memory.MemoryInput input) {
            storedInputs.add(input);
            return "mem-" + storedInputs.size();
        }

        @Override
        public int erase(io.casehub.neocortex.memory.EraseRequest request) {return 0;}
    }
}
