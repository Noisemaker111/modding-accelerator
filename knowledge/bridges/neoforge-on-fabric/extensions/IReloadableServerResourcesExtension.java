package ${package}.extensions;

import net.minecraft.core.HolderLookup;

/**
 * What NeoForge adds to the game's ReloadableServerResources: the registry lookup the reload loads data with
 * (getRegistryLookup), which mixin/ReloadableServerResourcesMixin keeps as the game makes the resources.
 */
public interface IReloadableServerResourcesExtension {

    default HolderLookup.Provider getRegistryLookup() {
        throw new IllegalStateException("ReloadableServerResources made before the bridge's mixin applied");
    }
}
