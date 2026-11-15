import com.lothrazar.cyclic.block.crafter.TileCrafter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
// Behaviour gate on the live base. It empties the centre grid slot of 16 crafters (one of each ingredient; eight of
// an ingredient around nothing is no recipe), waits 1 s, and requires each of them to show no recipe in its
// preview; puts the centres back and waits 4 s (two crafting cycles). Then every one of the 128 crafters must show
// its compressed item in the preview and have sent at least one more of it to its sink, every sink must hold only
// that item, and every crafter must keep compressing: nine ingredients consumed for each block made, counted over
// sources, hoppers, crafter inputs and outputs, and sinks.
String[] recipes = {"iron_ingot", "gold_ingot", "redstone", "coal", "lapis_lazuli", "diamond", "emerald", "wheat",
    "bone_meal", "slime_ball", "copper_ingot", "raw_iron", "raw_gold", "iron_nugget", "gold_nugget", "dried_kelp"};
String[] blocks = {"iron_block", "gold_block", "redstone_block", "coal_block", "lapis_block", "diamond_block", "emerald_block", "hay_block",
    "bone_block", "slime_block", "copper_block", "raw_iron_block", "raw_gold_block", "iron_ingot", "gold_ingot", "dried_kelp_block"};
var server = mc.getSingleplayerServer();
java.util.function.Function<String, Item> item = id -> BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
java.util.function.Function<Integer, BlockPos> crafterAt = c -> new BlockPos(2 * (c % 16), -58, 3 * (c / 16));
java.util.function.Function<Integer, String> recipeOf = c -> recipes[((c / 16) * 5 + c % 16) % 16];
java.util.function.Function<Integer, String> blockOf = c -> blocks[((c / 16) * 5 + c % 16) % 16];
java.lang.reflect.Field gridField;
java.lang.reflect.Field previewField;
try {
    gridField = TileCrafter.class.getDeclaredField("gridCap");
    previewField = TileCrafter.class.getDeclaredField("preview");
} catch (NoSuchFieldException e) {
    throw new RuntimeException(e);
}
gridField.setAccessible(true);
previewField.setAccessible(true);
java.util.function.BiFunction<TileCrafter, java.lang.reflect.Field, ItemStackHandler> handler = (crafter, field) -> {
    try {
        return (ItemStackHandler) field.get(crafter);
    } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
    }
};
// per crafter: [ingredients anywhere in its column, made items in its sink and outputs]
java.util.function.Supplier<long[][]> totals = () -> server.submit(() -> {
    var level = server.overworld();
    long[][] t = new long[128][2];
    for (int c = 0; c < 128; c++) {
        BlockPos pos = crafterAt.apply(c);
        Item in = item.apply(recipeOf.apply(c));
        Item out = item.apply(blockOf.apply(c));
        for (BlockPos p : new BlockPos[] {pos.above(2), pos.above(), pos.south().above(), pos.south(), pos.below(), pos.below(2)}) {
            Container box = (Container) level.getBlockEntity(p);
            for (int s = 0; s < box.getContainerSize(); s++) {
                ItemStack stack = box.getItem(s);
                if (stack.is(in)) t[c][0] += stack.getCount();
                else if (stack.is(out)) t[c][1] += stack.getCount();
                else if (!stack.isEmpty()) t[c][1] -= 1000000;
            }
        }
        var inv = ((TileCrafter) level.getBlockEntity(pos)).getItemHandler(null);
        for (int s = 0; s < 20; s++) {
            ItemStack stack = inv.getStackInSlot(s);
            if (stack.is(in)) t[c][0] += stack.getCount();
            else if (stack.is(out)) t[c][1] += stack.getCount();
            else if (!stack.isEmpty()) t[c][1] -= 1000000;
        }
    }
    return t;
}).join();
long[][] before = totals.get();
server.submit(() -> {
    var level = server.overworld();
    for (int c = 0; c < 16; c++) {
        int k = c * 9 % 128;
        handler.apply((TileCrafter) level.getBlockEntity(crafterAt.apply(k)), gridField).setStackInSlot(4, ItemStack.EMPTY);
    }
    return null;
}).join();
try {
    Thread.sleep(1000);
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();
}
boolean cleared = server.submit(() -> {
    var level = server.overworld();
    boolean ok = true;
    for (int c = 0; c < 16; c++) {
        int k = c * 9 % 128;
        TileCrafter crafter = (TileCrafter) level.getBlockEntity(crafterAt.apply(k));
        ok &= handler.apply(crafter, previewField).getStackInSlot(0).isEmpty();
        handler.apply(crafter, gridField).setStackInSlot(4, new ItemStack(item.apply(recipeOf.apply(k))));
    }
    return ok;
}).join();
try {
    Thread.sleep(4000);
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();
}
long[][] after = totals.get();
boolean shown = server.submit(() -> {
    var level = server.overworld();
    boolean ok = true;
    for (int c = 0; c < 128; c++) {
        ItemStack preview = handler.apply((TileCrafter) level.getBlockEntity(crafterAt.apply(c)), previewField).getStackInSlot(0);
        ok &= preview.is(item.apply(blockOf.apply(c))) && preview.getCount() == 1;
    }
    return ok;
}).join();
boolean crafting = true;
for (int c = 0; c < 128; c++) {
    long made = after[c][1] - before[c][1];
    long used = before[c][0] - after[c][0];
    crafting &= made >= 1 && before[c][1] >= 0 && used == 9 * made;
}
return Boolean.toString(cleared && shown && crafting);
