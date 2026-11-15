package ${package}.fabric.api.lookup.v1.block;

import ${package}.TransferBridge;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup, on NeoForge's block capabilities: a lookup is a
 * BlockCapability (Fabric's item, fluid and Team Reborn energy lookups are NeoForge's own Capabilities.Item, Fluid and
 * Energy BLOCK), find is NeoForge's getCapability, and providers are registered with NeoForge in RegisterCapabilitiesEvent
 * on the mod's bus, fallback providers after all others for every block. Registering after that event has no effect on
 * NeoForge (a warning is logged). getProvider answers for providers registered with registerForBlocks only.
 */
public interface BlockApiLookup<A, C> {

    static <A, C> BlockApiLookup<A, C> get(Identifier lookupId, Class<A> apiClass, Class<C> contextClass) {
        return TransferBridge.lookup(lookupId, apiClass, contextClass);
    }

    default @Nullable A find(Level level, BlockPos pos, C context) {
        return find(level, pos, null, null, context);
    }

    @Nullable A find(Level level, BlockPos pos, @Nullable BlockState state, @Nullable BlockEntity blockEntity, C context);

    void registerSelf(BlockEntityType<?>... blockEntityTypes);

    void registerForBlocks(BlockApiProvider<A, C> provider, Block... blocks);

    @SuppressWarnings("unchecked")
    default <T extends BlockEntity> void registerForBlockEntity(BiFunction<? super T, C, @Nullable A> provider, BlockEntityType<T> blockEntityType) {
        registerForBlockEntities((blockEntity, context) -> provider.apply((T) blockEntity, context), blockEntityType);
    }

    void registerForBlockEntities(BlockEntityApiProvider<A, C> provider, BlockEntityType<?>... blockEntityTypes);

    void registerFallback(BlockApiProvider<A, C> fallbackProvider);

    Identifier getId();

    Class<A> apiClass();

    Class<C> contextClass();

    @Nullable BlockApiProvider<A, C> getProvider(Block block);

    @FunctionalInterface
    interface BlockApiProvider<A, C> {
        @Nullable A find(Level level, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, C context);
    }

    @FunctionalInterface
    interface BlockEntityApiProvider<A, C> {
        @Nullable A find(BlockEntity blockEntity, C context);
    }
}
