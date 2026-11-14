import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;

var session = MapSession.get();
if (session == null || mc.player == null || session.map().chunkCount() < 16) throw new IllegalStateException("Enter a mapped world first");
var source = session.map();
var looks = BlockLooks.get();
int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
int v = (int) Math.floor(Projection.v(mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
List<TileKey> keys = List.of(new TileKey(TileKey.SURFACE, u, v), new TileKey(TileKey.SURFACE, u - 1, v), new TileKey(TileKey.SURFACE, u, v - 1));
boolean original = TileBaker.fast();
List<Object> samples = new ArrayList<>();
try {
    for (int round = -2; round < 6; round++) {
        for (int mode = 0; mode < 2; mode++) {
            boolean fast = ((round + mode) & 1) == 0;
            TileBaker.setFast(fast);
            for (TileKey key : keys) {
                long start = System.nanoTime();
                TileImage image = TileBaker.bake(key, source, looks, null, false);
                double millis = (System.nanoTime() - start) / 1e6;
                int known = 0;
                for (int p : image.mips()[0]) if ((p >>> 24) != 0) known++;
                if (round >= 0) samples.add(Map.of("round", round, "fast", fast, "tile", key.toString(), "ms", millis,
                    "knownPixels", known, "pixels", image.mips()[0].length, "pixelHash", Arrays.hashCode(image.mips()[0])));
                if (!fast && key.equals(keys.get(0))) vars.put("diorama-study-pixels", image.mips()[0]);
            }
        }
    }
} finally {
    TileBaker.setFast(original);
}
return new com.google.gson.Gson().toJson(Map.of("schema", 1, "kind", "diorama-cpu-bake", "chunks", source.chunkCount(),
    "position", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()), "samples", samples,
    "limit", "Same-JVM warm-cache CPU bakes. Fast changes image quality; this is a setting comparison, not an equivalent-output optimization."));
