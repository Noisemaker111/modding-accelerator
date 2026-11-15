import com.lothrazar.storagenetwork.api.capabilities.CapabilityConnectableDefault;
import com.lothrazar.storagenetwork.api.capabilities.CapabilityImportExportDefault;
import com.lothrazar.storagenetwork.api.capabilities.ItemStackMatcherDefault;
import com.lothrazar.storagenetwork.block.TileCableWithFacing;
import com.lothrazar.storagenetwork.block.TileConnectable;
import com.lothrazar.storagenetwork.block.cable.export.TileCableExport;
import com.lothrazar.storagenetwork.block.cable.input.TileCableIO;
import com.lothrazar.storagenetwork.block.cable.link.TileCableLink;
import com.lothrazar.storagenetwork.block.main.TileMain;
import com.lothrazar.storagenetwork.registry.SsnRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
// Exact gate: a frozen storage network far from the player (x 2002..2013, chunk 125, which never ticks),
// whose master is ticked by hand at fixed game times 700000..700129, so every launch computes the same
// transfers. The mod orders storages and requests by priority and breaks ties by identity hashes, which
// differ between JVMs, so every storage and export cable here has its own priority, every export cable
// asks for its own item into its own buffer, and the two import cables never run in the same round.
// Storage: row A, 10 barrels (z 0) with link cables (z 1): one with a deny filter (stone), one with an
// allow filter (dirt), one import-only ("way" IN), one that needs redstone (powered at round 50).
// Row B (z 3, links z 4): a double chest with a link on each half (deduplicated), a furnace (sided) and
// a barrel. I/O (z -3 buffers, z -2 exports, z -4 imports): cobblestone (stack, 3 speed), dirt (plain),
// iron (stock 20, 2 speed), oak log (operation: while the network holds more than 40, stack, 1 speed), and
// cobblestone again (stack, 3 speed, a later priority), so two requests for one item are served in priority order;
// import 0 (stack, 3 speed) on buffer 0, import 1 (allow sand and gravel) on buffer 1. Every 10 rounds a
// player-style insert (37 sand) and request (20 dirt) go through the master. At round 33 a link cable is
// broken, at 66 put back; at 80 a storage's priority changes; at 95 a barrel becomes a chest, which at 103 becomes
// half of a double chest (same block entity, new state); at 110 import 0 is broken. After each round
// every container, the network's size, comparator, empty slots, sorted contents and a cobblestone count
// go into a SHA-256; the gate returns the hash and the item total. It runs twice: at x 2004 as described, and at
// x 2036 (chunk 127) with a barrel in place of the furnace, so that every storage and export target is a plain vanilla
// barrel or chest, the inventories for which an export may decide its providers as it reaches them.
var server = mc.getSingleplayerServer();
java.util.function.BiFunction<Integer, Boolean, String> network = (ox, plain) -> server.submit(() -> {
    var level = server.overworld();
    var registries = level.registryAccess();
    int y = -60;
    level.getChunk(ox >> 4, -1);
    level.getChunk(ox >> 4, 0);
    java.util.function.Function<String, Item> item = id -> BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
    java.util.function.BiFunction<Integer, Integer, BlockPos> at = (x, z) -> new BlockPos(ox + x, y, z);
    for (int x = -2; x < 11; x++) for (int z = -5; z <= 6; z++) for (int dy = 0; dy < 2; dy++) {
        level.setBlockAndUpdate(new BlockPos(ox + x, y + dy, z), Blocks.AIR.defaultBlockState());
    }
    Block kabel = SsnRegistry.Blocks.KABEL.get();
    Block link = SsnRegistry.Blocks.STORAGE_KABEL.get();
    level.setBlockAndUpdate(at.apply(-2, 0), SsnRegistry.Blocks.MASTER.get().defaultBlockState());
    for (int z = -4; z <= 4; z++) level.setBlockAndUpdate(at.apply(-1, z), kabel.defaultBlockState());
    for (int x = 0; x < 10; x++) {
        level.setBlockAndUpdate(at.apply(x, 0), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(at.apply(x, 1), link.defaultBlockState());
    }
    var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
    level.setBlockAndUpdate(at.apply(0, 3), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
    level.setBlockAndUpdate(at.apply(1, 3), chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
    level.setBlockAndUpdate(at.apply(3, 3), (plain ? Blocks.BARREL : Blocks.FURNACE).defaultBlockState());
    level.setBlockAndUpdate(at.apply(5, 3), Blocks.BARREL.defaultBlockState());
    for (int x = 0; x < 6; x++) level.setBlockAndUpdate(at.apply(x, 4), (x == 2 || x == 4 ? kabel : link).defaultBlockState());
    for (int x = 0; x < 5; x++) {
        level.setBlockAndUpdate(at.apply(x, -3), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(at.apply(x, -2), SsnRegistry.Blocks.EXPORT_KABEL.get().defaultBlockState());
        if (x < 2) level.setBlockAndUpdate(at.apply(x, -4), SsnRegistry.Blocks.IMPORT_KABEL.get().defaultBlockState());
    }
    for (int x = -1; x < 10; x++) for (int z = -4; z <= 4; z++) {
        BlockPos pos = at.apply(x, z);
        if (level.getBlockEntity(pos) instanceof TileConnectable) {
            level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
            if (level.getBlockEntity(pos) instanceof TileCableWithFacing cable) cable.refreshInventoryDirection();
        }
    }
    String[] kinds = {"stone", "cobblestone", "dirt", "oak_log", "iron_ingot", "sand", "gravel", "coal", "string", "bone", "wheat", "glass"};
    java.util.function.BiConsumer<BlockPos, Integer> fill = (pos, seed) -> {
        Container c = (Container) level.getBlockEntity(pos);
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if ((slot * 5 + seed) % 3 == 0) continue;
            int count = (slot + seed) % 4 == 0 ? 1 + (slot * 13 + seed * 7) % 63 : 64;
            c.setItem(slot, new ItemStack(item.apply(kinds[(slot * 7 + seed * 3) % kinds.length]), count));
        }
    };
    for (int x = 0; x < 10; x++) fill.accept(at.apply(x, 0), x);
    fill.accept(at.apply(0, 3), 11);
    fill.accept(at.apply(1, 3), 12);
    fill.accept(at.apply(5, 3), 13);
    Container furnace = (Container) level.getBlockEntity(at.apply(3, 3));
    furnace.setItem(0, new ItemStack(item.apply("iron_ingot"), 9));
    furnace.setItem(2, new ItemStack(item.apply("coal"), 5));
    Container buffer1 = (Container) level.getBlockEntity(at.apply(1, -3));
    buffer1.setItem(0, new ItemStack(item.apply("sand"), 40));
    buffer1.setItem(1, new ItemStack(item.apply("stone"), 64));
    buffer1.setItem(2, new ItemStack(item.apply("gravel"), 64));
    java.util.function.Function<BlockPos, CapabilityConnectableDefault> storageAt = pos -> (CapabilityConnectableDefault) ((TileCableLink) level.getBlockEntity(pos)).getItemStorage();
    int[] priorities = {3, 7, 1, 9, 0, 5, 8, 2, 6, 4};
    for (int x = 0; x < 10; x++) storageAt.apply(at.apply(x, 1)).setPriority(priorities[x]);
    storageAt.apply(at.apply(3, 1)).getFilter().setStackInSlot(0, new ItemStack(item.apply("stone")));
    storageAt.apply(at.apply(5, 1)).getFilter().setIsAllowlist(true);
    storageAt.apply(at.apply(5, 1)).getFilter().setStackInSlot(0, new ItemStack(item.apply("dirt")));
    var inOnly = storageAt.apply(at.apply(6, 1));
    var wayTag = inOnly.serializeNBT(registries);
    wayTag.putString("way", "IN");
    inOnly.deserializeNBT(registries, wayTag);
    ((TileConnectable) level.getBlockEntity(at.apply(8, 1))).getConnectableNode().needsRedstone(true);
    int[] rowB = {0, 1, 3, 5};
    int[] rowBPriority = {12, 11, 14, 13};
    for (int i = 0; i < 4; i++) storageAt.apply(at.apply(rowB[i], 4)).setPriority(rowBPriority[i]);
    java.util.function.Function<Integer, CapabilityImportExportDefault> exportAt = x -> (CapabilityImportExportDefault) ((TileCableExport) level.getBlockEntity(at.apply(x, -2))).getIoStorage();
    java.util.function.Function<Integer, CapabilityImportExportDefault> importAt = x -> (CapabilityImportExportDefault) ((TileCableIO) level.getBlockEntity(at.apply(x, -4))).getIoStorage();
    java.util.function.BiConsumer<CapabilityImportExportDefault, String[]> upgrade = (io, names) -> {
        for (int s = 0; s < names.length; s++) {
            Item u = switch (names[s]) {
                case "stack" -> SsnRegistry.Items.STACK_UPGRADE.get();
                case "speed" -> SsnRegistry.Items.SPEED_UPGRADE.get();
                case "stock" -> SsnRegistry.Items.STOCK_UPGRADE.get();
                default -> SsnRegistry.Items.OP_U.get();
            };
            io.upgrades.setStackInSlot(s, new ItemStack(u));
        }
    };
    String[] exportItems = {"cobblestone", "dirt", "iron_ingot", "oak_log", "cobblestone"};
    for (int x = 0; x < 5; x++) {
        exportAt.apply(x).setPriority(20 + x);
        exportAt.apply(x).getFilter().setStackInSlot(0, new ItemStack(item.apply(exportItems[x]), x == 2 ? 20 : 1));
    }
    upgrade.accept(exportAt.apply(0), new String[] {"stack", "speed", "speed", "speed"});
    upgrade.accept(exportAt.apply(4), new String[] {"stack", "speed", "speed", "speed"});
    upgrade.accept(exportAt.apply(2), new String[] {"stock", "speed", "speed"});
    upgrade.accept(exportAt.apply(3), new String[] {"operation", "stack", "speed"});
    exportAt.apply(3).operationStack = new ItemStack(item.apply("oak_log"));
    exportAt.apply(3).operationLimit = 40;
    exportAt.apply(3).operationType = com.lothrazar.library.data.OpCompareType.GREATER.ordinal();
    upgrade.accept(importAt.apply(0), new String[] {"stack", "speed", "speed", "speed"});
    importAt.apply(1).getFilter().setIsAllowlist(true);
    importAt.apply(1).getFilter().setStackInSlot(0, new ItemStack(item.apply("sand")));
    importAt.apply(1).getFilter().setStackInSlot(1, new ItemStack(item.apply("gravel")));
    BlockPos mainPos = at.apply(-2, 0);
    TileMain main = (TileMain) level.getBlockEntity(mainPos);
    var nw = main.getNetwork();
    java.util.List<BlockPos> containers = new java.util.ArrayList<>();
    for (int x = 0; x < 10; x++) containers.add(at.apply(x, 0));
    for (int x : new int[] {0, 1, 3, 5}) containers.add(at.apply(x, 3));
    for (int x = 0; x < 5; x++) containers.add(at.apply(x, -3));
    BlockPos joined = at.apply(2, -1);
    java.security.MessageDigest digest;
    try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException missing) {
        throw new IllegalStateException(missing);
    }
    var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
    long saved = level.getGameTime();
    long total = 0;
    StringBuilder log = new StringBuilder();
    try {
        for (int round = 0; round < 130; round++) {
            data.setGameTime(700000L + round);
            StringBuilder state = new StringBuilder();
            if (round == 33) level.setBlockAndUpdate(at.apply(9, 1), Blocks.AIR.defaultBlockState());
            if (round == 50) level.setBlockAndUpdate(new BlockPos(ox + 8, y + 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
            if (round == 66) {
                level.setBlockAndUpdate(at.apply(9, 1), link.defaultBlockState());
                ((TileCableWithFacing) level.getBlockEntity(at.apply(9, 1))).refreshInventoryDirection();
                storageAt.apply(at.apply(9, 1)).setPriority(42);
            }
            if (round == 80) storageAt.apply(at.apply(4, 1)).setPriority(15);
            if (round == 95) {
                ((Container) level.getBlockEntity(at.apply(2, 0))).clearContent();
                level.setBlockAndUpdate(at.apply(2, 0), Blocks.CHEST.defaultBlockState());
                ((Container) level.getBlockEntity(at.apply(2, 0))).setItem(4, new ItemStack(item.apply("bone"), 30));
            }
            if (round == 103) {
                // The chest from round 95 becomes half of a double chest: same block entity, new block state, so the
                // storage's cached item handler and its target key must both change (NeoForge invalidates on chest type).
                var east = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST);
                level.setBlockAndUpdate(at.apply(2, -1), east.setValue(ChestBlock.TYPE, ChestType.LEFT));
                level.setBlockAndUpdate(at.apply(2, 0), east.setValue(ChestBlock.TYPE, ChestType.RIGHT));
                ((Container) level.getBlockEntity(at.apply(2, -1))).setItem(9, new ItemStack(item.apply("cobblestone"), 64));
            }
            if (round == 110) level.setBlockAndUpdate(at.apply(0, -4), Blocks.AIR.defaultBlockState());
            if (round % 10 == 3) state.append("insert=").append(main.insertStack(new ItemStack(item.apply("sand"), 37), false)).append(';');
            if (round % 10 == 7) state.append("request=").append(main.request(new ItemStackMatcherDefault(new ItemStack(item.apply("dirt"))), 20, false)).append(';');
            TileMain.serverTick(level, mainPos, level.getBlockState(mainPos), main);
            java.util.List<BlockPos> present = new java.util.ArrayList<>(containers);
            if (level.getBlockEntity(joined) instanceof Container) present.add(joined);
            for (BlockPos pos : present) {
                Container c = (Container) level.getBlockEntity(pos);
                for (int slot = 0; slot < c.getContainerSize(); slot++) {
                    ItemStack stack = c.getItem(slot);
                    if (!stack.isEmpty()) state.append(pos.getX() - ox).append(',').append(pos.getZ()).append(',').append(slot).append(',').append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(',').append(stack.getCount()).append(';');
                }
            }
            state.append("size=").append(nw.getConnectableSize()).append(";signal=").append(nw.getComparatorSignal()).append(";empty=").append(nw.emptySlots()).append(";sorted=");
            for (ItemStack stack : nw.getSortedStacks()) state.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append('x').append(stack.getCount()).append(',');
            state.append(";cobble=").append(nw.getAmount(new ItemStackMatcherDefault(new ItemStack(item.apply("cobblestone")))));
            if (round % 13 == 0) log.append(round).append(':').append(state.length()).append(' ');
            digest.update(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    } finally {
        data.setGameTime(saved);
    }
    for (BlockPos pos : containers) {
        Container c = (Container) level.getBlockEntity(pos);
        for (int slot = 0; slot < c.getContainerSize(); slot++) total += c.getItem(slot).getCount();
    }
    return java.util.HexFormat.of().formatHex(digest.digest()) + " items=" + total + " size=" + nw.getConnectableSize() + " lengths=" + log.toString().trim();
}).join();
return network.apply(2004, false) + " | " + network.apply(2036, true);
