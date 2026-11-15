package ${package}.transfer.item;

import ${package}.transfer.ResourceHandler;
import ${package}.transfer.TransferBridge;
import ${package}.transfer.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.world.Container;

/**
 * NeoForge's net.neoforged.neoforge.transfer.item.VanillaContainerWrapper: a vanilla Container as an item handler, on
 * Fabric API's ContainerStorage.of(container, null), which journals each slot and calls setChanged once after the outer
 * transaction commits, as NeoForge's wrapper does.
 */
public class VanillaContainerWrapper implements ResourceHandler<ItemResource> {

    final Storage<ItemVariant> storage;
    private final ResourceHandler<ItemResource> handler;

    VanillaContainerWrapper(Storage<ItemVariant> storage) {
        this.storage = storage;
        this.handler = TransferBridge.itemHandler(storage);
    }

    public static ResourceHandler<ItemResource> of(Container container) {
        return new VanillaContainerWrapper(ContainerStorage.of(container, null));
    }

    public static ResourceHandler<ItemResource> ofUncached(Container container) {
        return of(container);
    }

    @Override
    public int size() {
        return handler.size();
    }

    @Override
    public ItemResource getResource(int index) {
        return handler.getResource(index);
    }

    @Override
    public long getAmountAsLong(int index) {
        return handler.getAmountAsLong(index);
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        return handler.getCapacityAsLong(index, resource);
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return handler.isValid(index, resource);
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        return handler.insert(index, resource, amount, transaction);
    }

    @Override
    public int insert(ItemResource resource, int amount, TransactionContext transaction) {
        return handler.insert(resource, amount, transaction);
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        return handler.extract(index, resource, amount, transaction);
    }

    @Override
    public int extract(ItemResource resource, int amount, TransactionContext transaction) {
        return handler.extract(resource, amount, transaction);
    }

    @Override
    public String toString() {
        return "VanillaContainerWrapper[" + storage + "]";
    }
}
