import dev.diorama.MapSession;
import dev.diorama.Facing;
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
        if (!Objects.equals(a.key(), b.key()) || a.generation() != b.generation()
            || !Arrays.deepEquals(a.mips(), b.mips()) || !Arrays.equals(a.coverage(), b.coverage())
            || !Arrays.equals(a.info(), b.info()) || !Arrays.equals(a.blockLight(), b.blockLight())
            || !Arrays.equals(a.spots(), b.spots())) throw new AssertionError("Output differs: " + a.key());
        if (a.mips() != null) for (int[] level : a.mips()) checked += level.length;
        if (a.patches().size() != b.patches().size()) throw new AssertionError("Patch count");
        for (int i = 0; i < a.patches().size(); i++) {
            var x = a.patches().get(i);
            var y = b.patches().get(i);
            if (x.blockX() != y.blockX() || x.blockY() != y.blockY()
                || !Arrays.deepEquals(x.mips(), y.mips()) || !Arrays.equals(x.coverage(), y.coverage())
                || !Arrays.equals(x.info(), y.info()) || !Arrays.equals(x.blockLight(), y.blockLight())) {
                throw new AssertionError("Patch differs");
            }
            for (int[] level : x.mips()) checked += level.length;
        }
    }
}
var session = MapSession.get();
if (session == null || mc.player == null) throw new IllegalStateException("Enter a mapped world");
var looks = BlockLooks.get();
var facing = Projection.facing();
boolean fast = TileBaker.fast();
boolean baseFast = TileBakerBaseline.fast();
List<Object> cases = new ArrayList<>();
try {
    for (var direction : Facing.values()) {
        Projection.setFacing(direction);
        var source = new Frozen(session.map());
        TileBaker.setFast(true);
        TileBakerBaseline.setFast(true);
        int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
        int v = (int) Math.floor(Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
        TileKey key = null;
        int most = 0;
        for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
            var k = new TileKey(TileKey.SURFACE, u + du, v + dv);
            var image = TileBakerBaseline.bake(k, source, looks, null, false);
            int known = 0;
            for (int pixel : image.mips()[0]) if ((pixel >>> 24) != 0) known++;
            if (known > most) {
                most = known;
                key = k;
            }
        }
        if (key == null || most < 262144 / 2) throw new AssertionError("Insufficient coverage for " + direction);
        for (boolean quality : List.of(false, true)) {
            TileBaker.setFast(quality);
            TileBakerBaseline.setFast(quality);
            for (int scenario = 0; scenario < 4; scenario++) {
                TileKey k = scenario == 2 ? new TileKey(64, key.tileU(), key.tileV()) : key;
                BitSet blocks = null;
                if (scenario == 1) {
                    blocks = new BitSet();
                    for (int i : new int[]{0, 7, 8, 32, 64, 128, 200, 255}) blocks.set(i);
                }
                ChunkSource chosen = source;
                if (scenario == 3) chosen = new ChunkSource() {
                    public ChunkSnapshot chunk(int x, int z) { return source.chunk(x, z); }
                    public int minY() { return source.minY(); }
                    public int maxY() { return source.maxY(); }
                    public int topY() { return source.topY(); }
                    public int roof() { return 128; }
                    public boolean isSeen(int x, int y, int z) { return (x + z) % 3 != 0; }
                };
                var overlays = new Overlays(20261114L, true);
                var a = TileBakerBaseline.bake(k, chosen, looks, blocks, true, null, overlays);
                var b = TileBaker.bake(k, chosen, looks, blocks, true, null, overlays);
                Equal.arrays(a, b);
                cases.add(Map.of("facing", direction.name(), "fast", quality, "scenario", scenario, "surfaceKnownPixels", most));
            }
        }
    }
} finally {
    Projection.setFacing(facing);
    TileBaker.setFast(fast);
    TileBakerBaseline.setFast(baseFast);
    session.map().colorsChanged();
}
return new com.google.gson.Gson().toJson(Map.of("cases", cases, "checkedMipPixels", Equal.checked, "equal", true,
    "limit", "Live Overworld snapshots; roof/fog cases use an artificial source policy. Not a real Nether or modded-block compatibility test."));
