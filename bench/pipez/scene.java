import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.items.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
// Builds the base the experiment measures, once, into the save: 4 rows (z 0, 4, 8, 12) of 16 barrels
// (x 0..15), an item pipe line on each side of every row, and items that keep moving. A row's south
// line (z+1) extracts from its even barrels into its odd ones; its north line (z-1) extracts from the
// odd barrels into the even ones; all 64 extracting pipes have ultimate upgrades (64 items a tick).
// Every extracting pipe reaches 8 barrels at distances 1..15, some full and some empty, so the network
// keeps transferring and keeps finding full destinations for as long as the world runs. Even barrels
// start with 27 stacks of 27 different items.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    var source = server.createCommandSourceStack().withSuppressedOutput();
    server.getCommands().performPrefixedCommand(source, "forceload add -16 -16 31 15");
    server.getCommands().performPrefixedCommand(source, "fill -2 -60 -3 17 -55 15 minecraft:air");
    String[] items = {"stone", "cobblestone", "dirt", "oak_log", "birch_log", "spruce_log", "oak_planks", "sand", "gravel",
        "iron_ingot", "gold_ingot", "copper_ingot", "coal", "redstone", "lapis_lazuli", "diamond", "emerald", "quartz",
        "string", "bone", "arrow", "wheat", "carrot", "potato", "beef", "leather", "feather"};
    PipeBlock pipe = (PipeBlock) ModBlocks.ITEM_PIPE.get();
    for (int row = 0; row < 16; row += 4) for (int x = 0; x < 16; x++) {
        level.setBlockAndUpdate(new BlockPos(x, -60, row), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, -60, row + 1), pipe.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, -60, row - 1), pipe.defaultBlockState());
    }
    for (int row = 0; row < 16; row += 4) for (int x = 0; x < 16; x++) for (int z : new int[] {row + 1, row - 1}) {
        BlockPos pos = new BlockPos(x, -60, z);
        level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
    }
    for (int row = 0; row < 16; row += 4) for (int x = 0; x < 16; x++) {
        boolean even = x % 2 == 0;
        BlockPos pos = new BlockPos(x, -60, even ? row + 1 : row - 1);
        Direction toBarrel = even ? Direction.NORTH : Direction.SOUTH;
        pipe.setExtracting(level, pos, toBarrel, true);
        var tile = (UpgradeTileEntity) level.getBlockEntity(pos);
        tile.setUpgradeItem(toBarrel, new ItemStack(ModItems.ULTIMATE_UPGRADE.get()));
        if (even) {
            Container barrel = (Container) level.getBlockEntity(new BlockPos(x, -60, row));
            for (int slot = 0; slot < 27; slot++) {
                barrel.setItem(slot, new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(items[slot])), 64));
            }
        }
    }
    server.getCommands().performPrefixedCommand(source, "tp @a 7.5 -50 -8 0 45");
    return "scene";
}).join();
