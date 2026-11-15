package ${package}.transfer.transaction;

import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.transfer.transaction.Transaction, written on Fabric API's transactions
 * (net.fabricmc.fabric.api.transfer.v1.transaction.Transaction): each one is a Fabric transaction, so a converted mod's
 * handlers and Fabric mods' storages take part in the same transactions.
 *
 * <p>The two APIs mean the same thing: one root (Fabric: outer) transaction per thread, transactions nested in the
 * current one only, commit() keeps the changes, close() without commit() aborts them (and is a no-op once closed), an
 * aborted transaction reverts what it and the transactions nested in it changed. openRoot is Fabric's openOuter, open is
 * openNested, depth is nestingDepth, and ROOT_CLOSING is OUTER_CLOSING. A Fabric storage calling a converted handler
 * passes its own Fabric transaction, seen here through of().
 */
public final class Transaction implements AutoCloseable, TransactionContext {

    /** The Fabric transaction this one is. */
    private final net.fabricmc.fabric.api.transfer.v1.transaction.Transaction fabric;

    private Transaction(net.fabricmc.fabric.api.transfer.v1.transaction.Transaction fabric) {
        this.fabric = fabric;
    }

    /** Opens the root transaction of this thread; throws if one is open (as both loaders do). */
    public static Transaction openRoot() {
        return new Transaction(net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter());
    }

    /** Opens a transaction nested in parent, which must be the current one, or a root transaction when parent is null. */
    public static Transaction open(@Nullable TransactionContext parent) {
        if (parent == null) return openRoot();
        return new Transaction(fabric(parent).openNested());
    }

    public static Lifecycle getLifecycle() {
        return switch (net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.getLifecycle()) {
            case NONE -> Lifecycle.NONE;
            case OPEN -> Lifecycle.OPEN;
            case CLOSING -> Lifecycle.CLOSING;
            case OUTER_CLOSING -> Lifecycle.ROOT_CLOSING;
        };
    }

    /** The innermost open transaction, or null; like NeoForge's, it cannot be called while a transaction closes. */
    @Deprecated
    @SuppressWarnings("deprecation")
    public static @Nullable TransactionContext getCurrentOpenedTransaction() {
        var current = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.getCurrentUnsafe();
        return current == null ? null : of(current);
    }

    public void commit() {
        fabric.commit();
    }

    @Override
    public void close() {
        fabric.close();
    }

    @Override
    public int depth() {
        return fabric.nestingDepth();
    }

    @Override
    public String toString() {
        return "Transaction[depth=" + depth() + ", fabric=" + fabric + "]";
    }

    /** The bridge's view of a Fabric transaction context: the transaction open at its depth. */
    public static Transaction of(net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext context) {
        if (context instanceof net.fabricmc.fabric.api.transfer.v1.transaction.Transaction transaction) return new Transaction(transaction);
        return new Transaction(context.getOpenTransaction(context.nestingDepth()));
    }

    /** The Fabric transaction a context is. */
    public static net.fabricmc.fabric.api.transfer.v1.transaction.Transaction fabric(TransactionContext context) {
        return ((Transaction) context).fabric;
    }

    public enum Lifecycle {
        NONE,
        OPEN,
        CLOSING,
        ROOT_CLOSING
    }
}
