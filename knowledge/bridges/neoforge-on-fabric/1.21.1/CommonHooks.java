package ${package};

import java.util.List;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.CommonHooks: the static methods NeoForge's patches call to post its events
 * and apply their results, which mods may call too. Each one here posts the same event as NeoForge's and returns the same
 * result; the bridge mixins call them from the points NeoForge's patches do.
 */
public final class CommonHooks {

    private CommonHooks() {}

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

    /** Posts EntityInteractSpecific for a point on an entity; the cancellation result when it was cancelled, otherwise null. */
    public static InteractionResult onInteractEntityAt(Player player, Entity entity, HitResult ray, InteractionHand hand) {
        Vec3 vec3d = ray.getLocation().subtract(entity.position());
        return onInteractEntityAt(player, entity, vec3d, hand);
    }

    public static InteractionResult onInteractEntityAt(Player player, Entity entity, Vec3 vec3d, InteractionHand hand) {
        PlayerInteractEvent.EntityInteractSpecific event = Bus.INSTANCE.post(new PlayerInteractEvent.EntityInteractSpecific(player, hand, entity, vec3d));
        return event.isCanceled() ? event.getCancellationResult() : null;
    }

    /** Posts EntityInteract; the cancellation result when it was cancelled, otherwise null. */
    public static InteractionResult onInteractEntity(Player player, Entity entity, InteractionHand hand) {
        PlayerInteractEvent.EntityInteract event = Bus.INSTANCE.post(new PlayerInteractEvent.EntityInteract(player, hand, entity));
        return event.isCanceled() ? event.getCancellationResult() : null;
    }

    public static void onEmptyClick(Player player, InteractionHand hand) {
        Bus.INSTANCE.post(new PlayerInteractEvent.RightClickEmpty(player, hand));
    }

    public static void onEmptyLeftClick(Player player) {
        Bus.INSTANCE.post(new PlayerInteractEvent.LeftClickEmpty(player));
    }

    /**
     * Posts BlockEvent.BreakEvent, cancelled beforehand when the block would not break anyway (the held item cannot break
     * it, the player may not edit there, a game master block); when it is cancelled the client is sent the block back.
     */
    public static BlockEvent.BreakEvent fireBlockBreak(Level level, GameType gameType, ServerPlayer player, BlockPos pos, BlockState state) {
        boolean preCancelEvent = false;
        ItemStack itemstack = player.getMainHandItem();
        if (!itemstack.isEmpty() && !itemstack.getItem().canAttackBlock(state, level, pos, player)) {
            preCancelEvent = true;
        }
        if (player.blockActionRestricted(level, pos, gameType)) {
            preCancelEvent = true;
        }
        if (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            preCancelEvent = true;
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, player);
        event.setCanceled(preCancelEvent);
        Bus.INSTANCE.post(event);
        if (event.isCanceled()) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
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
            return InteractionResult.FAIL;
        }
        itemstack.setCount(newSize);
        itemstack.applyComponents(newComponents);
        return ret;
    }
}
