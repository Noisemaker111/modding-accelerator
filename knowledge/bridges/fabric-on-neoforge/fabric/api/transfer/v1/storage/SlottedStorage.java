package ${package}.fabric.api.transfer.v1.storage;

import ${package}.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import java.util.AbstractList;
import java.util.List;

/** Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage: a storage of numbered slots (a NeoForge handler's indices). */
public interface SlottedStorage<T> extends Storage<T> {

    int getSlotCount();

    SingleSlotStorage<T> getSlot(int slot);

    default List<SingleSlotStorage<T>> getSlots() {
        return new AbstractList<>() {
            @Override
            public SingleSlotStorage<T> get(int index) {
                return getSlot(index);
            }

            @Override
            public int size() {
                return getSlotCount();
            }
        };
    }
}
