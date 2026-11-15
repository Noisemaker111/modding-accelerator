package ${package}.fabric.api.transfer.v1.transaction;

import ${package}.TransferBridge;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.transaction.Transaction, written on NeoForge's transfer transactions
 * (net.neoforged.neoforge.transfer.transaction.Transaction): openOuter is NeoForge's openRoot, openNested its open(parent),
 * nestingDepth its depth, OUTER_CLOSING its ROOT_CLOSING. Both are one outer transaction per thread, nested only in the
 * current one, committed by commit() and aborted by close() (a no-op once closed), so the two loaders' handlers take part
 * in the same transactions with the same meaning.
 */
public interface Transaction extends AutoCloseable, TransactionContext {

    static Transaction openOuter() {
        return TransferBridge.openOuter();
    }

    static boolean isOpen() {
        return getLifecycle() != Lifecycle.NONE;
    }

    static Lifecycle getLifecycle() {
        return TransferBridge.lifecycle();
    }

    static Transaction openNested(@Nullable TransactionContext maybeParent) {
        return maybeParent == null ? openOuter() : maybeParent.openNested();
    }

    @Deprecated
    static @Nullable TransactionContext getCurrentUnsafe() {
        return TransferBridge.currentUnsafe();
    }

    void abort();

    void commit();

    @Override
    void close();

    enum Lifecycle {
        NONE,
        OPEN,
        CLOSING,
        OUTER_CLOSING
    }
}
