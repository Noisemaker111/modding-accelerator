package ${package};

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent, posted by the bridge's
 * IncomingDamageMixin where NeoForge's LivingEntity patch posts it (in hurt, once the entity can be hurt by the source):
 * cancelling it stops the damage, and setAmount changes it, as on NeoForge.
 */
public class LivingIncomingDamageEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;
    private final float originalAmount;
    private float amount;

    public LivingIncomingDamageEvent(LivingEntity entity, DamageSource source, float amount) {
        super(entity);
        this.source = source;
        this.originalAmount = amount;
        this.amount = amount;
    }

    public DamageSource getSource() {
        return source;
    }

    public float getAmount() {
        return amount;
    }

    public void setAmount(float amount) {
        this.amount = amount;
    }

    public float getOriginalAmount() {
        return originalAmount;
    }
}
