package ${package}.data;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Cloner;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.registries.RegistriesDatapackGenerator;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import ${package}.Event;
import ${package}.IModBusEvent;
import ${package}.ModContainer;

/**
 * NeoForge's net.neoforged.neoforge.data.event.GatherDataEvent, posted on the mod bus by Fabric's data generation
 * (DataEntrypoint, the fabric-datagen entrypoint mmt convert adds): Client then Server, both in Fabric's one data run, as
 * NeoForge's client data run generates both. Providers go into Fabric's pack for the mod (getGenerator is Fabric's
 * FabricDataGenerator, a DataGenerator); getLookupProvider is Fabric's registries. Differences: getResourceManager
 * reads nothing (Fabric's data run has no existing-resources manager); createDatapackRegistryObjects writes the
 * builder's entries for the mod's namespaces with the game's own RegistriesDatapackGenerator.
 */
public abstract class GatherDataEvent extends Event implements IModBusEvent {

    private final ModContainer modContainer;
    private final DataGenerator generator;
    private final DataGeneratorConfig config;
    private CompletableFuture<HolderLookup.Provider> lookupProvider;

    public GatherDataEvent(ModContainer modContainer, DataGenerator generator, DataGeneratorConfig config) {
        this.modContainer = modContainer;
        this.generator = generator;
        this.config = config;
        this.lookupProvider = config.lookupProvider();
    }

    public ModContainer getModContainer() {
        return this.modContainer;
    }

    public ResourceManager getResourceManager(PackType packType) {
        return ResourceManager.Empty.INSTANCE;
    }

    public Collection<Path> getInputs() {
        return this.config.inputs();
    }

    public DataGenerator getGenerator() {
        return this.generator;
    }

    public CompletableFuture<HolderLookup.Provider> getLookupProvider() {
        return this.lookupProvider;
    }

    public boolean includeDev() {
        return true;
    }

    public boolean includeReports() {
        return false;
    }

    public boolean validate() {
        return this.config.validate();
    }

    public <T extends DataProvider> T addProvider(T provider) {
        return this.generator.getVanillaPack(true).addProvider(output -> provider);
    }

    public <T extends DataProvider> T createProvider(DataProviderFromOutput<T> builder) {
        return this.generator.getVanillaPack(true).addProvider(builder::create);
    }

    public <T extends DataProvider> T createProvider(DataProviderFromOutputLookup<T> builder) {
        return this.generator.getVanillaPack(true).addProvider(output -> builder.create(output, this.lookupProvider));
    }

    public void createBlockAndItemTags(DataProviderFromOutputLookup<TagsProvider<Block>> blockTagsProvider, ItemTagsProvider itemTagsProvider) {
        TagsProvider<Block> blocks = createProvider(blockTagsProvider);
        createProvider(output -> itemTagsProvider.create(output, this.lookupProvider, blocks.contentsGetter()));
    }

    public void createDatapackRegistryObjects(RegistrySetBuilder registrySetBuilder) {
        createDatapackRegistryObjects(registrySetBuilder, this.config.mods());
    }

    public void createDatapackRegistryObjects(RegistrySetBuilder registrySetBuilder, Set<String> modIds) {
        // The builder's entries patched over the registries, as NeoForge does: later providers see them, and only the
        // patch (the mod's entries) is written.
        Cloner.Factory cloners = new Cloner.Factory();
        RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(data -> addCodec(cloners, data));
        CompletableFuture<RegistrySetBuilder.PatchedRegistries> patched = this.lookupProvider.thenApply(
            lookup -> registrySetBuilder.buildPatch(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), lookup, cloners)
        );
        this.lookupProvider = patched.thenApply(RegistrySetBuilder.PatchedRegistries::full);
        this.generator.getVanillaPack(true).addProvider(output -> new RegistriesDatapackGenerator(output, patched.thenApply(RegistrySetBuilder.PatchedRegistries::patches)));
    }

    private static <T> void addCodec(Cloner.Factory cloners, RegistryDataLoader.RegistryData<T> data) {
        cloners.addCodec(data.key(), data.elementCodec());
    }

    /** NeoForge's per-mod data generation settings: the mods, the inputs, Fabric's registries and its validation flag. */
    public record DataGeneratorConfig(Set<String> mods, Collection<Path> inputs, CompletableFuture<HolderLookup.Provider> lookupProvider, boolean validate) {

        public Collection<Path> getInputs() {
            return this.inputs;
        }

        public Set<String> getMods() {
            return this.mods;
        }

        public boolean isFlat() {
            return true;
        }
    }

    public static class Client extends GatherDataEvent {

        public Client(ModContainer modContainer, DataGenerator generator, DataGeneratorConfig config) {
            super(modContainer, generator, config);
        }
    }

    public static class Server extends GatherDataEvent {

        public Server(ModContainer modContainer, DataGenerator generator, DataGeneratorConfig config) {
            super(modContainer, generator, config);
        }
    }

    @FunctionalInterface
    public interface DataProviderFromOutput<T extends DataProvider> {
        T create(PackOutput output);
    }

    @FunctionalInterface
    public interface DataProviderFromOutputLookup<T extends DataProvider> {
        T create(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider);
    }

    @FunctionalInterface
    public interface ItemTagsProvider {
        TagsProvider<Item> create(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, CompletableFuture<TagsProvider.TagLookup<Block>> blockTags);
    }
}
