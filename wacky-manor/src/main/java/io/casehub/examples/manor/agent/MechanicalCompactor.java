package io.casehub.examples.manor.agent;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.examples.manor.model.ManorEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

public final class MechanicalCompactor implements io.casehub.blocks.summarisation.Compactor<ManorEvent> {

    @Override
    public List<LevelEvent<ManorEvent>> compact(List<LevelEvent<ManorEvent>> events) {
        if (events.isEmpty()) {return List.of();}

        var latest       = new LinkedHashMap<String, LevelEvent<ManorEvent>>();
        var dialogueSeen = new HashSet<String>();
        var result       = new ArrayList<LevelEvent<ManorEvent>>();

        for (var event : events) {
            ManorEvent e = event.payload();
            if (e.actionType() != null) {
                String key = supersessionKey(e);
                if (key != null) {
                    latest.put(key, event);
                    continue;
                }
            }
            if ("dialogue".equals(e.type())) {
                String dedupKey = e.characterId() + "::" + e.description();
                if (!dialogueSeen.add(dedupKey)) {continue;}
            }
            result.add(event);
        }
        result.addAll(latest.values());
        result.sort(Comparator.comparingLong(LevelEvent::timestamp));
        return List.copyOf(result);
    }

    private String supersessionKey(ManorEvent e) {
        return switch (e.actionType()) {
            case MOVE -> "move:" + e.characterId();
            case TAKE -> "take:" + e.target();
            case INTERACT, USE -> "object-state:" + e.target();
            default -> null;
        };
    }
}
