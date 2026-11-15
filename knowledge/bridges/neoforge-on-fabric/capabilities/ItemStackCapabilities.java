package ${package}.capabilities;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** NeoForge's capability methods on ItemStack (its IItemStackExtension), for Fabric: added as LevelCapabilities is to Level. */
public interface ItemStackCapabilities {

    default <T, C> @Nullable T getCapability(ItemCapability<T, C> capability, C context) {
        return capability.getCapability((ItemStack) (Object) this, context);
    }

    default <T> @Nullable T getCapability(ItemCapability<T, @Nullable Void> capability) {
        return capability.getCapability((ItemStack) (Object) this, null);
    }
}
