package ${package}.transfer;

import ${package}.transfer.resource.Resource;

/** NeoForge's net.neoforged.neoforge.transfer.TransferPreconditions: the argument checks handlers make. */
public final class TransferPreconditions {

    private TransferPreconditions() {}

    public static void checkNonEmpty(Resource resource) {
        if (resource.isEmpty()) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
    }

    public static void checkNonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("Expected value to be non-negative: " + value);
    }

    public static void checkNonEmptyNonNegative(Resource resource, int value) {
        checkNonEmpty(resource);
        checkNonNegative(value);
    }
}
