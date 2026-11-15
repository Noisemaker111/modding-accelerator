package ${package};

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * NeoForge's net.neoforged.neoforge.client.fluid.FluidTintSources: a plain block tint source as a fluid one (a fluid
 * state is colored as its block), and constant colors. NeoForge's FluidModel.fluidTintSource() is
 * FluidTintSources.of(model.tintSource()) on Fabric, whose FluidModel keeps the game's tint source.
 */
public final class FluidTintSources {

    private FluidTintSources() {}

    public static FluidTintSource of(BlockTintSource tintSource) {
        if (tintSource == null) return null;
        if (tintSource instanceof FluidTintSource fluidTintSource) return fluidTintSource;
        return new FluidTintSource() {
            @Override
            public int color(BlockState state) {
                return tintSource.color(state);
            }

            @Override
            public int color(FluidState state) {
                return this.color(state.createLegacyBlock());
            }

            @Override
            public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                return tintSource.colorInWorld(state, level, pos);
            }

            @Override
            public int colorInWorld(FluidState fluidState, BlockState blockState, BlockAndTintGetter level, BlockPos pos) {
                return this.colorInWorld(blockState, level, pos);
            }

            @Override
            public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                return tintSource.colorAsTerrainParticle(state, level, pos);
            }
        };
    }

    public static FluidTintSource constant(int color) {
        return constant(color, color);
    }

    public static FluidTintSource constant(int fluidColor, int blockColor) {
        return new FluidTintSource() {
            @Override
            public int color(FluidState state) {
                return fluidColor;
            }

            @Override
            public int color(BlockState state) {
                return blockColor;
            }
        };
    }
}
