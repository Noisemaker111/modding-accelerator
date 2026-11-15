package ${package}.transfer.transaction;

/** NeoForge's net.neoforged.neoforge.transfer.transaction.RootCommitJournal: runs a callback after the root transaction commits. */
public final class RootCommitJournal extends SnapshotJournal<Object> {

    private final Runnable rootCommitCallback;

    public RootCommitJournal(Runnable rootCommitCallback) {
        this.rootCommitCallback = rootCommitCallback;
    }

    @Override
    protected Object createSnapshot() {
        return Boolean.TRUE;
    }

    @Override
    protected void revertToSnapshot(Object snapshot) {}

    @Override
    protected void onRootCommit(Object originalState) {
        rootCommitCallback.run();
    }
}
