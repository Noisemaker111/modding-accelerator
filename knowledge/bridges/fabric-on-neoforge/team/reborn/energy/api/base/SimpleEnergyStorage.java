package ${package}.team.reborn.energy.api.base;

import ${package}.fabric.api.transfer.v1.storage.StoragePreconditions;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import ${package}.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import ${package}.team.reborn.energy.api.EnergyStorage;

/** Team Reborn Energy's team.reborn.energy.api.base.SimpleEnergyStorage: an amount with a capacity and per-operation limits, journaled. */
public class SimpleEnergyStorage extends SnapshotParticipant<Long> implements EnergyStorage {

    public long amount = 0;
    public final long capacity;
    public final long maxInsert;
    public final long maxExtract;

    public SimpleEnergyStorage(long capacity, long maxInsert, long maxExtract) {
        StoragePreconditions.notNegative(capacity);
        StoragePreconditions.notNegative(maxInsert);
        StoragePreconditions.notNegative(maxExtract);
        this.capacity = capacity;
        this.maxInsert = maxInsert;
        this.maxExtract = maxExtract;
    }

    @Override
    protected Long createSnapshot() {
        return amount;
    }

    @Override
    protected void readSnapshot(Long snapshot) {
        amount = snapshot;
    }

    @Override
    public boolean supportsInsertion() {
        return maxInsert > 0;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        long inserted = Math.min(maxInsert, Math.min(maxAmount, capacity - amount));
        if (inserted <= 0) return 0;
        updateSnapshots(transaction);
        amount += inserted;
        return inserted;
    }

    @Override
    public boolean supportsExtraction() {
        return maxExtract > 0;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        long extracted = Math.min(maxExtract, Math.min(maxAmount, amount));
        if (extracted <= 0) return 0;
        updateSnapshots(transaction);
        amount -= extracted;
        return extracted;
    }

    @Override
    public long getAmount() {
        return amount;
    }

    @Override
    public long getCapacity() {
        return capacity;
    }
}
