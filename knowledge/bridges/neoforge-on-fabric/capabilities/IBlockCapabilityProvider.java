package ${package}.capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** NeoForge's net.neoforged.neoforge.capabilities.IBlockCapabilityProvider: the capability of a block in the world, or null. */
@FunctionalInterface
public interface IBlockCapabilityProvider<T, C> {

    T getCapability(Level level, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, C context);
}
