import dev.diorama.MapSession;
import dev.diorama.bake.*;
import dev.diorama.data.*;

// Gate for builds that keep what bakes carry over in a ring of large arrays (bench/diorama/slab-ring.patch),
// written over once they leave the ring's window. Run last: it writes over everything kept.
// 1. The flight must have gone round the ring at least once, so reuse-live (run before this) compared
//    tiles while arrays were being written over under them.
// 2. Going round the ring once more, a run kept before must read as stale, and every live surface tile,
//    meshed and its spots picked with the reuse switches on, must come out as with them off.
// A build without the ring returns "true". Returns "true", or what went wrong, for compare: expect.
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
            m.opaque.count, m.water.count, m.tinted.count, m.casters.count}) head.putInt(v);
        md.update(head.array());
        for (var q : new GpuMesher.Quads[] {m.opaque, m.water, m.tinted, m.casters}) {
            var b = java.nio.ByteBuffer.allocate(q.count * GpuMesher.STRIDE * 4);
            for (int i = 0; i < q.count * GpuMesher.STRIDE; i++) b.putInt(q.get(i));
            md.update(b.array());
        }
        return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }
}
long until = System.currentTimeMillis() + 60_000;
while (!Boolean.TRUE.equals(vars.get("fly.done")) && vars.containsKey("fly.thread") && System.currentTimeMillis() < until) Thread.sleep(200);
Class<?> slabs;
try {
    slabs = Class.forName("dev.diorama.bake.Slabs");
} catch (ClassNotFoundException absent) {
    return "true";
}
java.lang.reflect.Method serialOf = slabs.getDeclaredMethod("serial");
java.lang.reflect.Method keep = slabs.getDeclaredMethod("keep", int[].class, int.class, int.class);
java.lang.reflect.Method valid = slabs.getDeclaredMethod("valid", Class.forName("dev.diorama.bake.Slabs$Run"));
java.lang.reflect.Field windowField = slabs.getDeclaredField("WINDOW");
java.lang.reflect.Field intsField = slabs.getDeclaredField("SLAB_INTS");
for (var m : new java.lang.reflect.AccessibleObject[] {serialOf, keep, valid, windowField, intsField}) m.setAccessible(true);
int window = windowField.getInt(null);
int slabInts = intsField.getInt(null);
long serial = (long) serialOf.invoke(null);
if (serial < window) return "the flight never went round the ring: pass " + serial + " of a window of " + window;
Object early = keep.invoke(null, new int[] {1, 2, 3}, 0, 3);
int[] filler = new int[slabInts];
for (int i = 0; i <= window; i++) keep.invoke(null, filler, 0, slabInts);
if ((boolean) valid.invoke(null, early)) return "a run whose array was written over still reads as valid";
var session = MapSession.get();
if (session == null || mc.level == null) return "no session";
var map = session.map();
var looks = BlockLooks.get();
List<TileKey> keys = new ArrayList<>();
for (Object k : ((Map<?, ?>) field(map, "tiles")).keySet()) {
    TileKey key = (TileKey) k;
    if (!key.isCave() && key.lod() == 0) keys.add(key);
}
keys.sort(Comparator.comparingInt(TileKey::tileU).thenComparingInt(TileKey::tileV));
var source = new Pinned(map);
var store = (TileStore) field(map, "store");
// By reflection: the switch exists only in builds that reuse work, and this snippet compiles against unmodified ones too.
java.lang.reflect.Field reuse = GpuMesher.class.getField("REUSE");
int differ = 0;
StringBuilder where = new StringBuilder();
for (TileKey key : keys) {
    String[] digests = new String[2];
    String[] spots = new String[2];
    int[] saved = store == null ? null : store.spots(key);
    var image = TileImage.full(key, new int[][] {new int[0]}, new byte[0]);
    for (int r = 0; r < 2; r++) {
        reuse.setBoolean(null, r == 1);
        var m = GpuMesher.build(key, source, looks, false, null, null);
        digests[r] = MeshDigest.of(m);
        m.freeFaces();
        spots[r] = Arrays.hashCode(TileSpots.pick(image, source, looks, false, saved)) + "/" + Arrays.hashCode(TileSpots.pick(image, source, looks, false, null));
    }
    if (!digests[0].equals(digests[1]) || !spots[0].equals(spots[1])) {
        differ++;
        where.append(" ").append(key.tileU()).append(",").append(key.tileV());
    }
}
reuse.setBoolean(null, true);
return differ == 0 && keys.size() >= 10 ? "true" : "tiles " + keys.size() + ", differ " + differ + where;
