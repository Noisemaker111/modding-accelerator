package ${package}.fabric.api.transfer.v1.storage;

import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;

/** Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.StorageView: one resource, its amount and capacity, and extraction. */
public interface StorageView<T> {

    long extract(T resource, long maxAmount, TransactionContext transaction);

    boolean isResourceBlank();

    T getResource();

    long getAmount();

    long getCapacity();

    default StorageView<T> getUnderlyingView() {
        return this;
    }
}
