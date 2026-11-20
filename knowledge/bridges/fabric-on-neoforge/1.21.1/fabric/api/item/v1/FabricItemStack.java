package ${package}.fabric.api.item.v1;

import net.minecraft.world.item.ItemStack;

/**
 * Fabric API's FabricItemStack on NeoForge, added to ItemStack by the bridge's mixin ItemStackFabricItemStack (with
 * ItemFabricItem, which it asks). canBeEnchantedWith is left out: Fabric answers it from its own enchanting event and
 * NeoForge's isPrimaryItemFor and supportsEnchantment answer differently for books. The casts go through Object:
 * ItemStack is final, and a mod that needs none of this compiles it without ItemStack implementing the interface.
 */
public interface FabricItemStack {

    /** What crafting leaves of the stack (Fabric API 1.21.1): NeoForge 21.1's stack-aware remainder, by default the item's own. */
    default ItemStack getRecipeRemainder() {
        ItemStack self = (ItemStack) (Object) this;
        return self.getItem().getCraftingRemainingItem(self);
    }

    /** The namespace of what the stack holds (FabricItem#getCreatorNamespace). */
    default String getCreatorNamespace() {
        ItemStack self = (ItemStack) (Object) this;
        return ((FabricItem) self.getItem()).getCreatorNamespace(self);
    }
}
