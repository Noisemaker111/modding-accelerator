import com.lothrazar.cyclic.block.crafter.TileCrafter;
import com.lothrazar.cyclic.registry.BlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;
// Times each part of one crafter's tick on the server thread, in microseconds per call: a frozen crafter (iron block,
// full inputs, empty outputs, energy) at x 2004 that never ticks by itself. "tick" is a whole serverTick with the
// timer held above zero, so it does everything but craft; the parts are called through reflection (a few ns each).
var server = mc.getSingleplayerServer();
return server.submit(() -> {
  try {
    var level = server.overworld();
    BlockPos pos = new BlockPos(2004, -60, 8);
    level.getChunk(pos);
    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    level.setBlockAndUpdate(pos, BlockRegistry.CRAFTER.get().defaultBlockState());
    TileCrafter crafter = (TileCrafter) level.getBlockEntity(pos);
    java.util.function.Function<String, Object> field = name -> {
        try {
            var f = TileCrafter.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(crafter);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    };
    ItemStackHandler grid = (ItemStackHandler) field.apply("gridCap");
    ItemStackHandler input = (ItemStackHandler) field.apply("inputHandler");
    ItemStackHandler out = (ItemStackHandler) field.apply("outHandler");
    for (int s = 0; s < 9; s++) grid.setStackInSlot(s, new ItemStack(Items.IRON_INGOT));
    for (int s = 0; s < 10; s++) input.setStackInSlot(s, new ItemStack(Items.IRON_INGOT, 64));
    crafter.getEnergyHandler(null).receiveEnergy(64000, false);
    var find = TileCrafter.class.getDeclaredMethod("findMatchingRecipe", java.util.ArrayList.class);
    var check = TileCrafter.class.getDeclaredMethod("checkInput", net.neoforged.neoforge.items.IItemHandler.class);
    var free = TileCrafter.class.getDeclaredMethod("hasFreeSpace", net.neoforged.neoforge.items.IItemHandler.class, ItemStack.class);
    var preview = TileCrafter.class.getDeclaredMethod("setPreviewSlot", ItemStack.class);
    for (var m : new java.lang.reflect.Method[] {find, check, free, preview}) m.setAccessible(true);
    ItemStack block = new ItemStack(Items.IRON_BLOCK);
    String[] names = {"tick", "findMatchingRecipe", "checkInput", "hasFreeSpace", "setPreviewSlot", "resultCopy"};
    double[] micros = new double[names.length];
    int calls = 20000;
    for (int pass = 0; pass < 3; pass++) {
        for (int part = 0; part < names.length; part++) {
            long start = System.nanoTime();
            for (int c = 0; c < calls; c++) {
                switch (part) {
                    case 0 -> {
                        crafter.setField(0, 1000);
                        crafter.serverTick();
                    }
                    case 1 -> find.invoke(crafter, (Object) null);
                    case 2 -> check.invoke(crafter, input);
                    case 3 -> free.invoke(crafter, out, block);
                    case 4 -> preview.invoke(crafter, block);
                    default -> {
                        var r = (net.minecraft.world.item.crafting.RecipeHolder<?>) find.invoke(crafter, (Object) null);
                        ((net.minecraft.world.item.crafting.CraftingRecipe) r.value()).getResultItem(level.registryAccess()).copy();
                    }
                }
            }
            micros[part] = (System.nanoTime() - start) / 1000.0 / calls;
        }
    }
    for (var h : new ItemStackHandler[] {grid, input, out, (ItemStackHandler) field.apply("preview")}) {
        for (int s = 0; s < h.getSlots(); s++) h.setStackInSlot(s, ItemStack.EMPTY);
    }
    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    StringBuilder b = new StringBuilder();
    for (int part = 0; part < names.length; part++) b.append(names[part]).append('=').append(String.format("%.3f", micros[part])).append("us ");
    return b.toString().trim();
  } catch (ReflectiveOperationException e) {
    throw new RuntimeException(e);
  }
}).join();
