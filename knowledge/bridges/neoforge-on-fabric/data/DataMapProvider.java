package ${package}.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import ${package}.conditions.ICondition;
import ${package}.datamaps.DataMapType;
import ${package}.datamaps.DataMaps;

/**
 * NeoForge's net.neoforged.neoforge.common.data.DataMapProvider: data/NAMESPACE/data_maps/REGISTRY/PATH.json for each
 * data map the mod fills in gather, in NeoForge's form (DataMaps reads it on Fabric): "values" by element id or #tag,
 * an entry written {"value": ..., "replace": true} when it replaces, an entry's or the file's neoforge:conditions,
 * "remove" and "replace" when used.
 */
public abstract class DataMapProvider implements DataProvider {

    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> lookupProvider;
    private final Map<DataMapType<?, ?>, Builder<?, ?>> builders = new LinkedHashMap<>();

    protected DataMapProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        this.output = output;
        this.lookupProvider = lookupProvider;
    }

    protected abstract void gather(HolderLookup.Provider provider);

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return this.lookupProvider.thenCompose(provider -> {
            this.builders.clear();
            gather(provider);
            DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, provider);
            List<CompletableFuture<?>> saved = new ArrayList<>();
            for (var e : this.builders.entrySet()) {
                DataMapType<?, ?> type = e.getKey();
                saved.add(DataProvider.saveStable(
                    cache,
                    e.getValue().json(ops),
                    this.output.getOutputFolder(PackOutput.Target.DATA_PACK).resolve(type.id().getNamespace())
                        .resolve(DataMaps.folder(type.registryKey())).resolve(type.id().getPath() + ".json")
                ));
            }
            return CompletableFuture.allOf(saved.toArray(CompletableFuture[]::new));
        });
    }

    @SuppressWarnings("unchecked")
    public <T, R> Builder<T, R> builder(DataMapType<R, T> type) {
        return (Builder<T, R>) this.builders.computeIfAbsent(type, t -> new Builder<>(type));
    }

    @Override
    public String getName() {
        return "Data Maps";
    }

    public static class Builder<T, R> {

        private final DataMapType<R, T> type;
        private final Map<String, Entry<T>> values = new LinkedHashMap<>();
        private final List<String> removals = new ArrayList<>();
        private final List<ICondition> conditions = new ArrayList<>();
        private boolean replace;

        record Entry<T>(T value, boolean replace, ICondition[] conditions) {}

        public Builder(DataMapType<R, T> type) {
            this.type = type;
        }

        public Builder<T, R> add(ResourceKey<R> key, T value, boolean replace, ICondition... conditions) {
            return add(key.identifier(), value, replace, conditions);
        }

        public Builder<T, R> add(Identifier id, T value, boolean replace, ICondition... conditions) {
            this.values.put(id.toString(), new Entry<>(value, replace, conditions));
            return this;
        }

        public Builder<T, R> add(Holder<R> holder, T value, boolean replace, ICondition... conditions) {
            return add(holder.unwrapKey().orElseThrow(() -> new IllegalArgumentException("A data map entry needs a registered holder")), value, replace, conditions);
        }

        public Builder<T, R> add(TagKey<R> tag, T value, boolean replace, ICondition... conditions) {
            this.values.put("#" + tag.location(), new Entry<>(value, replace, conditions));
            return this;
        }

        public Builder<T, R> remove(Identifier id) {
            this.removals.add(id.toString());
            return this;
        }

        public Builder<T, R> remove(TagKey<R> tag) {
            this.removals.add("#" + tag.location());
            return this;
        }

        public Builder<T, R> remove(Holder<R> holder) {
            return remove(holder.unwrapKey().orElseThrow().identifier());
        }

        public Builder<T, R> replace(boolean replace) {
            this.replace = replace;
            return this;
        }

        public Builder<T, R> conditions(ICondition... conditions) {
            this.conditions.addAll(List.of(conditions));
            return this;
        }

        JsonObject json(DynamicOps<JsonElement> ops) {
            JsonObject file = new JsonObject();
            if (!this.conditions.isEmpty()) file.add("neoforge:conditions", ICondition.LIST_CODEC.encodeStart(ops, this.conditions).getOrThrow());
            if (this.replace) file.addProperty("replace", true);
            JsonObject values = new JsonObject();
            for (var e : this.values.entrySet()) {
                Entry<T> entry = e.getValue();
                JsonElement value = this.type.codec().encodeStart(ops, entry.value()).getOrThrow();
                JsonElement written = value;
                if (entry.replace()) {
                    JsonObject wrapped = new JsonObject();
                    wrapped.add("value", value);
                    wrapped.addProperty("replace", true);
                    written = wrapped;
                }
                if (entry.conditions().length > 0) {
                    JsonObject conditional = written.isJsonObject() ? written.getAsJsonObject().deepCopy() : new JsonObject();
                    if (!written.isJsonObject()) conditional.add("neoforge:value", written);
                    conditional.add("neoforge:conditions", ICondition.LIST_CODEC.encodeStart(ops, List.of(entry.conditions())).getOrThrow());
                    written = conditional;
                }
                values.add(e.getKey(), written);
            }
            file.add("values", values);
            if (!this.removals.isEmpty()) {
                JsonArray removed = new JsonArray();
                this.removals.forEach(removed::add);
                file.add("remove", removed);
            }
            return file;
        }
    }
}
