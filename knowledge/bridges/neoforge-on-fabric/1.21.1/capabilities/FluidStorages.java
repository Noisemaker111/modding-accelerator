package ${package}.capabilities;

import ${package}.fluids.FluidStack;
import ${package}.fluids.capability.IFluidHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * NeoForge 21.1's IFluidHandler and Fabric API's fluid Storage, each presented as the other, so Capabilities.FluidHandler.BLOCK
 * is Fabric's FluidStorage.SIDED. NeoForge counts millibuckets, Fabric droplets (81 to the millibucket): a Storage offers
 * and takes whole millibuckets only. As ItemStorages, a handler as a Storage changes at once and is undone when the
 * transaction is aborted.
 */
public final class FluidStorages {

    /** Droplets in a millibucket. */
    public static final long DROPLETS = FluidConstants.BUCKET / 1000;

    private FluidStorages() {}

    public static Storage<FluidVariant> storage(IFluidHandler handler) {
        return handler instanceof HandlerOfStorage wrapped ? wrapped.storage : new StorageOfHandler(handler);
    }

    public static IFluidHandler handler(Storage<FluidVariant> storage) {
        return storage instanceof StorageOfHandler wrapped ? wrapped.handler : new HandlerOfStorage(storage);
    }

    static FluidVariant variant(FluidStack stack) {
        return stack.isEmpty() ? FluidVariant.blank() : FluidVariant.of(stack.getFluid(), stack.getComponentsPatch());
    }

    static FluidStack stack(FluidVariant variant, long droplets) {
        int amount = (int) Math.min(droplets / DROPLETS, Integer.MAX_VALUE);
        return variant.isBlank() || amount <= 0 ? FluidStack.EMPTY : new FluidStack(variant.getFluid().builtInRegistryHolder(), amount, variant.getComponents());
    }

    static final class StorageOfHandler implements Storage<FluidVariant> {
        final IFluidHandler handler;
        final UndoLog log = new UndoLog();

        StorageOfHandler(IFluidHandler handler) {
            this.handler = handler;
        }

        @Override
        public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            FluidStack offered = stack(resource, maxAmount);
            if (offered.isEmpty()) return 0;
            int filled = handler.fill(offered, IFluidHandler.FluidAction.EXECUTE);
            if (filled <= 0) return 0;
            FluidStack added = offered.copyWithAmount(filled);
            log.done(transaction, () -> handler.drain(added, IFluidHandler.FluidAction.EXECUTE));
            return filled * DROPLETS;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            FluidStack wanted = stack(resource, maxAmount);
            if (wanted.isEmpty()) return 0;
            FluidStack drained = handler.drain(wanted, IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) return 0;
            log.done(transaction, () -> handler.fill(drained, IFluidHandler.FluidAction.EXECUTE));
            return drained.getAmount() * DROPLETS;
        }

        @Override
        public Iterator<StorageView<FluidVariant>> iterator() {
            List<StorageView<FluidVariant>> views = new ArrayList<>();
            for (int tank = 0; tank < handler.getTanks(); tank++) views.add(new Tank(tank));
            return views.iterator();
        }

        final class Tank implements StorageView<FluidVariant> {
            final int tank;

            Tank(int tank) {
                this.tank = tank;
            }

            @Override
            public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
                FluidStack held = handler.getFluidInTank(tank);
                if (held.isEmpty() || !variant(held).equals(resource)) return 0;
                return StorageOfHandler.this.extract(resource, maxAmount, transaction);
            }

            @Override
            public boolean isResourceBlank() {
                return handler.getFluidInTank(tank).isEmpty();
            }

            @Override
            public FluidVariant getResource() {
                return variant(handler.getFluidInTank(tank));
            }

            @Override
            public long getAmount() {
                return handler.getFluidInTank(tank).getAmount() * DROPLETS;
            }

            @Override
            public long getCapacity() {
                return handler.getTankCapacity(tank) * DROPLETS;
            }
        }
    }

    static final class HandlerOfStorage implements IFluidHandler {
        final Storage<FluidVariant> storage;

        HandlerOfStorage(Storage<FluidVariant> storage) {
            this.storage = storage;
        }

        List<StorageView<FluidVariant>> views() {
            List<StorageView<FluidVariant>> views = new ArrayList<>();
            for (StorageView<FluidVariant> view : storage) views.add(view);
            return views;
        }

        @Override
        public int getTanks() {
            return views().size();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            List<StorageView<FluidVariant>> views = views();
            if (tank >= views.size() || views.get(tank).isResourceBlank()) return FluidStack.EMPTY;
            return stack(views.get(tank).getResource(), views.get(tank).getAmount());
        }

        @Override
        public int getTankCapacity(int tank) {
            List<StorageView<FluidVariant>> views = views();
            return tank >= views.size() ? 0 : (int) Math.min(views.get(tank).getCapacity() / DROPLETS, Integer.MAX_VALUE);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return fill(stack.copyWithAmount(1), FluidAction.SIMULATE) > 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return 0;
            try (Transaction transaction = UndoLog.open()) {
                long inserted = storage.insert(variant(resource), resource.getAmount() * DROPLETS, transaction);
                // Whole millibuckets only: a part of one is not offered back.
                long whole = inserted / DROPLETS * DROPLETS;
                if (whole != inserted) {
                    transaction.abort();
                    return 0;
                }
                if (action.execute()) transaction.commit();
                return (int) (whole / DROPLETS);
            }
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return FluidStack.EMPTY;
            return drain(variant(resource), resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            for (StorageView<FluidVariant> view : views()) {
                if (!view.isResourceBlank()) return drain(view.getResource(), maxDrain, action);
            }
            return FluidStack.EMPTY;
        }

        FluidStack drain(FluidVariant resource, int amount, FluidAction action) {
            if (amount <= 0) return FluidStack.EMPTY;
            try (Transaction transaction = UndoLog.open()) {
                long extracted = storage.extract(resource, amount * DROPLETS, transaction);
                long whole = extracted / DROPLETS * DROPLETS;
                if (whole != extracted) {
                    transaction.abort();
                    return FluidStack.EMPTY;
                }
                if (action.execute()) transaction.commit();
                return stack(resource, whole);
            }
        }
    }
}
