import java.nio.file.*;
import java.util.*;

/**
 * When perf-optimize may stop before --runs pairs: a sequential test over completed pairs.
 *
 * <ul>
 *   <li>A failed gate never passes again (exact and expect values, a tolerance exceeded, baseline launches
 *   disagreeing, differing pixels), so the first pair that fails one ends the run: an expect gate from the first
 *   pair, a gate compared with the baseline from the second, when two baseline launches show whether the gate
 *   itself is reproducible.</li>
 *   <li>From --min-runs pairs on, each look computes a 99% Student-t interval of the mean log speedup, cold and
 *   warm. Both entirely above 1: the change is clearly faster. Either entirely below --min-speedup (default 1):
 *   it cannot be accepted. Otherwise another pair is launched.</li>
 * </ul>
 *
 * The final verdict is still perf-optimize's own rule (every gate, 95% bootstrap intervals above 1) over the pairs
 * measured. A 99% t interval is wider than the 95% bootstrap at every pair count, so stopping early for speed adds at
 * most 0.5% one-sided error per look, and is rare unless the change is large and consistent.
 */
final class PerfStopping {
    /** Two-sided 99% Student t quantiles (0.995) for 1..30 degrees of freedom. */
    static final double[] T995 = {63.657, 9.925, 5.841, 4.604, 4.032, 3.707, 3.499, 3.355, 3.250, 3.169, 3.106, 3.055, 3.012, 2.977,
        2.947, 2.921, 2.898, 2.878, 2.861, 2.845, 2.831, 2.819, 2.807, 2.797, 2.787, 2.779, 2.771, 2.763, 2.756, 2.750};

    private PerfStopping() {}

    static double t995(int degrees) {
        return T995[Math.max(1, Math.min(degrees, T995.length)) - 1];
    }

    /** Geometric mean and 99% t interval of paired speedup ratios. */
    static Map<String, Object> interval(List<Double> ratios) {
        int n = ratios.size();
        double mean = ratios.stream().mapToDouble(Math::log).average().orElse(Double.NaN);
        double variance = 0;
        for (double ratio : ratios) variance += (Math.log(ratio) - mean) * (Math.log(ratio) - mean);
        variance = n > 1 ? variance / (n - 1) : Double.POSITIVE_INFINITY;
        double half = t995(n - 1) * Math.sqrt(variance / n);
        return Json.map("geometricMean", Math.exp(mean), "low", Math.exp(mean - half), "high", Math.exp(mean + half), "pairs", n);
    }

    /** Completed pairs, by run index, from perf-optimize's measurement rows. */
    static Map<Integer, Map<String, Map<String, Object>>> pairs(List<Object> measured) {
        Map<Integer, Map<String, Map<String, Object>>> pairs = new TreeMap<>();
        for (Object item : measured) {
            Map<String, Object> row = Json.object(item);
            pairs.computeIfAbsent(((Number) row.get("run")).intValue(), k -> new HashMap<>()).put(row.get("arm").toString(), row);
        }
        pairs.values().removeIf(pair -> pair.size() < 2);
        return pairs;
    }

    /** Why to stop after the pairs measured so far, or null to launch another pair. */
    static Map<String, Object> decide(Map<String, Object> experiment, List<Object> measured, int minRuns, int runs, double minSpeedup) throws Exception {
        Map<Integer, Map<String, Map<String, Object>>> pairs = pairs(measured);
        int n = pairs.size();
        if (n == 0) return null;
        for (Object item : Json.array(experiment.get("gates"))) {
            Map<String, Object> gate = Json.object(item);
            // An exact, tolerance or pixel gate compares with the baseline, which may itself differ between launches:
            // only from the second pair is a difference known to be the candidate's (or the gate's, which also fails).
            boolean relative = !"expect".equals(Json.text(gate, "compare", Boolean.TRUE.equals(gate.get("frame")) ? "pixels" : "exact"));
            if (relative && n < 2) continue;
            Map<String, Object> verdict = PerfOptimize.gate(gate, pairs);
            if (!Boolean.TRUE.equals(verdict.get("passed"))) {
                return Json.map("afterPairs", n, "decided", "rejected", "reason",
                    "gate " + verdict.get("name") + " failed after " + n + " pair(s), and a failed gate cannot pass again: " + verdict.get("failures"));
            }
        }
        if (n < minRuns || n >= runs) return null;
        Map<String, Object> intervals = new LinkedHashMap<>();
        boolean faster = true;
        String slower = null;
        for (String phase : List.of("cold", "warm")) {
            List<Double> ratios = new ArrayList<>();
            for (var pair : pairs.values()) {
                ratios.add(PerfOptimize.capture(pair.get("baseline"), phase) / Math.max(PerfOptimize.capture(pair.get("candidate"), phase), 1e-12));
            }
            Map<String, Object> interval = interval(ratios);
            intervals.put(phase, interval);
            faster &= ((Number) interval.get("low")).doubleValue() > 1;
            if (((Number) interval.get("high")).doubleValue() < minSpeedup && slower == null) slower = phase;
        }
        if (slower != null) {
            Map<String, Object> s = Json.object(intervals.get(slower));
            return Json.map("afterPairs", n, "decided", "rejected", "intervals", intervals, "reason", String.format(Locale.ROOT,
                "%s speedup %.3fx, 99%% t interval %.3f-%.3f, lies below %.3f after %d pairs", slower, s.get("geometricMean"), s.get("low"), s.get("high"), minSpeedup, n));
        }
        if (faster) {
            return Json.map("afterPairs", n, "decided", "faster", "intervals", intervals,
                "reason", "cold and warm 99% t intervals both lie above 1 after " + n + " pairs");
        }
        return null;
    }

    /**
     * A finished perf-optimize run decided again with this stopping rule, from its optimize.json: where it would
     * have stopped, its verdict then, and the wall time of the pairs it would have launched (sequential, as measured,
     * and with the two arms of each pair started together, estimated as the slower arm's time).
     */
    static Map<String, Object> replay(Path folder, int minRuns, double minSpeedup) throws Exception {
        Path file = Files.isDirectory(folder) ? folder.resolve("optimize.json") : folder;
        Map<String, Object> result = Json.read(file);
        Map<String, Object> experiment = Json.read(Path.of(result.get("experiment").toString()));
        int runs = ((Number) result.get("runs")).intValue();
        List<Object> rows = Json.array(result.getOrDefault("measurements", List.of()));
        // The experiment file may have gained gates since the run: decide only on the gates every launch measured.
        List<Object> measuredGates = new ArrayList<>();
        for (Object item : Json.array(experiment.get("gates"))) {
            String name = Json.object(item).get("name").toString();
            boolean everywhere = true;
            for (Object row : rows) everywhere &= Json.object(Json.object(row).getOrDefault("gates", Map.of())).containsKey(name);
            if (everywhere) measuredGates.add(item);
        }
        experiment = new LinkedHashMap<>(experiment);
        experiment.put("gates", measuredGates);
        double recorded = 0;
        for (Object item : Json.array(result.getOrDefault("steps", List.of()))) {
            Map<String, Object> step = Json.object(item);
            if (step.get("seconds") instanceof Number seconds) recorded += seconds.doubleValue();
        }
        Map<Integer, Double> slower = new TreeMap<>();
        Map<Integer, Double> both = new TreeMap<>();
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            int run = ((Number) row.get("run")).intValue();
            double seconds = ((Number) row.getOrDefault("seconds", 0)).doubleValue();
            slower.merge(run, seconds, Math::max);
            both.merge(run, seconds, Double::sum);
        }
        double armSeconds = both.values().stream().mapToDouble(Double::doubleValue).sum();
        // Steps outside the pairs: worktrees, tests and the final game stop.
        double setup = Math.max(0, recorded - armSeconds);
        List<Object> sofar = new ArrayList<>();
        Map<String, Object> stop = null;
        int stoppedAt = -1;
        for (int run : both.keySet()) {
            for (Object item : rows) if (((Number) Json.object(item).get("run")).intValue() == run) sofar.add(item);
            stop = decide(experiment, sofar, Math.min(minRuns, runs), runs, minSpeedup);
            stoppedAt = run;
            if (stop != null) break;
        }
        double sequential = setup;
        double parallel = setup;
        for (int run : both.keySet()) {
            if (run > stoppedAt) break;
            sequential += both.get(run);
            parallel += slower.get(run);
        }
        String verdict = PerfOptimize.verdict(result);
        String replayed = verdict;
        if (stop != null && "rejected".equals(stop.get("decided"))) replayed = "rejected";
        if (stop != null && "faster".equals(stop.get("decided"))) {
            Map<String, Object> again = Json.map("runs", runs);
            PerfOptimize.evaluate(experiment, again, sofar);
            replayed = Boolean.TRUE.equals(again.get("accepted")) ? "accepted" : "rejected";
        }
        return Json.map("source", file.toString(), "pairsRecorded", both.size(), "pairsReplayed", stoppedAt + 1, "stop", stop,
            "verdictRecorded", verdict, "verdictReplayed", replayed, "recordedSeconds", recorded, "replaySequentialSeconds", sequential,
            "replayParallelSeconds", parallel, "basis", "step times as recorded; parallel takes each pair's slower arm and assumes no contention between them");
    }
}
