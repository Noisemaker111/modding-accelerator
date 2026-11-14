import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.ChunkSnapshot;

// prepare_comparison.py installs the original baker as TileBakerBaseline.
class Frozen implements ChunkSource {
    final ChunkSource live;
    final int min, max, top, roof;
    final boolean depth;
    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();

    Frozen(ChunkSource live) {
        this.live = live;
        min = live.minY();
        max = live.maxY();
        top = live.topY();
        roof = live.roof();
        depth = live.depthCaves();
    }
    public ChunkSnapshot chunk(int x, int z) {
        long key = ChunkSnapshot.key(x, z);
        if (!chunks.containsKey(key)) chunks.put(key, live.chunk(x, z));
        return chunks.get(key);
    }
    public int minY() { return min; }
    public int maxY() { return max; }
    public int topY() { return top; }
    public int roof() { return roof; }
    public boolean depthCaves() { return depth; }
    public boolean isSeen(int x, int y, int z) { return true; }
}
class Equal {
    static long checked;
    static void arrays(TileImage a, TileImage b) {
        if (!Arrays.deepEquals(a.mips(), b.mips()) || !Arrays.equals(a.coverage(), b.coverage())
            || !Arrays.equals(a.info(), b.info()) || !Arrays.equals(a.blockLight(), b.blockLight())
            || !Arrays.equals(a.spots(), b.spots())) throw new AssertionError("Output differs: " + a.key());
        if (a.mips() != null) for (int[] level : a.mips()) checked += level.length;
        if (a.patches().size() != b.patches().size()) throw new AssertionError("Patch count");
        for (int i = 0; i < a.patches().size(); i++) {
            var x = a.patches().get(i);
            var y = b.patches().get(i);
            if (!Arrays.deepEquals(x.mips(), y.mips()) || !Arrays.equals(x.coverage(), y.coverage())
                || !Arrays.equals(x.info(), y.info()) || !Arrays.equals(x.blockLight(), y.blockLight())) {
                throw new AssertionError("Patch differs");
            }
            for (int[] level : x.mips()) checked += level.length;
        }
    }
}
var session = MapSession.get();
if (session == null || mc.player == null) throw new IllegalStateException("Enter a mapped world");
var frozen = new Frozen(session.map());
var looks = BlockLooks.get();
boolean original = TileBaker.fast();
boolean originalBase = TileBakerBaseline.fast();
List<TileKey> keys = new ArrayList<>();
List<Object> samples = new ArrayList<>();
int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
int v = (int) Math.floor(Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
try {
    TileBaker.setFast(true);
    TileBakerBaseline.setFast(true);
    Map<TileKey, Integer> coverage = new LinkedHashMap<>();
    for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
        var key = new TileKey(TileKey.SURFACE, u + du, v + dv);
        var a = TileBakerBaseline.bake(key, frozen, looks, null, false);
        var b = TileBaker.bake(key, frozen, looks, null, false);
        Equal.arrays(a, b);
        int known = 0;
        for (int pixel : a.mips()[0]) if ((pixel >>> 24) != 0) known++;
        if (known >= a.mips()[0].length / 2) coverage.put(key, known);
    }
    coverage.entrySet().stream().sorted(Map.Entry.<TileKey, Integer>comparingByValue().reversed())
        .limit(2).forEach(e -> keys.add(e.getKey()));
    if (keys.isEmpty()) throw new AssertionError("No terrain-heavy tile available");
    Random order = new Random(11);
    for (boolean fast : List.of(false, true)) {
        TileBaker.setFast(fast);
        TileBakerBaseline.setFast(fast);
        for (int round = -3; round < 8; round++) for (var key : keys) {
            boolean candidateFirst = order.nextBoolean();
            TileImage[] images = new TileImage[2];
            double[] millis = new double[2];
            for (int k = 0; k < 2; k++) {
                int which = candidateFirst ? 1 - k : k;
                long start = System.nanoTime();
                images[which] = which == 0 ? TileBakerBaseline.bake(key, frozen, looks, null, false)
                    : TileBaker.bake(key, frozen, looks, null, false);
                millis[which] = (System.nanoTime() - start) / 1e6;
            }
            Equal.arrays(images[0], images[1]);
            if (round >= 0) samples.add(Map.of("tile", key.toString(), "fast", fast, "round", round,
                "baselineMs", millis[0], "candidateMs", millis[1], "candidateFirst", candidateFirst, "knownPixels", coverage.get(key)));
        }
    }
} finally {
    TileBaker.setFast(original);
    TileBakerBaseline.setFast(originalBase);
}
return new com.google.gson.Gson().toJson(Map.of("samples", samples, "checkedMipPixels", Equal.checked,
    "chunks", frozen.chunks.size(), "facing", Projection.facing().name(),
    "limit", "Paired within-JVM elapsed CPU-bake timings, frozen chunk references, same quality. Not hardware FPS or independent-process significance."));
