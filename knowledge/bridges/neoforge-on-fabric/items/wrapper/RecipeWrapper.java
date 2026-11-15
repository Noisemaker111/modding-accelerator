package ${package}.items.wrapper;

import ${package}.items.IItemHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/** NeoForge's legacy net.neoforged.neoforge.items.wrapper.RecipeWrapper: an IItemHandler as a recipe's input. */
public class RecipeWrapper implements RecipeInput {

    protected final IItemHandler inv;

    public RecipeWrapper(IItemHandler inv) {
        this.inv = inv;
    }

    @Override
    public int size() {
        return inv.getSlots();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inv.getStackInSlot(slot);
    }
}
