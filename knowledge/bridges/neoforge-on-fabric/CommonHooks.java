package ${package};

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge's net.neoforged.neoforge.common.CommonHooks: static methods NeoForge's patches call and mods call directly,
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
    public static boolean canEntityDestroy(ServerLevel level, BlockPos pos, LivingEntity entity) {
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

    // ---- player interaction, block and lifecycle hooks ----

    /** Posts LeftClickBlock for a block action packet (server) or the client's start of breaking. */
    public static PlayerInteractEvent.LeftClickBlock onLeftClickBlock(Player player, BlockPos pos, Direction face, ServerboundPlayerActionPacket.Action action) {
        return Bus.INSTANCE.post(new PlayerInteractEvent.LeftClickBlock(player, pos, face, PlayerInteractEvent.LeftClickBlock.Action.convert(action)));
    }

    /** Posts LeftClickBlock with CLIENT_HOLD, each tick the client keeps breaking a block. */
    public static PlayerInteractEvent.LeftClickBlock onClientMineHold(Player player, BlockPos pos, Direction face) {
        return Bus.INSTANCE.post(new PlayerInteractEvent.LeftClickBlock(player, pos, face, PlayerInteractEvent.LeftClickBlock.Action.CLIENT_HOLD));
    }

    /** Posts RightClickBlock before a player uses a block or an item on it. */
    public static PlayerInteractEvent.RightClickBlock onRightClickBlock(Player player, InteractionHand hand, BlockPos pos, BlockHitResult hitVec) {
        return Bus.INSTANCE.post(new PlayerInteractEvent.RightClickBlock(player, hand, pos, hitVec));
    }

    /** Posts RightClickItem; the cancellation result when it was cancelled, otherwise null. */
    public static InteractionResult onItemRightClick(Player player, InteractionHand hand) {
        PlayerInteractEvent.RightClickItem event = Bus.INSTANCE.post(new PlayerInteractEvent.RightClickItem(player, hand));
        return event.isCanceled() ? event.getCancellationResult() : null;
    }

    /** Posts EntityInteract; the cancellation result when it was cancelled, otherwise null. */
    public static InteractionResult onInteractEntity(Player player, Entity entity, InteractionHand hand, Vec3 location) {
        PlayerInteractEvent.EntityInteract event = Bus.INSTANCE.post(new PlayerInteractEvent.EntityInteract(player, hand, entity, location));
        return event.isCanceled() ? event.getCancellationResult() : null;
    }

    public static void onEmptyClick(Player player, InteractionHand hand) {
        Bus.INSTANCE.post(new PlayerInteractEvent.RightClickEmpty(player, hand));
    }

    public static void onEmptyLeftClick(Player player) {
        Bus.INSTANCE.post(new PlayerInteractEvent.LeftClickEmpty(player));
    }

    /**
     * Posts BreakBlockEvent, cancelled beforehand when the block would not break anyway (the held item cannot break it,
     * the player may not edit there, a game master block); a cancelled event with notifyClient sends the block back.
     */
    public static BreakBlockEvent fireBlockBreak(Level level, GameType gameType, Player player, BlockPos pos, BlockState state) {
        boolean preCancelEvent = false;
        ItemStack itemstack = player.getMainHandItem();
        if (!itemstack.isEmpty() && !itemstack.canDestroyBlock(state, level, pos, player)) {
            preCancelEvent = true;
        }
        if (player.blockActionRestricted(level, pos, gameType)) {
            preCancelEvent = true;
        }
        if (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            preCancelEvent = true;
        }
        BreakBlockEvent event = new BreakBlockEvent(level, pos, state, player);
        event.setCanceled(preCancelEvent);
        Bus.INSTANCE.post(event);
        if (event.isCanceled() && event.shouldNotifyClient() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        }
        return event;
    }

    /**
     * Posts BlockDropsEvent for a broken block's drops; unless it was cancelled, the drops it leaves enter the level,
     * BlockState.spawnAfterBreak runs (with the game's own experience) and the experience it sets is dropped.
     */
    public static void handleBlockDrops(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity, List<ItemEntity> drops, Entity breaker, ItemStack tool) {
        BlockDropsEvent event = Bus.INSTANCE.post(new BlockDropsEvent(level, pos, state, blockEntity, drops, breaker, tool));
        if (event.isCanceled()) return;
        for (ItemEntity entity : event.getDrops()) {
            level.addFreshEntity(entity);
        }
        state.spawnAfterBreak(level, pos, tool, true);
        if (event.getDroppedExperience() > 0) {
            ExperienceOrb.award(level, Vec3.atCenterOf(pos), event.getDroppedExperience());
        }
    }

    /**
     * An item used on a block on the server, its block changes captured: they are posted as EntityPlaceEvent (one block)
     * or EntityMultiPlaceEvent, and put back, with the item as it was, when the event is cancelled.
     */
    public static InteractionResult onPlaceItemIntoWorld(UseOnContext context) {
        InteractionResult result = placeItemIntoWorld(context, c -> c.getItemInHand().getItem().useOn(c));
        Player player = context.getPlayer();
        if (player != null && result.consumesAction()) {
            player.awardStat(Stats.ITEM_USED.get(context.getItemInHand().getItem()));
        }
        return result;
    }

    /**
     * Not NeoForge's: onPlaceItemIntoWorld around the given use of the item (PlaceBlockMixin passes the game's
     * Item.useOn call), without the stat, which the game's ItemStack.useOn awards.
     */
    public static InteractionResult placeItemIntoWorld(UseOnContext context, Function<UseOnContext, InteractionResult> use) {
        ItemStack itemstack = context.getItemInHand();
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || itemstack.getItem() instanceof BucketItem) return use.apply(context);
        int size = itemstack.getCount();
        DataComponentMap components = itemstack.getComponents();
        List<BlockSnapshot> blockSnapshots;
        InteractionResult ret;
        BlockCapture.startSnapshots(level);
        try {
            ret = use.apply(context);
        } finally {
            blockSnapshots = BlockCapture.stopSnapshots();
        }
        if (!ret.consumesAction() || blockSnapshots.isEmpty()) return ret;
        int newSize = itemstack.getCount();
        DataComponentMap newComponents = itemstack.getComponents();
        itemstack.setCount(size);
        itemstack.applyComponents(components);
        Direction side = context.getClickedFace();
        boolean cancelled = blockSnapshots.size() > 1 ? EventHooks.onMultiBlockPlace(player, blockSnapshots, side) : EventHooks.onBlockPlace(player, blockSnapshots.get(0), side);
        if (cancelled) {
            BlockCapture.restore(blockSnapshots);
            if (player instanceof ServerPlayer serverPlayer) {
                int slot = context.getHand() == InteractionHand.MAIN_HAND ? serverPlayer.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
                serverPlayer.connection.send(serverPlayer.getInventory().createInventoryUpdatePacket(slot));
            }
            return InteractionResult.FAIL;
        }
        itemstack.setCount(newSize);
        itemstack.applyComponents(newComponents);
        return ret;
    }
}
