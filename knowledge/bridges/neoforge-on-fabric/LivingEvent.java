package ${package};

import net.minecraft.world.entity.LivingEntity;

/** NeoForge's net.neoforged.neoforge.event.entity.living.LivingEvent. */
public abstract class LivingEvent extends EntityEvent {

    public LivingEvent(LivingEntity entity) {
        super(entity);
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }
}
