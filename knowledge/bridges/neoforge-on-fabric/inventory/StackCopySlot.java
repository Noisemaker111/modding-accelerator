package ${package}.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.world.inventory.StackCopySlot: a menu slot whose stack lives in storage other than a
 * container, read and written as copies (getStackCopy, setStackCopy). The stack the slot last handed out is written back
 * on setChanged only when the storage no longer holds the same stack, as NeoForge does.
 */
public abstract class StackCopySlot extends Slot {

    private static final Container EMPTY = new SimpleContainer(0);

    private @Nullable ItemStack cachedReturnedStack = null;

    public StackCopySlot(int slot, int x, int y) {
        super(EMPTY, slot, x, y);
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
        if (cachedReturnedStack != null && !ItemStack.matches(cachedReturnedStack, getStackCopy())) set(cachedReturnedStack);
    }

    @Override
    public ItemStack remove(int amount) {
        ItemStack stack = getStackCopy().copy();
        ItemStack taken = stack.split(amount);
        set(stack);
        clearCachedReturnStack();
        return taken;
    }

    protected void clearCachedReturnStack() {
        cachedReturnedStack = null;
    }
}
