package ${package}.capabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.BlockCapability, as a Fabric API BlockApiLookup: a capability is the
 * lookup of the same name (BlockApiLookup.get(name, typeClass, contextClass)), so registering a provider registers a
 * lookup provider and getCapability is the lookup's find. NeoForge's built-in capabilities (Capabilities) are Fabric's
 * own lookups where Fabric has one (ItemStorage.SIDED, FluidStorage.SIDED), the handler converted to and from the
 * lookup's Storage (transfer.TransferBridge). Providers for one block (or block entity type) are asked in the order they
 * were registered, the first non-null answer winning, as NeoForge does; Fabric keeps one provider per block, so a block
 * given providers both directly (registerBlock) and through its block entity type (registerBlockEntity) keeps the first
 * kind registered. Proxying (setProxyable) is not mirrored.
 */
public final class BlockCapability<T, C> extends BaseCapability<T, C> {

    private static final Map<Identifier, BlockCapability<?, ?>> ALL = new LinkedHashMap<>();

    private final BlockApiLookup<Object, C> lookup;
    private final Function<Object, T> fromLookup;
    private final Function<T, Object> toLookup;
    private final Map<Block, List<IBlockCapabilityProvider<T, C>>> blockProviders = new IdentityHashMap<>();
    private final Map<BlockEntityType<?>, List<ICapabilityProvider<BlockEntity, C, T>>> blockEntityProviders = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    private <A> BlockCapability(Identifier name, Class<T> typeClass, Class<C> contextClass, BlockApiLookup<A, C> lookup, Function<A, T> fromLookup, Function<T, A> toLookup) {
        super(name, typeClass, contextClass);
        this.lookup = (BlockApiLookup<Object, C>) lookup;
        this.fromLookup = (Function<Object, T>) fromLookup;
        this.toLookup = (Function<T, Object>) toLookup;
    }

    public static <T, C> BlockCapability<T, C> create(Identifier name, Class<T> typeClass, Class<C> contextClass) {
        return bridged(name, typeClass, contextClass, BlockApiLookup.get(name, typeClass, contextClass), Function.identity(), Function.identity());
    }

    public static <T> BlockCapability<T, @Nullable Void> createVoid(Identifier name, Class<T> typeClass) {
        return create(name, typeClass, Void.class);
    }

    public static <T> BlockCapability<T, @Nullable Direction> createSided(Identifier name, Class<T> typeClass) {
        return create(name, typeClass, Direction.class);
    }

    /** A capability carried by a lookup of another object type (Capabilities: a handler carried as a Fabric Storage). */
    @SuppressWarnings("unchecked")
    static synchronized <T, C, A> BlockCapability<T, C> bridged(Identifier name, Class<T> typeClass, Class<C> contextClass, BlockApiLookup<A, C> lookup, Function<A, T> fromLookup, Function<T, A> toLookup) {
        BlockCapability<?, ?> existing = ALL.get(name);
        if (existing != null) {
            if (existing.typeClass() != typeClass || existing.contextClass() != contextClass) throw new IllegalStateException("Capability " + name + " already exists with other classes");
            return (BlockCapability<T, C>) existing;
        }
        BlockCapability<T, C> capability = new BlockCapability<>(name, typeClass, contextClass, lookup, fromLookup, toLookup);
        ALL.put(name, capability);
        return capability;
    }

    public static synchronized List<BlockCapability<?, ?>> getAll() {
        return List.copyOf(ALL.values());
    }

    /** The capability at a position, or null (Fabric's BlockApiLookup.find, which reads the state and block entity when they are null). */
    public @Nullable T getCapability(Level level, BlockPos pos, @Nullable BlockState state, @Nullable BlockEntity blockEntity, C context) {
        Object found = lookup.find(level, pos, state, blockEntity, context);
        return found == null ? null : fromLookup.apply(found);
    }

    synchronized void registerBlock(Block block, IBlockCapabilityProvider<T, C> provider) {
        List<IBlockCapabilityProvider<T, C>> providers = blockProviders.get(block);
        if (providers == null) {
            List<IBlockCapabilityProvider<T, C>> list = new ArrayList<>();
            blockProviders.put(block, list);
            lookup.registerForBlocks((level, pos, state, blockEntity, context) -> {
                for (IBlockCapabilityProvider<T, C> each : list) {
                    T found = each.getCapability(level, pos, state, blockEntity, context);
                    if (found != null) return toLookup.apply(found);
                }
                return null;
            }, block);
            providers = list;
        }
        providers.add(provider);
    }

    synchronized void registerBlockEntity(BlockEntityType<?> type, ICapabilityProvider<BlockEntity, C, T> provider) {
        List<ICapabilityProvider<BlockEntity, C, T>> providers = blockEntityProviders.get(type);
        if (providers == null) {
            List<ICapabilityProvider<BlockEntity, C, T>> list = new ArrayList<>();
            blockEntityProviders.put(type, list);
            lookup.registerForBlockEntities((blockEntity, context) -> {
                for (ICapabilityProvider<BlockEntity, C, T> each : list) {
                    T found = each.getCapability(blockEntity, context);
                    if (found != null) return toLookup.apply(found);
                }
                return null;
            }, type);
            providers = list;
        }
        providers.add(provider);
    }

    boolean isRegistered(Block block) {
        return lookup.getProvider(block) != null;
    }

    BlockApiLookup<Object, C> lookup() {
        return lookup;
    }

    T fromLookup(Object found) {
        return fromLookup.apply(found);
    }

    @Override
    public String toString() {
        return "BlockCapability[" + name() + "]";
    }
}
