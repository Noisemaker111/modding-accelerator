package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.extensions.IBlockExtension, whose methods NeoForge adds to Block: here
 * as static methods taking the block first, which modaccel convert's rules write in place of the calls
 * (block.getLightEmission(state, level, pos) becomes IBlockExtension.getLightEmission(block, state, level, pos)). Each
 * returns what NeoForge's default returns; a block cannot override them on Fabric.
 */
public final class IBlockExtension {

    private IBlockExtension() {}

    /** The light a state gives off at a position: NeoForge's default is the state's own light emission. */
    public static int getLightEmission(Block block, BlockState state, BlockGetter level, BlockPos pos) {
        return state.getLightEmission();
    }
}
