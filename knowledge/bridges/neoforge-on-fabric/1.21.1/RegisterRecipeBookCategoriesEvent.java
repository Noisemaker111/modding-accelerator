package ${package};

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent, posted on the mod bus while the
 * client mod loads. Fabric's client has no recipe book categories of mods' own (their enum constants cannot be added,
 * see EnumProxy), so the registrations are kept here and the game's recipe book shows a mod's recipes in its default tab.
 */
public class RegisterRecipeBookCategoriesEvent extends Event implements IModBusEvent {

    public RegisterRecipeBookCategoriesEvent() {}

    public void registerBookCategories(RecipeBookType type, List<RecipeBookCategories> categories) {}

    public void registerAggregateCategory(RecipeBookCategories category, List<RecipeBookCategories> others) {}

    public void registerRecipeCategoryFinder(RecipeType<?> type, Function<RecipeHolder<?>, RecipeBookCategories> lookup) {}
}
