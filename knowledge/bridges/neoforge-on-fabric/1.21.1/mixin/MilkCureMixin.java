package ${package}.mixin;

import ${package}.EffectCures;
import ${package}.extensions.EffectHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.MilkBucketItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge 21.1's MilkBucketItem patch: milk ends the effects milk cures (removeEffectsCuredBy), not every effect. */
@Mixin(MilkBucketItem.class)
abstract class MilkCureMixin {

    @WrapOperation(method = "finishUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;removeAllEffects()Z"))
    private boolean neoforgeBridge$milkCures(LivingEntity entity, Operation<Boolean> removeAll) {
        boolean removed = false;
        for (var instance : java.util.List.copyOf(entity.getActiveEffects())) {
            if (EffectHooks.curedBy(instance, EffectCures.MILK)) removed |= entity.removeEffect(instance.getEffect());
        }
        return removed;
    }
}
