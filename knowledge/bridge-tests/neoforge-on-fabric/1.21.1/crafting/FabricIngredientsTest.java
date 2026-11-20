package ${package}.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ${package}.NeoForgeRegistries;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A NeoForge custom ingredient type a mod registers is a Fabric API custom ingredient of the same id: the game's
 * ingredient codec reads it from a recipe's {"fabric:type": ...} object (the form mmt convert writes), and the
 * ingredient tests stacks as the NeoForge one does.
 */
class FabricIngredientsTest {

    record SticksOnly() implements ICustomIngredient {
        static final MapCodec<SticksOnly> CODEC = MapCodec.unit(new SticksOnly());
        static IngredientType<SticksOnly> type;

        @Override
        public boolean test(ItemStack stack) {
            return stack.is(Items.STICK);
        }

        @Override
        public Stream<ItemStack> getItems() {
            return Stream.of(new ItemStack(Items.STICK));
        }

        @Override
        public boolean isSimple() {
            return false;
        }

        @Override
        public IngredientType<?> getType() {
            return type;
        }
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SticksOnly.type = new IngredientType<>(SticksOnly.CODEC);
        Registry.register(NeoForgeRegistries.INGREDIENT_TYPES, ResourceLocation.fromNamespaceAndPath("demo", "sticks_only"), SticksOnly.type);
    }

    @Test
    void theGamesCodecReadsTheModsIngredient() {
        assertNotNull(CustomIngredientSerializer.get(ResourceLocation.fromNamespaceAndPath("demo", "sticks_only")));
        Ingredient ingredient = Ingredient.CODEC_NONEMPTY.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"fabric:type\": \"demo:sticks_only\"}")).getOrThrow();
        assertTrue(ingredient.test(new ItemStack(Items.STICK)));
        assertFalse(ingredient.test(new ItemStack(Items.APPLE)));
    }
}
