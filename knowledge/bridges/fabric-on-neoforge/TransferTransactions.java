package ${package};

import ${package}.fabric.api.transfer.v1.transaction.Transaction;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The bridge's Fabric transactions, each a NeoForge transaction (TransferBridge's class comment says how they map). Not a
 * Fabric class. Closing one runs its close callbacks in reverse order, each on its own, then always commits or closes
 * the NeoForge transaction, then (for an outer one) its outer close callbacks in reverse order; a callback that throws is
 * rethrown after all that, with any later failures suppressed, as Fabric's transaction manager does, so a failing
 * callback never leaves the NeoForge transaction open.
 */
final class TransferTransactions {

    private TransferTransactions() {}

    /** The bridge's Fabric transaction for each NeoForge transaction it opened, on this thread. */
    static final ThreadLocal<Map<net.neoforged.neoforge.transfer.transaction.TransactionContext, Tx>> OPENED = ThreadLocal.withInitial(IdentityHashMap::new);
    /** Set while the bridge runs a transaction's close callbacks (Fabric's CLOSING), or its outer close callbacks. */
    static final ThreadLocal<Transaction.Lifecycle> CLOSING = new ThreadLocal<>();

    static Transaction openOuter() {
        return new Tx(net.neoforged.neoforge.transfer.transaction.Transaction.openRoot(), null, true);
    }

    static Transaction.Lifecycle lifecycle() {
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
    static @Nullable TransactionContext currentUnsafe() {
        var current = net.neoforged.neoforge.transfer.transaction.Transaction.getCurrentOpenedTransaction();
        return current == null ? null : fabric(current);
    }

    /** The NeoForge transaction a Fabric context is. */
    static net.neoforged.neoforge.transfer.transaction.TransactionContext neo(TransactionContext context) {
        return ((Tx) context).neo;
    }

    /** A NeoForge context as a Fabric one: the bridge's own when it opened it, else a view of the NeoForge caller's. */
    static Transaction fabric(net.neoforged.neoforge.transfer.transaction.TransactionContext context) {
        Tx opened = OPENED.get().get(context);
        return opened != null ? opened : new Tx(context, null, false);
    }

    /** A failure of one callback, kept to be thrown once the transaction is closed. */
    private static RuntimeException collect(@Nullable RuntimeException failure, Exception thrown, String what) {
        if (failure == null) return new RuntimeException("Encountered an exception while invoking a transaction " + what + ".", thrown);
        failure.addSuppressed(thrown);
        return failure;
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
            int depth = nestingDepth();
            var neoTransaction = (net.neoforged.neoforge.transfer.transaction.Transaction) neo;
            RuntimeException failure = null;
            CLOSING.set(Lifecycle.CLOSING);
            try {
                for (int i = closeCallbacks.size() - 1; i >= 0; i--) {
                    try {
                        closeCallbacks.get(i).onClose(this, result);
                    } catch (Exception thrown) {
                        failure = collect(failure, thrown, "close callback");
                    }
                }
                closeCallbacks.clear();
            } finally {
                CLOSING.remove();
                OPENED.get().remove(neo);
                // Always: an exception above must not leave NeoForge's transaction open on this thread.
                if (result.wasCommitted()) neoTransaction.commit();
                else neoTransaction.close();
            }
            if (depth == 0 && !outerCloseCallbacks.isEmpty()) {
                CLOSING.set(Lifecycle.OUTER_CLOSING);
                try {
                    for (int i = outerCloseCallbacks.size() - 1; i >= 0; i--) {
                        try {
                            outerCloseCallbacks.get(i).afterOuterClose(result);
                        } catch (Exception thrown) {
                            failure = collect(failure, thrown, "outer close callback");
                        }
                    }
                    outerCloseCallbacks.clear();
                } finally {
                    CLOSING.remove();
                }
            }
            if (failure != null) throw failure;
        }

        @Override
        public String toString() {
            return "Transaction[depth=" + neo.depth() + ", neoforge=" + neo + "]";
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
}
