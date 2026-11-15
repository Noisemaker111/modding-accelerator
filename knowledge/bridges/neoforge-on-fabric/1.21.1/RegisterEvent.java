package ${package};

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 21.1's net.neoforged.neoforge.registries.RegisterEvent (ResourceLocation names): posted once for each of the game's registries, in the
 * game's registry order, when the mod's main entrypoint ends (Bridge.common, from DeferredRegister.registerPending,
 * right after the DeferredRegisters of that registry registered their entries), while Fabric still lets mods register.
 * The same members as NeoForge's. Difference: NeoForge also posts it for registries other mods made, before any of the
 * game's; here only the game's own registries (BuiltInRegistries.REGISTRY) get it, once per converted mod.
 */
public class RegisterEvent extends Event implements IModBusEvent {

    private static final Set<ResourceKey<?>> POSTED = new HashSet<>();

    private final ResourceKey<? extends Registry<?>> registryKey;
    private final Registry<?> registry;

    RegisterEvent(ResourceKey<? extends Registry<?>> registryKey, Registry<?> registry) {
        this.registryKey = registryKey;
        this.registry = registry;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> void register(ResourceKey<? extends Registry<T>> registryKey, ResourceLocation name, Supplier<T> valueSupplier) {
        if (this.registryKey.equals(registryKey)) {
            Registry.register((Registry) this.registry, name, valueSupplier.get());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> void register(ResourceKey<? extends Registry<T>> registryKey, Consumer<RegisterHelper<T>> consumer) {
        if (this.registryKey.equals(registryKey)) {
            consumer.accept((name, value) -> Registry.register((Registry) this.registry, name, value));
        }
    }

    public ResourceKey<? extends Registry<?>> getRegistryKey() {
        return this.registryKey;
    }

    public Registry<?> getRegistry() {
        return this.registry;
    }

    @SuppressWarnings("unchecked")
    public <T> Registry<T> getRegistry(ResourceKey<? extends Registry<T>> key) {
        return key == this.registryKey ? (Registry<T>) this.registry : null;
    }

    /** Posts the event for one registry, once. */
    static void post(Registry<?> registry) {
        if (!POSTED.add(registry.key())) return;
        Bus.INSTANCE.post(new RegisterEvent(registry.key(), registry));
    }

    @FunctionalInterface
    public interface RegisterHelper<T> {

        default void register(ResourceKey<T> key, T value) {
            register(key.location(), value);
        }

        void register(ResourceLocation name, T value);
    }
}
