import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// JIT warm-up before the cold capture, the same fixed work in every launch of both arms: the bake
// functions the map uses (GPU mesh, traced bake, a partial re-bake, spawn spots with and without saved
// spots, the GPU merge, mipmaps, tint blends), three rounds over eight surface tiles read from the
// experiment's frozen copy of the region files, as bake-digest.java reads them. Without it the cold
// window is the JIT's first minute, and cold pairs swung 0.56-1.20x with how much of it ran
// interpreted. The live map is not read or written: these snapshots are the warm-up's own, so nothing
// a build keeps for them (by snapshot token) can match a live chunk. Then waits 10 s for compiles queued
// by the last round, and for GPU baking to be on (below). Run on the control thread; returns "warm".
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
if (session == null || mc.level == null) throw new IllegalStateException("Enter the experiment world first");
var looks = BlockLooks.get();
var save = new FrozenSave(mc.level, mc.gameDirectory.toPath().resolve("diorama-gate/region"));
try {
    // Tiles beside the flight, none of them the bake-digest gate's.
    int[][] points = {{0, 70, 128}, {128, 70, 128}, {256, 70, 128}, {384, 70, 200}, {300, 70, 300}, {200, 70, 400}, {96, 70, 300}, {0, 70, 300}};
    Set<TileKey> keys = new LinkedHashSet<>();
    for (int[] p : points) {
        keys.add(new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(p[0], p[2]) / Projection.TILE_UNITS),
            (int) Math.floor(Projection.v(p[0], p[1], p[2]) / Projection.TILE_UNITS)));
    }
    for (int round = 0; round < 3; round++) {
        // A fresh source each round: every chunk is new to the bakes, as a first bake's are.
        var source = new Pinned(new SavedChunkSource(new SaveOnly(session.map()), save, 2));
        for (TileKey key : keys) {
            var mesh = GpuMesher.build(key, source, looks, false, null, null);
            mesh.freeFaces();
            var image = TileBaker.bake(key, source, looks, null, false);
            int[] spots = TileSpots.pick(image, source, looks, false, null);
            Mipmaps.build(image.mips()[0], Projection.TILE_PIXELS);
            BitSet blocks = new BitSet();
            for (int b = 0; b < 256; b += 4) blocks.set(b);
            var partial = TileBaker.bake(key, source, looks, blocks, false, image.mips()[0], image.blockLight(), spots, null);
            TileSpots.pick(partial, source, looks, false, spots);
            TileBaker.gpuImage(key, image.mips()[0], null, image.info(), null, null);
            // Meshed again over the same snapshots, as a tile is while the land around it loads.
            GpuMesher.build(key, source, looks, false, null, spots).freeFaces();
        }
        for (var snapshot : source.chunks.values()) {
            if (snapshot != null) BiomeTints.blend(snapshot);
        }
    }
} finally {
    save.close();
}
Thread.sleep(10_000);
// Then until GPU baking is on for the sky view: until Diorama's self-check has compared its first tiles every
// bake is traced on the CPU, and a check whose GPU bakes are slow (other games on the card) turns GPU baking off
// for the launch. Either one in the cold window measured the tracer, not the bakes a player's game runs: one
// baseline launch built 4 GPU meshes in its cold minute. A check turned off as too slow is asked again, as
// Diorama's settings do, at most three times.
int retries = 0;
long deadline = System.currentTimeMillis() + 180_000;
while (!dev.diorama.render.gpu.GpuBakeCheck.trusted(dev.diorama.render.gpu.GpuBakeCheck.SKY) && System.currentTimeMillis() < deadline) {
    var off = dev.diorama.render.gpu.GpuBakeCheck.off();
    if (off == dev.diorama.render.gpu.GpuBakeCheck.Off.TOO_SLOW && retries < 3) {
        retries++;
        dev.diorama.render.gpu.GpuBakeCheck.retry();
    } else if (off != null) {
        break;
    }
    Thread.sleep(250);
}
vars.put("warm.gpuRetries", retries);
// GPU baking still off (the check found the GPU's tiles differ from the tracer's, as a broken mesher makes them)
// is left to the gates: mapped.java requires the flight to end with GPU-baked tiles, so the launch is rejected by
// a gate instead of ending the run as an error.
vars.put("warm.gpuOff", String.valueOf(dev.diorama.render.gpu.GpuBakeCheck.off()));
return "warm";
