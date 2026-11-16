package ${package}.extensions;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The hooks of NeoForge 21.1's IItemExtension that items override, with NeoForge's defaults: the crafting remainder of a
 * stack (the game asks Fabric API's FabricItem.getRecipeRemainder, which ItemHooksMixin answers with these), whether a
 * changed stack plays the re-equip animation (FabricItem.allowComponentsUpdateAnimation), and the mod a stack comes from.
 * Added to Item by ItemHooksMixin, and for the compile (an override, a super call) by Loom's interface injection.
 */
public interface ItemHooks {

    private Item self() {
        return (Item) (Object) this;
    }

    default ItemStack getCraftingRemainingItem(ItemStack stack) {
        if (!hasCraftingRemainingItem(stack)) return ItemStack.EMPTY;
        return new ItemStack(self().getCraftingRemainingItem());
    }

    default boolean hasCraftingRemainingItem(ItemStack stack) {
        return self().hasCraftingRemainingItem();
    }

    default boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return !oldStack.equals(newStack);
    }

    /** NeoForge's default (CommonHooks.getDefaultCreatorModId): the item's namespace, or for a minecraft item the namespace of its one stored enchantment, its potion or its spawn egg's entity. */
    default String getCreatorModId(ItemStack stack) {
        Item item = stack.getItem();
        String namespace = BuiltInRegistries.ITEM.getKey(item).getNamespace();
        if (!"minecraft".equals(namespace)) return namespace;
        if (item instanceof EnchantedBookItem) {
            ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (enchantments != null && enchantments.size() == 1) {
                Holder<Enchantment> enchantment = enchantments.keySet().iterator().next();
                Optional<ResourceKey<Enchantment>> key = enchantment.unwrapKey();
                if (key.isPresent()) return key.get().location().getNamespace();
            }
        } else if (item instanceof PotionItem || item instanceof TippedArrowItem) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.potion().isPresent()) {
                Optional<ResourceKey<Potion>> key = contents.potion().get().unwrapKey();
                if (key.isPresent()) return key.get().location().getNamespace();
            }
        } else if (item instanceof SpawnEggItem egg) {
            ResourceLocation entity = BuiltInRegistries.ENTITY_TYPE.getKey(egg.getType(stack));
            return entity.getNamespace();
        }
        return namespace;
    }
}
