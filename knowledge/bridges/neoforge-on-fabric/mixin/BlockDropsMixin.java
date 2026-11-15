package ${package}.mixin;

import ${package}.BlockCapture;
import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's Block patch: each Block.dropResources keeps the item entities its drops make (popResource adds them to a
 * list instead of the level) and hands them to CommonHooks.handleBlockDrops, which posts BlockDropsEvent and then adds
 * the drops, runs spawnAfterBreak and drops the event's experience, in place of the game's spawnAfterBreak call.
 */
@Mixin(Block.class)
abstract class BlockDropsMixin {

    @WrapOperation(method = "dropResources", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private static void neoforgeBridge$keepDrops(List<ItemStack> stacks, Consumer<ItemStack> pop, Operation<Void> forEach, @Share("drops") LocalRef<List<ItemEntity>> drops) {
        List<ItemEntity> outer = BlockCapture.startDrops();
        try {
            forEach.call(stacks, pop);
        } finally {
            drops.set(BlockCapture.stopDrops(outer));
        }
    }

    @WrapOperation(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean neoforgeBridge$keepDrop(Level level, Entity entity, Operation<Boolean> addFreshEntity) {
        if (entity instanceof ItemEntity item && BlockCapture.keepDrop(item)) return true;
        return addFreshEntity.call(level, entity);
    }

    @WrapOperation(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;spawnAfterBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;Z)V"))
    private static void neoforgeBridge$drops(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean dropExperience, Operation<Void> spawnAfterBreak, @Share("drops") LocalRef<List<ItemEntity>> drops) {
        CommonHooks.handleBlockDrops(level, pos, state, null, drops.get(), null, tool);
    }

    @WrapOperation(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;spawnAfterBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;Z)V"))
    private static void neoforgeBridge$dropsOfBlockEntity(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean dropExperience, Operation<Void> spawnAfterBreak, @Share("drops") LocalRef<List<ItemEntity>> drops, @Local(argsOnly = true) BlockEntity blockEntity) {
        CommonHooks.handleBlockDrops(level, pos, state, blockEntity, drops.get(), null, tool);
    }

    @WrapOperation(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;spawnAfterBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;Z)V"))
    private static void neoforgeBridge$dropsOfBreaker(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean dropExperience, Operation<Void> spawnAfterBreak, @Share("drops") LocalRef<List<ItemEntity>> drops, @Local(argsOnly = true) BlockEntity blockEntity, @Local(argsOnly = true) Entity breaker) {
        CommonHooks.handleBlockDrops(level, pos, state, blockEntity, drops.get(), breaker, tool);
    }
}
