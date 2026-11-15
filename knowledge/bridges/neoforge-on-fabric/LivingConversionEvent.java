package ${package};

import java.util.function.Consumer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingConversionEvent, posted by EventHooks.canLivingConvert
 * (Pre) and onLivingConvert (Post), which mods call. NeoForge's patches also post them from the game's own conversions
 * (a zombie villager cured, a pig struck by lightning); the bridge does not.
 */
public abstract class LivingConversionEvent extends LivingEvent {

    public LivingConversionEvent(LivingEntity entity) {
        super(entity);
    }

    /** Before an entity converts; cancelling it stops the conversion, and setConversionTimer delays it. */
    public static class Pre extends LivingConversionEvent implements ICancellableEvent {

        private final EntityType<? extends LivingEntity> outcome;
        private final Consumer<Integer> timer;

        public Pre(LivingEntity entity, EntityType<? extends LivingEntity> outcome, Consumer<Integer> timer) {
            super(entity);
            this.outcome = outcome;
            this.timer = timer;
        }

        public EntityType<? extends LivingEntity> getOutcome() {
            return outcome;
        }

        public void setConversionTimer(int ticks) {
            timer.accept(ticks);
        }
    }

    /** After an entity converted, with the entity it became. */
    public static class Post extends LivingConversionEvent {

        private final LivingEntity outcome;

        public Post(LivingEntity entity, LivingEntity outcome) {
            super(entity);
            this.outcome = outcome;
        }

        public LivingEntity getOutcome() {
            return outcome;
        }
    }
}
