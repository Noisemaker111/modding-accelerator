package ${package}.energy;

/** NeoForge 21.1's net.neoforged.neoforge.energy.IEnergyStorage: stored energy, received and extracted at once unless simulated. */
public interface IEnergyStorage {

    int receiveEnergy(int toReceive, boolean simulate);

    int extractEnergy(int toExtract, boolean simulate);

    int getEnergyStored();

    int getMaxEnergyStored();

    boolean canExtract();

    boolean canReceive();
}
