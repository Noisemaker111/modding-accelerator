package ${package};

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.player.PlayerInteractEvent, with the same members and result and
 * cancel semantics, posted through CommonHooks where NeoForge's patches post it: RightClickBlock from
 * ServerPlayerGameMode.useItemOn and MultiPlayerGameMode.performUseItemOn (PlayerInteractMixin, ClientPlayerInteractMixin),
 * RightClickItem from ServerPlayerGameMode.useItem and MultiPlayerGameMode.useItem, LeftClickBlock from
 * ServerPlayerGameMode.handleBlockBreakAction (START, STOP, ABORT) and MultiPlayerGameMode.startDestroyBlock (START) and
 * continueDestroyBlock (CLIENT_HOLD), EntityInteract from Player.interactOn (EntityInteractMixin), RightClickEmpty and
 * LeftClickEmpty from Minecraft.startUseItem and startAttack (EmptyClickMixin). Differences: NeoForge's own item hooks
 * at those points (onItemUseFirst, doesSneakBypassUse) have no Fabric counterpart and are not called; the swing that
 * NeoForge's InputEvent.InteractionKeyMappingTriggered may suppress always happens.
 */
public abstract class PlayerInteractEvent extends PlayerEvent {

    private final InteractionHand hand;
    private final BlockPos pos;
    private final Direction face;

    protected PlayerInteractEvent(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        super(Objects.requireNonNull(player, "Null player in PlayerInteractEvent!"));
        this.hand = Objects.requireNonNull(hand, "Null hand in PlayerInteractEvent!");
        this.pos = Objects.requireNonNull(pos, "Null position in PlayerInteractEvent!");
        this.face = face;
    }

    /** A player right clicking an entity; cancelling it skips Entity.interact and returns the cancellation result. */
    public static class EntityInteract extends PlayerInteractEvent implements ICancellableEvent {

        private InteractionResult cancellationResult = InteractionResult.PASS;
        private final Entity target;
        private final Vec3 location;

        public EntityInteract(Player player, InteractionHand hand, Entity target, Vec3 location) {
            super(player, hand, target.blockPosition(), null);
            this.target = target;
            this.location = location;
        }

        public Entity getTarget() {
            return target;
        }

        public Vec3 getLocation() {
            return location;
        }

        public InteractionResult getCancellationResult() {
            return cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    /**
     * A player right clicking a block, on both sides. Cancelling it skips the block and the item and returns the
     * cancellation result; useBlock and useItem allow or deny the block's and the item's use (DEFAULT is vanilla).
     */
    public static class RightClickBlock extends PlayerInteractEvent implements ICancellableEvent {

        private InteractionResult cancellationResult = InteractionResult.PASS;
        private TriState useBlock = TriState.DEFAULT;
        private TriState useItem = TriState.DEFAULT;
        private BlockHitResult hitVec;

        public RightClickBlock(Player player, InteractionHand hand, BlockPos pos, BlockHitResult hitVec) {
            super(player, hand, pos, hitVec.getDirection());
            this.hitVec = hitVec;
        }

        public TriState getUseBlock() {
            return useBlock;
        }

        public TriState getUseItem() {
            return useItem;
        }

        public BlockHitResult getHitVec() {
            return hitVec;
        }

        public void setUseBlock(TriState triggerBlock) {
            this.useBlock = triggerBlock;
        }

        public void setUseItem(TriState triggerItem) {
            this.useItem = triggerItem;
        }

        @Override
        public void setCanceled(boolean canceled) {
            ICancellableEvent.super.setCanceled(canceled);
            if (canceled) {
                useBlock = TriState.FALSE;
                useItem = TriState.FALSE;
            }
        }

        public InteractionResult getCancellationResult() {
            return cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    /** A player right clicking with an item; cancelling it skips Item.use and returns the cancellation result. */
    public static class RightClickItem extends PlayerInteractEvent implements ICancellableEvent {

        private InteractionResult cancellationResult = InteractionResult.PASS;

        public RightClickItem(Player player, InteractionHand hand) {
            super(player, hand, player.blockPosition(), null);
        }

        public InteractionResult getCancellationResult() {
            return cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    /** A player right clicking nothing with an empty hand; client only, not cancellable. */
    public static class RightClickEmpty extends PlayerInteractEvent {

        public RightClickEmpty(Player player, InteractionHand hand) {
            super(player, hand, player.blockPosition(), null);
        }
    }

    /**
     * A player left clicking a block. On the server cancelling it ignores the action packet; useBlock FALSE skips
     * Block.attack and useItem FALSE skips the client's breaking progress.
     */
    public static class LeftClickBlock extends PlayerInteractEvent implements ICancellableEvent {

        private TriState useBlock = TriState.DEFAULT;
        private TriState useItem = TriState.DEFAULT;
        private final Action action;

        public LeftClickBlock(Player player, BlockPos pos, Direction face, Action action) {
            super(player, InteractionHand.MAIN_HAND, pos, face);
            this.action = action;
        }

        public TriState getUseBlock() {
            return useBlock;
        }

        public TriState getUseItem() {
            return useItem;
        }

        public Action getAction() {
            return this.action;
        }

        public void setUseBlock(TriState triggerBlock) {
            this.useBlock = triggerBlock;
        }

        public void setUseItem(TriState triggerItem) {
            this.useItem = triggerItem;
        }

        @Override
        public void setCanceled(boolean canceled) {
            ICancellableEvent.super.setCanceled(canceled);
            if (canceled) {
                useBlock = TriState.FALSE;
                useItem = TriState.FALSE;
            }
        }

        public enum Action {
            START,
            STOP,
            ABORT,
            CLIENT_HOLD;

            public static Action convert(ServerboundPlayerActionPacket.Action action) {
                return switch (action) {
                    case STOP_DESTROY_BLOCK -> STOP;
                    case ABORT_DESTROY_BLOCK -> ABORT;
                    default -> START;
                };
            }
        }
    }

    /** A player left clicking nothing; client only, not cancellable. */
    public static class LeftClickEmpty extends PlayerInteractEvent {

        public LeftClickEmpty(Player player) {
            super(player, InteractionHand.MAIN_HAND, player.blockPosition(), null);
        }
    }

    public InteractionHand getHand() {
        return hand;
    }

    public ItemStack getItemStack() {
        return getEntity().getItemInHand(hand);
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getFace() {
        return face;
    }

    public Level getLevel() {
        return getEntity().level();
    }

    public LogicalSide getSide() {
        return getLevel().isClientSide() ? LogicalSide.CLIENT : LogicalSide.SERVER;
    }
}
