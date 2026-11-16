package ${package}.mixin;

import ${package}.extensions.IRecipeOutputExtension;
import net.minecraft.data.recipes.RecipeOutput;
import org.spongepowered.asm.mixin.Mixin;

/** Gives the game's RecipeOutput NeoForge's withConditions (IRecipeOutputExtension). */
@Mixin(RecipeOutput.class)
public interface RecipeOutputExtensionMixin extends IRecipeOutputExtension {}
