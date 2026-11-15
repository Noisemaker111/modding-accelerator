package ${package}.fabric.api.transfer.v1.client.fluid;

import ${package}.fabric.api.transfer.v1.fluid.FluidVariant;
import ${package}.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering, on NeoForge: registered handlers
 * per fluid, tooltips (the fluid's name, the handler's lines, the fluid's id when advanced), and colors from the handler
 * (by default the fluid model's NeoForge tint, see FluidVariantRenderHandler).
 */
public final class FluidVariantRendering {

    private static final Map<Fluid, FluidVariantRenderHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final FluidVariantRenderHandler DEFAULT_HANDLER = new FluidVariantRenderHandler() {};

    private FluidVariantRendering() {}

    public static void register(Fluid fluid, FluidVariantRenderHandler handler) {
        if (HANDLERS.putIfAbsent(fluid, handler) != null) throw new IllegalArgumentException("Duplicate handler registration for fluid " + fluid);
    }

    public static @Nullable FluidVariantRenderHandler getHandler(Fluid fluid) {
        return HANDLERS.get(fluid);
    }

    public static FluidVariantRenderHandler getHandlerOrDefault(Fluid fluid) {
        return HANDLERS.getOrDefault(fluid, DEFAULT_HANDLER);
    }

    public static List<Component> getTooltip(FluidVariant fluidVariant) {
        return getTooltip(fluidVariant, false, defaultFlag());
    }

    public static List<Component> getTooltip(FluidVariant fluidVariant, boolean coloredName) {
        return getTooltip(fluidVariant, coloredName, defaultFlag());
    }

    public static List<Component> getTooltip(FluidVariant fluidVariant, TooltipFlag flag) {
        return getTooltip(fluidVariant, false, flag);
    }

    public static List<Component> getTooltip(FluidVariant fluidVariant, boolean coloredName, TooltipFlag flag) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(coloredName ? FluidVariantAttributes.getColoredName(fluidVariant) : FluidVariantAttributes.getName(fluidVariant));
        getHandlerOrDefault(fluidVariant.getFluid()).appendTooltip(fluidVariant, tooltip, flag);
        if (flag.isAdvanced()) tooltip.add(Component.literal(BuiltInRegistries.FLUID.getKey(fluidVariant.getFluid()).toString()).withStyle(ChatFormatting.DARK_GRAY));
        return tooltip;
    }

    public static int getColor(FluidVariant fluidVariant) {
        return getColor(fluidVariant, null, null);
    }

    public static int getColor(FluidVariant fluidVariant, @Nullable BlockAndTintGetter view, @Nullable BlockPos pos) {
        return getHandlerOrDefault(fluidVariant.getFluid()).getColor(fluidVariant, view, pos);
    }

    private static TooltipFlag defaultFlag() {
        return Minecraft.getInstance().options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
    }
}
