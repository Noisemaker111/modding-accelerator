package ${package}.mixin;

import ${package}.extensions.GameHooks;
import net.minecraft.core.component.DataComponentMap;
import org.spongepowered.asm.mixin.Mixin;

/** Gives DataComponentMap.Builder GameHooks.ComponentMapBuilder (the interface's defaults are the methods). */
@Mixin(DataComponentMap.Builder.class)
public abstract class ComponentMapBuilderHooksMixin implements GameHooks.ComponentMapBuilder {}
