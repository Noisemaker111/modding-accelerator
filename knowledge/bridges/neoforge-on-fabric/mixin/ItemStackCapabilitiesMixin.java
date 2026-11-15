package ${package}.mixin;

import ${package}.capabilities.ItemStackCapabilities;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Gives ItemStack the bridge's ItemStackCapabilities at runtime (NeoForge's stack.getCapability). */
@Mixin(ItemStack.class)
public abstract class ItemStackCapabilitiesMixin implements ItemStackCapabilities {}
