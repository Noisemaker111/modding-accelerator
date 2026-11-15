package ${package}.fabric.api.transfer.v1.storage.base;

import ${package}.fabric.api.transfer.v1.storage.StoragePreconditions;
import ${package}.fabric.api.transfer.v1.storage.TransferVariant;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import ${package}.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage: one variant and an amount of it,
 * journaled (see SnapshotParticipant) so an aborted transaction restores both. Saved as "variant" and "amount".
 */
public abstract class SingleVariantStorage<T extends TransferVariant<?>> extends SnapshotParticipant<ResourceAmount<T>> implements SingleSlotStorage<T> {

    public T variant = getBlankVariant();
    public long amount = 0;

    protected abstract T getBlankVariant();

    protected abstract long getCapacity(T variant);

    protected boolean canInsert(T variant) {
        return true;
    }

    protected boolean canExtract(T variant) {
        return true;
    }

    @Override
    public long insert(T insertedVariant, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(insertedVariant, maxAmount);
        if (!(insertedVariant.equals(variant) || variant.isBlank()) || !canInsert(insertedVariant)) return 0;
        long inserted = Math.min(maxAmount, getCapacity(insertedVariant) - amount);
        if (inserted <= 0) return 0;
        updateSnapshots(transaction);
        if (variant.isBlank()) {
            variant = insertedVariant;
            amount = inserted;
        } else {
            amount += inserted;
        }
        return inserted;
    }

    @Override
    public long extract(T extractedVariant, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(extractedVariant, maxAmount);
        if (!extractedVariant.equals(variant) || !canExtract(extractedVariant)) return 0;
        long extracted = Math.min(maxAmount, amount);
        if (extracted <= 0) return 0;
        updateSnapshots(transaction);
        amount -= extracted;
        if (amount == 0) variant = getBlankVariant();
        return extracted;
    }

    @Override
    public boolean isResourceBlank() {
        return variant.isBlank();
    }

    @Override
    public T getResource() {
        return variant;
    }

    @Override
    public long getAmount() {
        return amount;
    }

    @Override
    public long getCapacity() {
        return getCapacity(variant);
    }

    @Override
    protected ResourceAmount<T> createSnapshot() {
        return new ResourceAmount<>(variant, amount);
    }

    @Override
    protected void readSnapshot(ResourceAmount<T> snapshot) {
        variant = snapshot.resource();
        amount = snapshot.amount();
    }

    @Override
    public String toString() {
        return "SingleVariantStorage[%d %s]".formatted(amount, variant);
    }

    public static <T extends TransferVariant<?>> void readValue(SingleVariantStorage<T> storage, Codec<T> codec, Supplier<T> fallback, ValueInput value) {
        storage.variant = value.read("variant", codec).orElseGet(fallback);
        storage.amount = value.getLongOr("amount", 0L);
    }

    public static <T extends TransferVariant<?>> void writeValue(SingleVariantStorage<T> storage, Codec<T> codec, ValueOutput value) {
        value.store("variant", codec, storage.variant);
        value.putLong("amount", storage.amount);
    }
}
