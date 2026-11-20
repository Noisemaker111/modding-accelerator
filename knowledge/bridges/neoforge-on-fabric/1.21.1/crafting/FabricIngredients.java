package ${package}.crafting;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredient;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * NeoForge's custom ingredients as Fabric API's: each IngredientType a mod registers (NeoForgeRegistries.INGREDIENT_TYPES)
 * becomes a Fabric CustomIngredientSerializer of the same id, whose codec reads the NeoForge ingredient and wraps it;
 * the wrapper answers Fabric's test, matching stacks and testing need from the NeoForge ingredient.
 */
public final class FabricIngredients {

    private static Registry<IngredientType<?>> types;

    private FabricIngredients() {}

    public static void start(Registry<IngredientType<?>> registry) {
        types = registry;
        net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback.event(registry).register((rawId, id, type) -> register(id, type));
    }

    static <T extends ICustomIngredient> void register(ResourceLocation id, IngredientType<T> type) {
        if (CustomIngredientSerializer.get(id) != null) return;
        CustomIngredientSerializer.register(new Serializer<>(id, type));
    }

    public static Ingredient toVanilla(ICustomIngredient ingredient) {
        return new Wrapped(ingredient).toVanilla();
    }

    /** A NeoForge custom ingredient as Fabric's. */
    record Wrapped(ICustomIngredient ingredient) implements CustomIngredient {
        @Override
        public boolean test(ItemStack stack) {
            return ingredient.test(stack);
        }

        @Override
        public List<ItemStack> getMatchingStacks() {
            return ingredient.getItems().toList();
        }

        @Override
        public boolean requiresTesting() {
            return !ingredient.isSimple();
        }

        @Override
        public CustomIngredientSerializer<?> getSerializer() {
            ResourceLocation id = types == null ? null : types.getKey(ingredient.getType());
            CustomIngredientSerializer<?> serializer = id == null ? null : CustomIngredientSerializer.get(id);
            if (serializer == null) throw new IllegalStateException("Custom ingredient type not registered: " + ingredient.getType());
            return serializer;
        }
    }

    record Serializer<T extends ICustomIngredient>(ResourceLocation id, IngredientType<T> type) implements CustomIngredientSerializer<Wrapped> {
        @Override
        public ResourceLocation getIdentifier() {
            return id;
        }

        @SuppressWarnings("unchecked")
        @Override
        public MapCodec<Wrapped> getCodec(boolean allowEmpty) {
            return type.codec().xmap(Wrapped::new, wrapped -> (T) wrapped.ingredient());
        }

        @SuppressWarnings("unchecked")
        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Wrapped> getPacketCodec() {
            return ((StreamCodec<RegistryFriendlyByteBuf, T>) type.streamCodec()).map(Wrapped::new, wrapped -> (T) wrapped.ingredient());
        }
    }
}
