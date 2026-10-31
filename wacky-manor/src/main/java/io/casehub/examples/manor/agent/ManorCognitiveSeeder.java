package io.casehub.examples.manor.agent;

import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.goal.DriveGoalProposal;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.need.NeedTier;
import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.OverlayRef;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.platform.api.identity.PrincipalId;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ManorCognitiveSeeder {

    public record SeedResult(String subgraphId, Map<String, Instant> seededNodeTimestamps) {}

    public record PeopleSeedResult(String subgraphId, Set<String> seededAgentIds, int overlayCount) {}

    public static final String PEOPLE_SUBGRAPH = "people";

    // Golden prose rendering methodology (era5): each drive owns a unique body territory
    private static final Map<String, String> BODY_LOCATIONS = Map.ofEntries(
            Map.entry("scheming", "fingertips, periphery, visual field"),
            Map.entry("gloating", "sternum, breastbone"),
            Map.entry("greed", "gut, navel, ribs"),
            Map.entry("recognition", "upper chest, spine"),
            Map.entry("dominance", "spine, jaw, feet"),
            Map.entry("self-preservation", "ears, hearing, peripheral awareness"),
            Map.entry("protection", "legs, neck, positioning"),
            Map.entry("gallantry", "hands, chest warmth"),
            Map.entry("proving-worth", "shoulders, jaw, grip"),
            Map.entry("loyalty", "chest ache, alignment"),
            Map.entry("suspicion", "neck prickling, gut knowing"),
            Map.entry("curiosity", "fingertips tracing, chest fizzing"),
            Map.entry("social-harmony", "chest settling, jaw softening")
    );

    // Barrett (2017) channel emphasis per drive — primary 2 of 4 channels
    private static final Map<String, String> CHANNEL_EMPHASIS = Map.ofEntries(
            Map.entry("scheming", "attention + action-readiness"),
            Map.entry("gloating", "body + time"),
            Map.entry("greed", "body + attention"),
            Map.entry("recognition", "body + time"),
            Map.entry("dominance", "body + attention"),
            Map.entry("self-preservation", "attention + body"),
            Map.entry("protection", "action-readiness + attention"),
            Map.entry("gallantry", "action-readiness + body"),
            Map.entry("proving-worth", "body + time"),
            Map.entry("loyalty", "body + action-readiness"),
            Map.entry("suspicion", "attention + body"),
            Map.entry("curiosity", "attention + action-readiness"),
            Map.entry("social-harmony", "body + attention")
    );

    public PeopleSeedResult seedPeople(Map<String, SocialConfig> allConfigs, String tenantId) {
        Set<String> allAgentIds = allConfigs.keySet();
        if (allAgentIds.isEmpty()) {
            return new PeopleSeedResult(PEOPLE_SUBGRAPH, Set.of(), 0);
        }

        var subgraphId = mindMapStore.createSubgraph(
                new SubgraphInput(PEOPLE_SUBGRAPH, "social", null), tenantId);
        if (subgraphId == null || subgraphId.isBlank()) {
            return new PeopleSeedResult(PEOPLE_SUBGRAPH, Set.of(), 0);
        }

        var                 now           = Instant.now();
        Map<String, String> personNodeIds = new HashMap<>();

        for (String agentId : allAgentIds) {
            String nodeId = mindMapStore.addNode(
                    NodeInput.of(agentId, subgraphId)
                             .withConfidence(Confidence.stated(0.9, now))
                             .withProvenance("manor-seed")
                             .withTraits(Set.of("Entitylike"))
                             .withProperties(Map.of("agentId", agentId))
                             .withPad(0.0, 0.0, 0.0),
                    tenantId);
            personNodeIds.put(agentId, nodeId);
        }

        int overlayCount = 0;
        for (var entry : allConfigs.entrySet()) {
            String observerId = entry.getKey();
            SocialConfig config = entry.getValue();
            for (var rel : config.relationships()) {
                String targetNodeId = personNodeIds.get(rel.targetAgentId());
                if (targetNodeId == null) {continue;}

                var props = new HashMap<String, String>();
                props.put(OverlayRef.AGENT_ID, observerId);

                config.relationalSchemas().stream()
                      .filter(rs -> rel.targetAgentId().equals(rs.target()))
                      .findFirst()
                      .ifPresent(rs -> {
                          props.put("rel.target-id", rel.targetAgentId());
                          props.put("rel.role", rs.role());
                          props.put("rel.trust", String.valueOf(rs.trust()));
                          props.put("rel.intimacy", String.valueOf(rs.intimacy()));
                          props.put("rel.utility", String.valueOf(rs.utility()));
                          if (!rs.description().isEmpty()) {
                              props.put("rel.description", rs.description());
                          }
                      });

                mindMapStore.addNode(
                        NodeInput.of(observerId + "-sees-" + rel.targetAgentId(), subgraphId)
                                 .withConfidence(Confidence.stated(0.7, now))
                                 .withProvenance("manor-seed")
                                 .withTraits(Set.of("overlay"))
                                 .withRefs(Set.of(OverlayRef.of(targetNodeId)))
                                 .withProperties(Map.copyOf(props))
                                 .withPad(rel.pleasure(), rel.arousal(), rel.dominance())
                                 .withPrincipalId(PrincipalId.agent(observerId)),
                        tenantId);
                overlayCount++;
            }
        }

        return new PeopleSeedResult(subgraphId, Set.copyOf(allAgentIds), overlayCount);
    }


    private final MindMapStore mindMapStore;

    public ManorCognitiveSeeder(MindMapStore mindMapStore) {
        this.mindMapStore = mindMapStore;
    }

    public SeedResult seed(String agentId, SocialConfig config, String tenantId) {
        var subgraphName = subgraphName(agentId);
        if (config.initialBeliefs().isEmpty() && config.drives().isEmpty()) {
            return new SeedResult(subgraphName, Map.of());
        }

        var subgraphId = mindMapStore.createSubgraph(
                new SubgraphInput(subgraphName, "cognitive", null), tenantId);
        if (subgraphId == null || subgraphId.isBlank()) {
            return new SeedResult(subgraphName, Map.of());
        }

        var timestamps = new HashMap<String, Instant>();
        var now        = Instant.now();

        for (var belief : config.initialBeliefs()) {
            mindMapStore.addNode(
                    NodeInput.of(belief.value(), subgraphId)
                             .withConfidence(Confidence.stated(0.8, now))
                             .withProvenance("manor-seed")
                             .withTraits(Set.of("Belieflike"))
                             .withProperties(Map.of("subject", belief.key()))
                             .withPrincipalId(PrincipalId.agent(agentId)),
                    tenantId);
            timestamps.put(belief.key(), now);
        }

        for (var drive : config.drives()) {
            var driveProps = new HashMap<String, String>();
            driveProps.put("cognitiveKind", "drive-intensity");
            driveProps.put("agent-id", agentId);
            driveProps.put("drive-type", drive.type());
            driveProps.put("intensity", String.valueOf(drive.intensity()));
            driveProps.put("initial-intensity", String.valueOf(drive.intensity()));
            if (drive.description() != null && !drive.description().isEmpty()) {
                driveProps.put("description", drive.description());
            }
            String bodyLoc = BODY_LOCATIONS.get(drive.type());
            if (bodyLoc != null) { driveProps.put("body-location", bodyLoc); }
            String channel = CHANNEL_EMPHASIS.get(drive.type());
            if (channel != null) { driveProps.put("channel-emphasis", channel); }

            mindMapStore.addNode(
                    NodeInput.of(drive.type(), subgraphId)
                             .withConfidence(Confidence.stated(0.8, now))
                             .withProvenance("drive-adaptation")
                             .withProperties(Map.copyOf(driveProps)),
                    tenantId);
            timestamps.put("drive:" + drive.type(), now);
        }

        for (var facet : config.personalityFacets()) {
            mindMapStore.addNode(
                    NodeInput.of("facet-" + facet.facet().toLowerCase().replace(' ', '-'), subgraphId)
                             .withConfidence(Confidence.stated(0.9, now))
                             .withProvenance("personality-seed")
                             .withProperties(Map.of(
                                     "cognitiveKind", "personality-facet",
                                     "agent-id", agentId,
                                     "facet", facet.facet(),
                                     "score", String.valueOf(facet.score()))),
                    tenantId);
        }

        if (config.attachment() != null) {
            var att = config.attachment();
            mindMapStore.addNode(
                    NodeInput.of("attachment-style", subgraphId)
                             .withConfidence(Confidence.stated(0.9, now))
                             .withProvenance("personality-seed")
                             .withProperties(Map.of(
                                     "cognitiveKind", "attachment-style",
                                     "agent-id", agentId,
                                     "style", att.style(),
                                     "anxiety", String.valueOf(att.anxiety()),
                                     "avoidance", String.valueOf(att.avoidance()))),
                    tenantId);
        }

        for (NeedTier tier : NeedTier.values()) {
            mindMapStore.addNode(
                    NodeInput.of("need-" + tier.name().toLowerCase(), subgraphId)
                             .withConfidence(Confidence.stated(0.8, now))
                             .withProvenance("need-satisfaction")
                             .withProperties(Map.of(
                                     "cognitiveKind", "need-satisfaction",
                                     "agent-id", agentId,
                                     "tier", tier.name(),
                                     "satisfaction", "0.5",
                                     "resting-level", String.valueOf(switch (tier) {
                                         case SAFETY -> 0.6;
                                         case TASKS -> 0.3;
                                         default -> 0.4;
                                     }))),
                    tenantId);
            timestamps.put("need:" + tier.name(), now);
        }

        for (var schema : config.relationalSchemas()) {
            var schemaProps = new HashMap<String, String>();
            schemaProps.put("cognitiveKind", "relational-schema");
            schemaProps.put("agent-id", agentId);
            schemaProps.put("rel.target-id", schema.target());
            schemaProps.put("rel.role", schema.role());
            schemaProps.put("rel.trust", String.valueOf(schema.trust()));
            schemaProps.put("rel.intimacy", String.valueOf(schema.intimacy()));
            schemaProps.put("rel.utility", String.valueOf(schema.utility()));
            if (schema.description() != null && !schema.description().isEmpty()) {
                schemaProps.put("rel.description", schema.description());
            }
            mindMapStore.addNode(
                    NodeInput.of("rel-" + agentId + "-" + schema.target(), subgraphId)
                             .withConfidence(Confidence.stated(0.8, now))
                             .withProvenance("manor-seed")
                             .withProperties(Map.copyOf(schemaProps))
                             .withPrincipalId(PrincipalId.agent(agentId)),
                    tenantId);
            timestamps.put("rel:" + schema.target(), now);
        }

        return new SeedResult(subgraphId, Map.copyOf(timestamps));}

    public static String subgraphName(String agentId) {
        return "beliefs-" + agentId;
    }

    public static List<DriveGoalProposal> mapGoals(List<SocialConfig.GoalConfig> configs) {
        return configs.stream()
                .map(c -> new DriveGoalProposal(
                        DriveAxis.valueOf(c.axis()),
                        c.name(),
                        c.description(),
                        c.formationReason(),
                        c.intensity()))
                .toList();
    }

    public void seedGoals(String agentId, SocialConfig config,
                           GoalProposalOrchestrator goals, String tenantId) {
        if (config.goals().isEmpty()) return;
        var proposals = mapGoals(config.goals());
        goals.registerGoals(agentId, tenantId, proposals);
    }

    public int seedFormationMemories(String agentId, SocialConfig config, String tenantId,
                                     io.casehub.neocortex.memory.experience.ExperienceRecorder recorder) {
        if (config.formationMemories().isEmpty()) {return 0;}

        var events = config.formationMemories().stream()
                           .map(mem -> {
                               var period = mem.age() <= 6 ? "early-childhood"
                                                           : mem.age() <= 12 ? "childhood"
                                                                             : mem.age() <= 18 ? "adolescence"
                                                                                               : "young-adulthood";
                               var situationTypes = new java.util.ArrayList<>(List.of("formation", period));
                               for (var ann : mem.annotations()) {
                                   if (!situationTypes.contains(ann.drive())) {
                                       situationTypes.add(ann.drive());
                                   }
                               }
                               return new io.casehub.neocortex.memory.experience.FormativeExperience(
                                       agentId, tenantId,
                                       "formative", "age-" + mem.age(),
                                       Instant.now().minusSeconds((18 - mem.age()) * 365L * 24 * 60 * 60),
                                       mem.episode(),
                                       0.9,
                                       Map.of("age", String.valueOf(mem.age())),
                                       mem.catalogue(),
                                       List.copyOf(situationTypes),
                                       1.5,
                                       "continuous",
                                       period,
                                       mem.pleasure(), mem.arousal(), mem.dominance());
                           })
                           .map(e -> (io.casehub.neocortex.memory.experience.ExperienceEvent) e)
                           .toList();

        recorder.recordAll(events);
        return events.size();
    }

    public void seedCapsState(String agentId, SocialConfig config, String tenantId,
                              io.casehub.neocortex.caps.CapsEngine capsEngine) {
        SocialConfig.Disposition d = config.disposition() != null
                                     ? config.disposition()
                                     : SocialConfig.Disposition.NEUTRAL;
        capsEngine.initializeAgent(tenantId, agentId,
                                   new io.casehub.neocortex.cognitive.index.DispositionAxes(
                                           d.socialOrient(), d.ruleFollowing(), d.riskAppetite(),
                                           d.autonomy(), d.conflictMode()));
    }


}
