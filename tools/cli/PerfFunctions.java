import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Exact invocation boundaries for selected probes; elapsed wall time, not CPU attribution. */
final class PerfFunctions {
    static final int LIMIT = 100_000;
    final List<Map<String, Object>> calls = new ArrayList<>();
    final List<Map<String, Object>> context = new ArrayList<>();
    long omitted;
    long contextOmitted;

    void context(RecordedEvent event) {
        String name = event.getEventType().getName();
        if (!Set.of("jdk.ThreadSleep", "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.FileRead", "jdk.FileWrite",
            "jdk.SocketRead", "jdk.SocketWrite", "jdk.GCPhasePause").contains(name)) return;
        if (context.size() >= LIMIT) {
            contextOmitted++;
            return;
        }
        RecordedThread thread = event.getThread();
        context.add(Json.map("name", name, "started", event.getStartTime().toString(), "durationUs", event.getDuration().toNanos() / 1000.0,
            "threadId", thread == null ? -1 : thread.getJavaThreadId(), "site", PerfReport.site(event)));
    }

    void accept(RecordedEvent event) {
        if (calls.size() >= LIMIT) {
            omitted++;
            return;
        }
        RecordedThread thread = event.getThread();
        calls.add(Json.map("label", event.getString("label"), "caller", event.getString("caller"),
            "callId", event.getLong("callId"), "parentId", event.getLong("parentId"),
            "started", event.getStartTime().toString(), "inclusiveMs", event.getLong("inclusiveNanos") / 1e6,
            "selfMs", event.getLong("selfNanos") / 1e6, "exceptional", event.getBoolean("exceptional"),
            "threadId", thread == null ? -1 : thread.getJavaThreadId(), "thread", thread == null ? "unknown" : thread.getJavaName()));
    }

    static final class Total {
        final List<Double> inclusive = new ArrayList<>();
        double total, self;
        long exceptional;
        void add(Map<String, Object> call) {
            double ms = ((Number) call.get("inclusiveMs")).doubleValue();
            inclusive.add(ms);
            total += ms;
            self += ((Number) call.get("selfMs")).doubleValue();
            if (Boolean.TRUE.equals(call.get("exceptional"))) exceptional++;
        }
        Map<String, Object> summary(String label) {
            return Json.map("name", label, "calls", inclusive.size(), "inclusiveTotalMs", total, "selfTotalMs", self,
                "exceptionalCalls", exceptional, "inclusive", PerfReport.summary(inclusive));
        }
    }

    Map<String, Object> finish(Properties status) {
        Map<String, Total> methods = new TreeMap<>();
        Map<String, Total> edges = new TreeMap<>();
        for (Map<String, Object> call : calls) {
            String label = call.get("label").toString();
            methods.computeIfAbsent(label, k -> new Total()).add(call);
            edges.computeIfAbsent(call.get("caller") + " → " + label, k -> new Total()).add(call);
        }
        long completed = Long.parseLong(status.getProperty("timingCompleted", Long.toString(calls.size() + omitted)));
        long dropped = Long.parseLong(status.getProperty("timingDropped", "0"));
        long depthDropped = Long.parseLong(status.getProperty("timingDepthDropped", "0"));
        return Json.map("basis", "Instrumented elapsed wall time for completed selected calls, including waits and profiler overhead; not sampled CPU time.",
            "selfMeaning", "Inclusive time minus immediate timed children on the same thread. Uninstrumented callees and waits remain in self time. "
                + "Caller means nearest timed ancestor, not necessarily the direct Java caller. Async work on other threads is not charged to the submitting call.",
            "accounting", "Do not sum nested inclusive times or compare summed thread-ms with elapsed wall time. Recursive calls also overlap. "
                + "Calls crossing a capture boundary are omitted. Frame scopes measure method duration, not the interval between frames or GPU time.",
            "precision", "Nanosecond clock storage does not establish nanosecond accuracy. Compare captures without timing probes to assess observer overhead.",
            "selectors", status.getProperty("timingProbes", ""), "retainedCalls", calls.size(), "completedCalls", completed,
            "agentDroppedCalls", dropped, "depthDroppedCalls", depthDropped, "reportOmittedCalls", omitted,
            "contextEvents", context.size(), "contextOmittedEvents", contextOmitted,
            "partial", dropped > 0 || depthDropped > 0 || omitted > 0 || completed > calls.size(),
            "callTrees", trees(),
            "methods", methods.entrySet().stream().map(e -> e.getValue().summary(e.getKey())).toList(),
            "edges", edges.entrySet().stream().map(e -> e.getValue().summary(e.getKey())).toList(),
            "slowCalls", calls.stream().sorted(Comparator.comparingDouble(c -> -((Number) c.get("inclusiveMs")).doubleValue())).limit(200).toList());
    }

    Map<String, Object> trees() {
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        Map<Long, List<Map<String, Object>>> children = new HashMap<>();
        for (Map<String, Object> call : calls) {
            byId.put(((Number) call.get("callId")).longValue(), call);
            children.computeIfAbsent(((Number) call.get("parentId")).longValue(), k -> new ArrayList<>()).add(call);
        }
        List<Map<String, Object>> roots = calls.stream().filter(c -> !byId.containsKey(((Number) c.get("parentId")).longValue()))
            .sorted(Comparator.comparingDouble(c -> -((Number) c.get("inclusiveMs")).doubleValue())).limit(20).toList();
        int[] budget = {2000};
        List<Object> trees = new ArrayList<>();
        for (Map<String, Object> root : roots) if (budget[0] > 0) trees.add(tree(root, children, budget, 0));
        return Json.map("roots", trees, "displayedNodes", 2000 - budget[0], "otherRetainedNodes", calls.size() - (2000 - budget[0]),
            "meaning", "Slowest 20 retained root calls, up to 2000 nodes. A root can have a missing parent at a capture/retention boundary. Full retained timeline is downloadable.");
    }

    static Map<String, Object> tree(Map<String, Object> call, Map<Long, List<Map<String, Object>>> children, int[] budget, int depth) {
        budget[0]--;
        Map<String, Object> node = new LinkedHashMap<>(call);
        List<Object> nested = new ArrayList<>();
        List<Map<String, Object>> next = children.getOrDefault(((Number) call.get("callId")).longValue(), List.of());
        for (Map<String, Object> child : next) if (budget[0] > 0 && depth < 128) nested.add(tree(child, children, budget, depth + 1));
        node.put("children", nested);
        node.put("undisplayedChildren", next.size() - nested.size());
        return node;
    }

    void trace(Path output, Instant start) throws Exception {
        List<Object> events = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            double micros = Duration.between(start, Instant.parse(call.get("started").toString())).toNanos() / 1000.0;
            events.add(Json.map("name", call.get("label"), "cat", "instrumented-function", "ph", "X", "pid", 1,
                "tid", call.get("threadId"), "ts", micros, "dur", ((Number) call.get("inclusiveMs")).doubleValue() * 1000,
                "args", Json.map("selfMs", call.get("selfMs"), "callId", call.get("callId"), "parentId", call.get("parentId"),
                    "nearestTimedCaller", call.get("caller"), "thread", call.get("thread"), "exceptional", call.get("exceptional"))));
        }
        for (Map<String, Object> item : context) {
            events.add(Json.map("name", item.get("name"), "cat", "jfr-context", "ph", "X", "pid", 1, "tid", item.get("threadId"),
                "ts", Duration.between(start, Instant.parse(item.get("started").toString())).toNanos() / 1000.0,
                "dur", item.get("durationUs"), "args", Json.map("site", item.get("site"), "basis", "Recorded JFR event; thresholded, not complete coverage")));
        }
        Json.save(output.resolve("functions.trace.json"), Json.map("traceEvents", events, "displayTimeUnit", "ms"));
    }
}
