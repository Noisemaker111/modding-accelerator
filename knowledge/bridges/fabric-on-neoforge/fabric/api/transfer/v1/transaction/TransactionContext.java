package ${package}.fabric.api.transfer.v1.transaction;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext, on NeoForge: each context is a
 * NeoForge transaction (see Transaction and the bridge's TransferBridge for how close callbacks are kept).
 */
public interface TransactionContext {

    Transaction openNested();

    int nestingDepth();

    Transaction getOpenTransaction(int nestingDepth);

    void addCloseCallback(CloseCallback closeCallback);

    @FunctionalInterface
    interface CloseCallback {
        void onClose(TransactionContext transaction, Result result);
    }

    void addOuterCloseCallback(OuterCloseCallback outerCloseCallback);

    @FunctionalInterface
    interface OuterCloseCallback {
        void afterOuterClose(Result result);
    }

    enum Result {
        ABORTED,
        COMMITTED;

        public boolean wasAborted() {
            return this == ABORTED;
        }

        public boolean wasCommitted() {
            return this == COMMITTED;
        }
    }
}
