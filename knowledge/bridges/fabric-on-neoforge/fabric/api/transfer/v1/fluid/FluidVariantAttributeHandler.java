package ${package}.fabric.api.transfer.v1.fluid;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributeHandler, on NeoForge: by default each
 * attribute is the fluid's NeoForge FluidType's (its description, bucket sounds, light level, temperature, viscosity,
 * lighter-than-air), so a fluid reads as NeoForge and its mods show it.
 */
public interface FluidVariantAttributeHandler {

    default Component getName(FluidVariant fluidVariant) {
        return fluidVariant.getFluid().getFluidType().getDescription(stack(fluidVariant));
    }

    default Component getColoredName(FluidVariant fluidVariant) {
        return getName(fluidVariant).copy().withColor(getAssociatedColor(fluidVariant));
    }

    default int getAssociatedColor(FluidVariant fluidVariant) {
        return -1;
    }

    default Optional<SoundEvent> getFillSound(FluidVariant variant) {
        return Optional.ofNullable(type(variant).getSound(stack(variant), SoundActions.BUCKET_FILL));
    }

    default Optional<SoundEvent> getEmptySound(FluidVariant variant) {
        return Optional.ofNullable(type(variant).getSound(stack(variant), SoundActions.BUCKET_EMPTY));
    }

    default int getLightEmission(FluidVariant variant) {
        return type(variant).getLightLevel();
    }

    default int getTemperature(FluidVariant variant) {
        return type(variant).getTemperature();
    }

    default int getViscosity(FluidVariant variant, @Nullable Level level) {
        return type(variant).getViscosity();
    }

    default boolean isLighterThanAir(FluidVariant variant) {
        return type(variant).isLighterThanAir();
    }

    private static FluidType type(FluidVariant variant) {
        return variant.getFluid().getFluidType();
    }

    /** A bucket of the variant, as NeoForge's FluidType reads it. */
    private static FluidStack stack(FluidVariant variant) {
        return variant.isBlank() ? FluidStack.EMPTY : new FluidStack(variant.getFluid(), FluidType.BUCKET_VOLUME, variant.getComponentsPatch());
    }
}
