package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.EntityJoinLevelEvent for server levels, posted from Fabric's
 * ServerEntityEvents.ENTITY_LOAD once the entity is in the level. Fabric API for 1.21.1 has no earlier event, so it
 * cannot be cancelled here: a mod that cancels it names setCanceled when it compiles.
 */
public class EntityJoinLevelEvent extends EntityEvent {

    private final Level level;

    public EntityJoinLevelEvent(Entity entity, Level level) {
        super(entity);
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }
}
