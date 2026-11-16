package ${package}.mixin;

import ${package}.extensions.IDataComponentMapBuilderExtensions;
import net.minecraft.core.component.DataComponentMap;
import org.spongepowered.asm.mixin.Mixin;

/** Gives DataComponentMap.Builder NeoForge's has and set(Supplier, value) at runtime (the interface's defaults are the methods). */
@Mixin(DataComponentMap.Builder.class)
public abstract class ComponentMapBuilderExtensionsMixin implements IDataComponentMapBuilderExtensions {}
