package ${package}.mixin;

import ${package}.extensions.BlockEntityHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's LevelChunk patch: a block entity's data from a chunk packet goes to its handleUpdateTag (default:
 * loadWithComponents). The handler is the lambda replaceWithPacketData runs per block entity (its intermediary name).
 */
@Mixin(LevelChunk.class)
abstract class BlockEntityChunkDataMixin {

    @WrapOperation(method = "method_31716", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void neoforgeBridge$handleUpdateTag(BlockEntity blockEntity, CompoundTag tag, HolderLookup.Provider registries, Operation<Void> load) {
        ((BlockEntityHooks) blockEntity).handleUpdateTag(tag, registries);
    }
}
