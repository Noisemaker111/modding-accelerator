package ${package}.items.wrapper;

import ${package}.items.IItemHandlerModifiable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** NeoForge's legacy net.neoforged.neoforge.items.wrapper.InvWrapper: a vanilla Container as an IItemHandlerModifiable. */
public class InvWrapper implements IItemHandlerModifiable {

    private final Container inv;

    public InvWrapper(Container inv) {
        this.inv = inv;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o != null && getClass() == o.getClass() && getInv().equals(((InvWrapper) o).getInv());
    }

    @Override
    public int hashCode() {
        return getInv().hashCode();
    }

    @Override
    public int getSlots() {
        return getInv().getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return getInv().getItem(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack inSlot = getInv().getItem(slot);
        if (!inSlot.isEmpty() && (inSlot.getCount() >= Math.min(inSlot.getMaxStackSize(), getSlotLimit(slot)) || !ItemStack.isSameItemSameComponents(stack, inSlot))) return stack;
        if (!getInv().canPlaceItem(slot, stack)) return stack;
        int room = Math.min(stack.getMaxStackSize(), getSlotLimit(slot)) - inSlot.getCount();
        int moved = Math.min(room, stack.getCount());
        if (moved <= 0) return stack;
        if (!simulate) {
            getInv().setItem(slot, stack.copyWithCount(inSlot.getCount() + moved));
            getInv().setChanged();
        }
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) return ItemStack.EMPTY;
        ItemStack inSlot = getInv().getItem(slot);
        if (inSlot.isEmpty()) return ItemStack.EMPTY;
        if (simulate) return inSlot.copyWithCount(Math.min(inSlot.getCount(), amount));
        ItemStack removed = getInv().removeItem(slot, Math.min(inSlot.getCount(), amount));
        getInv().setChanged();
        return removed;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        getInv().setItem(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return getInv().getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return getInv().canPlaceItem(slot, stack);
    }

    public Container getInv() {
        return inv;
    }
}
