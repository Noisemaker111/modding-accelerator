package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's Minecraft patch: startAttack posts PlayerInteractEvent.LeftClickEmpty when the attack hit nothing (after
 * the attack strength reset); startUseItem posts RightClickEmpty for a hand that holds nothing when the use hit nothing,
 * before the game tries the hand's item.
 */
@Mixin(Minecraft.class)
abstract class EmptyClickMixin {

    @Shadow
    public LocalPlayer player;

    @Shadow
    public HitResult hitResult;

    @Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;resetAttackStrengthTicker()V", shift = At.Shift.AFTER))
    private void neoforgeBridge$leftClickEmpty(CallbackInfoReturnable<Boolean> cir) {
        CommonHooks.onEmptyLeftClick(this.player);
    }

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z", ordinal = 1))
    private void neoforgeBridge$rightClickEmpty(CallbackInfo ci, @Local InteractionHand hand, @Local ItemStack heldItem) {
        if (heldItem.isEmpty() && (this.hitResult == null || this.hitResult.getType() == HitResult.Type.MISS)) {
            CommonHooks.onEmptyClick(this.player, hand);
        }
    }
}
