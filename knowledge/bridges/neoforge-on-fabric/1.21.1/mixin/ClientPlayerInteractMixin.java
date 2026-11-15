package ${package}.mixin;

import ${package}.CommonHooks;
import ${package}.PlayerInteractEvent;
import ${package}.TriState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's MultiPlayerGameMode patch, the client's half of PlayerInteractEvent: performUseItemOn posts RightClickBlock
 * first (the same result and useBlock/useItem semantics as the server's); useItem posts RightClickItem before
 * ItemStack.use (cancelled: its result, the item is not used); startDestroyBlock posts LeftClickBlock START when a
 * survival player starts breaking (useBlock FALSE skips Block.attack; useItem FALSE sends the start packet and skips the
 * breaking progress) and, in creative, before the block is broken (cancelled: it is not broken on the client);
 * continueDestroyBlock posts LeftClickBlock CLIENT_HOLD each tick of breaking (useItem FALSE skips that tick's progress);
 * interactAt posts EntityInteractSpecific before Entity.interactAt (cancelled: its result, no interaction).
 */
@Mixin(MultiPlayerGameMode.class)
abstract class ClientPlayerInteractMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float destroyProgress;

    @Unique
    private PlayerInteractEvent.LeftClickBlock neoforgeBridge$leftClick;

    @Shadow
    private void startPrediction(ClientLevel level, PredictiveAction action) {
        throw new AssertionError();
    }

    @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$rightClickBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        event.set(CommonHooks.onRightClickBlock(player, hand, hit.getBlockPos(), hit));
        if (event.get().isCanceled()) cir.setReturnValue(event.get().getCancellationResult());
    }

    @ModifyExpressionValue(method = "performUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSecondaryUseActive()Z"))
    private boolean neoforgeBridge$forceUseBlock(boolean secondary, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        return event.get() != null && event.get().getUseBlock() == TriState.TRUE ? false : secondary;
    }

    @WrapOperation(method = "performUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;"))
    private ItemInteractionResult neoforgeBridge$useBlock(BlockState state, ItemStack stack, Level level, Player player, InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> use, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        if (event.get() != null && event.get().getUseBlock() == TriState.FALSE) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return use.call(state, stack, level, player, hand, hit);
    }

    @ModifyExpressionValue(method = "performUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemCooldowns;isOnCooldown(Lnet/minecraft/world/item/Item;)Z"))
    private boolean neoforgeBridge$forceUseItem(boolean cooldown, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        return event.get() != null && event.get().getUseItem() == TriState.TRUE ? false : cooldown;
    }

    @WrapOperation(method = "performUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult neoforgeBridge$useItem(ItemStack stack, UseOnContext context, Operation<InteractionResult> use, @Share("rightClick") LocalRef<PlayerInteractEvent.RightClickBlock> event) {
        if (event.get() != null && event.get().getUseItem() == TriState.FALSE) return InteractionResult.PASS;
        return use.call(stack, context);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"))
    private InteractionResultHolder<ItemStack> neoforgeBridge$rightClickItem(ItemStack stack, Level level, Player player, InteractionHand hand, Operation<InteractionResultHolder<ItemStack>> use) {
        InteractionResult cancelResult = CommonHooks.onItemRightClick(player, hand);
        if (cancelResult != null) return new InteractionResultHolder<>(cancelResult, stack);
        return use.call(stack, level, player, hand);
    }

    @WrapOperation(method = "interactAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;interactAt(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult neoforgeBridge$interactAt(Entity entity, Player player, Vec3 pos, InteractionHand hand, Operation<InteractionResult> interactAt) {
        InteractionResult cancelResult = CommonHooks.onInteractEntityAt(player, entity, pos, hand);
        if (cancelResult != null) return cancelResult;
        return interactAt.call(entity, player, pos, hand);
    }

    @Inject(method = "startDestroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/tutorial/Tutorial;onDestroyBlock(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)V", ordinal = 1, shift = At.Shift.AFTER), cancellable = true)
    private void neoforgeBridge$leftClickBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        PlayerInteractEvent.LeftClickBlock event = CommonHooks.onLeftClickBlock(this.minecraft.player, pos, direction, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        this.neoforgeBridge$leftClick = event;
        if (event.getUseItem() != TriState.FALSE) return;
        BlockState state = this.minecraft.level.getBlockState(pos);
        this.startPrediction(this.minecraft.level, sequence -> {
            if (!state.isAir() && this.destroyProgress == 0.0F && event.getUseBlock() != TriState.FALSE) {
                state.attack(this.minecraft.level, pos, this.minecraft.player);
            }
            return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
        });
        this.neoforgeBridge$leftClick = null;
        cir.setReturnValue(true);
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void neoforgeBridge$leftClickDone(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        this.neoforgeBridge$leftClick = null;
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;attack(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"))
    private void neoforgeBridge$attack(BlockState state, Level level, BlockPos pos, Player player, Operation<Void> attack) {
        if (this.neoforgeBridge$leftClick != null && this.neoforgeBridge$leftClick.getUseBlock() == TriState.FALSE) return;
        attack.call(state, level, pos, player);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean neoforgeBridge$creativeBreak(MultiPlayerGameMode gameMode, BlockPos pos, Operation<Boolean> destroy, @Local(argsOnly = true) Direction direction) {
        if (this.minecraft.player.getAbilities().instabuild && CommonHooks.onLeftClickBlock(this.minecraft.player, pos, direction, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).isCanceled()) return false;
        return destroy.call(gameMode, pos);
    }

    @Inject(method = "continueDestroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/tutorial/Tutorial;onDestroyBlock(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)V", ordinal = 1, shift = At.Shift.AFTER), cancellable = true)
    private void neoforgeBridge$clientMineHold(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (CommonHooks.onClientMineHold(this.minecraft.player, pos, direction).getUseItem() == TriState.FALSE) cir.setReturnValue(true);
    }
}
