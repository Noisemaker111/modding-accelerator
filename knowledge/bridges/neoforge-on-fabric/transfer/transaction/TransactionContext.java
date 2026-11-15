package ${package}.transfer.transaction;

/**
 * NeoForge's net.neoforged.neoforge.transfer.transaction.TransactionContext: the open transaction an operation runs in.
 * Here every context is a Transaction, which is one of Fabric API's transactions (see Transaction).
 */
public sealed interface TransactionContext permits Transaction {

    /** 0 for the root transaction, one more for each transaction nested in it (Fabric's nestingDepth()). */
    int depth();
}
