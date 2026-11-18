package ${package}.mixin;

import ${package}.extensions.BlockEntityHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where the client loads a block entity's data from a chunk packet, the block entity's handleUpdateTag loads it instead
 * (its default loads it as the game does). The handler is the lambda replaceWithPacketData runs per block entity.
 */
@Mixin(LevelChunk.class)
abstract class BlockEntityChunkDataMixin {

    @WrapOperation(method = "lambda$replaceWithPacketData$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void neoforgeBridge$handleUpdateTag(BlockEntity blockEntity, ValueInput input, Operation<Void> load) {
        ((BlockEntityHooks) blockEntity).handleUpdateTag(input);
    }
}
