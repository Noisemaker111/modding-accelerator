package ${package}.mixin;

import ${package}.CommonHooks;
import ${package}.PlayerInteractEvent;
import ${package}.TriState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's ServerPlayerGameMode patch: handleBlockBreakAction posts PlayerInteractEvent.LeftClickBlock first (cancelled:
 * the action is ignored; useBlock FALSE: Block.attack is skipped); useItem posts RightClickItem after the spectator and
 * cooldown checks (cancelled: its result is returned, the item is not used); useItemOn posts RightClickBlock after the
 * enabled-block check (cancelled: its result is returned; useBlock TRUE uses the block even when sneaking, FALSE skips
 * it; useItem TRUE uses the item even on cooldown, FALSE skips it and returns PASS).
 */
@Mixin(ServerPlayerGameMode.class)
abstract class PlayerInteractMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$leftClickBlock(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxY, int sequence, CallbackInfo ci, @Share("leftClick") LocalRef<PlayerInteractEvent.LeftClickBlock> event) {
        event.set(CommonHooks.onLeftClickBlock(this.player, pos, direction, action));
        if (event.get().isCanceled()) ci.cancel();
    }

    @WrapOperation(method = "handleBlockBreakAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;attack(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"))
    private void neoforgeBridge$attack(BlockState state, Level level, BlockPos pos, Player player, Operation<Void> attack, @Share("leftClick") LocalRef<PlayerInteractEvent.LeftClickBlock> event) {
        if (event.get() != null && event.get().getUseBlock() == TriState.FALSE) return;
        attack.call(state, level, pos, player);
    }

    @Inject(method = "useItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getCount()I", ordinal = 0), cancellable = true)
    private void neoforgeBridge$rightClickItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult cancelResult = CommonHooks.onItemRightClick(player, hand);
        if (cancelResult != null) cir.setReturnValue(cancelResult);
    }

    @Inject(method = "useItemOn", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;gameModeForPlayer:Lnet/minecraft/world/level/GameType;", opcode = Opcodes.GETFIELD, ordinal = 0), cancellable = true)
    private void neoforgeBridge$rightClickBlock(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        event.set(CommonHooks.onRightClickBlock(player, hand, hit.getBlockPos(), hit));
        if (event.get().isCanceled()) cir.setReturnValue(event.get().getCancellationResult());
    }

    @ModifyExpressionValue(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSecondaryUseActive()Z"))
    private boolean neoforgeBridge$forceUseBlock(boolean secondary, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        return event.get() != null && event.get().getUseBlock() == TriState.TRUE ? false : secondary;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;"))
    private ItemInteractionResult neoforgeBridge$useBlock(BlockState state, ItemStack stack, Level level, Player player, InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> use, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        if (event.get() != null && event.get().getUseBlock() == TriState.FALSE) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return use.call(state, stack, level, player, hand, hit);
    }

    @ModifyExpressionValue(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemCooldowns;isOnCooldown(Lnet/minecraft/world/item/Item;)Z"))
    private boolean neoforgeBridge$forceUseItem(boolean cooldown, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        return event.get() != null && event.get().getUseItem() == TriState.TRUE ? false : cooldown;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult neoforgeBridge$useItem(ItemStack stack, UseOnContext context, Operation<InteractionResult> use, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        if (event.get() != null && event.get().getUseItem() == TriState.FALSE) return InteractionResult.PASS;
        return use.call(stack, context);
    }
}
