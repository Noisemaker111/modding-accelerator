package ${package}.fabric.api.item.v1;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Fabric API's FabricItemStack on NeoForge, added to ItemStack by the bridge's mixin ItemStackFabricItemStack (with
 * ItemFabricItem, which it asks). canBeEnchantedWith is left out: Fabric answers it from its own enchanting event and
 * NeoForge's isPrimaryItemFor and supportsEnchantment answer differently for books.
 */
public interface FabricItemStack {

    /** What crafting leaves of the stack: NeoForge's stack-aware remainder, which by default is the item's own. */
    default ItemStackTemplate getCraftingRemainder() {
        ItemStack self = (ItemStack) this;
        return self.getItem().getCraftingRemainder(self);
    }

    /** The namespace of what the stack holds (FabricItem#getCreatorNamespace). */
    default String getCreatorNamespace() {
        ItemStack self = (ItemStack) this;
        return ((FabricItem) self.getItem()).getCreatorNamespace(self);
    }
}
