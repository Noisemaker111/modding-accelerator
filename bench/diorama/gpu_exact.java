import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.*;
import dev.diorama.render.gpu.GpuTileBaker;
import java.util.concurrent.CompletableFuture;

// Run twice: the first call queues a bake; later calls poll it without blocking frames.
// Start a new comparison after a completed result by running the snippet again.
class Exact {
    static Map<String, Object> pixels(int[] cpu, int[] gpu) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuLength", cpu == null ? -1 : cpu.length);
        out.put("gpuLength", gpu == null ? -1 : gpu.length);
        if (cpu == null || gpu == null) {
            out.put("equal", cpu == gpu);
            return out;
        }
        int n = Math.min(cpu.length, gpu.length);
        long mismatches = Math.abs((long) cpu.length - gpu.length);
        long[] channels = new long[4];
        int[] maxima = new int[4];
        for (int i = 0; i < n; i++) {
            if (cpu[i] != gpu[i]) mismatches++;
            for (int c = 0; c < 4; c++) {
                int diff = Math.abs(((cpu[i] >>> (8 * c)) & 255) - ((gpu[i] >>> (8 * c)) & 255));
                if (diff != 0) channels[c]++;
                maxima[c] = Math.max(maxima[c], diff);
            }
        }
        out.put("mismatchedElements", mismatches);
        out.put("channelMismatchCountsRGBA", channels);
        out.put("maxChannelErrorRGBA", maxima);
        out.put("equal", mismatches == 0);
        return out;
    }
    static Map<String, Object> bytes(byte[] cpu, byte[] gpu) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuLength", cpu == null ? -1 : cpu.length);
        out.put("gpuLength", gpu == null ? -1 : gpu.length);
        if (cpu == null || gpu == null) {
            out.put("equal", cpu == gpu);
            return out;
        }
        int n = Math.min(cpu.length, gpu.length);
        long mismatches = Math.abs((long) cpu.length - gpu.length);
        int max = 0;
        for (int i = 0; i < n; i++) {
            int diff = Math.abs((cpu[i] & 255) - (gpu[i] & 255));
            if (diff != 0) mismatches++;
            max = Math.max(max, diff);
        }
        out.put("mismatchedElements", mismatches);
        out.put("maxByteError", max);
        out.put("equal", mismatches == 0);
        return out;
    }
}
String slot = "diorama-gpu-exact-v1";
if ("0".equals(System.getenv("DIORAMA_GPU_BAKE"))) {
    return new com.google.gson.Gson().toJson(Map.of("status", "unavailable", "reason", "DIORAMA_GPU_BAKE=0 disables the GPU baking path. Restart with GPU baking enabled to compare it."));
}
Object previous = vars.get(slot);
if (previous instanceof CompletableFuture<?>) {
    var future = (CompletableFuture<?>) previous;
    if (!future.isDone() && System.currentTimeMillis() - (Long) vars.get(slot + "-started") > 30000) {
        vars.remove(slot);
        vars.remove(slot + "-started");
        return new com.google.gson.Gson().toJson(Map.of("status", "unavailable", "reason", "GPU result did not arrive within 30 seconds; verify that frames are running and GPU baking is available."));
    }
    if (!future.isDone()) {
        return new com.google.gson.Gson().toJson(Map.of("status", "pending", "instruction", "Allow frames to run, then run this snippet again."));
    }
    vars.remove(slot);
    vars.remove(slot + "-started");
    return new com.google.gson.Gson().toJson(future.join());
}
var session = MapSession.get();
if (session == null || mc.player == null || BlockLooks.get() == null) {
    return new com.google.gson.Gson().toJson(Map.of("status", "unavailable", "reason", "Enter a mapped world with block looks loaded."));
}
var source = new PinnedSource(session.map());
var looks = BlockLooks.get();
var style = MapStyle.get();
var facing = Projection.facing();
long sinBits = Double.doubleToRawLongBits(Projection.sin());
long cosBits = Double.doubleToRawLongBits(Projection.cos());
int u = (int) Math.floor(Projection.u(mc.player.getX(), mc.player.getZ()) / Projection.TILE_UNITS);
int v = (int) Math.floor(Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS);
var key = new TileKey(TileKey.SURFACE, u, v);
var overlays = new Overlays(20261114L, true);
byte[] info = new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
int[] reference = TileBaker.reference(key, source, looks, false, overlays, info);
int known = 0;
for (int pixel : reference) if ((pixel >>> 24) != 0) known++;
if (known < reference.length / 20) {
    return new com.google.gson.Gson().toJson(Map.of("status", "unavailable", "reason", "Nearby tile has less than 5% known pixels; move or wait for mapping.", "knownPixels", known));
}
boolean fast = TileBaker.fast();
TileImage cpu;
try {
    TileBaker.setFast(false);
    cpu = TileBaker.bake(key, source, looks, null, false, null, overlays);
} finally {
    TileBaker.setFast(fast);
}
var mesh = GpuMesher.build(key, source, looks, false, overlays);
var future = new CompletableFuture<Map<String, Object>>();
vars.put(slot, future);
vars.put(slot + "-started", System.currentTimeMillis());
final int knownPixels = known;
try {
    GpuTileBaker.submit(mesh, result -> {
        try {
            if (result.pixels() == null) {
                future.complete(Map.of("status", "unavailable", "reason", result.declined() ? "GPU bake declined" : "GPU bake failed or is unsupported", "device", GpuTileBaker.device()));
                return;
            }
            if (!style.equals(result.look()) || Projection.facing() != facing
                || Double.doubleToRawLongBits(Projection.sin()) != sinBits
                || Double.doubleToRawLongBits(Projection.cos()) != cosBits) {
                future.complete(Map.of("status", "unavailable", "reason", "Map style, facing or angle changed during the bake."));
                return;
            }
            TileImage gpu = TileBaker.gpuImage(key, result.pixels().clone(), null, result.info(), result.blockLight(), null);
            var color = Exact.pixels(reference, result.pixels());
            var overlay = Exact.bytes(info, result.info());
            var light = Exact.bytes(cpu.blockLight(), gpu.blockLight());
            var coverage = Exact.bytes(cpu.coverage(), gpu.coverage());
            List<Object> mips = new ArrayList<>();
            boolean equal = Boolean.TRUE.equals(color.get("equal")) && Boolean.TRUE.equals(overlay.get("equal"))
                && Boolean.TRUE.equals(light.get("equal")) && Boolean.TRUE.equals(coverage.get("equal"));
            for (int i = 0; i < cpu.mips().length; i++) {
                var level = Exact.pixels(cpu.mips()[i], gpu.mips()[i]);
                mips.add(level);
                equal &= Boolean.TRUE.equals(level.get("equal"));
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "complete");
            report.put("exact", equal);
            report.put("device", GpuTileBaker.device());
            report.put("tile", key.toString());
            report.put("knownPixels", knownPixels);
            report.put("pixels", color);
            report.put("overlay", overlay);
            report.put("blockLight", light);
            report.put("coverage", coverage);
            report.put("mips", mips);
            report.put("limit", "One surface tile; no saved merge, fog or spots. Exact equality here does not prove equality elsewhere. No performance claim.");
            future.complete(report);
        } catch (Throwable t) {
            future.complete(Map.of("status", "unavailable", "reason", "Comparison failed: " + t));
        }
    });
} catch (Throwable t) {
    future.complete(Map.of("status", "unavailable", "reason", "GPU submission failed: " + t));
}
return new com.google.gson.Gson().toJson(Map.of("status", "pending", "instruction", "Allow frames to run, then run this snippet again. If no result arrives within 30 seconds, the next poll reports unavailable."));
