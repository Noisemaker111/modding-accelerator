package ${package}.team.reborn.energy.api;

import ${package}.fabric.api.lookup.v1.block.BlockApiLookup;
import ${package}.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Team Reborn Energy's team.reborn.energy.api.EnergyStorage (the Fabric ecosystem's energy API), on NeoForge: SIDED is
 * NeoForge's Capabilities.Energy.BLOCK, each NeoForge EnergyHandler seen as an EnergyStorage and back, the same units
 * (amounts above Integer.MAX_VALUE are capped going to NeoForge's int API). Transactions are the bridge's Fabric
 * transactions over NeoForge's. ITEM (item-held energy) and ENERGY_COMPONENT are not mirrored.
 */
public interface EnergyStorage {

    BlockApiLookup<EnergyStorage, @Nullable Direction> SIDED = BlockApiLookup.get(Identifier.fromNamespaceAndPath("teamreborn", "sided_energy"), EnergyStorage.class, Direction.class);

    EnergyStorage EMPTY = new EnergyStorage() {
        @Override
        public boolean supportsInsertion() {
            return false;
        }

        @Override
        public long insert(long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public long extract(long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public long getAmount() {
            return 0;
        }

        @Override
        public long getCapacity() {
            return 0;
        }
    };

    default boolean supportsInsertion() {
        return true;
    }

    long insert(long maxAmount, TransactionContext transaction);

    default boolean supportsExtraction() {
        return true;
    }

    long extract(long maxAmount, TransactionContext transaction);

    long getAmount();

    long getCapacity();
}
