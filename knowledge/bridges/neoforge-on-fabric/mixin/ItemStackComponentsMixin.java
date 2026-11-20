package ${package}.mixin;

import ${package}.extensions.IItemStackComponentsExtension;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Gives ItemStack NeoForge's component methods taking a supplied type at runtime (the interface's defaults are the methods). */
@Mixin(ItemStack.class)
public abstract class ItemStackComponentsMixin implements IItemStackComponentsExtension {}
