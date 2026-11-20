package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What Fabric injects into game classes, called as Fabric code calls it (ModDevGradle's interface injection) on the
 * game's own classes, with the bridge's mixins applied by FML: the builder reads its own map, the creator namespace is the
 * registry's, and a stack's recipe remainder (Fabric API 1.21.1's name) is NeoForge's. Items need a server (NeoForge's test one).
 */
@ExtendWith(EphemeralTestServerProvider.class)
class InjectedInterfacesTest {

    @Test
    void builderAnswersFromItsOwnMap() {
        DataComponentMap.Builder builder = DataComponentMap.builder();
        assertFalse(builder.contains(DataComponents.MAX_STACK_SIZE));
        builder.set(DataComponents.MAX_STACK_SIZE, 16);
        assertTrue(builder.contains(DataComponents.MAX_STACK_SIZE));
        assertEquals(16, builder.getOrCreate(DataComponents.MAX_STACK_SIZE, () -> 64));
        // getOrCreate puts the fallback in; set with null takes a value out.
        assertEquals(3, builder.getOrCreate(DataComponents.MAX_DAMAGE, () -> 3));
        assertTrue(builder.contains(DataComponents.MAX_DAMAGE));
        builder.set(DataComponents.MAX_STACK_SIZE, null);
        assertFalse(builder.contains(DataComponents.MAX_STACK_SIZE));
        // getOrEmpty's list is the builder's own: what is added to it is built.
        List<ResourceLocation> recipes = builder.getOrEmpty(DataComponents.RECIPES);
        assertTrue(recipes.isEmpty());
        recipes.add(ResourceLocation.withDefaultNamespace("stone"));
        DataComponentMap built = builder.build();
        assertEquals(1, built.get(DataComponents.RECIPES).size());
        assertEquals(3, built.get(DataComponents.MAX_DAMAGE));
    }

    @Test
    void creatorNamespaceIsTheRegistryNamespace(MinecraftServer server) {
        int others = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            String namespace = BuiltInRegistries.ITEM.getKey(item).getNamespace();
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue;
            assertEquals(namespace, item.getCreatorNamespace(stack), "the creator of " + BuiltInRegistries.ITEM.getKey(item));
            assertEquals(namespace, stack.getCreatorNamespace());
            if (!namespace.equals("minecraft")) others++;
        }
        assertTrue(others > 0, "the host mod's own items are in the registry");
        // An enchanted book with one stored enchantment is that enchantment's.
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        assertEquals("minecraft", book.getCreatorNamespace());
    }

    @Test
    void recipeRemainderIsNeoForges(MinecraftServer server) {
        assertTrue(new ItemStack(Items.WATER_BUCKET).getRecipeRemainder().is(Items.BUCKET));
        assertTrue(Items.WATER_BUCKET.getRecipeRemainder(new ItemStack(Items.WATER_BUCKET)).is(Items.BUCKET));
        assertTrue(new ItemStack(Items.STONE).getRecipeRemainder().isEmpty());
    }
}
