package ${package}.fabric.api.transfer.v1.storage;

import ${package}.TransferBridge;
import ${package}.fabric.api.transfer.v1.transaction.Transaction;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.StreamSupport;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.Storage, on NeoForge: views over stored resources and
 * transactional insert and extract. A Storage registered with ItemStorage.SIDED or FluidStorage.SIDED is NeoForge's
 * ResourceHandler for other mods, and a NeoForge handler found there is a Storage here (see the bridge's TransferBridge).
 */
public interface Storage<T> extends Iterable<StorageView<T>> {

    @SuppressWarnings("unchecked")
    static <T> Storage<T> empty() {
        return (Storage<T>) Empty.INSTANCE;
    }

    default boolean supportsInsertion() {
        return true;
    }

    long insert(T resource, long maxAmount, TransactionContext transaction);

    default boolean supportsExtraction() {
        return true;
    }

    long extract(T resource, long maxAmount, TransactionContext transaction);

    @Override
    Iterator<StorageView<T>> iterator();

    default Iterator<StorageView<T>> nonEmptyIterator() {
        return StreamSupport.stream(spliterator(), false).filter(view -> view.getAmount() > 0 && !view.isResourceBlank()).iterator();
    }

    default Iterable<StorageView<T>> nonEmptyViews() {
        return this::nonEmptyIterator;
    }

    /** A number that changes whenever the contents may have; by default a new one on every call. */
    default long getVersion() {
        if (Transaction.isOpen()) throw new IllegalStateException("getVersion() may not be called during a transaction.");
        return TransferBridge.nextVersion();
    }

    @SuppressWarnings("unchecked")
    static <T> Class<Storage<T>> asClass() {
        return (Class<Storage<T>>) (Object) Storage.class;
    }

    /** Storage.empty(): holds nothing, takes nothing. */
    final class Empty implements Storage<Object> {

        static final Empty INSTANCE = new Empty();

        private Empty() {}

        @Override
        public boolean supportsInsertion() {
            return false;
        }

        @Override
        public long insert(Object resource, long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public long extract(Object resource, long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public Iterator<StorageView<Object>> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public long getVersion() {
            return 0;
        }
    }
}
