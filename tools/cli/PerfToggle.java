import java.nio.file.*;
import java.util.*;

/**
 * Same-JVM pre-screen of a candidate behind static boolean switches, in the running game: minutes
 * instead of a paired launch run. The setup snippet freezes an input and leaves the work in
 * {@code vars} as {@code perf.work} (a Supplier or Callable run on the eval thread); each round runs
 * it once per switch setting in shuffled order and records the thread's CPU time, its allocated bytes
 * and a content hash of what the work returned. Any round whose settings return different results
 * fails the screen. Descriptive: perf-optimize decides; this says which candidates are worth its time.
 */
final class PerfToggle {
    static final String WORK = "perf.work";

    static Map<String, Object> run(Path home, Mmt.Options options) throws Exception {
        List<String> switches = Arrays.stream(options.get("switch", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (switches.isEmpty() || switches.size() > 3) throw new IllegalArgumentException("perf-toggle needs --switch pkg.Class.FIELD[,...]: one to three static boolean fields");
        Path setup = Path.of(options.get("file", "")).toAbsolutePath();
        if (!Files.isRegularFile(setup)) throw new IllegalArgumentException("perf-toggle needs --file SETUP.java, which puts the work in vars as \"" + WORK + "\"");
        int rounds = Performance.integer(options, "rounds", 8, 3, 200);
        int warmup = Performance.integer(options, "warmup", 2, 0, 50);
        String thread = options.get("thread", "control");
        Path out = Performance.output(options);
        List<String> base = new ArrayList<>(PerfOptimize.childJava(home));
        base.add("eval");
        for (String key : List.of("root", "project")) {
            if (options.values.containsKey(key)) base.addAll(List.of("--" + key, options.get(key, "")));
        }
        eval(base, out, "setup", List.of("--file", setup.toString(), "--thread", thread));
        List<List<Map<String, Object>>> measured = new ArrayList<>();
        // Thread CPU time ticks every 15.6 ms on Windows: work shorter than that is repeated, the same
        // count in every setting, until the slowest setting uses about 300 ms of CPU (wall time where the
        // clock hasn't ticked yet: work that waits on another thread uses far less CPU than wall time).
        // A first call can be far slower than the rest (class loading, a cache filling), so the count
        // grows until it holds.
        int reps = 1;
        Path file = out.resolve("round.java");
        for (int tries = 0; tries < 8; tries++) {
            Files.writeString(file, snippet(switches, 999L - tries, reps));
            double slowest = 0;
            for (Map<String, Object> s : round(base, out, "calibration", file, thread)) {
                double cpu = ((Number) s.get("cpuMs")).doubleValue();
                slowest = Math.max(slowest, (cpu > 0 ? cpu : ((Number) s.get("wallMs")).doubleValue()) * reps);
            }
            if (slowest >= 200 || reps >= 1_000_000) {
                break;
            }
            reps = (int) Math.min(1_000_000, Math.max(reps * 2L, (long) Math.ceil(reps * 300 / Math.max(slowest, 0.001))));
        }
        for (int round = -warmup; round < rounds; round++) {
            Files.writeString(file, snippet(switches, 1000L + round, reps));
            List<Map<String, Object>> settings = round(base, out, "round " + round, file, thread);
            if (round >= 0) measured.add(settings);
        }
        Map<String, Object> result = summarize(switches, measured);
        result.put("setup", setup.toString());
        result.put("repetitions", reps);
        result.put("rounds", rounds);
        result.put("warmup", warmup);
        Json.save(out.resolve("toggle.json"), result);
        result.put("details", details(result) + "\nEvidence: " + out.resolve("toggle.json"));
        return result;
    }

    static List<Map<String, Object>> round(List<String> base, Path out, String name, Path file, String thread) throws Exception {
        return settings(eval(base, out, name, List.of("--file", file.toString(), "--thread", thread)));
    }

    /** The settings a round measured, from eval's --json receipt, which holds the snippet's answer under "result". */
    static List<Map<String, Object>> settings(Map<String, Object> receipt) {
        Map<String, Object> result = receipt.get("result") instanceof Map<?, ?> r ? Json.object(r) : Map.of();
        List<Map<String, Object>> settings = Json.array(Json.parse(Json.text(result, "value", "[]"))).stream().map(Json::object).toList();
        if (settings.isEmpty()) throw new IllegalStateException("eval returned no measurements: " + Json.write(receipt));
        return settings;
    }

    static Map<String, Object> eval(List<String> base, Path out, String name, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(base);
        command.addAll(args);
        command.addAll(List.of("--timeout", "120", "--json"));
        Path log = out.resolve("eval.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("perf-toggle " + name + " timed out; see " + log);
        }
        String text = PerfOptimize.childOutput(log).strip();
        Map<String, Object> answer = Json.object(Json.parse(text.substring(Math.max(0, text.lastIndexOf("\n{") + 1))));
        if (process.exitValue() != 0 || !Boolean.TRUE.equals(answer.get("ok"))) {
            throw new IllegalStateException("perf-toggle " + name + " failed: " + Json.text(answer, "details", text));
        }
        return answer;
    }

    /** Per setting: medians, and against all switches off, the per-round CPU and allocation ratios. */
    static Map<String, Object> summarize(List<String> switches, List<List<Map<String, Object>>> rounds) {
        int settings = 1 << switches.size();
        double[][] cpu = new double[settings][rounds.size()];
        double[][] alloc = new double[settings][rounds.size()];
        int differing = 0;
        for (int r = 0; r < rounds.size(); r++) {
            Set<String> hashes = new HashSet<>();
            for (Map<String, Object> s : rounds.get(r)) {
                int setting = ((Number) s.get("setting")).intValue();
                cpu[setting][r] = ((Number) s.get("cpuMs")).doubleValue();
                alloc[setting][r] = ((Number) s.get("allocatedBytes")).doubleValue();
                hashes.add(Json.text(s, "hash", ""));
            }
            if (hashes.size() != 1) differing++;
        }
        List<Object> rows = new ArrayList<>();
        for (int s = 0; s < settings; s++) {
            Map<String, Object> row = Json.map("setting", s, "on", on(switches, s), "cpuMsMedian", median(cpu[s]), "allocatedBytesMedian", median(alloc[s]));
            if (s > 0) {
                row.put("cpuSpeedup", ratio(cpu[0], cpu[s]));
                row.put("allocationRatio", ratio(alloc[0], alloc[s]));
            }
            rows.add(row);
        }
        return Json.map("schema", 1, "kind", "toggle", "ok", differing == 0, "switches", switches, "differingRounds", differing, "settings", rows,
            "note", "Same JVM, same input, shuffled order. Above 1 is better for the setting (all switches off is the baseline). "
                + "A setting worth a paired perf-optimize run gains about 10% or more on its own scope, or cuts what young collections copy.");
    }

    static List<String> on(List<String> switches, int setting) {
        List<String> on = new ArrayList<>();
        for (int i = 0; i < switches.size(); i++) {
            if ((setting >> i & 1) != 0) on.add(switches.get(i));
        }
        return on;
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n == 0 ? 0 : n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** Geometric mean of baseline/setting over paired rounds, with perf-optimize's 99% interval. */
    static Map<String, Object> ratio(double[] baseline, double[] setting) {
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < baseline.length; i++) {
            ratios.add(Math.max(baseline[i], 1e-9) / Math.max(setting[i], 1e-9));
        }
        return PerfStopping.interval(ratios);
    }

    static String details(Map<String, Object> result) {
        StringBuilder text = new StringBuilder();
        text.append(Boolean.TRUE.equals(result.get("ok")) ? "Every round returned the same result in every setting."
            : result.get("differingRounds") + " round(s) returned different results between settings: the candidate is not exact.");
        for (Object entry : Json.array(result.get("settings"))) {
            Map<String, Object> row = Json.object(entry);
            text.append(String.format(Locale.ROOT, "%n  on %s: CPU %.4g ms", row.get("on"), ((Number) row.get("cpuMsMedian")).doubleValue()));
            if (row.containsKey("cpuSpeedup")) {
                Map<String, Object> s = Json.object(row.get("cpuSpeedup")), a = Json.object(row.get("allocationRatio"));
                text.append(String.format(Locale.ROOT, ", speedup %.3fx (99%% %.3f-%.3f), allocation %.2fx less",
                    num(s, "geometricMean"), num(s, "low"), num(s, "high"), num(a, "geometricMean")));
            }
        }
        return text.toString();
    }

    static double num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    /** One round, as an eval snippet: every setting once in an order the seed shuffles; switches restored after. */
    static String snippet(List<String> switches, long seed, int reps) {
        StringBuilder names = new StringBuilder();
        for (String s : switches) names.append(names.length() == 0 ? "" : ", ").append('"').append(s).append('"');
        return """
            Object work = vars.get("%s");
            if (!(work instanceof java.util.function.Supplier) && !(work instanceof java.util.concurrent.Callable)) {
                throw new IllegalStateException("The setup snippet must put a java.util.function.Supplier or java.util.concurrent.Callable in vars as \\"%s\\"");
            }
            ClassLoader loader = work.getClass().getClassLoader();
            String[] names = {%s};
            java.lang.reflect.Field[] fields = new java.lang.reflect.Field[names.length];
            boolean[] was = new boolean[names.length];
            for (int i = 0; i < names.length; i++) {
                int dot = names[i].lastIndexOf('.');
                fields[i] = Class.forName(names[i].substring(0, dot), true, loader).getDeclaredField(names[i].substring(dot + 1));
                fields[i].setAccessible(true);
                int modifiers = fields[i].getModifiers();
                if (fields[i].getType() != boolean.class || !java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isFinal(modifiers)) {
                    throw new IllegalArgumentException(names[i] + " must be a static, non-final boolean");
                }
                was[i] = fields[i].getBoolean(null);
            }
            class Deep {
                long h = 1125899906842597L;
                void add(long v) { h = 31 * h + v; }
                void of(Object o) {
                    if (o == null) { add(7); return; }
                    if (o instanceof int[]) { for (int v : (int[]) o) add(v); return; }
                    if (o instanceof long[]) { for (long v : (long[]) o) add(v); return; }
                    if (o instanceof byte[]) { for (byte v : (byte[]) o) add(v); return; }
                    if (o instanceof short[]) { for (short v : (short[]) o) add(v); return; }
                    if (o instanceof char[]) { for (char v : (char[]) o) add(v); return; }
                    if (o instanceof boolean[]) { for (boolean v : (boolean[]) o) add(v ? 1 : 0); return; }
                    if (o instanceof float[]) { for (float v : (float[]) o) add(Float.floatToIntBits(v)); return; }
                    if (o instanceof double[]) { for (double v : (double[]) o) add(Double.doubleToLongBits(v)); return; }
                    if (o instanceof Object[]) { for (Object v : (Object[]) o) of(v); return; }
                    if (o instanceof Map) { for (Object e : ((Map<?, ?>) o).entrySet()) { of(((Map.Entry<?, ?>) e).getKey()); of(((Map.Entry<?, ?>) e).getValue()); } return; }
                    if (o instanceof Iterable) { for (Object v : (Iterable<?>) o) of(v); return; }
                    if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum) { add(o.toString().hashCode()); return; }
                    try {
                        if (o.getClass().getMethod("hashCode").getDeclaringClass() == Object.class) {
                            throw new IllegalArgumentException("The work returned a " + o.getClass().getName() + ", which has no content hash: return arrays, collections, strings or numbers");
                        }
                    } catch (NoSuchMethodException impossible) { }
                    add(o.hashCode());
                }
            }
            com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long id = Thread.currentThread().getId();
            List<Integer> order = new ArrayList<>();
            for (int s = 0; s < (1 << names.length); s++) order.add(s);
            Collections.shuffle(order, new Random(%dL));
            StringBuilder json = new StringBuilder("[");
            try {
                for (int setting : order) {
                    for (int i = 0; i < names.length; i++) fields[i].setBoolean(null, ((setting >> i) & 1) != 0);
                    long a0 = bean.getThreadAllocatedBytes(id);
                    long c0 = bean.getCurrentThreadCpuTime();
                    long w0 = System.nanoTime();
                    Object result = null;
                    for (int rep = 0; rep < %d; rep++) {
                        result = work instanceof java.util.function.Supplier ? ((java.util.function.Supplier<?>) work).get() : ((java.util.concurrent.Callable<?>) work).call();
                    }
                    long w1 = System.nanoTime();
                    long c1 = bean.getCurrentThreadCpuTime();
                    long a1 = bean.getThreadAllocatedBytes(id);
                    Deep deep = new Deep();
                    deep.of(result);
                    if (json.length() > 1) json.append(',');
                    json.append(String.format(Locale.ROOT, "{\\"setting\\":%%d,\\"cpuMs\\":%%.4f,\\"wallMs\\":%%.4f,\\"allocatedBytes\\":%%d,\\"hash\\":\\"%%x\\"}",
                        setting, (c1 - c0) / 1e6 / %d, (w1 - w0) / 1e6 / %d, (a1 - a0) / %d, deep.h));
                }
            } finally {
                for (int i = 0; i < names.length; i++) fields[i].setBoolean(null, was[i]);
            }
            return json.append("]").toString();
            """.formatted(WORK, WORK, names, seed, reps, reps, reps, reps);
    }
}
