package ${package}.mixin;

import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ClientLevel patch: ClientLevel.tickNonPassenger posts EntityTickEvent.Pre before entity.tick(),
 * skips the tick when it is cancelled, and posts EntityTickEvent.Post after it.
 */
@Mixin(ClientLevel.class)
abstract class ClientEntityTickMixin {

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void neoforgeBridge$tick(Entity entity, Operation<Void> tick) {
        if (EventHooks.fireEntityTickPre(entity).isCanceled()) return;
        tick.call(entity);
        EventHooks.fireEntityTickPost(entity);
    }
}
