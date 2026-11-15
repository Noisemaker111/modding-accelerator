package ${package};

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Not NeoForge's: what NeoForge's Level and Block patches keep in fields of their own (captureBlockSnapshots,
 * capturedBlockSnapshots, restoringBlockSnapshots, Block.capturedDrops), kept here for the bridge mixins, on the server
 * thread. Drops captured inside another drop capture are kept apart and the outer capture resumes after it; block
 * changes are recorded for one placement at a time, as NeoForge's level flag does.
 */
public final class BlockCapture {

    private static Level snapshotLevel;
    private static List<BlockSnapshot> snapshots;
    private static boolean restoring;
    private static List<ItemEntity> drops;

    private BlockCapture() {}

    /** Starts recording the blocks a level is about to change (CommonHooks.placeItemIntoWorld). */
    static void startSnapshots(Level level) {
        snapshotLevel = level;
        snapshots = new ArrayList<>();
    }

    static List<BlockSnapshot> stopSnapshots() {
        List<BlockSnapshot> out = snapshots == null ? List.of() : snapshots;
        snapshotLevel = null;
        snapshots = null;
        return out;
    }

    /** From BlockSnapshotMixin, first in Level.setBlock: the block as it is, before it changes, when recording. */
    public static void beforeSetBlock(Level level, BlockPos pos, int flags) {
        if (snapshots == null || level != snapshotLevel || restoring) return;
        snapshots.add(BlockSnapshot.create(level.dimension(), level, pos.immutable(), flags));
    }

    /** Puts recorded blocks back, last first, without the drops putting them back would make. */
    static void restore(List<BlockSnapshot> recorded) {
        restoring = true;
        try {
            for (int i = recorded.size() - 1; i >= 0; i--) {
                BlockSnapshot snapshot = recorded.get(i);
                snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS);
            }
        } finally {
            restoring = false;
        }
    }

    /** From BlockDropsMixin, first in Block.dropResources: the drops made from here on are kept for BlockDropsEvent. */
    public static List<ItemEntity> startDrops() {
        List<ItemEntity> outer = drops;
        drops = new ArrayList<>();
        return outer;
    }

    /** From BlockDropsMixin: the drops kept since startDrops, the outer capture resumed. */
    public static List<ItemEntity> stopDrops(List<ItemEntity> outer) {
        List<ItemEntity> out = drops == null ? new ArrayList<>() : drops;
        drops = outer;
        return out;
    }

    /** From BlockDropsMixin, in Block.popResource: true when the dropped item was kept (or dropped while restoring). */
    public static boolean keepDrop(ItemEntity entity) {
        if (restoring) return true;
        if (drops == null) return false;
        drops.add(entity);
        return true;
    }
}
