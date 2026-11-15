package ${package}.items;

import net.minecraft.world.item.ItemStack;

/** NeoForge's legacy net.neoforged.neoforge.items.IItemHandlerModifiable: an IItemHandler whose slots can be set directly. */
public interface IItemHandlerModifiable extends IItemHandler {

    void setStackInSlot(int slot, ItemStack stack);
}
