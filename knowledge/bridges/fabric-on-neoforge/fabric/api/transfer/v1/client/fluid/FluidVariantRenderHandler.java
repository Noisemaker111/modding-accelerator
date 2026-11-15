package ${package}.fabric.api.transfer.v1.client.fluid;

import ${package}.fabric.api.transfer.v1.fluid.FluidVariant;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRenderHandler, on NeoForge: by default a
 * fluid's color is its fluid model's tint, NeoForge's FluidTintSource where the fluid has one (its world color with a
 * level and position, else its color as a stack), as ARGB; -1 (white) when the model has no tint.
 */
public interface FluidVariantRenderHandler {

    default void appendTooltip(FluidVariant fluidVariant, List<Component> tooltip, TooltipFlag tooltipFlag) {}

    default int getColor(FluidVariant fluidVariant, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        FluidState fluidState = fluidVariant.getFluid().defaultFluidState();
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
        @SuppressWarnings("deprecation")
        BlockTintSource tint = model.tintSource();
        if (tint == null) return -1;
        BlockState block = fluidState.createLegacyBlock();
        int color;
        if (tint instanceof FluidTintSource fluidTint) {
            color = level != null && pos != null
                ? fluidTint.colorInWorld(fluidState, block, level, pos)
                : fluidTint.colorAsStack(new FluidStack(fluidVariant.getFluid(), FluidType.BUCKET_VOLUME, fluidVariant.getComponentsPatch()));
        } else {
            color = level != null && pos != null ? tint.colorInWorld(block, level, pos) : tint.color(block);
        }
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }
}
