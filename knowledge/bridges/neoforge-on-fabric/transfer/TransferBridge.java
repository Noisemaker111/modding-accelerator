package ${package}.transfer;

import ${package}.fluids.FluidType;
import ${package}.transfer.access.ItemAccess;
import ${package}.transfer.energy.EnergyHandler;
import ${package}.transfer.fluid.FluidResource;
import ${package}.transfer.item.ItemResource;
import ${package}.transfer.resource.Resource;
import ${package}.transfer.transaction.Transaction;
import ${package}.transfer.transaction.TransactionContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.lookup.v1.entity.EntityApiLookup;
import net.fabricmc.fabric.api.lookup.v1.item.ItemApiLookup;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;

/**
 * How NeoForge's transfer API crosses to Fabric API's Transfer API in the bridge (modaccel convert). Not a NeoForge class.
 *
 * <p>Items: a ResourceHandler of ItemResource is a Storage of ItemVariant (a SlottedStorage, one slot per index) and back;
 * Capabilities.Item.BLOCK is Fabric's ItemStorage.SIDED and Capabilities.Item.ITEM is ItemStorage.ITEM, so Fabric mods
 * see a converted mod's handlers and the converted mod sees theirs. A handler that went out and comes back is the same
 * object again. Item amounts are ints here and longs on Fabric: larger Fabric amounts read as Integer.MAX_VALUE.
 *
 * <p>Fluids: as items, over FluidStorage.SIDED and FluidStorage.ITEM, with amounts converted at 81 droplets (Fabric's
 * unit, FluidConstants.BUCKET = 81000) per millibucket (NeoForge's, FluidType.BUCKET_VOLUME = 1000). A converted handler
 * seen from Fabric reports amounts times 81 and moves only whole millibuckets: an insert or extract of fewer than 81
 * droplets moves nothing, and the rest of a request below a multiple of 81 is left. A Fabric storage seen from a converted
 * mod reports amounts divided by 81, rounded down (a remainder below 81 droplets is not visible as millibuckets), and an
 * operation moves whole millibuckets only: when the storage would move a part of one, the bridge aborts that try in a
 * nested transaction and asks again for the whole millibuckets it took.
 *
 * <p>Energy: Fabric API has no energy API. Capabilities.Energy.BLOCK, ENTITY and ITEM are lookups the bridge creates
 * (neoforge:energy_handler) whose objects are Fabric API Storage of net.minecraft.util.Unit, the amount being the energy.
 * Every mod converted from NeoForge creates the same lookups, so they see each other's energy; Fabric mods do not query
 * them. Entities: Fabric API has no item or fluid lookup for entities, so Item.ENTITY, Item.ENTITY_AUTOMATION and
 * Fluid.ENTITY are lookups the bridge creates too (also seen by converted mods only).
 *
 * <p>Transactions are Fabric's (see transaction.Transaction): a converted handler called from a Fabric storage takes part
 * in the caller's transaction, and a converted mod's transaction is the one a Fabric storage it calls takes part in.
 */
public final class TransferBridge {

    /** Fabric's droplets in one NeoForge millibucket: FluidConstants.BUCKET / FluidType.BUCKET_VOLUME = 81. */
    public static final long DROPLETS_PER_MILLIBUCKET = FluidConstants.BUCKET / FluidType.BUCKET_VOLUME;

    public static final BlockApiLookup<Storage<Unit>, @Nullable Direction> ENERGY_SIDED = BlockApiLookup.get(id("energy_handler"), Storage.asClass(), Direction.class);
    public static final EntityApiLookup<Storage<Unit>, @Nullable Direction> ENERGY_ENTITY = EntityApiLookup.get(id("energy_handler"), Storage.asClass(), Direction.class);
    public static final ItemApiLookup<Storage<Unit>, ContainerItemContext> ENERGY_ITEM = ItemApiLookup.get(id("energy_handler"), Storage.asClass(), ContainerItemContext.class);
    public static final EntityApiLookup<Storage<ItemVariant>, @Nullable Void> ITEM_ENTITY = EntityApiLookup.get(id("item_handler"), Storage.asClass(), Void.class);
    public static final EntityApiLookup<Storage<ItemVariant>, @Nullable Direction> ITEM_ENTITY_AUTOMATION = EntityApiLookup.get(id("item_handler_automation"), Storage.asClass(), Direction.class);
    public static final EntityApiLookup<Storage<FluidVariant>, @Nullable Direction> FLUID_ENTITY = EntityApiLookup.get(id("fluid_handler"), Storage.asClass(), Direction.class);

    static final Kind<ItemResource, ItemVariant> ITEMS = new Kind<>(1, ItemResource.EMPTY, TransferBridge::variant, TransferBridge::resource);
    static final Kind<FluidResource, FluidVariant> FLUIDS = new Kind<>(DROPLETS_PER_MILLIBUCKET, FluidResource.EMPTY, TransferBridge::variant, TransferBridge::resource);

    private TransferBridge() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neoforge", path);
    }

    // ---- resources and variants -------------------------------------------------------------------------

    public static ItemVariant variant(ItemResource resource) {
        return resource.isEmpty() ? ItemVariant.blank() : ItemVariant.of(resource.getItem(), resource.getComponentsPatch());
    }

    public static ItemResource resource(ItemVariant variant) {
        return variant.isBlank() ? ItemResource.EMPTY : ItemResource.of(variant.getItem(), variant.getComponentsPatch());
    }

    public static FluidVariant variant(FluidResource resource) {
        return resource.isEmpty() ? FluidVariant.blank() : FluidVariant.of(resource.getFluid(), resource.getComponentsPatch());
    }

    public static FluidResource resource(FluidVariant variant) {
        return variant.isBlank() ? FluidResource.EMPTY : FluidResource.of(variant.getFluid(), variant.getComponentsPatch());
    }

    // ---- handlers and storages --------------------------------------------------------------------------

    public static @Nullable Storage<ItemVariant> itemStorage(@Nullable ResourceHandler<ItemResource> handler) {
        return storage(handler, ITEMS);
    }

    public static @Nullable ResourceHandler<ItemResource> itemHandler(@Nullable Storage<ItemVariant> storage) {
        return handler(storage, ITEMS);
    }

    public static @Nullable Storage<FluidVariant> fluidStorage(@Nullable ResourceHandler<FluidResource> handler) {
        return storage(handler, FLUIDS);
    }

    public static @Nullable ResourceHandler<FluidResource> fluidHandler(@Nullable Storage<FluidVariant> storage) {
        return handler(storage, FLUIDS);
    }

    /** One index of an item handler as a Fabric slot. */
    public static SingleSlotStorage<ItemVariant> itemSlot(ResourceHandler<ItemResource> handler, int index) {
        return new HandlerStorage<>(handler, ITEMS).getSlot(index);
    }

    public static @Nullable Storage<Unit> energyStorage(@Nullable EnergyHandler handler) {
        if (handler == null) return null;
        if (handler instanceof StorageEnergy energy) return energy.storage;
        return new EnergyStorage(handler);
    }

    public static @Nullable EnergyHandler energyHandler(@Nullable Storage<Unit> storage) {
        if (storage == null) return null;
        if (storage instanceof EnergyStorage energy) return energy.handler;
        return new StorageEnergy(storage);
    }

    // ---- item access and container item context ----------------------------------------------------------

    public static @Nullable ItemAccess access(@Nullable ContainerItemContext context) {
        if (context == null) return null;
        if (context instanceof AccessContext access) return access.access;
        return new ContextAccess(context);
    }

    public static @Nullable ContainerItemContext context(@Nullable ItemAccess access) {
        if (access == null) return null;
        if (access instanceof ContextAccess context) return context.context;
        return new AccessContext(access);
    }

    // ---- checks, as NeoForge's TransferPreconditions -------------------------------------------------------

    public static int saturated(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }

    static long scaled(long amount, long scale) {
        return amount > Long.MAX_VALUE / scale ? Long.MAX_VALUE : amount * scale;
    }

    public static void checkNonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Expected value to be non-negative: " + value);
    }

    public static void checkNonEmpty(Resource resource) {
        if (resource.isEmpty()) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
    }

    public static void checkNonEmptyNonNegative(Resource resource, long value) {
        checkNonEmpty(resource);
        checkNonNegative(value);
    }

    // ---- adapters ------------------------------------------------------------------------------------------

    /** One resource kind's conversions: droplets per unit (scale), the empty resource, resource to variant and back. */
    record Kind<R extends Resource, V extends TransferVariant<?>>(long scale, R empty, Function<R, V> toVariant, Function<V, R> toResource) {}

    @SuppressWarnings("unchecked")
    private static <R extends Resource, V extends TransferVariant<?>> @Nullable Storage<V> storage(@Nullable ResourceHandler<R> handler, Kind<R, V> kind) {
        if (handler == null) return null;
        if (handler instanceof StorageHandler<?, ?> wrapped && wrapped.kind == kind) return (Storage<V>) wrapped.storage;
        return new HandlerStorage<>(handler, kind);
    }

    @SuppressWarnings("unchecked")
    private static <R extends Resource, V extends TransferVariant<?>> @Nullable ResourceHandler<R> handler(@Nullable Storage<V> storage, Kind<R, V> kind) {
        if (storage == null) return null;
        if (storage instanceof HandlerStorage<?, ?> wrapped && wrapped.kind == kind) return (ResourceHandler<R>) wrapped.handler;
        return new StorageHandler<>(storage, kind);
    }

    /** A converted mod's handler, as Fabric mods see it: one slot per index. */
    static final class HandlerStorage<R extends Resource, V extends TransferVariant<?>> implements SlottedStorage<V> {

        final ResourceHandler<R> handler;
        final Kind<R, V> kind;

        HandlerStorage(ResourceHandler<R> handler, Kind<R, V> kind) {
            this.handler = handler;
            this.kind = kind;
        }

        @Override
        public long insert(V variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(variant, maxAmount);
            int amount = saturated(maxAmount / kind.scale());
            if (amount == 0) return 0;
            return (long) handler.insert(kind.toResource().apply(variant), amount, Transaction.of(transaction)) * kind.scale();
        }

        @Override
        public long extract(V variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(variant, maxAmount);
            int amount = saturated(maxAmount / kind.scale());
            if (amount == 0) return 0;
            return (long) handler.extract(kind.toResource().apply(variant), amount, Transaction.of(transaction)) * kind.scale();
        }

        @Override
        public Iterator<StorageView<V>> iterator() {
            List<StorageView<V>> views = new ArrayList<>();
            int size = handler.size();
            for (int index = 0; index < size; index++) views.add(new Slot(index));
            return views.iterator();
        }

        @Override
        public int getSlotCount() {
            return handler.size();
        }

        @Override
        public SingleSlotStorage<V> getSlot(int slot) {
            Objects.checkIndex(slot, handler.size());
            return new Slot(slot);
        }

        @Override
        public String toString() {
            return "Storage[" + handler + "]";
        }

        private final class Slot implements SingleSlotStorage<V> {

            private final int index;

            Slot(int index) {
                this.index = index;
            }

            @Override
            public long insert(V variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                int amount = saturated(maxAmount / kind.scale());
                if (amount == 0) return 0;
                return (long) handler.insert(index, kind.toResource().apply(variant), amount, Transaction.of(transaction)) * kind.scale();
            }

            @Override
            public long extract(V variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                int amount = saturated(maxAmount / kind.scale());
                if (amount == 0) return 0;
                return (long) handler.extract(index, kind.toResource().apply(variant), amount, Transaction.of(transaction)) * kind.scale();
            }

            @Override
            public boolean isResourceBlank() {
                return handler.getResource(index).isEmpty();
            }

            @Override
            public V getResource() {
                return kind.toVariant().apply(handler.getResource(index));
            }

            @Override
            public long getAmount() {
                return scaled(handler.getAmountAsLong(index), kind.scale());
            }

            @Override
            public long getCapacity() {
                return scaled(handler.getCapacityAsLong(index, handler.getResource(index)), kind.scale());
            }
        }
    }

    /** A Fabric storage, as a converted mod sees it: its slots (or its views, for a storage without slots) are the indices. */
    static final class StorageHandler<R extends Resource, V extends TransferVariant<?>> implements ResourceHandler<R> {

        final Storage<V> storage;
        final Kind<R, V> kind;

        StorageHandler(Storage<V> storage, Kind<R, V> kind) {
            this.storage = storage;
            this.kind = kind;
        }

        private List<StorageView<V>> views() {
            List<StorageView<V>> views = new ArrayList<>();
            for (StorageView<V> view : storage) views.add(view);
            return views;
        }

        private StorageView<V> view(int index) {
            if (storage instanceof SlottedStorage<V> slotted) return slotted.getSlot(index);
            List<StorageView<V>> views = views();
            Objects.checkIndex(index, views.size());
            return views.get(index);
        }

        @Override
        public int size() {
            return storage instanceof SlottedStorage<V> slotted ? slotted.getSlotCount() : views().size();
        }

        @Override
        public R getResource(int index) {
            StorageView<V> view = view(index);
            return view.isResourceBlank() ? kind.empty() : kind.toResource().apply(view.getResource());
        }

        @Override
        public long getAmountAsLong(int index) {
            return view(index).getAmount() / kind.scale();
        }

        @Override
        public long getCapacityAsLong(int index, R resource) {
            return view(index).getCapacity() / kind.scale();
        }

        @Override
        public boolean isValid(int index, R resource) {
            return storage.supportsInsertion();
        }

        /** Into the slot at index; a storage without slots takes it as a whole. */
        @Override
        public int insert(int index, R resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            Storage<V> target = storage instanceof SlottedStorage<V> slotted ? slotted.getSlot(index) : storage;
            return move(target::insert, resource, amount, transaction);
        }

        @Override
        public int insert(R resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return move(storage::insert, resource, amount, transaction);
        }

        @Override
        public int extract(int index, R resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            StorageView<V> view = view(index);
            return move(view::extract, resource, amount, transaction);
        }

        @Override
        public int extract(R resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return move(storage::extract, resource, amount, transaction);
        }

        /** One insert or extract of whole units (millibuckets for fluids), in the caller's Fabric transaction. */
        private int move(Operation<V> operation, R resource, int amount, TransactionContext transaction) {
            if (amount == 0) return 0;
            V variant = kind.toVariant().apply(resource);
            var fabric = Transaction.fabric(transaction);
            long scale = kind.scale();
            long wanted = (long) amount * scale;
            if (scale == 1) return saturated(operation.apply(variant, wanted, fabric));
            while (wanted > 0) {
                try (var nested = fabric.openNested()) {
                    long moved = operation.apply(variant, wanted, nested);
                    if (moved % scale == 0) {
                        nested.commit();
                        return saturated(moved / scale);
                    }
                    // A part of a millibucket: abort this try, ask again for the whole millibuckets it moved.
                    wanted = moved / scale * scale;
                }
            }
            return 0;
        }

        @Override
        public String toString() {
            return "ResourceHandler[" + storage + "]";
        }
    }

    @FunctionalInterface
    interface Operation<V> {
        long apply(V variant, long amount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction);
    }

    /** A converted mod's energy handler on the bridge's energy lookups. */
    static final class EnergyStorage implements SingleSlotStorage<Unit> {

        final EnergyHandler handler;

        EnergyStorage(EnergyHandler handler) {
            this.handler = handler;
        }

        @Override
        public long insert(Unit unit, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            return handler.insert(saturated(maxAmount), Transaction.of(transaction));
        }

        @Override
        public long extract(Unit unit, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            return handler.extract(saturated(maxAmount), Transaction.of(transaction));
        }

        @Override
        public boolean isResourceBlank() {
            return false;
        }

        @Override
        public Unit getResource() {
            return Unit.INSTANCE;
        }

        @Override
        public long getAmount() {
            return handler.getAmountAsLong();
        }

        @Override
        public long getCapacity() {
            return handler.getCapacityAsLong();
        }
    }

    /** Energy from another converted mod's bridge (its own EnergyHandler class), as this mod's EnergyHandler. */
    static final class StorageEnergy implements EnergyHandler {

        final Storage<Unit> storage;

        StorageEnergy(Storage<Unit> storage) {
            this.storage = storage;
        }

        @Override
        public long getAmountAsLong() {
            long amount = 0;
            for (StorageView<Unit> view : storage) amount = Math.addExact(amount, view.getAmount());
            return amount;
        }

        @Override
        public long getCapacityAsLong() {
            long capacity = 0;
            for (StorageView<Unit> view : storage) capacity = Math.addExact(capacity, view.getCapacity());
            return capacity;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            checkNonNegative(amount);
            return saturated(storage.insert(Unit.INSTANCE, amount, Transaction.fabric(transaction)));
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            checkNonNegative(amount);
            return saturated(storage.extract(Unit.INSTANCE, amount, Transaction.fabric(transaction)));
        }
    }

    /** A Fabric ContainerItemContext as NeoForge's ItemAccess. */
    static final class ContextAccess implements ItemAccess {

        final ContainerItemContext context;

        ContextAccess(ContainerItemContext context) {
            this.context = context;
        }

        @Override
        public ItemResource getResource() {
            return resource(context.getItemVariant());
        }

        @Override
        public int getAmount() {
            return saturated(context.getAmount());
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return saturated(context.insert(variant(resource), amount, Transaction.fabric(transaction)));
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return saturated(context.extract(variant(resource), amount, Transaction.fabric(transaction)));
        }
    }

    /** NeoForge's ItemAccess as a Fabric ContainerItemContext: its item is the main slot; it has no additional slots. */
    static final class AccessContext implements ContainerItemContext {

        final ItemAccess access;

        AccessContext(ItemAccess access) {
            this.access = access;
        }

        @Override
        public SingleSlotStorage<ItemVariant> getMainSlot() {
            return new SingleSlotStorage<>() {
                @Override
                public long insert(ItemVariant variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                    StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                    return access.insert(resource(variant), saturated(maxAmount), Transaction.of(transaction));
                }

                @Override
                public long extract(ItemVariant variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                    StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                    return access.extract(resource(variant), saturated(maxAmount), Transaction.of(transaction));
                }

                @Override
                public boolean isResourceBlank() {
                    return access.getResource().isEmpty();
                }

                @Override
                public ItemVariant getResource() {
                    return variant(access.getResource());
                }

                @Override
                public long getAmount() {
                    return access.getAmount();
                }

                @Override
                public long getCapacity() {
                    ItemResource resource = access.getResource();
                    return resource.isEmpty() ? 64 : resource.getMaxStackSize();
                }
            };
        }

        @Override
        public long insertOverflow(ItemVariant variant, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(variant, maxAmount);
            return access.insert(resource(variant), saturated(maxAmount), Transaction.of(transaction));
        }

        @Override
        public List<SingleSlotStorage<ItemVariant>> getAdditionalSlots() {
            return List.of();
        }
    }
}
