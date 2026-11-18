package ${package}.mixin;

import ${package}.EffectCures;
import ${package}.extensions.EffectHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge 21.1's LivingEntity patch: a totem ends the effects it cures (PROTECTED_BY_TOTEM), not every effect. */
@Mixin(LivingEntity.class)
abstract class TotemCureMixin {

    @WrapOperation(method = "checkTotemDeathProtection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;removeAllEffects()Z"))
    private boolean neoforgeBridge$totemCures(LivingEntity entity, Operation<Boolean> removeAll) {
        boolean removed = false;
        for (var instance : java.util.List.copyOf(entity.getActiveEffects())) {
            if (EffectHooks.curedBy(instance, EffectCures.PROTECTED_BY_TOTEM)) removed |= entity.removeEffect(instance.getEffect());
        }
        return removed;
    }
}
