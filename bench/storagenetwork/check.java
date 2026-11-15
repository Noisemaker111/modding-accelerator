import com.lothrazar.storagenetwork.block.main.TileMain;
import net.minecraft.core.BlockPos;
// Reports the live base: connectables the master found, items in storage, items in the buffers.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    TileMain main = (TileMain) level.getBlockEntity(new BlockPos(-2, -60, 0));
    long storage = 0;
    long buffers = 0;
    for (int r = 0; r < 8; r++) for (int x = 0; x < 32; x++) {
        var c = (net.minecraft.world.Container) level.getBlockEntity(new BlockPos(x, -60, 3 * r));
        for (int s = 0; s < 27; s++) storage += c.getItem(s).getCount();
    }
    for (int x = 0; x < 16; x++) {
        var c = (net.minecraft.world.Container) level.getBlockEntity(new BlockPos(x, -60, -3));
        for (int s = 0; s < 27; s++) buffers += c.getItem(s).getCount();
    }
    return "connectables=" + main.getNetwork().getConnectableSize() + " storage=" + storage + " buffers=" + buffers + " time=" + level.getGameTime();
}).join();
