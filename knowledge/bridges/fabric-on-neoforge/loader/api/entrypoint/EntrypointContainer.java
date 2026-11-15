package ${package}.loader.api.entrypoint;

import ${package}.loader.api.ModContainer;

/** Fabric Loader's net.fabricmc.loader.api.entrypoint.EntrypointContainer: one entrypoint and the mod that declares it. */
public final class EntrypointContainer<T> {

    private final T entrypoint;
    private final ModContainer provider;
    private final String definition;

    public EntrypointContainer(T entrypoint, ModContainer provider, String definition) {
        this.entrypoint = entrypoint;
        this.provider = provider;
        this.definition = definition;
    }

    public T getEntrypoint() {
        return entrypoint;
    }

    public ModContainer getProvider() {
        return provider;
    }

    public String getDefinition() {
        return definition;
    }
}
