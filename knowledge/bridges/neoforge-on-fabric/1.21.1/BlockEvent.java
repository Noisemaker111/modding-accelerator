package ${package};

import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.level.BlockEvent and the block events of it the bridge has: BreakEvent
 * (posted from BlockBreakMixin where NeoForge's patch posts it, before a player breaks a block), EntityPlaceEvent
 * and EntityMultiPlaceEvent (posted from EventHooks.onBlockPlace and onMultiBlockPlace, which mods call and the bridge
 * mixins call where NeoForge's patches do), NeighborNotifyEvent, FluidPlaceBlockEvent and FarmlandTrampleEvent (the
 * same members as NeoForge's; posted only where a bridge mixin or EventHooks method says so).
 */
public abstract class BlockEvent extends Event {

    private final LevelAccessor level;
    private final BlockPos pos;
    private final BlockState state;

    public BlockEvent(LevelAccessor level, BlockPos pos, BlockState state) {
        this.pos = pos;
        this.level = level;
        this.state = state;
    }

    public LevelAccessor getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockState getState() {
        return state;
    }

    /** A player about to break a block (posted before the break); cancelling it keeps the block. */
    public static class BreakEvent extends BlockEvent implements ICancellableEvent {

        private final Player player;

        public BreakEvent(Level level, BlockPos pos, BlockState state, Player player) {
            super(level, pos, state);
            this.player = player;
        }

        public Player getPlayer() {
            return player;
        }

        @Override
        public void setCanceled(boolean canceled) {
            ICancellableEvent.super.setCanceled(canceled);
        }
    }

    /** A block placed by an entity (or by nothing, entity null); cancelling it puts the snapshot back. */
    public static class EntityPlaceEvent extends BlockEvent implements ICancellableEvent {

        private final Entity entity;
        private final BlockSnapshot blockSnapshot;
        private final BlockState placedBlock;
        private final BlockState placedAgainst;

        public EntityPlaceEvent(BlockSnapshot blockSnapshot, BlockState placedAgainst, Entity entity) {
            super(blockSnapshot.getLevel(), blockSnapshot.getPos(), !(entity instanceof Player) ? blockSnapshot.getState() : blockSnapshot.getCurrentState());
            this.entity = entity;
            this.blockSnapshot = blockSnapshot;
            this.placedBlock = !(entity instanceof Player) ? blockSnapshot.getState() : blockSnapshot.getCurrentState();
            this.placedAgainst = placedAgainst;
        }

        public Entity getEntity() {
            return entity;
        }

        public BlockSnapshot getBlockSnapshot() {
            return blockSnapshot;
        }

        public BlockState getPlacedBlock() {
            return placedBlock;
        }

        public BlockState getPlacedAgainst() {
            return placedAgainst;
        }
    }

    /** Several blocks placed at once (a bed, a door); cancelling it puts every snapshot back. */
    public static class EntityMultiPlaceEvent extends EntityPlaceEvent implements ICancellableEvent {

        private final List<BlockSnapshot> blockSnapshots;

        public EntityMultiPlaceEvent(List<BlockSnapshot> blockSnapshots, BlockState placedAgainst, Entity entity) {
            super(blockSnapshots.get(0), placedAgainst, entity);
            this.blockSnapshots = List.copyOf(blockSnapshots);
        }

        public List<BlockSnapshot> getReplacedBlockSnapshots() {
            return blockSnapshots;
        }
    }

    /** A block telling its neighbours it changed. */
    public static class NeighborNotifyEvent extends BlockEvent implements ICancellableEvent {

        private final EnumSet<Direction> notifiedSides;
        private final boolean forceRedstoneUpdate;

        public NeighborNotifyEvent(Level level, BlockPos pos, BlockState state, EnumSet<Direction> notifiedSides, boolean forceRedstoneUpdate) {
            super(level, pos, state);
            this.notifiedSides = notifiedSides;
            this.forceRedstoneUpdate = forceRedstoneUpdate;
        }

        public EnumSet<Direction> getNotifiedSides() {
            return notifiedSides;
        }

        public boolean getForceRedstoneUpdate() {
            return forceRedstoneUpdate;
        }
    }

    /** A fluid placing a block (lava and water making stone, fire from lava). */
    public static class FluidPlaceBlockEvent extends BlockEvent implements ICancellableEvent {

        private final BlockPos liquidPos;
        private BlockState newState;
        private final BlockState origState;

        public FluidPlaceBlockEvent(LevelAccessor level, BlockPos pos, BlockPos liquidPos, BlockState state) {
            super(level, pos, state);
            this.liquidPos = liquidPos;
            this.newState = state;
            this.origState = level.getBlockState(pos);
        }

        public BlockPos getLiquidPos() {
            return liquidPos;
        }

        public BlockState getNewState() {
            return newState;
        }

        public void setNewState(BlockState state) {
            this.newState = state;
        }

        public BlockState getOriginalState() {
            return origState;
        }
    }

    /** An entity trampling farmland. */
    public static class FarmlandTrampleEvent extends BlockEvent implements ICancellableEvent {

        private final Entity entity;
        private final float fallDistance;

        public FarmlandTrampleEvent(Level level, BlockPos pos, BlockState state, float fallDistance, Entity entity) {
            super(level, pos, state);
            this.entity = entity;
            this.fallDistance = fallDistance;
        }

        public Entity getEntity() {
            return entity;
        }

        public float getFallDistance() {
            return fallDistance;
        }
    }
}
