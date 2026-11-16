package ${package}.datamaps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;
import ${package}.NeoForge;
import ${package}.conditions.ICondition;
import ${package}.datamaps.builtin.NeoForgeDataMaps;

/**
 * NeoForge's data maps on Fabric. The types registered with RegisterDataMapTypesEvent (and NeoForge's own,
 * NeoForgeDataMaps) are read from every data pack when the server's data loads, at
 * data/NAMESPACE/data_maps/REGISTRY/PATH.json, and resolved when its tags load, as NeoForge does: the files of a type in
 * pack order, a file with "replace" clearing what came before, each value (or {"value": ..., "replace": true}) under an
 * element id or a #tag (every element of the tag), an entry's neoforge:conditions tested, a later value replacing an
 * earlier one, then "remove" (ids, tags or {"key": ...}). holder.getData(type) reads the result. Difference: Fabric has
 * no data map sync, so a client of a dedicated server has none (one of the same game reads the server's).
 */
public final class DataMaps {

    private DataMaps() {}

    private static final Map<ResourceKey<? extends Registry<?>>, Map<Identifier, DataMapType<?, ?>>> TYPES = new ConcurrentHashMap<>();
    /** Each type's files in pack order, as read at the last data load. */
    private static volatile Map<DataMapType<?, ?>, List<JsonObject>> raw = Map.of();
    private static volatile Map<DataMapType<?, ?>, Map<ResourceKey<?>, Object>> values = Map.of();
    private static boolean started;

    static void register(DataMapType<?, ?> type) {
        if (TYPES.computeIfAbsent(type.registryKey(), k -> new ConcurrentHashMap<>()).putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Data map type " + type.id() + " of " + type.registryKey().identifier() + " is already registered");
        }
    }

    /** NeoForge's own types (NeoForgeDataMaps.register). */
    public static void registerBuiltIn(DataMapType<?, ?> type) {
        register(type);
    }

    /** Called once from Bridge.common: NeoForge's own types, the mod's (the event), and loading with the data packs. */
    public static synchronized void start(Runnable postRegisterEvent) {
        if (started) return;
        started = true;
        NeoForgeDataMaps.register();
        postRegisterEvent.run();
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
            Identifier.fromNamespaceAndPath("${modid}", "neoforge_data_maps"),
            (ResourceManagerReloadListener) DataMaps::read
        );
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) resolve(registries);
            NeoForgeDataMaps.apply(client);
            for (ResourceKey<? extends Registry<?>> key : TYPES.keySet()) {
                registries.lookup(key).ifPresent(registry -> NeoForge.EVENT_BUS.post(
                    new DataMapsUpdatedEvent(registries, registry, client ? DataMapsUpdatedEvent.UpdateCause.CLIENT_SYNC : DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD)
                ));
            }
        });
        NeoForgeDataMaps.wire();
    }

    /** data_maps/REGISTRY: a registry of the game is its path alone, another one NAMESPACE/PATH (as NeoForge and tags). */
    public static String folder(ResourceKey<? extends Registry<?>> registry) {
        Identifier id = registry.identifier();
        return "data_maps/" + (id.getNamespace().equals("minecraft") ? "" : id.getNamespace() + "/") + id.getPath();
    }

    static void read(ResourceManager manager) {
        Map<DataMapType<?, ?>, List<JsonObject>> read = new LinkedHashMap<>();
        for (var registry : TYPES.entrySet()) {
            Map<Identifier, List<Resource>> stacks = FileToIdConverter.json(folder(registry.getKey())).listMatchingResourceStacks(manager);
            for (var stack : stacks.entrySet()) {
                DataMapType<?, ?> type = registry.getValue().get(FileToIdConverter.json(folder(registry.getKey())).fileToId(stack.getKey()));
                if (type == null) continue;
                List<JsonObject> files = new ArrayList<>();
                for (Resource resource : stack.getValue()) {
                    try (Reader reader = resource.openAsReader()) {
                        files.add(JsonParser.parseReader(reader).getAsJsonObject());
                    } catch (Exception e) {
                        throw new IllegalStateException("Data map " + stack.getKey() + " in " + resource.sourcePackId() + " is not a JSON object", e);
                    }
                }
                read.put(type, files);
            }
        }
        raw = read;
    }

    static void resolve(RegistryAccess registries) {
        Map<DataMapType<?, ?>, Map<ResourceKey<?>, Object>> resolved = new LinkedHashMap<>();
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        for (var e : raw.entrySet()) resolved.put(e.getKey(), resolve(registries, ops, e.getKey(), e.getValue()));
        values = resolved;
    }

    private static <R, T> Map<ResourceKey<?>, Object> resolve(RegistryAccess registries, DynamicOps<JsonElement> ops, DataMapType<R, T> type, List<JsonObject> files) {
        Registry<R> registry = registries.lookupOrThrow(type.registryKey());
        Map<ResourceKey<?>, Object> out = new LinkedHashMap<>();
        forEachEntry(type, ops, files, out::clear, (key, value) -> each(registry, key, k -> out.put(k, value)), key -> each(registry, key, out::remove));
        return out;
    }

    /**
     * Each file of a type in order: clear (a file with "replace"), each value's element id or #tag with its decoded
     * value (an entry whose conditions fail is skipped), then each removal's id or #tag.
     */
    public static <R, T> void forEachEntry(DataMapType<R, T> type, DynamicOps<JsonElement> ops, List<JsonObject> files, Runnable clear, BiConsumer<String, T> value, java.util.function.Consumer<String> removal) {
        Codec<T> codec = type.codec();
        for (JsonObject file : files) {
            if (file.has("neoforge:conditions") && !ICondition.LIST_CODEC.parse(ops, file.get("neoforge:conditions")).getOrThrow().stream().allMatch(c -> c.test(ICondition.IContext.EMPTY))) continue;
            if (file.has("replace") && file.get("replace").getAsBoolean()) clear.run();
            JsonObject entries = file.has("values") ? file.getAsJsonObject("values") : new JsonObject();
            for (var entry : entries.entrySet()) {
                JsonElement element = entry.getValue();
                if (element.isJsonObject() && element.getAsJsonObject().has("neoforge:conditions")) {
                    JsonObject copy = element.getAsJsonObject().deepCopy();
                    List<ICondition> conditions = ICondition.LIST_CODEC.parse(ops, copy.remove("neoforge:conditions")).getOrThrow();
                    if (!conditions.stream().allMatch(c -> c.test(ICondition.IContext.EMPTY))) continue;
                    // A value that is not an object is written beside its conditions as neoforge:value.
                    element = copy.has("neoforge:value") ? copy.get("neoforge:value") : copy;
                }
                // {"value": ..., "replace": ...} is an entry; anything else is the value itself.
                JsonElement body = element;
                Optional<T> wrapped = body.isJsonObject() && body.getAsJsonObject().has("value")
                    ? codec.parse(ops, body.getAsJsonObject().get("value")).result()
                    : Optional.empty();
                value.accept(entry.getKey(), wrapped.orElseGet(() -> codec.parse(ops, body).getOrThrow()));
            }
            JsonArray removals = file.has("remove") ? file.getAsJsonArray("remove") : new JsonArray();
            for (JsonElement r : removals) removal.accept(r.isJsonObject() ? r.getAsJsonObject().get("key").getAsString() : r.getAsString());
        }
    }

    /** The files of a type as read at the last data load (for a built-in map applied where the game builds its values). */
    public static List<JsonObject> files(DataMapType<?, ?> type) {
        return raw.getOrDefault(type, List.of());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <R> void each(Registry<R> registry, String key, java.util.function.Consumer<ResourceKey<?>> consumer) {
        if (key.startsWith("#")) {
            TagKey<R> tag = TagKey.create((ResourceKey) registry.key(), Identifier.parse(key.substring(1)));
            for (Holder<R> holder : registry.getTagOrEmpty(tag)) holder.unwrapKey().ifPresent(consumer);
        } else {
            consumer.accept(ResourceKey.create((ResourceKey) registry.key(), Identifier.parse(key)));
        }
    }

    /** A holder's value in a data map; null when it has none, or before the server's data loaded. */
    @SuppressWarnings("unchecked")
    public static <R, T> @Nullable T get(DataMapType<R, T> type, Holder<R> holder) {
        Map<ResourceKey<?>, Object> map = values.get(type);
        if (map == null) return null;
        return holder.unwrapKey().map(key -> (T) map.get(key)).orElse(null);
    }

    /** Every element with a value in a data map, for a built-in map applied to the game's own tables. */
    @SuppressWarnings("unchecked")
    public static <R, T> Map<ResourceKey<R>, T> all(DataMapType<R, T> type) {
        return (Map<ResourceKey<R>, T>) (Map<?, ?>) values.getOrDefault(type, Map.of());
    }
}
