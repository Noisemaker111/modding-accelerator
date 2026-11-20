package ${package}.mixin;

import ${package}.extensions.ItemHooks;
import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Gives Item NeoForge 21.1's hooks (ItemHooks), and answers Fabric API's item hooks the game asks with them: the recipe
 * remainder (FabricItem.getRecipeRemainder), whether a changed stack re-equips (allowComponentsUpdateAnimation),
 * which enchantments it takes (canBeEnchantedWith) and the mod it comes from (getCreatorNamespace).
 */
@Mixin(Item.class)
public abstract class ItemHooksMixin implements ItemHooks {

    public ItemStack getRecipeRemainder(ItemStack stack) {
        return ((ItemHooks) this).getCraftingRemainingItem(stack);
    }

    public boolean canBeEnchantedWith(ItemStack stack, Holder<Enchantment> enchantment, EnchantingContext context) {
        return context == EnchantingContext.PRIMARY ? ((ItemHooks) this).isPrimaryItemFor(stack, enchantment) : ((ItemHooks) this).supportsEnchantment(stack, enchantment);
    }

    public String getCreatorNamespace(ItemStack stack) {
        return ((ItemHooks) this).getCreatorModId(stack);
    }

    public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
        return ((ItemHooks) this).shouldCauseReequipAnimation(oldStack, newStack, false);
    }
}
