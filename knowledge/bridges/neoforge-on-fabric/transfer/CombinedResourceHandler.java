package ${package}.transfer;

import ${package}.transfer.resource.Resource;
import ${package}.transfer.transaction.TransactionContext;
import java.util.Objects;
import java.util.SequencedCollection;

/** NeoForge's net.neoforged.neoforge.transfer.CombinedResourceHandler: several handlers' indices one after the other. */
public class CombinedResourceHandler<T extends Resource> implements ResourceHandler<T> {

    private final ResourceHandler<T>[] handlers;
    private final int[] baseIndex;
    private final int sizeCache;

    @SuppressWarnings("unchecked")
    public CombinedResourceHandler(SequencedCollection<? extends ResourceHandler<T>> handlers) {
        this(handlers.toArray(ResourceHandler[]::new));
    }

    @SafeVarargs
    public CombinedResourceHandler(ResourceHandler<T>... handlers) {
        this.handlers = handlers;
        this.baseIndex = new int[handlers.length];
        int index = 0;
        for (int i = 0; i < handlers.length; i++) {
            index += handlers[i].size();
            baseIndex[i] = index;
        }
        this.sizeCache = index;
    }

    /** The handler an index falls in. */
    protected int getHandlerIndex(int index) {
        Objects.checkIndex(index, sizeCache);
        for (int i = 0; i < baseIndex.length; i++) {
            if (index < baseIndex[i]) return i;
        }
        throw new IndexOutOfBoundsException(index);
    }

    protected ResourceHandler<T> getHandlerFromIndex(int handlerIndex) {
        return handlers[handlerIndex];
    }

    protected int getSlotFromIndex(int index, int handlerIndex) {
        return handlerIndex == 0 ? index : index - baseIndex[handlerIndex - 1];
    }

    @Override
    public final int size() {
        return sizeCache;
    }

    @Override
    public T getResource(int index) {
        int handler = getHandlerIndex(index);
        return handlers[handler].getResource(getSlotFromIndex(index, handler));
    }

    @Override
    public long getAmountAsLong(int index) {
        int handler = getHandlerIndex(index);
        return handlers[handler].getAmountAsLong(getSlotFromIndex(index, handler));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        int handler = getHandlerIndex(index);
        return handlers[handler].getCapacityAsLong(getSlotFromIndex(index, handler), resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        int handler = getHandlerIndex(index);
        return handlers[handler].isValid(getSlotFromIndex(index, handler), resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        int handler = getHandlerIndex(index);
        return handlers[handler].insert(getSlotFromIndex(index, handler), resource, amount, transaction);
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        int inserted = 0;
        for (ResourceHandler<T> handler : handlers) {
            if (inserted >= amount) break;
            inserted += handler.insert(resource, amount - inserted, transaction);
        }
        return inserted;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        int handler = getHandlerIndex(index);
        return handlers[handler].extract(getSlotFromIndex(index, handler), resource, amount, transaction);
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        int extracted = 0;
        for (ResourceHandler<T> handler : handlers) {
            if (extracted >= amount) break;
            extracted += handler.extract(resource, amount - extracted, transaction);
        }
        return extracted;
    }
}
