package ${package}.items;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's legacy net.neoforged.neoforge.items.ItemHandlerHelper. giveItemToPlayer is Fabric API's
 * PlayerInventoryStorage.offerOrDrop (the preferred slot first), which drops what does not fit.
 */
public class ItemHandlerHelper {

    public static ItemStack insertItem(@Nullable IItemHandler dest, ItemStack stack, boolean simulate) {
        if (dest == null || stack.isEmpty()) return stack;
        for (int slot = 0; slot < dest.getSlots(); slot++) {
            stack = dest.insertItem(slot, stack, simulate);
            if (stack.isEmpty()) return ItemStack.EMPTY;
        }
        return stack;
    }

    /** Into the slots that hold the same item first, then into the empty ones. */
    public static ItemStack insertItemStacked(@Nullable IItemHandler inventory, ItemStack stack, boolean simulate) {
        if (inventory == null || stack.isEmpty()) return stack;
        if (!stack.isStackable()) return insertItem(inventory, stack, simulate);
        int size = inventory.getSlots();
        for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
            if (ItemStack.isSameItemSameComponents(inventory.getStackInSlot(slot), stack)) stack = inventory.insertItem(slot, stack, simulate);
        }
        for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) stack = inventory.insertItem(slot, stack, simulate);
        }
        return stack;
    }

    public static void giveItemToPlayer(Player player, ItemStack stack) {
        giveItemToPlayer(player, stack, -1);
    }

    public static void giveItemToPlayer(Player player, ItemStack stack, int preferredSlot) {
        if (stack.isEmpty()) return;
        PlayerInventoryStorage inventory = PlayerInventoryStorage.of(player);
        ItemVariant variant = ItemVariant.of(stack);
        long amount = stack.getCount();
        try (Transaction transaction = Transaction.openOuter()) {
            if (preferredSlot >= 0 && preferredSlot < inventory.getSlotCount()) amount -= inventory.getSlot(preferredSlot).insert(variant, amount, transaction);
            if (amount > 0) inventory.offerOrDrop(variant, amount, transaction);
            transaction.commit();
        }
    }

    public static int calcRedstoneFromInventory(@Nullable IItemHandler inv) {
        if (inv == null) return 0;
        int found = 0;
        float proportion = 0;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            proportion += (float) stack.getCount() / (float) Math.min(inv.getSlotLimit(slot), stack.getMaxStackSize());
            found++;
        }
        proportion /= (float) inv.getSlots();
        return Mth.floor(proportion * 14.0F) + (found > 0 ? 1 : 0);
    }
}
