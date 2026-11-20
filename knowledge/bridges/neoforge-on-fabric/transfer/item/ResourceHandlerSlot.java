package ${package}.transfer.item;

import ${package}.inventory.StackCopySlot;
import ${package}.transfer.IndexModifier;
import ${package}.transfer.ResourceHandler;
import ${package}.transfer.transaction.Transaction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.transfer.item.ResourceHandlerSlot: a menu slot over one index of an item resource
 * handler. A stack may be placed when the handler accepts its resource there, taken when one item can be extracted (a
 * simulated extraction, rolled back), and is written through the index modifier.
 */
public class ResourceHandlerSlot extends StackCopySlot {

    private final ResourceHandler<ItemResource> handler;
    private final IndexModifier<ItemResource> slotModifier;

    public ResourceHandlerSlot(ResourceHandler<ItemResource> handler, IndexModifier<ItemResource> slotModifier, int handlerSlot, int xPosition, int yPosition) {
        super(handlerSlot, xPosition, yPosition);
        this.handler = handler;
        this.slotModifier = slotModifier;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty() && handler.isValid(this.getContainerSlot(), ItemResource.of(stack));
    }

    @Override
    protected ItemStack getStackCopy() {
        return handler.getResource(this.getContainerSlot()).toStack(handler.getAmountAsInt(this.getContainerSlot()));
    }

    @Override
    protected void setStackCopy(ItemStack stack) {
        slotModifier.set(this.getContainerSlot(), ItemResource.of(stack), stack.getCount());
    }

    @Override
    public void onQuickCraft(ItemStack oldStackIn, ItemStack newStackIn) {}

    @Override
    public int getMaxStackSize() {
        return handler.getCapacityAsInt(this.getContainerSlot(), ItemResource.EMPTY);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return handler.getCapacityAsInt(this.getContainerSlot(), ItemResource.of(stack));
    }

    @Override
    public boolean mayPickup(Player player) {
        ItemResource resource = handler.getResource(this.getContainerSlot());
        if (resource.isEmpty()) return false;
        try (Transaction transaction = Transaction.openRoot()) {
            return handler.extract(this.getContainerSlot(), resource, 1, transaction) == 1;
        }
    }

    public ResourceHandler<ItemResource> getResourceHandler() {
        return handler;
    }

    /** NeoForge's Slot.isSameInventory (a method NeoForge adds to Slot): whether the other slot is over the same handler. */
    public boolean isSameInventory(Slot other) {
        return other instanceof ResourceHandlerSlot slot && slot.handler == this.handler;
    }
}
