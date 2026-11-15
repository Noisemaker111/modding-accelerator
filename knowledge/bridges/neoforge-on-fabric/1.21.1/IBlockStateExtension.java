package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.extensions.IBlockStateExtension, whose methods NeoForge adds to BlockState:
 * here as static methods taking the state first, which modaccel convert's rules write in place of the calls
 * (state.getLightEmission(level, pos) becomes IBlockStateExtension.getLightEmission(state, level, pos)). Each returns
 * what NeoForge's default (IBlockExtension's) returns; a block cannot override them on Fabric.
 */
public final class IBlockStateExtension {

    private IBlockStateExtension() {}

    /** The light the state gives off at a position: NeoForge's default is the state's own light emission. */
    public static int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getLightEmission();
    }

    /** Whether an entity may destroy the state: not a dragon-immune block for the ender dragon, air or a wither-breakable block for the wither and its skulls. */
    public static boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        if (entity instanceof EnderDragon) {
            return !state.getBlock().defaultBlockState().is(BlockTags.DRAGON_IMMUNE);
        } else if (entity instanceof WitherBoss || entity instanceof WitherSkull) {
            return state.isAir() || WitherBoss.canDestroy(state);
        }
        return true;
    }
}
