package ${package}.mixin;

import ${package}.fabric.api.block.v1.FabricBlockState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Gives BlockState Fabric API's FabricBlockState at runtime, as Fabric injects it (the interface's defaults are the methods). */
@Mixin(BlockState.class)
public abstract class BlockStateFabricBlockState implements FabricBlockState {}
