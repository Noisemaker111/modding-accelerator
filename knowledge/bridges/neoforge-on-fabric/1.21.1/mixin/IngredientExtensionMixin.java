package ${package}.mixin;

import ${package}.extensions.IIngredientExtension;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Ingredient NeoForge 21.1's isSimple (the interface's default is the method). */
@Mixin(Ingredient.class)
public abstract class IngredientExtensionMixin implements IIngredientExtension {}
