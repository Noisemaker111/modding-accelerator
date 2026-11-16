package ${package}.extensions;

import ${package}.ItemAbility;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/** NeoForge 21.1's ItemStack methods that ask the item about this stack (ItemHooks): crafting remainder, abilities, food. Added by ItemStackHooksMixin. */
public interface ItemStackHooks {

    default boolean hasCraftingRemainingItem() {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).hasCraftingRemainingItem(self);
    }

    default ItemStack getCraftingRemainingItem() {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).getCraftingRemainingItem(self);
    }

    default boolean canPerformAction(ItemAbility ability) {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).canPerformAction(self, ability);
    }

    default FoodProperties getFoodProperties(LivingEntity entity) {
        ItemStack self = (ItemStack) (Object) this;
        return ((ItemHooks) self.getItem()).getFoodProperties(self, entity);
    }
}
