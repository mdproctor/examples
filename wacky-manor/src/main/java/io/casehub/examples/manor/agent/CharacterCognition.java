package io.casehub.examples.manor.agent;

import io.casehub.agency.perception.observation.affordance.ObservationSection;
import io.casehub.eidos.api.AgentConstraint;
import io.casehub.examples.manor.model.ActionType;
import io.casehub.examples.manor.model.CharacterState;
import io.casehub.neocortex.cognition.prompt.observation.CognitionObservationBuilder;
import io.casehub.neocortex.cognition.prompt.observation.DriveSnapshot;
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
    private final io.casehub.neocortex.cognition.core.CognitionCore     cognitionCore;
    private final ManorCognitiveSeeder.SeedResult                       seedResult;
    private final String                                                tenantId;
    private final io.casehub.neocortex.mindmap.MindMapStore             mindMapStore;
    private final io.casehub.engine.trust.TrustEvolutionConfig          trustEvolutionConfig;
    private final io.casehub.neocortex.caps.SituationClassifier         situationClassifier;
    private final CognitionObservationBuilder                           cognitiveObservation;
    private final boolean                                              briefingMode;


    public CharacterCognition(String agentId, AgentExperienceService experienceService) {
        this(agentId, experienceService, null, SocialConfig.empty(), List.of(),
             null, new ManorContextStrategy(), null, null, null, null, null, null, true);
    }

    public CharacterCognition(String agentId, AgentExperienceService experienceService,
                              CognitiveDefaults cognitiveDefaults, SocialConfig socialConfig,
                              List<AgentConstraint> constraints) {
        this(agentId, experienceService, cognitiveDefaults, socialConfig, constraints,
             null, new ManorContextStrategy(), null, null, null, null, null, null, true);
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
                              io.casehub.engine.trust.TrustEvolutionConfig trustEvolutionConfig,
                              io.casehub.neocortex.caps.SituationClassifier situationClassifier,
                              boolean briefingMode) {
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
        this.situationClassifier    = situationClassifier;
        this.briefingMode           = briefingMode;
        this.cognitiveObservation   = new CognitionObservationBuilder(
                agentId, tenantId, mindMapStore, cognitionCore, situationClassifier,
                toDriveSnapshots(this.socialConfig.drives(), briefingMode));
    }

    public String agentId() {return agentId;}

    public CognitionObservationBuilder cognitiveObservation() { return cognitiveObservation; }

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

    public void observeCharacterState(String characterId,
                                      io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType behavior,
                                      io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel threat) {
        cognitiveObservation.observeCharacterState(characterId, behavior, threat);
    }




    public io.casehub.neocortex.caps.SituationContext classifySituation(String situationText, Collection<String> nearbyAgentIds) {
        var metadata = buildSituationMetadata(nearbyAgentIds);
        return cognitiveObservation.classifySituation(situationText, metadata);
    }

    // Section ordering follows Lazarus (1991) appraisal process flow
    public List<ObservationSection> renderCognitiveSections(
            CharacterState character,
            Collection<String> nearbyAgentIds,
            Map<String, String> agentNames) {
        return renderCognitiveSections(character, nearbyAgentIds, agentNames,
                                       classifySituation(character.lastActionResult(), nearbyAgentIds));
    }

    public List<ObservationSection> renderCognitiveSections(
            CharacterState character,
            Collection<String> nearbyAgentIds,
            Map<String, String> agentNames,
            io.casehub.neocortex.caps.SituationContext situationContext) {
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
                    var beliefs     = new java.util.ArrayList<io.casehub.agency.deliberation.belief.Belief<String>>();
                    for (var node : beliefNodes) {
                        var key          = node.property("subject").orElse(node.name());
                        var value        = node.name();
                        int entrenchment = (int) (node.confidence().value() * 10);
                        beliefs.add(io.casehub.agency.deliberation.belief.Belief.of(key, value, entrenchment));
                        if ("belief-revision".equals(node.provenance())) {
                            revisedKeys.add(key);
                        }
                    }
                    sections.add(io.casehub.agency.perception.observation.affordance
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

        for (var s : cognitiveObservation.renderTheoryOfMind(nearbyAgentIds, agentNames)) {
            sections.add(toAgency(s));
        }

        if (briefingMode && !socialConfig.personalityFacets().isEmpty()) {
            sections.add(renderPersonalityFacets());
        } else if (!briefingMode && !socialConfig.personalityFacets().isEmpty()) {
            sections.add(renderPersonalityFacetsStructured());
        }

        var activatedCategories = (cognitionCore != null)
                                  ? cognitionCore.activatedDriveCategories()
                                  : java.util.List.<String>of();

        if (!briefingMode) {
            sections.add(ObservationSection.text("How to Feel",
                    "Your body is already responding to this moment. The drives below are patterns " +
                    "your body falls into — not decisions you make. Notice what is already happening " +
                    "before you have a word for it.\n\n" +
                    "HOW YOUR BODY IS CALIBRATED\n" +
                    "Your personality scores are not opinions — they are how your body is wired. " +
                    "A high score means that disposition lives in your muscles, posture, breathing, " +
                    "gut. A low score means your body is quiet where others are loud. Let the scores " +
                    "shape the body's response, not your thoughts about it.\n\n" +
                    "Your attachment style is the ground state your body returns to between events. " +
                    "Avoidant bodies pull away from warmth and find calm in control. Secure bodies " +
                    "settle into closeness and unsettle in absence. Anxious bodies amplify every " +
                    "signal and treat absence as alarm. This ground colors every drive above it.\n\n" +
                    "FOUR CHANNELS\n" +
                    "1. Body — muscles, gut, chest, jaw, hands, breathing, skin, posture. What is " +
                    "happening right now, before you name it? Where exactly in the body?\n" +
                    "2. Attention — what has seized your focus? What have you stopped noticing? " +
                    "What are you scanning for involuntarily?\n" +
                    "3. Time — has this moment compressed, stretched, frozen, accelerated?\n" +
                    "4. Action readiness — what is your body preparing to do before you have " +
                    "decided? Lean forward, pull back, square up, reach, go still?\n\n" +
                    "CONTEXT SHAPES FEELING\n" +
                    "Each person present shifts your body differently — trust settles breathing, " +
                    "threat tightens shoulders, someone you want to impress changes your posture " +
                    "before you know it. Notice which person your body is tracking and how it " +
                    "shifts when they move or speak.\n\n" +
                    "The physical space matters — cold contracts muscles, warmth loosens them, " +
                    "darkness sharpens hearing, enclosed spaces compress the chest.\n\n" +
                    "STRUCTURE IN YOUR ASIDE\n" +
                    "Write as the body discovering this scene — not as a mind analysing it. " +
                    "Start with what your body notices first, before you have words for it.\n\n" +
                    "Each person present changes something in your posture, breathing, or " +
                    "attention. Bold the scene anchor — **a person acting**, **a sensation " +
                    "arriving**, **a body territory waking** — and let the drives surface " +
                    "as reactions to what is happening, not as a catalog.\n\n" +
                    "Each pull below names a body territory. When that territory activates, " +
                    "it is because someone or something in the scene triggered it. Multiple " +
                    "drives can share a paragraph when the body cannot separate them.\n\n" +
                    "Use discovery verbs — arrives, settles, pools, fizzes, shifts, tightens " +
                    "— not analytical verbs like calculates, assesses, determines.\n\n" +
                    "Your body state may be ambiguous. Racing heart could be excitement or " +
                    "fear. Tightness could be grief or fury. Stay with the uncertainty — " +
                    "do not resolve it.\n\n" +
                    "Do not conclude. Do not explain. Discover."));
        }

        var internalState = cognitiveObservation.renderInternalState(
                activatedCategories, character.lastActionResult(), nearbyAgentIds, situationContext, agentNames);
        if (internalState != null) {
            sections.add(toAgency(internalState));
        }

        if (!socialConfig.formationMemories().isEmpty()) {
            sections.add(renderFormationMemories());
        }

        if (briefingMode && socialConfig.substrate() != null && !socialConfig.substrate().isBlank()) {
            sections.add(ObservationSection.text("What Shaped You", socialConfig.substrate()));
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

        for (var s : cognitiveObservation.renderCognitionCoreSections()) {
            sections.add(toAgency(s));
        }

        var personaSection = PersonaActivationSection.resolve(
                socialConfig.personaConstraint(), nearbyAgentIds);
        if (personaSection != null) {
            sections.add(personaSection);
        }

        return sections;
    }


    public List<String> renderTaggedSections() {
        return cognitiveObservation.renderTaggedSections();
    }

    private ObservationSection renderFormationMemories() {
        var sb = new StringBuilder();
        sb.append("These are the experiences that made you who you are.\n\n");
        // Salience ordering: abs(valence) + arousal — Menon (2015) salience network
        var sorted = socialConfig.formationMemories().stream()
                                 .sorted(java.util.Comparator.comparingDouble(
                                         (SocialConfig.FormationMemory m) -> Math.abs(m.pleasure()) + m.arousal()).reversed())
                                 .toList();
        for (var mem : sorted) {
            sb.append("**Age ").append(mem.age()).append(":** ")
              .append(FormationMemoryParser.stripAnnotations(mem.episode())).append("\n\n");
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

    private ObservationSection renderPersonalityFacetsStructured() {
        var items = socialConfig.personalityFacets().stream()
                .map(f -> f.facet() + ": " + f.score() + "/100")
                .toList();
        return ObservationSection.items("Who You Are", null, items);
    }

    ObservationSection renderInternalState(List<String> activatedCategories, String situationText) {
        return renderInternalState(activatedCategories, situationText, List.of());
    }

    ObservationSection renderInternalState(List<String> activatedCategories, String situationText,
                                           Collection<String> nearbyAgentIds) {
        var ctx = classifySituation(situationText, nearbyAgentIds);
        return renderInternalState(activatedCategories, situationText, nearbyAgentIds, ctx);
    }

    ObservationSection renderInternalState(List<String> activatedCategories, String situationText,
                                           Collection<String> nearbyAgentIds,
                                           io.casehub.neocortex.caps.SituationContext situationContext) {
        var result = cognitiveObservation.renderInternalState(
                activatedCategories, situationText, nearbyAgentIds, situationContext);
        return result != null ? toAgency(result) : null;
    }

    List<String> selectSituationPrimedMemories(
            io.casehub.neocortex.caps.SituationContext situationContext,
            List<String> activatedDriveTypes) {
        return cognitiveObservation.selectSituationPrimedMemories(situationContext, activatedDriveTypes);
    }

    Map<String, String> buildSituationMetadata(Collection<String> nearbyAgentIds) {
        if (nearbyAgentIds == null || nearbyAgentIds.isEmpty()) return Map.of();
        var nearby = new java.util.StringJoiner(", ");
        for (var schema : socialConfig.relationalSchemas()) {
            if (nearbyAgentIds.contains(schema.target())) {
                nearby.add(schema.target() + " (" + schema.role() + ")");
            }
        }
        String nearbyStr = nearby.toString();
        return nearbyStr.isEmpty() ? Map.of() : Map.of("nearby-characters", nearbyStr);
    }

    List<DriveSnapshot> appraisalFilterDrives(
            List<DriveSnapshot> eligible,
            io.casehub.neocortex.caps.SituationContext situationContext,
            Collection<String> nearbyAgentIds) {
        return cognitiveObservation.appraisalFilterDrives(eligible, situationContext, nearbyAgentIds);
    }


    private ObservationSection renderRelationalSchemas(Collection<String> nearbyAgentIds, Map<String, String> agentNames) {
        if (mindMapStore != null && tenantId != null) {
            var evolved = renderEvolvedRelationalSchemas(nearbyAgentIds, agentNames);
            if (evolved != null) {return evolved;}
        }
        return renderRelationalSchemasFromConfig(nearbyAgentIds, agentNames);
    }

    private ObservationSection renderEvolvedRelationalSchemas(Collection<String> nearbyAgentIds, Map<String, String> agentNames) {
        var peopleSg = mindMapStore.listSubgraphs(tenantId).stream()
                                   .filter(sg -> "people".equals(sg.name()))
                                   .findFirst();
        if (peopleSg.isEmpty()) {return null;}

        var nodes         = mindMapStore.nodesIn(peopleSg.get().id(), tenantId);
        var agentToNodeId = new java.util.HashMap<String, String>();
        for (var node : nodes) {
            if (!node.traits().contains("overlay")) {
                node.property("agentId").ifPresent(aid -> {
                    if (nearbyAgentIds.contains(aid)) {agentToNodeId.put(aid, node.id());}
                });
            }
        }

        var     sb    = new StringBuilder();
        boolean found = false;
        for (var overlay : nodes) {
            if (!overlay.traits().contains("overlay")) {continue;}
            if (!agentId.equals(overlay.property(io.casehub.neocortex.mindmap.OverlayRef.AGENT_ID).orElse(null))) {
                continue;
            }
            var sharedId = io.casehub.neocortex.mindmap.OverlayRef.sharedNodeId(overlay).orElse(null);
            for (var entry : agentToNodeId.entrySet()) {
                if (entry.getValue().equals(sharedId)) {
                    var schema = io.casehub.neocortex.cognitive.index.RelationalSchema.fromProperties(overlay.properties());
                    if (schema != null && schema.description() != null && !schema.description().isEmpty()) {
                        var displayName = agentNames.getOrDefault(entry.getKey(), entry.getKey());
                        sb.append("**").append(displayName).append(":** ").append(schema.description()).append("\n");
                        found = true;
                    }
                }
            }
        }

        if (!found) {return null;}
        return ObservationSection.text("How You Feel About Those Present", sb.toString().strip());
    }

    private ObservationSection renderRelationalSchemasFromConfig(Collection<String> nearbyAgentIds, Map<String, String> agentNames) {
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


    // Attachment quadrants — Bartholomew & Horowitz (1991) anxiety × avoidance two-dimensional model
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





    private java.util.List<ObservationSection> renderTrustSections() {
        if (mindMapStore == null || trustEvolutionConfig == null || tenantId == null) return List.of();

        var summaries = new java.util.ArrayList<io.casehub.agency.perception.observation.affordance.TrustSummary>();

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

            // Uninformative Beta prior — alpha=beta=1, sum≤2 means no evidence (Jøsang 2016, Subjective Logic)
            if (alpha + beta <= 2.0) continue;

            String targetNodeId = io.casehub.neocortex.mindmap.OverlayRef.sharedNodeId(overlay).orElse(null);
            if (targetNodeId == null) continue;

            var sharedNode = sharedNodes.get(targetNodeId);
            if (sharedNode == null) continue;
            String subjectName = sharedNode.name();

            var levels = trustEvolutionConfig.levels();
            io.casehub.agency.perception.observation.affordance.TrustLevel level;
            if (score >= levels.high()) level = io.casehub.agency.perception.observation.affordance.TrustLevel.HIGH;
            else if (score >= levels.moderate()) level = io.casehub.agency.perception.observation.affordance.TrustLevel.MODERATE;
            else level = io.casehub.agency.perception.observation.affordance.TrustLevel.LOW;

            // Beta posterior concentration (Kruschke 2015) — sum>10 narrow posterior, <4 wide
            String reason = (alpha + beta > 10) ? "You feel quite certain about this"
                          : (alpha + beta < 4) ? "You're still forming an opinion"
                          : null;

            summaries.add(new io.casehub.agency.perception.observation.affordance.TrustSummary(subjectName, level, reason));
        }

        if (summaries.isEmpty()) return List.of();
        return List.of(io.casehub.agency.perception.observation.affordance.CognitiveObservationSections.trustSection(summaries));
    }


    List<DriveSnapshot> resolveAdaptedDrives() {
        return cognitiveObservation.resolveAdaptedDrives();
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
        if (!contextStrategy.shouldCompareSociallyBySnapshot(resolveAdaptedDrives())) {
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

    // --- Type conversion ---

    static List<DriveSnapshot> toDriveSnapshots(List<SocialConfig.Drive> drives) {
        return toDriveSnapshots(drives, true);
    }

    static List<DriveSnapshot> toDriveSnapshots(List<SocialConfig.Drive> drives, boolean briefingMode) {
        if (drives == null || drives.isEmpty()) return List.of();
        return drives.stream()
                .map(d -> {
                    String desc = briefingMode
                            ? d.description()
                            : "Drive: " + d.type() + " (intensity " + String.format("%.1f", d.intensity()) + ")";
                    return new DriveSnapshot(d.type(), d.intensity(), desc, d.context());
                })
                .toList();
    }

    private static ObservationSection toAgency(
            io.casehub.neocortex.cognition.prompt.observation.ObservationSection section) {
        return switch (section) {
            case io.casehub.neocortex.cognition.prompt.observation.ObservationSection.TextBlock tb ->
                    ObservationSection.text(tb.header(), tb.content());
            case io.casehub.neocortex.cognition.prompt.observation.ObservationSection.ItemList il ->
                    ObservationSection.items(il.header(), il.emptyMessage(), il.items());
            case io.casehub.neocortex.cognition.prompt.observation.ObservationSection.EntityGroup eg ->
                    ObservationSection.text(eg.header(), eg.entities().toString());
            case io.casehub.neocortex.cognition.prompt.observation.AnnotatedSection as ->
                    toAgency(as.section());
        };
    }
}
