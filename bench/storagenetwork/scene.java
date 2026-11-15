import com.lothrazar.storagenetwork.api.capabilities.CapabilityImportExportDefault;
import com.lothrazar.storagenetwork.block.TileCableWithFacing;
import com.lothrazar.storagenetwork.block.cable.export.TileCableExport;
import com.lothrazar.storagenetwork.block.cable.input.TileCableIO;
import com.lothrazar.storagenetwork.block.main.TileMain;
import com.lothrazar.storagenetwork.registry.SsnRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
// Builds the storage base the experiment measures, once, into the save. A master at (-2, -60, 0) and a
// cable spine along x = -1 join: 8 rows (z 0, 3, .., 21) of 32 barrels (x 0..31), each barrel with a
// storage link cable on its south side (256 storage barrels, 6,912 slots, holding 41 item kinds); and an
// I/O station of 16 buffer barrels at z -3, each with an export cable on its south side (z -2) and an
// import cable on its north side (z -4). Export cable i asks the network for one item kind, import cable
// i pulls the buffer back into the network; both have a stack upgrade and 3 speed upgrades (64 items
// every 7 ticks). Items circulate for as long as the world runs and the total never changes.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    var source = server.createCommandSourceStack().withSuppressedOutput();
    server.getCommands().performPrefixedCommand(source, "forceload add -16 -16 47 31");
    server.getCommands().performPrefixedCommand(source, "fill -4 -60 -6 33 -55 24 minecraft:air");
    String[] items = {"stone", "cobblestone", "dirt", "oak_log", "birch_log", "spruce_log", "oak_planks", "sand", "gravel",
        "iron_ingot", "gold_ingot", "copper_ingot", "coal", "redstone", "lapis_lazuli", "diamond", "emerald", "quartz",
        "string", "bone", "arrow", "wheat", "carrot", "potato", "beef", "leather", "feather", "glass", "andesite", "diorite",
        "granite", "deepslate", "tuff", "clay_ball", "flint", "gunpowder", "slime_ball", "ender_pearl", "glowstone_dust", "sugar_cane", "kelp"};
    int rows = 8;
    int width = 32;
    int y = -60;
    Block kabel = SsnRegistry.Blocks.KABEL.get();
    Block link = SsnRegistry.Blocks.STORAGE_KABEL.get();
    Block exporter = SsnRegistry.Blocks.EXPORT_KABEL.get();
    Block importer = SsnRegistry.Blocks.IMPORT_KABEL.get();
    level.setBlockAndUpdate(new BlockPos(-2, y, 0), SsnRegistry.Blocks.MASTER.get().defaultBlockState());
    for (int z = -4; z <= 3 * (rows - 1) + 1; z++) level.setBlockAndUpdate(new BlockPos(-1, y, z), kabel.defaultBlockState());
    for (int r = 0; r < rows; r++) for (int x = 0; x < width; x++) {
        level.setBlockAndUpdate(new BlockPos(x, y, 3 * r), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, y, 3 * r + 1), link.defaultBlockState());
    }
    for (int x = 0; x < 16; x++) {
        level.setBlockAndUpdate(new BlockPos(x, y, -3), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, y, -2), exporter.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, y, -4), importer.defaultBlockState());
    }
    for (int x = -1; x < width; x++) for (int z = -4; z <= 3 * (rows - 1) + 1; z++) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockEntity(pos) instanceof TileCableWithFacing cable) {
            level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
            ((TileCableWithFacing) level.getBlockEntity(pos)).refreshInventoryDirection();
        }
    }
    for (int x = -1; x < width; x++) for (int z = -4; z <= 3 * (rows - 1) + 1; z++) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).is(kabel)) level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
    }
    // Storage: barrel (r, x) holds 12 to 26 stacks of kinds that depend on its place, some partial.
    for (int r = 0; r < rows; r++) for (int x = 0; x < width; x++) {
        Container barrel = (Container) level.getBlockEntity(new BlockPos(x, y, 3 * r));
        int filled = 12 + (r * 7 + x * 3) % 15;
        for (int slot = 0; slot < filled; slot++) {
            String id = items[(r * 5 + x * 11 + slot * 7) % items.length];
            int count = ((slot + x + r) % 4 == 0) ? 1 + (slot * 13 + x * 5 + r) % 63 : 64;
            barrel.setItem(slot, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id)), count));
        }
    }
    for (int x = 0; x < 16; x++) {
        var export = (CapabilityImportExportDefault) ((TileCableExport) level.getBlockEntity(new BlockPos(x, y, -2))).getIoStorage();
        var in = (CapabilityImportExportDefault) ((TileCableIO) level.getBlockEntity(new BlockPos(x, y, -4))).getIoStorage();
        export.getFilter().setStackInSlot(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(items[x * 2])), 1));
        for (var io : new CapabilityImportExportDefault[] {export, in}) {
            io.upgrades.setStackInSlot(0, new ItemStack(SsnRegistry.Items.STACK_UPGRADE.get()));
            for (int s = 1; s < 4; s++) io.upgrades.setStackInSlot(s, new ItemStack(SsnRegistry.Items.SPEED_UPGRADE.get()));
        }
        level.getBlockEntity(new BlockPos(x, y, -2)).setChanged();
        level.getBlockEntity(new BlockPos(x, y, -4)).setChanged();
    }
    TileMain main = (TileMain) level.getBlockEntity(new BlockPos(-2, y, 0));
    main.getNetwork().setShouldRefresh();
    server.getCommands().performPrefixedCommand(source, "tp @a 15.5 -50 -10 0 45");
    return "scene";
}).join();
