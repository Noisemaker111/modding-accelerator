package ${package};

import net.minecraft.world.entity.Entity;

/** NeoForge's net.neoforged.neoforge.event.entity.EntityEvent: the base of the entity events this bridge posts. */
public abstract class EntityEvent extends Event {

    private final Entity entity;

    public EntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }
}
