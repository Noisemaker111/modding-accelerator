package ${package};

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.entity.living.LivingDamageEvent, posted by the bridge's ActualDamageMixin
 * where NeoForge's LivingEntity patch posts it: Pre once armour, enchantments and absorption have reduced the damage
 * (setNewDamage changes what the entity loses), Post once its health has dropped.
 */
public abstract class LivingDamageEvent extends LivingEvent {

    private final DamageSource source;

    protected LivingDamageEvent(LivingEntity entity, DamageSource source) {
        super(entity);
        this.source = source;
    }

    public DamageSource getSource() {
        return source;
    }

    public static class Pre extends LivingDamageEvent {
        private final float originalDamage;
        private float newDamage;

        public Pre(LivingEntity entity, DamageSource source, float damage) {
            super(entity, source);
            this.originalDamage = damage;
            this.newDamage = damage;
        }

        public float getOriginalDamage() {
            return originalDamage;
        }

        public float getNewDamage() {
            return newDamage;
        }

        public void setNewDamage(float newDamage) {
            this.newDamage = newDamage;
        }
    }

    public static class Post extends LivingDamageEvent {
        private final float newDamage;

        public Post(LivingEntity entity, DamageSource source, float damage) {
            super(entity, source);
            this.newDamage = damage;
        }

        public float getNewDamage() {
            return newDamage;
        }
    }
}
