package ${package}.mixin;

import ${package}.CommonHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's Player patch: interactOn posts PlayerInteractEvent.EntityInteract after the spectator check, on both sides;
 * when it is cancelled its cancellation result is returned and the entity is not interacted with.
 */
@Mixin(Player.class)
abstract class EntityInteractMixin {

    @Inject(method = "interactOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;", ordinal = 0), cancellable = true)
    private void neoforgeBridge$entityInteract(Entity entity, InteractionHand hand, Vec3 location, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult cancelResult = CommonHooks.onInteractEntity((Player) (Object) this, entity, hand, location);
        if (cancelResult != null) cir.setReturnValue(cancelResult);
    }
}
