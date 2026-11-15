package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ${package}.fabric.api.transfer.v1.transaction.Transaction;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Closing a transaction as Fabric's transaction manager does: callbacks in reverse order, each on its own, and a callback
 * that throws never leaves the NeoForge transaction open on the thread.
 */
class TransferTransactionsTest {

    @Test
    void failingCloseCallbackOnAbortStillClosesTheTransaction() {
        List<String> order = new ArrayList<>();
        Transaction outer = TransferTransactions.openOuter();
        outer.addCloseCallback((transaction, result) -> order.add("first"));
        outer.addCloseCallback((transaction, result) -> {
            order.add("second");
            throw new IllegalStateException("callback failed");
        });
        assertThrows(RuntimeException.class, outer::close);
        assertEquals(List.of("second", "first"), order);
        // A NeoForge root transaction left open would make this throw.
        try (Transaction again = TransferTransactions.openOuter()) {
            again.commit();
        }
    }

    @Test
    void failingCallbacksOnNestedCommitStillCloseBoth() {
        List<TransactionContext.Result> results = new ArrayList<>();
        Transaction outer = TransferTransactions.openOuter();
        Transaction nested = outer.openNested();
        nested.addCloseCallback((transaction, result) -> {
            results.add(result);
            throw new IllegalStateException("nested callback failed");
        });
        outer.addOuterCloseCallback(result -> {
            results.add(result);
            throw new IllegalStateException("outer callback failed");
        });
        assertThrows(RuntimeException.class, nested::commit);
        assertThrows(RuntimeException.class, outer::commit);
        assertEquals(List.of(TransactionContext.Result.COMMITTED, TransactionContext.Result.COMMITTED), results);
        try (Transaction again = TransferTransactions.openOuter()) {
            again.abort();
        }
    }
}
