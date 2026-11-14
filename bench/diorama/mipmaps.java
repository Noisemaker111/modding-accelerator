import dev.diorama.bake.Mipmaps;
import dev.diorama.Projection;
import java.lang.management.ManagementFactory;

// Run with modaccel eval --file. Does not replace Diorama's implementation.
class Candidate {
    static volatile long sink;
    static int average(int p0, int p1, int p2, int p3) {
        int a0 = p0 >>> 24, a1 = p1 >>> 24, a2 = p2 >>> 24, a3 = p3 >>> 24;
        int alpha = a0 + a1 + a2 + a3;
        if (alpha == 0) return 0;
        if (alpha == 1020) {
            int rb = (((p0 & 0x00ff00ff) + (p1 & 0x00ff00ff) + (p2 & 0x00ff00ff) + (p3 & 0x00ff00ff)) >>> 2) & 0x00ff00ff;
            int g = (((p0 & 0x0000ff00) + (p1 & 0x0000ff00) + (p2 & 0x0000ff00) + (p3 & 0x0000ff00)) >>> 2) & 0x0000ff00;
            return 0xff000000 | rb | g;
        }
        int r = ((p0 & 255) * a0 + (p1 & 255) * a1 + (p2 & 255) * a2 + (p3 & 255) * a3) / alpha;
        int g = (((p0 >> 8) & 255) * a0 + ((p1 >> 8) & 255) * a1 + ((p2 >> 8) & 255) * a2 + ((p3 >> 8) & 255) * a3) / alpha;
        int b = (((p0 >> 16) & 255) * a0 + ((p1 >> 16) & 255) * a1 + ((p2 >> 16) & 255) * a2 + ((p3 >> 16) & 255) * a3) / alpha;
        return ((alpha / 4) << 24) | (b << 16) | (g << 8) | r;
    }
    static int[][] build(int[] base, int size) {
        int[][] levels = new int[Mipmaps.LEVELS][];
        levels[0] = base;
        for (int level = 1; level < levels.length; level++) {
            int src = size >> (level - 1), dst = src >> 1;
            int[] from = levels[level - 1], to = new int[dst * dst];
            for (int y = 0; y < dst; y++) for (int x = 0; x < dst; x++) {
                int i = y * 2 * src + x * 2;
                to[y * dst + x] = average(from[i], from[i + 1], from[i + src], from[i + src + 1]);
            }
            levels[level] = to;
        }
        return levels;
    }
    static long measure(int[] pixels, int size, boolean candidate, int repeats) {
        long start = System.nanoTime(), check = 0;
        for (int i = 0; i < repeats; i++) {
            int[][] result = candidate ? build(pixels, size) : Mipmaps.build(pixels, size);
            check += result[result.length - 1][i % result[result.length - 1].length];
        }
        sink = check;
        return System.nanoTime() - start;
    }
}
Random random = new Random(20261114);
for (int i = 0; i < 1_000_000; i++) {
    int a = random.nextInt(), b = random.nextInt(), c = random.nextInt(), d = random.nextInt();
    if ((i & 1) == 0) {
        a |= 0xff000000;
        b |= 0xff000000;
        c |= 0xff000000;
        d |= 0xff000000;
    }
    if (Mipmaps.average(a, b, c, d) != Candidate.average(a, b, c, d)) throw new AssertionError("Pixel mismatch " + i);
}
int size = Projection.TILE_PIXELS;
List<Object> datasets = new ArrayList<>();
Map<String, int[]> inputs = new LinkedHashMap<>();
for (String kind : List.of("opaque", "mixed-alpha", "empty", "opaque-with-unknown-edge")) {
    int[] pixels = new int[size * size];
    for (int i = 0; i < pixels.length; i++) {
        int value = random.nextInt();
        pixels[i] = switch (kind) {
            case "opaque" -> value | 0xff000000;
            case "empty" -> 0;
            case "opaque-with-unknown-edge" -> i % size < size / 8 ? 0 : value | 0xff000000;
            default -> value;
        };
    }
    inputs.put(kind, pixels);
}
Object real = vars.get("diorama-study-pixels");
if (real instanceof int[] pixels && pixels.length == size * size) inputs.put("captured-terrain", pixels);
for (var input : inputs.entrySet()) {
    int[] pixels = input.getValue();
    int[][] before = Mipmaps.build(pixels, size), after = Candidate.build(pixels, size);
    for (int level = 0; level < before.length; level++) if (!Arrays.equals(before[level], after[level])) {
        throw new AssertionError("Mip chain differs: " + input.getKey() + " level " + level);
    }
    for (int warmup = 0; warmup < 10; warmup++) {
        Candidate.measure(pixels, size, false, 20);
        Candidate.measure(pixels, size, true, 20);
    }
    List<Object> rounds = new ArrayList<>();
    for (int round = 0; round < 12; round++) {
        long baseline, candidate;
        boolean candidateFirst = random.nextBoolean();
        if (candidateFirst) {
            candidate = Candidate.measure(pixels, size, true, 50);
            baseline = Candidate.measure(pixels, size, false, 50);
        } else {
            baseline = Candidate.measure(pixels, size, false, 50);
            candidate = Candidate.measure(pixels, size, true, 50);
        }
        rounds.add(Map.of("baselineMs", baseline / 50e6, "candidateMs", candidate / 50e6, "candidateFirst", candidateFirst));
    }
    datasets.add(Map.of("name", input.getKey(), "size", size, "rounds", rounds, "allMipPixelsEqual", true));
}
return new com.google.gson.Gson().toJson(Map.of("schema", 1, "kind", "diorama-mipmap-experiment", "datasets", datasets,
    "pixelComparisons", 1_000_000, "java", System.getProperty("java.version"), "jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
    "limit", "Within one JVM, isolated CPU mip construction. Does not establish whole-mod FPS improvement."));
