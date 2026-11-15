package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.EntityJoinLevelEvent for server levels, posted from Fabric's
 * ServerEntityEvents.ALLOW_LOAD: cancelling it keeps the entity out of the level, as on NeoForge.
 */
public class EntityJoinLevelEvent extends EntityEvent implements ICancellableEvent {

    private final Level level;

    public EntityJoinLevelEvent(Entity entity, Level level) {
        super(entity);
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }
}
