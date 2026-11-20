package ${package}.mixin;

import ${package}.extensions.ItemStackHooks;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Gives ItemStack NeoForge 21.1's crafting remainder methods (the interface's defaults). */
@Mixin(ItemStack.class)
public abstract class ItemStackHooksMixin implements ItemStackHooks {}
