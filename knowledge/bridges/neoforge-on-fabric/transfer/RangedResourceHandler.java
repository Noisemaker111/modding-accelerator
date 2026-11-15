package ${package}.transfer;

import ${package}.transfer.resource.Resource;
import ${package}.transfer.transaction.TransactionContext;
import java.util.Objects;
import java.util.function.Supplier;

/** NeoForge's net.neoforged.neoforge.transfer.RangedResourceHandler: the indices start (inclusive) to end (exclusive) of another handler. */
public class RangedResourceHandler<T extends Resource> implements ResourceHandler<T> {

    protected final Supplier<ResourceHandler<T>> delegate;
    protected int start;
    protected int end;

    public static <T extends Resource> RangedResourceHandler<T> of(ResourceHandler<T> delegate, int start, int end) {
        return new RangedResourceHandler<>(delegate, start, end);
    }

    public static <T extends Resource> RangedResourceHandler<T> of(Supplier<ResourceHandler<T>> delegate, int start, int end) {
        return new RangedResourceHandler<>(delegate, start, end);
    }

    public static <T extends Resource> RangedResourceHandler<T> ofSingleIndex(ResourceHandler<T> delegate, int index) {
        return of(delegate, index, index + 1);
    }

    public static <T extends Resource> RangedResourceHandler<T> ofSingleIndex(Supplier<ResourceHandler<T>> delegate, int index) {
        return of(delegate, index, index + 1);
    }

    protected RangedResourceHandler(ResourceHandler<T> delegate, int start, int end) {
        this(() -> delegate, start, end);
    }

    protected RangedResourceHandler(Supplier<ResourceHandler<T>> delegate, int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid range " + start + " to " + end);
        this.delegate = delegate;
        this.start = start;
        this.end = end;
    }

    @Override
    public int size() {
        return end - start;
    }

    protected int convertIndex(int index) {
        Objects.checkIndex(index, size());
        return start + index;
    }

    @Override
    public T getResource(int index) {
        return delegate.get().getResource(convertIndex(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return delegate.get().getAmountAsLong(convertIndex(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return delegate.get().getCapacityAsLong(convertIndex(index), resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        return delegate.get().isValid(convertIndex(index), resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        return delegate.get().insert(convertIndex(index), resource, amount, transaction);
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        return delegate.get().extract(convertIndex(index), resource, amount, transaction);
    }
}
