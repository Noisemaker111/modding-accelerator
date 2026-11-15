package ${package}.capabilities;

import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.BlockCapabilityCache, as Fabric API's BlockApiCache of the capability's
 * lookup: getCapability() answers from the cache, which Fabric keeps valid as block entities come and go. Fabric's cache
 * has no invalidation listener, so the isValid and invalidationListener given to create are not called: code that
 * re-queries on invalidation keeps working because every getCapability() is current.
 */
public final class BlockCapabilityCache<T, C> {

    private final BlockCapability<T, C> capability;
    private final BlockApiCache<Object, C> cache;
    private final C context;

    private BlockCapabilityCache(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context) {
        this.capability = capability;
        this.cache = BlockApiCache.create(capability.lookup(), level, pos.immutable());
        this.context = context;
    }

    public static <T, C> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context) {
        return new BlockCapabilityCache<>(capability, level, pos, context);
    }

    public static <T, C> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context, BooleanSupplier isValid, Runnable invalidationListener) {
        return new BlockCapabilityCache<>(capability, level, pos, context);
    }

    public ServerLevel level() {
        return cache.getLevel();
    }

    public BlockPos pos() {
        return cache.getPos();
    }

    public C context() {
        return context;
    }

    public @Nullable T getCapability() {
        Object found = cache.find(context);
        return found == null ? null : capability.fromLookup(found);
    }
}
