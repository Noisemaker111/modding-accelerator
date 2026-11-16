package ${package}.mixin;

import ${package}.extensions.GameHooks;
import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;

/** Gives FoodProperties.Builder GameHooks.FoodBuilder (the interface's defaults are the methods). */
@Mixin(FoodProperties.Builder.class)
public abstract class FoodBuilderHooksMixin implements GameHooks.FoodBuilder {}
