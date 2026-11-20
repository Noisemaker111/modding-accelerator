package ${package}.fluids.capability;

import net.minecraft.world.item.ItemStack;

/** NeoForge 21.1's net.neoforged.neoforge.fluids.capability.IFluidHandlerItem: a fluid handler of an item, and the item it leaves. */
public interface IFluidHandlerItem extends IFluidHandler {

    ItemStack getContainer();
}
