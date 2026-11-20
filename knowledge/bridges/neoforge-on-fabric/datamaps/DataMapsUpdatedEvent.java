package ${package}.datamaps;

import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import ${package}.Event;

/**
 * NeoForge's net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent, posted on the game bus for each registry
 * with data maps once they are loaded (DataMaps, when the server's tags load): SERVER_RELOAD on the server, and
 * CLIENT_SYNC on a client of the same game (Fabric has no data map sync of its own: a client of a dedicated server
 * reads no data maps).
 */
public class DataMapsUpdatedEvent extends Event {

    private final RegistryAccess registries;
    private final Registry<?> registry;
    private final UpdateCause cause;

    public DataMapsUpdatedEvent(RegistryAccess registries, Registry<?> registry, UpdateCause cause) {
        this.registries = registries;
        this.registry = registry;
        this.cause = cause;
    }

    public RegistryAccess getRegistries() {
        return this.registries;
    }

    public Registry<?> getRegistry() {
        return this.registry;
    }

    public ResourceKey<? extends Registry<?>> getRegistryKey() {
        return this.registry.key();
    }

    @SuppressWarnings("unchecked")
    public <T> void ifRegistry(ResourceKey<Registry<T>> key, Consumer<Registry<T>> consumer) {
        if (this.registry.key() == key) consumer.accept((Registry<T>) this.registry);
    }

    public UpdateCause getCause() {
        return this.cause;
    }

    public enum UpdateCause {
        CLIENT_SYNC,
        SERVER_RELOAD
    }
}
