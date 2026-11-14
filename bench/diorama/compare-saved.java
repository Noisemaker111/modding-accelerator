import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.ChunkSnapshot;

// Run twice, once with each value. Each eval performs three warmups and eight measured pairs.
boolean fast = false;

class Captured implements ChunkSource {
    final ChunkSource live;
    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
    final Set<Long> hidden = new HashSet<>();
    final int min;
    final int max;
    final int top;
    final int roof;
    boolean mask;
    boolean frozen;

    Captured(ChunkSource live) {
        this.live = live;
        min = live.minY();
        max = live.maxY();
        top = live.topY();
        roof = live.roof();
    }

    public ChunkSnapshot chunk(int x, int z) {
        long key = ChunkSnapshot.key(x, z);
        if (!chunks.containsKey(key)) {
            if (frozen) {
                throw new IllegalStateException("Unexpected chunk after inputs frozen: " + x + ", " + z);
            }
            chunks.put(key, live.chunk(x, z));
        }
        ChunkSnapshot snapshot = chunks.get(key);
        if (mask && snapshot != null && Math.floorMod(x + z, 3) == 0) {
            hidden.add(key);
            return null;
        }
        return snapshot;
    }

    public int minY() { return min; }
    public int maxY() { return max; }
    public int topY() { return top; }
    public int roof() { return roof; }
    public boolean depthCaves() { return false; }
    public boolean isSeen(int x, int y, int z) { return true; }
}

class Check {
    static long pixels;

    static void same(TileImage a, TileImage b) {
        if (!Objects.equals(a.key(), b.key()) || a.generation() != b.generation()
            || !Arrays.deepEquals(a.mips(), b.mips()) || !Arrays.equals(a.coverage(), b.coverage())
            || !Arrays.equals(a.info(), b.info()) || !Arrays.equals(a.blockLight(), b.blockLight())
            || !Arrays.equals(a.spots(), b.spots()) || a.patches().size() != b.patches().size()) {
            throw new AssertionError("Full image differs");
        }
        if (a.mips() != null) {
            for (int[] level : a.mips()) {
                pixels += level.length;
            }
        }
        for (int i = 0; i < a.patches().size(); i++) {
            var x = a.patches().get(i);
            var y = b.patches().get(i);
            if (x.blockX() != y.blockX() || x.blockY() != y.blockY()
                || !Arrays.deepEquals(x.mips(), y.mips()) || !Arrays.equals(x.coverage(), y.coverage())
                || !Arrays.equals(x.info(), y.info()) || !Arrays.equals(x.blockLight(), y.blockLight())) {
                throw new AssertionError("Patch differs: " + i);
            }
            if (x.mips() != null) {
                for (int[] level : x.mips()) {
                    pixels += level.length;
                }
            }
        }
    }
}

var session = MapSession.get();
if (session == null || mc.player == null) {
    throw new IllegalStateException("Enter a mapped world");
}
var source = new Captured(session.map());
var looks = BlockLooks.get();
boolean originalFast = TileBakerBaseline.fast();
boolean productionFast = TileBaker.fast();
List<Object> samples = new ArrayList<>();
try {
    TileBakerBaseline.setFast(fast);
    TileBaker.setFast(fast);
    int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
    int v = (int) Math.floor(Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
    TileKey key = null;
    TileImage savedImage = null;
    int known = 0;
    for (int du = -1; du <= 1; du++) {
        for (int dv = -1; dv <= 1; dv++) {
            var current = new TileKey(TileKey.SURFACE, u + du, v + dv);
            var image = TileBakerBaseline.bake(current, source, looks, null, false);
            int count = 0;
            for (int pixel : image.mips()[0]) {
                if ((pixel >>> 24) != 0) {
                    count++;
                }
            }
            if (count > known) {
                known = count;
                key = current;
                savedImage = image;
            }
        }
    }
    if (key == null || known < 262144 / 2) {
        throw new AssertionError("Insufficient known terrain");
    }
    int[] saved = savedImage.mips()[0];
    byte[] light = savedImage.blockLight();
    int[] spots = TileSpots.pick(savedImage, source, looks, false, null);
    int knownSpots = 0;
    if (spots != null) {
        for (int spot : spots) {
            if (spot != TileSpots.UNKNOWN) {
                knownSpots++;
            }
        }
    }
    if (knownSpots == 0) {
        throw new AssertionError("No known saved spots");
    }
    source.mask = true;
    Random random = new Random(91);
    for (int round = -3; round < 8; round++) {
        boolean candidateFirst = random.nextBoolean();
        TileImage baseline = null;
        TileImage candidate = null;
        double baselineMs = 0;
        double candidateMs = 0;
        for (int i = 0; i < 2; i++) {
            boolean runCandidate = (i == 0) == candidateFirst;
            long start = System.nanoTime();
            if (runCandidate) {
                candidate = TileBaker.bake(key, source, looks, null, false, saved, light, spots, new Overlays(20261114L, true));
                candidateMs = (System.nanoTime() - start) / 1e6;
            } else {
                baseline = TileBakerBaseline.bake(key, source, looks, null, false, saved, light, spots, new Overlays(20261114L, true));
                baselineMs = (System.nanoTime() - start) / 1e6;
            }
        }
        Check.same(baseline, candidate);
        if (round == -3) {
            source.frozen = true;
        }
        if (round >= 0) {
            samples.add(Map.of("tile", key.toString(), "round", round, "fast", fast, "candidateFirst", candidateFirst, "baselineMs", baselineMs, "candidateMs", candidateMs));
        }
    }
    if (source.hidden.isEmpty()) {
        throw new AssertionError("No present chunks were hidden");
    }
    return new com.google.gson.Gson().toJson(Map.of("samples", samples, "checkedMipPixels", Check.pixels, "equal", true, "knownPixels", known, "knownSavedSpots", knownSpots, "hiddenChunks", source.hidden.size(), "capturedChunks", source.chunks.size(), "tile", key.toString(), "limit", "Same-JVM elapsed CPU bakes over real captured chunk references with a synthetic missing-chunk mask; no FPS, GPU, cold-load, or exhaustive identity claim."));
} finally {
    TileBakerBaseline.setFast(originalFast);
    TileBaker.setFast(productionFast);
}
