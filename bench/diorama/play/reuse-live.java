import dev.diorama.MapSession;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Exact-output gate for anything a build carries from one bake to the next: after the flight, every
// surface tile the live map holds is meshed and its spawn spots picked (whole, and only every fourth
// block over its saved spots, as a partial re-bake picks) from the map's own chunk snapshots, the ones
// the flight's bakes left their reusable work on: once as a fresh bake would (every switch named in
// "switches" off) and twice as the build bakes. The meshes (every face list, the sky occlusion table,
// the region and flags) and the spots must be identical. A build without such switches runs each three
// times as it is. Returns "true", or what differed, for compare: expect.
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
    public int roof() { return from.roof(); }
    public boolean isSeen(int x, int y, int z) { return from.isSeen(x, y, z); }
}
class MeshDigest {
    static String of(GpuMesher.Mesh m) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        var head = java.nio.ByteBuffer.allocate(4 * 32);
        for (int v : new int[] {m.traceInstead ? 1 : 0, m.empty ? 1 : 0, m.lowestY, m.topY, m.minY, m.originX, m.originZ, m.rayTop,
            m.casterX0, m.casterX1, m.casterZ0, m.casterZ1, m.casterY0, m.casterY1, m.voxelCount,
            m.voxelOrigin[0], m.voxelOrigin[1], m.voxelOrigin[2], m.voxelSize[0], m.voxelSize[1], m.voxelSize[2],
            m.opaque.count, m.water.count, m.tinted.count, m.casters.count}) head.putInt(v);
        md.update(head.array());
        for (var q : new GpuMesher.Quads[] {m.opaque, m.water, m.tinted, m.casters}) {
            var b = java.nio.ByteBuffer.allocate(q.count * GpuMesher.STRIDE * 4);
            for (int i = 0; i < q.count * GpuMesher.STRIDE; i++) b.putInt(q.get(i));
            md.update(b.array());
        }
        if (m.voxels != null) {
            var b = java.nio.ByteBuffer.allocate(m.voxelCount * 4);
            for (int i = 0; i < m.voxelCount; i++) b.putInt(m.voxels[i]);
            md.update(b.array());
        }
        return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }
}
long until = System.currentTimeMillis() + 60_000;
while (!Boolean.TRUE.equals(vars.get("fly.done")) && vars.containsKey("fly.thread") && System.currentTimeMillis() < until) Thread.sleep(200);
var session = MapSession.get();
if (session == null || mc.level == null) return "no session";
var map = session.map();
var looks = BlockLooks.get();
// Switches a build may carry (public static boolean, true when it reuses work): off for the fresh mesh.
List<java.lang.reflect.Field> switches = new ArrayList<>();
for (String name : List.of("dev.diorama.bake.VoxelReader.SHARED", "dev.diorama.bake.GpuMesher.REUSE", "dev.diorama.bake.GpuMesher.AIR_ROWS")) {
    int dot = name.lastIndexOf('.');
    try {
        switches.add(Class.forName(name.substring(0, dot)).getField(name.substring(dot + 1)));
    } catch (ReflectiveOperationException absent) {
    }
}
List<TileKey> keys = new ArrayList<>();
for (Object k : ((Map<?, ?>) field(map, "tiles")).keySet()) {
    TileKey key = (TileKey) k;
    if (!key.isCave() && key.lod() == 0) keys.add(key);
}
keys.sort(Comparator.comparingInt(TileKey::tileU).thenComparingInt(TileKey::tileV));
var source = new Pinned(map);
var store = (TileStore) field(map, "store");
int differ = 0;
StringBuilder where = new StringBuilder();
for (TileKey key : keys) {
    for (var s : switches) s.setBoolean(null, false);
    var fresh = GpuMesher.build(key, source, looks, false, null, null);
    String a = MeshDigest.of(fresh);
    fresh.freeFaces();
    for (var s : switches) s.setBoolean(null, true);
    String[] reused = new String[2];
    for (int r = 0; r < 2; r++) {
        var m = GpuMesher.build(key, source, looks, false, null, null);
        reused[r] = MeshDigest.of(m);
        m.freeFaces();
    }
    int[] saved = store == null ? null : store.spots(key);
    var image = TileImage.full(key, new int[][] {new int[0]}, new byte[0]);
    BitSet blocks = new BitSet();
    for (int b = 0; b < 256; b += 4) blocks.set(b);
    // Builds with the dirty-block pick (bench/diorama/dirty-only-gpu-spots.patch) also pick that way; found
    // by reflection, so the gate runs on an unmodified build too.
    java.lang.reflect.Method dirtyPick = null;
    int startY = 0;
    try {
        dirtyPick = TileSpots.class.getMethod("pick", TileImage.class, ChunkSource.class, BlockLooks.class, boolean.class, int[].class, BitSet.class, int.class);
        startY = (int) MapPick.class.getMethod("startY").invoke(new MapPick(source, looks, key, false));
    } catch (NoSuchMethodException absent) {
    }
    String[] spots = new String[3];
    for (int r = 0; r < 3; r++) {
        for (var s : switches) s.setBoolean(null, r > 0);
        spots[r] = Arrays.hashCode(TileSpots.pick(image, source, looks, false, saved)) + "/"
            + (dirtyPick == null ? 0 : Arrays.hashCode((int[]) field(dirtyPick.invoke(null, image, source, looks, false, saved, blocks, startY), "spots"))) + "/"
            + Arrays.hashCode(TileSpots.pick(image, source, looks, false, null));
    }
    if (!a.equals(reused[0]) || !a.equals(reused[1]) || !spots[0].equals(spots[1]) || !spots[0].equals(spots[2])) {
        differ++;
        where.append(" ").append(key.tileU()).append(",").append(key.tileV());
    }
}
for (var s : switches) s.setBoolean(null, true);
return differ == 0 && keys.size() >= 10 ? "true" : "tiles " + keys.size() + ", differ " + differ + where;
