package ${package}.extensions;

import net.minecraft.network.Connection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;

/**
 * The hooks of NeoForge's IBlockEntityExtension that block entities override to sync their data to the client: an
 * update packet's data (onDataPacket, called by BlockEntityDataMixin where the client loads it), a chunk's block entity
 * data on the client (handleUpdateTag, BlockEntityChunkDataMixin), and onLoad, when the block entity joins a loaded
 * level (Fabric API's block entity load events, hooked in Bridge and ClientBridge). Each default does what the game
 * does there without NeoForge: load the data. Added to BlockEntity by BlockEntityHooksMixin, and for the compile by
 * Loom's interface injection.
 */
public interface BlockEntityHooks {

    private BlockEntity self() {
        return (BlockEntity) (Object) this;
    }

    default void onDataPacket(Connection connection, ValueInput input) {
        self().loadWithComponents(input);
    }

    default void handleUpdateTag(ValueInput input) {
        self().loadWithComponents(input);
    }

    default void onLoad() {
    }
}
