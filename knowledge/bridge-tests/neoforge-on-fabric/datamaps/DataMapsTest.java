package ${package}.datamaps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.PackOutput;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ${package}.conditions.NeoForgeConditions;
import ${package}.data.DataMapProvider;

/**
 * Data maps load as NeoForge loads them: files in pack order, a file's replace clearing what came before, an entry's
 * {"value", "replace"} form, an entry whose conditions fail skipped, removals last; and a file DataMapProvider writes
 * loads back as the values it was given.
 */
class DataMapsTest {

    static final DataMapType<Item, Integer> WEIGHTS = DataMapType.builder(Identifier.fromNamespaceAndPath("demo", "weights"), Registries.ITEM, Codec.INT).build();

    @TempDir
    Path out;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static Map<String, Integer> load(List<JsonObject> files) {
        Map<String, Integer> out = new LinkedHashMap<>();
        DataMaps.forEachEntry(WEIGHTS, JsonOps.INSTANCE, files, out::clear, out::put, out::remove);
        return out;
    }

    @Test
    void filesLoadInPackOrderWithNeoForgesRules() {
        Map<String, Integer> loaded = load(List.of(
            json("{\"values\":{\"demo:a\":1,\"demo:b\":2,\"#demo:heavy\":3}}"),
            json("{\"values\":{\"demo:a\":{\"value\":10,\"replace\":true},\"demo:c\":{\"neoforge:conditions\":[{\"type\":\"neoforge:false\"}],\"neoforge:value\":4}},\"remove\":[\"demo:b\"]}"),
            json("{\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"no_such_mod\"}],\"replace\":true,\"values\":{\"demo:z\":9}}")
        ));
        assertEquals(Map.of("demo:a", 10, "#demo:heavy", 3), loaded);
        assertEquals(Map.of("demo:z", 9), load(List.of(json("{\"values\":{\"demo:a\":1}}"), json("{\"replace\":true,\"values\":{\"demo:z\":9}}"))));
    }

    @Test
    void whatTheProviderWritesLoadsBack() throws Exception {
        HolderLookup.Provider lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        new DataMapProvider(new PackOutput(this.out), CompletableFuture.completedFuture(lookup)) {
            @Override
            protected void gather(HolderLookup.Provider provider) {
                builder(WEIGHTS)
                    .add(Identifier.fromNamespaceAndPath("demo", "a"), 5, false)
                    .add(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("demo", "heavy")), 7, true)
                    .add(Identifier.fromNamespaceAndPath("demo", "never"), 1, false, NeoForgeConditions.never())
                    .remove(Identifier.fromNamespaceAndPath("demo", "b"));
            }
        }.run(CachedOutput.NO_CACHE).join();
        JsonObject written = json(Files.readString(this.out.resolve("data/demo/data_maps/item/weights.json")));
        assertEquals(Map.of("demo:a", 5, "#demo:heavy", 7), load(List.of(written)));
    }
}
