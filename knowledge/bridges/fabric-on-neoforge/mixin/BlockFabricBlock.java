package ${package}.mixin;

import ${package}.fabric.api.block.v1.FabricBlock;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Block Fabric API's FabricBlock at runtime, as Fabric injects it (the interface's defaults are the methods). */
@Mixin(Block.class)
public abstract class BlockFabricBlock implements FabricBlock {}
