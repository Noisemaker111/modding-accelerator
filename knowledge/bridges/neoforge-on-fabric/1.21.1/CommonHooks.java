package ${package};

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.CommonHooks: static methods NeoForge's patches call and mods call directly,
 * each with NeoForge's behaviour. captureDrops stands in for the two captureDrops methods NeoForge's Entity patch adds
 * (taking the entity first): while an entity's drops are captured, the bridge's drop mixins put the item entities its
 * death drops into the collection instead of the level, as NeoForge's patches do.
 */
public final class CommonHooks {

    static final Map<Entity, Collection<ItemEntity>> CAPTURED_DROPS = new ConcurrentHashMap<>();

    private CommonHooks() {}

    /** Posts LivingDropsEvent; true when it was cancelled (the drops are then not added to the level). */
    public static boolean onLivingDrops(LivingEntity entity, DamageSource source, Collection<ItemEntity> drops, boolean recentlyHit) {
        return Bus.INSTANCE.post(new LivingDropsEvent(entity, source, drops, recentlyHit)).isCanceled();
    }

    /**
     * Whether a living entity may destroy the block at a position: the block is loaded, the entity may grief
     * (EventHooks.canEntityGrief), the block lets it (IBlockStateExtension.canEntityDestroy) and LivingDestroyBlockEvent
     * is not cancelled.
     */
    public static boolean canEntityDestroy(Level level, BlockPos pos, LivingEntity entity) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return EventHooks.canEntityGrief(level, entity) && IBlockStateExtension.canEntityDestroy(state, level, pos, entity) && EventHooks.onEntityDestroyBlock(entity, pos, state);
    }

    /** The collection an entity's drops are being captured into, or null when they are not (NeoForge's Entity.captureDrops()). */
    public static Collection<ItemEntity> captureDrops(Entity entity) {
        return CAPTURED_DROPS.get(entity);
    }

    /** Starts (a collection) or ends (null) capturing an entity's drops; returns the collection captured until now (NeoForge's Entity.captureDrops(value)). */
    public static Collection<ItemEntity> captureDrops(Entity entity, Collection<ItemEntity> value) {
        return value == null ? CAPTURED_DROPS.remove(entity) : CAPTURED_DROPS.put(entity, value);
    }
}
