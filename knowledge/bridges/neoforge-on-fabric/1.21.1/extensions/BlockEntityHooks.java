package ${package}.extensions;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The hooks of NeoForge 21.1's IBlockEntityExtension that block entities override, with NeoForge's defaults: an update
 * packet's data (onDataPacket, called by BlockEntityDataMixin where the client loads it), a chunk's block entity data on
 * the client (handleUpdateTag, BlockEntityChunkDataMixin) and onLoad, when the block entity joins a loaded level (Fabric
 * API's block entity load events, hooked in Bridge). Added to BlockEntity by BlockEntityHooksMixin, and for the compile
 * by Loom's interface injection.
 */
public interface BlockEntityHooks {

    private BlockEntity self() {
        return (BlockEntity) (Object) this;
    }

    default void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        CompoundTag tag = pkt.getTag();
        if (!tag.isEmpty()) self().loadWithComponents(tag, lookupProvider);
    }

    default void handleUpdateTag(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        self().loadWithComponents(tag, lookupProvider);
    }

    default void onLoad() {}
}
