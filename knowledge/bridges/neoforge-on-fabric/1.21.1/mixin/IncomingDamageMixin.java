package ${package}.mixin;

import ${package}.NeoForge;
import ${package}.LivingIncomingDamageEvent;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's LivingEntity patch: hurt posts LivingIncomingDamageEvent once the entity can be hurt by the source (after
 * the invulnerability, side, death and fire-resistance checks); cancelled, the entity is not hurt; a changed amount is
 * the damage from then on.
 */
@Mixin(LivingEntity.class)
abstract class IncomingDamageMixin {

    @Inject(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSleeping()Z", ordinal = 0), cancellable = true)
    private void neoforgeBridge$incomingDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> info, @Local(argsOnly = true) LocalFloatRef damage) {
        LivingIncomingDamageEvent event = NeoForge.EVENT_BUS.post(new LivingIncomingDamageEvent((LivingEntity) (Object) this, source, damage.get()));
        if (event.isCanceled()) info.setReturnValue(false);
        else damage.set(event.getAmount());
    }
}
