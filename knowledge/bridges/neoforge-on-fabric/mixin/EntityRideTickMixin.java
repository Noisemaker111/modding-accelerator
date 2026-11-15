package ${package}.mixin;

import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's Entity patch: a passenger's tick in Entity.rideTick() posts EntityTickEvent.Pre before it (cancelling it
 * skips the tick) and EntityTickEvent.Post after it.
 */
@Mixin(Entity.class)
abstract class EntityRideTickMixin {

    @WrapOperation(method = "rideTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void neoforgeBridge$tick(Entity entity, Operation<Void> tick) {
        if (EventHooks.fireEntityTickPre(entity).isCanceled()) return;
        tick.call(entity);
        EventHooks.fireEntityTickPost(entity);
    }
}
