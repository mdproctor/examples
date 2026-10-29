package io.casehub.examples.manor.engine;

import io.casehub.examples.manor.model.TriggerCondition;

public final class ConditionMatcher {

    public boolean matches(TriggerCondition condition, WorldState world) {
        return switch (condition) {
            case TriggerCondition.CharacterInRoom c -> {
                var ch = world.character(c.character());
                yield ch != null && c.room().equals(ch.currentRoom());
            }
            case TriggerCondition.CharacterHasItem c -> {
                var ch = world.character(c.character());
                yield ch != null && ch.hasItem(c.item());
            }
            case TriggerCondition.ObjectInRoom o ->
                world.room(o.room()) != null &&
                    world.room(o.room()).objects().containsKey(o.object());
            case TriggerCondition.SceneCompleted s ->
                world.isSceneCompleted(s.sceneId());
            case TriggerCondition.AllOf all ->
                all.conditions().stream().allMatch(c -> matches(c, world));
        };
    }
}
