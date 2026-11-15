package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.EntityJoinLevelEvent. For server levels it is posted from Fabric's
 * ServerEntityEvents.ALLOW_LOAD, which Fabric fires where NeoForge's PersistentEntitySectionManager patch posts the
 * event (loadedFromDisk is its isLoadedFromDisk); for the client level from ClientEntityJoinMixin, at the head of
 * ClientLevel.addEntity as NeoForge's ClientLevel patch does. Cancelling it keeps the entity out of the level.
 */
public class EntityJoinLevelEvent extends EntityEvent implements ICancellableEvent {

    private final Level level;
    private final boolean loadedFromDisk;

    public EntityJoinLevelEvent(Entity entity, Level level) {
        this(entity, level, false);
    }

    public EntityJoinLevelEvent(Entity entity, Level level, boolean loadedFromDisk) {
        super(entity);
        this.level = level;
        this.loadedFromDisk = loadedFromDisk;
    }

    public Level getLevel() {
        return level;
    }

    /** Whether the entity was read from the level's saved chunks rather than newly spawned. */
    public boolean loadedFromDisk() {
        return loadedFromDisk;
    }
}
