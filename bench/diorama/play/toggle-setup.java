import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Setup for `mmt perf-toggle`: the bake-digest gate's frozen input (chunks from the experiment's own
// copy of the save, the flight's tiles), and as the work, a first mesh of each tile plus its spawn
// spots whole and over saved spots. The work returns each mesh's face counts and every spot array,
// so a switch that changes them fails the screen. Switches are the candidate's own static booleans:
//   mmt perf-toggle --root DIORAMA --project :1.21.1 --file bench/diorama/play/toggle-setup.java \
//     --switch dev.diorama.bake.VoxelReader.POOL_IDS
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
vars.put("perf.work", (java.util.function.Supplier<Object>) () -> {
    // First meshes: nothing kept from the round before.
    boolean reuse = GpuMesher.REUSE;
    GpuMesher.REUSE = false;
    try {
        List<Object> out = new ArrayList<>();
        for (TileKey key : keys) {
            var m = GpuMesher.build(key, source, looks, false, null, spotsOf.get(key));
            out.add(new int[] {m.opaque.count, m.water.count, m.tinted.count});
            m.freeFaces();
            out.add(TileSpots.pick(images.get(key), source, looks, false, null));
            out.add(TileSpots.pick(images.get(key), source, looks, false, spotsOf.get(key)));
        }
        return out;
    } finally {
        GpuMesher.REUSE = reuse;
    }
});
vars.put("perf.toggle.save", save);
return keys.size() + " tiles";
