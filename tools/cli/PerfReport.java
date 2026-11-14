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
    Instant start, end;
    final Map<String, Long> allocationSites = new HashMap<>(), allocationClasses = new HashMap<>();
    final Map<String, Double> lockSites = new HashMap<>();
    final Map<String, Long> probeCalls = new TreeMap<>();
    long executionSamples, truncated;
    long readBytes, writeBytes, socketRead, socketWrite, allocationWeight;
    double gcPauseMs, blockedMs;
    Long firstCpu, lastCpu;
    Double firstCpuTime, lastCpuTime;

    PerfReport(Properties classes) { this.classes = classes; }

    static Map<String, Object> write(Path input, Path output) throws Exception {
        Path recording = Files.isDirectory(input) ? input.resolve("capture.jfr") : input;
        if (!Files.isRegularFile(recording)) throw new IOException("No recording at " + recording);
        Path directory = recording.toAbsolutePath().getParent();
        PerfReport report = new PerfReport(Performance.properties(directory.resolve("classes.properties")));
        try (RecordingFile stream = new RecordingFile(recording)) {
            while (stream.hasMoreEvents()) report.accept(stream.readEvent());
        }
        if (report.start == null) throw new IOException("Recording contains no events");
        Files.createDirectories(output);
        Properties status = Performance.properties(directory.resolve("status.properties"));
        Properties environment = Performance.properties(directory.resolve("environment.properties"));
        Map<String, Object> result = report.finish(status);
        result.put("environment", new TreeMap<>(environment));
        result.put("recording", recording.toAbsolutePath().toString());
        result.put("sha256", Performance.sha256(recording));
        Path inventory = directory.resolve("inventory.json");
        if (Files.isRegularFile(inventory)) result.put("inventory", Json.read(inventory));
        Json.save(output.resolve("report.json"), result);
        Json.save(output.resolve("stacks.speedscope.json"), report.speedscope());
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
        String name = event.getEventType().getName();
        events.merge(name, 1L, Long::sum);
        Instant time = event.getStartTime();
        if (start == null || time.isBefore(start)) start = time;
        Instant finished = event.getEndTime();
        if (end == null || finished.isAfter(end)) end = finished;
        long bucket = time.getEpochSecond();
        Map<String, Object> point = timeline.computeIfAbsent(bucket, key -> Json.map("epochSecond", key));
        switch (name) {
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
            }
            case "jdk.FileRead" -> readBytes += Math.max(0, event.getLong("bytesRead"));
            case "jdk.FileWrite" -> writeBytes += Math.max(0, event.getLong("bytesWritten"));
            case "jdk.SocketRead" -> socketRead += Math.max(0, event.getLong("bytesRead"));
            case "jdk.SocketWrite" -> socketWrite += Math.max(0, event.getLong("bytesWritten"));
            case "jdk.CPULoad" -> {
                double cpu = (event.getFloat("jvmUser") + event.getFloat("jvmSystem")) * 100;
                add("jvm_cpu_percent_of_machine", cpu);
                point.put("cpuPercent", cpu);
            }
            default -> { }
        }
    }

    static String site(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null || trace.getFrames().isEmpty()) return "No stack recorded";
        RecordedMethod method = trace.getFrames().get(0).getMethod();
        return method.getType().getName() + "." + method.getName() + method.getDescriptor();
    }

    static List<Object> weighted(Map<String, ? extends Number> values, double total, String unit) {
        return values.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue())).limit(200)
            .map(e -> (Object) Json.map("name", e.getKey(), "amount", e.getValue(), "unit", unit,
                "percent", total > 0 ? 100 * e.getValue().doubleValue() / total : 0)).toList();
    }

    void sample(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null || trace.getFrames().isEmpty()) return;
        executionSamples++;
        List<RecordedFrame> frames = trace.getFrames();
        RecordedMethod top = frames.get(0).getMethod();
        String className = top.getType().getName();
        String method = className + "." + top.getName() + top.getDescriptor();
        methods.merge(method, 1L, Long::sum);
        owners.merge(owner(className), 1L, Long::sum);
        RecordedThread thread = event.getThread("sampledThread");
        threads.merge(thread == null ? "unknown" : thread.getJavaName(), 1L, Long::sum);
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
        List<String> unavailable = new ArrayList<>(List.of("GPU time / VRAM", "RSS", "per-mod retained heap", "useful throughput", "mixin-origin attribution"));
        if (!metrics.containsKey("frame_ms")) unavailable.add("FPS / frame pacing: no recognized active frame hook");
        if (!metrics.containsKey("tick_ms")) unavailable.add("server tick time: no recognized active server tick hook");
        if (executionSamples == 0) unavailable.add("sampled execution hotspots");
        if (!completeWindow) warnings.add("Retained event history is shorter than the capture; rate estimates are omitted. Earlier JFR chunks may have been evicted.");
        if (!status.isEmpty() && !status.getProperty("state", "").equals("complete")) warnings.add("The capture did not report normal completion; this is partial evidence.");
        warnings.add("Observation of this session, including menus/loading/pauses. Not a controlled benchmark or a causal mod ranking.");
        warnings.add("Frame intervals measure CPU-side frame completion pacing, not GPU duration or displayed-frame presentation.");
        warnings.add("1% low FPS = 1000 / mean duration of the slowest ceil(1% of captured frame intervals).");
        warnings.add("Execution samples are statistical hotspots, not exact CPU milliseconds; origins identify loaded code, not the cause of work.");
        warnings.add("Mixins can execute inside Minecraft methods; shared-library and game samples must not be charged to an arbitrary mod.");
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
<h2>Coverage</h2>
<ul id="missing">
</ul>
<details open>
<summary>Interpretation and limitations</summary>
<ul id="warnings">
</ul>
</details>
<script>const d=__DATA__;

const el=id=>document.getElementById(id),fmt=v=>typeof v==='number'?v.toLocaleString(undefined,{maximumFractionDigits:2}):'Unavailable';

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
for(const [name,s] of Object.entries(d.metrics))row(el('metrics').tBodies[0],[name,s.n,fmt(s.mean),fmt(s.p95),fmt(s.p99),fmt(s.max)]);

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
