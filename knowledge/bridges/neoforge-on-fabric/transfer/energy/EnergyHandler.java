package ${package}.transfer.energy;

import ${package}.transfer.TransferBridge;
import ${package}.transfer.transaction.TransactionContext;

/**
 * NeoForge's net.neoforged.neoforge.transfer.energy.EnergyHandler: an amount of energy, a capacity, and transactional
 * insert and extract. Fabric API has no energy API, so the bridge carries energy handlers through a lookup of its own
 * (TransferBridge.ENERGY_SIDED, Fabric API Storage of net.minecraft.util.Unit): mods converted from NeoForge see each
 * other's energy there; Fabric mods do not.
 */
public interface EnergyHandler {

    long getAmountAsLong();

    default int getAmountAsInt() {
        return TransferBridge.saturated(getAmountAsLong());
    }

    long getCapacityAsLong();

    default int getCapacityAsInt() {
        return TransferBridge.saturated(getCapacityAsLong());
    }

    int insert(int amount, TransactionContext transaction);

    int extract(int amount, TransactionContext transaction);
}
