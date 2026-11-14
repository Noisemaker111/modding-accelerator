import dev.diorama.MapSession;
import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.ChunkSnapshot;

// Run one facing per eval: change this index to 0, 1, 2, and 3.
int facingIndex = 0;
class Captured implements ChunkSource {
    final ChunkSource live;
    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
    final Set<Long> hidden = new HashSet<>();
    final int min, max, top, roof;
    boolean mask;
    boolean fog;
    boolean depth;
    Captured(ChunkSource live) {
        this.live = live;
        min = live.minY();
        max = live.maxY();
        top = live.topY();
        roof = live.roof();
    }
    public ChunkSnapshot chunk(int x, int z) {
        long key = ChunkSnapshot.key(x, z);
        if (!chunks.containsKey(key)) chunks.put(key, live.chunk(x, z));
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
    public boolean depthCaves() { return depth; }
    public boolean isSeen(int x, int y, int z) { return !fog || Math.floorMod(x + y + z, 3) != 0; }
}
class Check {
    static long pixels;
    static void same(TileImage a, TileImage b, String label) {
        if (!Objects.equals(a.key(), b.key()) || a.generation() != b.generation()
            || !Arrays.deepEquals(a.mips(), b.mips()) || !Arrays.equals(a.coverage(), b.coverage())
            || !Arrays.equals(a.info(), b.info()) || !Arrays.equals(a.blockLight(), b.blockLight())
            || !Arrays.equals(a.spots(), b.spots()) || a.patches().size() != b.patches().size()) {
            throw new AssertionError(label + ": full image differs");
        }
        if (a.mips() != null) for (int[] level : a.mips()) pixels += level.length;
        for (int i = 0; i < a.patches().size(); i++) {
            var x = a.patches().get(i);
            var y = b.patches().get(i);
            if (x.blockX() != y.blockX() || x.blockY() != y.blockY()
                || !Arrays.deepEquals(x.mips(), y.mips()) || !Arrays.equals(x.coverage(), y.coverage())
                || !Arrays.equals(x.info(), y.info()) || !Arrays.equals(x.blockLight(), y.blockLight())) {
                throw new AssertionError(label + ": patch " + i + " differs");
            }
            for (int[] level : x.mips()) pixels += level.length;
        }
    }
}
var session = MapSession.get();
if (session == null || mc.player == null) throw new IllegalStateException("Enter a mapped world");
var previousFacing = Projection.facing();
boolean oldFast = TileBaker.fast();
boolean oldBaseFast = TileBakerBaseline.fast();
var direction = Facing.values()[facingIndex];
var source = new Captured(session.map());
List<Object> cases = new ArrayList<>();
int knownSpots = 0;
int knownPixels = 0;
try {
    Projection.setFacing(direction);
    TileBaker.setFast(true);
    TileBakerBaseline.setFast(true);
    int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
    int v = (int) Math.floor(Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
    TileKey best = null;
    TileImage savedImage = null;
    int known = 0;
    for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
        var key = new TileKey(TileKey.SURFACE, u + du, v + dv);
        var image = TileBakerBaseline.bake(key, source, BlockLooks.get(), null, false);
        int count = 0;
        for (int pixel : image.mips()[0]) if ((pixel >>> 24) != 0) count++;
        if (count > known) {
            known = count;
            best = key;
            savedImage = image;
        }
    }
    if (best == null || known < Projection.TILE_PIXELS * Projection.TILE_PIXELS / 2) throw new AssertionError("Insufficient mapped terrain");
    // Select patches at tile edges and across its interior, including section crossings.
    BitSet blocks = new BitSet();
    for (int i : new int[]{0, 1, 7, 8, 15, 16, 31, 64, 119, 120, 127, 128, 135, 136, 192, 239, 240, 247, 248, 254, 255}) blocks.set(i);
    int[] saved = savedImage.mips()[0];
    byte[] savedLight = savedImage.blockLight();
    int[] savedSpots = TileSpots.pick(savedImage, source, BlockLooks.get(), false, null);
    knownPixels = known;
    if (savedSpots != null) for (int spot : savedSpots) if (spot != TileSpots.UNKNOWN) knownSpots++;
    for (boolean fast : List.of(true, false)) {
        TileBaker.setFast(fast);
        TileBakerBaseline.setFast(fast);
        for (int scenario = 0; scenario < 6; scenario++) {
            source.mask = scenario == 1 || scenario == 2 || scenario == 5;
            source.fog = scenario == 3 || scenario == 5;
            source.depth = scenario == 4;
            TileKey key = scenario == 4 ? new TileKey((int) Math.floor(mc.player.getY()), best.tileU(), best.tileV()) : best;
            boolean useSaved = scenario == 2 || scenario == 5;
            boolean fog = scenario == 3 || scenario == 5;
            var overlays = new Overlays(20261114L, true);
            String label = direction + " fast=" + fast + " scenario=" + scenario;
            var a = TileBakerBaseline.bake(key, source, BlockLooks.get(), blocks, fog, useSaved ? saved : null, useSaved ? savedLight : null, useSaved ? savedSpots : null, overlays);
            var b = TileBaker.bake(key, source, BlockLooks.get(), blocks, fog, useSaved ? saved : null, useSaved ? savedLight : null, useSaved ? savedSpots : null, overlays);
            Check.same(a, b, label);
            if (a.patches().size() != blocks.cardinality()) throw new AssertionError(label + ": patch count");
            int visible = 0;
            int light = 0;
            int overlay = 0;
            for (var patch : a.patches()) {
                for (int pixel : patch.mips()[0]) if ((pixel >>> 24) != 0) visible++;
                if (patch.blockLight() != null) for (byte value : patch.blockLight()) if (value != 0) light++;
                if (patch.info() != null) for (byte value : patch.info()) if (value != 0) overlay++;
            }
            cases.add(Map.of("fast", fast, "scenario", scenario, "patches", a.patches().size(),
                "visiblePixels", visible, "nonzeroLightBytes", light, "nonzeroOverlayBytes", overlay));
        }
    }
    if (source.hidden.isEmpty()) throw new AssertionError("No known chunks hidden; missing-chunk scenarios did not exercise the fixture");
} finally {
    Projection.setFacing(previousFacing);
    TileBaker.setFast(oldFast);
    TileBakerBaseline.setFast(oldBaseFast);
    session.map().colorsChanged();
}
int present = 0;
int negative = 0;
for (var entry : source.chunks.entrySet()) {
    if (entry.getValue() == null) continue;
    present++;
    if (entry.getValue().chunkX < 0 || entry.getValue().chunkZ < 0) negative++;
}
return new com.google.gson.Gson().toJson(Map.of("facing", direction.name(), "cases", cases, "checkedMipPixels", Check.pixels,
    "knownSurfacePixels", knownPixels, "knownSavedSpots", knownSpots, "capturedPresentChunks", present, "presentChunksWithNegativeCoordinate", negative, "hiddenPresentChunks", source.hidden.size(),
    "equal", true, "limits", "Real captured mapped chunks with artificial fog and missing-chunk policies. Negative-coordinate coverage is only established when its reported count is nonzero. Repeat all facing indices and representative worlds; no GPU or mod compatibility claim."));
