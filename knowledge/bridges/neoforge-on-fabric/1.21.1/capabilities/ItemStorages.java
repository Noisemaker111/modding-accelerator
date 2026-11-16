package ${package}.capabilities;

import ${package}.items.IItemHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's IItemHandler and Fabric API's item Storage, each presented as the other, so Capabilities.ItemHandler.BLOCK
 * is Fabric's ItemStorage.SIDED: a converted mod's inventories take part in hoppers and Fabric mods' pipes, and theirs in
 * its own code. A handler as a Storage changes at once and is undone when the transaction is aborted (UndoLog); a Storage
 * as a handler runs each call in its own transaction, committed unless simulated.
 */
public final class ItemStorages {

    private ItemStorages() {}

    public static Storage<ItemVariant> storage(IItemHandler handler) {
        return handler instanceof HandlerOfStorage wrapped ? wrapped.storage : new StorageOfHandler(handler);
    }

    public static IItemHandler handler(Storage<ItemVariant> storage) {
        return storage instanceof StorageOfHandler wrapped ? wrapped.handler : new HandlerOfStorage(storage);
    }

    static final class StorageOfHandler implements SlottedStorage<ItemVariant> {
        final IItemHandler handler;
        final UndoLog log = new UndoLog();

        StorageOfHandler(IItemHandler handler) {
            this.handler = handler;
        }

        @Override
        public int getSlotCount() {
            return handler.getSlots();
        }

        @Override
        public SingleSlotStorage<ItemVariant> getSlot(int slot) {
            return new Slot(slot);
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            long inserted = 0;
            for (int slot = 0; slot < handler.getSlots() && inserted < maxAmount; slot++) inserted += getSlot(slot).insert(resource, maxAmount - inserted, transaction);
            return inserted;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            long extracted = 0;
            for (int slot = 0; slot < handler.getSlots() && extracted < maxAmount; slot++) extracted += getSlot(slot).extract(resource, maxAmount - extracted, transaction);
            return extracted;
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            List<StorageView<ItemVariant>> views = new ArrayList<>();
            for (int slot = 0; slot < handler.getSlots(); slot++) views.add(new Slot(slot));
            return views.iterator();
        }

        final class Slot implements SingleSlotStorage<ItemVariant> {
            final int slot;

            Slot(int slot) {
                this.slot = slot;
            }

            @Override
            public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
                if (resource.isBlank() || maxAmount <= 0) return 0;
                ItemStack offered = resource.toStack((int) Math.min(maxAmount, Integer.MAX_VALUE));
                int inserted = offered.getCount() - handler.insertItem(slot, offered.copy(), true).getCount();
                if (inserted <= 0) return 0;
                ItemStack left = handler.insertItem(slot, resource.toStack(inserted), false);
                int done = inserted - left.getCount();
                if (done > 0) log.done(transaction, () -> handler.extractItem(slot, done, false));
                return done;
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
                if (resource.isBlank() || maxAmount <= 0 || !resource.matches(handler.getStackInSlot(slot))) return 0;
                ItemStack simulated = handler.extractItem(slot, (int) Math.min(maxAmount, Integer.MAX_VALUE), true);
                if (simulated.isEmpty() || !resource.matches(simulated)) return 0;
                ItemStack taken = handler.extractItem(slot, simulated.getCount(), false);
                if (taken.isEmpty()) return 0;
                log.done(transaction, () -> handler.insertItem(slot, taken, false));
                return taken.getCount();
            }

            @Override
            public boolean isResourceBlank() {
                return handler.getStackInSlot(slot).isEmpty();
            }

            @Override
            public ItemVariant getResource() {
                return ItemVariant.of(handler.getStackInSlot(slot));
            }

            @Override
            public long getAmount() {
                return handler.getStackInSlot(slot).getCount();
            }

            @Override
            public long getCapacity() {
                ItemStack stack = handler.getStackInSlot(slot);
                return stack.isEmpty() ? handler.getSlotLimit(slot) : Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
            }
        }
    }

    static final class HandlerOfStorage implements IItemHandler {
        final Storage<ItemVariant> storage;

        HandlerOfStorage(Storage<ItemVariant> storage) {
            this.storage = storage;
        }

        List<StorageView<ItemVariant>> views() {
            List<StorageView<ItemVariant>> views = new ArrayList<>();
            for (StorageView<ItemVariant> view : storage) views.add(view);
            return views;
        }

        StorageView<ItemVariant> view(int slot) {
            if (storage instanceof SlottedStorage<ItemVariant> slotted) return slot < slotted.getSlotCount() ? slotted.getSlot(slot) : null;
            List<StorageView<ItemVariant>> views = views();
            return slot < views.size() ? views.get(slot) : null;
        }

        @Override
        public int getSlots() {
            return storage instanceof SlottedStorage<ItemVariant> slotted ? slotted.getSlotCount() : views().size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            StorageView<ItemVariant> view = view(slot);
            if (view == null || view.isResourceBlank()) return ItemStack.EMPTY;
            return view.getResource().toStack((int) Math.min(view.getAmount(), Integer.MAX_VALUE));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            Storage<ItemVariant> target = storage instanceof SlottedStorage<ItemVariant> slotted && slot < slotted.getSlotCount() ? slotted.getSlot(slot) : storage;
            try (Transaction transaction = UndoLog.open()) {
                long inserted = target.insert(ItemVariant.of(stack), stack.getCount(), transaction);
                if (!simulate) transaction.commit();
                return inserted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) inserted);
            }
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            StorageView<ItemVariant> view = view(slot);
            if (amount <= 0 || view == null || view.isResourceBlank()) return ItemStack.EMPTY;
            ItemVariant resource = view.getResource();
            try (Transaction transaction = UndoLog.open()) {
                long extracted = view.extract(resource, Math.min(amount, resource.getItem().getDefaultMaxStackSize()), transaction);
                if (!simulate) transaction.commit();
                return extracted <= 0 ? ItemStack.EMPTY : resource.toStack((int) extracted);
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            StorageView<ItemVariant> view = view(slot);
            return view == null ? 0 : (int) Math.min(view.getCapacity(), Integer.MAX_VALUE);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return insertItem(slot, stack.copyWithCount(1), true).isEmpty();
        }
    }
}
