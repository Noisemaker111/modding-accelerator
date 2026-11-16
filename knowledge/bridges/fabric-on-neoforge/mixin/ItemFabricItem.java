package ${package}.mixin;

import ${package}.fabric.api.item.v1.FabricItem;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Item Fabric API's FabricItem at runtime, as Fabric injects it (the interface's defaults are the methods). */
@Mixin(Item.class)
public abstract class ItemFabricItem implements FabricItem {}
