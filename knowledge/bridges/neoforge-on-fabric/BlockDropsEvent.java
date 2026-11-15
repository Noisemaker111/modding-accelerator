package ${package};

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge's net.neoforged.neoforge.event.level.BlockDropsEvent: the item entities a broken block drops, posted by
 * CommonHooks.handleBlockDrops from BlockDropsMixin where NeoForge's patch posts it, in each Block.dropResources after
 * the drops were made and before they enter the level. Listeners may change the list; cancelling it drops nothing and
 * skips BlockState.spawnAfterBreak. Difference: NeoForge starts getDroppedExperience at the block's own experience
 * (its IBlockExtension.getExpDrop), which Fabric has no counterpart for; here it starts at 0, the game's own experience
 * drop still comes from spawnAfterBreak, and an amount a listener sets is dropped in addition.
 */
public class BlockDropsEvent extends BlockEvent implements ICancellableEvent {

    private final BlockEntity blockEntity;
    private final List<ItemEntity> drops;
    private final Entity breaker;
    private final ItemStack tool;
    private int experience;

    public BlockDropsEvent(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity, List<ItemEntity> drops, Entity breaker, ItemStack tool) {
        super(level, pos, state);
        this.blockEntity = blockEntity;
        this.drops = drops;
        this.breaker = breaker;
        this.tool = tool;
        this.experience = 0;
    }

    public List<ItemEntity> getDrops() {
        return this.drops;
    }

    public BlockEntity getBlockEntity() {
        return blockEntity;
    }

    public Entity getBreaker() {
        return this.breaker;
    }

    public ItemStack getTool() {
        return this.tool;
    }

    @Override
    public void setCanceled(boolean canceled) {
        ICancellableEvent.super.setCanceled(canceled);
    }

    @Override
    public ServerLevel getLevel() {
        return (ServerLevel) super.getLevel();
    }

    public int getDroppedExperience() {
        return experience;
    }

    public void setDroppedExperience(int experience) {
        if (experience < 0) throw new IllegalArgumentException("May not set a negative experience drop.");
        this.experience = experience;
    }
}
