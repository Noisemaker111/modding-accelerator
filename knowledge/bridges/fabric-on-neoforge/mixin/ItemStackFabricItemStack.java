package ${package}.mixin;

import ${package}.fabric.api.item.v1.FabricItemStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Gives ItemStack Fabric API's FabricItemStack at runtime, as Fabric injects it (the interface's defaults are the methods). */
@Mixin(ItemStack.class)
public abstract class ItemStackFabricItemStack implements FabricItemStack {}
