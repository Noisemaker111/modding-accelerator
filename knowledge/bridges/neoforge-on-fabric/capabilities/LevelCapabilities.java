package ${package}.capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's capability methods on Level (its ILevelExtension), for Fabric: convert adds this interface to Level for the
 * mod's compile (Loom interface injection, fabric.mod.json loom:injected_interfaces) and the bridge's LevelCapabilitiesMixin
 * adds it at runtime, so level.getCapability(...) calls compile and run unchanged. invalidateCapabilities is a no-op: Fabric
 * API's lookups and caches have nothing to invalidate.
 */
public interface LevelCapabilities {

    default <T, C> @Nullable T getCapability(BlockCapability<T, C> cap, BlockPos pos, C context) {
        return cap.getCapability((Level) this, pos, null, null, context);
    }

    default <T, C> @Nullable T getCapability(BlockCapability<T, C> cap, BlockPos pos, @Nullable BlockState state, @Nullable BlockEntity blockEntity, C context) {
        return cap.getCapability((Level) this, pos, state, blockEntity, context);
    }

    default <T> @Nullable T getCapability(BlockCapability<T, @Nullable Void> cap, BlockPos pos) {
        return cap.getCapability((Level) this, pos, null, null, null);
    }

    default <T> @Nullable T getCapability(BlockCapability<T, @Nullable Void> cap, BlockPos pos, @Nullable BlockState state, @Nullable BlockEntity blockEntity) {
        return cap.getCapability((Level) this, pos, state, blockEntity, null);
    }

    /** Written by the mixin (one body every converted mod shares). */
    void invalidateCapabilities(BlockPos pos);

    void invalidateCapabilities(ChunkPos pos);
}
