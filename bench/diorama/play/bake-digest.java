import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Exact-output gate: every bake function the map uses, run on chunks read from a frozen copy of the
// world's region files (the experiment's "files"), not the live map or the running server's save, so two
// launches see the same input whatever their timing was. For fixed surface tiles along the flight it
// digests the GPU mesh, the traced image (full, and a partial re-bake over saved pixels), every mip level,
// spawn spots with and without saved spots, the GPU merge and the biome tints of every chunk read.
// Returns "digest part=... tiles=N chunks=N knownPixels=N" for compare: exact.
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
class Digest {
    final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    Digest() throws Exception { }
    void ints(int[] a) {
        if (a == null) { md.update((byte) 7); return; }
        var b = java.nio.ByteBuffer.allocate(a.length * 4);
        b.asIntBuffer().put(a);
        md.update(b.array());
    }
    void bytes(byte[] a) {
        if (a == null) { md.update((byte) 9); return; }
        md.update(a);
    }
    void num(long v) { md.update(java.nio.ByteBuffer.allocate(8).putLong(v).array()); }
    void image(TileImage image) {
        num(image.isFull() ? 1 : 0);
        if (image.mips() != null) for (int[] level : image.mips()) ints(level);
        bytes(image.coverage());
        bytes(image.info());
        bytes(image.blockLight());
        ints(image.spots());
        num(image.patches().size());
        for (var p : image.patches()) {
            num(p.blockX());
            num(p.blockY());
            for (int[] level : p.mips()) ints(level);
            bytes(p.coverage());
            bytes(p.info());
            bytes(p.blockLight());
        }
    }
    void quads(GpuMesher.Quads q) {
        num(q.count);
        int[] all = new int[q.count * GpuMesher.STRIDE];
        for (int i = 0; i < all.length; i++) all[i] = q.get(i);
        ints(all);
    }
    String hex() { return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 32); }
}
long until = System.currentTimeMillis() + 60_000;
while (!Boolean.TRUE.equals(vars.get("fly.done")) && vars.containsKey("fly.thread") && System.currentTimeMillis() < until) Thread.sleep(200);
var session = MapSession.get();
if (session == null || mc.level == null) throw new IllegalStateException("Enter the experiment world first");
var looks = BlockLooks.get();
var save = new FrozenSave(mc.level, mc.gameDirectory.toPath().resolve("diorama-gate/region"));
try {
    var source = new Pinned(new SavedChunkSource(new SaveOnly(session.map()), save, 2));
    Map<String, Digest> parts = new LinkedHashMap<>();
    for (String part : List.of("mesh", "trace", "spots", "mips", "partial", "savedSpots", "gpuMerge", "tints")) parts.put(part, new Digest());
    // Ground points along the flight (east from the origin, then south): the tiles they fall in.
    int[][] points = {{64, 70, 0}, {200, 70, 0}, {330, 70, 16}, {470, 70, 100}, {470, 70, 260}, {470, 70, 420}};
    Set<TileKey> keys = new LinkedHashSet<>();
    for (int[] p : points) {
        keys.add(new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(p[0], p[2]) / Projection.TILE_UNITS),
            (int) Math.floor(Projection.v(p[0], p[1], p[2]) / Projection.TILE_UNITS)));
    }
    int known = 0;
    for (TileKey key : keys) {
        var mesh = GpuMesher.build(key, source, looks, false, null, null);
        parts.get("mesh").num(mesh.traceInstead ? 1 : 0);
        parts.get("mesh").num(mesh.lowestY);
        parts.get("mesh").quads(mesh.opaque);
        parts.get("mesh").quads(mesh.water);
        parts.get("mesh").quads(mesh.tinted);
        parts.get("mesh").quads(mesh.casters);
        parts.get("mesh").ints(mesh.voxels == null ? null : Arrays.copyOf(mesh.voxels, mesh.voxelCount));
        mesh.freeFaces();
        var image = TileBaker.bake(key, source, looks, null, false);
        parts.get("trace").image(image);
        for (int pixel : image.mips()[0]) if ((pixel >>> 24) != 0) known++;
        int[] spots = TileSpots.pick(image, source, looks, false, null);
        parts.get("spots").ints(spots);
        parts.get("mips").ints(Mipmaps.build(image.mips()[0], Projection.TILE_PIXELS)[1]);
        // A partial re-bake over the full one, as after a chunk changed: every fourth block.
        BitSet blocks = new BitSet();
        for (int b = 0; b < 256; b += 4) blocks.set(b);
        var partial = TileBaker.bake(key, source, looks, blocks, false, image.mips()[0], image.blockLight(), spots, null);
        parts.get("partial").image(partial);
        parts.get("savedSpots").ints(TileSpots.pick(partial, source, looks, false, spots));
        parts.get("savedSpots").ints(TileSpots.pick(image, source, looks, false, spots));
        // The GPU path's merge, fed the traced pixels in place of a readback.
        parts.get("gpuMerge").image(TileBaker.gpuImage(key, image.mips()[0], null, image.info(), null, null));
    }
    List<Long> chunkKeys = new ArrayList<>(source.chunks.keySet());
    Collections.sort(chunkKeys);
    int chunks = 0;
    for (long k : chunkKeys) {
        var snapshot = source.chunks.get(k);
        if (snapshot == null) continue;
        chunks++;
        var tints = BiomeTints.blend(snapshot);
        parts.get("tints").ints(tints.grass());
        parts.get("tints").ints(tints.foliage());
        parts.get("tints").ints(tints.dryFoliage());
        parts.get("tints").ints(tints.water());
    }
    Digest all = new Digest();
    StringBuilder each = new StringBuilder();
    for (var e : parts.entrySet()) {
        String h = e.getValue().hex();
        all.md.update(h.getBytes());
        each.append(" ").append(e.getKey()).append("=").append(h, 0, 8);
    }
    return all.hex() + each + " tiles=" + keys.size() + " chunks=" + chunks + " knownPixels=" + known;
} finally {
    save.close();
}
