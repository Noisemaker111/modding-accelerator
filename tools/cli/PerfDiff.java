import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Descriptive comparison of observation captures, deliberately not a statistical regression gate. */
final class PerfDiff {
    static Map<String, Object> run(Modaccel.Options options) throws Exception {
        Path left = reportPath(options.get("baseline", "")), right = reportPath(options.get("candidate", ""));
        Map<String, Object> a = Json.read(left), b = Json.read(right);
        if (!"observation".equals(a.get("kind")) || !"observation".equals(b.get("kind"))) {
            throw new IllegalArgumentException("perf-diff requires two observation report.json files");
        }
        Map<String, Object> am = Json.object(a.get("metrics")), bm = Json.object(b.get("metrics"));
        Set<String> common = new TreeSet<>(am.keySet());
        common.retainAll(bm.keySet());
        List<Object> differences = new ArrayList<>();
        for (String metric : common) {
            Map<String, Object> av = Json.object(am.get(metric)), bv = Json.object(bm.get(metric));
            for (String statistic : List.of("mean", "p95", "p99", "max")) {
                double x = ((Number) av.get(statistic)).doubleValue(), y = ((Number) bv.get(statistic)).doubleValue();
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite metric in report");
                differences.add(Json.map("metric", metric, "statistic", statistic, "baseline", x,
                    "candidate", y, "delta", y - x, "deltaPercent", x == 0 ? null : (y - x) / x * 100));
            }
        }
        Map<String, Object> ae = Json.object(a.get("environment")), be = Json.object(b.get("environment"));
        Set<String> environment = new TreeSet<>(ae.keySet());
        environment.addAll(be.keySet());
        List<String> mismatches = environment.stream().filter(k -> !ae.containsKey(k) || !be.containsKey(k) || !Objects.equals(ae.get(k), be.get(k))).toList();
        Set<String> missing = new TreeSet<>(am.keySet());
        missing.addAll(bm.keySet());
        missing.removeAll(common);
        Map<String, Object> result = Json.map("schema", 1, "kind", "observation-diff", "ok", true,
            "baselineSha256", Fingerprints.bytes(Files.readAllBytes(left)), "candidateSha256", Fingerprints.bytes(Files.readAllBytes(right)),
            "differences", differences, "environmentDifferences", mismatches, "unpairedMetrics", missing,
            "warning", "Descriptive only. Matching environment fields do not establish equal workload/hardware or independent trials. No causal or regression verdict.");
        Path out = Performance.output(options);
        Json.save(out.resolve("diff.json"), result);
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>Mod Accel comparison</title>");
        html.append("<style>body{font:16px system-ui;max-width:1100px;margin:40px auto;padding:20px}td,th{padding:10px;text-align:left;border-bottom:1px solid #ddd}</style>");
        html.append("<h1>Observed performance differences</h1><p>").append(result.get("warning")).append("</p>");
        html.append("<p>Changed environment fields: ").append(PerfScenario.escape(mismatches.toString())).append("</p>");
        html.append("<p>Metrics missing from one capture: ").append(PerfScenario.escape(missing.toString())).append("</p>");
        html.append("<table><tr><th>Metric</th><th>Statistic</th><th>Baseline</th><th>Candidate</th><th>Change %</th></tr>");
        for (Object entry : differences) {
            Map<String, Object> row = Json.object(entry);
            html.append("<tr>");
            for (String key : List.of("metric", "statistic", "baseline", "candidate", "deltaPercent")) {
                html.append("<td>").append(PerfScenario.escape(String.valueOf(row.get(key)))).append("</td>");
            }
            html.append("</tr>");
        }
        Files.writeString(out.resolve("diff.html"), html.append("</table><a href=diff.json>JSON evidence</a>").toString());
        return Json.map("ok", true, "html", out.resolve("diff.html").toString(), "details", result.get("warning"));
    }
    static Path reportPath(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Supply both --baseline and --candidate");
        Path path = Path.of(value).toAbsolutePath();
        return Files.isDirectory(path) ? path.resolve("report.json") : path;
    }
}
