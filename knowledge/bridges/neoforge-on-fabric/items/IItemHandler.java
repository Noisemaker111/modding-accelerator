package ${package}.items;

import ${package}.transfer.ResourceHandler;
import ${package}.transfer.item.ItemResource;
import ${package}.transfer.item.ItemUtil;
import ${package}.transfer.transaction.Transaction;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's legacy net.neoforged.neoforge.items.IItemHandler (deprecated since NeoForge 21.9, still in 26.1): slots of
 * item stacks with simulate flags. of(handler) presents a transfer ResourceHandler as one; each call is its own root
 * transaction, so, as on NeoForge, it cannot be used while a transaction is open.
 */
public interface IItemHandler {

    static IItemHandler of(ResourceHandler<ItemResource> handler) {
        Objects.requireNonNull(handler, "handler");
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return handler.size();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return ItemUtil.getStack(handler, slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return ItemUtil.insertItemReturnRemaining(handler, slot, stack, simulate, null);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (amount <= 0) return ItemStack.EMPTY;
                ItemResource resource = handler.getResource(slot);
                if (resource.isEmpty()) return ItemStack.EMPTY;
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = handler.extract(slot, resource, Math.min(amount, resource.getMaxStackSize()), transaction);
                    if (!simulate) transaction.commit();
                    return resource.toStack(extracted);
                }
            }

            @Override
            public int getSlotLimit(int slot) {
                return handler.getCapacityAsInt(slot, ItemResource.EMPTY);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack.isEmpty() || handler.isValid(slot, ItemResource.of(stack));
            }
        };
    }

    int getSlots();

    ItemStack getStackInSlot(int slot);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);
}
