package modaccel.datacheck.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * data-check reads data, never how a block collides or what its faces can hold. Every block state caches its
 * collision shape and whether each face supports another block (6 faces, 3 kinds of support) at start-up,
 * about half of Bootstrap's CPU; data-check's game caches an empty shape and no sturdy faces instead. A mod
 * that asks for a block's collision shape during its own start-up gets that empty shape in data-check;
 * nothing a data pack loads depends on it. Fluid state, light and occlusion are still computed as the game
 * does. Not required: on a game whose code differs it simply does not apply.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class SkipShapeCache {
    @Redirect(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape modaccel$noCollision(Block block, BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }

    @Redirect(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape modaccel$noCollisionAgain(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }

    @Redirect(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/SupportType;isSupporting(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"))
    private boolean modaccel$notSturdy(SupportType type, BlockState state, BlockGetter level, BlockPos pos, Direction face) { return false; }
}
