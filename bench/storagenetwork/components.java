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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
// Break attempt (exact gate): items with components in the storages, and storage blocks with components. A frozen
// network far from the player, ticked by hand at fixed game times 800000..800119 (like trace.java). Storages (z 0,
// links z 1), each with its own priority: 0 a barrel placed from a named barrel item (custom_name on the block entity)
// holding plain cobblestone, cobblestone named "a" and "b", an enchanted iron sword, damaged pickaxes and a shulker box
// item full of cobblestone (container component); 1 a barrel whose link denies cobblestone named "a" (nbt match on);
// 2 a barrel whose link allows only cobblestone named "b" (nbt on); 3 a barrel whose link denies cobblestone (nbt off);
// 4 a shulker box block with contents (not a plain barrel/chest, so the export takes the general path) or, in the
// second network, a barrel. Exports (z -2) into buffers (z -3): cobblestone (stack, 3 speed), iron sword, shulker box,
// iron pickaxe. Import (z -4) from buffer 4 (named-a, named-b and plain cobblestone, a renamed sword) with an allow
// filter of cobblestone named "a" (nbt on). Every 10 rounds a player-style insert of 20 cobblestone named "b" and a
// request of 10 cobblestone named "a" (nbt matcher). At round 40 a stack in storage 0 is renamed in place, at 60
// storage 1's filter switches to nbt off, at 80 storage 2's barrel is replaced by a chest. Each round every container
// (item, count, components), the network's size, comparator signal, empty slots and sorted contents (with components)
// go into a SHA-256. Runs at x 7004 (with the shulker box storage) and at x 7036 (all barrels).
var server = mc.getSingleplayerServer();
java.util.function.BiFunction<Integer, Boolean, String> network = (ox, plain) -> server.submit(() -> {
    var level = server.overworld();
    var registries = level.registryAccess();
    int y = -60;
    level.getChunk(ox >> 4, -1);
    level.getChunk(ox >> 4, 0);
    java.util.function.BiFunction<Integer, Integer, BlockPos> at = (x, z) -> new BlockPos(ox + x, y, z);
    for (int x = -2; x < 11; x++) for (int z = -5; z <= 6; z++) for (int dy = 0; dy < 2; dy++) {
        level.setBlockAndUpdate(new BlockPos(ox + x, y + dy, z), Blocks.AIR.defaultBlockState());
    }
    java.util.function.BiFunction<Item, String, ItemStack> named = (item, name) -> {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    };
    Block kabel = SsnRegistry.Blocks.KABEL.get();
    Block link = SsnRegistry.Blocks.STORAGE_KABEL.get();
    level.setBlockAndUpdate(at.apply(-2, 0), SsnRegistry.Blocks.MASTER.get().defaultBlockState());
    for (int z = -4; z <= 1; z++) level.setBlockAndUpdate(at.apply(-1, z), kabel.defaultBlockState());
    for (int x = 0; x < 5; x++) {
        level.setBlockAndUpdate(at.apply(x, 0), (x == 4 && !plain ? Blocks.SHULKER_BOX : Blocks.BARREL).defaultBlockState());
        level.setBlockAndUpdate(at.apply(x, 1), link.defaultBlockState());
        level.setBlockAndUpdate(at.apply(x, -3), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(at.apply(x, -2), x < 4 ? SsnRegistry.Blocks.EXPORT_KABEL.get().defaultBlockState() : kabel.defaultBlockState());
    }
    level.setBlockAndUpdate(at.apply(4, -4), SsnRegistry.Blocks.IMPORT_KABEL.get().defaultBlockState());
    for (int x = 0; x < 4; x++) level.setBlockAndUpdate(at.apply(x, -4), kabel.defaultBlockState());
    // storage 0 is a barrel placed from a named barrel item
    level.getBlockEntity(at.apply(0, 0)).applyComponentsFromItemStack(named.apply(Items.BARREL, "vault"));
    for (int x = -1; x < 10; x++) for (int z = -4; z <= 4; z++) {
        BlockPos pos = at.apply(x, z);
        if (level.getBlockEntity(pos) instanceof TileConnectable) {
            level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos));
            if (level.getBlockEntity(pos) instanceof TileCableWithFacing cable) cable.refreshInventoryDirection();
        }
    }
    Container s0 = (Container) level.getBlockEntity(at.apply(0, 0));
    s0.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
    s0.setItem(1, named.apply(Items.COBBLESTONE, "a").copyWithCount(40));
    s0.setItem(2, named.apply(Items.COBBLESTONE, "b").copyWithCount(33));
    ItemStack sword = new ItemStack(Items.IRON_SWORD);
    sword.enchant(registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 3);
    s0.setItem(3, sword.copy());
    s0.setItem(4, new ItemStack(Items.IRON_SWORD));
    for (int i = 0; i < 3; i++) {
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.set(DataComponents.DAMAGE, 20 * i);
        s0.setItem(5 + i, pick);
    }
    ItemStack box = new ItemStack(Items.SHULKER_BOX);
    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.COBBLESTONE, 64), named.apply(Items.DIAMOND, "gem"))));
    s0.setItem(8, box.copy());
    s0.setItem(9, new ItemStack(Items.SHULKER_BOX));
    s0.setItem(10, new ItemStack(Items.COBBLESTONE, 64));
    s0.setItem(11, named.apply(Items.COBBLESTONE, "a").copyWithCount(64));
    Container s3 = (Container) level.getBlockEntity(at.apply(3, 0));
    s3.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
    s3.setItem(1, named.apply(Items.COBBLESTONE, "b").copyWithCount(64));
    Container s4 = (Container) level.getBlockEntity(at.apply(4, 0));
    s4.setItem(0, named.apply(Items.COBBLESTONE, "a").copyWithCount(64));
    s4.setItem(1, box.copy());
    s4.setItem(2, new ItemStack(Items.COBBLESTONE, 30));
    Container buf4 = (Container) level.getBlockEntity(at.apply(4, -3));
    buf4.setItem(0, named.apply(Items.COBBLESTONE, "a").copyWithCount(50));
    buf4.setItem(1, named.apply(Items.COBBLESTONE, "b").copyWithCount(50));
    buf4.setItem(2, new ItemStack(Items.COBBLESTONE, 50));
    buf4.setItem(3, named.apply(Items.IRON_SWORD, "old"));
    java.util.function.Function<Integer, CapabilityConnectableDefault> storageAt = x -> (CapabilityConnectableDefault) ((TileCableLink) level.getBlockEntity(at.apply(x, 1))).getItemStorage();
    int[] priorities = {3, 7, 1, 9, 5};
    for (int x = 0; x < 5; x++) storageAt.apply(x).setPriority(priorities[x]);
    storageAt.apply(1).getFilter().setMatchNbt(true);
    storageAt.apply(1).getFilter().setStackInSlot(0, named.apply(Items.COBBLESTONE, "a"));
    storageAt.apply(2).getFilter().setMatchNbt(true);
    storageAt.apply(2).getFilter().setIsAllowlist(true);
    storageAt.apply(2).getFilter().setStackInSlot(0, named.apply(Items.COBBLESTONE, "b"));
    storageAt.apply(3).getFilter().setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
    java.util.function.Function<Integer, CapabilityImportExportDefault> exportAt = x -> (CapabilityImportExportDefault) ((TileCableExport) level.getBlockEntity(at.apply(x, -2))).getIoStorage();
    CapabilityImportExportDefault importer = (CapabilityImportExportDefault) ((TileCableIO) level.getBlockEntity(at.apply(4, -4))).getIoStorage();
    Item[] exportItems = {Items.COBBLESTONE, Items.IRON_SWORD, Items.SHULKER_BOX, Items.IRON_PICKAXE};
    for (int x = 0; x < 4; x++) {
        exportAt.apply(x).setPriority(20 + x);
        exportAt.apply(x).getFilter().setStackInSlot(0, new ItemStack(exportItems[x]));
    }
    exportAt.apply(0).upgrades.setStackInSlot(0, new ItemStack(SsnRegistry.Items.STACK_UPGRADE.get()));
    for (int s = 1; s < 4; s++) exportAt.apply(0).upgrades.setStackInSlot(s, new ItemStack(SsnRegistry.Items.SPEED_UPGRADE.get()));
    for (int s = 0; s < 3; s++) exportAt.apply(1 + s).upgrades.setStackInSlot(0, new ItemStack(SsnRegistry.Items.SPEED_UPGRADE.get()));
    importer.upgrades.setStackInSlot(0, new ItemStack(SsnRegistry.Items.STACK_UPGRADE.get()));
    importer.upgrades.setStackInSlot(1, new ItemStack(SsnRegistry.Items.SPEED_UPGRADE.get()));
    importer.getFilter().setMatchNbt(true);
    importer.getFilter().setIsAllowlist(true);
    importer.getFilter().setStackInSlot(0, named.apply(Items.COBBLESTONE, "a"));
    BlockPos mainPos = at.apply(-2, 0);
    TileMain main = (TileMain) level.getBlockEntity(mainPos);
    var nw = main.getNetwork();
    java.util.List<BlockPos> containers = new java.util.ArrayList<>();
    for (int x = 0; x < 5; x++) {
        containers.add(at.apply(x, 0));
        containers.add(at.apply(x, -3));
    }
    java.security.MessageDigest digest;
    try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException missing) {
        throw new IllegalStateException(missing);
    }
    // ItemStack.save (the codec) names components by id; their toString can print identity hashes (records holding objects without toString)
    java.util.function.Function<ItemStack, String> text = stack -> stack.isEmpty() ? "empty" : stack.copyWithCount(1).save(registries).toString() + "x" + stack.getCount();
    var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
    long saved = level.getGameTime();
    StringBuilder summary = new StringBuilder();
    try {
        for (int round = 0; round < 120; round++) {
            data.setGameTime(800000L + round);
            StringBuilder state = new StringBuilder();
            if (round == 40) s0.getItem(2).set(DataComponents.CUSTOM_NAME, Component.literal("a"));
            if (round == 60) storageAt.apply(1).getFilter().setMatchNbt(false);
            if (round == 80) {
                ((Container) level.getBlockEntity(at.apply(2, 0))).clearContent();
                level.setBlockAndUpdate(at.apply(2, 0), Blocks.CHEST.defaultBlockState());
                ((Container) level.getBlockEntity(at.apply(2, 0))).setItem(3, named.apply(Items.COBBLESTONE, "b").copyWithCount(5));
            }
            if (round % 10 == 3) state.append("insert=").append(main.insertStack(named.apply(Items.COBBLESTONE, "b").copyWithCount(20), false)).append(';');
            if (round % 10 == 7) state.append("request=").append(text.apply(main.request(new ItemStackMatcherDefault(named.apply(Items.COBBLESTONE, "a"), false, true), 10, false))).append(';');
            TileMain.serverTick(level, mainPos, level.getBlockState(mainPos), main);
            for (BlockPos pos : containers) {
                Container c = (Container) level.getBlockEntity(pos);
                for (int slot = 0; slot < c.getContainerSize(); slot++) {
                    ItemStack stack = c.getItem(slot);
                    if (!stack.isEmpty()) state.append(pos.getX() - ox).append(',').append(pos.getZ()).append(',').append(slot).append(',').append(text.apply(stack)).append(';');
                }
            }
            state.append("size=").append(nw.getConnectableSize()).append(";signal=").append(nw.getComparatorSignal()).append(";empty=").append(nw.emptySlots()).append(";sorted=");
            java.util.List<String> sorted = new java.util.ArrayList<>();
            for (ItemStack stack : nw.getSortedStacks()) sorted.add(text.apply(stack));
            java.util.Collections.sort(sorted); // ties between stacks that differ only in components are ordered by identity hashes, which differ per JVM
            state.append(String.join(",", sorted));
            digest.update(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (round == 119) {
                for (int x = 0; x < 5; x++) {
                    Container buffer = (Container) level.getBlockEntity(at.apply(x, -3));
                    int count = 0;
                    for (int slot = 0; slot < buffer.getContainerSize(); slot++) count += buffer.getItem(slot).getCount();
                    summary.append(" buf").append(x).append('=').append(count);
                }
                summary.append(" size=").append(nw.getConnectableSize());
            }
        }
    } finally {
        data.setGameTime(saved);
    }
    for (int x = -2; x < 11; x++) for (int z = -5; z <= 6; z++) for (int dy = 0; dy < 2; dy++) {
        BlockPos pos = new BlockPos(ox + x, y + dy, z);
        if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }
    return java.util.HexFormat.of().formatHex(digest.digest()) + summary;
}).join();
return network.apply(7004, false) + " | " + network.apply(7036, true);
