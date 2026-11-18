package ${package}.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * NeoForge 21.1's global loot modifiers on Fabric. With the data packs, the enabled list
 * (data/neoforge/loot_modifiers/global_loot_modifiers.json in every pack, in pack order, "replace" starting it over) and
 * each entry's file (data/NAMESPACE/loot_modifiers/PATH.json) are read; once the tags are loaded they are decoded with
 * the server's registries (a type from NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS; one that does not decode is
 * left out, as NeoForge logs and skips it). LootModifiersMixin applies them, in the list's order, to each loot table's
 * roll, knowing which table it is from Fabric API's LootTableEvents.ALL_LOADED (for neoforge:loot_table_id). Conditions
 * on a modifier's file ("neoforge:conditions") are not checked. Every converted mod has its own copy of this bridge, so
 * each applies the modifiers of its own IGlobalLootModifier type.
 */
public final class LootModifierManager {

    private static final ResourceLocation LIST = ResourceLocation.fromNamespaceAndPath("neoforge", "loot_modifiers/global_loot_modifiers.json");
    private static final ThreadLocal<Deque<ResourceLocation>> ROLLING = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<LootTable, ResourceLocation> IDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static Map<ResourceLocation, JsonObject> raw = Map.of();
    private static List<IGlobalLootModifier> modifiers = List.of();
    private static Registry<MapCodec<? extends IGlobalLootModifier>> serializers;
    private static boolean started;

    private LootModifierManager() {}

    public static synchronized void start(Registry<MapCodec<? extends IGlobalLootModifier>> registry) {
        if (started) return;
        started = true;
        serializers = registry;
        ResourceLocation addTable = ResourceLocation.fromNamespaceAndPath("neoforge", "add_table");
        if (!registry.containsKey(addTable)) Registry.register(registry, addTable, AddTableLootModifier.CODEC);
        ResourceLocation tableId = ResourceLocation.fromNamespaceAndPath("neoforge", "loot_table_id");
        if (!BuiltInRegistries.LOOT_CONDITION_TYPE.containsKey(tableId)) Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, tableId, LootTableIdCondition.LOOT_TABLE_ID);
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath("${modid}", "neoforge_loot_modifiers");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                raw = read(manager);
            }
        });
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) modifiers = decode(registries, raw);
        });
        LootTableEvents.ALL_LOADED.register((manager, tables) -> {
            IDS.clear();
            for (var entry : tables.entrySet()) IDS.put(entry.getValue(), entry.getKey().location());
        });
    }

    /** The enabled modifiers' files, by id, in the order the lists give them. */
    static Map<ResourceLocation, JsonObject> read(ResourceManager manager) {
        Set<ResourceLocation> entries = new LinkedHashSet<>();
        for (Resource list : manager.getResourceStack(LIST)) {
            JsonObject json = parse(list);
            if (json == null) continue;
            if (json.has("replace") && json.get("replace").getAsBoolean()) entries.clear();
            if (json.has("entries")) for (JsonElement entry : json.getAsJsonArray("entries")) entries.add(ResourceLocation.parse(entry.getAsString()));
        }
        Map<ResourceLocation, JsonObject> out = new LinkedHashMap<>();
        for (ResourceLocation entry : entries) {
            Optional<Resource> file = manager.getResource(entry.withPath(path -> "loot_modifiers/" + path + ".json"));
            if (file.isEmpty()) continue;
            JsonObject json = parse(file.get());
            if (json != null) out.put(entry, json);
        }
        return out;
    }

    private static JsonObject parse(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<IGlobalLootModifier> decode(RegistryAccess registries, Map<ResourceLocation, JsonObject> files) {
        if (serializers == null) return List.of();
        Codec<Object> codec = ((Registry) serializers).byNameCodec().dispatch(LootModifierManager::codecOf, c -> (MapCodec<Object>) c);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        List<IGlobalLootModifier> out = new ArrayList<>();
        for (var file : files.entrySet()) {
            JsonObject json = file.getValue().deepCopy();
            json.remove("neoforge:conditions");
            json.remove("fabric:load_conditions");
            codec.parse(ops, json).result().ifPresent(modifier -> {
                if (modifier instanceof IGlobalLootModifier own) out.add(own);
            });
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static MapCodec<Object> codecOf(Object modifier) {
        try {
            return (MapCodec<Object>) modifier.getClass().getMethod("codec").invoke(modifier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Not a NeoForge loot modifier: " + modifier, e);
        }
    }

    /** The loot after this bridge's modifiers, in order. */
    public static ObjectArrayList<ItemStack> modify(ObjectArrayList<ItemStack> loot, LootContext context) {
        ObjectArrayList<ItemStack> out = loot;
        for (IGlobalLootModifier modifier : modifiers) out = modifier.apply(out, context);
        return out;
    }

    public static void rollStarts(LootTable table) {
        ROLLING.get().push(IDS.getOrDefault(table, LootTableIdCondition.UNKNOWN_LOOT_TABLE));
    }

    public static void rollEnds() {
        ROLLING.get().poll();
    }

    /** The loot table being rolled on this thread (the unknown table outside a roll). */
    public static ResourceLocation rolling() {
        ResourceLocation id = ROLLING.get().peek();
        return id == null ? LootTableIdCondition.UNKNOWN_LOOT_TABLE : id;
    }

    /** For tests: the modifiers in effect, as decoded. */
    static void use(List<IGlobalLootModifier> list) {
        modifiers = List.copyOf(list);
    }
}
