package ${package};

import java.util.function.Supplier;

/**
 * FancyModLoader's net.neoforged.fml.ModLoadingContext for the converted mod. NeoForge sets the active container while
 * it constructs and dispatches to each mod; this bridge is copied into one mod only, so its active container is always
 * that mod's (${modid}). Extension points have no Fabric counterpart without another mod, as in ModContainer.
 */
public final class ModLoadingContext {

    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    private ModLoadingContext() {}

    public static ModLoadingContext get() {
        return INSTANCE;
    }

    public ModContainer getActiveContainer() {
        return Bridge.container("${modid}");
    }

    public String getActiveNamespace() {
        return getActiveContainer().getNamespace();
    }

    public <T> void registerExtensionPoint(Class<T> point, Supplier<T> extension) {
        getActiveContainer().registerExtensionPoint(point, extension);
    }
}
