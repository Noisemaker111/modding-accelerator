package ${package}.capabilities;

import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** NeoForge's capability methods on Entity, for Fabric: added to Entity as LevelCapabilities is to Level (EntityCapabilitiesMixin). */
public interface EntityCapabilities {

    default <T, C> @Nullable T getCapability(EntityCapability<T, C> capability, C context) {
        return capability.getCapability((Entity) this, context);
    }

    default <T> @Nullable T getCapability(EntityCapability<T, @Nullable Void> capability) {
        return capability.getCapability((Entity) this, null);
    }
}
