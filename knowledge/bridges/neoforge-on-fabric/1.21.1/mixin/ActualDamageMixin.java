package ${package}.mixin;

import ${package}.NeoForge;
import ${package}.LivingDamageEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 21.1's LivingEntity patch: actuallyHurt posts LivingDamageEvent.Pre once armour, enchantments and absorption
 * have reduced the damage (its new damage is what the entity loses), and LivingDamageEvent.Post once its health dropped.
 */
@Mixin(LivingEntity.class)
abstract class ActualDamageMixin {

    @ModifyVariable(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setAbsorptionAmount(F)V", ordinal = 0, shift = At.Shift.AFTER), argsOnly = true)
    private float neoforgeBridge$damagePre(float damage, DamageSource source) {
        return NeoForge.EVENT_BUS.post(new LivingDamageEvent.Pre((LivingEntity) (Object) this, source, damage)).getNewDamage();
    }

    @Inject(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;gameEvent(Lnet/minecraft/core/Holder;)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$damagePost(DamageSource source, float damage, CallbackInfo info) {
        NeoForge.EVENT_BUS.post(new LivingDamageEvent.Post((LivingEntity) (Object) this, source, damage));
    }
}
