package ${package}.mixin;

import ${package}.BlockSnapshot;
import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's EnderMan patch: an enderman putting its block down (EndermanLeaveBlockGoal.tick) posts
 * BlockEvent.EntityPlaceEvent for the block below, as EventHooks.onBlockPlace with Direction.UP, after canPlaceBlock;
 * when it is cancelled the block is not placed.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")
abstract class EndermanPlaceMixin {

    @Shadow
    @Final
    private EnderMan enderman;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/EnderMan$EndermanLeaveBlockGoal;canPlaceBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean neoforgeBridge$placeEvent(boolean canPlace, @Local Level level, @Local(ordinal = 1) BlockPos below) {
        return canPlace && !EventHooks.onBlockPlace(this.enderman, BlockSnapshot.create(level.dimension(), level, below), Direction.UP);
    }
}
