import com.lothrazar.cyclic.block.crafter.TileCrafter;
import com.lothrazar.cyclic.registry.BlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.neoforged.neoforge.items.ItemStackHandler;
// Builds the crafter base the experiment measures, once, into the save: 8 rows (z 0, 3, .., 21) of 16 Cyclic
// crafters (x 0, 2, .., 30, y -58), 128 in all, with a creative battery between each pair along the row
// (x 1, 3, .., 31) to power them. Each crafter is a column: a source barrel at y -56 full of its ingredient,
// a hopper at y -57 feeding the crafter from above, the crafter, a hopper at y -59 pulling its output down
// into a sink barrel at y -60; a second source barrel (y -57) and hopper (y -58) feed it from the south, so
// two hoppers (5 items per 40 ticks each) keep up with a craft of 9 every 40 ticks. Crafter (r, i) compresses
// ingredient (r * 5 + i) % 16 of the list below (all nine grid slots hold it: Cyclic's crafter only crafts
// a grid with no empty slot while its inputs are full) and starts with its 10 input slots full, so it
// crafts every 40 ticks for at least 12 minutes of play.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
  try {
    var level = server.overworld();
    var source = server.createCommandSourceStack().withSuppressedOutput();
    server.getCommands().performPrefixedCommand(source, "forceload add -16 -16 47 31");
    server.getCommands().performPrefixedCommand(source, "fill -4 -60 -4 35 -50 26 minecraft:air");
    // each crafter compresses one ingredient: all nine grid slots hold it
    String[] recipes = {"iron_ingot", "gold_ingot", "redstone", "coal", "lapis_lazuli", "diamond", "emerald", "wheat", "bone_meal", "slime_ball", "copper_ingot", "raw_iron", "raw_gold", "iron_nugget", "gold_nugget", "dried_kelp"};
    java.lang.reflect.Field gridField = TileCrafter.class.getDeclaredField("gridCap");
    java.lang.reflect.Field inputField = TileCrafter.class.getDeclaredField("inputHandler");
    gridField.setAccessible(true);
    inputField.setAccessible(true);
    int crafters = 0;
    for (int r = 0; r < 8; r++) for (int i = 0; i < 16; i++) {
        int x = 2 * i;
        int z = 3 * r;
        String id = recipes[(r * 5 + i) % recipes.length];
        var ingredient = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
        level.setBlockAndUpdate(new BlockPos(x, -60, z), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, -59, z), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(new BlockPos(x, -58, z), BlockRegistry.CRAFTER.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x + 1, -58, z), BlockRegistry.BATTERY_INFINITE.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, -57, z), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(new BlockPos(x, -56, z), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x, -58, z + 1), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(new BlockPos(x, -57, z + 1), Blocks.BARREL.defaultBlockState());
        for (BlockPos at : new BlockPos[] {new BlockPos(x, -56, z), new BlockPos(x, -57, z + 1)}) {
            Container barrel = (Container) level.getBlockEntity(at);
            for (int slot = 0; slot < 27; slot++) barrel.setItem(slot, new ItemStack(ingredient, 64));
        }
        TileCrafter crafter = (TileCrafter) level.getBlockEntity(new BlockPos(x, -58, z));
        ItemStackHandler grid = (ItemStackHandler) gridField.get(crafter);
        ItemStackHandler input = (ItemStackHandler) inputField.get(crafter);
        for (int s = 0; s < 9; s++) grid.setStackInSlot(s, new ItemStack(ingredient, 1));
        for (int s = 0; s < 10; s++) input.setStackInSlot(s, new ItemStack(ingredient, 64));
        crafter.setChanged();
        crafters++;
    }
    server.getCommands().performPrefixedCommand(source, "tp @a 15.5 -50 -8 0 45");
    return "crafters " + crafters;
  } catch (ReflectiveOperationException e) {
    throw new RuntimeException(e);
  }
}).join();
