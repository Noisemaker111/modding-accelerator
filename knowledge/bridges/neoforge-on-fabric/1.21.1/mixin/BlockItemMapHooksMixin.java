package ${package}.mixin;

import ${package}.extensions.MiscHooks;
import net.minecraft.world.item.BlockItem;
import org.spongepowered.asm.mixin.Mixin;

/** Gives BlockItem MiscHooks.BlockItemMap (the interface's defaults are the methods). */
@Mixin(BlockItem.class)
public abstract class BlockItemMapHooksMixin implements MiscHooks.BlockItemMap {}
