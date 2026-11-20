package ${package}.capabilities;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * How a NeoForge handler, which changes at once, takes part in a Fabric transaction: each change it makes inside one is
 * logged with the change that reverses it, and a transaction that is aborted (at any depth) runs its reversals, newest
 * first. The snapshot is the log's length, so nested transactions undo only their own changes.
 */
final class UndoLog extends SnapshotParticipant<Integer> {

    private final List<Runnable> undo = new ArrayList<>();

    /** Records a change already made in this transaction, with what reverses it. */
    void done(TransactionContext transaction, Runnable reverse) {
        updateSnapshots(transaction);
        undo.add(reverse);
    }

    @Override
    protected Integer createSnapshot() {
        return undo.size();
    }

    @Override
    protected void readSnapshot(Integer size) {
        while (undo.size() > size) undo.remove(undo.size() - 1).run();
    }

    @Override
    protected void onFinalCommit() {
        undo.clear();
    }

    /** A transaction for one NeoForge-style call: nested in the thread's open one, or a new outer one. */
    static Transaction open() {
        return Transaction.isOpen() ? Transaction.getCurrentUnsafe().openNested() : Transaction.openOuter();
    }
}
