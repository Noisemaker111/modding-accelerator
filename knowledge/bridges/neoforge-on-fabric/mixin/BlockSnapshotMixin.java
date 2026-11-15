package ${package}.mixin;

import ${package}.BlockCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's Level patch: while an item is being placed (CommonHooks.placeItemIntoWorld), setBlock first records the
 * block it is about to change as a BlockSnapshot, for BlockEvent.EntityPlaceEvent and for putting it back. Difference:
 * NeoForge also holds the placed blocks' onPlace and neighbour updates until the event passed; here they happen as the
 * game does them, and a cancelled placement is undone afterwards.
 */
@Mixin(Level.class)
abstract class BlockSnapshotMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void neoforgeBridge$snapshot(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        BlockCapture.beforeSetBlock((Level) (Object) this, pos, flags);
    }
}
