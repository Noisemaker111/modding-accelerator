package ${package}.fabric.api.transfer.v1.transaction.base;

import ${package}.TransferBridge;
import ${package}.fabric.api.transfer.v1.transaction.Transaction;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant, kept by a NeoForge
 * SnapshotJournal, which works the same way: the first change in a transaction takes a snapshot, an abort reads it back,
 * a nested commit hands it to the enclosing transaction, and onFinalCommit runs once after the outer transaction commits.
 * onClose and afterOuterClose stay for code that calls them; the journal does their work.
 */
public abstract class SnapshotParticipant<T> implements Transaction.CloseCallback, Transaction.OuterCloseCallback {

    private final Journal journal = new Journal();

    protected abstract T createSnapshot();

    protected abstract void readSnapshot(T snapshot);

    protected void releaseSnapshot(T snapshot) {}

    protected void onFinalCommit() {}

    public void updateSnapshots(TransactionContext transaction) {
        journal.updateSnapshots(TransferBridge.neo(transaction));
    }

    @Override
    public void onClose(TransactionContext transaction, Transaction.Result result) {}

    @Override
    public void afterOuterClose(Transaction.Result result) {
        if (result.wasCommitted()) onFinalCommit();
    }

    private final class Journal extends SnapshotJournal<T> {

        @Override
        protected T createSnapshot() {
            return SnapshotParticipant.this.createSnapshot();
        }

        @Override
        protected void revertToSnapshot(T snapshot) {
            readSnapshot(snapshot);
        }

        @Override
        protected void releaseSnapshot(T snapshot) {
            SnapshotParticipant.this.releaseSnapshot(snapshot);
        }

        @Override
        protected void onRootCommit(T originalState) {
            onFinalCommit();
        }
    }
}
