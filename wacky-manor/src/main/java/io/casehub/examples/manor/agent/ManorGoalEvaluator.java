package io.casehub.examples.manor.agent;

import io.casehub.api.model.RetrievedMemory;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.api.spi.routing.GoalRevisionStrategy;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ManorGoalEvaluator {

    private static final Logger log = Logger.getLogger(ManorGoalEvaluator.class);
    // matches AgentDescriptorValidator.MAX_GOALS (package-private in eidos-api)
    static final int MAX_GOALS = 10;

    private final GoalFormationStrategy formationStrategy;
    private final GoalRevisionStrategy revisionStrategy;
    private final AgentRegistry agentRegistry;
    private final CaseMemoryStore memoryStore;
    private final String tenancyId;
    private final int cooldownTicks;
    private final int maxNewPerReflection;
    private final ManorPlanEvaluator planEvaluator;
    private final ConcurrentHashMap<String, Integer> lastFormationTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();

    public ManorGoalEvaluator(GoalFormationStrategy formationStrategy,
                               GoalRevisionStrategy revisionStrategy,
                               AgentRegistry agentRegistry,
                               CaseMemoryStore memoryStore,
                               String tenancyId,
                               int cooldownTicks,
                               int maxNewPerReflection) {
        this(formationStrategy, revisionStrategy, agentRegistry, memoryStore,
             tenancyId, cooldownTicks, maxNewPerReflection, null);
    }

    public ManorGoalEvaluator(GoalFormationStrategy formationStrategy,
                               GoalRevisionStrategy revisionStrategy,
                               AgentRegistry agentRegistry,
                               CaseMemoryStore memoryStore,
                               String tenancyId,
                               int cooldownTicks,
                               int maxNewPerReflection,
                               ManorPlanEvaluator planEvaluator) {
        this.formationStrategy = formationStrategy;
        this.revisionStrategy = revisionStrategy;
        this.agentRegistry = agentRegistry;
        this.memoryStore = memoryStore;
        this.tenancyId = tenancyId;
        this.cooldownTicks = cooldownTicks;
        this.maxNewPerReflection = maxNewPerReflection;
        this.planEvaluator = planEvaluator;
    }

    public void evaluate(String agentId, int currentTick, List<String> insights,
                         Map<String, GoalOutcomeCounts> goalOutcomes) {
        var lock = agentLocks.computeIfAbsent(agentId, k -> new ReentrantLock());
        lock.lock();
        try {
            var lastTick = lastFormationTick.get(agentId);
            if (lastTick != null && currentTick - lastTick < cooldownTicks) {
                return;
            }

            var descriptorOpt = agentRegistry.findById(agentId, tenancyId);
            if (descriptorOpt.isEmpty()) {return;}
            var descriptor = descriptorOpt.get();

            int remaining = MAX_GOALS - descriptor.goals().size();
            if (remaining <= 0 && revisionStrategy == null) {return;}

            List<AgentGoal> finalGoals = new ArrayList<>(descriptor.goals());
            boolean         changed    = false;

            if (remaining > 0) {
                List<RetrievedMemory> memories = retrieveMemories(agentId);
                var context = new GoalFormationContext(agentId, tenancyId,
                                                       insights, descriptor.goals(), memories, remaining);
                var proposal = formationStrategy.propose(context);
                if (proposal != null && !proposal.goals().isEmpty()) {
                    var newGoals = validateAndConvert(proposal.goals(), descriptor, remaining);
                    if (!newGoals.isEmpty()) {
                        finalGoals.addAll(newGoals);
                        changed = true;
                        if (planEvaluator != null) {
                            for (AgentGoal newGoal : newGoals) {
                                planEvaluator.formPlanForGoal(agentId, newGoal, finalGoals, currentTick);
                            }
                        }
                    }
                }
            }

            if (revisionStrategy != null && !finalGoals.isEmpty()) {
                var revisionContext = new io.casehub.api.spi.routing.GoalRevisionContext(
                        agentId, tenancyId, finalGoals, goalOutcomes);
                var revisionProposal = revisionStrategy.revise(revisionContext);
                if (revisionProposal != null && !revisionProposal.revisions().isEmpty()) {
                    for (var revision : revisionProposal.revisions()) {
                        switch (revision.action()) {
                            case REVISE -> {
                                for (int i = 0; i < finalGoals.size(); i++) {
                                    if (finalGoals.get(i).name().equals(revision.goalName())) {
                                        finalGoals.set(i, finalGoals.get(i).toBuilder()
                                                                    .description(revision.revisedDescription()).build());
                                        changed = true;
                                        break;
                                    }
                                }
                            }
                            case ABANDON -> {
                                if (finalGoals.removeIf(g -> g.name().equals(revision.goalName()))) {
                                    changed = true;
                                    ingestGoalTransition(agentId, "Abandoned", revision.goalName(), revision.revisionReason());
                                    if (planEvaluator != null) {
                                        planEvaluator.removePlanForGoal(agentId, revision.goalName());
                                    }
                                }
                            }
                            case COMPLETE -> {
                                if (finalGoals.removeIf(g -> g.name().equals(revision.goalName()))) {
                                    changed = true;
                                    ingestGoalTransition(agentId, "Completed", revision.goalName(), revision.revisionReason());
                                    if (planEvaluator != null) {
                                        planEvaluator.removePlanForGoal(agentId, revision.goalName());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!changed) {return;}

            var updated = descriptor.toBuilder().goals(finalGoals).build();
            agentRegistry.register(updated);
            lastFormationTick.put(agentId, currentTick);
        } catch (Exception e) {
            log.warnf(e, "Goal evaluation failed for agent %s", agentId);
        } finally {
            lock.unlock();
        }
    }

    private List<AgentGoal> validateAndConvert(
            List<GoalFormationProposal.ProposedGoal> proposed,
            io.casehub.eidos.api.AgentDescriptor descriptor, int remaining) {
        var existingNames = new HashSet<String>();
        for (AgentGoal g : descriptor.goals()) {
            existingNames.add(g.name());
        }
        List<AgentGoal> validated = new ArrayList<>();
        for (var p : proposed) {
            if (validated.size() >= maxNewPerReflection || validated.size() >= remaining) break;
            if (p.name() == null || p.name().isBlank()) continue;
            if (p.description() == null || p.description().isBlank()) continue;
            if (existingNames.contains(p.name())) continue;
            GoalPriority priority = p.suggestedPriority() != null
                    ? p.suggestedPriority() : GoalPriority.SECONDARY;
            try {
                var goal = new AgentGoal(p.name(), p.description(), priority,
                        Visibility.PRIVATE, List.of(), java.util.Map.of());
                validated.add(goal);
                existingNames.add(p.name());
            } catch (Exception e) {
                log.debugf("Rejected proposed goal %s: %s", p.name(), e.getMessage());
            }
        }
        return validated;
    }

    private List<RetrievedMemory> retrieveMemories(String agentId) {
        try {
            var memories = memoryStore.query(MemoryQuery.forEntity(agentId,
                    new MemoryDomain("manor"), tenancyId)
                    .withLimit(20).withOrder(MemoryOrder.SALIENCE));
            return memories.stream()
                    .map(m -> new RetrievedMemory(m.memoryId(), m.text(),
                            m.domain().name(), m.createdAt(), m.attributes()))
                    .toList();
        } catch (Exception e) {
            log.debugf("Failed to retrieve memories for %s: %s", agentId, e.getMessage());
            return List.of();
        }
    }

    private void ingestGoalTransition(String agentId, String action, String goalName, String reason) {
        try {
            String text = action + " goal: " + goalName + " — " + reason;
            var input = new io.casehub.neocortex.memory.MemoryInput(
                    agentId, new MemoryDomain("manor"), tenancyId, null,
                    text, java.util.Map.of("type", "goal-" + action.toLowerCase()), 0.7);
            memoryStore.store(input);
        } catch (Exception e) {
            log.debugf("Failed to ingest goal transition for %s: %s", agentId, e.getMessage());
        }
    }

}
