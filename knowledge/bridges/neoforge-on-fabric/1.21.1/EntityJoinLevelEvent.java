package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.EntityJoinLevelEvent, posted where NeoForge's patches post it:
 * for server levels at the head of PersistentEntitySectionManager.addEntity (EntityJoinLevelMixin; loadedFromDisk is
 * whether the entity came from the level's saved chunks), for the client level at the head of ClientLevel.addEntity
 * (ClientEntityJoinMixin). Cancelling it keeps the entity out of the level.
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
