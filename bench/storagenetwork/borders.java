import com.lothrazar.storagenetwork.api.capabilities.CapabilityImportExportDefault;
import com.lothrazar.storagenetwork.block.TileCableWithFacing;
import com.lothrazar.storagenetwork.block.TileConnectable;
import com.lothrazar.storagenetwork.block.cable.export.TileCableExport;
import com.lothrazar.storagenetwork.registry.SsnRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
// Exact gate: which chunks a network keeps loaded beyond its own nodes. The unmodified mod's capability lookups read
// chunks with ServerChunkCache.getChunk(x, z, FULL, true), which renews a one-tick "unknown" ticket on each, so whatever
// a master reads every tick stays loaded. Master in chunk (750, 0), forced; a cable spine along z 2 to x 12095, the
// east edge of chunk 755 (chunks 753-755 are loaded only by the network's own tickets). Two neighbours of the network
// sit in chunks no node is in:
// - a double chest storage: its link and one half at x 12095 (chunk 755), the other half at x 12096 (chunk 756). On
//   every tick an export runs (every 7 ticks: 3 speed upgrades) the unmodified mod looks up every storage's inventory,
//   and a double chest's lookup reads the other half, so chunk 756 is loaded for about 3 ticks in 7 ("some");
// - that export cable, at (12088, 0), asks for diamonds, which the network does not have, from a barrel at
//   (12088, -1) in chunk (755, -1). The unmodified mod reads an export's target only when it moves something, so that
//   chunk stays unloaded ("none").
// Phase A has a furnace storage in the network (the general export path), phase B does not (all-vanilla networks may
// take a fast path). Each phase polls 60 times, 100 ms apart, between server ticks, whether each chunk is loaded
// (FULL) and returns all, none or some. 3 ticks in 7 make "all" or "none" for chunk 756 about 1e-15 likely.
var server = mc.getSingleplayerServer();
int y = -60;
var source = server.createCommandSourceStack().withSuppressedOutput();
java.util.function.Consumer<String> run = command -> server.submit(() -> server.getCommands().performPrefixedCommand(source, command)).join();
java.util.function.BiFunction<Integer, Integer, Boolean> full = (cx, cz) -> server.submit(() -> server.overworld().getChunkSource().hasChunk(cx, cz)).join();
BlockPos master = new BlockPos(12002, y, 2);
BlockPos chestA = new BlockPos(12095, y, 0);
BlockPos chestB = new BlockPos(12096, y, 0);
BlockPos chestLink = new BlockPos(12095, y, 1);
BlockPos branch = new BlockPos(12088, y, 1);
BlockPos exporter = new BlockPos(12088, y, 0);
BlockPos target = new BlockPos(12088, y, -1);
BlockPos furnace = new BlockPos(12010, y, 0);
BlockPos furnaceLink = new BlockPos(12010, y, 1);
int[][] watched = {{756, 0}, {755, -1}, {755, 0}, {753, 0}};
String[] names = {"chestHalf756", "exportTarget755s", "nodes755", "nodes753"};
java.util.function.Supplier<String> poll = () -> {
    int[] seen = new int[watched.length];
    for (int i = 0; i < 60; i++) {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        for (int c = 0; c < watched.length; c++) {
            if (full.apply(watched[c][0], watched[c][1])) seen[c]++;
        }
    }
    StringBuilder s = new StringBuilder();
    for (int c = 0; c < watched.length; c++) {
        s.append(names[c]).append('=').append(seen[c] == 60 ? "all" : seen[c] == 0 ? "none" : "some").append(' ');
    }
    return s.toString().trim();
};
StringBuilder out = new StringBuilder();
try {
    run.accept("forceload add 12000 -16 12111 15");
    long deadline = System.currentTimeMillis() + 60000;
    while (!(full.apply(750, 0) && full.apply(756, 0) && full.apply(755, -1)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
    Thread.sleep(500);
    server.submit(() -> {
        var level = server.overworld();
        for (int x = 12000; x < 12112; x++) for (int z = -2; z < 4; z++) for (int dy = 0; dy < 2; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        Block kabel = SsnRegistry.Blocks.KABEL.get();
        Block link = SsnRegistry.Blocks.STORAGE_KABEL.get();
        level.setBlockAndUpdate(master, SsnRegistry.Blocks.MASTER.get().defaultBlockState());
        for (int x = 12003; x <= 12095; x++) level.setBlockAndUpdate(new BlockPos(x, y, 2), kabel.defaultBlockState());
        var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(chestA, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        level.setBlockAndUpdate(chestB, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        level.setBlockAndUpdate(chestLink, link.defaultBlockState());
        level.setBlockAndUpdate(branch, kabel.defaultBlockState());
        level.setBlockAndUpdate(target, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(exporter, SsnRegistry.Blocks.EXPORT_KABEL.get().defaultBlockState());
        level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(furnaceLink, link.defaultBlockState());
        for (int x = 12000; x < 12112; x++) for (int z = -2; z < 4; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockEntity(pos) instanceof TileConnectable) {
                level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
                if (level.getBlockEntity(pos) instanceof TileCableWithFacing cable) cable.refreshInventoryDirection();
            }
        }
        ((Container) level.getBlockEntity(chestA)).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        ((Container) level.getBlockEntity(chestB)).setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        var export = (CapabilityImportExportDefault) ((TileCableExport) level.getBlockEntity(exporter)).getIoStorage();
        export.getFilter().setStackInSlot(0, new ItemStack(Items.DIAMOND));
        for (int slot = 0; slot < 3; slot++) export.upgrades.setStackInSlot(slot, new ItemStack(SsnRegistry.Items.SPEED_UPGRADE.get()));
    }).join();
    Thread.sleep(2000);
    run.accept("forceload remove 12016 -16 12111 15");
    run.accept("forceload remove 12000 -16 12015 -1");
    Thread.sleep(3000);
    out.append("A (furnace storage): ").append(poll.get());
    server.submit(() -> server.overworld().setBlockAndUpdate(furnaceLink, Blocks.AIR.defaultBlockState())).join();
    Thread.sleep(2000);
    out.append(" | B (vanilla only): ").append(poll.get());
    out.append(" | target holds ").append(server.submit(() -> {
        var level = server.overworld();
        if (!level.getChunkSource().hasChunk(755, -1)) return "unloaded";
        Container c = (Container) level.getBlockEntity(target);
        int n = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) n += c.getItem(slot).getCount();
        return String.valueOf(n);
    }).join());
} finally {
    run.accept("forceload add 12000 -16 12111 15");
    long deadline = System.currentTimeMillis() + 60000;
    while (!(full.apply(756, 0) && full.apply(755, -1)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
    server.submit(() -> {
        var level = server.overworld();
        for (int x = 12000; x < 12112; x++) for (int z = -2; z < 4; z++) for (int dy = 0; dy < 2; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            if (!level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        server.getCommands().performPrefixedCommand(source, "forceload remove 12000 -16 12111 15");
    }).join();
}
return out.toString();
