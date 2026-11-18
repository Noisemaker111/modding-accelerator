package ${package}.extensions;

import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;

/**
 * NeoForge 21.1's Ingredient.isSimple: whether the ingredient matches by item alone (so recipe matching may use the game's
 * item counting), which on Fabric is an ingredient that does not need its stacks tested (FabricIngredient.requiresTesting).
 * Added by IngredientExtensionMixin, and for the compile by Loom's interface injection.
 */
public interface IIngredientExtension {

    default boolean isSimple() {
        return !((FabricIngredient) this).requiresTesting();
    }
}
