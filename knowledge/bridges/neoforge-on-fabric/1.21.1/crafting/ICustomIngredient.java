package ${package}.crafting;

import java.util.stream.Stream;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.crafting.ICustomIngredient: an ingredient that tests stacks itself, typed by
 * an IngredientType in NeoForgeRegistries.INGREDIENT_TYPES. On Fabric it is a Fabric API custom ingredient (toVanilla
 * gives the game's Ingredient around it, through FabricIngredients), read from a recipe's {"fabric:type": ...} object,
 * the form mmt convert writes NeoForge's {"type": ...} in.
 */
public interface ICustomIngredient {

    boolean test(ItemStack stack);

    Stream<ItemStack> getItems();

    boolean isSimple();

    IngredientType<?> getType();

    default Ingredient toVanilla() {
        return FabricIngredients.toVanilla(this);
    }
}
