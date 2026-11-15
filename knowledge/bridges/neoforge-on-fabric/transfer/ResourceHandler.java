package ${package}.transfer;

import ${package}.transfer.resource.Resource;
import ${package}.transfer.transaction.TransactionContext;

/**
 * NeoForge's net.neoforged.neoforge.transfer.ResourceHandler: indexed contents (size, getResource, getAmountAsLong,
 * getCapacityAsLong, isValid) and transactional insert and extract, per index or across the handler.
 *
 * <p>On Fabric a handler is seen by Fabric mods as a Fabric API Storage (SlottedStorage, one slot per index) through the
 * capabilities the bridge registers (see TransferBridge), and a Fabric Storage is seen here as a handler. Amounts are
 * ints, as NeoForge's: a Fabric amount above Integer.MAX_VALUE is read as Integer.MAX_VALUE.
 */
public interface ResourceHandler<T extends Resource> {

    int size();

    T getResource(int index);

    long getAmountAsLong(int index);

    default int getAmountAsInt(int index) {
        return TransferBridge.saturated(getAmountAsLong(index));
    }

    long getCapacityAsLong(int index, T resource);

    default int getCapacityAsInt(int index, T resource) {
        return TransferBridge.saturated(getCapacityAsLong(index, resource));
    }

    boolean isValid(int index, T resource);

    int insert(int index, T resource, int amount, TransactionContext transaction);

    /** Inserts into every index in order until amount is in. */
    default int insert(T resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        int inserted = 0;
        int size = size();
        for (int index = 0; index < size && inserted < amount; index++) {
            inserted += insert(index, resource, amount - inserted, transaction);
        }
        return inserted;
    }

    int extract(int index, T resource, int amount, TransactionContext transaction);

    /** Extracts from every index in order until amount is out. */
    default int extract(T resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        int extracted = 0;
        int size = size();
        for (int index = 0; index < size && extracted < amount; index++) {
            extracted += extract(index, resource, amount - extracted, transaction);
        }
        return extracted;
    }

    @SuppressWarnings("unchecked")
    static <T extends Resource> Class<ResourceHandler<T>> asClass() {
        return (Class<ResourceHandler<T>>) (Object) ResourceHandler.class;
    }
}
