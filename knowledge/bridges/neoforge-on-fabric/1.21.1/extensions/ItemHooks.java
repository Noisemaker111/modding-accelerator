package ${package}.extensions;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import ${package}.ItemAbilities;
import ${package}.ItemAbility;
import net.minecraft.core.HolderSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
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

    /** Whether the stack can do the ability: NeoForge's defaults for the game's tools (an item can override this, as on NeoForge). */
    default boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        Item item = self();
        if (item instanceof AxeItem) return ItemAbilities.DEFAULT_AXE_ACTIONS.contains(ability);
        if (item instanceof HoeItem) return ItemAbilities.DEFAULT_HOE_ACTIONS.contains(ability);
        if (item instanceof ShovelItem) return ItemAbilities.DEFAULT_SHOVEL_ACTIONS.contains(ability);
        if (item instanceof PickaxeItem) return ItemAbilities.DEFAULT_PICKAXE_ACTIONS.contains(ability);
        if (item instanceof SwordItem) return ItemAbilities.DEFAULT_SWORD_ACTIONS.contains(ability);
        if (item instanceof ShearsItem) return ItemAbilities.DEFAULT_SHEARS_ACTIONS.contains(ability);
        if (item instanceof ShieldItem) return ItemAbilities.DEFAULT_SHIELD_ACTIONS.contains(ability);
        if (item instanceof FishingRodItem) return ItemAbilities.DEFAULT_FISHING_ROD_ACTIONS.contains(ability);
        if (item instanceof TridentItem) return ItemAbilities.DEFAULT_TRIDENT_ACTIONS.contains(ability);
        if (item instanceof BrushItem) return ItemAbilities.DEFAULT_BRUSH_ACTIONS.contains(ability);
        if (item instanceof FlintAndSteelItem) return ItemAbilities.DEFAULT_FLINT_ACTIONS.contains(ability);
        if (item instanceof FireChargeItem) return ItemAbilities.DEFAULT_FIRECHARGE_ACTIONS.contains(ability);
        if (item instanceof SpyglassItem) return ItemAbilities.DEFAULT_SPYGLASS_ACTIONS.contains(ability);
        return false;
    }

    /** Whether an enchantment may go on the stack (Fabric API's canBeEnchantedWith, ACCEPTABLE, asks this): NeoForge's default. */
    default boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return stack.is(Items.ENCHANTED_BOOK) || enchantment.value().isSupportedItem(stack);
    }

    /** Whether the stack is a primary item for the enchantment (canBeEnchantedWith, PRIMARY): NeoForge's default. */
    default boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        if (stack.getItem() == Items.BOOK) return true;
        Optional<HolderSet<Item>> primaries = enchantment.value().definition().primaryItems();
        return supportsEnchantment(stack, enchantment) && (primaries.isEmpty() || stack.is(primaries.get()));
    }

    /** The stack's food: its food component (the game reads the component when the food is eaten; an override is not asked there). */
    default FoodProperties getFoodProperties(ItemStack stack, LivingEntity entity) {
        return stack.get(DataComponents.FOOD);
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
