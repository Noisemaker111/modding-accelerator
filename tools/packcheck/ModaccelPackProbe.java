package modaccel.packprobe;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * pack-check's probe, loaded into a real modded server on any loader. Written in Mojang names against the vanilla
 * game only; pack-check renames it to the loader's runtime names (none, SRG or intermediary) before it goes in the
 * mods folder. Nothing here names a loader class: the loader is reached by reflection, so one source serves Forge,
 * NeoForge, Fabric and Quilt. Forge and NeoForge construct this class through @Mod; Fabric and Quilt call
 * ModaccelPackProbeFabric, which calls start().
 *
 * A watcher thread waits for the server to finish starting, then runs the work on the server thread in slices of
 * 40 ms, the way a tick handler would.
 *
 * place: every block item is used on a support block by a fake player through the same server path a real
 *   right-click takes (ServerPlayerGameMode.useItemOn, so other mods' events and mixins run), and the block that
 *   lands is compared with the block the item says it places. Placed blocks stay, and are listed.
 * verify: on the next start of the same world, each listed block is read back after the save and reload.
 * open: the world opened; only its dimensions are written and read back.
 *
 * Properties: modaccel.probe.mode (place|verify|open), modaccel.probe.out (result JSON), modaccel.probe.placed
 * (the place run's list, read by verify), modaccel.probe.only (comma-separated item ids; default every block item).
 */
@net.minecraftforge.fml.common.Mod("modaccel_pack_probe")
@net.neoforged.fml.common.Mod("modaccel_pack_probe")
public final class ModaccelPackProbe {
    static final int COLUMNS = 64, SPACING = 3;
    static Work running;

    public ModaccelPackProbe() { start(); }

    /** Once per game, whichever loader calls first. */
    static synchronized void start() {
        if (running == null) running = new Work();
    }

    static final class Work {
        final JsonObject result = new JsonObject();
        MinecraftServer server;
        Iterator<Item> items; Placement placement; Set<Long> area; long waitingSince;
        volatile boolean finished;

        Work() {
            Thread watcher = new Thread(this::watch, "modaccel-pack-probe");
            watcher.setDaemon(true);
            watcher.start();
        }

        void watch() {
            try {
                long since = System.nanoTime();
                while ((server = findServer()) == null || !server.isReady() || server.getTickCount() < 1) {
                    if (server != null && !server.isRunning() && server.getTickCount() > 0) return;
                    Thread.sleep(250);
                    if (System.nanoTime() - since > TimeUnit.MINUTES.toNanos(60)) return;
                }
                CompletableFuture.runAsync(this::started, server).get(10, TimeUnit.MINUTES);
                while (!finished) {
                    if (!server.isRunning()) return;
                    CompletableFuture.runAsync(this::step, server).get(10, TimeUnit.MINUTES);
                    Thread.sleep(10);
                }
            } catch (Throwable failure) {
                if (!finished) {
                    result.addProperty("error", stack(failure));
                    finish(false);
                }
            }
        }

        void started() {
            String mode = System.getProperty("modaccel.probe.mode", "place");
            result.addProperty("mode", mode);
            result.addProperty("loader", loader());
            result.add("mods", mods());
            result.add("dimensions", dimensions(server));
            System.out.println("MODACCEL-PROBE-STARTED " + mode);
            if (mode.equals("open")) { finish(true); return; }
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

        void step() {
            if (finished) return;
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

        synchronized void finish(boolean ok) {
            if (finished) return;
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
            if (server != null && !server.isDedicatedServer())
                try { Class.forName("modaccel.packprobe.ModaccelPackOpener").getDeclaredMethod("finished").invoke(null); return; }
                catch (ReflectiveOperationException | LinkageError absent) { System.out.println("MODACCEL-PROBE-NO-OPENER"); }
            if (server != null) server.halt(false);
        }
    }

    /** The running server, from whichever loader is present: Forge and NeoForge keep it in ServerLifecycleHooks, Fabric and Quilt in FabricLoader. */
    static MinecraftServer findServer() {
        for (String hooks : List.of("net.neoforged.neoforge.server.ServerLifecycleHooks", "net.minecraftforge.server.ServerLifecycleHooks")) {
            Object server = call(hooks, "getCurrentServer");
            if (server instanceof MinecraftServer found) return found;
        }
        Object fabric = call("net.fabricmc.loader.api.FabricLoader", "getInstance");
        if (fabric != null) try {
            if (invoke(fabric, "getGameInstance") instanceof MinecraftServer found) return found;
        } catch (ReflectiveOperationException | RuntimeException notYet) { /* the game instance is set once the server is built */ }
        return null;
    }

    /** A no-argument method called through the public type that declares it: loaders hide their implementation classes. */
    static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Deque<Class<?>> types = new ArrayDeque<>(List.of(target.getClass()));
        while (!types.isEmpty()) {
            Class<?> type = types.poll();
            if (java.lang.reflect.Modifier.isPublic(type.getModifiers())) try { return type.getMethod(name).invoke(target); }
            catch (NoSuchMethodException | IllegalAccessException notHere) { /* a supertype */ }
            if (type.getSuperclass() != null) types.add(type.getSuperclass());
            types.addAll(List.of(type.getInterfaces()));
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    static Object call(String type, String method) {
        try { return Class.forName(type, true, ModaccelPackProbe.class.getClassLoader()).getMethod(method).invoke(null); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException absent) { return null; }
    }

    static String loader() {
        for (String[] known : new String[][] { { "net.neoforged.fml.ModList", "neoforge" }, { "net.minecraftforge.fml.ModList", "forge" }, { "org.quiltmc.loader.api.QuiltLoader", "quilt" }, { "net.fabricmc.loader.api.FabricLoader", "fabric" } })
            try { Class.forName(known[0], false, ModaccelPackProbe.class.getClassLoader()); return known[1]; }
            catch (ClassNotFoundException | LinkageError absent) { /* next */ }
        return "unknown";
    }

    /** A player the server treats as real for placing blocks: the loader's own fake player where it has one. */
    static ServerPlayer fakePlayer(ServerLevel level) throws ReflectiveOperationException {
        for (String[] factory : new String[][] { { "net.neoforged.neoforge.common.util.FakePlayerFactory", "getMinecraft" }, { "net.minecraftforge.common.util.FakePlayerFactory", "getMinecraft" }, { "net.fabricmc.fabric.api.entity.FakePlayer", "get" } }) {
            try {
                Class<?> type = Class.forName(factory[0], true, ModaccelPackProbe.class.getClassLoader());
                for (Method method : type.getMethods())
                    if (method.getName().equals(factory[1]) && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(level))
                        return (ServerPlayer) method.invoke(null, level);
            } catch (ClassNotFoundException | LinkageError absent) { /* next */ }
        }
        // No loader fake player (a Fabric pack without Fabric API): a plain player that is never added to the world.
        var profile = new com.mojang.authlib.GameProfile(UUID.fromString("41c82c87-7afb-4024-ba57-13d2c99cae77"), "[modaccel]");
        for (var constructor : ServerPlayer.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 3) return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile);
            if (types.length == 4) for (Method made : types[3].getMethods())
                if (java.lang.reflect.Modifier.isStatic(made.getModifiers()) && made.getParameterCount() == 0 && made.getReturnType() == types[3])
                    return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile, made.invoke(null));
        }
        throw new IllegalStateException("no way to make a player for placing blocks");
    }

    static BlockPos origin(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int top = level.dimensionType().minY() + level.dimensionType().height() - 1;
        return new BlockPos(spawn.getX() - COLUMNS * SPACING / 2, top - 8, spawn.getZ() - COLUMNS * SPACING / 2);
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

    /** One block item at a time, a few per slice. */
    static final class Placement {
        final ServerLevel level; final JsonObject result; final ServerPlayer player; final Set<String> only = only(); final BlockPos origin;
        final JsonArray different = new JsonArray(), unplaced = new JsonArray(), failed = new JsonArray(), placed = new JsonArray();
        int tried, same, cell;
        // Surfaces tried in order until the block lands: the top of stone (most blocks), grass (plants), farmland
        // (crops) and sand (cacti), then a wall face, a ceiling, and sand under water.
        static final String[] SURFACES = { "minecraft:stone", "minecraft:grass_block", "minecraft:farmland", "minecraft:sand", "wall", "ceiling", "water" };
        Placement(ServerLevel level, JsonObject result) throws ReflectiveOperationException { this.level = level; this.result = result; player = fakePlayer(level); origin = origin(level); }
        static Block ground(String surface) {
            return switch (surface) {
                case "minecraft:grass_block" -> Blocks.GRASS_BLOCK;
                case "minecraft:farmland" -> Blocks.FARMLAND;
                case "minecraft:sand", "water" -> Blocks.SAND;
                default -> Blocks.STONE;
            };
        }
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
                BlockState landed = null; BlockPos support = origin.offset((cell % COLUMNS) * SPACING, 0, (cell / COLUMNS) * SPACING), at = null;
                String used = null; InteractionResult answer = null;
                for (String surface : SURFACES) {
                    clear(level, support);
                    // Each surface: what is built, which face the player right-clicks, and where the block should land.
                    BlockPos clicked = support, target = support.above(); Direction face = Direction.UP; Vec3 point;
                    float yaw = 180f, pitch = 45f; Vec3 standing = new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 2.5);
                    Block base = ground(surface);
                    switch (surface) {
                        case "wall" -> {   // the north face of a stone block: torches, ladders, wall signs, vines
                            target = support.north(); face = Direction.NORTH;
                            point = new Vec3(support.getX() + 0.5, support.getY() + 0.5, support.getZ());
                            standing = new Vec3(support.getX() + 0.5, support.getY(), support.getZ() - 1.5); yaw = 0f; pitch = 0f;
                        }
                        case "ceiling" -> {   // the underside of a stone block: hanging signs, spore blossoms, weeping vines
                            clicked = support.above(2); face = Direction.DOWN;
                            point = new Vec3(support.getX() + 0.5, support.getY() + 2, support.getZ() + 0.5); pitch = -45f;
                            level.setBlock(clicked, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        }
                        case "water" -> {   // sand under still water: seagrass, kelp, coral
                            point = new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5);
                            // Glass round the water, or a water plant (a water source itself) floods the neighbouring cells.
                            for (Direction side : Direction.Plane.HORIZONTAL) if (level.getBlockState(target.relative(side)).isAir()) level.setBlock(target.relative(side), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                            level.setBlock(target, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                        }
                        default -> point = new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5);   // the top of a block
                    }
                    level.setBlock(support, base.defaultBlockState(), Block.UPDATE_ALL);
                    BlockState before = level.getBlockState(target);
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    player.moveTo(standing.x, standing.y, standing.z, yaw, pitch);
                    answer = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(point, face, clicked, false));
                    used = surface.contains(":") ? surface : surface + " (" + BuiltInRegistries.BLOCK.getKey(base) + ")";
                    BlockState now = level.getBlockState(target);
                    if (now != before) { landed = now; at = target; break; }
                    BlockState after = level.getBlockState(support);
                    if (after.getBlock() != base) { landed = after; at = support; row.addProperty("replacedSupport", true); break; }
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                row.addProperty("support", used);
                row.addProperty("answer", String.valueOf(answer));
                if (landed == null) { unplaced.add(row); clear(level, support); return; }
                String landedId = BuiltInRegistries.BLOCK.getKey(landed.getBlock()).toString();
                row.addProperty("landed", landedId);
                row.addProperty("state", landed.toString());
                row.addProperty("x", at.getX()); row.addProperty("y", at.getY()); row.addProperty("z", at.getZ());
                // An item with a wall or hanging form (torch, sign) registers both blocks as its own: either is the item's block.
                if (landed.getBlock() == expected || landed.getBlock().asItem() == item) same++; else different.add(row);
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
        // The cell: the support, the two blocks above it (target and ceiling), the wall target to its north, and the four
        // blocks round the target (the water attempt's glass). A cell is reused after an item that did not place, and
        // anything left there would give the next item a wall to hang on or take the room a bed needs.
        for (int dy = 0; dy <= 2; dy++) level.setBlock(support.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        level.setBlock(support.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        for (Direction side : Direction.Plane.HORIZONTAL) level.setBlock(support.above().relative(side), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** Every loaded mod and its version, from whichever loader is present. */
    static JsonArray mods() {
        JsonArray mods = new JsonArray();
        ClassLoader loader = ModaccelPackProbe.class.getClassLoader();
        try {
            for (String list : List.of("net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList")) {
                Class<?> type;
                try { type = Class.forName(list, true, loader); } catch (ClassNotFoundException | LinkageError absent) { continue; }
                Object all = type.getMethod("get").invoke(null);
                for (Object info : (List<?>) invoke(all, "getMods")) mods.add(invoke(info, "getModId") + " " + invoke(info, "getVersion"));
                return mods;
            }
            Object fabric = call("net.fabricmc.loader.api.FabricLoader", "getInstance");
            if (fabric != null) for (Object container : (Collection<?>) invoke(fabric, "getAllMods")) {
                Object meta = invoke(container, "getMetadata");
                mods.add(invoke(meta, "getId") + " " + invoke(invoke(meta, "getVersion"), "getFriendlyString"));
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) { mods.add("(mod list unreadable: " + unreadable + ")"); }
        return mods;
    }

    static String stack(Throwable failure) {
        StringWriter text = new StringWriter(); failure.printStackTrace(new PrintWriter(text));
        String all = text.toString(); return all.length() > 4000 ? all.substring(0, 4000) : all;
    }
}
