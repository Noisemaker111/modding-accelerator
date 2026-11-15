import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
// Behaviour gate on the live base. In steady state export cable i puts 64 of its item into buffer i and
// import cable i pulls them back into the same slot in the same tick, so the base looks still between
// ticks. This gate rotates the 16 buffers' contents (buffer i gets buffer i+1's items; no item is made or
// lost), waits 2 s (about 5 transfer cycles), and then requires: every buffer holds only its own export
// item again and is not empty (imports pulled the foreign items back and exports refilled), the base
// still holds all 265,274 items before and after, and the master still sees all 315 nodes.
String[] items = {"stone", "cobblestone", "dirt", "oak_log", "birch_log", "spruce_log", "oak_planks", "sand", "gravel",
    "iron_ingot", "gold_ingot", "copper_ingot", "coal", "redstone", "lapis_lazuli", "diamond", "emerald", "quartz",
    "string", "bone", "arrow", "wheat", "carrot", "potato", "beef", "leather", "feather", "glass", "andesite", "diorite",
    "granite", "deepslate", "tuff", "clay_ball", "flint", "gunpowder", "slime_ball", "ender_pearl", "glowstone_dust", "sugar_cane", "kelp"};
var server = mc.getSingleplayerServer();
java.util.function.Supplier<long[]> totals = () -> server.submit(() -> {
    var level = server.overworld();
    long total = 0;
    for (int z = -3; z <= 21; z += 3) for (int x = 0; x < 32; x++) {
        if (z == -3 && x >= 16) continue;
        Container barrel = (Container) level.getBlockEntity(new BlockPos(x, -60, z));
        for (int slot = 0; slot < 27; slot++) total += barrel.getItem(slot).getCount();
    }
    var main = (com.lothrazar.storagenetwork.block.main.TileMain) level.getBlockEntity(new BlockPos(-2, -60, 0));
    return new long[] {total, main.getNetwork().getConnectableSize()};
}).join();
long[] before = totals.get();
server.submit(() -> {
    var level = server.overworld();
    java.util.List<java.util.List<ItemStack>> contents = new java.util.ArrayList<>();
    for (int x = 0; x < 16; x++) {
        Container c = (Container) level.getBlockEntity(new BlockPos(x, -60, -3));
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (int slot = 0; slot < 27; slot++) stacks.add(c.removeItemNoUpdate(slot));
        contents.add(stacks);
        c.setChanged();
    }
    for (int x = 0; x < 16; x++) {
        Container c = (Container) level.getBlockEntity(new BlockPos(x, -60, -3));
        var stacks = contents.get((x + 1) % 16);
        for (int slot = 0; slot < 27; slot++) c.setItem(slot, stacks.get(slot));
    }
    return null;
}).join();
try {
    Thread.sleep(2000);
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();
}
boolean own = server.submit(() -> {
    var level = server.overworld();
    boolean ok = true;
    for (int x = 0; x < 16; x++) {
        Container c = (Container) level.getBlockEntity(new BlockPos(x, -60, -3));
        var expected = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(items[x * 2]));
        long count = 0;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.isEmpty()) continue;
            ok &= stack.getItem() == expected;
            count += stack.getCount();
        }
        ok &= count > 0;
    }
    return ok;
}).join();
long[] after = totals.get();
return Boolean.toString(own && before[0] == 265274 && after[0] == 265274 && before[1] == 315 && after[1] == 315);
