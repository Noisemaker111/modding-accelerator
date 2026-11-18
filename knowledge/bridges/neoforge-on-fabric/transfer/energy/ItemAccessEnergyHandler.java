package ${package}.transfer.energy;

import ${package}.transfer.TransferPreconditions;
import ${package}.transfer.access.ItemAccess;
import ${package}.transfer.item.ItemResource;
import ${package}.transfer.transaction.TransactionContext;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;

/**
 * NeoForge's net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler: the energy of the items an item access
 * holds, kept per item in an integer data component. Inserting and extracting move up to maxInsert or maxExtract per
 * item, spread evenly over the stack, by exchanging the items for ones with the new amount.
 */
public class ItemAccessEnergyHandler implements EnergyHandler {

    protected final ItemAccess itemAccess;
    protected final Item validItem;
    protected final DataComponentType<Integer> energyComponent;
    protected final int capacity;
    protected final int maxInsert;
    protected final int maxExtract;

    public ItemAccessEnergyHandler(ItemAccess itemAccess, DataComponentType<Integer> energyComponent, int capacity) {
        this(itemAccess, energyComponent, capacity, capacity);
    }

    public ItemAccessEnergyHandler(ItemAccess itemAccess, DataComponentType<Integer> energyComponent, int capacity, int maxTransfer) {
        this(itemAccess, energyComponent, capacity, maxTransfer, maxTransfer);
    }

    public ItemAccessEnergyHandler(ItemAccess itemAccess, DataComponentType<Integer> energyComponent, int capacity, int maxInsert, int maxExtract) {
        TransferPreconditions.checkNonNegative(capacity);
        TransferPreconditions.checkNonNegative(maxInsert);
        TransferPreconditions.checkNonNegative(maxExtract);
        this.itemAccess = itemAccess;
        this.validItem = itemAccess.getResource().getItem();
        this.energyComponent = energyComponent;
        this.capacity = capacity;
        this.maxInsert = maxInsert;
        this.maxExtract = maxExtract;
    }

    protected int getAmountFrom(ItemResource resource) {
        return resource.is(validItem) ? resource.getOrDefault(energyComponent, 0) : 0;
    }

    protected ItemResource update(ItemResource resource, int newAmount) {
        return resource.with(energyComponent, newAmount);
    }

    @Override
    public long getAmountAsLong() {
        return (long) itemAccess.getAmount() * getAmountFrom(itemAccess.getResource());
    }

    @Override
    public long getCapacityAsLong() {
        return itemAccess.getResource().is(validItem) ? (long) itemAccess.getAmount() * capacity : 0;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        int items = itemAccess.getAmount();
        if (items == 0) return 0;
        int perItem = Math.min(maxInsert, amount / items);
        ItemResource resource = itemAccess.getResource();
        if (perItem == 0 || !resource.is(validItem)) return 0;
        int current = getAmountFrom(resource);
        int inserted = Math.min(perItem, capacity - current);
        if (inserted <= 0) return 0;
        ItemResource filled = update(resource, current + inserted);
        return filled.isEmpty() ? 0 : inserted * itemAccess.exchange(filled, items, transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        int items = itemAccess.getAmount();
        if (items == 0) return 0;
        int perItem = Math.min(maxExtract, amount / items);
        if (perItem == 0) return 0;
        ItemResource resource = itemAccess.getResource();
        int current = getAmountFrom(resource);
        int extracted = Math.min(perItem, current);
        if (extracted <= 0) return 0;
        ItemResource emptied = update(resource, current - extracted);
        return emptied.isEmpty() ? 0 : extracted * itemAccess.exchange(emptied, items, transaction);
    }
}
