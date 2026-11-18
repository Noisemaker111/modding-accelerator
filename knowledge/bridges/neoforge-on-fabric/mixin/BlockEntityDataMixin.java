package ${package}.mixin;

import ${package}.extensions.BlockEntityHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where the client loads a block entity's update packet, the block entity's onDataPacket loads it instead (its default
 * loads it as the game does), so a block entity that overrides it gets its own sync. The handler is the lambda
 * handleBlockEntityData runs for the block entity.
 */
@Mixin(ClientPacketListener.class)
abstract class BlockEntityDataMixin {

    @WrapOperation(method = "lambda$handleBlockEntityData$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void neoforgeBridge$onDataPacket(BlockEntity blockEntity, ValueInput input, Operation<Void> load) {
        ((BlockEntityHooks) blockEntity).onDataPacket(((ClientPacketListener) (Object) this).getConnection(), input);
    }
}
