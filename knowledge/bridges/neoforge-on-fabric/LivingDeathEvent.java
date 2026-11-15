package ${package};

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingDeathEvent, posted from Fabric's
 * ServerLivingEntityEvents.ALLOW_DEATH on the server: cancelling it keeps the entity alive, as on NeoForge.
 */
public class LivingDeathEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;

    public LivingDeathEvent(LivingEntity entity, DamageSource source) {
        super(entity);
        this.source = source;
    }

    public DamageSource getSource() {
        return source;
    }
}
