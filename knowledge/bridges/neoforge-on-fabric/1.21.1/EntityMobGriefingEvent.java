package ${package};

import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.EntityMobGriefingEvent, posted by EventHooks.canEntityGrief, which
 * mods call. NeoForge's patches also call it in place of the game's own mobGriefing checks; the bridge does not, so
 * the game's mobs follow the game rule alone.
 */
public class EntityMobGriefingEvent extends EntityEvent {

    private final boolean isMobGriefingEnabled;
    private boolean canGrief;

    public EntityMobGriefingEvent(Level level, Entity entity) {
        super(entity);
        this.isMobGriefingEnabled = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        this.canGrief = this.isMobGriefingEnabled;
    }

    public boolean isMobGriefingEnabled() {
        return this.isMobGriefingEnabled;
    }

    public void setCanGrief(boolean canGrief) {
        this.canGrief = canGrief;
    }

    public boolean canGrief() {
        return this.canGrief;
    }
}
