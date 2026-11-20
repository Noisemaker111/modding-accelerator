package ${package}.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ${package}.NeoForgeRegistries;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Global loot modifiers load as NeoForge loads them: the enabled lists in pack order, a list with "replace" starting
 * over, each entry's own file; a file whose type is not registered is left out; and the modifiers change a roll's items
 * in order.
 */
class LootModifierManagerTest {

    static final class AddStick extends LootModifier {
        static final MapCodec<AddStick> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i).apply(i, AddStick::new));

        AddStick(LootItemCondition[] conditions) {
            super(conditions);
        }

        @Override
        protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
            loot.add(new ItemStack(Items.STICK));
            return loot;
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC;
        }
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Registry.register(NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS, ResourceLocation.fromNamespaceAndPath("demo", "add_stick"), AddStick.CODEC);
    }

    static Resource resource(String json) {
        return new Resource(null, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void enabledListsAreReadInPackOrder() {
        Map<String, String> files = Map.of(
            "demo:loot_modifiers/a.json", "{\"type\": \"demo:add_stick\", \"conditions\": []}",
            "demo:loot_modifiers/b.json", "{\"type\": \"demo:add_stick\", \"conditions\": []}",
            "demo:loot_modifiers/c.json", "{\"type\": \"demo:add_stick\", \"conditions\": []}"
        );
        ResourceManager manager = (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ResourceManager.class }, (proxy, method, args) -> switch (method.getName()) {
            case "getResourceStack" -> List.of(resource("{\"entries\": [\"demo:a\"]}"), resource("{\"replace\": true, \"entries\": [\"demo:b\"]}"), resource("{\"entries\": [\"demo:c\", \"demo:b\"]}"));
            case "getResource" -> Optional.ofNullable(files.get(args[0].toString())).map(LootModifierManagerTest::resource);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Map<ResourceLocation, JsonObject> read = LootModifierManager.read(manager);
        assertEquals(List.of(ResourceLocation.parse("demo:b"), ResourceLocation.parse("demo:c")), List.copyOf(read.keySet()));
    }

    @Test
    void decodedModifiersChangeTheRoll() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        List<IGlobalLootModifier> decoded = LootModifierManager.decode(registries, Map.of(
            ResourceLocation.parse("demo:one"), JsonParser.parseString("{\"type\": \"demo:add_stick\", \"conditions\": []}").getAsJsonObject(),
            ResourceLocation.parse("demo:gone"), JsonParser.parseString("{\"type\": \"demo:not_registered\"}").getAsJsonObject()
        ));
        assertEquals(1, decoded.size());
        LootModifierManager.use(List.of(decoded.get(0), decoded.get(0)));
        ObjectArrayList<ItemStack> loot = LootModifierManager.modify(new ObjectArrayList<>(), null);
        assertEquals(2, loot.size());
        assertTrue(loot.stream().allMatch(stack -> stack.is(Items.STICK)));
        LootModifierManager.use(List.of());
    }
}
