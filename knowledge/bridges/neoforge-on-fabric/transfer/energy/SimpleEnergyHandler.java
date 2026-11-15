package ${package}.transfer.energy;

import ${package}.common.util.ValueIOSerializable;
import ${package}.transfer.TransferBridge;
import ${package}.transfer.transaction.SnapshotJournal;
import ${package}.transfer.transaction.TransactionContext;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * NeoForge's net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler: a stored amount with a capacity and per-operation
 * insert and extract limits, saved as "energy". Changes are journaled, so an aborted transaction puts the amount back;
 * onEnergyChanged runs once after the root transaction commits, with the amount before it.
 */
public class SimpleEnergyHandler implements EnergyHandler, ValueIOSerializable {

    protected int energy;
    protected int capacity;
    protected int maxInsert;
    protected int maxExtract;
    private final EnergyJournal energyJournal = new EnergyJournal();

    public SimpleEnergyHandler(int capacity) {
        this(capacity, capacity);
    }

    public SimpleEnergyHandler(int capacity, int maxTransfer) {
        this(capacity, maxTransfer, maxTransfer);
    }

    public SimpleEnergyHandler(int capacity, int maxInsert, int maxExtract) {
        this(capacity, maxInsert, maxExtract, 0);
    }

    public SimpleEnergyHandler(int capacity, int maxInsert, int maxExtract, int energy) {
        TransferBridge.checkNonNegative(capacity);
        TransferBridge.checkNonNegative(maxInsert);
        TransferBridge.checkNonNegative(maxExtract);
        TransferBridge.checkNonNegative(energy);
        this.capacity = capacity;
        this.maxInsert = maxInsert;
        this.maxExtract = maxExtract;
        this.energy = energy;
    }

    @Override
    public void serialize(ValueOutput output) {
        output.putInt("energy", energy);
    }

    @Override
    public void deserialize(ValueInput input) {
        energy = Math.max(0, input.getIntOr("energy", 0));
    }

    /** Sets the amount outside a transaction. */
    public void set(int amount) {
        TransferBridge.checkNonNegative(amount);
        if (energy == amount) return;
        int previous = energy;
        energy = amount;
        onEnergyChanged(previous);
    }

    protected void onEnergyChanged(int previousAmount) {}

    @Override
    public long getAmountAsLong() {
        return energy;
    }

    @Override
    public long getCapacityAsLong() {
        return capacity;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferBridge.checkNonNegative(amount);
        int inserted = Math.min(capacity - energy, Math.min(amount, maxInsert));
        if (inserted <= 0) return 0;
        energyJournal.updateSnapshots(transaction);
        energy += inserted;
        return inserted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferBridge.checkNonNegative(amount);
        int extracted = Math.min(energy, Math.min(amount, maxExtract));
        if (extracted <= 0) return 0;
        energyJournal.updateSnapshots(transaction);
        energy -= extracted;
        return extracted;
    }

    private final class EnergyJournal extends SnapshotJournal<Integer> {

        @Override
        protected Integer createSnapshot() {
            return energy;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            energy = snapshot;
        }

        @Override
        protected void onRootCommit(Integer originalState) {
            if (energy != originalState) onEnergyChanged(originalState);
        }
    }
}
