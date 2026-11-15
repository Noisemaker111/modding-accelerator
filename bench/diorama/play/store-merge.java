import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Exact-output gate for the tile store (TileStore), whose cache a build may keep in another form: two tiles of
// the frozen save are handed a fixed run of bakes (a full bake with spots, a patch, the first bake again, the
// same bake once more, other pixels without spots, the same pixels with other spots), and after each one
// everything a reader gets is digested, including that a reader's array is its own. Then the store is written,
// closed and opened again, and the tiles read back. Each step changes the cached tile under the readers.
// Returns "digest tiles=2 steps..." for compare: exact.
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
class StoreDigest {
    final java.security.MessageDigest md;
    StoreDigest() throws Exception { md = java.security.MessageDigest.getInstance("SHA-256"); }
    void ints(int[] a) {
        if (a == null) { md.update((byte) 7); return; }
        var b = java.nio.ByteBuffer.allocate(a.length * 4);
        for (int v : a) b.putInt(v);
        md.update(b.array());
    }
    void bytes(byte[] a) {
        if (a == null) { md.update((byte) 9); return; }
        md.update(a);
    }
    void read(TileStore store, TileKey key) {
        int[] pixels = store.read(key);
        ints(pixels);
        var saved = store.readSaved(key);
        if (saved == null) {
            md.update((byte) 5);
        } else {
            ints(saved.pixels());
            bytes(saved.blockLight());
            ints(saved.spots());
        }
        ints(store.spots(key));
        // A reader's array is its own: changing it must not change the store.
        if (pixels != null) {
            pixels[0] ^= 1;
            ints(store.read(key));
        }
    }
    String hex() { return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 32); }
}
var session = MapSession.get();
if (session == null || mc.level == null) throw new IllegalStateException("Enter the experiment world first");
var looks = BlockLooks.get();
var save = new FrozenSave(mc.level, mc.gameDirectory.toPath().resolve("diorama-gate/region"));
java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("diorama-store-gate");
StoreDigest digest = new StoreDigest();
StringBuilder steps = new StringBuilder();
try {
    var source = new Pinned(new SavedChunkSource(new SaveOnly(session.map()), save, 2));
    int[][] points = {{64, 70, 0}, {330, 70, 16}};
    List<TileKey> keys = new ArrayList<>();
    for (int[] p : points) {
        keys.add(new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(p[0], p[2]) / Projection.TILE_UNITS),
            (int) Math.floor(Projection.v(p[0], p[1], p[2]) / Projection.TILE_UNITS)));
    }
    var store = new TileStore(folder);
    int size = Projection.TILE_PIXELS;
    for (TileKey key : keys) {
        TileImage a = TileBaker.bake(key, source, looks, null, false);
        int[] spotsA = TileSpots.pick(a, source, looks, false, null);
        // 1. A full bake with its spots, over nothing saved.
        store.baked(a.withSpots(spotsA));
        digest.read(store, key);
        steps.append(" full");
        // 2. A patch that changes pixels, light and spots in one block, over the saved tile.
        int edge = TileImage.BLOCK;
        int[][] mips = new int[Mipmaps.LEVELS][];
        for (int level = 0; level < Mipmaps.LEVELS; level++) {
            int e = edge >> level;
            mips[level] = new int[e * e];
            for (int i = 0; i < mips[level].length; i++) mips[level][i] = 0xFF000000 | (i * 2654435 + level);
        }
        byte[] coverage = new byte[(edge / (size / Projection.COVERAGE_CELLS)) * (edge / (size / Projection.COVERAGE_CELLS))];
        Arrays.fill(coverage, (byte) 1);
        byte[] light = new byte[(edge / BlockLight.SCALE) * (edge / BlockLight.SCALE)];
        Arrays.fill(light, (byte) 7);
        int[] patchSpots = spotsA.clone();
        for (int i = 0; i < patchSpots.length; i += 97) patchSpots[i] = TileSpots.NOTHING;
        store.baked(TileImage.partial(key, List.of(new TileImage.Patch(1, 2, mips, coverage, null, light))).withSpots(patchSpots));
        digest.read(store, key);
        steps.append(" patch");
        // 3. The first bake again: it puts the tile back as it was.
        store.baked(a.withSpots(spotsA));
        digest.read(store, key);
        steps.append(" again");
        // 4. The same bake once more: nothing changes, and the saved tile stays.
        store.baked(a.withSpots(spotsA));
        digest.read(store, key);
        steps.append(" same");
        // 5. A full bake with other pixels and no spots of its own: the saved spots stay.
        int[][] other = new int[a.mips().length][];
        for (int level = 0; level < other.length; level++) {
            other[level] = a.mips()[level].clone();
            for (int i = 0; i < other[level].length; i += 13) other[level][i] ^= 0x00010101;
        }
        store.baked(TileImage.full(key, other, a.coverage(), a.info()).withBlockLight(a.blockLight()));
        digest.read(store, key);
        steps.append(" nospots");
        // 6. The same pixels with other spots: only the spots change.
        int[] spotsB = spotsA.clone();
        for (int i = 0; i < spotsB.length; i += 31) spotsB[i] = TileSpots.UNKNOWN;
        store.baked(TileImage.full(key, other, a.coverage(), a.info()).withBlockLight(a.blockLight()).withSpots(spotsB));
        digest.read(store, key);
        steps.append(" spots");
    }
    // Written and read back by a store opened on the folder, as after a restart.
    store.catchUp();
    store.close();
    var reopened = new TileStore(folder);
    for (TileKey key : keys) digest.read(reopened, key);
    reopened.close();
    return digest.hex() + " tiles=" + keys.size() + steps;
} finally {
    save.close();
    try (var walk = java.nio.file.Files.walk(folder)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
}
