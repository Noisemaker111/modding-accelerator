import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import jdk.jfr.consumer.*;

/**
 * Charges each sampled stack, allocation, lock wait and I/O event to the mod whose code is
 * innermost on that stack, and to the feature through which the game entered that mod.
 * Game, JVM, loader and library frames are transparent: work they do for a mod is the mod's.
 */
final class PerfAttribution {
    static final Pattern NAMED_MIXIN = Pattern.compile(
        "^(?:handler|redirect|modify|localvar|wrapOperation|wrapWithCondition|constant|args|modifyExpressionValue|modifyReturnValue)\\$[a-z0-9]+\\$([a-z0-9_.-]+)\\$(.+)$");
    static final Pattern MIXIN = Pattern.compile(
        "^(?:handler|redirect|modify|localvar|wrapOperation|wrapWithCondition|constant|args|modifyExpressionValue|modifyReturnValue)\\$[a-z0-9]+\\$(.+)$");
    static final List<String> TRANSPARENT = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.",
        "net.fabricmc.loader.", "net.neoforged.fml.", "net.neoforged.bus.", "net.minecraftforge.fml.", "net.minecraftforge.eventbus.",
        "cpw.mods.", "org.spongepowered.", "com.llamalad7.mixinextras.", "org.objectweb.asm.", "dev.modaccel.");
    static final List<String> EVENT_BUSES = List.of("net.neoforged.bus.", "net.minecraftforge.eventbus.", "net.fabricmc.fabric.api.event.",
        "net.fabricmc.fabric.impl.base.event.", "net.neoforged.neoforge.common.NeoForge", "net.minecraftforge.common.MinecraftForge");
    static final Set<String> PLATFORM = Set.of("minecraft", "java", "fabricloader", "neoforge", "forge", "mixinextras", "fml");

    final Properties classes, features;
    final Path root;
    final List<String> ownPackages;
    final Map<String, Origin> byLocation = new HashMap<>();
    final Map<String, Origin> byId = new HashMap<>();
    final Map<String, Unit> units = new TreeMap<>();
    final Map<String, Long> threadSamples = new HashMap<>();
    final Map<String, double[]> threadCpu = new TreeMap<>();
    long samples, attributedSamples, profilerSamples;
    double allocationTotal, blockedTotal, profilerAllocation;

    record Origin(String key, String name, boolean mod, String location) { }

    static final class Tally {
        long samples, self;
        double allocation, blockedMs, ioBytes;
        final Map<String, Long> methods = new HashMap<>(), threads = new HashMap<>();
    }

    static final class Unit {
        final Origin origin;
        final Tally total = new Tally();
        final Map<String, Tally> features = new HashMap<>();
        final Map<String, String> featureKinds = new HashMap<>();
        final Map<String, Long> heapBytes = new HashMap<>(), heapInstances = new HashMap<>();
        /** Samples with each of this mod's methods anywhere on the stack: CPU spent in it and what it called. */
        final Map<String, Long> inclusiveMethods = new HashMap<>();
        long inclusive;
        Unit(Origin origin) { this.origin = origin; }
    }

    record Hit(Unit unit, String feature, String kind, String method) { }

    PerfAttribution(Properties classes, Properties features, Path root, List<String> ownPackages) {
        this.classes = classes;
        this.features = features;
        this.root = root;
        this.ownPackages = ownPackages;
        for (String name : classes.stringPropertyNames()) origin(name);
    }

    /** The mod owning this frame, or null for game, JVM, loader, platform and library code. */
    Origin owner(String type, String method) {
        if (type.startsWith("net.minecraft.") || type.startsWith("com.mojang.")) {
            Matcher named = NAMED_MIXIN.matcher(method);
            if (named.matches() && byId.containsKey(named.group(1))) return byId.get(named.group(1));
            if (MIXIN.matcher(method).matches()) return byId.computeIfAbsent("[mixin]", k -> new Origin(k, "Mixin handlers (mod not named in method)", true, ""));
            return null;
        }
        Origin origin = origin(type);
        return origin != null && origin.mod ? origin : null;
    }

    Origin origin(String type) {
        for (String prefix : TRANSPARENT) if (type.startsWith(prefix)) return null;
        String name = type;
        String location = classes.getProperty(name);
        // Hidden lambda classes and nested classes share their host's origin.
        if (location == null && name.contains("$$")) location = classes.getProperty(name = name.substring(0, name.indexOf("$$")));
        if (location == null && name.contains("$")) location = classes.getProperty(name.substring(0, name.indexOf('$')));
        boolean own = ownPackages.stream().anyMatch(type::startsWith);
        if (location == null || location.equals("ambiguous")) {
            return own ? byId.computeIfAbsent("[your mod]", k -> new Origin(k, "Your mod (" + String.join(", ", ownPackages) + ")", true, "")) : null;
        }
        String known = location;
        Origin cached = byLocation.get(known);
        if (cached != null || byLocation.containsKey(known)) return cached;
        Origin resolved = resolve(location, own);
        byLocation.put(known, resolved);
        if (resolved != null && resolved.mod) byId.putIfAbsent(resolved.key, resolved);
        return resolved;
    }

    /**
     * Whether a class location is the mod's own build output. Gradle's caches inside the checkout are
     * not: Loom keeps the dependencies it remaps (Fabric API and every other mod) under .gradle.
     */
    static boolean underRoot(Path path, Path root) {
        if (path == null || root == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) && !normalized.startsWith(root.resolve(".gradle"));
    }

    Origin resolve(String location, boolean own) {
        // Mod Accel's own control and profiling agents are never the mod being measured.
        if (location.contains("/.modaccel/") || location.contains("modaccel-agent") || location.contains("modaccel-perf-agent")) return null;
        Path path = path(location);
        String[] metadata = path == null ? null : metadata(path);
        boolean underRoot = underRoot(path, root);
        if (metadata != null && PLATFORM.contains(metadata[0])) return null;
        if (metadata != null) return new Origin(metadata[0], metadata[1] + " (" + metadata[0] + ")" + (underRoot ? " · your mod" : ""), true, location);
        if (own || underRoot) return new Origin("[your mod]", "Your mod (" + (root == null ? location : root.getFileName()) + ")", true, location);
        return null;
    }

    static Path path(String location) {
        try {
            String text = location;
            for (String prefix : List.of("union:", "jar:")) if (text.startsWith(prefix)) text = text.substring(prefix.length());
            int cut = text.indexOf("%23");
            if (cut < 0) cut = text.indexOf('#');
            if (cut < 0) cut = text.indexOf("!/");
            if (cut >= 0) text = text.substring(0, cut);
            if (text.startsWith("file:")) return Path.of(URI.create(text));
            // NeoForge's union: locations are URI paths without the scheme ("/C:/...").
            if (text.startsWith("/")) return Path.of(URI.create("file:" + text));
            return Path.of(URLDecoder.decode(text, StandardCharsets.UTF_8));
        } catch (Exception unreadable) { return null; }
    }

    /** {id, display name} from fabric.mod.json or mods.toml, in the jar, the directory, or Gradle's matching resources folder. */
    static String[] metadata(Path path) {
        try {
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                try (ZipFile jar = new ZipFile(path.toFile())) {
                    for (String file : List.of("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "quilt.mod.json")) {
                        ZipEntry entry = jar.getEntry(file);
                        if (entry != null && entry.getSize() < 1_048_576) try (InputStream in = jar.getInputStream(entry)) {
                            String[] parsed = parse(file, new String(in.readNBytes(1_048_576), StandardCharsets.UTF_8));
                            if (parsed != null) return parsed;
                        }
                    }
                }
                return null;
            }
            List<Path> folders = new ArrayList<>(List.of(path));
            Path normalized = path.toAbsolutePath().normalize();
            // Gradle keeps classes and resources apart: build/classes/java/main -> build/resources/main.
            if (normalized.getNameCount() >= 3 && normalized.getParent().getParent().getFileName().toString().equals("classes")) {
                folders.add(normalized.getParent().getParent().getParent().resolve("resources").resolve(normalized.getFileName()));
            }
            for (Path folder : folders) for (String file : List.of("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "quilt.mod.json")) {
                Path candidate = folder.resolve(file);
                if (Files.isRegularFile(candidate) && Files.size(candidate) < 1_048_576) {
                    String[] parsed = parse(file, Files.readString(candidate));
                    if (parsed != null) return parsed;
                }
            }
        } catch (Exception unreadable) { }
        return null;
    }

    static String[] parse(String file, String text) {
        if (file.endsWith(".json")) {
            Map<String, Object> data = Json.object(Json.parse(text));
            if (data.get("quilt_loader") instanceof Map<?, ?> quilt) data = Json.object(quilt);
            String id = Json.text(data, "id", "");
            if (id.isBlank()) return null;
            Object meta = data.get("metadata");
            String name = Json.text(data, "name", meta instanceof Map<?, ?> m && m.get("name") != null ? m.get("name").toString() : id);
            return new String[]{id, name};
        }
        Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(text);
        if (!id.find() || id.group(1).contains("${")) return null;
        Matcher name = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"").matcher(text);
        return new String[]{id.group(1), name.find() && !name.group(1).contains("${") ? name.group(1) : id.group(1)};
    }

    static String typeName(RecordedFrame frame) {
        return frame.getMethod().getType().getName();
    }

    static String methodName(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        return method.getType().getName() + "." + method.getName() + method.getDescriptor();
    }

    /** Innermost mod on the stack, the feature through which the game entered it, and its innermost method. */
    Hit hit(List<RecordedFrame> frames) {
        int top = -1;
        Origin owner = null;
        for (int i = 0; i < frames.size() && owner == null; i++) {
            RecordedMethod method = frames.get(i).getMethod();
            owner = owner(method.getType().getName(), method.getName());
            if (owner != null) top = i;
        }
        if (owner == null) return null;
        Unit unit = units.computeIfAbsent(owner.key, k -> new Unit(byId.getOrDefault(k, byLocation.values().stream().filter(o -> o != null && o.key.equals(k)).findFirst().orElse(null))));
        int entry = top;
        while (entry + 1 < frames.size()) {
            RecordedMethod below = frames.get(entry + 1).getMethod();
            Origin next = owner(below.getType().getName(), below.getName());
            if (next == null || !next.key.equals(owner.key)) break;
            entry++;
        }
        RecordedMethod entered = frames.get(entry).getMethod();
        int below = entry + 1;
        // Method handles and hidden lambda classes sit between an event bus and its handler.
        while (below < frames.size() && plumbing(typeName(frames.get(below)))) below++;
        String caller = below < frames.size() ? typeName(frames.get(below)) : "";
        String kind = null, label = null;
        Matcher mixin = MIXIN.matcher(entered.getName());
        if (mixin.matches()) {
            Matcher named = NAMED_MIXIN.matcher(entered.getName());
            kind = "mixin";
            label = simple(entered.getType().getName()) + " ← " + (named.matches() ? named.group(2) : mixin.group(1));
        } else if (EVENT_BUSES.stream().anyMatch(caller::startsWith)) {
            kind = "event handler";
            label = handler(frames, entry, top);
        } else {
            List<String> types = new ArrayList<>();
            for (RecordedFrame frame : frames) types.add(typeName(frame));
            String[] found = typed(types, entry, top, features);
            if (found != null) {
                kind = found[0];
                label = found[1];
            }
        }
        if (kind == null) {
            kind = "other code";
            label = simple(entered.getType().getName()) + "." + entered.getName();
        }
        return new Hit(unit, kind + " · " + label, kind, methodName(frames.get(top)));
    }

    /**
     * The kind and name of the outermost frame, from entry in to top, whose class (or its outer class) has a known
     * feature kind. Lambdas and method handles are skipped: a block's ticker lambda (BlockMain$$Lambda.0x1a8a9314fd0)
     * would otherwise name the feature by its hidden class's address, where the block entity method it calls names it.
     */
    static String[] typed(List<String> types, int entry, int top, Properties features) {
        for (int i = entry; i >= top; i--) {
            String type = types.get(i);
            if (plumbing(type)) continue;
            String outer = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
            String found = features.getProperty(type, features.getProperty(outer));
            if (found != null) return new String[] {found, simple(type)};
        }
        return null;
    }

    static boolean plumbing(String type) {
        return type.startsWith("java.lang.invoke.") || type.contains("$$Lambda") || type.matches(".*\\.0x[0-9a-f]+$");
    }

    String handler(List<RecordedFrame> frames, int entry, int top) {
        for (int i = entry; i >= top; i--) {
            RecordedMethod method = frames.get(i).getMethod();
            if (plumbing(method.getType().getName())) continue;
            String event = "";
            Matcher parameter = Pattern.compile("^\\(L([^;]+);").matcher(method.getDescriptor());
            if (parameter.find()) event = simple(parameter.group(1).replace('/', '.')) + " → ";
            return event + simple(method.getType().getName()) + "." + method.getName();
        }
        return simple(frames.get(entry).getMethod().getType().getName());
    }

    static String simple(String type) {
        return type.substring(type.lastIndexOf('.') + 1).replace('$', '.');
    }

    /** Work the profiler's own hooks and timing probes did, often inside a mod's method: never the mod's cost. */
    static boolean profiler(List<RecordedFrame> frames) {
        for (RecordedFrame frame : frames) if (typeName(frame).startsWith("dev.modaccel.")) return true;
        return false;
    }

    void sample(List<RecordedFrame> frames, String thread) {
        samples++;
        threadSamples.merge(thread, 1L, Long::sum);
        if (profiler(frames)) {
            profilerSamples++;
            return;
        }
        Hit hit = hit(frames);
        Set<String> present = new HashSet<>();
        Map<String, Set<String>> methodsPresent = new HashMap<>();
        for (RecordedFrame frame : frames) {
            Origin origin = owner(frame.getMethod().getType().getName(), frame.getMethod().getName());
            if (origin == null) continue;
            present.add(origin.key);
            methodsPresent.computeIfAbsent(origin.key, k -> new HashSet<>()).add(methodName(frame));
        }
        for (var entry : methodsPresent.entrySet()) {
            Unit unit = units.get(entry.getKey());
            if (unit != null) for (String method : entry.getValue()) unit.inclusiveMethods.merge(method, 1L, Long::sum);
        }
        for (String key : present) {
            Unit unit = units.get(key);
            if (unit != null) unit.inclusive++;
        }
        if (hit == null) return;
        attributedSamples++;
        boolean self = owner(typeName(frames.get(0)), frames.get(0).getMethod().getName()) != null;
        for (Tally tally : List.of(hit.unit.total, feature(hit))) {
            tally.samples++;
            if (self) tally.self++;
            tally.methods.merge(hit.method, 1L, Long::sum);
            tally.threads.merge(thread, 1L, Long::sum);
        }
    }

    Tally feature(Hit hit) {
        hit.unit.featureKinds.put(hit.feature, hit.kind);
        return hit.unit.features.computeIfAbsent(hit.feature, k -> new Tally());
    }

    void allocation(RecordedEvent event, long weight) {
        allocationTotal += weight;
        if (profiler(PerfReport.frames(event.getStackTrace()))) {
            profilerAllocation += weight;
            return;
        }
        Hit hit = stack(event);
        if (hit == null) return;
        hit.unit.total.allocation += weight;
        feature(hit).allocation += weight;
    }

    void blocked(RecordedEvent event, double ms) {
        blockedTotal += ms;
        Hit hit = stack(event);
        if (hit == null) return;
        hit.unit.total.blockedMs += ms;
        feature(hit).blockedMs += ms;
    }

    void io(RecordedEvent event, long bytes) {
        Hit hit = stack(event);
        if (hit == null) return;
        hit.unit.total.ioBytes += bytes;
        feature(hit).ioBytes += bytes;
    }

    void threadCpu(RecordedEvent event) {
        RecordedThread thread = event.getThread();
        if (thread == null) return;
        double[] sum = threadCpu.computeIfAbsent(thread.getJavaName() == null ? thread.getOSName() : thread.getJavaName(), k -> new double[2]);
        sum[0] += event.getFloat("user") + event.getFloat("system");
        sum[1]++;
    }

    Hit stack(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        List<RecordedFrame> frames = PerfReport.frames(trace);
        return frames.isEmpty() ? null : hit(frames);
    }

    /** Shallow live bytes of each mod's own classes from the post-capture class histogram. */
    void heap(Path histogram) throws IOException {
        if (!Files.isRegularFile(histogram)) return;
        Pattern row = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
        for (String line : Files.readAllLines(histogram)) {
            Matcher match = row.matcher(line);
            if (!match.find()) continue;
            String type = match.group(3);
            while (type.startsWith("[")) type = type.substring(1);
            if (type.startsWith("L") && type.endsWith(";")) type = type.substring(1, type.length() - 1);
            Origin origin = owner(type, "");
            if (origin == null) continue;
            Unit unit = units.computeIfAbsent(origin.key, k -> new Unit(origin));
            unit.heapBytes.merge(match.group(3), Long.parseLong(match.group(2)), Long::sum);
            unit.heapInstances.merge(match.group(3), Long.parseLong(match.group(1)), Long::sum);
        }
    }

    static long busiest(Map<String, Long> threads, String name) {
        return threads.getOrDefault(name, 0L);
    }

    Map<String, Object> finish(Map<String, Object> report, Properties status, List<Map<String, Object>> timedCalls, Map<String, double[]> totals,
                               String timingSelectors, double seconds) {
        long period = Long.parseLong(status.getProperty("samplePeriodMs", "0"));
        Map<String, Object> ticks = report.get("metrics") instanceof Map<?, ?> m && m.get("tick_ms") instanceof Map<?, ?> t ? Json.object(t) : null;
        Map<String, Object> frames = report.get("metrics") instanceof Map<?, ?> m && m.get("frame_ms") instanceof Map<?, ?> f ? Json.object(f) : null;
        Map<String, String> selectorClass = new HashMap<>();
        for (String selector : timingSelectors.split("\n")) {
            if (selector.isBlank() || !selector.contains("=")) continue;
            String target = selector.substring(selector.indexOf('=') + 1);
            String owner = target.substring(0, target.lastIndexOf('.', target.indexOf('(')));
            selectorClass.put(selector.substring(0, selector.indexOf('=')), owner);
        }
        Map<String, List<Map<String, Object>>> exact = new HashMap<>();
        // Complete agent totals when present; older captures fall back to the retained per-call events.
        Map<String, double[]> exactTotals = new TreeMap<>(totals);
        for (Map<String, Object> call : totals.isEmpty() ? timedCalls : List.<Map<String, Object>>of()) {
            String label = call.get("label").toString();
            if (label.startsWith("@")) continue;
            double[] sum = exactTotals.computeIfAbsent(label, k -> new double[3]);
            sum[0]++;
            sum[1] += ((Number) call.get("inclusiveMs")).doubleValue();
            sum[2] += ((Number) call.get("selfMs")).doubleValue();
        }
        long tickCount = ticks == null ? 0 : ((Number) ticks.get("n")).longValue();
        for (var entry : exactTotals.entrySet()) {
            String type = selectorClass.get(entry.getKey());
            Origin origin = type == null ? null : owner(type, "");
            String key = origin == null ? "[unattributed]" : origin.key;
            if (origin != null) units.computeIfAbsent(key, k -> new Unit(origin));
            double[] v = entry.getValue();
            if (entry.getKey().startsWith("@")) continue;
            Map<String, Object> row = Json.map("label", entry.getKey(), "class", type, "complete", !totals.isEmpty(), "calls", (long) v[0],
                "inclusiveTotalMs", v[1], "selfTotalMs", v[2], "meanInclusiveMs", v[1] / v[0]);
            if (tickCount > 0) {
                row.put("callsPerTick", v[0] / tickCount);
                row.put("inclusiveMsPerTick", v[1] / tickCount);
            }
            row.put("inclusiveMsPerSecond", v[1] / seconds);
            exact.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        long serverSamples = threadSamples.getOrDefault("Server thread", 0L);
        long renderSamples = threadSamples.getOrDefault("Render thread", 0L);
        List<Object> rows = new ArrayList<>();
        for (Unit unit : units.values()) {
            Map<String, Object> row = Json.map("id", unit.origin == null ? "unknown" : unit.origin.key,
                "name", unit.origin == null ? "unknown" : unit.origin.name,
                "yourMod", unit.origin != null && (unit.origin.key.equals("[your mod]") || unit.origin.name.endsWith("· your mod")));
            row.put("samples", unit.total.samples);
            row.put("selfSamples", unit.total.self);
            row.put("inclusiveSamples", unit.inclusive);
            row.put("percentOfSamples", samples == 0 ? 0 : 100.0 * unit.total.samples / samples);
            if (period > 0) row.put("sampledCpuMsPerSecond", unit.total.samples * period / seconds);
            share(row, "perTick", busiest(unit.total.threads, "Server thread"), serverSamples, ticks);
            share(row, "perFrame", busiest(unit.total.threads, "Render thread"), renderSamples, frames);
            row.put("allocationBytesPerSecond", unit.total.allocation / seconds);
            row.put("blockedMs", unit.total.blockedMs);
            row.put("ioBytes", unit.total.ioBytes);
            long heap = unit.heapBytes.values().stream().mapToLong(Long::longValue).sum();
            if (!unit.heapBytes.isEmpty()) {
                row.put("heapShallowBytes", heap);
                row.put("heapClasses", top(unit.heapBytes, 20, "bytes"));
            }
            row.put("threads", top(unit.total.threads, 20, "samples"));
            row.put("methods", top(unit.total.methods, 30, "samples"));
            // Inclusive CPU per method, for timing a scope by samples rather than wall time, which
            // load on the machine stretches (perf-optimize scopes with "method").
            List<Object> inclusiveMethods = new ArrayList<>();
            for (Object item : top(unit.inclusiveMethods, 60, "samples")) {
                Map<String, Object> method = Json.object(item);
                if (period > 0) method.put("cpuMsPerSecond", ((Number) method.get("samples")).doubleValue() * period / seconds);
                inclusiveMethods.add(method);
            }
            row.put("inclusiveMethods", inclusiveMethods);
            List<Object> features = new ArrayList<>();
            unit.features.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Tally> e) -> -(e.getValue().samples + e.getValue().allocation / 1e9)))
                .limit(60).forEach(e -> {
                    Tally t = e.getValue();
                    Map<String, Object> feature = Json.map("name", e.getKey(), "kind", unit.featureKinds.get(e.getKey()),
                        "samples", t.samples, "selfSamples", t.self,
                        "percentOfSamples", samples == 0 ? 0 : 100.0 * t.samples / samples,
                        "allocationBytesPerSecond", t.allocation / seconds, "blockedMs", t.blockedMs, "ioBytes", t.ioBytes,
                        "methods", top(t.methods, 10, "samples"), "threads", top(t.threads, 5, "samples"));
                    share(feature, "perTick", busiest(t.threads, "Server thread"), serverSamples, ticks);
                    share(feature, "perFrame", busiest(t.threads, "Render thread"), renderSamples, frames);
                    features.add(feature);
                });
            row.put("features", features);
            row.put("exact", exact.getOrDefault(row.get("id").toString(), List.of()));
            rows.add(row);
        }
        rows.sort(Comparator.comparingDouble((Object r) -> -((Number) Json.object(r).get("samples")).doubleValue()
            - ((Number) Json.object(r).get("allocationBytesPerSecond")).doubleValue() / 1e9));
        List<Object> threads = new ArrayList<>();
        threadCpu.forEach((name, sum) -> threads.add(Json.map("thread", name, "meanCpuPercentOfMachine", 100 * sum[0] / sum[1], "intervals", (long) sum[1],
            "samples", threadSamples.getOrDefault(name, 0L))));
        threads.sort(Comparator.comparingDouble((Object r) -> -((Number) Json.object(r).get("meanCpuPercentOfMachine")).doubleValue()));
        return Json.map("mods", rows, "threads", threads.stream().limit(40).toList(),
            "executionSamples", samples, "attributedSamples", attributedSamples, "samplePeriodMs", period,
            "profilerOverhead", Json.map("samples", profilerSamples, "allocationBytesPerSecond", profilerAllocation / seconds,
                "meaning", "Samples and allocation inside modaccel's own frame/tick hooks and timing probes; charged to no mod."),
            "unattributed", exact.getOrDefault("[unattributed]", List.of()),
            "basis", Json.map(
                "sampled", "Execution samples, allocation samples, lock and I/O events are charged to the innermost mod frame on their stack; "
                    + "game, JVM, loader and library frames above it count as that mod's work. Feature = how the game entered that mod "
                    + "(event bus → event handler, mixin handler name, or the block/entity/renderer/goal/screen class on the stack). Sampled values are estimates.",
                "perTick", "Share of server-thread samples × mean measured tick duration. Interval is a binomial 95% range on that share; "
                    + "samples are autocorrelated, so treat it as approximate. Waits inside a tick are not sampled.",
                "perFrame", "Share of render-thread samples × mean measured frame interval. GPU work, vsync and driver waits are not sampled, so this is CPU-side only.",
                "exact", "Timing probes measure every completed call of the selected method: exact call counts and wall time, including profiler overhead.",
                "heap", "Shallow bytes of live instances of each mod's own classes after a full GC at the end of the capture; "
                    + "objects of game or JDK classes that a mod holds are not included (not a retained-size analysis).",
                "threads", "Per-thread CPU load reported by the operating system through JFR (exact per interval, not sampled).",
                "mixins", "Mixin handlers merged into game classes are charged to the mod named in the handler (Fabric). Overwrites, "
                    + "unnamed handlers and @Unique helpers cannot be traced to a mod."));
    }

    static void share(Map<String, Object> row, String prefix, long hits, long total, Map<String, Object> budget) {
        if (total <= 0 || budget == null) return;
        double p = (double) hits / total;
        double margin = 1.96 * Math.sqrt(p * (1 - p) / total);
        double mean = ((Number) budget.get("mean")).doubleValue();
        row.put(prefix + "Share", p);
        row.put(prefix + "Ms", p * mean);
        row.put(prefix + "MsLow", Math.max(0, p - margin) * mean);
        row.put(prefix + "MsHigh", Math.min(1, p + margin) * mean);
    }

    static List<Object> top(Map<String, ? extends Number> values, int limit, String unit) {
        return values.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue())).limit(limit)
            .map(e -> (Object) Json.map("name", e.getKey(), unit, e.getValue())).toList();
    }

    /** Timing selectors for the hottest sampled entry methods of a mod, ready for perf --probes. */
    static Map<String, Object> suggestions(Map<String, Object> attribution) {
        List<Object> selectors = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Object item : Json.array(attribution.get("mods"))) {
            Map<String, Object> mod = Json.object(item);
            if (selectors.size() >= 16) break;
            if (((Number) mod.get("samples")).longValue() == 0) continue;
            // Coarse methods first (most samples with them anywhere on the stack): timing a caller costs
            // a few calls, timing a per-block leaf costs millions and distorts the capture.
            List<Object> ranked = Json.array(mod.getOrDefault("inclusiveMethods", List.of()));
            for (Object m : ranked.isEmpty() ? Json.array(mod.get("methods")) : ranked) {
                if (selectors.size() >= 16) break;
                String name = Json.object(m).get("name").toString();
                int paren = name.indexOf('(');
                int dot = name.lastIndexOf('.', paren);
                String type = name.substring(0, dot), method = name.substring(dot + 1, paren), descriptor = name.substring(paren);
                if (method.startsWith("<") || method.startsWith("lambda$") || type.startsWith("net.minecraft.") || type.contains("$$")) continue;
                String label = (simple(type) + "." + method).replaceAll("[^A-Za-z0-9_.-]", "_");
                if (!labels.add(label)) continue;
                selectors.add(Json.map("class", type, "method", method, "descriptor", descriptor, "label", label, "mode", "timing",
                    "mod", mod.get("id"), "sampledShare", Json.object(m).get("samples")));
            }
        }
        return Json.map("probes", selectors,
            "meaning", "Timing selectors for each mod's methods with the most samples on the stack, coarse callers first. Run perf again with --probes this file for exact call counts and wall time; "
                + "remove selectors for very hot tiny methods if overhead distorts the capture.");
    }
}
