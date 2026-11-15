import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * One JSON record per optimization-session run (perf, perf-optimize, a mod's own comparison script), in
 * the same shape every time, so sessions on different mods can be summed and compared
 * ({@code python bench/perf_sessions.py}) and accepted runs can be kept in coverage/perf-runs.json
 * ({@code python bench/record.py perf RECORD}). Written next to the run's output as record.json and
 * copied to .runs/perf-records/ in the modaccel checkout.
 */
final class PerfRecord {
    static final List<String> KINDS = List.of("perf", "perf-optimize", "comparison");
    static final List<String> PHASES = List.of("setup", "build", "launch", "warmup", "measure", "gates", "other");
    static final List<String> VERDICTS = List.of("accepted", "rejected", "observed", "error");

    final Map<String, Object> record = new LinkedHashMap<>();
    final Map<String, Object> phases = new LinkedHashMap<>();
    final List<Object> steps = new ArrayList<>();
    final List<Object> load = new ArrayList<>();

    PerfRecord(String kind, Modaccel.Options options) {
        record.put("schema", 1);
        record.put("id", Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8));
        record.put("kind", kind);
        record.put("session", options == null ? "" : options.get("session", ""));
        // A capture perf-optimize ran for one of its launches: its time is counted in that run's record.
        if (options != null && !options.get("parent", "").isBlank()) record.put("parent", options.get("parent", ""));
        record.put("started", Instant.now().toString());
        record.put("mod", Json.map("id", "", "version", "", "loader", "", "project", options == null ? "" : options.get("project", "")));
        for (String phase : PHASES) phases.put(phase, 0.0);
        record.put("phases", phases);
        record.put("steps", steps);
        record.put("machine", machine());
        record.put("candidate", null);
        record.put("gates", new ArrayList<>());
        record.put("speedup", null);
        record.put("verdict", "error");
        record.put("rejection", null);
    }

    /**
     * The share of a step's time that is the session's own wall time: 0.5 while perf-optimize runs both arms of a
     * pair at once, so phases still add up to the wall time; steps keep their full elapsed seconds.
     */
    volatile double share = 1;

    /** Adds wall time to a phase and keeps the step, so the slowest steps can be found later. */
    synchronized void step(String phase, String name, double seconds) {
        if (!PHASES.contains(phase)) phase = "other";
        if (!Double.isFinite(seconds) || seconds < 0) return;
        double counted = seconds * share;
        phases.put(phase, ((Number) phases.get(phase)).doubleValue() + counted);
        Map<String, Object> step = Json.map("phase", phase, "name", name, "seconds", seconds);
        if (share != 1) step.put("share", share);
        steps.add(step);
    }

    /** Samples machine load now: whole-system CPU over about a second, and how many other JVMs run. */
    void sampleLoad(String when) {
        double cpu = systemCpu();
        long javas = javaProcesses();
        addLoad(when, cpu, javas);
    }

    synchronized void addLoad(String when, double systemCpu, long javas) {
        load.add(Json.map("when", when, "at", Instant.now().toString(), "systemCpu", systemCpu, "javaProcesses", javas));
        Map<String, Object> machine = Json.object(record.get("machine"));
        machine.put("load", load);
        double sum = 0;
        double max = 0;
        int n = 0;
        for (Object item : load) {
            Object cpu = Json.object(item).get("systemCpu");
            if (cpu instanceof Number value && value.doubleValue() >= 0) {
                sum += value.doubleValue();
                max = Math.max(max, value.doubleValue());
                n++;
            }
        }
        machine.put("meanSystemCpu", n == 0 ? null : sum / n);
        machine.put("maxSystemCpu", n == 0 ? null : max);
    }

    static Map<String, Object> machine() {
        String cpu = Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER")).orElse(System.getProperty("os.arch"));
        return Json.map("os", System.getProperty("os.name") + " " + System.getProperty("os.version"), "cpu", cpu,
            "logicalCores", Runtime.getRuntime().availableProcessors(), "load", new ArrayList<>(), "meanSystemCpu", null, "maxSystemCpu", null);
    }

    /** Whole-machine CPU use 0..1 over the next second; -1 when the platform does not say. */
    static double systemCpu() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            os.getCpuLoad();
            Thread.sleep(1000);
            return os.getCpuLoad();
        } catch (Exception | LinkageError unavailable) {
            return -1;
        }
    }

    static long javaProcesses() {
        return ProcessHandle.allProcesses().filter(p -> p.info().command().map(c -> {
            String name = Path.of(c).getFileName().toString().toLowerCase(Locale.ROOT);
            return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
        }).orElse(false)).count() - 1;
    }

    synchronized void mod(String id, String version, String loader) {
        Map<String, Object> mod = Json.object(record.get("mod"));
        if (id != null && !id.isBlank()) mod.put("id", id);
        if (version != null && !version.isBlank()) mod.put("version", version);
        if (loader != null && !loader.isBlank()) mod.put("loader", loader);
    }

    /** The mod a launch receipt says was loaded: the first mod modsLoaded lists. */
    void modFromLaunch(Map<String, Object> launched) {
        Map<String, Object> loaded = Json.object(Json.object(launched.getOrDefault("result", Map.of())).getOrDefault("modsLoaded", Map.of()));
        List<Object> mods = Json.array(loaded.getOrDefault("mods", List.of()));
        Map<String, Object> first = mods.isEmpty() ? Map.of() : Json.object(mods.get(0));
        mod(Json.text(first, "id", ""), Json.text(first, "loadedVersion", Json.text(first, "declaredVersion", "")), Json.text(loaded, "loader", ""));
    }

    /** Build and launch time from a world-check/ui-check receipt's stages; the rest of the step is launch. */
    void launchStages(String name, Map<String, Object> launched, double seconds) {
        double build = 0;
        for (Object item : Json.array(launched.getOrDefault("stages", List.of()))) {
            Map<String, Object> stage = Json.object(item);
            if (Set.of("java-setup", "metadata", "compile", "agent-compile", "own-game").contains(Json.text(stage, "name", ""))
                && stage.get("seconds") instanceof Number value) build += value.doubleValue();
        }
        build = Math.min(build, seconds);
        step("build", name, build);
        step("launch", name, seconds - build);
    }

    Map<String, Object> finish(String verdict, String rejection) {
        record.put("finished", Instant.now().toString());
        record.put("verdict", verdict);
        record.put("rejection", rejection);
        double total = 0;
        for (String phase : PHASES) total += ((Number) phases.get(phase)).doubleValue();
        record.put("seconds", total);
        return record;
    }

    /** Writes record.json into the run's output and a copy into .runs/perf-records of the modaccel checkout. */
    Path save(Path home, Path out) throws Exception {
        List<String> problems = validate(record);
        if (!problems.isEmpty()) record.put("problems", problems);
        Json.save(out.resolve("record.json"), record);
        Path copy = home.resolve(".runs/perf-records").resolve(record.get("id") + ".json");
        Files.createDirectories(copy.getParent());
        Json.save(copy, record);
        return out.resolve("record.json");
    }

    /** Problems with a record; empty when it has every field in the expected shape. */
    static List<String> validate(Map<String, Object> r) {
        List<String> problems = new ArrayList<>();
        if (!Long.valueOf(1).equals(r.get("schema")) && !Integer.valueOf(1).equals(r.get("schema"))) problems.add("schema must be 1");
        if (Json.text(r, "id", "").isBlank()) problems.add("id is required");
        if (!KINDS.contains(Json.text(r, "kind", ""))) problems.add("kind must be one of " + KINDS);
        if (!VERDICTS.contains(Json.text(r, "verdict", ""))) problems.add("verdict must be one of " + VERDICTS);
        if (Json.text(r, "started", "").isBlank()) problems.add("started is required");
        Map<String, Object> mod = r.get("mod") instanceof Map<?, ?> m ? Json.object(m) : null;
        if (mod == null || !mod.containsKey("id") || !mod.containsKey("version") || !mod.containsKey("loader")) problems.add("mod needs id, version and loader");
        Map<String, Object> phases = r.get("phases") instanceof Map<?, ?> p ? Json.object(p) : Map.of();
        for (String phase : PHASES) if (!(phases.get(phase) instanceof Number)) problems.add("phases." + phase + " must be seconds");
        for (String key : phases.keySet()) if (!PHASES.contains(key)) problems.add("unknown phase " + key);
        if (!(r.get("machine") instanceof Map<?, ?>)) problems.add("machine is required");
        if (!(r.get("gates") instanceof List<?>)) problems.add("gates must be a list");
        if ("perf-optimize".equals(r.get("kind")) || "comparison".equals(r.get("kind"))) {
            Map<String, Object> candidate = r.get("candidate") instanceof Map<?, ?> c ? Json.object(c) : null;
            if (candidate == null || Json.text(candidate, "label", "").isBlank() || Json.text(candidate, "diffSha256", "").isBlank()) {
                problems.add("a comparison names its candidate: label and diffSha256");
            }
        }
        if ("accepted".equals(r.get("verdict"))) {
            Map<String, Object> speed = r.get("speedup") instanceof Map<?, ?> s ? Json.object(s) : Map.of();
            for (String phase : List.of("cold", "warm")) {
                Map<String, Object> s = speed.get(phase) instanceof Map<?, ?> x ? Json.object(x) : Map.of();
                if (!(s.get("low") instanceof Number low) || low.doubleValue() <= 1) problems.add("an accepted run needs speedup." + phase + ".low above 1");
            }
        }
        if ("rejected".equals(r.get("verdict")) && Json.text(r, "rejection", "").isBlank()) problems.add("a rejected run says why");
        return problems;
    }

    /** Why a perf-optimize result was rejected, in one line. */
    static String rejection(Map<String, Object> result) {
        if (result.containsKey("error")) return "error: " + result.get("error");
        List<String> reasons = new ArrayList<>();
        for (Object item : Json.array(result.getOrDefault("gates", List.of()))) {
            Map<String, Object> gate = Json.object(item);
            if (!Boolean.TRUE.equals(gate.get("passed"))) reasons.add("gate " + gate.get("name") + " failed: " + gate.get("failures"));
        }
        if (result.get("speedup") instanceof Map<?, ?> speedup) for (String phase : List.of("cold", "warm")) {
            Map<String, Object> s = Json.object(speedup.get(phase));
            if (s.get("low") instanceof Number low && low.doubleValue() <= 1) {
                double high = ((Number) s.get("high")).doubleValue();
                reasons.add(String.format(Locale.ROOT, "%s speedup %.3fx, 95%% interval %.3f-%.3f %s", phase,
                    ((Number) s.get("geometricMean")).doubleValue(), low.doubleValue(), high, high < 1 ? "lies below 1 (slower)" : "includes 1"));
            }
        }
        if (result.get("stopped") instanceof Map<?, ?> stopped && "rejected".equals(stopped.get("decided")) && !reasons.isEmpty()) {
            reasons.add("stopped after " + stopped.get("afterPairs") + " pair(s)");
        }
        return reasons.isEmpty() ? "not accepted" : String.join("; ", reasons);
    }

    /** The interval fields of a speedup summary, without its raw sample lists. */
    static Map<String, Object> interval(Map<String, Object> s) {
        return Json.map("geometricMean", s.get("geometricMean"), "low", s.get("low"), "high", s.get("high"), "pairs", s.get("pairs"),
            "baselineMean", s.get("baselineMean"), "candidateMean", s.get("candidateMean"));
    }
}
