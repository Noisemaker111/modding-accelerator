package ${package}.fabric.api.transfer.v1.fluid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes, on NeoForge: a handler registered for a
 * fluid answers for it; otherwise the default handler gives the fluid's NeoForge FluidType attributes.
 */
public final class FluidVariantAttributes {

    private static final Map<Fluid, FluidVariantAttributeHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final FluidVariantAttributeHandler DEFAULT_HANDLER = new FluidVariantAttributeHandler() {};

    private FluidVariantAttributes() {}

    public static void register(Fluid fluid, FluidVariantAttributeHandler handler) {
        if (HANDLERS.putIfAbsent(fluid, handler) != null) throw new IllegalArgumentException("Duplicate handler registration for fluid " + fluid);
    }

    public static @Nullable FluidVariantAttributeHandler getHandler(Fluid fluid) {
        return HANDLERS.get(fluid);
    }

    public static FluidVariantAttributeHandler getHandlerOrDefault(Fluid fluid) {
        return HANDLERS.getOrDefault(fluid, DEFAULT_HANDLER);
    }

    public static Component getName(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getName(variant);
    }

    public static Component getColoredName(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getColoredName(variant);
    }

    public static int getAssociatedColor(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getAssociatedColor(variant);
    }

    public static SoundEvent getFillSound(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getFillSound(variant).or(() -> variant.getFluid().getPickupSound()).orElse(SoundEvents.BUCKET_FILL);
    }

    public static SoundEvent getEmptySound(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getEmptySound(variant).orElse(SoundEvents.BUCKET_EMPTY);
    }

    public static int getLuminance(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getLightEmission(variant);
    }

    public static int getTemperature(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).getTemperature(variant);
    }

    public static int getViscosity(FluidVariant variant, @Nullable Level level) {
        return getHandlerOrDefault(variant.getFluid()).getViscosity(variant, level);
    }

    public static boolean isLighterThanAir(FluidVariant variant) {
        return getHandlerOrDefault(variant.getFluid()).isLighterThanAir(variant);
    }
}
