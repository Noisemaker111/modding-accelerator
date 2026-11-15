package ${package}.transfer;

import ${package}.transfer.resource.Resource;
import ${package}.transfer.resource.ResourceStack;
import ${package}.transfer.transaction.Transaction;
import ${package}.transfer.transaction.TransactionContext;
import java.util.function.Predicate;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.transfer.ResourceHandlerUtil: queries over a handler and moves between two, each move
 * all-or-nothing per resource in a transaction nested in the given one (or a root transaction when none is given).
 */
public final class ResourceHandlerUtil {

    private ResourceHandlerUtil() {}

    public static boolean isEmpty(Resource resource, int amount) {
        return amount <= 0 || resource.isEmpty();
    }

    public static boolean isEmpty(ResourceHandler<? extends Resource> handler) {
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            if (handler.getAmountAsLong(index) > 0) return false;
        }
        return true;
    }

    public static <T extends Resource> boolean isFull(ResourceHandler<T> handler) {
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            if (handler.getAmountAsLong(index) < handler.getCapacityAsLong(index, handler.getResource(index))) return false;
        }
        return true;
    }

    public static <T extends Resource> boolean isValid(ResourceHandler<T> handler, T resource) {
        TransferBridge.checkNonEmpty(resource);
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            if (handler.isValid(index, resource)) return true;
        }
        return false;
    }

    /** 0 when empty, else 1 to 15 by the average fill of the non-empty indices (as a comparator reads a container). */
    public static <T extends Resource> int getRedstoneSignalFromResourceHandler(ResourceHandler<T> handler) {
        float proportion = 0;
        int samples = 0;
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            long amount = handler.getAmountAsLong(index);
            if (amount <= 0) continue;
            long capacity = handler.getCapacityAsLong(index, handler.getResource(index));
            if (capacity <= 0) continue;
            proportion += Math.min(1.0F, (float) amount / capacity);
            samples++;
        }
        if (samples == 0) return 0;
        return Mth.lerpDiscrete(proportion / samples, 0, 15);
    }

    /** Inserts into the indices that already hold something first, then into the empty ones. */
    public static <T extends Resource> int insertStacking(@Nullable ResourceHandler<T> handler, T resource, int amount, @Nullable TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        if (handler == null || amount == 0) return 0;
        try (Transaction nested = Transaction.open(transaction)) {
            int inserted = 0;
            int size = handler.size();
            for (int pass = 0; pass < 2 && inserted < amount; pass++) {
                for (int index = 0; index < size && inserted < amount; index++) {
                    if (handler.getResource(index).isEmpty() == (pass == 0)) continue;
                    inserted += handler.insert(index, resource, amount - inserted, nested);
                }
            }
            nested.commit();
            return inserted;
        }
    }

    public static <T extends Resource> @Nullable ResourceStack<T> extractFirst(@Nullable ResourceHandler<T> handler, Predicate<T> filter, int amount, @Nullable TransactionContext transaction) {
        TransferBridge.checkNonNegative(amount);
        if (handler == null || amount == 0) return null;
        try (Transaction nested = Transaction.open(transaction)) {
            T resource = findExtractableResource(handler, filter, nested);
            if (resource == null) return null;
            int extracted = handler.extract(resource, amount, nested);
            if (extracted == 0) return null;
            nested.commit();
            return new ResourceStack<>(resource, extracted);
        }
    }

    public static <T extends Resource> int move(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction) {
        return move(from, to, filter, amount, transaction, false);
    }

    public static <T extends Resource> int moveStacking(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction) {
        return move(from, to, filter, amount, transaction, true);
    }

    public static <T extends Resource> @Nullable ResourceStack<T> moveFirst(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction) {
        return moveFirst(from, to, filter, amount, transaction, false);
    }

    public static <T extends Resource> @Nullable ResourceStack<T> moveFirstStacking(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction) {
        return moveFirst(from, to, filter, amount, transaction, true);
    }

    public static <T extends Resource> boolean contains(ResourceHandler<T> handler, T resource) {
        return indexOf(handler, resource) != -1;
    }

    public static <T extends Resource> int indexOf(ResourceHandler<T> handler, T resource) {
        TransferBridge.checkNonEmpty(resource);
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            if (resource.equals(handler.getResource(index))) return index;
        }
        return -1;
    }

    /** The first resource the filter accepts that can be extracted, found with a transaction that is then aborted. */
    public static <T extends Resource> @Nullable T findExtractableResource(ResourceHandler<T> handler, Predicate<T> filter, @Nullable TransactionContext transaction) {
        try (Transaction probe = Transaction.open(transaction)) {
            int size = handler.size();
            for (int index = 0; index < size; index++) {
                T resource = handler.getResource(index);
                if (!resource.isEmpty() && filter.test(resource) && handler.extract(index, resource, handler.getAmountAsInt(index), probe) > 0) return resource;
            }
            return null;
        }
    }

    private static <T extends Resource> @Nullable ResourceStack<T> moveFirst(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction, boolean stacking) {
        TransferBridge.checkNonNegative(amount);
        if (from == null || to == null || amount == 0) return null;
        int size = from.size();
        for (int index = 0; index < size; index++) {
            T resource = from.getResource(index);
            if (resource.isEmpty() || !filter.test(resource)) continue;
            int moved = moveOne(from, to, resource, amount, transaction, stacking);
            if (moved > 0) return new ResourceStack<>(resource, moved);
        }
        return null;
    }

    private static <T extends Resource> int move(@Nullable ResourceHandler<T> from, @Nullable ResourceHandler<T> to, Predicate<T> filter, int amount, @Nullable TransactionContext transaction, boolean stacking) {
        TransferBridge.checkNonNegative(amount);
        if (from == null || to == null || amount == 0) return 0;
        try (Transaction nested = Transaction.open(transaction)) {
            int moved = 0;
            int size = from.size();
            for (int index = 0; index < size && moved < amount; index++) {
                T resource = from.getResource(index);
                if (resource.isEmpty() || !filter.test(resource)) continue;
                moved += moveOne(from, to, resource, amount - moved, nested, stacking);
            }
            nested.commit();
            return moved;
        }
    }

    /** Moves up to amount of one resource: as much as the destination takes of what the source gives. */
    private static <T extends Resource> int moveOne(ResourceHandler<T> from, ResourceHandler<T> to, T resource, int amount, @Nullable TransactionContext transaction, boolean stacking) {
        int accepted;
        try (Transaction probe = Transaction.open(transaction)) {
            int extracted = from.extract(resource, amount, probe);
            accepted = extracted == 0 ? 0 : stacking ? insertStacking(to, resource, extracted, probe) : to.insert(resource, extracted, probe);
        }
        if (accepted == 0) return 0;
        try (Transaction move = Transaction.open(transaction)) {
            int extracted = from.extract(resource, accepted, move);
            int inserted = stacking ? insertStacking(to, resource, extracted, move) : to.insert(resource, extracted, move);
            if (extracted != inserted) return 0;
            move.commit();
            return inserted;
        }
    }
}
