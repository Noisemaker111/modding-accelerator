package ${package}.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import ${package}.conditions.ICondition;
import ${package}.conditions.WithConditions;

/**
 * NeoForge's net.neoforged.neoforge.common.data.JsonCodecProvider: one JSON file per entry the mod gives in gather,
 * at TARGET/NAMESPACE/DIRECTORY/PATH.json, written with its codec; an entry given conditionally also carries
 * neoforge:conditions (convert moves those to Fabric's form in the mod's shipped files).
 */
public abstract class JsonCodecProvider<T> implements DataProvider {

    private final PackOutput output;
    private final PackOutput.Target target;
    private final String directory;
    private final Codec<T> codec;
    private final CompletableFuture<HolderLookup.Provider> lookupProvider;
    protected final String modid;
    private final Map<ResourceLocation, WithConditions<T>> entries = new LinkedHashMap<>();

    public JsonCodecProvider(PackOutput output, PackOutput.Target target, String directory, net.minecraft.server.packs.PackType packType, Codec<T> codec, CompletableFuture<HolderLookup.Provider> lookupProvider, String modId, ExistingFileHelper existingFileHelper) {
        this.output = output;
        this.target = target;
        this.directory = directory;
        this.codec = codec;
        this.lookupProvider = lookupProvider;
        this.modid = modId;
    }

    protected abstract void gather();

    /** After gather, before writing: a subclass that collects its entries its own way gives them here. */
    void collected() {}

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return this.lookupProvider.thenCompose(provider -> {
            this.entries.clear();
            gather();
            collected();
            DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, provider);
            List<CompletableFuture<?>> saved = new ArrayList<>();
            for (var e : this.entries.entrySet()) {
                JsonElement json = this.codec.encodeStart(ops, e.getValue().carrier()).getOrThrow();
                if (!e.getValue().conditions().isEmpty() && json.isJsonObject()) {
                    json.getAsJsonObject().add("neoforge:conditions", ICondition.LIST_CODEC.encodeStart(ops, e.getValue().conditions()).getOrThrow());
                }
                ResourceLocation id = e.getKey();
                saved.add(DataProvider.saveStable(cache, json, this.output.getOutputFolder(this.target).resolve(id.getNamespace()).resolve(this.directory).resolve(id.getPath() + ".json")));
            }
            return CompletableFuture.allOf(saved.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public String getName() {
        return "JsonCodecProvider[" + this.directory + "] for " + this.modid;
    }

    public void unconditional(ResourceLocation id, T value) {
        process(id, new WithConditions<>(value));
    }

    public void conditionally(ResourceLocation id, Consumer<WithConditions.Builder<T>> configurator) {
        WithConditions.Builder<T> builder = new WithConditions.Builder<>();
        configurator.accept(builder);
        process(id, builder.build());
    }

    private void process(ResourceLocation id, WithConditions<T> entry) {
        if (this.entries.put(id, entry) != null) throw new IllegalStateException("Duplicate entry " + id + " in " + getName());
    }
}
