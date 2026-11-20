package ${package}.capabilities;

import ${package}.energy.IEnergyStorage;
import ${package}.fluids.capability.IFluidHandler;
import ${package}.fluids.capability.IFluidHandlerItem;
import ${package}.items.IItemHandler;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 21.1's net.neoforged.neoforge.capabilities.Capabilities. The block item and fluid handlers are Fabric API's
 * ItemStorage.SIDED and FluidStorage.SIDED (ItemStorages, FluidStorages), so hoppers and Fabric mods' pipes see a
 * converted mod's inventories and tanks and it sees theirs. Energy (Fabric API has none), the entity handlers and the
 * item-held handlers are the bridge's own Fabric lookups of NeoForge's names, shared by the converted mods.
 */
public final class Capabilities {

    private Capabilities() {}

    private static ResourceLocation create(String path) {
        return ResourceLocation.fromNamespaceAndPath("neoforge", path);
    }

    public static final class EnergyStorage {

        public static final BlockCapability<IEnergyStorage, @Nullable Direction> BLOCK = BlockCapability.createSided(create("energy"), IEnergyStorage.class);
        public static final EntityCapability<IEnergyStorage, @Nullable Direction> ENTITY = EntityCapability.createSided(create("energy"), IEnergyStorage.class);
        public static final ItemCapability<IEnergyStorage, @Nullable Void> ITEM = ItemCapability.createVoid(create("energy"), IEnergyStorage.class);

        private EnergyStorage() {}
    }

    public static final class FluidHandler {

        public static final BlockCapability<IFluidHandler, @Nullable Direction> BLOCK = BlockCapability.bridged(
            create("fluid_handler"), IFluidHandler.class, Direction.class, FluidStorage.SIDED, FluidStorages::handler, FluidStorages::storage
        );
        public static final EntityCapability<IFluidHandler, @Nullable Direction> ENTITY = EntityCapability.createSided(create("fluid_handler"), IFluidHandler.class);
        public static final ItemCapability<IFluidHandlerItem, @Nullable Void> ITEM = ItemCapability.createVoid(create("fluid_handler"), IFluidHandlerItem.class);

        private FluidHandler() {}
    }

    public static final class ItemHandler {

        public static final BlockCapability<IItemHandler, @Nullable Direction> BLOCK = BlockCapability.bridged(
            create("item_handler"), IItemHandler.class, Direction.class, ItemStorage.SIDED, ItemStorages::handler, ItemStorages::storage
        );
        public static final EntityCapability<IItemHandler, @Nullable Void> ENTITY = EntityCapability.createVoid(create("item_handler"), IItemHandler.class);
        public static final EntityCapability<IItemHandler, @Nullable Direction> ENTITY_AUTOMATION = EntityCapability.createSided(create("item_handler_automation"), IItemHandler.class);
        public static final ItemCapability<IItemHandler, @Nullable Void> ITEM = ItemCapability.createVoid(create("item_handler"), IItemHandler.class);

        private ItemHandler() {}
    }
}
