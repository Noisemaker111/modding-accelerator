package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent: an entity removed from a level or unloaded
 * with its chunk, posted from Fabric's ServerEntityEvents.ENTITY_UNLOAD and ClientEntityEvents.ENTITY_UNLOAD, which fire
 * where NeoForge posts it (the levels' entity callbacks, when an entity stops being tracked). Not cancellable.
 */
public class EntityLeaveLevelEvent extends EntityEvent {

    private final Level level;

    public EntityLeaveLevelEvent(Entity entity, Level level) {
        super(entity);
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }
}
