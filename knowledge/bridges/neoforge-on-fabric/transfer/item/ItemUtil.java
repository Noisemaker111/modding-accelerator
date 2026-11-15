package ${package}.transfer.item;

import ${package}.transfer.ResourceHandler;
import ${package}.transfer.transaction.Transaction;
import ${package}.transfer.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** NeoForge's net.neoforged.neoforge.transfer.item.ItemUtil: an index as an ItemStack, and stack-in, remainder-out inserts. */
public final class ItemUtil {

    private ItemUtil() {}

    public static ItemStack getStack(ResourceHandler<ItemResource> handler, int index) {
        ItemResource resource = handler.getResource(index);
        return resource.isEmpty() ? ItemStack.EMPTY : resource.toStack(handler.getAmountAsInt(index));
    }

    public static ItemStack insertItemReturnRemaining(ResourceHandler<ItemResource> handler, ItemStack stack, boolean simulate, @Nullable TransactionContext transaction) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        try (Transaction nested = Transaction.open(transaction)) {
            int inserted = handler.insert(ItemResource.of(stack), stack.getCount(), nested);
            if (!simulate) nested.commit();
            int left = stack.getCount() - inserted;
            return left == 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        }
    }

    public static ItemStack insertItemReturnRemaining(ResourceHandler<ItemResource> handler, int index, ItemStack stack, boolean simulate, @Nullable TransactionContext transaction) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        try (Transaction nested = Transaction.open(transaction)) {
            int inserted = handler.insert(index, ItemResource.of(stack), stack.getCount(), nested);
            if (!simulate) nested.commit();
            int left = stack.getCount() - inserted;
            return left == 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        }
    }
}
