package ${package}.items;

import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.items.IItemHandler: an inventory of slots, read and changed one stack at a time,
 * a simulated call changing nothing. Capabilities.ItemHandler.BLOCK presents one to Fabric API as an item Storage
 * (ItemHandlerStorage) and a Fabric Storage as one (StorageItemHandler).
 */
public interface IItemHandler {

    int getSlots();

    ItemStack getStackInSlot(int slot);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);
}
