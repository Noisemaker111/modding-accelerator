package ${package}.items;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.items.StackCopySlot: a menu slot whose stack is a copy read from and written back
 * to somewhere else (an item's component), so changes to the returned stack are written back on setChanged.
 */
public abstract class StackCopySlot extends Slot {

    private static final Container EMPTY = new SimpleContainer(0);

    private ItemStack cachedReturnedStack = null;

    public StackCopySlot(int x, int y) {
        super(EMPTY, 0, x, y);
    }

    protected abstract ItemStack getStackCopy();

    protected abstract void setStackCopy(ItemStack stack);

    @Override
    public final ItemStack getItem() {
        return cachedReturnedStack = getStackCopy();
    }

    @Override
    public final void set(ItemStack stack) {
        setStackCopy(stack);
        cachedReturnedStack = stack;
    }

    @Override
    public final void setChanged() {
        if (cachedReturnedStack != null) set(cachedReturnedStack);
    }

    @Override
    public final ItemStack remove(int amount) {
        ItemStack stack = getStackCopy().copy();
        ItemStack taken = stack.split(amount);
        set(stack);
        cachedReturnedStack = null;
        return taken;
    }
}
