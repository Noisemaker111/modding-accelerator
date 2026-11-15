package ${package}.items;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** NeoForge's legacy net.neoforged.neoforge.items.SlotItemHandler: a menu slot showing one slot of an IItemHandler. */
public class SlotItemHandler extends Slot {

    private static final Container EMPTY_INVENTORY = new SimpleContainer(0);

    private final IItemHandler itemHandler;
    protected final int index;

    public SlotItemHandler(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
        super(EMPTY_INVENTORY, index, xPosition, yPosition);
        this.itemHandler = itemHandler;
        this.index = index;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty() && itemHandler.isItemValid(index, stack);
    }

    @Override
    public ItemStack getItem() {
        return getItemHandler().getStackInSlot(index);
    }

    @Override
    public void set(ItemStack stack) {
        ((IItemHandlerModifiable) getItemHandler()).setStackInSlot(index, stack);
        setChanged();
    }

    public void initialize(ItemStack stack) {
        ((IItemHandlerModifiable) getItemHandler()).setStackInSlot(index, stack);
        setChanged();
    }

    @Override
    public void onQuickCraft(ItemStack oldStackIn, ItemStack newStackIn) {}

    @Override
    public int getMaxStackSize() {
        return itemHandler.getSlotLimit(index);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return Math.min(stack.getMaxStackSize(), itemHandler.getSlotLimit(index));
    }

    @Override
    public boolean mayPickup(Player player) {
        return !getItemHandler().extractItem(index, 1, true).isEmpty();
    }

    @Override
    public ItemStack remove(int amount) {
        return getItemHandler().extractItem(index, amount, false);
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }
}
