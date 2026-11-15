package ${package};

import ${package}.fabric.api.lookup.v1.block.BlockApiLookup;
import ${package}.fabric.api.transfer.v1.fluid.FluidVariant;
import ${package}.fabric.api.transfer.v1.item.ItemVariant;
import ${package}.fabric.api.transfer.v1.storage.SlottedStorage;
import ${package}.fabric.api.transfer.v1.storage.Storage;
import ${package}.fabric.api.transfer.v1.storage.StoragePreconditions;
import ${package}.fabric.api.transfer.v1.storage.StorageView;
import ${package}.fabric.api.transfer.v1.storage.TransferVariant;
import ${package}.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import ${package}.fabric.api.transfer.v1.transaction.Transaction;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import ${package}.team.reborn.energy.api.EnergyStorage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/**
 * How Fabric API's Transfer and lookup APIs (mirrored under fabric.api) and Team Reborn Energy's EnergyStorage cross to
 * NeoForge's capabilities and transfer API in the bridge (modaccel convert). Not a Fabric class.
 *
 * <p>Lookups: BlockApiLookup.get names a lookup; the bridge makes it a NeoForge BlockCapability. ItemStorage.SIDED is
 * Capabilities.Item.BLOCK, FluidStorage.SIDED is Capabilities.Fluid.BLOCK and Team Reborn's EnergyStorage.SIDED is
 * Capabilities.Energy.BLOCK, the Storage converted to and from NeoForge's ResourceHandler or EnergyHandler, so NeoForge
 * mods see the converted mod's storages and it sees theirs. Any other lookup is BlockCapability.create(id, apiClass,
 * contextClass). Providers registered with a lookup are registered in RegisterCapabilitiesEvent on the mod's bus
 * (TransferHooks); fallback providers last (EventPriority.LOWEST), for every block. A provider registered after that
 * event is not seen. A null direction is NeoForge's null context: the same "no side" both loaders mean.
 *
 * <p>Amounts: items are longs on Fabric and ints on NeoForge (a larger NeoForge-bound request is capped at
 * Integer.MAX_VALUE). Fluids are droplets on Fabric (FluidConstants.BUCKET = 81000) and millibuckets on NeoForge (1000):
 * a NeoForge handler seen through Fabric's API reports amounts times 81 and moves whole millibuckets only (fewer than 81
 * droplets move nothing; the rest of a request below a multiple of 81 is left). A converted Fabric storage seen from
 * NeoForge reports amounts divided by 81, rounded down (below 81 droplets is not visible as millibuckets), and moves whole
 * millibuckets: when the storage would move a part of one, the bridge aborts that try and asks again for the whole
 * millibuckets it took. Energy has the same unit on both (Team Reborn's E and NeoForge's FE), capped at Integer.MAX_VALUE.
 *
 * <p>Transactions: every Fabric transaction here is a NeoForge transaction (Transaction.openOuter is NeoForge's
 * openRoot, openNested is open(parent), nestingDepth is depth), so both loaders' handlers take part in the same ones; both
 * are nested and abort on close, as Fabric's are. SnapshotParticipant is a NeoForge SnapshotJournal (snapshots per depth,
 * readSnapshot on abort, onFinalCommit after the outer commit). Close callbacks of a transaction the bridge opened are
 * called when it closes, with its result, as on Fabric. A NeoForge caller's transaction is seen as a Fabric context, and
 * the bridge opens a transaction nested in it around each call into a Fabric storage; close callbacks added to the
 * NeoForge caller's own transaction are told the result late: ABORTED when it or an enclosing transaction aborts,
 * COMMITTED when the root commits. getOpenTransaction cannot reach past the NeoForge caller's transaction.
 *
 * <p>Not mirrored: ItemStorage.ITEM and FluidStorage.ITEM (ContainerItemContext), the entity and item lookups, Team Reborn
 * Energy's ITEM lookup and ENERGY_COMPONENT.
 */
public final class TransferBridge {

    /** Fabric's droplets in one NeoForge millibucket: 81000 / 1000. */
    public static final long DROPLETS_PER_MILLIBUCKET = 81;

    private static final AtomicLong VERSION = new AtomicLong();

    private TransferBridge() {}

    /** Storage.getVersion's default: a new number on every call. */
    public static long nextVersion() {
        return VERSION.getAndIncrement();
    }

    public static int saturated(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }

    static long scaled(long amount, long scale) {
        return amount > Long.MAX_VALUE / scale ? Long.MAX_VALUE : amount * scale;
    }

    // ---- variants ---------------------------------------------------------------------------------------------

    private static final Map<Item, ItemVariant> ITEM_VARIANTS = new ConcurrentHashMap<>();
    private static final Map<Fluid, FluidVariant> FLUID_VARIANTS = new ConcurrentHashMap<>();

    public static ItemVariant itemVariant(Item item, DataComponentPatch patch) {
        Objects.requireNonNull(item, "Item may not be null.");
        Objects.requireNonNull(patch, "Components may not be null.");
        if (patch.isEmpty() || item == Items.AIR) return ITEM_VARIANTS.computeIfAbsent(item, each -> new ItemVariantImpl(each, DataComponentPatch.EMPTY));
        return new ItemVariantImpl(item, patch);
    }

    /** A flowing fluid's variant is its source's, as on Fabric. */
    public static FluidVariant fluidVariant(Fluid fluid, DataComponentPatch patch) {
        Objects.requireNonNull(fluid, "Fluid may not be null.");
        Objects.requireNonNull(patch, "Components may not be null.");
        if (fluid != Fluids.EMPTY && !fluid.isSource(fluid.defaultFluidState())) {
            if (!(fluid instanceof FlowingFluid flowing)) throw new IllegalArgumentException("Cannot convert flowing fluid " + BuiltInRegistries.FLUID.getKey(fluid) + " into a still fluid.");
            fluid = flowing.getSource();
        }
        if (patch.isEmpty() || fluid == Fluids.EMPTY) return FLUID_VARIANTS.computeIfAbsent(fluid, each -> new FluidVariantImpl(each, DataComponentPatch.EMPTY));
        return new FluidVariantImpl(fluid, patch);
    }

    public static ItemResource resource(ItemVariant variant) {
        return variant.isBlank() ? ItemResource.EMPTY : ItemResource.of(variant.getItem(), variant.getComponentsPatch());
    }

    public static ItemVariant variant(ItemResource resource) {
        return resource.isEmpty() ? ItemVariant.blank() : itemVariant(resource.getItem(), resource.getComponentsPatch());
    }

    public static FluidResource resource(FluidVariant variant) {
        return variant.isBlank() ? FluidResource.EMPTY : FluidResource.of(variant.getFluid(), variant.getComponentsPatch());
    }

    public static FluidVariant variant(FluidResource resource) {
        return resource.isEmpty() ? FluidVariant.blank() : fluidVariant(resource.getFluid(), resource.getComponentsPatch());
    }

    static final class ItemVariantImpl implements ItemVariant {

        private final Item item;
        private final DataComponentPatch patch;
        private final DataComponentMap components;
        private final int hashCode;

        ItemVariantImpl(Item item, DataComponentPatch patch) {
            this.item = item;
            this.patch = patch;
            this.components = patch.isEmpty() ? item.components() : PatchedDataComponentMap.fromPatch(item.components(), patch);
            this.hashCode = Objects.hash(item, patch);
        }

        @Override
        public boolean isBlank() {
            return item == Items.AIR;
        }

        @Override
        public Item getObject() {
            return item;
        }

        @Override
        public DataComponentPatch getComponentsPatch() {
            return patch;
        }

        @Override
        public DataComponentMap getComponents() {
            return components;
        }

        @Override
        public ItemVariant withComponents(DataComponentPatch changes) {
            if (isBlank() || changes.isEmpty()) return this;
            ItemStack stack = toStack();
            stack.applyComponents(changes);
            return ItemVariant.of(stack);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof ItemVariantImpl other && item == other.item && patch.equals(other.patch);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return "ItemVariant{item=" + BuiltInRegistries.ITEM.getKey(item) + ", components=" + patch + "}";
        }
    }

    static final class FluidVariantImpl implements FluidVariant {

        private final Fluid fluid;
        private final DataComponentPatch patch;
        private final DataComponentMap components;
        private final int hashCode;

        FluidVariantImpl(Fluid fluid, DataComponentPatch patch) {
            this.fluid = fluid;
            this.patch = patch;
            this.components = patch.isEmpty() ? DataComponentMap.EMPTY : PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY, patch);
            this.hashCode = Objects.hash(fluid, patch);
        }

        @Override
        public boolean isBlank() {
            return fluid == Fluids.EMPTY;
        }

        @Override
        public Fluid getObject() {
            return fluid;
        }

        @Override
        public DataComponentPatch getComponentsPatch() {
            return patch;
        }

        @Override
        public DataComponentMap getComponents() {
            return components;
        }

        @Override
        public FluidVariant withComponents(DataComponentPatch changes) {
            if (isBlank() || changes.isEmpty()) return this;
            PatchedDataComponentMap map = PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY, patch);
            map.applyPatch(changes);
            return fluidVariant(fluid, map.asPatch());
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof FluidVariantImpl other && fluid == other.fluid && patch.equals(other.patch);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return "FluidVariant{fluid=" + BuiltInRegistries.FLUID.getKey(fluid) + ", components=" + patch + "}";
        }
    }

    // ---- transactions -----------------------------------------------------------------------------------------

    /** The bridge's Fabric transaction for each NeoForge transaction it opened, on this thread. */
    private static final ThreadLocal<Map<net.neoforged.neoforge.transfer.transaction.TransactionContext, Tx>> OPENED = ThreadLocal.withInitial(IdentityHashMap::new);
    /** Set while the bridge runs a transaction's close callbacks (Fabric's CLOSING), or its outer close callbacks. */
    private static final ThreadLocal<Transaction.Lifecycle> CLOSING = new ThreadLocal<>();

    public static Transaction openOuter() {
        return new Tx(net.neoforged.neoforge.transfer.transaction.Transaction.openRoot(), null, true);
    }

    public static Transaction.Lifecycle lifecycle() {
        Transaction.Lifecycle closing = CLOSING.get();
        if (closing != null) return closing;
        return switch (net.neoforged.neoforge.transfer.transaction.Transaction.getLifecycle()) {
            case NONE -> Transaction.Lifecycle.NONE;
            case OPEN -> Transaction.Lifecycle.OPEN;
            case CLOSING -> Transaction.Lifecycle.CLOSING;
            case ROOT_CLOSING -> Transaction.Lifecycle.OUTER_CLOSING;
        };
    }

    @SuppressWarnings("deprecation")
    public static @Nullable TransactionContext currentUnsafe() {
        var current = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
        return current == null ? null : fabric(current);
    }

    /** The NeoForge transaction a Fabric context is. */
    public static net.neoforged.neoforge.transfer.transaction.TransactionContext neo(TransactionContext context) {
        return ((Tx) context).neo;
    }

    /** A NeoForge context as a Fabric one: the bridge's own when it opened it, else a view of the NeoForge caller's. */
    public static Transaction fabric(net.neoforged.neoforge.transfer.transaction.TransactionContext context) {
        Tx opened = OPENED.get().get(context);
        return opened != null ? opened : new Tx(context, null, false);
    }

    /** A Fabric transaction that is a NeoForge transaction. */
    static final class Tx implements Transaction {

        final net.neoforged.neoforge.transfer.transaction.TransactionContext neo;
        private final @Nullable Tx parent;
        /** Whether the bridge opened it: then the bridge commits or closes it and calls its callbacks exactly. */
        private final boolean owned;
        private boolean open = true;
        private final List<CloseCallback> closeCallbacks = new ArrayList<>();
        private final List<OuterCloseCallback> outerCloseCallbacks = new ArrayList<>();

        Tx(net.neoforged.neoforge.transfer.transaction.TransactionContext neo, @Nullable Tx parent, boolean owned) {
            this.neo = neo;
            this.parent = parent;
            this.owned = owned;
            if (owned) OPENED.get().put(neo, this);
        }

        @Override
        public Transaction openNested() {
            return new Tx(net.neoforged.neoforge.transfer.transaction.Transaction.open(neo), this, true);
        }

        @Override
        public int nestingDepth() {
            return neo.depth();
        }

        @Override
        public Transaction getOpenTransaction(int nestingDepth) {
            if (nestingDepth < 0) throw new IndexOutOfBoundsException("Nesting depth may not be negative.");
            for (Tx tx = this; tx != null; tx = tx.parent) {
                if (tx.nestingDepth() == nestingDepth) return tx;
            }
            throw new IllegalStateException("The transaction at depth " + nestingDepth + " was opened by NeoForge code the bridge cannot see past.");
        }

        @Override
        public void addCloseCallback(CloseCallback closeCallback) {
            if (owned) {
                closeCallbacks.add(closeCallback);
                return;
            }
            // The NeoForge caller's transaction: a journal tells the result as NeoForge closes it.
            new CallbackJournal(this, closeCallback).updateSnapshots(neo);
        }

        @Override
        public void addOuterCloseCallback(OuterCloseCallback outerCloseCallback) {
            Tx root = this;
            while (root.parent != null) root = root.parent;
            if (root.owned && root.nestingDepth() == 0) {
                root.outerCloseCallbacks.add(outerCloseCallback);
                return;
            }
            new CallbackJournal(outerCloseCallback).updateSnapshots(neo);
        }

        @Override
        public void abort() {
            close(Result.ABORTED);
        }

        @Override
        public void commit() {
            close(Result.COMMITTED);
        }

        @Override
        public void close() {
            if (open) close(Result.ABORTED);
        }

        private void close(Result result) {
            if (!owned) throw new IllegalStateException("A transaction NeoForge code opened is closed by that code.");
            if (!open) throw new IllegalStateException("Transaction operation cannot be applied to a closed transaction.");
            open = false;
            var neoTransaction = (net.neoforged.neoforge.transfer.transaction.Transaction) neo;
            CLOSING.set(Lifecycle.CLOSING);
            try {
                for (int i = 0; i < closeCallbacks.size(); i++) closeCallbacks.get(i).onClose(this, result);
            } finally {
                CLOSING.remove();
                OPENED.get().remove(neo);
            }
            if (result.wasCommitted()) neoTransaction.commit();
            else neoTransaction.close();
            if (nestingDepth() != 0 || outerCloseCallbacks.isEmpty()) return;
            CLOSING.set(Lifecycle.OUTER_CLOSING);
            try {
                for (OuterCloseCallback callback : outerCloseCallbacks) callback.afterOuterClose(result);
            } finally {
                CLOSING.remove();
            }
        }

        @Override
        public String toString() {
            return "Transaction[depth=" + nestingDepth() + ", neoforge=" + neo + "]";
        }
    }

    /** A Fabric callback on a transaction NeoForge code opened, told the result through a NeoForge journal. */
    private static final class CallbackJournal extends net.neoforged.neoforge.transfer.transaction.SnapshotJournal<Boolean> {

        private final @Nullable Tx transaction;
        private final TransactionContext.@Nullable CloseCallback close;
        private final TransactionContext.@Nullable OuterCloseCallback outer;
        private boolean told;

        CallbackJournal(Tx transaction, TransactionContext.CloseCallback close) {
            this.transaction = transaction;
            this.close = close;
            this.outer = null;
        }

        CallbackJournal(TransactionContext.OuterCloseCallback outer) {
            this.transaction = null;
            this.close = null;
            this.outer = outer;
        }

        @Override
        protected Boolean createSnapshot() {
            return Boolean.TRUE;
        }

        @Override
        protected void revertToSnapshot(Boolean snapshot) {
            tell(TransactionContext.Result.ABORTED);
        }

        @Override
        protected void onRootCommit(Boolean originalState) {
            tell(TransactionContext.Result.COMMITTED);
        }

        private void tell(TransactionContext.Result result) {
            if (told) return;
            told = true;
            if (close != null) close.onClose(transaction, result);
            if (outer != null) outer.afterOuterClose(result);
        }
    }

    // ---- storages and handlers --------------------------------------------------------------------------------

    /** One resource kind's conversions: droplets per unit (scale), the empty resource, resource to variant and back. */
    record Kind<R extends Resource, V extends TransferVariant<?>>(long scale, R empty, Function<R, V> toVariant, Function<V, R> toResource) {}

    static final Kind<ItemResource, ItemVariant> ITEMS = new Kind<>(1, ItemResource.EMPTY, TransferBridge::variant, TransferBridge::resource);
    static final Kind<FluidResource, FluidVariant> FLUIDS = new Kind<>(DROPLETS_PER_MILLIBUCKET, FluidResource.EMPTY, TransferBridge::variant, TransferBridge::resource);

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

    public static @Nullable EnergyStorage energyStorage(@Nullable EnergyHandler handler) {
        if (handler == null) return null;
        if (handler instanceof StorageEnergy energy) return energy.storage;
        return new HandlerEnergy(handler);
    }

    public static @Nullable EnergyHandler energyHandler(@Nullable EnergyStorage storage) {
        if (storage == null) return null;
        if (storage instanceof HandlerEnergy energy) return energy.handler;
        return new StorageEnergy(storage);
    }

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

    /** A NeoForge handler through Fabric's API: one slot per index. */
    static final class HandlerStorage<R extends Resource, V extends TransferVariant<?>> implements SlottedStorage<V> {

        final ResourceHandler<R> handler;
        final Kind<R, V> kind;

        HandlerStorage(ResourceHandler<R> handler, Kind<R, V> kind) {
            this.handler = handler;
            this.kind = kind;
        }

        @Override
        public long insert(V variant, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(variant, maxAmount);
            int amount = saturated(maxAmount / kind.scale());
            if (amount == 0) return 0;
            return (long) handler.insert(kind.toResource().apply(variant), amount, neo(transaction)) * kind.scale();
        }

        @Override
        public long extract(V variant, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(variant, maxAmount);
            int amount = saturated(maxAmount / kind.scale());
            if (amount == 0) return 0;
            return (long) handler.extract(kind.toResource().apply(variant), amount, neo(transaction)) * kind.scale();
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
            public long insert(V variant, long maxAmount, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                int amount = saturated(maxAmount / kind.scale());
                if (amount == 0) return 0;
                return (long) handler.insert(index, kind.toResource().apply(variant), amount, neo(transaction)) * kind.scale();
            }

            @Override
            public long extract(V variant, long maxAmount, TransactionContext transaction) {
                StoragePreconditions.notBlankNotNegative(variant, maxAmount);
                int amount = saturated(maxAmount / kind.scale());
                if (amount == 0) return 0;
                return (long) handler.extract(index, kind.toResource().apply(variant), amount, neo(transaction)) * kind.scale();
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

    /** A converted Fabric storage, as NeoForge mods see it: its slots (or views, without slots) are the indices. */
    static final class StorageHandler<R extends Resource, V extends TransferVariant<?>> implements ResourceHandler<R> {

        final Storage<V> storage;
        final Kind<R, V> kind;

        StorageHandler(Storage<V> storage, Kind<R, V> kind) {
            this.storage = storage;
            this.kind = kind;
        }

        private StorageView<V> view(int index) {
            if (storage instanceof SlottedStorage<V> slotted) return slotted.getSlot(index);
            List<StorageView<V>> views = new ArrayList<>();
            for (StorageView<V> view : storage) views.add(view);
            Objects.checkIndex(index, views.size());
            return views.get(index);
        }

        @Override
        public int size() {
            if (storage instanceof SlottedStorage<V> slotted) return slotted.getSlotCount();
            int size = 0;
            for (StorageView<V> ignored : storage) size++;
            return size;
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

        @Override
        public int insert(int index, R resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            Storage<V> target = storage instanceof SlottedStorage<V> slotted ? slotted.getSlot(index) : storage;
            return move(target::insert, resource, amount, transaction);
        }

        @Override
        public int insert(R resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return move(storage::insert, resource, amount, transaction);
        }

        @Override
        public int extract(int index, R resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            StorageView<V> view = view(index);
            return move(view::extract, resource, amount, transaction);
        }

        @Override
        public int extract(R resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            checkNonEmptyNonNegative(resource, amount);
            return move(storage::extract, resource, amount, transaction);
        }

        /** One operation of whole units (millibuckets for fluids), in a Fabric transaction nested in the NeoForge caller's. */
        private int move(Operation<V> operation, R resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (amount == 0) return 0;
            V variant = kind.toVariant().apply(resource);
            Transaction caller = fabric(transaction);
            long scale = kind.scale();
            long wanted = (long) amount * scale;
            while (wanted > 0) {
                try (Transaction nested = caller.openNested()) {
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
        long apply(V variant, long amount, TransactionContext transaction);
    }

    static void checkNonEmptyNonNegative(Resource resource, long amount) {
        if (resource.isEmpty()) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
        if (amount < 0) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
    }

    /** A NeoForge energy handler as Team Reborn's EnergyStorage. */
    static final class HandlerEnergy implements EnergyStorage {

        final EnergyHandler handler;

        HandlerEnergy(EnergyHandler handler) {
            this.handler = handler;
        }

        @Override
        public long insert(long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            return handler.insert(saturated(maxAmount), neo(transaction));
        }

        @Override
        public long extract(long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notNegative(maxAmount);
            return handler.extract(saturated(maxAmount), neo(transaction));
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

    /** A converted mod's Team Reborn EnergyStorage as NeoForge's EnergyHandler. */
    static final class StorageEnergy implements EnergyHandler {

        final EnergyStorage storage;

        StorageEnergy(EnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public long getAmountAsLong() {
            return storage.getAmount();
        }

        @Override
        public long getCapacityAsLong() {
            return storage.getCapacity();
        }

        @Override
        public int insert(int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (amount < 0) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
            if (amount == 0 || !storage.supportsInsertion()) return 0;
            try (Transaction nested = fabric(transaction).openNested()) {
                long inserted = storage.insert(amount, nested);
                nested.commit();
                return saturated(inserted);
            }
        }

        @Override
        public int extract(int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (amount < 0) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
            if (amount == 0 || !storage.supportsExtraction()) return 0;
            try (Transaction nested = fabric(transaction).openNested()) {
                long extracted = storage.extract(amount, nested);
                nested.commit();
                return saturated(extracted);
            }
        }
    }

    // ---- lookups ----------------------------------------------------------------------------------------------

    private static final Map<Identifier, Lookup<?, ?>> LOOKUPS = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static synchronized <A, C> BlockApiLookup<A, C> lookup(Identifier id, Class<A> apiClass, Class<C> contextClass) {
        Lookup<?, ?> existing = LOOKUPS.get(id);
        if (existing != null) {
            if (existing.apiClass != apiClass || existing.contextClass != contextClass) throw new IllegalArgumentException("Lookup with id " + id + " is already registered with other classes.");
            return (BlockApiLookup<A, C>) existing;
        }
        Lookup<A, C> lookup = switch (id.toString()) {
            case "fabric:sided_item_storage" -> new Lookup<>(id, apiClass, contextClass, Capabilities.Item.BLOCK, storage -> itemHandler((Storage<ItemVariant>) storage), handler -> (A) itemStorage((ResourceHandler<ItemResource>) handler));
            case "fabric:sided_fluid_storage" -> new Lookup<>(id, apiClass, contextClass, Capabilities.Fluid.BLOCK, storage -> fluidHandler((Storage<FluidVariant>) storage), handler -> (A) fluidStorage((ResourceHandler<FluidResource>) handler));
            case "teamreborn:sided_energy" -> new Lookup<>(id, apiClass, contextClass, Capabilities.Energy.BLOCK, storage -> energyHandler((EnergyStorage) storage), handler -> (A) energyStorage((EnergyHandler) handler));
            default -> new Lookup<>(id, apiClass, contextClass, BlockCapability.create(id, apiClass, contextClass), Function.identity(), Function.identity());
        };
        LOOKUPS.put(id, lookup);
        return lookup;
    }

    /** RegisterCapabilitiesEvent (TransferHooks): every lookup's providers. */
    static synchronized void register(RegisterCapabilitiesEvent event) {
        for (Lookup<?, ?> lookup : LOOKUPS.values()) lookup.register(event, false);
    }

    /** RegisterCapabilitiesEvent at EventPriority.LOWEST: every lookup's fallback providers, after every other provider. */
    static synchronized void registerFallbacks(RegisterCapabilitiesEvent event) {
        for (Lookup<?, ?> lookup : LOOKUPS.values()) lookup.register(event, true);
    }

    /** A Fabric lookup as a NeoForge block capability: the capability's object converted to and from the lookup's. */
    static final class Lookup<A, C> implements BlockApiLookup<A, C> {

        private final Identifier id;
        final Class<A> apiClass;
        final Class<C> contextClass;
        private final BlockCapability<Object, C> capability;
        private final Function<A, Object> toCapability;
        private final Function<Object, A> fromCapability;
        private final Map<Block, BlockApiProvider<A, C>> blockProviders = new LinkedHashMap<>();
        private final Map<BlockEntityType<?>, BlockEntityApiProvider<A, C>> blockEntityProviders = new LinkedHashMap<>();
        private final List<BlockApiProvider<A, C>> fallbacks = new ArrayList<>();
        private boolean registered;

        @SuppressWarnings("unchecked")
        <T> Lookup(Identifier id, Class<A> apiClass, Class<C> contextClass, BlockCapability<T, ?> capability, Function<A, T> toCapability, Function<T, A> fromCapability) {
            this.id = id;
            this.apiClass = apiClass;
            this.contextClass = contextClass;
            this.capability = (BlockCapability<Object, C>) (BlockCapability<?, ?>) capability;
            this.toCapability = (Function<A, Object>) toCapability;
            this.fromCapability = (Function<Object, A>) fromCapability;
        }

        @Override
        public @Nullable A find(Level level, BlockPos pos, @Nullable BlockState state, @Nullable BlockEntity blockEntity, C context) {
            Object found = capability.getCapability(level, pos, state, blockEntity, context);
            return found == null ? null : fromCapability.apply(found);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void registerSelf(BlockEntityType<?>... blockEntityTypes) {
            registerForBlockEntities((blockEntity, context) -> apiClass.isInstance(blockEntity) ? (A) blockEntity : null, blockEntityTypes);
        }

        @Override
        public synchronized void registerForBlocks(BlockApiProvider<A, C> provider, Block... blocks) {
            late();
            for (Block block : blocks) blockProviders.putIfAbsent(Objects.requireNonNull(block), provider);
        }

        @Override
        public synchronized void registerForBlockEntities(BlockEntityApiProvider<A, C> provider, BlockEntityType<?>... blockEntityTypes) {
            late();
            for (BlockEntityType<?> type : blockEntityTypes) blockEntityProviders.putIfAbsent(Objects.requireNonNull(type), provider);
        }

        @Override
        public synchronized void registerFallback(BlockApiProvider<A, C> fallbackProvider) {
            late();
            fallbacks.add(fallbackProvider);
        }

        private void late() {
            if (registered) org.slf4j.LoggerFactory.getLogger("modaccel-bridge").warn("A provider for lookup {} was registered after RegisterCapabilitiesEvent; NeoForge will not see it.", id);
        }

        synchronized void register(RegisterCapabilitiesEvent event, boolean fallback) {
            if (fallback) {
                registered = true;
                if (fallbacks.isEmpty()) return;
                for (Block block : BuiltInRegistries.BLOCK) {
                    event.registerBlock(capability, (level, pos, state, blockEntity, context) -> {
                        for (BlockApiProvider<A, C> each : fallbacks) {
                            A found = each.find(level, pos, state, blockEntity, context);
                            if (found != null) return toCapability.apply(found);
                        }
                        return null;
                    }, block);
                }
                return;
            }
            for (var entry : blockProviders.entrySet()) {
                BlockApiProvider<A, C> provider = entry.getValue();
                event.registerBlock(capability, (level, pos, state, blockEntity, context) -> {
                    A found = provider.find(level, pos, state, blockEntity, context);
                    return found == null ? null : toCapability.apply(found);
                }, entry.getKey());
            }
            for (var entry : blockEntityProviders.entrySet()) registerBlockEntity(event, entry.getKey(), entry.getValue());
        }

        private <BE extends BlockEntity> void registerBlockEntity(RegisterCapabilitiesEvent event, BlockEntityType<BE> type, BlockEntityApiProvider<A, C> provider) {
            event.registerBlockEntity(capability, type, (BE blockEntity, C context) -> {
                A found = provider.find(blockEntity, context);
                return found == null ? null : toCapability.apply(found);
            });
        }

        @Override
        public Identifier getId() {
            return id;
        }

        @Override
        public Class<A> apiClass() {
            return apiClass;
        }

        @Override
        public Class<C> contextClass() {
            return contextClass;
        }

        @Override
        public synchronized @Nullable BlockApiProvider<A, C> getProvider(Block block) {
            return blockProviders.get(block);
        }
    }
}
