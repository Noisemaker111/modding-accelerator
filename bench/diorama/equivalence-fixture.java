import dev.diorama.Facing;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SavedChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;

// prepare_comparison.py installs TileBakerBaseline. Run one scenario per eval; replace this
// number with 0..5. Each run creates private snapshots; it never writes to a world chunk.
// 0: surface four samples, 1: surface fast, 2: cave four samples, 3: cave fast,
// 4: lifted roof four samples, 5: lifted roof fast.
class Fixture implements ChunkSource {
    final net.minecraft.client.multiplayer.ClientLevel level;
    final PalettedContainer<BlockState> blank;
    final int count, minSection, min, max, excluded;
    final boolean roof;
    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
    final int[] made = new int[4];

    Fixture(net.minecraft.client.multiplayer.ClientLevel level, int excluded, boolean roof) {
        this.level = level;
        this.min = level.getMinBuildHeight();
        this.max = level.getMaxBuildHeight();
        this.minSection = level.getMinSection();
        this.count = level.getSectionsCount();
        this.excluded = excluded;
        this.roof = roof;
        if (min > 60 || max <= 67) throw new IllegalStateException("Fixture needs y 60..67 within dimension");
        // Copy an existing palette into private storage, then clear it before constructing terrain.
        this.blank = level.getChunkAt(mc.player.blockPosition()).getSections()[0].getStates().copy();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            this.blank.set(x, y, z, Blocks.AIR.defaultBlockState());
        }
    }
    public int minY() { return min; }
    public int maxY() { return max; }
    public int topY() { return 66; }
    public int roof() { return roof ? 80 : NO_ROOF; }
    public boolean isSeen(int x, int y, int z) { return true; }
    @SuppressWarnings("unchecked")
    public ChunkSnapshot chunk(int cx, int cz) {
        // Deliberately leave some far chunks unknown. No calls touch or modify live chunks.
        if (cx < -12 || cx > 3 || cz < -16 || cz > 5) return null;
        long key = ChunkSnapshot.key(cx, cz);
        ChunkSnapshot previous = chunks.get(key);
        if (previous != null) return previous;
        PalettedContainer<BlockState>[] states = new PalettedContainer[count];
        int section = (60 >> 4) - minSection;
        states[section] = blank.copy();
        int[] surface = new int[256];
        int[] solid = new int[256];
        DataLayer[] blockLight = new DataLayer[count];
        DataLayer[] skyLight = new DataLayer[count];
        byte[] lamps = new byte[DataLayer.SIZE];
        byte[] sky = new byte[DataLayer.SIZE];
        Arrays.fill(lamps, (byte) 0xA7);
        Arrays.fill(sky, (byte) 0xFF);
        blockLight[section] = new DataLayer(lamps);
        skyLight[section] = new DataLayer(sky);
        BlockState[] feature = {Blocks.WATER.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState()};
        int[] grass = new int[256], foliage = new int[256], dry = new int[256], water = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = (cx << 4) + x;
            int wz = (cz << 4) + z;
            int i = (z << 4) | x;
            // A solid base and broad 4x4 alternating patches, aligned across negative chunk edges.
            for (int y = 60; y <= 62; y++) states[section].set(x, y & 15, z, Blocks.STONE.defaultBlockState());
            int kind = Math.floorMod(Math.floorDiv(wx, 4) + Math.floorDiv(wz, 4), 4);
            BlockState chosen = kind == excluded ? Blocks.STONE.defaultBlockState() : feature[kind];
            states[section].set(x, 63 & 15, z, chosen);
            if (states[section].get(x, 63 & 15, z) != chosen) throw new AssertionError("palette did not retain " + kind);
            made[kind]++;
            surface[i] = 64;
            solid[i] = 64;
            grass[i] = 0x75A84C;
            foliage[i] = 0x549C42;
            dry[i] = 0xA39854;
            water[i] = 0x3F76E4;
        }
        var saved = new SavedChunk(cx, cz, true, states, (PalettedContainerRO<Holder<Biome>>[]) new PalettedContainerRO[count], blockLight, skyLight, surface, solid);
        SavedChunk[] around = new SavedChunk[9];
        around[4] = saved;
        ChunkSnapshot result = ChunkSnapshot.fromSave(saved, around, level, 0);
        result.setTints(new ChunkSnapshot.Tints(grass, foliage, dry, water));
        chunks.put(key, result);
        return result;
    }
}
class FixtureEqual {
    static long pixels;
    static void check(TileImage a, TileImage b) {
        if (!Objects.equals(a.key(), b.key()) || a.generation() != b.generation()
            || !Arrays.deepEquals(a.mips(), b.mips()) || !Arrays.equals(a.coverage(), b.coverage())
            || !Arrays.equals(a.info(), b.info()) || !Arrays.equals(a.blockLight(), b.blockLight())
            || !Arrays.equals(a.spots(), b.spots())) throw new AssertionError("Full image differs: " + a.key());
        if (a.mips() != null) for (int[] level : a.mips()) pixels += level.length;
        if (a.patches().size() != b.patches().size()) throw new AssertionError("Patch count differs");
        for (int i = 0; i < a.patches().size(); i++) {
            var x = a.patches().get(i);
            var y = b.patches().get(i);
            if (x.blockX() != y.blockX() || x.blockY() != y.blockY()
                || !Arrays.deepEquals(x.mips(), y.mips()) || !Arrays.equals(x.coverage(), y.coverage())
                || !Arrays.equals(x.info(), y.info()) || !Arrays.equals(x.blockLight(), y.blockLight())) {
                throw new AssertionError("Patch differs at " + i);
            }
            for (int[] level : x.mips()) pixels += level.length;
        }
    }
}
int scenario = 0;
// Repeat surface scenario 0 with styles 1..3: low/high pitch and alternate shading.
int styleIndex = 0;
if (scenario < 0 || scenario > 5) throw new IllegalArgumentException("scenario must be 0..5");
if (styleIndex < 0 || styleIndex > 3) throw new IllegalArgumentException("styleIndex must be 0..3");
if (mc.level == null || mc.player == null) throw new IllegalStateException("Enter a world first");
var oldFacing = Projection.facing();
var oldStyle = MapStyle.get();
boolean oldFast = TileBaker.fast(), oldBaseFast = TileBakerBaseline.fast();
try {
    MapStyle.set(switch (styleIndex) {
        case 1 -> oldStyle.withPitch(35);
        case 2 -> oldStyle.withPitch(65);
        case 3 -> oldStyle.withOutline(0).withSkyOcclusion(0).withClearWater(false);
        default -> oldStyle;
    });
    Projection.setFacing(Facing.NORTH);
    boolean fast = (scenario & 1) != 0;
    boolean cave = scenario / 2 == 1;
    boolean roof = scenario / 2 == 2;
    TileBaker.setFast(fast);
    TileBakerBaseline.setFast(fast);
    var source = new Fixture(mc.level, -1, roof);
    var looks = BlockLooks.get();
    // Negative tile, crossing many negative x/z chunk boundaries. The flat fixture projects
    // into this tile at the default pitch; assert coverage rather than assuming that it does.
    var key = new TileKey(cave ? 66 : TileKey.SURFACE, -1, -1);
    var overlay = new Overlays(20261114L, true);
    var baseline = TileBakerBaseline.bake(key, source, looks, null, false, null, overlay);
    var actual = TileBaker.bake(key, source, looks, null, false, null, overlay);
    FixtureEqual.check(baseline, actual);
    BitSet patchBlocks = new BitSet();
    for (int index : new int[] {0, 7, 8, 31, 63, 127, 128, 192, 255}) patchBlocks.set(index);
    var partialBaseline = TileBakerBaseline.bake(key, source, looks, patchBlocks, false, null, overlay);
    var partialActual = TileBaker.bake(key, source, looks, patchBlocks, false, null, overlay);
    FixtureEqual.check(partialBaseline, partialActual);
    if (partialBaseline.patches().size() != patchBlocks.cardinality()) throw new AssertionError("Missing patches");
    int known = 0;
    for (int pixel : baseline.mips()[0]) if ((pixel >>> 24) != 0) known++;
    if (known < 10000) throw new AssertionError("Insufficient known coverage: " + known);
    // A control for each material proves it affects this image. This is stronger than merely
    // counting fixture placement, but proves output influence, not an unobstructed individual ray.
    String[] names = {"water", "glass", "stone_slab", "glowstone"};
    Map<String, Integer> changed = new LinkedHashMap<>();
    for (int kind = 0; kind < names.length; kind++) {
        var control = new Fixture(mc.level, kind, roof);
        var without = TileBakerBaseline.bake(key, control, looks, null, false, null, overlay);
        int n = 0;
        for (int i = 0; i < baseline.mips()[0].length; i++) if (baseline.mips()[0][i] != without.mips()[0][i]) n++;
        if (n == 0) throw new AssertionError(names[kind] + " has no influence in scenario " + scenario);
        changed.put(names[kind], n);
    }
    return new com.google.gson.Gson().toJson(Map.ofEntries(
        Map.entry("scenario", scenario), Map.entry("styleIndex", styleIndex), Map.entry("fast", fast), Map.entry("cave", cave),
        Map.entry("roof", roof), Map.entry("key", key.toString()), Map.entry("knownPixels", known),
        Map.entry("fixturePlacements", source.made), Map.entry("changedPixelsWhenReplacedWithStone", changed),
        Map.entry("checkedMipPixels", FixtureEqual.pixels), Map.entry("equal", true),
        Map.entry("limits", "Synthetic snapshots with fixed light and biome tints; material controls establish image influence, not a real Nether or modded-world test.")));
} finally {
    MapStyle.set(oldStyle);
    Projection.setFacing(oldFacing);
    TileBaker.setFast(oldFast);
    TileBakerBaseline.setFast(oldBaseFast);
    if (MapSession.get() != null) MapSession.get().map().colorsChanged();
}
