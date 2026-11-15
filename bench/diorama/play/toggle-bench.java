import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Paired same-JVM timing of two switchable candidates (a benchmark-only build with MapPick.AIR_SKIP and
// TileSpots.INT_ROWS switches), randomized order per round, outputs compared between settings.
// Quick same-JVM timing of the bake functions on the bake-digest gate's fixed input (chunks from the save,
// the flight's tiles): GPU mesh, tracer, spawn spots with saved spots, GPU merge, mipmaps and tint blends.
// vars "bench.rounds" (default 5) after one warmup round. Descriptive: use perf-optimize to decide.
class SaveOnly implements ChunkSource {
    final int min, max, top;
    SaveOnly(ChunkSource map) {
        min = map.minY();
        max = map.maxY();
        top = map.topY();
    }
    public ChunkSnapshot chunk(int x, int z) { return null; }
    public int minY() { return min; }
    public int maxY() { return max; }
    public int topY() { return top; }
    public boolean isSeen(int x, int y, int z) { return true; }
}
class FrozenSave implements ChunkSave {
    // The experiment's own copy of the save's region files ("files" in experiment.json), which no
    // server writes to, so every launch reads the same chunks whatever the flight generated or saved.
    final net.minecraft.world.level.Level level;
    final ChunkTags tags;
    final Object storage;
    final java.lang.reflect.Method read;
    FrozenSave(net.minecraft.world.level.Level level, java.nio.file.Path folder) throws Exception {
        this.level = level;
        this.tags = new ChunkTags(level, true);
        var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo("gate", level.dimension(), "chunk");
        var make = net.minecraft.world.level.chunk.storage.RegionFileStorage.class.getDeclaredConstructor(
            net.minecraft.world.level.chunk.storage.RegionStorageInfo.class, java.nio.file.Path.class, boolean.class);
        make.setAccessible(true);
        this.storage = make.newInstance(info, folder, false);
        this.read = net.minecraft.world.level.chunk.storage.RegionFileStorage.class.getDeclaredMethod("read", net.minecraft.world.level.ChunkPos.class);
        this.read.setAccessible(true);
    }
    net.minecraft.nbt.CompoundTag tag(int x, int z) {
        try {
            return (net.minecraft.nbt.CompoundTag) read.invoke(storage, new net.minecraft.world.level.ChunkPos(x, z));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public net.minecraft.world.level.Level level() { return level; }
    public boolean isOpen() { return true; }
    public Map<Long, int[]> scan() { return Map.of(); }
    public ChunkTags.Summary summary(int x, int z) {
        var tag = tag(x, z);
        if (tag == null) return null;
        if (!ChunkTags.finished(tag)) return ChunkTags.Summary.UNFINISHED;
        int[] surface = tags.surface(tag);
        int[] solid = tags.solid(tag);
        return surface == null || solid == null ? ChunkTags.Summary.UNFINISHED : ChunkTags.summaryOf(surface, solid);
    }
    public synchronized int top(int x, int z) {
        var s = summary(x, z);
        return s == null || !s.finished() || s.maxY() <= level.getMinBuildHeight() ? Integer.MIN_VALUE : s.maxY();
    }
    public synchronized SavedChunk read(int x, int z) {
        var tag = tag(x, z);
        return tag == null ? null : tags.read(x, z, tag);
    }
    public void forgetTops(long region) { }
    public void clear() { }
    public void close() {
        try {
            storage.getClass().getMethod("close").invoke(storage);
        } catch (Exception ignored) {
        }
    }
    public boolean serverBehind() { return false; }
}
class Pinned implements ChunkSource {
    final ChunkSource from;
    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
    Pinned(ChunkSource from) { this.from = from; }
    public synchronized ChunkSnapshot chunk(int x, int z) {
        long key = ChunkSnapshot.key(x, z);
        if (!chunks.containsKey(key)) chunks.put(key, from.chunk(x, z));
        return chunks.get(key);
    }
    public int minY() { return from.minY(); }
    public int maxY() { return from.maxY(); }
    public int topY() { return from.topY(); }
    public boolean isSeen(int x, int y, int z) { return true; }
}
var session = MapSession.get();
var looks = BlockLooks.get();
var save = new FrozenSave(mc.level, mc.gameDirectory.toPath().resolve("diorama-gate/region"));
int rounds = vars.containsKey("bench.rounds") ? ((Number) vars.get("bench.rounds")).intValue() : 8;
try {
    var source = new Pinned(new SavedChunkSource(new SaveOnly(session.map()), save, 2));
    int[][] points = {{64, 70, 0}, {200, 70, 0}, {330, 70, 16}, {470, 70, 100}, {470, 70, 260}, {470, 70, 420}};
    List<TileKey> keys = new ArrayList<>();
    for (int[] p : points) {
        var k = new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(p[0], p[2]) / Projection.TILE_UNITS), (int) Math.floor(Projection.v(p[0], p[1], p[2]) / Projection.TILE_UNITS));
        if (!keys.contains(k)) keys.add(k);
    }
    Map<TileKey, TileImage> images = new HashMap<>();
    Map<TileKey, int[]> spotsOf = new HashMap<>();
    for (TileKey key : keys) {
        images.put(key, TileBaker.bake(key, source, looks, null, false));
        spotsOf.put(key, TileSpots.pick(images.get(key), source, looks, false, null));
    }
    // Settings: 0 both off, 1 air skip only, 2 integer rows only, 3 both.
    double[][] ms = new double[4][3 * rounds];
    Random order = new Random(7);
    int differ = 0;
    for (int round = -2; round < rounds; round++) {
        List<Integer> settings = new ArrayList<>(List.of(0, 1, 2, 3));
        Collections.shuffle(settings, order);
        Map<Integer, Integer> hashes = new HashMap<>();
        for (int setting : settings) {
            MapPick.AIR_SKIP = (setting & 1) != 0;
            TileSpots.INT_ROWS = (setting & 2) != 0;
            double mesh = 0, spots = 0, saved = 0;
            int hash = 0;
            for (TileKey key : keys) {
                long t = System.nanoTime();
                var m = GpuMesher.build(key, source, looks, false, null, spotsOf.get(key));
                mesh += (System.nanoTime() - t) / 1e6;
                hash = 31 * hash + m.opaque.count;
                m.freeFaces();
                t = System.nanoTime();
                hash = 31 * hash + Arrays.hashCode(TileSpots.pick(images.get(key), source, looks, false, null));
                spots += (System.nanoTime() - t) / 1e6;
                t = System.nanoTime();
                hash = 31 * hash + Arrays.hashCode(TileSpots.pick(images.get(key), source, looks, false, spotsOf.get(key)));
                saved += (System.nanoTime() - t) / 1e6;
            }
            hashes.put(setting, hash);
            if (round >= 0) {
                ms[setting][round] = mesh;
                ms[setting][rounds + round] = spots;
                ms[setting][2 * rounds + round] = saved;
            }
        }
        if (new HashSet<>(hashes.values()).size() != 1) differ++;
    }
    MapPick.AIR_SKIP = true;
    TileSpots.INT_ROWS = true;
    StringBuilder out = new StringBuilder("{\"differentRounds\":" + differ);
    String[] parts = {"mesh", "spots", "savedSpots"};
    for (int s = 0; s < 4; s++) for (int p = 0; p < 3; p++) {
        double[] v = Arrays.copyOfRange(ms[s], p * rounds, (p + 1) * rounds);
        Arrays.sort(v);
        out.append(String.format(Locale.ROOT, ",\"s%d.%s\":%.1f", s, parts[p], v[rounds / 2]));
    }
    // Paired ratios against both-off, per round: geometric mean.
    for (int s = 1; s < 4; s++) for (int p = 0; p < 3; p++) {
        double logs = 0;
        for (int r = 0; r < rounds; r++) logs += Math.log(ms[0][p * rounds + r] / ms[s][p * rounds + r]);
        out.append(String.format(Locale.ROOT, ",\"speedup.s%d.%s\":%.3f", s, parts[p], Math.exp(logs / rounds)));
    }
    return out.append("}").toString();
} finally {
    save.close();
}
