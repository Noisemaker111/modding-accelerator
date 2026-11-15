package ${package}.fabric.api.transfer.v1.storage;

/** Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions: the argument checks storages make. */
public final class StoragePreconditions {

    private StoragePreconditions() {}

    public static void notBlank(TransferVariant<?> variant) {
        if (variant.isBlank()) throw new IllegalArgumentException("Transfer variant may not be blank.");
    }

    public static void notNegative(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount may not be negative, but it is: " + amount);
    }

    public static void notBlankNotNegative(TransferVariant<?> variant, long amount) {
        notBlank(variant);
        notNegative(amount);
    }
}
