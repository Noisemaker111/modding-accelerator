package ${package};

import net.minecraft.world.entity.Entity;

/**
 * NeoForge's net.neoforged.neoforge.event.tick.EntityTickEvent, posted where NeoForge's patches post it: by the bridge
 * mixins around the entity's tick in ServerLevel.tickNonPassenger (EntityTickMixin), ClientLevel.tickNonPassenger
 * (ClientEntityTickMixin) and Entity.rideTick (EntityRideTickMixin). Cancelling Pre skips the entity's tick (on 26.x
 * its commonTick too) and Post, as on NeoForge.
 */
public abstract class EntityTickEvent extends EntityEvent {

    protected EntityTickEvent(Entity entity) {
        super(entity);
    }

    /** Fired once per game tick, per entity, before the entity ticks. Cancelling it skips the tick and Post. */
    public static class Pre extends EntityTickEvent implements ICancellableEvent {

        public Pre(Entity entity) {
            super(entity);
        }

        @Override
        public void setCanceled(boolean canceled) {
            ICancellableEvent.super.setCanceled(canceled);
        }
    }

    /** Fired once per game tick, per entity, after the entity ticked; not fired when Pre was cancelled. */
    public static class Post extends EntityTickEvent {

        public Post(Entity entity) {
            super(entity);
        }
    }
}
