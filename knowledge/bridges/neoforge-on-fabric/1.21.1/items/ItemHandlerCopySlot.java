package ${package}.items;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.items.ItemHandlerCopySlot: a StackCopySlot over one slot of an item handler
 * (a ComponentItemHandler), placing, picking up and stack limits answered by that slot's SlotItemHandler.
 */
public class ItemHandlerCopySlot extends StackCopySlot {

    private final SlotItemHandler slotItemHandler;

    public ItemHandlerCopySlot(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
        super(xPosition, yPosition);
        this.slotItemHandler = new SlotItemHandler(itemHandler, index, xPosition, yPosition);
    }

    public ItemHandlerCopySlot(SlotItemHandler slotItemHandler) {
        super(slotItemHandler.x, slotItemHandler.y);
        this.slotItemHandler = slotItemHandler;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return slotItemHandler.mayPlace(stack);
    }

    @Override
    protected ItemStack getStackCopy() {
        return slotItemHandler.getItem();
    }

    @Override
    protected void setStackCopy(ItemStack stack) {
        ((IItemHandlerModifiable) slotItemHandler.getItemHandler()).setStackInSlot(slotItemHandler.index, stack);
    }

    @Override
    public void onQuickCraft(ItemStack oldStackIn, ItemStack newStackIn) {
        slotItemHandler.onQuickCraft(oldStackIn, newStackIn);
    }

    @Override
    public int getMaxStackSize() {
        return slotItemHandler.getMaxStackSize();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return slotItemHandler.getMaxStackSize(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return slotItemHandler.mayPickup(player);
    }

    /** NeoForge's Slot.isSameInventory: whether the other slot is over the same item handler. */
    public boolean isSameInventory(Slot other) {
        return other instanceof SlotItemHandler handlerSlot && handlerSlot.getItemHandler() == getItemHandler()
            || other instanceof ItemHandlerCopySlot copySlot && copySlot.getItemHandler() == getItemHandler();
    }

    public IItemHandler getItemHandler() {
        return slotItemHandler.getItemHandler();
    }
}
