package ${package}.mixin;

import ${package}.CommonHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's patches post PlayerInteractEvent.EntityInteract just before the server's interact packet handler and
 * the client's MultiPlayerGameMode.interact call Player.interactOn; here it is posted first in interactOn, which only
 * those two call. When it is cancelled its cancellation result is returned and the entity is not interacted with.
 */
@Mixin(Player.class)
abstract class EntityInteractMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$entityInteract(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult cancelResult = CommonHooks.onInteractEntity((Player) (Object) this, entity, hand);
        if (cancelResult != null) cir.setReturnValue(cancelResult);
    }
}
