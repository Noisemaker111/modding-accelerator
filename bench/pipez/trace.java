import de.maxhenkel.pipez.DirectionalPosition;
import de.maxhenkel.pipez.ItemFilter;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.types.ItemPipeType;
import de.maxhenkel.pipez.items.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
// Exact gate: frozen networks far from the player (x 2000.., never a ticking chunk), ticked by hand
// in a fixed order at fixed game times, so every launch computes the same transfers. Two rows of 12
// barrels (z 0 and z 6), an item pipe line on each side of each row, and one extracting pipe at an end
// of every line, so no two destinations of an extractor are at the same distance: Pipez orders
// equal-distance connections by a hash of Direction, which differs between JVMs. Row 0: south line
// from barrel 0 NEAREST, north line from barrel 11 FURTHEST with an inverted filter that skips barrel 1.
// Row 6: south line from barrel 0 ROUND_ROBIN, north line from barrel 11 with an advanced upgrade
// (every 5 game ticks) NEAREST and a filter that allows only barrel 4. Barrel 3 of row 0 starts full,
// barrel 7 of each row with partial mixed stacks. After each of 120 rounds the contents of every barrel
// and every round-robin index go into a SHA-256; the gate returns the hash and the item total.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    int ox = 2000;
    int y = -60;
    level.getChunk(ox >> 4, 0);
    PipeBlock pipe = (PipeBlock) ModBlocks.ITEM_PIPE.get();
    String[] items = {"stone", "cobblestone", "dirt", "oak_log", "birch_log", "iron_ingot", "gold_ingot", "coal", "redstone",
        "diamond", "string", "bone", "arrow", "wheat", "carrot"};
    int[] rows = {0, 6};
    for (int x = -2; x < 14; x++) for (int z = -2; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(ox + x, y, z), Blocks.AIR.defaultBlockState());
    for (int row : rows) for (int x = 0; x < 12; x++) {
        level.setBlockAndUpdate(new BlockPos(ox + x, y, row), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(ox + x, y, row + 1), pipe.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(ox + x, y, row - 1), pipe.defaultBlockState());
    }
    for (int row : rows) for (int x = 0; x < 12; x++) for (int z : new int[] {row + 1, row - 1}) {
        BlockPos pos = new BlockPos(ox + x, y, z);
        level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
    }
    java.util.function.BiFunction<Integer, Integer, Container> barrel = (row, x) -> (Container) level.getBlockEntity(new BlockPos(ox + x, y, row));
    for (int row : rows) for (int x : new int[] {0, 11}) {
        Container c = barrel.apply(row, x);
        for (int slot = 0; slot < 27; slot++) {
            String id = items[(slot * 7 + x + row) % items.length];
            c.setItem(slot, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id)), 1 + (slot * 13 + x * 5 + row) % 64));
        }
    }
    for (int slot = 0; slot < 27; slot++) barrel.apply(0, 3).setItem(slot, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("stone")), 64));
    for (int row : rows) for (int slot = 0; slot < 27; slot += 3) {
        barrel.apply(row, 7).setItem(slot, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(items[slot % items.length])), 60));
    }
    // {row, barrel x, line (1 south, -1 north), upgrade, distribution, filtered barrel or null, inverted}
    Object[][] extractors = {
        {0, 0, 1, "ultimate", UpgradeTileEntity.Distribution.NEAREST, null, false},
        {0, 11, -1, "ultimate", UpgradeTileEntity.Distribution.FURTHEST, 1, true},
        {6, 0, 1, "ultimate", UpgradeTileEntity.Distribution.ROUND_ROBIN, null, false},
        {6, 11, -1, "advanced", UpgradeTileEntity.Distribution.NEAREST, 4, false}
    };
    java.util.List<PipeLogicTileEntity> pipes = new java.util.ArrayList<>();
    for (Object[] e : extractors) {
        int row = (Integer) e[0];
        int x = (Integer) e[1];
        int line = (Integer) e[2];
        BlockPos pos = new BlockPos(ox + x, y, row + line);
        Direction toBarrel = line > 0 ? Direction.NORTH : Direction.SOUTH;
        pipe.setExtracting(level, pos, toBarrel, true);
        var tile = (PipeLogicTileEntity) level.getBlockEntity(pos);
        ItemStack upgrade = new ItemStack(e[3].equals("ultimate") ? ModItems.ULTIMATE_UPGRADE.get() : ModItems.ADVANCED_UPGRADE.get());
        ItemPipeType.INSTANCE.setDistribution(upgrade, (UpgradeTileEntity.Distribution) e[4]);
        if (e[5] != null) {
            int target = (Integer) e[5];
            var destination = new DirectionalPosition(new BlockPos(ox + target, y, row), line > 0 ? Direction.SOUTH : Direction.NORTH);
            ItemPipeType.INSTANCE.setFilters(upgrade, java.util.List.of(new ItemFilter(new java.util.UUID(0, target), null, null, false, destination, (Boolean) e[6])));
        }
        tile.setUpgradeItem(toBarrel, upgrade);
        pipes.add(tile);
    }
    java.security.MessageDigest digest;
    try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException missing) {
        throw new IllegalStateException(missing);
    }
    var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
    long saved = level.getGameTime();
    long total = 0;
    try {
        for (int round = 0; round < 120; round++) {
            data.setGameTime(700000L + round);
            for (PipeLogicTileEntity tile : pipes) tile.tick();
            StringBuilder state = new StringBuilder();
            for (int row : rows) for (int x = 0; x < 12; x++) {
                Container c = barrel.apply(row, x);
                for (int slot = 0; slot < 27; slot++) {
                    ItemStack stack = c.getItem(slot);
                    if (!stack.isEmpty()) state.append(row).append(',').append(x).append(',').append(slot).append(',').append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(',').append(stack.getCount()).append(';');
                }
            }
            for (PipeLogicTileEntity tile : pipes) for (Direction d : Direction.values()) state.append(tile.getRoundRobinIndex(d, ItemPipeType.INSTANCE)).append(',');
            digest.update(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    } finally {
        data.setGameTime(saved);
    }
    for (int row : rows) for (int x = 0; x < 12; x++) for (int slot = 0; slot < 27; slot++) total += barrel.apply(row, x).getItem(slot).getCount();
    return java.util.HexFormat.of().formatHex(digest.digest()) + " items=" + total;
}).join();
