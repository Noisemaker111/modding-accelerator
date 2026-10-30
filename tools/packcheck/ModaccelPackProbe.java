package modaccel.packprobe;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * pack-check's probe, loaded into a real modded server. Written in Mojang names; pack-check renames it to the
 * loader's runtime names before it goes in the mods folder.
 *
 * place: every block item is used on a support block by a fake player through the same server path a real
 *   right-click takes (ServerPlayerGameMode.useItemOn, so other mods' events and mixins run), and the block that
 *   lands is compared with the block the item says it places. Placed blocks stay, and are listed.
 * verify: on the next start of the same world, each listed block is read back after the save and reload.
 *
 * Properties: modaccel.probe.mode (place|verify), modaccel.probe.out (result JSON), modaccel.probe.placed
 * (the place run's list, read by verify), modaccel.probe.only (comma-separated item ids; default every block item).
 */
@Mod("modaccel_pack_probe")
public final class ModaccelPackProbe {
    static final int COLUMNS = 64, SPACING = 3;

    public ModaccelPackProbe() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        // The opener is built only into the client probe; a server probe has no client classes to call.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
            try { Class.forName("modaccel.packprobe.ModaccelPackOpener").getDeclaredMethod("register").invoke(null); }
            catch (ReflectiveOperationException absent) { System.out.println("MODACCEL-PROBE-NO-OPENER"); }
    }

    final JsonObject result = new JsonObject();
    MinecraftServer server;
    Iterator<Item> items; Placement placement; Set<Long> area; long waitingSince;
    boolean finished;

    void started(ServerStartedEvent event) {
        server = event.getServer();
        String mode = System.getProperty("modaccel.probe.mode", "place");
        result.addProperty("mode", mode);
        result.add("mods", mods());
        result.add("dimensions", dimensions(server));
        System.out.println("MODACCEL-PROBE-STARTED " + mode);
        // The world opened. Load the test area through the normal chunk system before touching it, so no
        // block update in the middle of a tick has to wait for chunk generation.
        ServerLevel level = server.overworld();
        BlockPos origin = origin(level);
        area = new HashSet<>();
        int rows = rows();
        result.addProperty("gridRows", rows);
        for (int x = (origin.getX() >> 4) - 1; x <= (origin.getX() + COLUMNS * SPACING >> 4) + 1; x++)
            for (int z = (origin.getZ() >> 4) - 1; z <= (origin.getZ() + rows * SPACING >> 4) + 1; z++) {
                level.setChunkForced(x, z, true); area.add(net.minecraft.world.level.ChunkPos.asLong(x, z));
            }
        waitingSince = System.nanoTime();
    }

    void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (server == null || finished || event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        ServerLevel level = server.overworld();
        try {
            if (area != null) {
                for (long chunk : area) if (!level.hasChunk(net.minecraft.world.level.ChunkPos.getX(chunk), net.minecraft.world.level.ChunkPos.getZ(chunk))) {
                    if (System.nanoTime() - waitingSince > 300_000_000_000L) throw new IllegalStateException("The test area's chunks did not load in 300 s");
                    return;
                }
                result.addProperty("areaLoadSeconds", (System.nanoTime() - waitingSince) / 1e9);
                area = null;
                if (System.getProperty("modaccel.probe.mode", "place").equals("verify")) { verify(server, result); finish(true); return; }
                placement = new Placement(level, result);
                items = BuiltInRegistries.ITEM.iterator();
            }
            long budget = System.nanoTime() + 40_000_000L;
            while (items.hasNext() && System.nanoTime() < budget) placement.place(items.next());
            if (!items.hasNext()) { placement.finish(); finish(true); }
        } catch (Throwable failure) {
            result.addProperty("error", stack(failure));
            finish(false);
        }
    }

    void finish(boolean ok) {
        finished = true;
        result.addProperty("ok", ok);
        try {
            Path out = Path.of(System.getProperty("modaccel.probe.out", "modaccel-probe.json"));
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            System.out.println("MODACCEL-PROBE-DONE " + out.toAbsolutePath());
        } catch (IOException unwritable) { unwritable.printStackTrace(); }
        // A normal stop: the world is saved, so the next start can read it back. In singleplayer, halting the built-in
        // server leaves the game on its saving screen, so the client closes itself, which saves and stops that server.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
            try { Class.forName("modaccel.packprobe.ModaccelPackOpener").getDeclaredMethod("finished").invoke(null); return; }
            catch (ReflectiveOperationException absent) { System.out.println("MODACCEL-PROBE-NO-OPENER"); }
        server.halt(false);
    }

    static BlockPos origin(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        return new BlockPos(spawn.getX() - COLUMNS * SPACING / 2, level.getMaxBuildHeight() - 8, spawn.getZ() - COLUMNS * SPACING / 2);
    }

    static Set<String> only() {
        Set<String> only = new HashSet<>();
        for (String id : System.getProperty("modaccel.probe.only", "").split(",")) if (!id.isBlank()) only.add(id.strip());
        return only;
    }
    static boolean chosen(Item item, Set<String> only) {
        return item instanceof BlockItem && (only.isEmpty() || only.contains(BuiltInRegistries.ITEM.getKey(item).toString()));
    }
    /** Rows of the test grid: one cell per chosen block item. */
    static int rows() {
        Set<String> only = only(); int count = 0;
        for (Item item : BuiltInRegistries.ITEM) if (chosen(item, only)) count++;
        return Math.max(1, (count + COLUMNS - 1) / COLUMNS);
    }

    /** One block item at a time, a few per tick. */
    static final class Placement {
        final ServerLevel level; final JsonObject result; final FakePlayer player; final Set<String> only = only(); final BlockPos origin;
        final JsonArray different = new JsonArray(), unplaced = new JsonArray(), failed = new JsonArray(), placed = new JsonArray();
        int tried, same, cell;
        // Things a support can be, tried in order until the block lands: most blocks, then plants, then crops.
        final Block[] supports = { Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.FARMLAND, Blocks.SAND };
        Placement(ServerLevel level, JsonObject result) { this.level = level; this.result = result; player = FakePlayerFactory.getMinecraft(level); origin = origin(level); }
        void place(Item item) {
            if (!chosen(item, only)) return;
            BlockItem blockItem = (BlockItem) item;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            tried++;
            Block expected = blockItem.getBlock();
            String expectedId = BuiltInRegistries.BLOCK.getKey(expected).toString();
            JsonObject row = new JsonObject();
            row.addProperty("item", id);
            row.addProperty("itemMod", BuiltInRegistries.ITEM.getKey(item).getNamespace());
            row.addProperty("expected", expectedId);
            try {
                BlockState landed = null; BlockPos support = null; String used = null; InteractionResult answer = null;
                for (Block base : supports) {
                    support = origin.offset((cell % COLUMNS) * SPACING, 0, (cell / COLUMNS) * SPACING);
                    clear(level, support);
                    level.setBlock(support, base.defaultBlockState(), Block.UPDATE_ALL);
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    player.moveTo(support.getX() + 0.5, support.getY() + 1, support.getZ() + 2.5, 180f, 45f);
                    BlockHitResult hit = new BlockHitResult(new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5), Direction.UP, support, false);
                    answer = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
                    BlockState above = level.getBlockState(support.above());
                    used = BuiltInRegistries.BLOCK.getKey(base).toString();
                    if (!above.isAir()) { landed = above; break; }
                    BlockState after = level.getBlockState(support);
                    if (after.getBlock() != base) { landed = after; row.addProperty("replacedSupport", true); break; }
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                row.addProperty("support", used);
                row.addProperty("answer", String.valueOf(answer));
                if (landed == null) { unplaced.add(row); clear(level, support); return; }
                String landedId = BuiltInRegistries.BLOCK.getKey(landed.getBlock()).toString();
                row.addProperty("landed", landedId);
                row.addProperty("state", landed.toString());
                BlockPos at = row.has("replacedSupport") ? support : support.above();
                row.addProperty("x", at.getX()); row.addProperty("y", at.getY()); row.addProperty("z", at.getZ());
                if (landed.getBlock() == expected) same++; else different.add(row);
                placed.add(row);
                cell++;
            } catch (Throwable failure) {
                row.addProperty("error", stack(failure));
                failed.add(row);
                cell++;
            }
        }
        void finish() {
            // Blocks keep changing while the world runs (coral dries, cacti break, an item that spawns a structure
            // covers its neighbours). What the save must keep is each block as it is now, just before the save.
            JsonArray changedWhileRunning = new JsonArray();
            for (JsonElement element : placed) {
                JsonObject row = element.getAsJsonObject();
                BlockState now = level.getBlockState(new BlockPos(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt()));
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                row.addProperty("saved", nowId);
                if (!nowId.equals(row.get("landed").getAsString())) changedWhileRunning.add(row.get("item").getAsString() + ": " + row.get("landed").getAsString() + " -> " + nowId);
            }
            result.add("changedWhileRunning", changedWhileRunning);
            result.addProperty("blockItems", tried);
            result.addProperty("landedAsExpected", same);
            result.add("different", different);
            result.add("unplaced", unplaced);
            result.add("failed", failed);
            result.add("placed", placed);
        }
    }

    static void verify(MinecraftServer server, JsonObject result) throws IOException {
        ServerLevel level = server.overworld();
        JsonObject before = JsonParser.parseString(Files.readString(Path.of(System.getProperty("modaccel.probe.placed")), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray changed = new JsonArray();
        int read = 0, kept = 0;
        for (JsonElement element : before.getAsJsonArray("placed")) {
            JsonObject row = element.getAsJsonObject();
            BlockPos at = new BlockPos(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt());
            BlockState now = level.getBlockState(at);
            read++;
            String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
            String saved = row.get("saved").getAsString();
            if (nowId.equals(saved)) { kept++; continue; }
            JsonObject change = new JsonObject();
            change.addProperty("item", row.get("item").getAsString());
            change.addProperty("before", saved);
            change.addProperty("after", nowId);
            change.addProperty("state", now.toString());
            changed.add(change);
        }
        result.addProperty("read", read);
        result.addProperty("kept", kept);
        result.add("changed", changed);
    }

    /**
     * Each dimension written the way level.dat stores it and read back the way the next start reads it.
     * A dimension that writes but cannot be read stops the world from opening again.
     */
    static JsonArray dimensions(MinecraftServer server) {
        JsonArray out = new JsonArray();
        var access = server.registryAccess();
        var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, access);
        var stems = access.registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM);
        for (var entry : stems.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", entry.getKey().location().toString());
            try {
                var written = net.minecraft.world.level.dimension.LevelStem.CODEC.encodeStart(ops, entry.getValue());
                if (written.error().isPresent()) {
                    // The game still saves what it could write, and the next start rejects it: name the entries.
                    String message = written.error().get().message();
                    row.addProperty("write", message.length() > 600 ? message.substring(0, 600) + "..." : message);
                    written.resultOrPartial(ignored -> { }).ifPresent(partial -> row.add("outOfRangeBiomes", outOfRange(partial)));
                    out.add(row); continue;
                }
                net.minecraft.nbt.Tag tag = written.result().get();
                row.addProperty("savedBytes", tag.toString().length());
                var read = net.minecraft.world.level.dimension.LevelStem.CODEC.parse(ops, tag);
                if (read.error().isPresent()) row.addProperty("read", read.error().get().message());
                // Find the smallest saved part that fails on its own, so the report names it.
                if (read.error().isPresent()) row.addProperty("failingPart", failingPart(ops, tag));
            } catch (Throwable failure) { row.addProperty("error", stack(failure)); }
            out.add(row);
        }
        return out;
    }

    /** Biome entries of a multi-noise list whose climate values fall outside what the game reads back. */
    static JsonArray outOfRange(net.minecraft.nbt.Tag tag) {
        JsonArray found = new JsonArray(); Set<String> seen = new HashSet<>();
        outOfRange(tag, found, seen);
        return found;
    }
    static void outOfRange(net.minecraft.nbt.Tag tag, JsonArray found, Set<String> seen) {
        if (tag instanceof net.minecraft.nbt.ListTag list) { for (var child : list) outOfRange(child, found, seen); return; }
        if (!(tag instanceof net.minecraft.nbt.CompoundTag compound)) return;
        if (compound.contains("biome") && compound.get("parameters") instanceof net.minecraft.nbt.CompoundTag parameters) {
            for (String key : parameters.getAllKeys()) {
                double limit = key.equals("offset") ? 1 : 2, low = key.equals("offset") ? 0 : -2;
                List<Double> values = new ArrayList<>();
                var value = parameters.get(key);
                if (value instanceof net.minecraft.nbt.NumericTag number) values.add(number.getAsDouble());
                if (value instanceof net.minecraft.nbt.ListTag pair) for (var v : pair) if (v instanceof net.minecraft.nbt.NumericTag n) values.add(n.getAsDouble());
                for (double v : values) if (v < low || v > limit) {
                    String biome = compound.getString("biome");
                    if (seen.add(biome)) found.add(biome + " " + key + "=" + v);
                }
            }
            return;
        }
        for (String key : compound.getAllKeys()) outOfRange(compound.get(key), found, seen);
    }

    /** The path inside a saved dimension whose own codec rejects it: generator, then biome_source, then settings. */
    static String failingPart(net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops, net.minecraft.nbt.Tag stem) {
        if (!(stem instanceof net.minecraft.nbt.CompoundTag compound) || !(compound.get("generator") instanceof net.minecraft.nbt.CompoundTag generator)) return "";
        StringBuilder found = new StringBuilder();
        if (generator.contains("biome_source")) {
            var read = net.minecraft.world.level.biome.BiomeSource.CODEC.parse(ops, generator.get("biome_source"));
            if (read.error().isPresent()) found.append("generator.biome_source (").append(generator.getCompound("biome_source").getString("type")).append("): ").append(read.error().get().message()).append('\n');
        }
        if (generator.contains("settings")) {
            var read = net.minecraft.world.level.levelgen.NoiseGeneratorSettings.CODEC.parse(ops, generator.get("settings"));
            if (read.error().isPresent()) found.append("generator.settings: ").append(read.error().get().message()).append('\n');
        }
        String text = found.toString();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    static void clear(ServerLevel level, BlockPos support) {
        for (int dy = 0; dy <= 2; dy++) level.setBlock(support.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    static JsonArray mods() {
        JsonArray mods = new JsonArray();
        net.minecraftforge.fml.ModList.get().forEachModFile(file -> file.getModInfos().forEach(info -> mods.add(info.getModId() + " " + info.getVersion())));
        return mods;
    }

    static String stack(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text));
        String all = text.toString(); return all.length() > 4000 ? all.substring(0, 4000) : all;
    }
}
