package ${package};

import net.minecraft.world.entity.Entity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.EntityEvent: the base of the entity events this bridge posts.
 * EntityConstructing is posted at the end of every entity's constructor, as NeoForge's Entity patch does
 * (mixin/EntityConstructingMixin).
 */
public abstract class EntityEvent extends Event {

    private final Entity entity;

    public EntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }

    public static class EntityConstructing extends EntityEvent {

        public EntityConstructing(Entity entity) {
            super(entity);
        }

        /** Posts the event on the bridge's bus (for the bridge's mixin, which is in another package). */
        public static void post(Entity entity) {
            Bus.INSTANCE.post(new EntityConstructing(entity));
        }
    }
}
