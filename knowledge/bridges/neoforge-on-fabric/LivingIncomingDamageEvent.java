package ${package};

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent, posted from Fabric's
 * ServerLivingEntityEvents.ALLOW_DAMAGE on the server: cancelling it stops the damage, as on NeoForge. Fabric's callback
 * cannot change the amount, so setAmount is not mirrored: a mod that calls it names it when it compiles.
 */
public class LivingIncomingDamageEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;
    private final float amount;

    public LivingIncomingDamageEvent(LivingEntity entity, DamageSource source, float amount) {
        super(entity);
        this.source = source;
        this.amount = amount;
    }

    public DamageSource getSource() {
        return source;
    }

    public float getAmount() {
        return amount;
    }

    public float getOriginalAmount() {
        return amount;
    }
}
