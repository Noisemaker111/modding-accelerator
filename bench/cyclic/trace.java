import com.lothrazar.cyclic.block.crafter.TileCrafter;
import com.lothrazar.cyclic.registry.BlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;
// Exact gate: 12 frozen crafters far from the player (x 2004..2026, y -60, z 0: chunks 125 and 126, which never
// tick), each ticked by hand for 160 rounds in a fixed order, all inside one server task. Grids and inputs:
//  0  nine iron ingots (iron block), 2 stacks of ingots;      1  one oak log (planks, shapeless), a stack of logs;
//  2  eight paper around a filled map (map extending: a recipe whose match reads the map's saved data), paper and the map;
//  3  eight cobblestone around nothing (furnace), cobblestone;  4  dirt and a diamond: no recipe;  5  empty grid;
//  6  nine iron ingots, one of them named (components), 1 stack;  7  needs redstone, unpowered until round 100;
//  8  nine gold nuggets with energy for 2 crafts;              9  bowl, brown and red mushroom (stew, shapeless, 3 kinds);
// 10, 11  an oak log in grid slot 1, inputs full: 1 (crafter 10) or 2 (11) oak logs, then dirt. Cyclic's input check
//     lets an input stack whose count ran out claim empty grid slots, and then every empty grid slot: 10 crafts once.
// Changes during the run, so cached recipe state must notice: round 50 crafter 0's centre emptied (no recipe), 70
// put back; round 60 crafter 1's log becomes a birch log (another recipe, no matching input); round 80 crafter 3's
// first cobblestone is shrunk to nothing in place (no recipe, no handler call), 100 put back; round 90 crafter 6's
// named ingot renamed in place; round 120 the server's recipes are replaced by all but iron_block and furnace, round
// 140 the original recipes are put back in their order. After every round each crafter's 30 slots (item, count,
// components), timer and energy go into a SHA-256; the gate returns the hash and each crafter's output count.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
  try {
    var level = server.overworld();
    int ox = 2004;
    int y = -60;
    level.getChunk(ox >> 4, 0);
    level.getChunk((ox + 22) >> 4, 0);
    for (int x = -1; x < 25; x++) for (int dy = 0; dy < 2; dy++) for (int z = -1; z < 2; z++) {
        level.setBlockAndUpdate(new BlockPos(ox + x, y + dy, z), Blocks.AIR.defaultBlockState());
    }
    java.lang.reflect.Field gridField = TileCrafter.class.getDeclaredField("gridCap");
    java.lang.reflect.Field inputField = TileCrafter.class.getDeclaredField("inputHandler");
    java.lang.reflect.Field outField = TileCrafter.class.getDeclaredField("outHandler");
    java.lang.reflect.Field previewField = TileCrafter.class.getDeclaredField("preview");
    for (var f : new java.lang.reflect.Field[] {gridField, inputField, outField, previewField}) f.setAccessible(true);
    java.util.function.Function<String, net.minecraft.world.item.Item> item = id -> BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id));
    int n = 12;
    TileCrafter[] crafters = new TileCrafter[n];
    ItemStackHandler[] grids = new ItemStackHandler[n];
    ItemStackHandler[] inputs = new ItemStackHandler[n];
    ItemStackHandler[] outs = new ItemStackHandler[n];
    ItemStackHandler[] previews = new ItemStackHandler[n];
    for (int k = 0; k < n; k++) {
        BlockPos pos = new BlockPos(ox + 2 * k, y, 0);
        level.setBlockAndUpdate(pos, BlockRegistry.CRAFTER.get().defaultBlockState());
        crafters[k] = (TileCrafter) level.getBlockEntity(pos);
        grids[k] = (ItemStackHandler) gridField.get(crafters[k]);
        inputs[k] = (ItemStackHandler) inputField.get(crafters[k]);
        outs[k] = (ItemStackHandler) outField.get(crafters[k]);
        previews[k] = (ItemStackHandler) previewField.get(crafters[k]);
        crafters[k].getEnergyHandler(null).receiveEnergy(k == 8 ? 1000 : 64000, false);
    }
    for (int s = 0; s < 9; s++) grids[0].setStackInSlot(s, new ItemStack(Items.IRON_INGOT));
    inputs[0].setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    inputs[0].setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 64));
    grids[1].setStackInSlot(0, new ItemStack(Items.OAK_LOG));
    inputs[1].setStackInSlot(0, new ItemStack(Items.OAK_LOG, 64));
    ItemStack map = MapItem.create(level, ox, 0, (byte) 0, false, false);
    for (int s = 0; s < 9; s++) grids[2].setStackInSlot(s, s == 4 ? map.copy() : new ItemStack(Items.PAPER));
    inputs[2].setStackInSlot(0, new ItemStack(Items.PAPER, 64));
    inputs[2].setStackInSlot(1, map.copy());
    for (int s = 0; s < 9; s++) if (s != 4) grids[3].setStackInSlot(s, new ItemStack(Items.COBBLESTONE));
    inputs[3].setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 64));
    grids[4].setStackInSlot(0, new ItemStack(Items.DIRT));
    grids[4].setStackInSlot(4, new ItemStack(Items.DIAMOND));
    inputs[4].setStackInSlot(0, new ItemStack(Items.DIRT, 64));
    for (int s = 0; s < 9; s++) grids[6].setStackInSlot(s, new ItemStack(Items.IRON_INGOT));
    grids[6].getStackInSlot(3).set(DataComponents.CUSTOM_NAME, Component.literal("named"));
    inputs[6].setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    for (int s = 0; s < 9; s++) grids[7].setStackInSlot(s, new ItemStack(Items.COAL));
    inputs[7].setStackInSlot(0, new ItemStack(Items.COAL, 64));
    crafters[7].setField(1, 1);
    for (int s = 0; s < 9; s++) grids[8].setStackInSlot(s, new ItemStack(Items.GOLD_NUGGET));
    inputs[8].setStackInSlot(0, new ItemStack(Items.GOLD_NUGGET, 64));
    grids[9].setStackInSlot(0, new ItemStack(Items.BOWL));
    grids[9].setStackInSlot(1, new ItemStack(Items.BROWN_MUSHROOM));
    grids[9].setStackInSlot(2, new ItemStack(Items.RED_MUSHROOM));
    inputs[9].setStackInSlot(0, new ItemStack(Items.BOWL, 64));
    inputs[9].setStackInSlot(1, new ItemStack(Items.BROWN_MUSHROOM, 64));
    inputs[9].setStackInSlot(2, new ItemStack(Items.RED_MUSHROOM, 64));
    for (int k = 10; k < 12; k++) {
        grids[k].setStackInSlot(1, new ItemStack(Items.OAK_LOG));
        inputs[k].setStackInSlot(0, new ItemStack(Items.OAK_LOG, k - 9));
        for (int s = 1; s < 10; s++) inputs[k].setStackInSlot(s, new ItemStack(Items.DIRT, 64));
    }
    var manager = level.getRecipeManager();
    java.util.List<RecipeHolder<?>> original = new java.util.ArrayList<>(manager.getRecipes());
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    java.util.function.Consumer<ItemStack> put = stack -> {
        String text = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount() + stack.getComponentsPatch() + ";";
        digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    };
    try {
        for (int round = 0; round < 160; round++) {
            if (round == 50) grids[0].setStackInSlot(4, ItemStack.EMPTY);
            if (round == 70) grids[0].setStackInSlot(4, new ItemStack(Items.IRON_INGOT));
            if (round == 60) grids[1].setStackInSlot(0, new ItemStack(Items.BIRCH_LOG));
            if (round == 80) grids[3].getStackInSlot(0).shrink(1);
            if (round == 100) grids[3].setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
            if (round == 90) grids[6].getStackInSlot(3).set(DataComponents.CUSTOM_NAME, Component.literal("renamed"));
            if (round == 100) level.setBlockAndUpdate(new BlockPos(ox + 14, y + 1, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
            if (round == 120) {
                java.util.List<RecipeHolder<?>> fewer = new java.util.ArrayList<>();
                for (var holder : original) {
                    String id = holder.id().toString();
                    if (!id.equals("minecraft:iron_block") && !id.equals("minecraft:furnace")) fewer.add(holder);
                }
                manager.replaceRecipes(fewer);
            }
            if (round == 140) manager.replaceRecipes(original);
            for (int k = 0; k < n; k++) crafters[k].serverTick();
            for (int k = 0; k < n; k++) {
                for (var handler : new ItemStackHandler[] {grids[k], inputs[k], outs[k], previews[k]}) {
                    for (int s = 0; s < handler.getSlots(); s++) put.accept(handler.getStackInSlot(s));
                }
                String state = "t" + crafters[k].getField(0) + "e" + crafters[k].getEnergyHandler(null).getEnergyStored() + "|";
                digest.update(state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    } finally {
        if (manager.getRecipes().size() != original.size()) manager.replaceRecipes(original);
    }
    StringBuilder made = new StringBuilder();
    for (int k = 0; k < n; k++) {
        int count = 0;
        for (int s = 0; s < outs[k].getSlots(); s++) count += outs[k].getStackInSlot(s).getCount();
        made.append(' ').append(count);
    }
    for (int k = 0; k < n; k++) {
        for (var handler : new ItemStackHandler[] {grids[k], inputs[k], outs[k], previews[k]}) {
            for (int s = 0; s < handler.getSlots(); s++) handler.setStackInSlot(s, ItemStack.EMPTY);
        }
    }
    for (int x = -1; x < 25; x++) for (int dy = 0; dy < 2; dy++) {
        level.setBlockAndUpdate(new BlockPos(ox + x, y + dy, 0), Blocks.AIR.defaultBlockState());
    }
    return java.util.HexFormat.of().formatHex(digest.digest()) + " outputs" + made;
  } catch (ReflectiveOperationException | java.security.NoSuchAlgorithmException e) {
    throw new RuntimeException(e);
  }
}).join();
