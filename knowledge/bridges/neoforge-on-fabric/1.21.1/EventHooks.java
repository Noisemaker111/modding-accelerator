package ${package};

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.EventHooks: the static methods NeoForge's patches call to post its events,
 * and that mods call directly. Each one here posts the same event as NeoForge's and returns the same result; the bridge
 * mixins call them from the points NeoForge's patches do. Where the game's own code calls one of these on NeoForge and
 * no bridge mixin does here (finalizeMobSpawn from the game's spawners, canEntityGrief from the game's mob griefing
 * checks, canLivingConvert from the game's conversions), its event is posted only when a mod calls the method.
 */
public final class EventHooks {

    private EventHooks() {}

    /** Posts EntityTickEvent.Pre; from the entity tick mixins, before the entity ticks. */
    public static EntityTickEvent.Pre fireEntityTickPre(Entity entity) {
        return Bus.INSTANCE.post(new EntityTickEvent.Pre(entity));
    }

    /** Posts EntityTickEvent.Post; from the entity tick mixins, after the entity ticked. */
    public static void fireEntityTickPost(Entity entity) {
        Bus.INSTANCE.post(new EntityTickEvent.Post(entity));
    }

    /** Posts PlayerTickEvent.Pre; from PlayerTickMixin, first in Player.tick(). */
    public static void firePlayerTickPre(Player player) {
        Bus.INSTANCE.post(new PlayerTickEvent.Pre(player));
    }

    /** Posts PlayerTickEvent.Post; from PlayerTickMixin, last in Player.tick(). */
    public static void firePlayerTickPost(Player player) {
        Bus.INSTANCE.post(new PlayerTickEvent.Post(player));
    }

    /** The bridge's own hook for the entity join mixins: posts EntityJoinLevelEvent; true when it was cancelled. */
    public static boolean fireEntityJoinLevel(Entity entity, Level level, boolean loadedFromDisk) {
        return Bus.INSTANCE.post(new EntityJoinLevelEvent(entity, level, loadedFromDisk)).isCanceled();
    }

    /** Posts BlockEvent.EntityPlaceEvent for the snapshot's block, placed against its neighbour opposite direction; true when cancelled. */
    public static boolean onBlockPlace(Entity entity, BlockSnapshot blockSnapshot, Direction direction) {
        BlockState placedAgainst = blockSnapshot.getLevel().getBlockState(blockSnapshot.getPos().relative(direction.getOpposite()));
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(blockSnapshot, placedAgainst, entity);
        return Bus.INSTANCE.post(event).isCanceled();
    }

    /** Posts BlockEvent.EntityMultiPlaceEvent for the snapshots, placed against the first one's neighbour; true when cancelled. */
    public static boolean onMultiBlockPlace(Entity entity, List<BlockSnapshot> blockSnapshots, Direction direction) {
        BlockSnapshot snap = blockSnapshots.get(0);
        BlockState placedAgainst = snap.getLevel().getBlockState(snap.getPos().relative(direction.getOpposite()));
        BlockEvent.EntityMultiPlaceEvent event = new BlockEvent.EntityMultiPlaceEvent(blockSnapshots, placedAgainst, entity);
        return Bus.INSTANCE.post(event).isCanceled();
    }

    /** Posts LivingDestroyBlockEvent; true when the entity may destroy the block (the event was not cancelled). */
    public static boolean onEntityDestroyBlock(LivingEntity entity, BlockPos pos, BlockState state) {
        return !Bus.INSTANCE.post(new LivingDestroyBlockEvent(entity, pos, state)).isCanceled();
    }

    /** Whether an entity may grief: the mobGriefing rule when there is no entity, else EntityMobGriefingEvent's answer. */
    public static boolean canEntityGrief(Level level, Entity entity) {
        if (entity == null) return level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        return Bus.INSTANCE.post(new EntityMobGriefingEvent(level, entity)).canGrief();
    }

    /** Posts MobSpawnEvent.SpawnPlacementCheck; the placement's result given the game's own (defaultResult). */
    public static boolean checkSpawnPlacements(EntityType<?> entityType, ServerLevelAccessor level, MobSpawnType spawnType, BlockPos pos, RandomSource random, boolean defaultResult) {
        MobSpawnEvent.SpawnPlacementCheck event = new MobSpawnEvent.SpawnPlacementCheck(entityType, level, spawnType, pos, random, defaultResult);
        return Bus.INSTANCE.post(event).getPlacementCheckResult();
    }

    /** Posts MobSpawnEvent.PositionCheck; by default the mob's own spawn rules and obstruction check decide. */
    public static boolean checkSpawnPosition(Mob mob, ServerLevelAccessor level, MobSpawnType spawnType) {
        MobSpawnEvent.PositionCheck event = new MobSpawnEvent.PositionCheck(mob, level, spawnType, null);
        Bus.INSTANCE.post(event);
        if (event.getResult() == MobSpawnEvent.PositionCheck.Result.DEFAULT) {
            return mob.checkSpawnRules(level, spawnType) && mob.checkSpawnObstruction(level);
        }
        return event.getResult() == MobSpawnEvent.PositionCheck.Result.SUCCEED;
    }

    /**
     * The bridge's own hook for MobSpawnPositionMixin and SpawnUtilPositionMixin, which keep the game's two checks in
     * place: posts MobSpawnEvent.PositionCheck and returns its result (DEFAULT when the level is not a server's).
     */
    public static MobSpawnEvent.PositionCheck.Result firePositionCheck(Mob mob, LevelAccessor level, MobSpawnType spawnType) {
        if (!(level instanceof ServerLevelAccessor serverLevel)) return MobSpawnEvent.PositionCheck.Result.DEFAULT;
        MobSpawnEvent.PositionCheck event = new MobSpawnEvent.PositionCheck(mob, serverLevel, spawnType, null);
        return Bus.INSTANCE.post(event).getResult();
    }

    /** Posts FinalizeSpawnEvent, then finalizes the mob's spawn with the event's values unless it was cancelled (then null). */
    public static SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, SpawnGroupData spawnData) {
        FinalizeSpawnEvent event = new FinalizeSpawnEvent(mob, level, mob.getX(), mob.getY(), mob.getZ(), difficulty, spawnType, spawnData, null);
        Bus.INSTANCE.post(event);
        if (!event.isCanceled()) {
            return mob.finalizeSpawn(level, event.getDifficulty(), event.getSpawnType(), event.getSpawnData());
        }
        return null;
    }

    /** Posts LivingConversionEvent.Pre; true when the conversion may go ahead (the event was not cancelled). */
    public static boolean canLivingConvert(LivingEntity entity, EntityType<? extends LivingEntity> outcome, Consumer<Integer> timer) {
        return !Bus.INSTANCE.post(new LivingConversionEvent.Pre(entity, outcome, timer)).isCanceled();
    }

    /** Posts LivingConversionEvent.Post once an entity has become another. */
    public static void onLivingConvert(LivingEntity entity, LivingEntity outcome) {
        Bus.INSTANCE.post(new LivingConversionEvent.Post(entity, outcome));
    }

    // ---- player interaction, block and lifecycle hooks ----

    /** Posts LivingEntityUseItemEvent.Start; the use duration it leaves, or -1 when it was cancelled. */
    public static int onItemUseStart(LivingEntity entity, ItemStack item, InteractionHand hand, int duration) {
        LivingEntityUseItemEvent.Start event = new LivingEntityUseItemEvent.Start(entity, item, hand, duration);
        return Bus.INSTANCE.post(event).isCanceled() ? -1 : event.getDuration();
    }

    /** Posts LivingEntityUseItemEvent.Tick; the duration left it leaves, or -1 when it was cancelled. */
    public static int onItemUseTick(LivingEntity entity, ItemStack item, int duration) {
        LivingEntityUseItemEvent.Tick event = new LivingEntityUseItemEvent.Tick(entity, item, duration);
        return Bus.INSTANCE.post(event).isCanceled() ? -1 : event.getDuration();
    }

    /** Posts LivingEntityUseItemEvent.Stop; true when it was cancelled (the item is not released). */
    public static boolean onUseItemStop(LivingEntity entity, ItemStack item, int duration) {
        return Bus.INSTANCE.post(new LivingEntityUseItemEvent.Stop(entity, item, duration)).isCanceled();
    }

    /** Posts LivingEntityUseItemEvent.Finish; the result stack it leaves. */
    public static ItemStack onItemUseFinish(LivingEntity entity, ItemStack item, int duration, ItemStack result) {
        LivingEntityUseItemEvent.Finish event = new LivingEntityUseItemEvent.Finish(entity, item, duration, result);
        Bus.INSTANCE.post(event);
        return event.getResultStack();
    }

    /** Posts PlayerEvent.HarvestCheck with the player's own answer (hasCorrectToolForDrops) as the default; the event's answer. */
    public static boolean doPlayerHarvestCheck(Player player, BlockState state, BlockGetter level, BlockPos pos) {
        boolean vanillaValue = player.hasCorrectToolForDrops(state);
        return Bus.INSTANCE.post(new PlayerEvent.HarvestCheck(player, state, level, pos, vanillaValue)).canHarvest();
    }

    /**
     * NeoForge's onEffectRemoved (MobEffectEvent.Remove, which listeners may cancel): the bridge posts no MobEffectEvent,
     * so the removal is never cancelled.
     */
    public static boolean onEffectRemoved(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.effect.MobEffectInstance effect, EffectCure cure) {
        return false;
    }
}
