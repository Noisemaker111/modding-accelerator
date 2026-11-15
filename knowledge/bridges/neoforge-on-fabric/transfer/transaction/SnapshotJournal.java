package ${package}.transfer.transaction;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext.CloseCallback;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext.OuterCloseCallback;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext.Result;

/**
 * NeoForge's net.neoforged.neoforge.transfer.transaction.SnapshotJournal, kept by Fabric API's close callbacks: the
 * first change in a transaction takes a snapshot; an abort reverts to it; a nested commit hands it to the enclosing
 * transaction (or releases it when that one has its own); a root commit keeps the first snapshot as the original state
 * and calls onRootCommit(original) once the root has closed (Fabric's outer close callback), then releases it. This is
 * NeoForge's order exactly, so a journal behaves the same whether a NeoForge or a Fabric caller opened the transaction.
 */
public abstract class SnapshotJournal<T> {

    private static final Object NONE = new Object();

    /** The snapshot taken at each depth, NONE where there is none. */
    private final List<Object> snapshots = new ArrayList<>();
    private int snapshotCount;
    /** The state before the root transaction that committed, until onRootCommit has seen it. */
    private Object original = NONE;
    private final Callbacks callbacks = new Callbacks();

    protected abstract T createSnapshot();

    protected abstract void revertToSnapshot(T snapshot);

    protected void releaseSnapshot(T snapshot) {}

    protected void onRootCommit(T originalState) {}

    public final boolean isInTransaction() {
        return snapshotCount > 0;
    }

    public void updateSnapshots(TransactionContext transaction) {
        var fabric = Transaction.fabric(transaction);
        int depth = fabric.nestingDepth();
        while (snapshots.size() <= depth) snapshots.add(NONE);
        if (snapshots.get(depth) != NONE) return;
        snapshotCount++;
        snapshots.set(depth, createSnapshot());
        fabric.addCloseCallback(callbacks);
    }

    @SuppressWarnings("unchecked")
    private final class Callbacks implements CloseCallback, OuterCloseCallback {

        @Override
        public void onClose(net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction, Result result) {
            int depth = transaction.nestingDepth();
            T snapshot = (T) snapshots.set(depth, NONE);
            if (result.wasAborted()) {
                revertToSnapshot(snapshot);
                releaseSnapshot(snapshot);
                snapshotCount--;
            } else if (depth == 0) {
                if (original == NONE) {
                    original = snapshot;
                    transaction.addOuterCloseCallback(this);
                } else {
                    releaseSnapshot(snapshot);
                }
                snapshotCount = 0;
            } else if (snapshots.get(depth - 1) == NONE) {
                snapshots.set(depth - 1, snapshot);
                transaction.getOpenTransaction(depth - 1).addCloseCallback(this);
            } else {
                releaseSnapshot(snapshot);
                snapshotCount--;
            }
        }

        @Override
        public void afterOuterClose(Result result) {
            T state = (T) original;
            original = NONE;
            onRootCommit(state);
            releaseSnapshot(state);
        }
    }
}
