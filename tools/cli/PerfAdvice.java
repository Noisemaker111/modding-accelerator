import java.util.*;

/** Evidence-linked triage. Suggestions are experiments, never automatically verified fixes. */
final class PerfAdvice {
    static void enrich(Map<String, Object> report) {
        List<Object> catalog = new ArrayList<>();
        metric(catalog, report, "Average FPS", "fps", "FPS", "CPU-side frame pacing");
        metric(catalog, report, "1% low FPS", "onePercentLowFps", "FPS", "CPU-side frame pacing");
        for (String stat : List.of("mean", "p50", "p95", "p99", "max")) {
            metric(catalog, report, "Frame interval " + stat, "metrics.frame_ms." + stat, "ms", "CPU-side frame pacing");
            metric(catalog, report, "Server tick " + stat, "metrics.tick_ms." + stat, "ms", "Normally completed server ticks");
        }
        metric(catalog, report, "Frames over 50 ms", "framesOver50ms", "frames", "Observed count");
        metric(catalog, report, "Frames over 100 ms", "framesOver100ms", "frames", "Observed count");
        metric(catalog, report, "Ticks over 50 ms", "ticksOver50ms", "ticks", "Observed count");
        metric(catalog, report, "Observed tick rate", "observedTicksPerSecond", "ticks/s", "Requires retained capture window");
        metric(catalog, report, "Process CPU", "averageCpuCoresUsed", "cores", "Process CPU time / elapsed sample time");
        metric(catalog, report, "Mean heap used", "metrics.heap_used_bytes.mean", "bytes", "Sampled Java heap, not RSS");
        metric(catalog, report, "Peak sampled heap", "metrics.heap_used_bytes.max", "bytes", "Sampled Java heap, not retained heap per mod");
        metric(catalog, report, "Allocation rate", "estimatedAllocationBytesPerSecond", "bytes/s", "JFR weighted estimate");
        metric(catalog, report, "Total GC pause time", "gcPauseTotalMs", "ms", "Recorded GC pauses");
        metric(catalog, report, "GC pause p99", "metrics.gc_pause_ms.p99", "ms", "Recorded GC pauses");
        metric(catalog, report, "Monitor blocked time", "observedMonitorBlockedMs", "thread-ms", "Thresholded; summed across threads");
        metric(catalog, report, "File reads", "observedFileReadBytes", "bytes", "Recorded events only; may omit short operations");
        metric(catalog, report, "File writes", "observedFileWriteBytes", "bytes", "Recorded events only; may omit short operations");
        metric(catalog, report, "Socket reads", "observedSocketReadBytes", "bytes", "Recorded events only; not full network traffic");
        metric(catalog, report, "Socket writes", "observedSocketWriteBytes", "bytes", "Recorded events only; not full network traffic");
        report.put("metricCatalog", catalog);
        List<Object> findings = new ArrayList<>();
        if (number(report, "framesOver50ms") > 0) finding(findings, "frame-stalls", "Investigate frame stalls",
            evidence(report, "framesOver50ms", "metrics.frame_ms.p99"),
            "Intervals over 50 ms occurred; loading, pauses, FPS caps and background throttling can also cause them.",
            "Reproduce in a loaded world with the same camera and foreground state. Inspect render-thread stacks and the GC timeline during stalls. "
                + "If a renderer repeats expensive work, test caching unchanged results, batching draws, or culling invisible content.",
            "Retest the identical scene and settings across independent launches; compare frame p99, 1% lows and frames over 50 ms, and inspect image correctness.");
        if (number(report, "ticksOver50ms") > 0) finding(findings, "tick-budget", "Investigate ticks over the 50 ms budget",
            evidence(report, "ticksOver50ms", "metrics.tick_ms.p99"),
            "Completed ticks exceeded 50 ms; this does not identify which mod caused the work.",
            "Inspect server-thread stacks during slow ticks. For pipes or machines, vary network length, branches, endpoints, "
                + "occupied slots and stack size separately. If traversal repeats, test topology caching with invalidation or batching transfers.",
            "Repeat the same world workload; compare tick p99 and over-budget ticks. Assert delivered items, inventory totals and routing correctness; method calls are not throughput.");
        if (number(report, "gcPauseTotalMs") > 0) finding(findings, "gc-pauses", "Check whether GC pauses overlap stalls",
            evidence(report, "gcPauseTotalMs", "metrics.gc_pause_ms.p99", "estimatedAllocationBytesPerSecond"),
            "GC pauses were recorded. Their presence alone does not establish a memory bottleneck or leak.",
            "Align GC and frame/tick spikes in the timeline, then inspect allocation sites. If a hot loop creates temporary collections or buffers, "
                + "test reusing bounded buffers or avoiding intermediate objects. Do not increase heap solely because allocation is high.",
            "Keep heap, collector and workload fixed; compare allocation rate, pause distribution and frame/tick tails. Check memory after a long repeated workload.");
        if (number(report, "observedMonitorBlockedMs") > 0) finding(findings, "monitor-contention", "Inspect recorded monitor contention",
            evidence(report, "observedMonitorBlockedMs", "lockSites"),
            "This is summed blocked time across threads, not a percentage of elapsed time. Waits below the JFR threshold may be absent.",
            "Inspect the listed lock sites and owning threads in the JFR. If expensive computation or I/O occurs while holding a shared monitor, "
                + "test moving that work outside the lock or narrowing the protected data; preserve thread-safety and game-thread rules.",
            "Repeat concurrent workloads; compare the same lock sites, blocked time and frame/tick tails. Stress correctness, ordering and races.");
        List<Object> hot = report.get("hotMethods") instanceof List<?> values ? new ArrayList<>(values) : List.of();
        if (!hot.isEmpty()) {
            Map<String, Object> top = Json.object(hot.get(0));
            finding(findings, "sampled-hotspot", "Inspect sampled method: " + top.get("name"),
                Json.map("hotMethods[0]", top, "executionSamples", report.get("executionSamples")),
                "Self samples locate observed Java execution, not exact CPU time or mod responsibility. Inspect callers and thread context before changing code.",
                "Open the sampled stacks and locate this method in source. Measure call count and per-call work separately. "
                    + "If unchanged inputs trigger repeated work, test a bounded cache with invalidation; if scans scale with scene size, test indexing or incremental updates.",
                "Add a subsystem benchmark with correctness assertions. Repeat independent JVM runs on identical inputs and compare wall time plus whole-game frame/tick tails.");
        }
        if (findings.isEmpty()) finding(findings, "capture-coverage", "Collect more evidence before choosing a fix",
            evidence(report, "executionSamples", "unavailable"),
            "No supported triage rule fired. This is not proof that the modpack is optimized.",
            "Capture the slow action in a loaded world, check timing-hook coverage, and profile a dedicated server separately when its ticks are the concern.",
            "Confirm that the report contains the affected frame/tick metric and enough samples to cover the repeatable slowdown.");
        report.put("recommendations", findings);
        report.put("recommendationPolicy", "Rule-based investigation steps, not verified fixes. No universal performance score or automatic mod blame. "
            + "Missing events are unavailable, not zero. Fix verification requires controlled before/after runs and correctness checks.");
    }

    static Object value(Map<String, Object> report, String path) {
        Object current = report;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    static double number(Map<String, Object> report, String path) {
        Object v = value(report, path);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0;
    }

    static void metric(List<Object> rows, Map<String, Object> report, String label, String path, String unit, String basis) {
        Object v = value(report, path);
        boolean available = v instanceof Number n && Double.isFinite(n.doubleValue());
        rows.add(Json.map("name", label, "source", path, "unit", unit, "value", available ? v : null,
            "availability", available ? "recorded" : "unavailable", "basis", basis));
    }

    static Map<String, Object> evidence(Map<String, Object> report, String... paths) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String path : paths) if (value(report, path) != null) values.put(path, value(report, path));
        return values;
    }

    static void finding(List<Object> rows, String id, String title, Map<String, Object> evidence,
                        String interpretation, String change, String validation) {
        rows.add(Json.map("id", id, "title", title, "status", "investigation", "evidence", evidence,
            "interpretation", interpretation, "suggestedChange", change, "validation", validation, "fixVerified", false));
    }
}
