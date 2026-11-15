package mmt.packprobe;

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
 * MmtPackProbeFabric, which calls start().
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
 * Properties: mmt.probe.mode (place|verify|open), mmt.probe.out (result JSON), mmt.probe.placed
 * (the place run's list, read by verify), mmt.probe.only (comma-separated item ids; default every block item).
 */
@net.minecraftforge.fml.common.Mod("mmt_pack_probe")
@net.neoforged.fml.common.Mod("mmt_pack_probe")
public final class MmtPackProbe {
    static final int COLUMNS = 64, SPACING = 3;
    /** Block.setBlock flags, the same numbers in every version (1.16 does not name them): update neighbours and clients, clients only, no shape updates. */
    static final int UPDATE_ALL = 3, UPDATE_CLIENTS = 2, UPDATE_KNOWN_SHAPE = 16;
    static Work running;

    public MmtPackProbe() { start(); }

    /** Once per game, whichever loader calls first. */
    static synchronized void start() {
        if (running == null) running = new Work();
    }

    static final class Work {
        final JsonObject result = new JsonObject();
        MinecraftServer server;
        Iterator<Item> items; Placement placement; Set<Long> area; Deque<Long> toForce; long waitingSince, lastProgress = System.nanoTime();
        volatile boolean finished;

        Work() {
            Thread watcher = new Thread(this::watch, "mmt-pack-probe");
            watcher.setDaemon(true);
            watcher.start();
        }

        void watch() {
            try {
                long since = System.nanoTime();
                String mode = System.getProperty("mmt.probe.mode", "place");
                boolean fingerprint = mode.startsWith("fingerprint");
                boolean frozen = false;
                while ((server = findServer()) == null || !MmtPackGame.ready(server) || server.getTickCount() < 1) {
                    if (server != null && !server.isRunning() && server.getTickCount() > 0) return;
                    // The fingerprint's world is frozen from its first tick: the freeze is queued the moment the server exists.
                    if (fingerprint && server != null && !frozen) frozen = fingerprint("freezeAtLoad", null) != null;
                    Thread.sleep(fingerprint ? 2 : 250);
                    if (System.nanoTime() - since > TimeUnit.MINUTES.toNanos(60)) return;
                }
                if (fingerprint) {
                    // The behaviour fingerprint (MmtFingerprint), run from this thread; it writes its own record.
                    result.addProperty("mode", mode);
                    result.addProperty("fingerprint", String.valueOf(fingerprint(mode.equals("fingerprint") ? "content" : "reload", System.getProperty("mmt.fingerprint.args", "{}"))));
                    finish(true);
                    return;
                }
                onServer(this::started);
                while (!finished) {
                    if (!server.isRunning()) return;
                    onServer(this::step);
                    Thread.sleep(10);
                }
            } catch (Throwable failure) {
                if (!finished) {
                    result.addProperty("error", stack(failure));
                    finish(false);
                }
            }
        }

        /**
         * Runs work on the server thread and waits for it as long as the server runs: a big pack on a busy machine can hold
         * its server thread for many minutes (chunk after chunk loaded in one tick), and that is pack-check's to judge from
         * the log, not the probe's to give up on.
         */
        void onServer(Runnable work) throws Exception {
            CompletableFuture<Void> done = CompletableFuture.runAsync(work, server);
            while (true) {
                try { done.get(1, TimeUnit.MINUTES); return; }
                catch (java.util.concurrent.TimeoutException busy) { if (!server.isRunning()) return; }
            }
        }

        void started() {
            String mode = System.getProperty("mmt.probe.mode", "place");
            result.addProperty("mode", mode);
            result.addProperty("loader", loader());
            result.add("mods", mods());
            result.add("dimensions", MmtPackGame.dimensions(server));
            System.out.println("MMT-PROBE-STARTED " + mode);
            if (mode.equals("open")) { finish(true); return; }
            // The world opened. Load the test area through the normal chunk system before touching it, so no
            // block update in the middle of a tick has to wait for chunk generation.
            ServerLevel level = server.overworld();
            // A level with no players and no saved forced chunk stops ticking block entities 300 ticks after it empties, and
            // with them BlockEntity.onLoad for every chunk that loads later. The test area's tickets (forceChunk) are not saved
            // forced chunks, so a big pack whose probe began past those 300 ticks loaded its grid into a level that never called
            // onLoad: Ender IO 8.2.13's capacitor bank, whose caches onLoad makes, then stopped All the Mods 10's reopen. The
            // spawn chunk, already loaded, is forced the saved way, so the level ticks like one a player is in.
            BlockPos spawn = MmtPackGame.spawn(level);
            level.setChunkForced(spawn.getX() >> 4, spawn.getZ() >> 4, true);
            BlockPos origin = origin(level);
            area = new HashSet<>();
            int rows = rows();
            result.addProperty("gridRows", rows);
            // Forcing a chunk generates it on the server thread: a big pack's grid is over a thousand chunks, which in one task
            // ran past the watchdog's 300 s. They are forced a few per tick in step(), and the 300 s wait starts after.
            toForce = new ArrayDeque<>();
            for (int x = (origin.getX() >> 4) - 1; x <= (origin.getX() + COLUMNS * SPACING >> 4) + 1; x++)
                for (int z = (origin.getZ() >> 4) - 1; z <= (origin.getZ() + rows * SPACING >> 4) + 1; z++) {
                    toForce.add(MmtPackGame.chunkKey(x, z)); area.add(MmtPackGame.chunkKey(x, z));
                }
            result.addProperty("areaChunks", area.size());
            waitingSince = System.nanoTime();
        }

        void step() {
            if (finished) return;
            ServerLevel level = server.overworld();
            try {
                if (toForce != null && !toForce.isEmpty()) {
                    long budget = System.nanoTime() + 40_000_000L;
                    do {
                        long chunk = toForce.poll();
                        MmtPackGame.forceChunk(level, net.minecraft.world.level.ChunkPos.getX(chunk), net.minecraft.world.level.ChunkPos.getZ(chunk));
                    } while (!toForce.isEmpty() && System.nanoTime() < budget);
                    if (System.nanoTime() - lastProgress > 30_000_000_000L) {
                        lastProgress = System.nanoTime();
                        System.out.println("MMT-PROBE-PROGRESS loading the test area: " + (area.size() - toForce.size()) + " of " + area.size() + " chunks");
                    }
                    waitingSince = System.nanoTime();
                    return;
                }
                if (area != null) {
                    for (long chunk : area) if (!level.hasChunk(net.minecraft.world.level.ChunkPos.getX(chunk), net.minecraft.world.level.ChunkPos.getZ(chunk))) {
                        if (System.nanoTime() - waitingSince > 300_000_000_000L) throw new IllegalStateException("The test area's chunks did not load in 300 s");
                        if (System.nanoTime() - lastProgress > 30_000_000_000L) {
                            lastProgress = System.nanoTime();
                            System.out.println("MMT-PROBE-PROGRESS loading the test area");
                        }
                        return;
                    }
                    result.addProperty("areaLoadSeconds", (System.nanoTime() - waitingSince) / 1e9);
                    area = null;
                    if (System.getProperty("mmt.probe.mode", "place").equals("verify")) { verify(server, result); finish(true); return; }
                    placement = new Placement(level, result);
                    items = MmtPackGame.items().iterator();
                }
                long budget = System.nanoTime() + 40_000_000L;
                while (items.hasNext() && System.nanoTime() < budget) placement.place(items.next());
                // A line every 30 s while placing, so pack-check sees a long run of a big pack is still working.
                if (System.nanoTime() - lastProgress > 30_000_000_000L) {
                    lastProgress = System.nanoTime();
                    System.out.println("MMT-PROBE-PROGRESS " + placement.tried + " block items placed");
                }
                if (!items.hasNext()) { placement.finish(); finish(true); }
            } catch (Throwable failure) {
                result.addProperty("error", stack(failure));
                finish(false);
            }
        }

        /** An entry of MmtFingerprint, which is in the probe jar when its game layer compiled for this version. */
        Object fingerprint(String entry, String arguments) throws Exception {
            Class<?> type;
            try { type = Class.forName("mmt.packprobe.MmtFingerprint"); }
            catch (ClassNotFoundException absent) { throw new IllegalStateException("This probe was built without the fingerprint (no layer for this game version)"); }
            if (arguments == null) return type.getMethod(entry, Object.class).invoke(null, server);
            try { return type.getMethod(entry, Object.class, String.class).invoke(null, server, arguments); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause() instanceof Exception ? (Exception) failed.getCause() : failed; }
        }

        synchronized void finish(boolean ok) {
            if (finished) return;
            finished = true;
            result.addProperty("ok", ok);
            try {
                Path out = Paths.get(System.getProperty("mmt.probe.out", "mmt-probe.json"));
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8));
                System.out.println("MMT-PROBE-DONE " + out.toAbsolutePath());
            } catch (IOException unwritable) { unwritable.printStackTrace(); }
            // A normal stop: the world is saved, so the next start can read it back. In singleplayer, halting the built-in
            // server leaves the game on its saving screen, so the client closes itself, which saves and stops that server.
            if (server != null && !server.isDedicatedServer())
                try { Class.forName("mmt.packprobe.MmtPackOpener").getDeclaredMethod("finished").invoke(null); return; }
                catch (ReflectiveOperationException | LinkageError absent) { System.out.println("MMT-PROBE-NO-OPENER"); }
            if (server != null) server.halt(false);
        }
    }

    /**
     * Where Forge and NeoForge keep the running server: NeoForge's ServerLifecycleHooks, Forge's from 1.17, and Forge's
     * for 1.16 and before (net.minecraftforge.fml.server, as forge-1.16.5-36.2.42-universal.jar has it).
     */
    static final List<String> SERVER_HOOKS = Arrays.asList(
        "net.neoforged.neoforge.server.ServerLifecycleHooks",
        "net.minecraftforge.server.ServerLifecycleHooks",
        "net.minecraftforge.fml.server.ServerLifecycleHooks"
    );

    /** The running server, from whichever loader is present: Forge and NeoForge keep it in ServerLifecycleHooks, Fabric and Quilt in FabricLoader. */
    static MinecraftServer findServer() {
        for (String hooks : SERVER_HOOKS) {
            Object server = call(hooks, "getCurrentServer");
            if (server instanceof MinecraftServer) return (MinecraftServer) server;
        }
        Object fabric = call("net.fabricmc.loader.api.FabricLoader", "getInstance");
        if (fabric != null) try {
            Object game = invoke(fabric, "getGameInstance");
            if (game instanceof MinecraftServer) return (MinecraftServer) game;
        } catch (ReflectiveOperationException | RuntimeException notYet) { /* the game instance is set once the server is built */ }
        return null;
    }

    /** A no-argument method called through the public type that declares it: loaders hide their implementation classes. */
    static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Deque<Class<?>> types = new ArrayDeque<>(Collections.singletonList(target.getClass()));
        while (!types.isEmpty()) {
            Class<?> type = types.poll();
            if (java.lang.reflect.Modifier.isPublic(type.getModifiers())) try { return type.getMethod(name).invoke(target); }
            catch (NoSuchMethodException | IllegalAccessException notHere) { /* a supertype */ }
            if (type.getSuperclass() != null) types.add(type.getSuperclass());
            types.addAll(Arrays.asList(type.getInterfaces()));
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    static Object call(String type, String method) {
        try { return Class.forName(type, true, MmtPackProbe.class.getClassLoader()).getMethod(method).invoke(null); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException absent) { return null; }
    }

    static String loader() {
        for (String[] known : new String[][] { { "net.neoforged.fml.ModList", "neoforge" }, { "net.minecraftforge.fml.ModList", "forge" }, { "org.quiltmc.loader.api.QuiltLoader", "quilt" }, { "net.fabricmc.loader.api.FabricLoader", "fabric" } })
            try { Class.forName(known[0], false, MmtPackProbe.class.getClassLoader()); return known[1]; }
            catch (ClassNotFoundException | LinkageError absent) { /* next */ }
        return "unknown";
    }

    /** A player the server treats as real for placing blocks: the loader's own fake player where it has one. */
    static ServerPlayer fakePlayer(ServerLevel level) throws ReflectiveOperationException {
        for (String[] factory : new String[][] { { "net.neoforged.neoforge.common.util.FakePlayerFactory", "getMinecraft" }, { "net.minecraftforge.common.util.FakePlayerFactory", "getMinecraft" }, { "net.fabricmc.fabric.api.entity.FakePlayer", "get" } }) {
            try {
                Class<?> type = Class.forName(factory[0], true, MmtPackProbe.class.getClassLoader());
                for (Method method : type.getMethods())
                    if (method.getName().equals(factory[1]) && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(level))
                        return (ServerPlayer) method.invoke(null, level);
            } catch (ClassNotFoundException | LinkageError absent) { /* next */ }
        }
        // No loader fake player (a Fabric pack without Fabric API): a plain player that is never added to the world.
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.fromString("41c82c87-7afb-4024-ba57-13d2c99cae77"), "[mmt]");
        for (java.lang.reflect.Constructor<?> constructor : ServerPlayer.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 3) return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile);
            if (types.length != 4) continue;
            // The fourth argument by its type, since run-time names differ by loader: a default made by a static
            // factory (client information, 1.20.2+), a game mode handler made from the level (before 1.17), or nothing
            // (1.19's profile public key, which an offline player does not have).
            for (Method made : types[3].getMethods())
                if (java.lang.reflect.Modifier.isStatic(made.getModifiers()) && made.getParameterCount() == 0 && made.getReturnType() == types[3])
                    return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile, made.invoke(null));
            for (java.lang.reflect.Constructor<?> mode : types[3].getConstructors())
                if (mode.getParameterCount() == 1 && mode.getParameterTypes()[0].isInstance(level))
                    return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile, mode.newInstance(level));
            try { return (ServerPlayer) constructor.newInstance(level.getServer(), level, profile, null); }
            catch (ReflectiveOperationException | RuntimeException refused) { /* the next constructor */ }
        }
        throw new IllegalStateException("no way to make a player for placing blocks");
    }

    static BlockPos origin(ServerLevel level) {
        BlockPos spawn = MmtPackGame.spawn(level);
        int top = MmtPackGame.top(level);
        return new BlockPos(spawn.getX() - COLUMNS * SPACING / 2, top - 8, spawn.getZ() - COLUMNS * SPACING / 2);
    }

    static Set<String> only() {
        Set<String> only = new HashSet<>();
        for (String id : System.getProperty("mmt.probe.only", "").split(",")) if (!id.trim().isEmpty()) only.add(id.trim());
        return only;
    }
    static boolean chosen(Item item, Set<String> only) {
        return item instanceof BlockItem && (only.isEmpty() || only.contains(MmtPackGame.itemId(item)));
    }
    /** Rows of the test grid: one cell per chosen block item. */
    static int rows() {
        Set<String> only = only(); int count = 0;
        for (Item item : MmtPackGame.items()) if (chosen(item, only)) count++;
        return Math.max(1, (count + COLUMNS - 1) / COLUMNS);
    }

    /** One block item at a time, a few per slice. */
    static final class Placement {
        final ServerLevel level; final JsonObject result; final ServerPlayer player; final Set<String> only = only(); final BlockPos origin;
        final JsonArray different = new JsonArray(), unplaced = new JsonArray(), failed = new JsonArray(), placed = new JsonArray(), unsendable = new JsonArray();
        int tried, same, cell, hidden;
        // Surfaces tried in order until the block lands: the top of stone (most blocks), grass (plants), farmland
        // (crops) and sand (cacti), then a wall face, a ceiling, and sand under water.
        static final String[] SURFACES = { "minecraft:stone", "minecraft:grass_block", "minecraft:farmland", "minecraft:sand", "wall", "ceiling", "water" };
        Placement(ServerLevel level, JsonObject result) throws ReflectiveOperationException { this.level = level; this.result = result; player = fakePlayer(level); origin = origin(level); }
        static Block ground(String surface) {
            if (surface.equals("minecraft:grass_block")) return Blocks.GRASS_BLOCK;
            if (surface.equals("minecraft:farmland")) return Blocks.FARMLAND;
            if (surface.equals("minecraft:sand") || surface.equals("water")) return Blocks.SAND;
            return Blocks.STONE;
        }
        void place(Item item) {
            if (!chosen(item, only)) return;
            // An item in no creative tab is not one a player places (unless asked for by --only): left alone, and counted.
            if (only.isEmpty() && !MmtPackGame.listed(item)) { hidden++; return; }
            // A developer's debug block (Applied Energistics 2's debug chunk loader loads chunks while its own chunk loads, which
            // hangs the next start) is a tool for the mod's author, not pack content.
            if (only.isEmpty() && MmtPackGame.itemId(item).split(":", 2)[1].matches("(.*_)?debug(_.*)?")) { hidden++; return; }
            BlockItem blockItem = (BlockItem) item;
            String id = MmtPackGame.itemId(item);
            tried++;
            Block expected = blockItem.getBlock();
            String expectedId = MmtPackGame.blockId(expected);
            JsonObject row = new JsonObject();
            row.addProperty("item", id);
            row.addProperty("itemMod", MmtPackGame.itemNamespace(item));
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
                    if (surface.equals("wall")) {   // the north face of a stone block: torches, ladders, wall signs, vines
                        target = support.north(); face = Direction.NORTH;
                        point = new Vec3(support.getX() + 0.5, support.getY() + 0.5, support.getZ());
                        standing = new Vec3(support.getX() + 0.5, support.getY(), support.getZ() - 1.5); yaw = 0f; pitch = 0f;
                    } else if (surface.equals("ceiling")) {   // the underside of a stone block: hanging signs, spore blossoms, weeping vines
                        clicked = support.above(2); face = Direction.DOWN;
                        point = new Vec3(support.getX() + 0.5, support.getY() + 2, support.getZ() + 0.5); pitch = -45f;
                        level.setBlock(clicked, Blocks.STONE.defaultBlockState(), UPDATE_ALL);
                    } else if (surface.equals("water")) {   // sand under still water: seagrass, kelp, coral
                        point = new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5);
                        // Glass round the water, or a water plant (a water source itself) floods the neighbouring cells.
                        for (Direction side : Direction.Plane.HORIZONTAL) if (level.getBlockState(target.relative(side)).isAir()) level.setBlock(target.relative(side), Blocks.GLASS.defaultBlockState(), UPDATE_ALL);
                        level.setBlock(target, Blocks.WATER.defaultBlockState(), UPDATE_ALL);
                    } else point = new Vec3(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5);   // the top of a block
                    level.setBlock(support, base.defaultBlockState(), UPDATE_ALL);
                    BlockState before = level.getBlockState(target);
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    MmtPackGame.moveTo(player, standing.x, standing.y, standing.z, yaw, pitch);
                    answer = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(point, face, clicked, false));
                    used = surface.contains(":") ? surface : surface + " (" + MmtPackGame.blockId(base) + ")";
                    BlockState now = level.getBlockState(target);
                    if (now != before) { landed = now; at = target; break; }
                    BlockState after = level.getBlockState(support);
                    if (after.getBlock() != base) { landed = after; at = support; row.addProperty("replacedSupport", true); break; }
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                row.addProperty("support", used);
                row.addProperty("answer", String.valueOf(answer));
                if (landed == null) { unplaced.add(row); clear(level, support); return; }
                String landedId = MmtPackGame.blockId(landed.getBlock());
                row.addProperty("landed", landedId);
                row.addProperty("state", landed.toString());
                row.addProperty("x", at.getX()); row.addProperty("y", at.getY()); row.addProperty("z", at.getZ());
                // The next tick sends each changed block's block entity to players through this same call. A block whose entity
                // cannot write itself bare (a mod's portal or spectral block, normally placed by the mod with its data) stops the
                // whole server there: it is taken out again and named, so the rest of the pack is still checked.
                net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(at);
                if (entity != null) try { entity.getUpdatePacket(); }
                catch (Throwable broken) {
                    row.addProperty("error", stack(broken));
                    unsendable.add(row);
                    level.removeBlockEntity(at);
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
                    clear(level, support);
                    cell++;
                    return;
                }
                // An item with a wall or hanging form (torch, sign) registers both blocks as its own: either is the item's block.
                if (landed.getBlock() == expected || landed.getBlock().asItem() == item) same++;
                else {
                    // The item's own block built on the one that landed (Supplementaries' tater in a jar on Quark's tiny potato):
                    // the item's mod chose the other block in its own compatibility code.
                    if (landed.getBlock().getClass().isAssignableFrom(expected.getClass())) row.addProperty("related", true);
                    different.add(row);
                }
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
                String nowId = MmtPackGame.blockId(now.getBlock());
                row.addProperty("saved", nowId);
                // A block still waiting for a scheduled tick when the world saves (coral out of water, a cactus beside a block)
                // changes by the game's own rules when that tick runs after the reload; the save is not what changed it.
                BlockPos at = new BlockPos(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt());
                try { if (level.getBlockTicks().hasScheduledTick(at, now.getBlock())) row.addProperty("ticking", true); }
                catch (Throwable unknown) { /* this game's tick list cannot say; the block is compared as it is */ }
                // A block that cannot survive where it stands (a shrub a mod lets you place on stone) breaks at its next block
                // update, whenever that comes: the game's survival rule, not the save, changes it.
                try { if (!now.canSurvive(level, at)) row.addProperty("ticking", true); }
                catch (Throwable unknown) { /* a block whose survival check needs more than the level: compared as it is */ }
                if (!nowId.equals(row.get("landed").getAsString())) changedWhileRunning.add(row.get("item").getAsString() + ": " + row.get("landed").getAsString() + " -> " + nowId);
            }
            result.add("changedWhileRunning", changedWhileRunning);
            result.addProperty("blockItems", tried);
            result.addProperty("hiddenBlockItems", hidden);
            result.addProperty("landedAsExpected", same);
            result.add("different", different);
            result.add("unplaced", unplaced);
            result.add("failed", failed);
            result.add("placed", placed);
            result.add("unsendable", unsendable);
        }
    }

    static void verify(MinecraftServer server, JsonObject result) throws IOException {
        ServerLevel level = server.overworld();
        // new JsonParser().parse: the Gson of 1.16 has no static parseString.
        @SuppressWarnings("deprecation")
        JsonObject before = new JsonParser().parse(new String(Files.readAllBytes(Paths.get(System.getProperty("mmt.probe.placed"))), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray changed = new JsonArray(), settled = new JsonArray();
        int read = 0, kept = 0;
        if (!before.has("placed")) throw new IllegalStateException("the first start placed nothing to read back" + (before.has("error") ? ": " + before.get("error").getAsString().split("\n")[0] : ""));
        for (JsonElement element : before.getAsJsonArray("placed")) {
            JsonObject row = element.getAsJsonObject();
            BlockPos at = new BlockPos(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt());
            BlockState now = level.getBlockState(at);
            read++;
            String nowId = MmtPackGame.blockId(now.getBlock());
            String saved = row.get("saved").getAsString();
            if (nowId.equals(saved)) { kept++; continue; }
            JsonObject change = new JsonObject();
            change.addProperty("item", row.get("item").getAsString());
            change.addProperty("before", saved);
            change.addProperty("after", nowId);
            change.addProperty("state", now.toString());
            if (row.has("ticking") && row.get("ticking").getAsBoolean()) settled.add(change); else changed.add(change);
        }
        result.addProperty("read", read);
        result.addProperty("kept", kept);
        result.add("changed", changed);
        result.add("settled", settled);
    }

    static void clear(ServerLevel level, BlockPos support) {
        // The cell: the support, the two blocks above it (target and ceiling), the wall target to its north, and the four
        // blocks round the target (the water attempt's glass). A cell is reused after an item that did not place, and
        // anything left there would give the next item a wall to hang on or take the room a bed needs.
        for (int dy = 0; dy <= 2; dy++) level.setBlock(support.above(dy), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
        level.setBlock(support.north(), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
        for (Direction side : Direction.Plane.HORIZONTAL) level.setBlock(support.above().relative(side), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
    }

    /** Every loaded mod and its version, from whichever loader is present. */
    static JsonArray mods() {
        JsonArray mods = new JsonArray();
        ClassLoader loader = MmtPackProbe.class.getClassLoader();
        try {
            for (String list : Arrays.asList("net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList")) {
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
