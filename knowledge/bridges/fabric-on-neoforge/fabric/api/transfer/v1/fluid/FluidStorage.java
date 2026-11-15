package ${package}.fabric.api.transfer.v1.fluid;

import ${package}.fabric.api.lookup.v1.block.BlockApiLookup;
import ${package}.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage, on NeoForge: SIDED is NeoForge's
 * Capabilities.Fluid.BLOCK, each ResourceHandler of FluidResource seen as a Storage of FluidVariant in droplets (81 per
 * NeoForge millibucket, see the bridge's TransferBridge), and back. ITEM and the combined item providers are not mirrored.
 */
public final class FluidStorage {

    public static final BlockApiLookup<Storage<FluidVariant>, @Nullable Direction> SIDED = BlockApiLookup.get(Identifier.fromNamespaceAndPath("fabric", "sided_fluid_storage"), Storage.asClass(), Direction.class);

    private FluidStorage() {}
}
