package ${package}.fabric.api.transfer.v1.fluid;

import ${package}.TransferBridge;
import ${package}.fabric.api.transfer.v1.storage.TransferVariant;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant, on NeoForge: a still fluid and its component patch
 * (a flowing fluid becomes its source, as on Fabric), the same thing as NeoForge's FluidResource. Amounts that go with it
 * are Fabric's droplets (FluidConstants.BUCKET = 81000); NeoForge's handlers count millibuckets, 81 droplets each.
 */
public interface FluidVariant extends TransferVariant<Fluid> {

    Codec<FluidVariant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.FLUID.holderByNameCodec().fieldOf("fluid").forGetter(FluidVariant::typeHolder),
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(FluidVariant::getComponentsPatch)
    ).apply(instance, (fluid, patch) -> TransferBridge.fluidVariant(fluid.value(), patch)));

    StreamCodec<RegistryFriendlyByteBuf, FluidVariant> PACKET_CODEC = StreamCodec.composite(
        ByteBufCodecs.holderRegistry(Registries.FLUID),
        FluidVariant::typeHolder,
        DataComponentPatch.STREAM_CODEC,
        FluidVariant::getComponentsPatch,
        (fluid, patch) -> TransferBridge.fluidVariant(fluid.value(), patch)
    );

    static FluidVariant blank() {
        return of(Fluids.EMPTY);
    }

    static FluidVariant of(Fluid fluid) {
        return of(fluid, DataComponentPatch.EMPTY);
    }

    static FluidVariant of(Fluid fluid, DataComponentPatch components) {
        return TransferBridge.fluidVariant(fluid, components);
    }

    default Fluid getFluid() {
        return getObject();
    }

    @Override
    @SuppressWarnings("deprecation")
    default Holder<Fluid> typeHolder() {
        return getFluid().builtInRegistryHolder();
    }

    @Override
    FluidVariant withComponents(DataComponentPatch patch);
}
