package ${package};

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import ${package}.fluids.FluidStack;

/**
 * NeoForge's net.neoforged.neoforge.client.fluid.FluidTintSource: the game's block tint source asked for a fluid, with
 * NeoForge's defaults (a fluid's color is its own state's; in the world, the block's; as a stack, the default state's).
 * On Fabric the game's FluidModel keeps a plain BlockTintSource, which FluidTintSources.of reads as one.
 */
public interface FluidTintSource extends BlockTintSource {

    int color(FluidState state);

    default int colorInWorld(FluidState fluidState, BlockState blockState, BlockAndTintGetter level, BlockPos pos) {
        return this.color(fluidState);
    }

    default int colorAsStack(FluidStack stack) {
        return this.color(stack.getFluid().defaultFluidState());
    }

    @Override
    default int color(BlockState state) {
        return this.color(state.getFluidState());
    }

    @Override
    default int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return this.colorInWorld(state.getFluidState(), state, level, pos);
    }
}
