package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge's net.neoforged.neoforge.event.level.block.BreakBlockEvent (NeoForge 26.1 and later; 21.1's is
 * BlockEvent.BreakEvent): a player about to break a block, posted by CommonHooks.fireBlockBreak from BlockBreakMixin
 * where NeoForge's patch posts it, first in ServerPlayerGameMode.destroyBlock, in place of the game's own
 * ItemStack.canDestroyBlock check. It starts cancelled when the held item cannot break the block, the player may not
 * edit there, or the block is a game master block; cancelling it keeps the block, and with setNotifyClient(true) the
 * client is sent the block again. The same members as NeoForge's.
 */
public class BreakBlockEvent extends BlockEvent implements ICancellableEvent {

    private final Player player;
    private boolean notifyClient = false;

    public BreakBlockEvent(Level level, BlockPos pos, BlockState state, Player player) {
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

    public boolean shouldNotifyClient() {
        return notifyClient;
    }

    public void setNotifyClient(boolean notify) {
        this.notifyClient = notify;
    }
}
