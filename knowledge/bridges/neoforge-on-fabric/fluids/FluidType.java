package ${package}.fluids;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.material.Fluid;

/**
 * The parts of NeoForge's net.neoforged.neoforge.fluids.FluidType a fluid's code reads, on Fabric: BUCKET_VOLUME (1000
 * millibuckets) and the fluid's name and attributes, which Fabric API keeps per fluid in FluidVariantAttributes. NeoForge
 * registers fluid types; Fabric has none, so here a fluid's type is the fluid itself (FluidStack.getFluidType()).
 */
public class FluidType {

    public static final int BUCKET_VOLUME = 1000;

    private static final Map<Fluid, FluidType> TYPES = new ConcurrentHashMap<>();

    private final Fluid fluid;

    private FluidType(Fluid fluid) {
        this.fluid = fluid;
    }

    /** The type of a fluid (the bridge's: one per fluid). */
    public static FluidType of(Fluid fluid) {
        return TYPES.computeIfAbsent(fluid, FluidType::new);
    }

    public Component getDescription() {
        return FluidVariantAttributes.getName(FluidVariant.of(fluid));
    }

    public String getDescriptionId() {
        return key(getDescription());
    }

    public Component getDescription(FluidStack stack) {
        return stack.getHoverName();
    }

    public String getDescriptionId(FluidStack stack) {
        return key(getDescription(stack));
    }

    public int getLightLevel() {
        return FluidVariantAttributes.getLuminance(FluidVariant.of(fluid));
    }

    public int getTemperature() {
        return FluidVariantAttributes.getTemperature(FluidVariant.of(fluid));
    }

    public int getViscosity() {
        return FluidVariantAttributes.getViscosity(FluidVariant.of(fluid), null);
    }

    public boolean isLighterThanAir() {
        return FluidVariantAttributes.isLighterThanAir(FluidVariant.of(fluid));
    }

    private static String key(Component name) {
        return name.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : name.getString();
    }

    @Override
    public String toString() {
        return "FluidType[" + fluid + "]";
    }
}
