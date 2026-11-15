package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ServerGamePacketListenerImpl patch: the interact packet handler's interact-at action posts
 * PlayerInteractEvent.EntityInteractSpecific (CommonHooks.onInteractEntityAt) before Entity.interactAt; when it is
 * cancelled its cancellation result stands for interactAt's (the swing and the advancement trigger still see it).
 * Minecraft 1.21.1 only (26.1 has no EntityInteractSpecific).
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl$1")
abstract class EntityInteractSpecificMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;interactAt(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private static InteractionResult neoforgeBridge$interactAt(Entity entity, Player player, Vec3 pos, InteractionHand hand, Operation<InteractionResult> interactAt) {
        InteractionResult cancelResult = CommonHooks.onInteractEntityAt(player, entity, pos, hand);
        if (cancelResult != null) return cancelResult;
        return interactAt.call(entity, player, pos, hand);
    }
}
