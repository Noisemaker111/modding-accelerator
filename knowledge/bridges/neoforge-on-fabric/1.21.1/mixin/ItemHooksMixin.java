package ${package}.mixin;

import ${package}.extensions.ItemHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Gives Item NeoForge 21.1's hooks (ItemHooks), and answers Fabric API's item hooks the game asks with them: the recipe
 * remainder (FabricItem.getRecipeRemainder) and whether a changed stack re-equips (allowComponentsUpdateAnimation).
 */
@Mixin(Item.class)
public abstract class ItemHooksMixin implements ItemHooks {

    public ItemStack getRecipeRemainder(ItemStack stack) {
        return ((ItemHooks) this).getCraftingRemainingItem(stack);
    }

    public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
        return ((ItemHooks) this).shouldCauseReequipAnimation(oldStack, newStack, false);
    }
}
