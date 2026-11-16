package ${package};

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
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
 * NeoForge's net.neoforged.neoforge.common.extensions.IItemExtension, whose methods NeoForge adds to Item: here as static
 * methods taking the item first, which mmt convert's rules write in place of the calls. Each answers as NeoForge's
 * default; an item cannot override them on Fabric.
 */
public final class IItemExtension {

    private IItemExtension() {}

    /**
     * The mod a stack comes from, as NeoForge's default (CommonHooks.getDefaultCreatorModId): the item's namespace, or for
     * a minecraft item, the namespace of its one stored enchantment, its potion, or its spawn egg's entity.
     */
    public static String getCreatorModId(Item item, HolderLookup.Provider registries, ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String modId = id == null ? null : id.getNamespace();
        if (!"minecraft".equals(modId)) return modId;
        Item held = stack.getItem();
        if (stack.has(DataComponents.STORED_ENCHANTMENTS)) {
            Set<Holder<Enchantment>> stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).keySet();
            if (stored.size() == 1) {
                Optional<ResourceKey<Enchantment>> key = stored.iterator().next().unwrapKey();
                if (key.isPresent()) return key.get().identifier().getNamespace();
            }
        } else if (held instanceof PotionItem || held instanceof TippedArrowItem) {
            Optional<ResourceKey<Potion>> key = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion().flatMap(Holder::unwrapKey);
            if (key.isPresent()) return key.get().identifier().getNamespace();
        } else if (held instanceof SpawnEggItem) {
            EntityType<?> type = SpawnEggItem.getType(stack);
            Optional<ResourceKey<EntityType<?>>> key = type == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getResourceKey(type);
            if (key.isPresent()) return key.get().identifier().getNamespace();
        }
        return modId;
    }
}
