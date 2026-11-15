package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent, posted by
 * EventHooks.onEntityDestroyBlock and CommonHooks.canEntityDestroy, which mods call; cancelling it stops the entity
 * destroying the block. NeoForge's patches also post it from the game's block-breaking mobs; the bridge does not.
 */
public class LivingDestroyBlockEvent extends LivingEvent implements ICancellableEvent {

    private final BlockPos pos;
    private final BlockState state;

    public LivingDestroyBlockEvent(LivingEntity entity, BlockPos pos, BlockState state) {
        super(entity);
        this.pos = pos;
        this.state = state;
    }

    public BlockState getState() {
        return state;
    }

    public BlockPos getPos() {
        return pos;
    }
}
