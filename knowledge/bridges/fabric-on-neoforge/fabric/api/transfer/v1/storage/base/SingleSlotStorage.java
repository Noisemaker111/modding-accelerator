package ${package}.fabric.api.transfer.v1.storage.base;

import ${package}.fabric.api.transfer.v1.storage.SlottedStorage;
import ${package}.fabric.api.transfer.v1.storage.StorageView;
import java.util.Iterator;
import java.util.List;

/** Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage: a storage that is its own single view and slot. */
public interface SingleSlotStorage<T> extends SlottedStorage<T>, StorageView<T> {

    @Override
    default Iterator<StorageView<T>> iterator() {
        return List.<StorageView<T>>of(this).iterator();
    }

    @Override
    default int getSlotCount() {
        return 1;
    }

    @Override
    default SingleSlotStorage<T> getSlot(int slot) {
        if (slot != 0) throw new IndexOutOfBoundsException("Slot " + slot + " does not exist in a single-slot storage.");
        return this;
    }
}
