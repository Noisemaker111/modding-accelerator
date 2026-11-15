package ${package};

import com.mojang.logging.LogUtils;
import java.lang.ref.WeakReference;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;

/**
 * NeoForge's net.neoforged.neoforge.common.util.BlockSnapshot: a block's state, block entity data and position at one
 * moment, which can be put back. The same members and behaviour as NeoForge's.
 */
public class BlockSnapshot {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ResourceKey<Level> dim;
    private final BlockPos pos;
    private final int flags;
    private final BlockState state;
    private final CompoundTag nbt;
    private WeakReference<LevelAccessor> level;
    private String toString = null;

    private BlockSnapshot(ResourceKey<Level> dim, LevelAccessor level, BlockPos pos, BlockState state, CompoundTag nbt, int flags) {
        this.dim = dim;
        this.pos = pos.immutable();
        this.state = state;
        this.flags = flags;
        this.nbt = nbt;
        this.level = new WeakReference<>(level);
    }

    public static BlockSnapshot create(ResourceKey<Level> dim, LevelAccessor level, BlockPos pos, int flag) {
        return new BlockSnapshot(dim, level, pos, level.getBlockState(pos), getBlockEntityTag(level, pos), flag);
    }

    public static BlockSnapshot create(ResourceKey<Level> dim, LevelAccessor level, BlockPos pos) {
        return create(dim, level, pos, 3);
    }

    public ResourceKey<Level> getDimension() {
        return this.dim;
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getFlags() {
        return flags;
    }

    public CompoundTag getTag() {
        return nbt;
    }

    public BlockState getState() {
        return this.state;
    }

    public LevelAccessor getLevel() {
        LevelAccessor current = this.level.get();
        if (current == null) {
            current = ServerLifecycleHooks.getCurrentServer().getLevel(this.dim);
            this.level = new WeakReference<>(current);
        }
        return current;
    }

    public BlockState getCurrentState() {
        LevelAccessor current = this.getLevel();
        return current == null ? Blocks.AIR.defaultBlockState() : current.getBlockState(this.pos);
    }

    public BlockEntity recreateBlockEntity(HolderLookup.Provider provider) {
        return getTag() != null ? BlockEntity.loadStatic(getPos(), getState(), getTag(), provider) : null;
    }

    public boolean restoreToLocation(LevelAccessor level, BlockPos pos, int flags) {
        BlockState replaced = getState();
        if (!level.setBlock(pos, replaced, flags)) {
            return false;
        }
        if (level instanceof Level realLevel) {
            BlockState current = getCurrentState();
            realLevel.sendBlockUpdated(pos, current, replaced, flags);
        }
        restoreBlockEntity(level, pos);
        return true;
    }

    public boolean restore(int flags) {
        return restoreToLocation(getLevel(), getPos(), flags);
    }

    public boolean restore() {
        return restore(this.getFlags());
    }

    public boolean restoreBlockEntity(LevelAccessor level, BlockPos pos) {
        if (getTag() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(be.problemPath(), LOGGER)) {
                    be.loadWithComponents(TagValueInput.create(problems, level.registryAccess(), getTag()));
                    be.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BlockSnapshot other = (BlockSnapshot) obj;
        return this.dim.equals(other.dim) && this.pos.equals(other.pos) && this.state == other.state && this.flags == other.flags && Objects.equals(this.nbt, other.nbt);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 73 * hash + this.dim.hashCode();
        hash = 73 * hash + this.pos.hashCode();
        hash = 73 * hash + this.state.hashCode();
        hash = 73 * hash + this.flags;
        hash = 73 * hash + Objects.hashCode(this.getTag());
        return hash;
    }

    @Override
    public String toString() {
        if (toString == null) {
            this.toString = "BlockSnapshot[Level:" + this.dim.identifier() + ",Pos: " + this.pos + ",State: " + this.state + ",Flags: " + this.flags + ",NBT: " + (this.nbt == null ? "null" : this.nbt.toString()) + ']';
        }
        return this.toString;
    }

    private static CompoundTag getBlockEntityTag(LevelAccessor level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithFullMetadata(level.registryAccess());
    }
}
