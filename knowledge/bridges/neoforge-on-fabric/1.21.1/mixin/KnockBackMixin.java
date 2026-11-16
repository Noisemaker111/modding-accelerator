package ${package}.mixin;

import ${package}.NeoForge;
import ${package}.LivingKnockBackEvent;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge 21.1's LivingEntity patch: knockback posts LivingKnockBackEvent first; cancelled, there is none; else its values are used. */
@Mixin(LivingEntity.class)
abstract class KnockBackMixin {

    @Inject(method = "knockback(DDD)V", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$knockBack(double strength, double x, double z, CallbackInfo info, @Local(ordinal = 0, argsOnly = true) LocalDoubleRef strengthRef, @Local(ordinal = 1, argsOnly = true) LocalDoubleRef xRef, @Local(ordinal = 2, argsOnly = true) LocalDoubleRef zRef) {
        LivingKnockBackEvent event = NeoForge.EVENT_BUS.post(new LivingKnockBackEvent((LivingEntity) (Object) this, (float) strength, x, z));
        if (event.isCanceled()) {
            info.cancel();
            return;
        }
        strengthRef.set(event.getStrength());
        xRef.set(event.getRatioX());
        zRef.set(event.getRatioZ());
    }
}
