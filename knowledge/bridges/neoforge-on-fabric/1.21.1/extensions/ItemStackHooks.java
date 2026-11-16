package ${package}.extensions;

import net.minecraft.world.item.ItemStack;

/** NeoForge 21.1's ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem(): the item's answer for this stack (ItemHooks). Added by ItemStackHooksMixin. */
public interface ItemStackHooks {

    default boolean hasCraftingRemainingItem() {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).hasCraftingRemainingItem(self);
    }

    default ItemStack getCraftingRemainingItem() {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).getCraftingRemainingItem(self);
    }
}
