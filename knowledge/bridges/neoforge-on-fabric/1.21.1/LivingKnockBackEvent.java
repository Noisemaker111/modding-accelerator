package ${package};

import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent, posted by the bridge's KnockBackMixin
 * when an entity is knocked back (LivingEntity.knockback): a listener may change the strength and direction or cancel it.
 */
public class LivingKnockBackEvent extends LivingEvent implements ICancellableEvent {

    private final float originalStrength;
    private final double originalRatioX;
    private final double originalRatioZ;
    private float strength;
    private double ratioX;
    private double ratioZ;

    public LivingKnockBackEvent(LivingEntity target, float strength, double ratioX, double ratioZ) {
        super(target);
        this.strength = this.originalStrength = strength;
        this.ratioX = this.originalRatioX = ratioX;
        this.ratioZ = this.originalRatioZ = ratioZ;
    }

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }

    public double getRatioX() {
        return ratioX;
    }

    public void setRatioX(double ratioX) {
        this.ratioX = ratioX;
    }

    public double getRatioZ() {
        return ratioZ;
    }

    public void setRatioZ(double ratioZ) {
        this.ratioZ = ratioZ;
    }

    public float getOriginalStrength() {
        return originalStrength;
    }

    public double getOriginalRatioX() {
        return originalRatioX;
    }

    public double getOriginalRatioZ() {
        return originalRatioZ;
    }
}
