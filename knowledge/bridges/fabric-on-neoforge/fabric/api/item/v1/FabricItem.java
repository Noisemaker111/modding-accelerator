package ${package}.fabric.api.item.v1;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Fabric API's FabricItem on NeoForge. Fabric injects it into every Item; here the bridge's mixin ItemFabricItem adds it
 * to Item when the game runs, and ModDevGradle's interface injection (which mmt convert sets up) adds it for the mod's
 * compile. Only Fabric's query is here. Fabric's hooks that only Fabric's own patches call (allowComponentsUpdateAnimation,
 * allowContinuingBlockBreaking, getCraftingRemainder(ItemStack), canBeEnchantedWith) are left out: NeoForge would never
 * call an item's override of them, so a converted item that overrides one fails to compile and is listed.
 */
public interface FabricItem {

    /**
     * The namespace of what the stack holds, as Fabric gives it: the item's, or for a potion or tipped arrow with a
     * potion, the potion's, or for an enchanted book with exactly one stored enchantment, that enchantment's.
     */
    default String getCreatorNamespace(ItemStack stack) {
        Holder<?> holder = stack.typeHolder();
        if ((this instanceof PotionItem || this instanceof TippedArrowItem) && stack.has(DataComponents.POTION_CONTENTS)) {
            Optional<Holder<Potion>> potion = stack.get(DataComponents.POTION_CONTENTS).potion();
            if (potion.isPresent()) holder = potion.get();
        } else if (stack.is(Items.ENCHANTED_BOOK) && stack.has(DataComponents.STORED_ENCHANTMENTS)) {
            Set<Holder<Enchantment>> stored = stack.get(DataComponents.STORED_ENCHANTMENTS).keySet();
            if (stored.size() == 1) holder = stored.iterator().next();
        }
        return holder.unwrapKey().orElseThrow().identifier().getNamespace();
    }
}
