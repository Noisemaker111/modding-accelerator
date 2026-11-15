import com.lothrazar.storagenetwork.api.capabilities.CapabilityConnectableDefault;
import com.lothrazar.storagenetwork.api.capabilities.CapabilityImportExportDefault;
import com.lothrazar.storagenetwork.block.TileCableWithFacing;
import com.lothrazar.storagenetwork.block.TileConnectable;
import com.lothrazar.storagenetwork.block.cable.export.TileCableExport;
import com.lothrazar.storagenetwork.block.cable.link.TileCableLink;
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
// Break attempt (exact gate), live: part of a network unloads and loads again while an export runs. Master in chunk
// (500, 0) (x 8002, z 2), a cable spine along z 2 to x 8230 (chunk 514). Storages (links at z 1, inventories z 0):
// a barrel at x 8045 (chunk 502); a double chest whose halves straddle the chunk border, x 8047 (chunk 502, its link)
// and x 8048 (chunk 503); two barrels at x 8226/8228 (chunk 514). An export cable at x 8004 (needs redstone) pulls
// cobblestone (stack upgrade) into a barrel at z 0. Phase 1: everything forced, export runs until 128 moved. Phase 2:
// only chunk 500 stays forced: 502 is a FULL border chunk, 503 drops below FULL (the chest's other half), 514 unloads;
// the export runs until nothing more arrives for 4 s. Phase 3: everything forced again, the export drains the rest.
// Returns cobblestone and dirt counts per container after each phase; no item may be made or lost (768 cobblestone).
var server = mc.getSingleplayerServer();
int y = -60;
var source = server.createCommandSourceStack().withSuppressedOutput();
java.util.function.Consumer<String> run = command -> server.submit(() -> server.getCommands().performPrefixedCommand(source, command)).join();
java.util.function.Function<Integer, Boolean> full = cx -> server.submit(() -> server.overworld().getChunkSource().hasChunk(cx, 0)).join();
BlockPos master = new BlockPos(8002, y, 2);
BlockPos target = new BlockPos(8004, y, 0);
BlockPos exporter = new BlockPos(8004, y, 1);
BlockPos power = new BlockPos(8004, y + 1, 1);
BlockPos[] storages = {new BlockPos(8045, y, 0), new BlockPos(8047, y, 0), new BlockPos(8048, y, 0), new BlockPos(8226, y, 0), new BlockPos(8228, y, 0)};
String[] names = {"barrel502", "chestA502", "chestB503", "barrel514a", "barrel514b"};
java.util.function.Supplier<String> counts = () -> server.submit(() -> {
    var level = server.overworld();
    StringBuilder s = new StringBuilder();
    java.util.List<BlockPos> all = new java.util.ArrayList<>(java.util.List.of(storages));
    all.add(0, target);
    for (int i = 0; i < all.size(); i++) {
        BlockPos pos = all.get(i);
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, 0)) {
            s.append(i == 0 ? "target" : names[i - 1]).append("=unloaded ");
            continue;
        }
        Container c = (Container) level.getBlockEntity(pos);
        int cobble = 0;
        int dirt = 0;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(Items.COBBLESTONE)) cobble += stack.getCount();
            if (stack.is(Items.DIRT)) dirt += stack.getCount();
        }
        s.append(i == 0 ? "target" : names[i - 1]).append('=').append(cobble).append('/').append(dirt).append(' ');
    }
    return s.toString().trim();
}).join();
java.util.function.Supplier<Integer> moved = () -> server.submit(() -> {
    Container c = (Container) server.overworld().getBlockEntity(target);
    int n = 0;
    for (int slot = 0; slot < 27; slot++) n += c.getItem(slot).getCount();
    return n;
}).join();
java.util.function.Consumer<Boolean> powered = on -> server.submit(() -> server.overworld().setBlockAndUpdate(power, (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState())).join();
Runnable settle = () -> {
    long deadline = System.currentTimeMillis() + 60000;
    int last = -1;
    long since = System.currentTimeMillis();
    while (System.currentTimeMillis() < deadline) {
        int now = moved.get();
        if (now != last) {
            last = now;
            since = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - since > 4000) break;
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
};
StringBuilder out = new StringBuilder();
try {
    run.accept("forceload add 8000 0 8239 15");
    long deadline = System.currentTimeMillis() + 60000;
    while (!(full.apply(500) && full.apply(503) && full.apply(507) && full.apply(514)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
    Thread.sleep(500);
    server.submit(() -> {
        var level = server.overworld();
        for (int x = 8000; x < 8240; x++) for (int z = -1; z < 4; z++) for (int dy = 0; dy < 2; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        Block kabel = SsnRegistry.Blocks.KABEL.get();
        Block link = SsnRegistry.Blocks.STORAGE_KABEL.get();
        level.setBlockAndUpdate(master, SsnRegistry.Blocks.MASTER.get().defaultBlockState());
        for (int x = 8003; x <= 8230; x++) level.setBlockAndUpdate(new BlockPos(x, y, 2), kabel.defaultBlockState());
        level.setBlockAndUpdate(target, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(exporter, SsnRegistry.Blocks.EXPORT_KABEL.get().defaultBlockState());
        level.setBlockAndUpdate(storages[0], Blocks.BARREL.defaultBlockState());
        var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(storages[1], chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        level.setBlockAndUpdate(storages[2], chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        level.setBlockAndUpdate(storages[3], Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(storages[4], Blocks.BARREL.defaultBlockState());
        for (BlockPos s : new BlockPos[] {storages[0], storages[1], storages[3], storages[4]}) level.setBlockAndUpdate(s.relative(Direction.SOUTH), link.defaultBlockState());
        for (int x = 8000; x < 8240; x++) for (int z = 0; z < 4; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockEntity(pos) instanceof TileConnectable) {
                level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
                if (level.getBlockEntity(pos) instanceof TileCableWithFacing cable) cable.refreshInventoryDirection();
            }
        }
        int[] cobbleStacks = {2, 3, 3, 2, 2};
        int[] priorities = {4, 9, 0, 7, 2};
        int[] links = {0, 1, 3, 4};
        for (int i = 0; i < 5; i++) {
            Container c = (Container) level.getBlockEntity(storages[i]);
            for (int slot = 0; slot < cobbleStacks[i]; slot++) c.setItem(slot * 2, new ItemStack(Items.COBBLESTONE, 64));
            c.setItem(20, new ItemStack(Items.DIRT, 10 + i));
        }
        for (int i = 0; i < 4; i++) ((CapabilityConnectableDefault) ((TileCableLink) level.getBlockEntity(storages[links[i]].relative(Direction.SOUTH))).getItemStorage()).setPriority(priorities[i]);
        var export = (CapabilityImportExportDefault) ((TileCableExport) level.getBlockEntity(exporter)).getIoStorage();
        export.getFilter().setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
        export.upgrades.setStackInSlot(0, new ItemStack(SsnRegistry.Items.STACK_UPGRADE.get()));
        ((TileConnectable) level.getBlockEntity(exporter)).getConnectableNode().needsRedstone(true);
    }).join();
    Thread.sleep(2000);
    out.append("start: ").append(counts.get());
    powered.accept(true);
    deadline = System.currentTimeMillis() + 30000;
    while (moved.get() < 128 && System.currentTimeMillis() < deadline) Thread.sleep(50);
    powered.accept(false);
    Thread.sleep(500);
    out.append(" | phase1: ").append(counts.get());
    // 2a: only chunk 500 forced, network whole: does the network itself keep 503 and 514 loaded? (the unmodified mod's
    // per-tick provider scan opens the double chest, whose lookup loads the other half's chunk)
    run.accept("forceload remove 8016 0 8239 15");
    int[] fullPolls = new int[2];
    for (int i = 0; i < 50; i++) {
        Thread.sleep(200);
        if (full.apply(503)) fullPolls[0]++;
        if (full.apply(514)) fullPolls[1]++;
    }
    out.append(" | released, network whole: 503 always FULL=").append(fullPolls[0] == 50).append(" never=").append(fullPolls[0] == 0).append(", 514 always FULL=").append(fullPolls[1] == 50).append(" never=").append(fullPolls[1] == 0);
    // what a player sees then: the network's cobblestone (the terminal's count) and what the export can still pull
    out.append(" networkCobble=").append(server.submit(() -> ((com.lothrazar.storagenetwork.block.main.TileMain) server.overworld().getBlockEntity(master)).getNetwork().getAmount(new com.lothrazar.storagenetwork.api.capabilities.ItemStackMatcherDefault(new ItemStack(Items.COBBLESTONE)))).join());
    powered.accept(true);
    settle.run();
    powered.accept(false);
    out.append(" exportWhileReleased: ").append(counts.get());
    // 2b: cut the spine at x 8046, so the chest and the 514 barrels leave the network; their chunks may now unload
    BlockPos cut = new BlockPos(8046, y, 2);
    server.submit(() -> server.overworld().setBlockAndUpdate(cut, Blocks.AIR.defaultBlockState())).join();
    deadline = System.currentTimeMillis() + 60000;
    while ((full.apply(503) || full.apply(514)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
    Thread.sleep(2000);
    out.append(" | cut: chunks502/503/514 full: ").append(full.apply(502)).append('/').append(full.apply(503)).append('/').append(full.apply(514));
    powered.accept(true);
    settle.run();
    powered.accept(false);
    out.append(" | phase2: ").append(counts.get());
    // 3: load everything again (new block entities from the save) and rejoin the spine
    run.accept("forceload add 8000 0 8239 15");
    deadline = System.currentTimeMillis() + 60000;
    while (!(full.apply(503) && full.apply(507) && full.apply(514)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
    Thread.sleep(1000);
    server.submit(() -> {
        var level = server.overworld();
        level.setBlockAndUpdate(cut, SsnRegistry.Blocks.KABEL.get().defaultBlockState());
        for (BlockPos pos : new BlockPos[] {cut, cut.west(), cut.east()}) level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
    }).join();
    Thread.sleep(2000);
    out.append(" | reloaded: ").append(counts.get());
    powered.accept(true);
    settle.run();
    powered.accept(false);
    out.append(" | phase3: ").append(counts.get());
} finally {
    server.submit(() -> {
        var level = server.overworld();
        for (int x = 8000; x < 8240; x++) for (int z = -1; z < 4; z++) for (int dy = 0; dy < 2; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.getChunkSource().hasChunk(x >> 4, 0)) continue;
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            if (!level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        server.getCommands().performPrefixedCommand(source, "forceload remove 8000 0 8239 15");
    }).join();
}
return out.toString();
