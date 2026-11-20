package ${package}.data;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Cloner;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.registries.RegistriesDatapackGenerator;
import net.minecraft.resources.RegistryDataLoader;

/**
 * NeoForge's net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider: the game's registry generator over a
 * RegistrySetBuilder's entries patched onto the registries, writing only the patch (the mod's own entries), with the
 * full patched registries for later providers (getRegistryProvider).
 */
public class DatapackBuiltinEntriesProvider extends RegistriesDatapackGenerator {

    private final CompletableFuture<HolderLookup.Provider> registryProvider;

    public DatapackBuiltinEntriesProvider(PackOutput output, CompletableFuture<RegistrySetBuilder.PatchedRegistries> registries, Set<String> modIds) {
        super(output, registries.thenApply(RegistrySetBuilder.PatchedRegistries::patches));
        this.registryProvider = registries.thenApply(RegistrySetBuilder.PatchedRegistries::full);
    }

    public DatapackBuiltinEntriesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, RegistrySetBuilder builder, Set<String> modIds) {
        this(output, patch(registries, builder), modIds);
    }

    public CompletableFuture<HolderLookup.Provider> getRegistryProvider() {
        return this.registryProvider;
    }

    /** The builder's entries over the registries, as NeoForge patches them (a cloner per datapack registry). */
    public static CompletableFuture<RegistrySetBuilder.PatchedRegistries> patch(CompletableFuture<HolderLookup.Provider> registries, RegistrySetBuilder builder) {
        Cloner.Factory cloners = new Cloner.Factory();
        RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(data -> addCodec(cloners, data));
        return registries.thenApply(lookup -> builder.buildPatch(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), lookup, cloners));
    }

    private static <T> void addCodec(Cloner.Factory cloners, RegistryDataLoader.RegistryData<T> data) {
        cloners.addCodec(data.key(), data.elementCodec());
    }
}
