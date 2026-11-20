package ${package}.fluids;

import ${package}.capabilities.Capabilities;
import ${package}.fluids.capability.IFluidHandler;
import ${package}.fluids.capability.IFluidHandlerItem;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 21.1's net.neoforged.neoforge.fluids.FluidUtil: a block's or an item's fluid handler (Capabilities.FluidHandler,
 * so a Fabric mod's tank is found too), what an item holds, and moving fluid between two handlers as NeoForge does
 * (what the source can give and the destination take, simulated first).
 */
public final class FluidUtil {

    private FluidUtil() {}

    public static Optional<IFluidHandlerItem> getFluidHandler(ItemStack itemStack) {
        return Optional.ofNullable(Capabilities.FluidHandler.ITEM.getCapability(itemStack.copyWithCount(1), null));
    }

    public static Optional<FluidStack> getFluidContained(ItemStack container) {
        if (container.isEmpty()) return Optional.empty();
        return getFluidHandler(container).map(handler -> handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE)).filter(stack -> !stack.isEmpty());
    }

    public static Optional<IFluidHandler> getFluidHandler(Level level, BlockPos blockPos, @Nullable Direction side) {
        BlockState state = level.getBlockState(blockPos);
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(blockPos) : null;
        return Optional.ofNullable(Capabilities.FluidHandler.BLOCK.getCapability(level, blockPos, state, blockEntity, side));
    }

    public static FluidStack tryFluidTransfer(IFluidHandler fluidDestination, IFluidHandler fluidSource, int maxAmount, boolean doTransfer) {
        FluidStack drainable = fluidSource.drain(maxAmount, IFluidHandler.FluidAction.SIMULATE);
        if (drainable.isEmpty()) return FluidStack.EMPTY;
        return tryFluidTransfer(fluidDestination, fluidSource, drainable, doTransfer);
    }

    public static FluidStack tryFluidTransfer(IFluidHandler fluidDestination, IFluidHandler fluidSource, FluidStack resource, boolean doTransfer) {
        FluidStack drainable = fluidSource.drain(resource, IFluidHandler.FluidAction.SIMULATE);
        if (drainable.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, drainable)) return FluidStack.EMPTY;
        int fillable = fluidDestination.fill(drainable, IFluidHandler.FluidAction.SIMULATE);
        if (fillable <= 0) return FluidStack.EMPTY;
        FluidStack toMove = drainable.copyWithAmount(fillable);
        if (!doTransfer) return toMove;
        FluidStack drained = fluidSource.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) return FluidStack.EMPTY;
        drained.setAmount(fluidDestination.fill(drained, IFluidHandler.FluidAction.EXECUTE));
        return drained;
    }
}
