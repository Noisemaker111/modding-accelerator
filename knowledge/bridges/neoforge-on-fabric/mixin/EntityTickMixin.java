package ${package}.mixin;

import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's ServerLevel patch: ServerLevel.tickNonPassenger posts EntityTickEvent.Pre before entity.commonTick() and
 * entity.tick(), skips both when it is cancelled, and posts EntityTickEvent.Post after them.
 */
@Mixin(ServerLevel.class)
abstract class EntityTickMixin {

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;commonTick()V"))
    private void neoforgeBridge$commonTick(Entity entity, Operation<Void> commonTick, @Share("canceled") LocalBooleanRef canceled) {
        canceled.set(EventHooks.fireEntityTickPre(entity).isCanceled());
        if (!canceled.get()) commonTick.call(entity);
    }

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void neoforgeBridge$tick(Entity entity, Operation<Void> tick, @Share("canceled") LocalBooleanRef canceled) {
        if (canceled.get()) return;
        tick.call(entity);
        EventHooks.fireEntityTickPost(entity);
    }
}
