package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.observation.affordance.ObservationSection;
import io.casehub.eidos.api.AgentConstraint;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.memory.Memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class CharacterCognition {

    private final String                                                agentId;
    private final AgentExperienceService                                experienceService;
    private final CognitiveDefaults                                     cognitiveDefaults;
    private final SocialConfig                                          socialConfig;
    private final List<AgentConstraint>                                 constraints;
    private final io.casehub.neocortex.cognitive.index.CognitiveProfile cognitiveProfile;
    private final ManorContextStrategy                                  contextStrategy;
    private final io.casehub.neocortex.cognition.core.CognitionCore        cognitionCore;
    private final ManorCognitiveSeeder.SeedResult                       seedResult;
    private final String                                                tenantId;
    private final io.casehub.neocortex.mindmap.MindMapStore             mindMapStore;
    private final io.casehub.engine.trust.TrustEvolutionConfig          trustEvolutionConfig;

    public CharacterCognition(String agentId, AgentExperienceService experienceService) {
        this(agentId, experienceService, null, SocialConfig.empty(), List.of(),
             null, new ManorContextStrategy(), null, null, null, null, null);
    }

    public CharacterCognition(String agentId, AgentExperienceService experienceService,
                              CognitiveDefaults cognitiveDefaults, SocialConfig socialConfig,
                              List<AgentConstraint> constraints) {
        this(agentId, experienceService, cognitiveDefaults, socialConfig, constraints,
             null, new ManorContextStrategy(), null, null, null, null, null);
    }

    public CharacterCognition(String agentId, AgentExperienceService experienceService,
                              CognitiveDefaults cognitiveDefaults, SocialConfig socialConfig,
                              List<AgentConstraint> constraints,
                              io.casehub.neocortex.cognitive.index.CognitiveProfile cognitiveProfile,
                              ManorContextStrategy contextStrategy,
                              io.casehub.neocortex.cognition.core.CognitionCore cognitionCore,
                              ManorCognitiveSeeder.SeedResult seedResult,
                              String tenantId,
                              io.casehub.neocortex.mindmap.MindMapStore mindMapStore,
                              io.casehub.engine.trust.TrustEvolutionConfig trustEvolutionConfig) {
        this.agentId                = agentId;
        this.experienceService      = experienceService;
        this.cognitiveDefaults      = cognitiveDefaults;
        this.socialConfig           = socialConfig != null ? socialConfig : SocialConfig.empty();
        this.constraints            = constraints != null ? List.copyOf(constraints) : List.of();
        this.cognitiveProfile       = cognitiveProfile;
        this.contextStrategy        = contextStrategy != null ? contextStrategy : new ManorContextStrategy();
        this.cognitionCore          = cognitionCore;
        this.seedResult             = seedResult;
        this.tenantId               = tenantId;
        this.mindMapStore           = mindMapStore;
        this.trustEvolutionConfig   = trustEvolutionConfig;
    }

    public String agentId() {return agentId;}

    public double computeImportance(ActionType action) {
        if (action == null) {return 0.5;}
        return switch (action) {
            case STEAL -> 0.9;
            case USE -> 0.8;
            case TAKE, GIVE, PULL_ASIDE -> 0.7;
            case INTERACT -> 0.6;
            case MOVE -> 0.3;
            case LOOK -> 0.2;
            case WAIT -> 0.1;
        };
    }

    public void recordExperience(String room, String description, String thinking,
                                 double importance, String targetAgentId, int tick) {
        if (experienceService != null) {
            experienceService.ingest(agentId, room, description, thinking, importance, targetAgentId, tick);
        }
    }

    public List<Memory> recallMemories(int limit) {
        return experienceService != null ? experienceService.recall(agentId, limit) : List.of();
    }

    public List<Memory> recallReflections(int limit) {
        return experienceService != null ? experienceService.recallReflections(agentId, limit) : List.of();
    }

    public List<Memory> recallRelationships(String otherId, int limit) {
        return experienceService != null ? experienceService.recallRelationships(agentId, otherId, limit) : List.of();
    }

    public List<ObservationSection> renderCognitiveSections(
            CharacterState character,
            Collection<String> nearbyAgentIds,
            Map<String, String> agentNames) {
        var sections = new ArrayList<ObservationSection>();

        if (!socialConfig.tendencies().isEmpty()) {
            sections.add(ObservationSection.items(
                    "Your Behavioral Tendencies", null, socialConfig.tendencies()));
        }

        if (mindMapStore != null && tenantId != null) {
            var beliefSubgraphName = ManorCognitiveSeeder.subgraphName(agentId);
            var subgraphs          = mindMapStore.listSubgraphs(tenantId);
            var beliefSg = subgraphs.stream()
                                    .filter(sg -> beliefSubgraphName.equals(sg.name()))
                                    .findFirst();
            if (beliefSg.isPresent()) {
                var nodes = mindMapStore.nodesIn(beliefSg.get().id(), tenantId);
                var beliefNodes = nodes.stream()
                                       .filter(n -> n.traits().contains("Belieflike"))
                                       .toList();
                if (!beliefNodes.isEmpty()) {
                    var revisedKeys = new java.util.HashSet<String>();
                    var beliefs     = new java.util.ArrayList<io.casehub.blocks.agentic.belief.Belief<String>>();
                    for (var node : beliefNodes) {
                        var key          = node.property("subject").orElse(node.name());
                        var value        = node.name();
                        int entrenchment = (int) (node.confidence().value() * 10);
                        beliefs.add(io.casehub.blocks.agentic.belief.Belief.of(key, value, entrenchment));
                        if ("belief-revision".equals(node.provenance())) {
                            revisedKeys.add(key);
                        }
                    }
                    sections.add(io.casehub.blocks.summarisation.observation.affordance
                                         .CognitiveObservationSections.beliefsSection(beliefs, revisedKeys));
                }
            }
        } else if (!socialConfig.initialBeliefs().isEmpty()) {
            var items = socialConfig.initialBeliefs().stream()
                                    .map(SocialConfig.InitialBelief::value)
                                    .toList();
            sections.add(ObservationSection.items("Your Beliefs", null, items));
        }

        var selectedNorms = contextStrategy.selectNorms(socialConfig.norms(), agentNames.values(), character.inventory());
        if (!selectedNorms.isEmpty()) {
            var items = selectedNorms.stream()
                                     .map(SocialConfig.NormEntry::rule)
                                     .toList();
            sections.add(ObservationSection.items("Social Rules", null, items));
        }

        sections.addAll(renderSocialAwareness(nearbyAgentIds, agentNames));

        if (!socialConfig.personalityFacets().isEmpty()) {
            sections.add(renderPersonalityFacets());
        }

        if (!socialConfig.relationalSchemas().isEmpty() && !nearbyAgentIds.isEmpty()) {
            var schemaSection = renderRelationalSchemas(nearbyAgentIds, agentNames);
            if (schemaSection != null) {
                sections.add(schemaSection);
            }
        }

        if (socialConfig.attachment() != null) {
            sections.add(renderAttachmentStyle());
        }

        var trustSections = renderTrustSections();
        if (!trustSections.isEmpty()) {
            sections.addAll(trustSections);
        }

        if (cognitionCore != null && tenantId != null) {
            var ctx = new io.casehub.neocortex.cognition.prompt.CognitionRenderContext(agentId, tenantId, null);
            for (var section : cognitionCore.promptSections()) {
                if (section.blockTag() != null) continue;
                var text = section.render(ctx);
                if (text != null && !text.isBlank()) {
                    sections.add(adaptPromptSection(text));
                }
            }
        }

        var personaSection = PersonaActivationSection.resolve(
                socialConfig.personaConstraint(), nearbyAgentIds);
        if (personaSection != null) {
            sections.add(personaSection);
        }

        return sections;
    }


    public List<String> renderTaggedSections() {
        if (cognitionCore == null || tenantId == null) return List.of();
        var ctx = new io.casehub.neocortex.cognition.prompt.CognitionRenderContext(agentId, tenantId, null);
        var tagged = new ArrayList<String>();
        for (var section : cognitionCore.promptSections()) {
            if (section.blockTag() == null) continue;
            var text = section.render(ctx);
            if (text != null && !text.isBlank()) {
                tagged.add(text);
            }
        }
        return tagged;
    }

    private ObservationSection renderFormationMemories() {
        var sb = new StringBuilder();
        sb.append("These are the experiences that made you who you are.\n\n");
        for (var mem : socialConfig.formationMemories()) {
            sb.append("**Age ").append(mem.age()).append(":** ").append(mem.episode()).append("\n\n");
        }
        return ObservationSection.text("Where You Come From", sb.toString().strip());
    }

    private ObservationSection renderPersonalityFacets() {
        var sb = new StringBuilder();
        for (var facet : socialConfig.personalityFacets()) {
            if (!facet.origin().isEmpty()) {
                sb.append(facet.origin()).append(" ");
            }
        }
        return ObservationSection.text("Who You Are", sb.toString().strip());
    }

    private ObservationSection renderRelationalSchemas(Collection<String> nearbyAgentIds, Map<String, String> agentNames) {
        var relevant = socialConfig.relationalSchemas().stream()
                                   .filter(rs -> nearbyAgentIds.contains(rs.target()))
                                   .toList();
        if (relevant.isEmpty()) {return null;}

        var sb = new StringBuilder();
        for (var rs : relevant) {
            var displayName = agentNames.getOrDefault(rs.target(), rs.target());
            sb.append("**").append(displayName).append(":** ");
            if (!rs.description().isEmpty()) {
                sb.append(rs.description());
            }
            sb.append("\n");
        }
        return ObservationSection.text("How You Feel About Those Present", sb.toString().strip());
    }

    private ObservationSection renderAttachmentStyle() {
        var a  = socialConfig.attachment();
        var sb = new StringBuilder();
        if (a.avoidance() > 0.6) {
            sb.append("You don't need people. Not really. When someone tries to get close, ");
            sb.append("you feel faint irritation. Closeness is a vulnerability you learned to avoid.");
        } else if (a.anxiety() > 0.5) {
            sb.append("You worry about the people you love. When they're out of sight, ");
            sb.append("something in you tightens. You need to know they're safe.");
        } else if (a.avoidance() < 0.3 && a.anxiety() < 0.3) {
            sb.append("You trust easily and feel comfortable with closeness. ");
            sb.append("When someone you care about is near, you feel settled.");
        } else {
            sb.append("You are cautious with closeness but capable of trust once earned.");
        }
        return ObservationSection.text("How You Bond", sb.toString());
    }


    private static ObservationSection adaptPromptSection(String text) {
        if (text.startsWith("## ")) {
            int end = text.indexOf('\n');
            if (end > 0) {
                return ObservationSection.text(text.substring(3, end).strip(), text.substring(end).strip());
            }
        }
        int nl = text.indexOf('\n');
        if (nl > 0) {
            return ObservationSection.text(text.substring(0, nl).strip(), text.substring(nl).strip());
        }
        return ObservationSection.text("Cognitive State", text);
    }


    private java.util.List<ObservationSection> renderTrustSections() {
        if (mindMapStore == null || trustEvolutionConfig == null || tenantId == null) return List.of();

        var summaries = new java.util.ArrayList<io.casehub.blocks.summarisation.observation.affordance.TrustSummary>();

        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var peopleSubgraph = subgraphs.stream()
            .filter(sg -> "people".equals(sg.name()))
            .findFirst();
        if (peopleSubgraph.isEmpty()) return List.of();

        var nodes = mindMapStore.nodesIn(peopleSubgraph.get().id(), tenantId);
        var sharedNodes = new java.util.HashMap<String, io.casehub.neocortex.mindmap.MindMapNode>();
        for (var node : nodes) {
            if (!node.traits().contains("overlay")) {
                node.property("agentId").ifPresent(aid -> sharedNodes.put(node.id(), node));
            }
        }

        for (var overlay : nodes) {
            if (!overlay.traits().contains("overlay")) continue;
            if (!agentId.equals(overlay.property(io.casehub.neocortex.mindmap.OverlayRef.AGENT_ID).orElse(null))) continue;

            var trustScoreStr = overlay.property(io.casehub.engine.trust.OverlayTrustPropertyModel.TRUST_SCORE);
            var alphaStr = overlay.property(io.casehub.engine.trust.OverlayTrustPropertyModel.TRUST_ALPHA);
            var betaStr = overlay.property(io.casehub.engine.trust.OverlayTrustPropertyModel.TRUST_BETA);
            if (trustScoreStr.isEmpty()) continue;

            double score = Double.parseDouble(trustScoreStr.get());
            double alpha = alphaStr.map(Double::parseDouble).orElse(1.0);
            double beta = betaStr.map(Double::parseDouble).orElse(1.0);

            if (alpha + beta <= 2.0) continue;

            String targetNodeId = io.casehub.neocortex.mindmap.OverlayRef.sharedNodeId(overlay).orElse(null);
            if (targetNodeId == null) continue;

            var sharedNode = sharedNodes.get(targetNodeId);
            if (sharedNode == null) continue;
            String subjectName = sharedNode.name();

            var levels = trustEvolutionConfig.levels();
            io.casehub.blocks.summarisation.observation.affordance.TrustLevel level;
            if (score >= levels.high()) level = io.casehub.blocks.summarisation.observation.affordance.TrustLevel.HIGH;
            else if (score >= levels.moderate()) level = io.casehub.blocks.summarisation.observation.affordance.TrustLevel.MODERATE;
            else level = io.casehub.blocks.summarisation.observation.affordance.TrustLevel.LOW;

            String reason = (alpha + beta > 10) ? "You feel quite certain about this"
                          : (alpha + beta < 4) ? "You're still forming an opinion"
                          : null;

            summaries.add(new io.casehub.blocks.summarisation.observation.affordance.TrustSummary(subjectName, level, reason));
        }

        if (summaries.isEmpty()) return List.of();
        return List.of(io.casehub.blocks.summarisation.observation.affordance.CognitiveObservationSections.trustSection(summaries));
    }


    java.util.List<SocialConfig.Drive> resolveAdaptedDrives() {
        if (mindMapStore == null || tenantId == null) {
            return socialConfig.drives();
        }
        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var driveNodes = subgraphs.stream()
                                  .filter(s -> "cognitive".equals(s.type()))
                                  .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                                  .filter(n -> "drive-intensity".equals(n.properties().get("cognitiveKind")))
                                  .filter(n -> agentId.equals(n.properties().get("agent-id")))
                                  .toList();
        if (driveNodes.isEmpty()) {
            return socialConfig.drives();
        }
        return driveNodes.stream()
                         .map(n -> new SocialConfig.Drive(
                                 n.properties().get("drive-type"),
                                 Double.parseDouble(n.properties().getOrDefault("intensity", "0")),
                                 n.properties().getOrDefault("description", "")))
                         .toList();
    }


    String behavioralCue(String stage) {
        boolean cooperate = contextStrategy.shouldCooperate(stage);
        boolean disclose  = contextStrategy.shouldDisclose(stage);
        if (!cooperate) {return null;}
        if (disclose) {return "Willing to cooperate and share openly";}
        return "Willing to cooperate";
    }

    List<ObservationSection> renderSocialAwareness(
            java.util.Collection<String> nearbyAgentIds,
            java.util.Map<String, String> agentNames) {
        if (cognitiveProfile == null || tenantId == null) {
            return List.of();
        }
        if (!contextStrategy.shouldCompareSocially(resolveAdaptedDrives(), false)) {
            return List.of();
        }

        var stageMap = new java.util.HashMap<String, String>();
        if (mindMapStore != null) {
            var subgraphs = mindMapStore.listSubgraphs(tenantId);
            var peopleSg = subgraphs.stream()
                                    .filter(sg -> "people".equals(sg.name())).findFirst();
            if (peopleSg.isPresent()) {
                var overlayNodes = mindMapStore.nodesIn(peopleSg.get().id(), tenantId);
                var sharedNodes  = new java.util.HashMap<String, String>();
                for (var node : overlayNodes) {
                    if (!node.traits().contains("overlay")) {
                        node.property("agentId").ifPresent(aid -> sharedNodes.put(node.id(), aid));
                    }
                }
                for (var node : overlayNodes) {
                    if (!node.traits().contains("overlay")) {continue;}
                    if (!agentId.equals(node.property(io.casehub.neocortex.mindmap.OverlayRef.AGENT_ID).orElse(null))) {
                        continue;
                    }
                    var targetNodeId = io.casehub.neocortex.mindmap.OverlayRef.sharedNodeId(node).orElse(null);
                    if (targetNodeId == null) {continue;}
                    var targetId = sharedNodes.get(targetNodeId);
                    if (targetId == null) {continue;}
                    var stage = node.property(io.casehub.neocortex.cognition.relationship.OverlayFamiliarityPropertyModel.FAMILIARITY_STAGE).orElse("stranger");
                    stageMap.put(targetId, stage);
                }
            }
        }

        var selfPrincipal = io.casehub.platform.api.identity.PrincipalId.agent(agentId);
        var lines         = new java.util.ArrayList<String>();

        for (String nearbyId : nearbyAgentIds) {
            var    otherPrincipal = io.casehub.platform.api.identity.PrincipalId.agent(nearbyId);
            String otherName      = agentNames.getOrDefault(nearbyId, nearbyId);
            String stage          = stageMap.getOrDefault(nearbyId, "stranger");

            String perceptionLine = null;
            try {
                var query = io.casehub.neocortex.cognitive.index.CognitiveProfileQuery
                                    .byName(nearbyId, tenantId);
                var perspectives = cognitiveProfile.compare(query,
                                                            java.util.Set.of(selfPrincipal, otherPrincipal));
                if (!perspectives.isEmpty()) {
                    var comparison = io.casehub.neocortex.cognitive.index.SocialComparison
                                             .compare(perspectives);
                    perceptionLine = PerceptionTranslator.translate(comparison, selfPrincipal, otherPrincipal, otherName, stage)
                                                         .orElse(null);
                }
            } catch (Exception e) {
                // graceful degradation
            }

            String behavioralCue = behavioralCue(stage);
            if (perceptionLine != null && behavioralCue != null) {
                lines.add(perceptionLine + ". " + behavioralCue);
            } else if (perceptionLine != null) {
                lines.add(perceptionLine);
            } else if (behavioralCue != null) {
                lines.add(otherName + " (" + stage + "): " + behavioralCue);
            }
        }

        if (lines.isEmpty()) {return List.of();}
        return List.of(ObservationSection.items("Social Awareness", null, lines));
    }

}
