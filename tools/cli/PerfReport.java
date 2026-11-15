import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming JFR reader. Unknown measurements remain absent, never invented as zeros. */
final class PerfReport {
    static final int LIMIT = 2_000_000;
    final Map<String, List<Double>> metrics = new TreeMap<>();
    final Map<String, Long> events = new TreeMap<>();
    final Map<String, Long> methods = new HashMap<>(), owners = new HashMap<>(), threads = new HashMap<>();
    final Map<String, Integer> frameIds = new LinkedHashMap<>();
    final List<Object> stacks = new ArrayList<>();
    final Map<Long, Map<String, Object>> timeline = new TreeMap<>();
    final List<String> warnings = new ArrayList<>();
    final Properties classes;
    final PerfFunctions functions = new PerfFunctions();
    final PerfAttribution attribution;
    Instant start, end;
    boolean hidden;
    final Map<String, Long> allocationSites = new HashMap<>(), allocationClasses = new HashMap<>();
    final Map<String, Double> lockSites = new HashMap<>();
    final Map<String, Long> probeCalls = new TreeMap<>();
    final Map<String, double[]> timingTotals = new TreeMap<>();
    long executionSamples, truncated;
    long readBytes, writeBytes, socketRead, socketWrite, allocationWeight;
    double gcPauseMs, blockedMs;
    Long firstCpu, lastCpu;
    Double firstCpuTime, lastCpuTime;

    PerfReport(Properties classes, PerfAttribution attribution) {
        this.classes = classes;
        this.attribution = attribution;
    }

    static Map<String, Object> write(Path input, Path output) throws Exception {
        Path recording = Files.isDirectory(input) ? input.resolve("capture.jfr") : input;
        if (!Files.isRegularFile(recording)) throw new IOException("No recording at " + recording);
        Path directory = recording.toAbsolutePath().getParent();
        Properties classes = Performance.properties(directory.resolve("classes.properties"));
        Properties request = Performance.properties(directory.resolve("request.properties"));
        String rootText = request.getProperty("attributionRoot", "");
        Path root = rootText.isBlank() ? null : Path.of(rootText).toAbsolutePath().normalize();
        List<String> own = Arrays.stream(request.getProperty("modPackages", "").split(",")).map(String::strip).filter(v -> !v.isEmpty()).toList();
        PerfReport report = new PerfReport(classes, new PerfAttribution(classes,
            Performance.properties(directory.resolve("features.properties")), root, own));
        try (RecordingFile stream = new RecordingFile(recording)) {
            while (stream.hasMoreEvents()) report.accept(stream.readEvent());
        }
        if (report.start == null) throw new IOException("Recording contains no events");
        report.attribution.heap(directory.resolve("heap-histogram.txt"));
        Files.createDirectories(output);
        Properties status = Performance.properties(directory.resolve("status.properties"));
        Properties environment = Performance.properties(directory.resolve("environment.properties"));
        report.hidden = Boolean.parseBoolean(environment.getProperty("modaccel.background", "false"));
        Map<String, Object> result = report.finish(status);
        result.put("environment", new TreeMap<>(environment));
        result.put("recording", recording.toAbsolutePath().toString());
        result.put("sha256", Performance.sha256(recording));
        Path inventory = directory.resolve("inventory.json");
        if (Files.isRegularFile(inventory)) result.put("inventory", Json.read(inventory));
        Json.save(output.resolve("report.json"), result);
        Json.save(output.resolve("probes.suggested.json"), PerfAttribution.suggestions(Json.object(result.get("attribution"))));
        Json.save(output.resolve("stacks.speedscope.json"), report.speedscope());
        report.functions.trace(output, report.start);
        String data = Json.write(result).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        Files.writeString(output.resolve("report.html"), HTML.replace("__DATA__", data));
        return Json.map("ok", true, "html", output.resolve("report.html").toString(),
            "report", output.resolve("report.json").toString(), "recording", recording.toString(),
            "details", "Open " + output.resolve("report.html") + " in your browser. Raw JFR and sampled stacks are retained locally.");
    }

    void add(String name, double value) {
        if (!Double.isFinite(value) || value < 0) return;
        List<Double> values = metrics.computeIfAbsent(name, k -> new ArrayList<>());
        if (values.size() < LIMIT) values.add(value);
        else truncated++;
    }

    void accept(RecordedEvent event) {
        functions.context(event);
        String name = event.getEventType().getName();
        events.merge(name, 1L, Long::sum);
        Instant time = event.getStartTime();
        if (start == null || time.isBefore(start)) start = time;
        Instant finished = event.getEndTime();
        if (end == null || finished.isAfter(end)) end = finished;
        long bucket = time.getEpochSecond();
        Map<String, Object> point = timeline.computeIfAbsent(bucket, key -> Json.map("epochSecond", key));
        switch (name) {
            case "modaccel.MethodTiming" -> functions.accept(event);
            case "modaccel.TimingTotals" -> timingTotals.put(event.getString("label"),
                new double[]{event.getLong("calls"), event.getLong("inclusiveNanos") / 1e6, event.getLong("selfNanos") / 1e6});
            case "modaccel.ProbeCalls" -> probeCalls.merge(event.getString("label"), event.getLong("calls"), Long::sum);
            case "modaccel.Pulse" -> {
                String kind = event.getString("kind");
                double ms = event.getLong("nanos") / 1e6;
                add(kind + "_ms", ms);
                point.merge(kind + "Count", 1L, (a, b) -> ((Number) a).longValue() + 1);
                point.merge(kind + "MaxMs", ms, (a, b) -> Math.max(((Number) a).doubleValue(), ms));
            }
            case "modaccel.Process" -> {
                long heap = event.getLong("heapUsed");
                add("heap_used_bytes", heap);
                point.put("heapMiB", heap / 1048576.0);
                long cpu = event.getLong("processCpuNanos");
                double at = time.getEpochSecond() + time.getNano() / 1e9;
                if (cpu > 0) {
                    if (firstCpu == null) {
                        firstCpu = cpu;
                        firstCpuTime = at;
                    }
                    lastCpu = cpu;
                    lastCpuTime = at;
                }
            }
            case "jdk.ExecutionSample" -> sample(event);
            case "jdk.ObjectAllocationSample" -> {
                long weight = event.getLong("weight");
                allocationWeight += weight;
                RecordedClass type = event.getClass("objectClass");
                allocationClasses.merge(type == null ? "unknown" : type.getName(), weight, Long::sum);
                allocationSites.merge(site(event), weight, Long::sum);
                attribution.allocation(event, weight);
            }
            case "jdk.GCHeapSummary" -> add("heap_at_gc_boundary_bytes", event.getLong("heapUsed"));
            case "jdk.GCPhasePause" -> {
                double pause = event.getDuration().toNanos() / 1e6;
                add("gc_pause_ms", pause);
                gcPauseMs += pause;
                point.merge("gcPauseMs", pause, (a, b) -> ((Number) a).doubleValue() + pause);
            }
            case "jdk.JavaMonitorEnter" -> {
                double blocked = event.getDuration().toNanos() / 1e6;
                add("monitor_blocked_ms", blocked);
                blockedMs += blocked;
                lockSites.merge(site(event), blocked, Double::sum);
                attribution.blocked(event, blocked);
            }
            case "jdk.FileRead" -> attribution.io(event, bytes(event, "bytesRead", 0));
            case "jdk.FileWrite" -> attribution.io(event, bytes(event, "bytesWritten", 1));
            case "jdk.SocketRead" -> attribution.io(event, bytes(event, "bytesRead", 2));
            case "jdk.SocketWrite" -> attribution.io(event, bytes(event, "bytesWritten", 3));
            case "jdk.ThreadCPULoad" -> attribution.threadCpu(event);
            case "jdk.CPULoad" -> {
                double cpu = (event.getFloat("jvmUser") + event.getFloat("jvmSystem")) * 100;
                add("jvm_cpu_percent_of_machine", cpu);
                point.put("cpuPercent", cpu);
            }
            default -> { }
        }
    }

    long bytes(RecordedEvent event, String field, int kind) {
        long bytes = Math.max(0, event.getLong(field));
        switch (kind) {
            case 0 -> readBytes += bytes;
            case 1 -> writeBytes += bytes;
            case 2 -> socketRead += bytes;
            default -> socketWrite += bytes;
        }
        return bytes;
    }

    /** Stack frames that name a method; JFR can record frames without one, which carry nothing to attribute. */
    static List<RecordedFrame> frames(RecordedStackTrace trace) {
        if (trace == null) return List.of();
        List<RecordedFrame> frames = trace.getFrames();
        for (RecordedFrame frame : frames) {
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                return frames.stream().filter(f -> f.getMethod() != null && f.getMethod().getType() != null).toList();
            }
        }
        return frames;
    }

    static String site(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        List<RecordedFrame> frames = frames(trace);
        if (frames.isEmpty()) return "No stack recorded";
        RecordedMethod method = frames.get(0).getMethod();
        return method.getType().getName() + "." + method.getName() + method.getDescriptor();
    }

    static List<Object> weighted(Map<String, ? extends Number> values, double total, String unit) {
        return values.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue())).limit(200)
            .map(e -> (Object) Json.map("name", e.getKey(), "amount", e.getValue(), "unit", unit,
                "percent", total > 0 ? 100 * e.getValue().doubleValue() / total : 0)).toList();
    }

    void sample(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        List<RecordedFrame> frames = frames(trace);
        if (frames.isEmpty()) return;
        executionSamples++;
        RecordedMethod top = frames.get(0).getMethod();
        String className = top.getType().getName();
        String method = className + "." + top.getName() + top.getDescriptor();
        methods.merge(method, 1L, Long::sum);
        owners.merge(owner(className), 1L, Long::sum);
        RecordedThread thread = event.getThread("sampledThread");
        String threadName = thread == null ? "unknown" : thread.getJavaName();
        threads.merge(threadName, 1L, Long::sum);
        attribution.sample(frames, threadName);
        if (stacks.size() < 200_000) {
            List<Integer> sample = new ArrayList<>();
            for (int i = frames.size() - 1; i >= 0; i--) {
                RecordedMethod m = frames.get(i).getMethod();
                String key = m.getType().getName() + "." + m.getName() + m.getDescriptor();
                sample.add(frameIds.computeIfAbsent(key, k -> frameIds.size()));
            }
            stacks.add(sample);
        } else truncated++;
    }

    String owner(String className) {
        if (className.startsWith("net.minecraft.")) return "Minecraft / transformed game code";
        if (className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.")) return "JVM / shared library";
        String location = classes.getProperty(className);
        if (location == null) return "Unknown (no class origin)";
        if (location.equals("ambiguous")) return "Ambiguous classloader origin";
        try {
            if (location.startsWith("file:")) {
                Path file = Path.of(java.net.URI.create(location));
                return file.getFileName().toString() + " [" + Integer.toHexString(location.hashCode()) + "]";
            }
        } catch (Exception ignored) { }
        return "Other loader origin";
    }

    Map<String, Object> finish(Properties status) {
        double eventSpan = Math.max(.001, Duration.between(start, end).toNanos() / 1e9);
        double seconds = Double.parseDouble(status.getProperty("elapsedSeconds", Double.toString(eventSpan)));
        if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("Invalid capture elapsedSeconds");
        boolean completeWindow = eventSpan >= seconds * .9;
        Map<String, Object> summaries = new TreeMap<>();
        metrics.forEach((key, values) -> summaries.put(key, summary(values)));
        Map<String, Object> result = Json.map("schema", 1, "kind", "observation", "seconds", seconds,
            "started", start.toString(), "eventSpanSeconds", eventSpan, "metrics", summaries, "events", events,
            "timeline", new ArrayList<>(timeline.values()), "hotMethods", ranked(methods),
            "allocationSites", weighted(allocationSites, allocationWeight, "estimated bytes"),
            "allocationClasses", weighted(allocationClasses, allocationWeight, "estimated bytes"),
            "lockSites", weighted(lockSites, blockedMs, "observed blocked ms"), "selfSamplesByOrigin", ranked(owners), "samplesByThread", ranked(threads), "executionSamples", executionSamples,
            "agentBuild", status.getProperty("agentBuild", "unknown"), "probeCalls", probeCalls, "hooks", status.getProperty("hooks", ""), "hookErrors", status.getProperty("errors", ""));
        if (metrics.containsKey("frame_ms")) {
            List<Double> frames = metrics.get("frame_ms");
            double mean = frames.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            List<Double> sorted = frames.stream().sorted(Comparator.reverseOrder()).toList();
            int count = Math.max(1, (int) Math.ceil(sorted.size() * .01));
            double slow = sorted.subList(0, count).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            result.put("fps", mean > 0 ? 1000 / mean : null);
            result.put("onePercentLowFps", slow > 0 ? 1000 / slow : null);
            result.put("framesOver50ms", frames.stream().filter(v -> v > 50).count());
            result.put("framesOver100ms", frames.stream().filter(v -> v > 100).count());
        }
        if (metrics.containsKey("tick_ms")) {
            List<Double> ticks = metrics.get("tick_ms");
            result.put("ticksOver50ms", ticks.stream().filter(v -> v > 50).count());
            if (completeWindow) result.put("observedTicksPerSecond", ticks.size() / seconds);
        }
        if (events.containsKey("jdk.GCPhasePause")) result.put("gcPauseTotalMs", gcPauseMs);
        if (events.containsKey("jdk.JavaMonitorEnter")) result.put("observedMonitorBlockedMs", blockedMs);
        if (completeWindow && events.containsKey("jdk.ObjectAllocationSample")) result.put("estimatedAllocationBytesPerSecond", allocationWeight / seconds);
        if (events.containsKey("jdk.FileRead")) result.put("observedFileReadBytes", readBytes);
        if (events.containsKey("jdk.FileWrite")) result.put("observedFileWriteBytes", writeBytes);
        if (events.containsKey("jdk.SocketRead")) result.put("observedSocketReadBytes", socketRead);
        if (events.containsKey("jdk.SocketWrite")) result.put("observedSocketWriteBytes", socketWrite);
        if (firstCpu != null && lastCpuTime > firstCpuTime && lastCpu >= firstCpu) {
            result.put("averageCpuCoresUsed", (lastCpu - firstCpu) / 1e9 / (lastCpuTime - firstCpuTime));
        }
        List<String> unavailable = new ArrayList<>(List.of("GPU time / VRAM", "RSS", "per-mod retained heap (shallow live bytes of each mod's own classes are reported instead)",
            "useful throughput", "mixin overwrites and unnamed mixin handlers"));
        if (!metrics.containsKey("frame_ms")) unavailable.add("FPS / frame pacing: no recognized active frame hook");
        if (!metrics.containsKey("tick_ms")) unavailable.add("server tick time: no recognized active server tick hook");
        if (executionSamples == 0) unavailable.add("sampled execution hotspots");
        if (!completeWindow) warnings.add("Retained event history is shorter than the capture; rate estimates are omitted. Earlier JFR chunks may have been evicted.");
        if (!status.isEmpty() && !status.getProperty("state", "").equals("complete")) warnings.add("The capture did not report normal completion; this is partial evidence.");
        warnings.add("Observation of this session, including menus/loading/pauses. Not a controlled benchmark or a causal mod ranking.");
        warnings.add("Frame intervals measure CPU-side frame completion pacing, not GPU duration or displayed-frame presentation.");
        if (hidden) {
            warnings.add("This client was hidden and throttled by Mod Accel (--allow-hidden): FPS and frame intervals are not representative. "
                + "Server ticks and timed methods still run normally.");
        }
        warnings.add("1% low FPS = 1000 / mean duration of the slowest ceil(1% of captured frame intervals).");
        warnings.add("Execution samples are statistical hotspots, not exact CPU milliseconds. Per-mod costs charge each stack to its innermost mod frame; "
            + "a mod that only calls game code still owns that work, and the mod that triggered it (for example through a shared event) may differ.");
        warnings.add("Mixin handlers are charged to a mod only when their merged method name names it; overwrites and unnamed handlers remain game code.");
        warnings.add("JFR allocation weights are estimates; I/O and lock events may be thresholded and are observed lower bounds, not complete totals.");
        warnings.add("Explicit probes count method entries (including failed/simulated operations), not delivered items. Their overhead can be substantial.");
        warnings.add("Instrumentation overhead is not calibrated. Compare with --no-hooks and external baseline measurements before performance claims.");
        warnings.add("Raw JFR and class-origin files may contain local paths, JVM arguments and server addresses. Reports stay local.");
        if (truncated > 0) warnings.add("Report sample limit reached: " + truncated + " entries omitted. Raw JFR retained; tail statistics may be incomplete.");
        if (!status.getProperty("errors", "").isEmpty()) warnings.add("Some timing hooks failed; inspect hookErrors in report.json.");
        for (String probe : status.getProperty("requestedProbes", "").split("\n")) {
            if (!probe.isBlank() && !probeCalls.containsKey(probe)) unavailable.add("Method probe " + probe + ": no matching loaded method");
        }
        result.put("unavailable", unavailable);
        result.put("warnings", warnings);
        result.put("functionTiming", functions.finish(status));
        if (Boolean.TRUE.equals(Json.object(result.get("functionTiming")).get("partial"))) {
            warnings.add("Function timing history is incomplete. Totals and percentiles describe retained calls only; inspect functionTiming coverage counters.");
        }
        for (String selector : status.getProperty("timingProbes", "").split("\n")) {
            if (!selector.isBlank()) {
                String label = selector.substring(0, selector.indexOf('='));
                if (functions.calls.stream().noneMatch(c -> c.get("label").equals(label))) {
                    unavailable.add("Function duration " + label + ": no complete retained timed call; entry counts do not establish duration");
                }
            }
        }
        result.put("attribution", attribution.finish(result, status, functions.calls, timingTotals, status.getProperty("timingProbes", ""), seconds));
        PerfAdvice.enrich(result);
        return result;
    }

    static Map<String, Object> summary(List<Double> samples) {
        List<Double> sorted = samples.stream().sorted().toList();
        return Json.map("n", sorted.size(), "mean", sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0),
            "p50", percentile(sorted, .5), "p95", percentile(sorted, .95), "p99", percentile(sorted, .99),
            "max", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
    }

    static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) return 0;
        double index = (sorted.size() - 1) * fraction;
        int low = (int) Math.floor(index), high = (int) Math.ceil(index);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    List<Object> ranked(Map<String, Long> values) {
        return values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(200)
            .map(e -> (Object) Json.map("name", e.getKey(), "samples", e.getValue(),
                "percent", executionSamples == 0 ? 0 : 100.0 * e.getValue() / executionSamples)).toList();
    }

    Map<String, Object> speedscope() {
        return Json.map("$schema", "https://www.speedscope.app/file-format-schema.json", "name", "Mod Accel execution samples",
            "shared", Json.map("frames", frameIds.keySet().stream().map(n -> Json.map("name", n)).toList()),
            "profiles", List.of(Json.map("type", "sampled", "name", "Execution samples (all threads)", "unit", "none",
                "startValue", 0, "endValue", stacks.size(), "samples", stacks, "weights", Collections.nCopies(stacks.size(), 1))));
    }

    static final String HTML = """
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mod Accel · Performance</title>
<style>
:root{color-scheme:dark;
font:15px system-ui;
background:#10191c;
color:#dce8e9}body{max-width:1200px;
margin:auto;
padding:32px}
h1{font-size:36px;
margin:6px 0}h2{font-size:21px;
margin-top:32px}.muted{color:#9ab3b8}header{border-bottom:1px solid #345057;
padding-bottom:24px}
.cards{display:grid;
grid-template-columns:repeat(auto-fit,minmax(170px,1fr));
gap:12px;
margin:24px 0}.card{background:#1a2b30;
border:1px solid #345057;
padding:20px;
border-radius:12px}
.card b{display:block;
font-size:30px;
color:#80e4b3}table{width:100%;
border-collapse:collapse}th,td{padding:10px;
text-align:left;
border-bottom:1px solid #345057}
td:first-child{overflow-wrap:anywhere}th{color:#9ab3b8}input,select{background:#1a2b30;
color:inherit;
border:1px solid #52727b;
padding:10px;
border-radius:5px}
canvas{width:100%;
height:220px;
background:#152328;
border-radius:10px}a{color:#80e4b3}details{margin:20px 0}li{margin:8px 0}.scroll{overflow:auto}.bar{height:4px;
background:#80e4b3}
#tree-root{max-width:100%}summary{overflow-wrap:anywhere}
</style>
<header>
<div class="muted">MOD ACCEL / LOCAL PERFORMANCE REPORT</div>
<h1>What this session costs</h1>
<p id="meta" class="muted">
</p>
<a href="report.json">JSON report</a> · <a href="stacks.speedscope.json">Sampled stacks (Speedscope)</a>
</header>
<div class="cards" id="cards">
</div>
<p class="muted">Observation only. Menus, loading and pauses remain in the capture. Missing metrics are not zero.</p>
<h2>Where the cost is, by mod and feature</h2>
<p id="cost-basis" class="muted"></p>
<p class="muted">Sampled columns are estimates (≈); exact rows come from timing probes. Expand a mod for its features and methods.
<a href="probes.suggested.json">Timing probes for the top methods</a> make those exact on the next capture.</p>
<div class="scroll"><table id="cost"><thead><tr><th>Mod</th><th>Share of samples ≈</th><th>Server ms/tick ≈ (95%)</th>
<th>Render ms/frame ≈ (95%)</th><th>Allocation/s ≈</th><th>Live heap (own classes)</th><th>Lock wait ms</th><th>I/O bytes</th></tr></thead><tbody></tbody></table></div>
<div id="cost-detail"></div>
<h3>Threads</h3>
<div class="scroll"><table id="threads"><thead><tr><th>Thread</th><th>Mean CPU % of machine (OS, exact)</th><th>Execution samples</th></tr></thead><tbody></tbody></table></div>
<h2>What to investigate and how to verify a fix</h2>
<p id="advice-policy" class="muted"></p>
<div id="advice"></div>
<h2>Metric checklist</h2>
<p id="metric-count" class="muted"></p>
<div class="scroll"><table id="catalog">
<thead><tr><th>Metric</th><th>Value</th><th>Unit</th><th>Measurement basis</th></tr></thead><tbody></tbody>
</table></div>
<h2>Timeline</h2>
<label>Signal <select id="signal">
<option value="frameMaxMs">Worst frame interval / second (ms)</option>
<option value="tickMaxMs">Worst tick / second (ms)</option>
<option value="heapMiB">Heap (MiB)</option>
<option value="cpuPercent">JVM CPU (% of machine)</option>
<option value="gcPauseMs">GC pause time / second (ms)</option>
</select>
</label>
<p id="range" class="muted">
</p>
<canvas id="plot" aria-label="Recorded signal over time">
</canvas>
<h2>Distributions</h2>
<div class="scroll">
<table id="metrics">
<thead>
<tr>
<th>Metric / unit</th>
<th>Samples</th>
<th>Mean</th>
<th>p95</th>
<th>p99</th>
<th>Max</th>
</tr>
</thead>
<tbody>
</tbody>
</table>
</div>
<h2>Sampled hotspots</h2>
<p class="muted">Self samples identify where execution was observed. They do not prove which mod caused it.</p>
<select id="group">
<option value="selfSamplesByOrigin">Code origin</option>
<option value="hotMethods">Method</option>
<option value="samplesByThread">Thread</option>
<option value="allocationSites">Allocation sites (estimated bytes)</option>
<option value="allocationClasses">Allocated classes (estimated bytes)</option>
<option value="lockSites">Lock contention sites (observed ms)</option>
</select>
<input id="search" placeholder="Filter names" aria-label="Filter hotspots">
<div class="scroll">
<table id="hot">
<thead>
<tr>
<th>Name</th>
<th>Amount</th>
<th>Share</th>
</tr>
</thead>
<tbody>
</tbody>
</table>
</div>
<h2>Explicit method probes</h2>
<ul id="probes"></ul>
<h2>Measured function time</h2>
<p id="function-basis" class="muted"></p>
<p id="function-coverage"></p>
<p id="function-accounting" class="muted"></p>
<h3>Drill into a slow call</h3>
<p id="tree-coverage" class="muted"></p>
<label>Invocation <select id="tree-root" aria-label="Choose a timed root call"></select></label>
<div id="call-tree" class="scroll"></div>
<a href="functions.trace.json">Download function call timeline</a>
<p class="muted">Open the trace in a Chrome Trace-compatible viewer such as Perfetto to inspect nested calls by thread.
Call and parent IDs connect retained invocations. Recorded sleep, park, monitor, I/O and GC events provide timeline context;
they can be thresholded or missing. The table below shows the slowest 200 completed calls.</p>
<label>Group <select id="function-group"><option value="methods">Function</option><option value="edges">Nearest timed caller → function</option></select></label>
<div class="scroll"><table id="functions"><thead><tr><th>Name</th><th>Calls</th><th>Total ms</th><th>Self ms</th>
<th>Mean ms</th><th>p99 ms</th><th>Max ms</th><th>Thrown</th></tr></thead><tbody></tbody></table></div>
<div class="scroll"><table id="invocations"><thead><tr><th>Started / thread</th><th>Function / caller</th><th>Call / parent ID</th>
<th>Total ms</th><th>Self ms</th><th>Thrown</th></tr></thead><tbody></tbody></table></div>
<h2>Coverage</h2>
<ul id="missing">
</ul>
<details open>
<summary>Interpretation and limitations</summary>
<ul id="warnings">
</ul>
</details>
<script>const d=__DATA__;

const el=id=>document.getElementById(id),fmt=v=>typeof v==='number'?v.toLocaleString(undefined,{maximumFractionDigits:2}):'Unavailable',ms=v=>typeof v==='number'?v.toFixed(6):'Unavailable';

el('meta').textContent=d.started+' · '+fmt(d.seconds)+' seconds · '+fmt(d.executionSamples)+' execution samples';

for(const [label,v] of [['Average FPS',d.fps],
['1% low FPS',d.onePercentLowFps],['p99 tick · ms',d.metrics.tick_ms?.p99],['Frames >50 ms',d.framesOver50ms],['CPU cores used',d.averageCpuCoresUsed]]){const c=document.createElement('div');
c.className='card';
c.textContent=label;
const b=document.createElement('b');
b.textContent=fmt(v);
c.append(b);
el('cards').append(c)}
for(const [name,count] of Object.entries(d.probeCalls||{})){const li=document.createElement('li');li.textContent=name+': '+count+' method entries';el('probes').append(li)}
function row(body,values){const tr=document.createElement('tr');
for(const v of values){const td=document.createElement('td');
td.textContent=v;
tr.append(td)}body.append(tr)}
const at=d.attribution||{mods:[],threads:[],basis:{}},bytes=v=>typeof v!=='number'?'Unavailable':v>1048576?fmt(v/1048576)+' MiB':v>1024?fmt(v/1024)+' KiB':fmt(v)+' B';
el('cost-basis').textContent=Object.values(at.basis||{}).join(' ');
const range=(m,k)=>typeof m[k+'Ms']==='number'?fmt(m[k+'Ms'])+' ('+fmt(m[k+'MsLow'])+'–'+fmt(m[k+'MsHigh'])+')':'Unavailable';
for(const m of at.mods)row(el('cost').tBodies[0],[m.name+(m.yourMod?' ★':''),fmt(m.percentOfSamples)+'% ('+m.samples+')',range(m,'perTick'),range(m,'perFrame'),
bytes(m.allocationBytesPerSecond),m.heapShallowBytes===undefined?'Unavailable':bytes(m.heapShallowBytes),fmt(m.blockedMs),bytes(m.ioBytes)]);
for(const m of at.mods){const box=document.createElement('details');if(m.yourMod)box.open=true;const s=document.createElement('summary');
s.textContent=m.name+' · features and methods';box.append(s);const t=document.createElement('table');
t.innerHTML='<thead><tr><th>Feature</th><th>Share ≈</th><th>Server ms/tick ≈</th><th>Render ms/frame ≈</th>'+
'<th>Allocation/s ≈</th><th>Top methods</th></tr></thead><tbody></tbody>';
for(const f of m.features)row(t.tBodies[0],[f.name,fmt(f.percentOfSamples)+'%',range(f,'perTick'),range(f,'perFrame'),bytes(f.allocationBytesPerSecond),
f.methods.slice(0,3).map(x=>x.name+' ('+x.samples+')').join(' · ')]);
const w=document.createElement('div');w.className='scroll';w.append(t);box.append(w);
if(m.exact.length){const e=document.createElement('table');
e.innerHTML='<thead><tr><th>Timed method (exact)</th><th>Calls</th><th>Calls/tick</th><th>ms/tick</th>'+
'<th>Mean ms/call</th><th>Total ms</th></tr></thead><tbody></tbody>';
for(const x of m.exact)row(e.tBodies[0],[x.label,x.calls,fmt(x.callsPerTick),fmt(x.inclusiveMsPerTick),ms(x.meanInclusiveMs),fmt(x.inclusiveTotalMs)]);
const v=document.createElement('div');v.className='scroll';v.append(e);box.append(v)}
if(m.heapClasses){const p=document.createElement('p');p.className='muted';
p.textContent='Largest live own classes: '+m.heapClasses.slice(0,5).map(x=>x.name+' '+bytes(x.bytes)).join(' · ');box.append(p)}
el('cost-detail').append(box)}
for(const t of at.threads)row(el('threads').tBodies[0],[t.thread,fmt(t.meanCpuPercentOfMachine),t.samples]);
el('advice-policy').textContent=d.recommendationPolicy;
for(const f of d.recommendations||[]){const box=document.createElement('details');
const title=document.createElement('summary');title.textContent=f.title+' · '+f.status;box.append(title);
for(const [label,text] of [['Interpretation',f.interpretation],['Try',f.suggestedChange],['Verify',f.validation]]){
const p=document.createElement('p');p.textContent=label+': '+text;box.append(p)}
const evidence=document.createElement('pre');evidence.style.whiteSpace='pre-wrap';evidence.style.overflowWrap='anywhere';
evidence.textContent=JSON.stringify(f.evidence,null,2);box.append(evidence);el('advice').append(box)}
const catalog=d.metricCatalog||[];
el('metric-count').textContent=catalog.filter(m=>m.availability==='recorded').length+' of '+catalog.length+' metrics available in this capture.';
for(const m of catalog)row(el('catalog').tBodies[0],[m.name,fmt(m.value),m.unit,m.basis+(m.availability==='unavailable'?' · No usable measurement':'')]);
for(const [name,s] of Object.entries(d.metrics))row(el('metrics').tBodies[0],[name,s.n,fmt(s.mean),fmt(s.p95),fmt(s.p99),fmt(s.max)]);
const ft=d.functionTiming||{};
el('function-basis').textContent=(ft.basis||'')+' '+(ft.selfMeaning||'');
el('function-accounting').textContent=(ft.accounting||'')+' '+(ft.precision||'');
el('function-coverage').textContent=(ft.partial?'PARTIAL: ':'')+(ft.retainedCalls||0)+' completed calls retained; '+(ft.agentDroppedCalls||0)+
' dropped at event limit; '+(ft.depthDroppedCalls||0)+' skipped at depth limit; '+(ft.reportOmittedCalls||0)+' omitted by reader. '+
(ft.contextEvents||0)+' context events; '+(ft.contextOmittedEvents||0)+' context events omitted. '+
(!ft.retainedCalls?'Enable mode: timing in your probe selectors and capture the action to measure durations.':'');
function functionRows(){const body=el('functions').tBodies[0];body.replaceChildren();
for(const r of [...(ft[el('function-group').value]||[])].sort((a,b)=>b.selfTotalMs-a.selfTotalMs))row(body,[r.name,r.calls,ms(r.inclusiveTotalMs),ms(r.selfTotalMs),
ms(r.inclusive.mean),ms(r.inclusive.p99),ms(r.inclusive.max),r.exceptionalCalls])}
el('function-group').onchange=functionRows;functionRows();
const trees=ft.callTrees?.roots||[];
el('tree-coverage').textContent=(ft.callTrees?.meaning||'No timed calls.')+' '+(ft.callTrees?.displayedNodes||0)+
' nodes shown; '+(ft.callTrees?.otherRetainedNodes||0)+' other retained nodes in the trace.';
trees.forEach((r,i)=>{const option=document.createElement('option');option.value=i;
option.textContent=r.label+' · '+ms(r.inclusiveMs)+' ms · '+r.thread+' · call '+r.callId;el('tree-root').append(option)});
function callNode(r){const box=document.createElement('details');box.style.marginLeft='12px';
const title=document.createElement('summary');title.textContent=r.label+' · total '+ms(r.inclusiveMs)+' ms · self '+ms(r.selfMs)+
' ms · call '+r.callId+(r.exceptional?' · threw':'');box.append(title);
for(const child of r.children||[])box.append(callNode(child));
if(r.undisplayedChildren){const p=document.createElement('p');p.textContent=r.undisplayedChildren+' children omitted from this preview; use the trace.';box.append(p)}
return box}
function callTree(){el('call-tree').replaceChildren();const root=trees[Number(el('tree-root').value)];
if(root){const node=callNode(root);node.open=true;el('call-tree').append(node)}}
el('tree-root').onchange=callTree;callTree();
for(const r of ft.slowCalls||[])row(el('invocations').tBodies[0],[r.started+' / '+r.thread,r.label+' / '+r.caller,
r.callId+' / '+r.parentId,ms(r.inclusiveMs),ms(r.selfMs),r.exceptional?'yes':'no']);

for(const [id,values] of [['missing',d.unavailable],['warnings',d.warnings]])for(const text of values){const li=document.createElement('li');
li.textContent=text;
el(id).append(li)}
function hotspots(){const body=el('hot').tBodies[0];
body.replaceChildren();
for(const r of d[el('group').value])if(r.name.toLowerCase().includes(el('search').value.toLowerCase()))row(body,[r.name,fmt(r.samples??r.amount)+(r.unit?' '+r.unit:''),fmt(r.percent)+'%'])}
el('group').onchange=hotspots;
el('search').oninput=hotspots;
hotspots();

function draw(){const key=el('signal').value,
p=d.timeline.filter(r=>typeof r[key]==='number'),c=el('plot'),w=c.width=c.clientWidth*devicePixelRatio,h=c.height=220*devicePixelRatio,ctx=c.getContext('2d');
ctx.clearRect(0,0,w,h);
if(!p.length){el('range').textContent='Not measured in this recording';
return}const max=Math.max(1,...p.map(r=>r[key])),first=p[0].epochSecond,last=p[p.length-1].epochSecond;
el('range').textContent='0–'+fmt(max)+' · '+(last-first)+' seconds · gaps are not interpolated';
ctx.strokeStyle='#80e4b3';
ctx.lineWidth=2*devicePixelRatio;
ctx.beginPath();
p.forEach((r,i)=>{const x=10+(r.epochSecond-first)/Math.max(1,last-first)*(w-20),y=h-12-r[key]/max*(h-24);
if(!i||r.epochSecond-p[i-1].epochSecond>1)ctx.moveTo(x,y);
else ctx.lineTo(x,y)});
ctx.stroke()}
el('signal').onchange=draw;
window.onresize=draw;
draw();
</script>
</html>
""";
}
