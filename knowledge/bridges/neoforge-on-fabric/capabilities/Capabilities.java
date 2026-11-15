package ${package}.capabilities;

import ${package}.transfer.ResourceHandler;
import ${package}.transfer.TransferBridge;
import ${package}.transfer.access.ItemAccess;
import ${package}.transfer.energy.EnergyHandler;
import ${package}.transfer.fluid.FluidResource;
import ${package}.transfer.item.ItemResource;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.Capabilities on Fabric API's lookups (see transfer.TransferBridge):
 * Item.BLOCK is ItemStorage.SIDED, Item.ITEM is ItemStorage.ITEM, Fluid.BLOCK is FluidStorage.SIDED and Fluid.ITEM is
 * FluidStorage.ITEM, so Fabric mods and the converted mod see each other's item and fluid handlers (fluid amounts at 81
 * droplets per millibucket). Energy and the entity capabilities have no Fabric API lookup: they are lookups the bridge
 * creates, named as NeoForge's capabilities, which every mod converted from NeoForge shares.
 */
public final class Capabilities {

    private Capabilities() {}

    private static Identifier create(String path) {
        return Identifier.fromNamespaceAndPath("neoforge", path);
    }

    public static final class Energy {

        public static final BlockCapability<EnergyHandler, @Nullable Direction> BLOCK = BlockCapability.bridged(
            create("energy_handler"), EnergyHandler.class, Direction.class, TransferBridge.ENERGY_SIDED, TransferBridge::energyHandler, TransferBridge::energyStorage
        );
        public static final EntityCapability<EnergyHandler, @Nullable Direction> ENTITY = EntityCapability.bridged(
            create("energy_handler"), EnergyHandler.class, Direction.class, TransferBridge.ENERGY_ENTITY, TransferBridge::energyHandler, TransferBridge::energyStorage
        );
        public static final ItemCapability<EnergyHandler, ItemAccess> ITEM = ItemCapability.bridged(
            create("energy_handler"), EnergyHandler.class, ItemAccess.class, TransferBridge.ENERGY_ITEM, TransferBridge::energyHandler, TransferBridge::energyStorage, TransferBridge::context, TransferBridge::access
        );

        private Energy() {}
    }

    public static final class Fluid {

        public static final BlockCapability<ResourceHandler<FluidResource>, @Nullable Direction> BLOCK = BlockCapability.bridged(
            create("fluid_handler"), ResourceHandler.<FluidResource>asClass(), Direction.class, FluidStorage.SIDED, TransferBridge::fluidHandler, TransferBridge::fluidStorage
        );
        public static final EntityCapability<ResourceHandler<FluidResource>, @Nullable Direction> ENTITY = EntityCapability.bridged(
            create("fluid_handler"), ResourceHandler.<FluidResource>asClass(), Direction.class, TransferBridge.FLUID_ENTITY, TransferBridge::fluidHandler, TransferBridge::fluidStorage
        );
        public static final ItemCapability<ResourceHandler<FluidResource>, ItemAccess> ITEM = ItemCapability.bridged(
            create("fluid_handler"), ResourceHandler.<FluidResource>asClass(), ItemAccess.class, FluidStorage.ITEM, TransferBridge::fluidHandler, TransferBridge::fluidStorage, TransferBridge::context, TransferBridge::access
        );

        private Fluid() {}
    }

    public static final class Item {

        public static final BlockCapability<ResourceHandler<ItemResource>, @Nullable Direction> BLOCK = BlockCapability.bridged(
            create("item_handler"), ResourceHandler.<ItemResource>asClass(), Direction.class, ItemStorage.SIDED, TransferBridge::itemHandler, TransferBridge::itemStorage
        );
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Void> ENTITY = EntityCapability.bridged(
            create("item_handler"), ResourceHandler.<ItemResource>asClass(), Void.class, TransferBridge.ITEM_ENTITY, TransferBridge::itemHandler, TransferBridge::itemStorage
        );
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Direction> ENTITY_AUTOMATION = EntityCapability.bridged(
            create("item_handler_automation"), ResourceHandler.<ItemResource>asClass(), Direction.class, TransferBridge.ITEM_ENTITY_AUTOMATION, TransferBridge::itemHandler, TransferBridge::itemStorage
        );
        public static final ItemCapability<ResourceHandler<ItemResource>, ItemAccess> ITEM = ItemCapability.bridged(
            create("item_handler"), ResourceHandler.<ItemResource>asClass(), ItemAccess.class, ItemStorage.ITEM, TransferBridge::itemHandler, TransferBridge::itemStorage, TransferBridge::context, TransferBridge::access
        );

        private Item() {}
    }
}
