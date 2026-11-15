package mmt.packprobe;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The behaviour fingerprint: one canonical record of what a mod does in a seeded, frozen world, the same on any mod and
 * any loader. Written in Mojang names against the vanilla game only, like pack-check's probe, and shipped in the same
 * probe jar; every call that changed between game versions is in MmtFingerprintGame (tools/packcheck/fingerprint/).
 * Runs on a dedicated server (inside pack-check's probe) or in a hidden client (loaded by mmt eval).
 *
 * Three entry points, each called from a thread that is not the server's, with a JSON argument:
 *   content: a new world. Every registry, then every block item and entity type of the target namespaces placed or
 *     spawned, containers given one stack, the world run for a fixed number of ticks with checkpoints, then saved.
 *   reload:  the saved world opened again: the same area read back, to compare with the state the save held.
 *   world:   an existing save (perf-optimize's experiment world) opened frozen: the area round every player run for a
 *     fixed number of ticks with checkpoints.
 *
 * Determinism. The world is frozen from its first tick where the game has a tick freeze (1.20.3 and later): blocks,
 * entities, block entities and scheduled ticks stand still, while chunks load. The scenario then runs every tick itself,
 * inside one task on the server thread, so nothing else interleaves: before the first tick every random source the
 * server, each level and each loaded entity hold is seeded, by its declaring class and order, from one seed.
 * What cannot be frozen is listed in FINGERPRINT.md: code a mod runs in its own server or level tick events while the
 * world is frozen, randomness a mod keeps in its own static fields or takes from ThreadLocalRandom, Math.random or the
 * clock, the order of hash maps keyed by identity, and work done on other threads (chunk generation, lighting).
 * Those show as elements two baseline runs disagree on, which a comparison then leaves out and names.
 */
public final class MmtFingerprint {
    static final int SCHEMA = 1;
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MmtFingerprint() {}

    // ----------------------------------------------------------------------------------------------- entry points

    /** An existing world opened frozen: the area round the players and spawn, run for args.ticks ticks. */
    public static String world(Object serverObject, String arguments) throws Exception {
        final MinecraftServer server = (MinecraftServer) serverObject;
        final JsonObject args = parse(arguments);
        final int radius = integer(args, "radius", 8);
        final JsonObject out = header("world", server, args);
        settle(server, radius, true, integer(args, "settleSeconds", 120), out);
        final Map<String, Set<Long>> area = onServer(server, new Callable<Map<String, Set<Long>>>() {
            public Map<String, Set<Long>> call() { return areaRound(server, radius); }
        });
        run(server, area, args, out);
        write(args, out);
        return summary(out);
    }

    /** A new world: registries, every target block item placed and entity spawned, then a fixed run, then saved. */
    public static String content(Object serverObject, String arguments) throws Exception {
        final MinecraftServer server = (MinecraftServer) serverObject;
        final JsonObject args = parse(arguments);
        final JsonObject out = header("content", server, args);
        final Set<String> namespaces = strings(args, "namespaces");
        out.add("registries", onServer(server, new Callable<JsonObject>() {
            public JsonObject call() { return registries(server); }
        }));
        final Content content = onServer(server, new Callable<Content>() {
            public Content call() throws Exception { return new Content(server, namespaces); }
        });
        out.add("targets", content.targets());
        long since = System.nanoTime();
        while (!onServer(server, new Callable<Boolean>() { public Boolean call() { return content.loaded(); } })) {
            if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(300)) throw new IllegalStateException("The fingerprint area did not load in 300 s");
            Thread.sleep(100);
        }
        while (!onServer(server, new Callable<Boolean>() { public Boolean call() throws Exception { return content.placeSome(); } })) Thread.sleep(5);
        out.add("placement", onServer(server, new Callable<JsonObject>() { public JsonObject call() { return content.finishPlacing(); } }));
        out.add("spawned", onServer(server, new Callable<JsonArray>() { public JsonArray call() { return content.spawn(); } }));
        run(server, content.area, args, out);
        onServer(server, new Callable<Boolean>() { public Boolean call() { return MmtFingerprintGame.save(server); } });
        out.add("area", areaJson(content.area));
        write(args, out);
        return summary(out);
    }

    /** The saved content world opened again: the area read back once its blocks and entities are loaded. */
    public static String reload(Object serverObject, String arguments) throws Exception {
        final MinecraftServer server = (MinecraftServer) serverObject;
        final JsonObject args = parse(arguments);
        final JsonObject out = header("reload", server, args);
        final Map<String, Set<Long>> area = areaFrom(args.getAsJsonObject("area"));
        long since = System.nanoTime();
        while (!onServer(server, new Callable<Boolean>() { public Boolean call() { return loaded(server, area, true); } })) {
            if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(300)) throw new IllegalStateException("The saved area did not load again in 300 s");
            Thread.sleep(100);
        }
        // Entities reach a loaded chunk a moment after it: wait until the count stops changing.
        int last = -1, same = 0;
        while (same < 4 && System.nanoTime() - since < TimeUnit.SECONDS.toNanos(300)) {
            int now = onServer(server, new Callable<Integer>() { public Integer call() { return entityCount(server, area); } });
            same = now == last ? same + 1 : 0;
            last = now;
            Thread.sleep(250);
        }
        out.add("reloaded", onServer(server, new Callable<JsonObject>() { public JsonObject call() { return state(server, area, 0); } }));
        write(args, out);
        return summary(out);
    }

    /**
     * Queues the tick freeze as the server's first task, from the moment the server exists: it runs while the server
     * loads its spawn area, before its first tick. False when the game has no tick freeze (before 1.20.3).
     */
    public static Boolean freezeAtLoad(Object serverObject) {
        final MinecraftServer server = (MinecraftServer) serverObject;
        if (!MmtFingerprintGame.canFreeze()) return Boolean.FALSE;
        server.execute(new Runnable() {
            public void run() { MmtFingerprintGame.freeze(server, true); }
        });
        return Boolean.TRUE;
    }

    // ----------------------------------------------------------------------------------------------- the run

    /**
     * Seeds every reachable random source, records checkpoint 0, then runs args.ticks full server ticks inside one
     * server task (so nothing else runs between them), recording a checkpoint every args.every ticks and the chunk
     * tickets of the area after every tick.
     */
    static void run(final MinecraftServer server, final Map<String, Set<Long>> area, final JsonObject args, final JsonObject out) throws Exception {
        final int ticks = integer(args, "ticks", 100), every = Math.max(1, integer(args, "every", 20));
        final long seed = args.has("seed") ? args.get("seed").getAsLong() : 20261115L;
        out.add("checkpoints", onServer(server, new Callable<JsonArray>() {
            public JsonArray call() {
                JsonArray checkpoints = new JsonArray();
                boolean wasFrozen = MmtFingerprintGame.frozen(server);
                out.addProperty("frozenAtStart", wasFrozen);
                out.addProperty("seeded", reseed(server, seed));
                Map<String, Map<Long, TreeSet<String>>> seen = new TreeMap<String, Map<Long, TreeSet<String>>>();
                checkpoints.add(state(server, area, 0));
                if (wasFrozen) MmtFingerprintGame.freeze(server, false);
                try {
                    long before = server.overworld().getGameTime();
                    for (int tick = 1; tick <= ticks; tick++) {
                        MmtFingerprintGame.tickServer(server);
                        sampleTickets(server, area, seen);
                        if (tick % every == 0 || tick == ticks) checkpoints.add(state(server, area, tick));
                    }
                    long advanced = server.overworld().getGameTime() - before;
                    out.addProperty("ticksRun", advanced);
                    // A singleplayer server whose client shows a pause screen skips its ticks: the run proved nothing.
                    if (ticks > 0 && advanced == 0) out.addProperty("error", "The world did not advance: the game was paused while the fingerprint ticked it");
                } finally {
                    // perf-optimize measures the same world after the fingerprint: it asks for the world to run on.
                    boolean unfreeze = args.has("unfreeze") && args.get("unfreeze").getAsBoolean();
                    if (wasFrozen && !unfreeze) MmtFingerprintGame.freeze(server, true);
                }
                JsonObject tickets = new JsonObject();
                for (Map.Entry<String, Map<Long, TreeSet<String>>> level : seen.entrySet()) {
                    JsonObject chunks = new JsonObject();
                    for (Map.Entry<Long, TreeSet<String>> chunk : level.getValue().entrySet()) chunks.addProperty(chunkName(chunk.getKey()), String.join(" ", chunk.getValue()));
                    tickets.add(level.getKey(), chunks);
                }
                out.add("ticketsDuringRun", tickets);
                return checkpoints;
            }
        }));
    }

    static void sampleTickets(MinecraftServer server, Map<String, Set<Long>> area, Map<String, Map<Long, TreeSet<String>>> seen) {
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> chunks = area.get(dimension(level));
            if (chunks == null) continue;
            for (long chunk : chunks) {
                List<String> tickets = MmtFingerprintGame.tickets(level, chunk);
                if (tickets.isEmpty()) continue;
                Map<Long, TreeSet<String>> byChunk = seen.get(dimension(level));
                if (byChunk == null) seen.put(dimension(level), byChunk = new TreeMap<Long, TreeSet<String>>());
                TreeSet<String> types = byChunk.get(chunk);
                if (types == null) byChunk.put(chunk, types = new TreeSet<String>());
                types.addAll(tickets);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- the state

    /** Everything the area holds at one moment: every value a JSON leaf, so two records compare leaf by leaf. */
    static JsonObject state(MinecraftServer server, Map<String, Set<Long>> area, int tick) {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("tick", tick);
        JsonObject levels = new JsonObject();
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> chunks = area.get(dimension(level));
            if (chunks == null || chunks.isEmpty()) continue;
            levels.add(dimension(level), levelState(server, level, chunks));
        }
        checkpoint.add("levels", levels);
        checkpoint.addProperty("rng", rngState(server));
        return checkpoint;
    }

    static JsonObject levelState(MinecraftServer server, ServerLevel level, Set<Long> chunks) {
        JsonObject out = new JsonObject();
        out.addProperty("gameTime", level.getGameTime());
        JsonArray unloaded = new JsonArray();
        JsonObject blocks = new JsonObject(), blockEntities = new JsonObject(), inventories = new JsonObject(), ticks = new JsonObject(), tickets = new JsonObject();
        Map<BlockState, Integer> counts = new IdentityHashMap<BlockState, Integer>();
        Map<String, Long> items = new TreeMap<String, Long>();
        int loaded = 0;
        for (long chunk : new TreeSet<Long>(chunks)) {
            String name = chunkName(chunk);
            List<String> chunkTickets = MmtFingerprintGame.tickets(level, chunk);
            if (!chunkTickets.isEmpty()) tickets.addProperty(name, String.join(" ", chunkTickets));
            LevelChunk at = level.getChunkSource().getChunkNow(chunkX(chunk), chunkZ(chunk));
            if (at == null) { unloaded.add(name); continue; }
            loaded++;
            blocks.addProperty(name, blockHash(at, counts));
            Map<BlockPos, BlockEntity> entities = new TreeMap<BlockPos, BlockEntity>(POSITIONS);
            entities.putAll(at.getBlockEntities());
            for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
                String pos = position(entry.getKey());
                BlockEntity blockEntity = entry.getValue();
                String nbt;
                try { nbt = normal(MmtFingerprintGame.blockEntityNbt(blockEntity, server)); }
                catch (Throwable failure) { nbt = "(not saved: " + failure + ")"; }
                blockEntities.addProperty(pos, nbt);
                if (blockEntity instanceof Container) {
                    Container container = (Container) blockEntity;
                    inventories.addProperty(pos, contents(container, items));
                }
            }
            List<String> scheduled = new ArrayList<String>();
            MmtFingerprintGame.scheduledTicks(level, at, scheduled);
            Collections.sort(scheduled);
            if (!scheduled.isEmpty()) ticks.addProperty(name, String.join(" | ", scheduled));
        }
        JsonObject chunkState = new JsonObject();
        chunkState.addProperty("inArea", chunks.size());
        chunkState.addProperty("loaded", loaded);
        chunkState.add("unloaded", unloaded);
        out.add("chunks", chunkState);
        out.add("tickets", tickets);
        out.add("blocks", blocks);
        Map<String, Long> byBlock = new TreeMap<String, Long>();
        for (Map.Entry<BlockState, Integer> count : counts.entrySet()) {
            String id = MmtPackGame.blockId(count.getKey().getBlock());
            Long sum = byBlock.get(id);
            byBlock.put(id, (sum == null ? 0 : sum) + count.getValue());
        }
        out.add("blockCounts", numbers(byBlock));
        out.add("blockEntities", blockEntities);
        out.add("inventories", inventories);
        out.add("scheduledTicks", ticks);
        // Entities: players are the client's, not the mod's; the rest by type and saved data, in a stable order.
        Map<String, List<String>> byType = new TreeMap<String, List<String>>();
        for (Entity entity : level.getAllEntities()) {
            if (entity == null || entity instanceof Player) continue;
            BlockPos pos = entity.blockPosition();
            if (!chunks.contains(MmtPackGame.chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) continue;
            String type = String.valueOf(EntityType.getKey(entity.getType()));
            String nbt;
            try { nbt = normal(MmtFingerprintGame.entityNbt(entity, server)); }
            catch (Throwable failure) { nbt = "(not saved: " + failure + ")"; }
            List<String> list = byType.get(type);
            if (list == null) byType.put(type, list = new ArrayList<String>());
            list.add(nbt);
            if (entity instanceof ItemEntity) add(items, MmtPackGame.itemId(((ItemEntity) entity).getItem().getItem()), ((ItemEntity) entity).getItem().getCount());
        }
        JsonObject entities = new JsonObject(), entityCounts = new JsonObject();
        for (Map.Entry<String, List<String>> type : byType.entrySet()) {
            Collections.sort(type.getValue());
            entityCounts.addProperty(type.getKey(), type.getValue().size());
            for (int i = 0; i < type.getValue().size(); i++) entities.addProperty(type.getKey() + "#" + i, type.getValue().get(i));
        }
        out.add("entityCounts", entityCounts);
        out.add("entities", entities);
        out.add("items", numbers(items));
        out.addProperty("randomState", rngState(level));
        return out;
    }

    static final Comparator<BlockPos> POSITIONS = new Comparator<BlockPos>() {
        public int compare(BlockPos a, BlockPos b) {
            if (a.getX() != b.getX()) return a.getX() < b.getX() ? -1 : 1;
            if (a.getY() != b.getY()) return a.getY() < b.getY() ? -1 : 1;
            return a.getZ() == b.getZ() ? 0 : a.getZ() < b.getZ() ? -1 : 1;
        }
    };

    /** One hash of every block state in the chunk, section by section, counting each state as it goes. */
    static String blockHash(LevelChunk chunk, Map<BlockState, Integer> counts) {
        MessageDigest digest = sha256();
        LevelChunkSection[] sections = chunk.getSections();
        byte[] word = new byte[4];
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || MmtFingerprintGame.empty(section)) continue;
            put(digest, word, -1 - index);
            BlockState last = null;
            int lastId = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (state != last) { last = state; lastId = Block.getId(state); }
                put(digest, word, lastId);
                Integer count = counts.get(state);
                counts.put(state, count == null ? 1 : count + 1);
            }
        }
        return hex(digest.digest()).substring(0, 16);
    }

    static void put(MessageDigest digest, byte[] word, int value) {
        word[0] = (byte) (value >>> 24); word[1] = (byte) (value >>> 16); word[2] = (byte) (value >>> 8); word[3] = (byte) value;
        digest.update(word);
    }

    /** A container's slots as "slot:item*count", adding each stack to the item totals. */
    static String contents(Container container, Map<String, Long> items) {
        StringBuilder text = new StringBuilder();
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            String id = MmtPackGame.itemId(stack.getItem());
            if (text.length() > 0) text.append(' ');
            text.append(slot).append(':').append(id).append('*').append(stack.getCount());
            add(items, id, stack.getCount());
        }
        return text.toString();
    }

    static void add(Map<String, Long> totals, String id, long count) {
        Long sum = totals.get(id);
        totals.put(id, (sum == null ? 0 : sum) + count);
    }

    /**
     * Saved data in a form that compares between runs: identifiers a game makes from an unseeded random source at
     * creation (entity UUIDs and the references to them) are left out, since they differ in every run.
     */
    static String normal(String snbt) {
        return snbt
            .replaceAll("\"?[A-Za-z_]*(?:UUID|Uuid|uuid)[A-Za-z_]*\"?:\\[I;-?\\d+,-?\\d+,-?\\d+,-?\\d+\\],?", "")
            .replaceAll("\"?[A-Za-z_]*(?:UUID|Uuid|uuid)(?:Most|Least)\"?:-?\\d+L,?", "")
            .replaceAll("\"?[A-Za-z_]*(?:UUID|Uuid|uuid)[A-Za-z_]*\"?:\"[0-9a-fA-F-]{36}\",?", "")
            .replaceAll(",}", "}");
    }

    // ----------------------------------------------------------------------------------------------- registries

    /** Every registry the server holds (built-in and from data packs), each registry's tags, recipes, loot tables and advancements. */
    static JsonObject registries(MinecraftServer server) {
        Map<String, Collection<String>> all = new TreeMap<String, Collection<String>>();
        MmtFingerprintGame.registries(server, all);
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Collection<String>> registry : all.entrySet()) {
            List<String> ids = new ArrayList<String>(registry.getValue());
            Collections.sort(ids);
            JsonArray array = new JsonArray();
            for (String id : ids) array.add(new JsonPrimitive(id));
            out.add(registry.getKey(), array);
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------------- randomness

    /** Every random source in fields of the server, its levels and their entities, and the game's static ones, seeded. */
    static int reseed(MinecraftServer server, long seed) {
        int seeded = 0;
        seeded += reseedFields(server, server.getClass(), seed, false);
        for (ServerLevel level : server.getAllLevels()) {
            long levelSeed = seed ^ (31L * dimension(level).hashCode());
            seeded += reseedFields(level, level.getClass(), levelSeed, false);
            for (Entity entity : level.getAllEntities()) {
                if (entity == null || entity instanceof Player) continue;
                // An entity's own random source decides its wandering, sounds and drops: seeded from where it stands.
                BlockPos pos = entity.blockPosition();
                long entitySeed = levelSeed ^ (pos.getX() * 73428767L) ^ (pos.getY() * 912931L) ^ (pos.getZ() * 19349663L) ^ String.valueOf(EntityType.getKey(entity.getType())).hashCode();
                seeded += reseedFields(entity, entity.getClass(), entitySeed, false);
            }
        }
        seeded += reseedFields(null, server.getClass(), seed ^ 1, true);
        seeded += reseedFields(null, ServerLevel.class, seed ^ 2, true);
        seeded += reseedFields(null, Entity.class, seed ^ 3, true);
        try { seeded += reseedFields(null, Class.forName("net.minecraft.util.Mth", false, MmtFingerprint.class.getClassLoader()), seed ^ 4, true); }
        catch (ClassNotFoundException | LinkageError renamed) { /* the run-time name differs; the instance fields above still count */ }
        return seeded;
    }

    /** Fields are found by type, never by name, so this works whatever names the loader runs the game under. */
    static int reseedFields(Object owner, Class<?> type, long seed, boolean statics) {
        int seeded = 0, index = 0;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) != statics || field.getType().isPrimitive()) continue;
                index++;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    long fieldSeed = seed * 6364136223846793005L + index * 1442695040888963407L + c.getSimpleName().hashCode();
                    if (value instanceof Random) { ((Random) value).setSeed(fieldSeed); seeded++; }
                    else if (MmtFingerprintGame.setSeed(value, fieldSeed)) seeded++;
                } catch (Throwable inaccessible) { /* a field the module system keeps closed */ }
            }
        }
        return seeded;
    }

    /** A hash of the state inside the random sources an object holds, read without drawing from them. */
    static String rngState(Object owner) {
        MessageDigest digest = sha256();
        int index = 0;
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                index++;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null || !MmtFingerprintGame.isRandom(value)) continue;
                    digest.update((index + "=" + inner(value, 0)).getBytes(StandardCharsets.UTF_8));
                } catch (Throwable inaccessible) { /* skipped */ }
            }
        }
        return hex(digest.digest()).substring(0, 16);
    }

    /** The numbers inside a random source (its seed, or its two state words), two levels deep. */
    static String inner(Object value, int depth) throws IllegalAccessException {
        StringBuilder text = new StringBuilder();
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try { field.setAccessible(true); } catch (Throwable closed) { continue; }
                Object inside = field.get(value);
                if (field.getType() == long.class || field.getType() == int.class) text.append(inside).append(',');
                else if (inside instanceof java.util.concurrent.atomic.AtomicLong) text.append(((java.util.concurrent.atomic.AtomicLong) inside).get()).append(',');
                else if (inside != null && depth < 1 && !field.getType().getName().startsWith("java.")) text.append('[').append(inner(inside, depth + 1)).append(']');
            }
        }
        return text.toString();
    }

    // ----------------------------------------------------------------------------------------------- the area

    /** Chunks within the radius of every player, the spawn and every forced chunk, per level. */
    static Map<String, Set<Long>> areaRound(MinecraftServer server, int radius) {
        Map<String, Set<Long>> area = new TreeMap<String, Set<Long>>();
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> centres = new TreeSet<Long>();
            for (Player player : level.players()) centres.add(MmtPackGame.chunkKey(player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4));
            if (level == server.overworld()) {
                BlockPos spawn = MmtPackGame.spawn(level);
                centres.add(MmtPackGame.chunkKey(spawn.getX() >> 4, spawn.getZ() >> 4));
            }
            Set<Long> chunks = new TreeSet<Long>();
            for (long forced : MmtFingerprintGame.forcedChunks(level)) chunks.add(forced);
            for (long centre : centres) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
                chunks.add(MmtPackGame.chunkKey(chunkX(centre) + dx, chunkZ(centre) + dz));
            if (!chunks.isEmpty()) area.put(dimension(level), chunks);
        }
        return area;
    }

    /** Waits until the world stops loading chunks round its players (and a player is in it, for a client). */
    static void settle(final MinecraftServer server, final int radius, final boolean wantPlayer, int seconds, JsonObject out) throws Exception {
        long since = System.nanoTime();
        String last = "";
        int same = 0;
        while (System.nanoTime() - since < TimeUnit.SECONDS.toNanos(seconds)) {
            String now = onServer(server, new Callable<String>() {
                public String call() {
                    if (wantPlayer && server.getPlayerList().getPlayerCount() == 0) return "no player";
                    StringBuilder loaded = new StringBuilder();
                    for (Map.Entry<String, Set<Long>> level : areaRound(server, radius).entrySet()) {
                        ServerLevel at = null;
                        for (ServerLevel candidate : server.getAllLevels()) if (dimension(candidate).equals(level.getKey())) at = candidate;
                        int count = 0;
                        for (long chunk : level.getValue()) if (at.getChunkSource().getChunkNow(chunkX(chunk), chunkZ(chunk)) != null) count++;
                        loaded.append(level.getKey()).append('=').append(count).append('/').append(at.getChunkSource().getLoadedChunksCount()).append(' ');
                    }
                    return loaded.toString();
                }
            });
            same = now.equals(last) && !now.equals("no player") ? same + 1 : 0;
            last = now;
            if (same >= 6) break;
            Thread.sleep(500);
        }
        out.addProperty("settledAfterSeconds", (System.nanoTime() - since) / 1e9);
        out.addProperty("settled", same >= 6);
        out.addProperty("loadedWhenSettled", last.trim());
    }

    static boolean loaded(MinecraftServer server, Map<String, Set<Long>> area, boolean withEntities) {
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> chunks = area.get(dimension(level));
            if (chunks == null) continue;
            for (long chunk : chunks) {
                if (level.getChunkSource().getChunkNow(chunkX(chunk), chunkZ(chunk)) == null) return false;
                if (withEntities && !MmtFingerprintGame.entitiesLoaded(level, chunk)) return false;
            }
        }
        return true;
    }

    static int entityCount(MinecraftServer server, Map<String, Set<Long>> area) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> chunks = area.get(dimension(level));
            if (chunks == null) continue;
            for (Entity entity : level.getAllEntities()) {
                if (entity == null || entity instanceof Player) continue;
                BlockPos pos = entity.blockPosition();
                if (chunks.contains(MmtPackGame.chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) count++;
            }
        }
        return count;
    }

    static JsonObject areaJson(Map<String, Set<Long>> area) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Set<Long>> level : area.entrySet()) {
            JsonArray chunks = new JsonArray();
            for (long chunk : level.getValue()) chunks.add(new JsonPrimitive(chunkName(chunk)));
            out.add(level.getKey(), chunks);
        }
        return out;
    }

    static Map<String, Set<Long>> areaFrom(JsonObject json) {
        Map<String, Set<Long>> area = new TreeMap<String, Set<Long>>();
        for (Map.Entry<String, JsonElement> level : json.entrySet()) {
            Set<Long> chunks = new TreeSet<Long>();
            for (JsonElement chunk : level.getValue().getAsJsonArray()) {
                String[] xz = chunk.getAsString().split(",");
                chunks.add(MmtPackGame.chunkKey(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
            }
            area.put(level.getKey(), chunks);
        }
        return area;
    }

    // ----------------------------------------------------------------------------------------------- content

    /** The new world's test area: a grid for the target block items (pack-check's placement) and a row of cells for entities. */
    static final class Content {
        final MinecraftServer server;
        final ServerLevel level;
        final Set<String> namespaces;
        final List<Item> blockItems = new ArrayList<Item>();
        final List<EntityType<?>> entityTypes = new ArrayList<EntityType<?>>();
        final Map<String, Set<Long>> area = new TreeMap<String, Set<Long>>();
        final JsonObject placementResult = new JsonObject();
        final BlockPos origin, cells;
        MmtPackProbe.Placement placement;
        Iterator<Item> next;
        static final int CELL = 5;

        Content(MinecraftServer server, Set<String> namespaces) throws Exception {
            this.server = server;
            this.level = server.overworld();
            this.namespaces = namespaces;
            StringBuilder only = new StringBuilder();
            for (Item item : MmtPackGame.items()) {
                if (!(item instanceof BlockItem) || !targeted(MmtPackGame.itemNamespace(item))) continue;
                blockItems.add(item);
                if (only.length() > 0) only.append(',');
                only.append(MmtPackGame.itemId(item));
            }
            for (EntityType<?> type : MmtFingerprintGame.entityTypes()) {
                String id = String.valueOf(EntityType.getKey(type));
                if (targeted(id.substring(0, id.indexOf(':'))) && !id.equals("minecraft:player")) entityTypes.add(type);
            }
            // The placement takes only the chosen items; with none chosen it would take every block item.
            System.setProperty("mmt.probe.only", only.length() == 0 ? "mmt:none" : only.toString());
            origin = MmtPackProbe.origin(level);
            int rows = Math.max(1, (blockItems.size() + MmtPackProbe.COLUMNS - 1) / MmtPackProbe.COLUMNS);
            cells = origin.offset(0, 0, rows * MmtPackProbe.SPACING + 8);
            int cellRows = Math.max(1, (entityTypes.size() + MmtPackProbe.COLUMNS - 1) / MmtPackProbe.COLUMNS);
            Set<Long> chunks = new TreeSet<Long>();
            int width = MmtPackProbe.COLUMNS * Math.max(MmtPackProbe.SPACING, CELL);
            int depth = cells.getZ() - origin.getZ() + cellRows * CELL;
            for (int x = (origin.getX() >> 4) - 1; x <= (origin.getX() + width >> 4) + 1; x++)
                for (int z = (origin.getZ() >> 4) - 1; z <= (origin.getZ() + depth >> 4) + 1; z++) {
                    level.setChunkForced(x, z, true);
                    chunks.add(MmtPackGame.chunkKey(x, z));
                }
            area.put(dimension(level), chunks);
        }

        boolean targeted(String namespace) {
            return namespaces.isEmpty() ? !namespace.equals("minecraft") : namespaces.contains(namespace);
        }

        JsonObject targets() {
            JsonObject out = new JsonObject();
            out.addProperty("blockItems", blockItems.size());
            out.addProperty("entityTypes", entityTypes.size());
            JsonArray names = new JsonArray();
            for (String namespace : new TreeSet<String>(namespaces)) names.add(new JsonPrimitive(namespace));
            out.add("namespaces", names);
            return out;
        }

        boolean loaded() { return MmtFingerprint.loaded(server, area, false); }

        /** Places items for 40 ms; true once every one is placed. */
        boolean placeSome() throws Exception {
            if (placement == null) { placement = new MmtPackProbe.Placement(level, placementResult); next = blockItems.iterator(); }
            long budget = System.nanoTime() + 40_000_000L;
            while (next.hasNext() && System.nanoTime() < budget) placement.place(next.next());
            return !next.hasNext();
        }

        /** Each placed container gets one stack, so what a mod does with items has something to move. */
        JsonObject finishPlacing() {
            placement.finish();
            int filled = 0;
            for (long chunk : area.get(dimension(level))) {
                LevelChunk at = level.getChunkSource().getChunkNow(chunkX(chunk), chunkZ(chunk));
                if (at == null) continue;
                for (BlockEntity blockEntity : new ArrayList<BlockEntity>(at.getBlockEntities().values())) {
                    if (!(blockEntity instanceof Container)) continue;
                    Container container = (Container) blockEntity;
                    ItemStack stack = new ItemStack(Items.COBBLESTONE, 16);
                    if (container.getContainerSize() > 0 && container.getItem(0).isEmpty() && container.canPlaceItem(0, stack)) {
                        container.setItem(0, stack);
                        container.setChanged();
                        filled++;
                    }
                }
            }
            JsonObject out = new JsonObject();
            for (String key : new String[] { "blockItems", "landedAsExpected" }) if (placementResult.has(key)) out.add(key, placementResult.get(key));
            out.addProperty("placed", size(placementResult, "placed"));
            out.addProperty("different", size(placementResult, "different"));
            out.addProperty("unplaced", size(placementResult, "unplaced"));
            out.addProperty("failed", size(placementResult, "failed"));
            out.addProperty("containersFilled", filled);
            // What each item placed, by item, so a comparison names the item rather than a position.
            JsonObject byItem = new JsonObject();
            for (String list : new String[] { "placed", "unplaced", "failed" }) {
                if (!placementResult.has(list)) continue;
                for (JsonElement element : placementResult.getAsJsonArray(list)) {
                    JsonObject row = element.getAsJsonObject();
                    String what = list.equals("placed") ? row.get("state").getAsString() : list.equals("failed") ? "failed" : "did not place";
                    byItem.addProperty(row.get("item").getAsString(), what);
                }
            }
            out.add("byItem", byItem);
            return out;
        }

        /** Every target entity type in its own glass cell: made the way a spawn egg makes it, without spawn rules. */
        JsonArray spawn() {
            JsonArray spawned = new JsonArray();
            int index = 0;
            for (EntityType<?> type : entityTypes) {
                BlockPos floor = cells.offset((index % MmtPackProbe.COLUMNS) * CELL, 0, (index / MmtPackProbe.COLUMNS) * CELL);
                index++;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 4; dy++) {
                    boolean wall = dx != 0 || dz != 0 || dy == 0 || dy == 4;
                    level.setBlock(floor.offset(dx, dy, dz), wall ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(), MmtPackProbe.UPDATE_ALL);
                }
                String id = String.valueOf(EntityType.getKey(type));
                try {
                    Entity entity = MmtFingerprintGame.create(type, level);
                    if (entity == null) { spawned.add(new JsonPrimitive(id + " not made")); continue; }
                    entity.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
                    boolean added = level.addFreshEntity(entity);
                    spawned.add(new JsonPrimitive(id + (added ? "" : " refused")));
                } catch (Throwable failure) {
                    spawned.add(new JsonPrimitive(id + " failed: " + failure));
                }
            }
            return spawned;
        }
    }

    static int size(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key).size() : 0;
    }

    // ----------------------------------------------------------------------------------------------- plumbing

    static JsonObject header(String mode, MinecraftServer server, JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.addProperty("mode", mode);
        out.addProperty("loader", MmtPackProbe.loader());
        out.add("mods", MmtPackProbe.mods());
        out.addProperty("dedicated", server.isDedicatedServer());
        out.addProperty("freezes", MmtFingerprintGame.canFreeze());
        out.add("arguments", args);
        return out;
    }

    static <T> T onServer(MinecraftServer server, final Callable<T> task) throws Exception {
        final CompletableFuture<T> done = new CompletableFuture<T>();
        server.execute(new Runnable() {
            public void run() {
                try { done.complete(task.call()); } catch (Throwable failure) { done.completeExceptionally(failure); }
            }
        });
        return done.get(15, TimeUnit.MINUTES);
    }

    static void write(JsonObject args, JsonObject out) throws Exception {
        String file = args.has("out") ? args.get("out").getAsString() : "mmt-fingerprint.json";
        Path path = Paths.get(file).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("MMT-FINGERPRINT-DONE " + path);
    }

    static String summary(JsonObject out) {
        String text = GSON.toJson(out);
        return out.get("mode").getAsString() + " " + hex(sha256().digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16)
            + (out.has("error") ? " error: " + out.get("error").getAsString() : "");
    }

    static JsonObject parse(String text) {
        @SuppressWarnings("deprecation")
        JsonElement parsed = new JsonParser().parse(text == null || text.trim().isEmpty() ? "{}" : text);
        return parsed.getAsJsonObject();
    }

    static int integer(JsonObject args, String key, int otherwise) {
        return args.has(key) ? args.get(key).getAsInt() : otherwise;
    }

    static Set<String> strings(JsonObject args, String key) {
        Set<String> out = new TreeSet<String>();
        if (args.has(key)) for (JsonElement element : args.getAsJsonArray(key)) out.add(element.getAsString());
        return out;
    }

    static JsonObject numbers(Map<String, Long> values) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Long> entry : values.entrySet()) out.addProperty(entry.getKey(), entry.getValue());
        return out;
    }

    static String dimension(ServerLevel level) {
        return MmtFingerprintGame.dimension(level);
    }

    /** The location in a resource or tag key's text ("ResourceKey[minecraft:root / minecraft:block]"), the same in every version. */
    static String id(Object key) {
        String text = String.valueOf(key);
        int slash = text.lastIndexOf(" / ");
        return slash >= 0 && text.endsWith("]") ? text.substring(slash + 3, text.length() - 1) : text;
    }

    static int chunkX(long chunk) { return (int) chunk; }
    static int chunkZ(long chunk) { return (int) (chunk >>> 32); }
    static String chunkName(long chunk) { return chunkX(chunk) + "," + chunkZ(chunk); }
    static String position(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }
}
