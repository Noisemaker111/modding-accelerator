package ${package}.mixin;

import ${package}.extensions.GameHooks;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Gives ItemStack GameHooks.StackDamage (the interface's defaults are the methods). */
@Mixin(ItemStack.class)
public abstract class StackDamageHooksMixin implements GameHooks.StackDamage {}
