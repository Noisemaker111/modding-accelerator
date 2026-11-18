import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Setup for `mmt perf-toggle`: the saved-chunk import's own work. Each round reads the flight's tiles
// from the experiment's frozen copy of the save, as the import does after a rejoin, then meshes them for
// the GPU, traces them, and picks their spawn spots, as MapLevel.drawFromSave and bakeFromSave do. Every
// chunk is read again each round, so nothing carries over from the round before. Returns a hash of every
// face, pixel and spot, so a switch that changes them fails the screen:
//   mmt perf-toggle --root DIORAMA --project :1.21.1 --file bench/diorama/play/import-toggle-setup.java \
//     --switch dev.diorama.bake.GpuMesher.BITS,dev.diorama.bake.GpuMesher.RINGS,dev.diorama.bake.VoxelReader.SHARED
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
var session = MapSession.get();
var looks = BlockLooks.get();
var save = new FrozenSave(mc.level, mc.gameDirectory.toPath().resolve("diorama-gate/region"));
var live = new SaveOnly(session.map());
var facing = Projection.facing();
int[][] points = {{64, 70, 0}, {200, 70, 0}, {330, 70, 16}, {470, 70, 100}, {470, 70, 260}, {470, 70, 420}};
List<TileKey> keys = new ArrayList<>();
for (int[] p : points) {
    var k = new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(p[0], p[2]) / Projection.TILE_UNITS), (int) Math.floor(Projection.v(p[0], p[1], p[2]) / Projection.TILE_UNITS));
    if (!keys.contains(k)) keys.add(k);
}
vars.put("perf.work", (java.util.concurrent.Callable<Object>) () -> {
    // A first mesh of each tile, as the import's are: no tile's last mesh to take columns from.
    boolean reuse = GpuMesher.REUSE;
    GpuMesher.REUSE = false;
    try {
        var source = new PinnedSource(new TurnedSource(new SavedChunkSource(live, save, 2), facing));
        List<Object> out = new ArrayList<>();
        for (TileKey key : keys) {
            var m = GpuMesher.build(key, source, looks, false, null);
            long[] faces = new long[4];
            GpuMesher.Quads[] lists = {m.opaque, m.water, m.tinted, m.casters};
            for (int q = 0; q < 4; q++) {
                java.nio.IntBuffer data = (java.nio.IntBuffer) field(field(lists[q], "memory"), "data");
                long h = lists[q].count;
                for (int n = 0, end = lists[q].count * GpuMesher.STRIDE; n < end; n++) h = h * 31 + data.get(n);
                faces[q] = h;
            }
            out.add(faces);
            m.freeFaces();
            TileImage image = TileBaker.bake(key, source, looks, null, false);
            out.add(image.mips()[0]);
            out.add(TileSpots.pick(image, source, looks, false, null));
        }
        return out;
    } finally {
        GpuMesher.REUSE = reuse;
    }
});
vars.put("perf.toggle.save", save);
return keys.size() + " tiles";
