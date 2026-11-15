package ${package}.energy;

import ${package}.transfer.energy.EnergyHandler;
import ${package}.transfer.transaction.Transaction;
import java.util.Objects;

/**
 * NeoForge's legacy net.neoforged.neoforge.energy.IEnergyStorage (deprecated since NeoForge 21.9, still in 26.1): energy
 * with simulate flags. of(handler) presents a transfer EnergyHandler as one, each call its own root transaction.
 */
public interface IEnergyStorage {

    static IEnergyStorage of(EnergyHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int toReceive, boolean simulate) {
                if (toReceive <= 0) return 0;
                try (Transaction transaction = Transaction.openRoot()) {
                    int received = handler.insert(toReceive, transaction);
                    if (!simulate) transaction.commit();
                    return received;
                }
            }

            @Override
            public int extractEnergy(int toExtract, boolean simulate) {
                if (toExtract <= 0) return 0;
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = handler.extract(toExtract, transaction);
                    if (!simulate) transaction.commit();
                    return extracted;
                }
            }

            @Override
            public int getEnergyStored() {
                return handler.getAmountAsInt();
            }

            @Override
            public int getMaxEnergyStored() {
                return handler.getCapacityAsInt();
            }

            @Override
            public boolean canExtract() {
                return true;
            }

            @Override
            public boolean canReceive() {
                return true;
            }
        };
    }

    int receiveEnergy(int toReceive, boolean simulate);

    int extractEnergy(int toExtract, boolean simulate);

    int getEnergyStored();

    int getMaxEnergyStored();

    boolean canExtract();

    boolean canReceive();
}
