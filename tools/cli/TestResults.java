import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;

/** Count this invocation's XML, never yesterday's successful test run. */
final class TestResults {
    static Map<String, Object> readTasks(Map<String, Object> model, List<String> tasks, String runId) throws Exception {
        int executed = 0, failed = 0, skipped = 0; List<Object> reports = new ArrayList<>(), failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String task : tasks) {
            String name = task.substring(task.lastIndexOf(':') + 1); if (!seen.add(name)) continue;
            var count = read(model, name, runId);
            executed += ((Number)count.get("executed")).intValue(); failed += ((Number)count.get("failed")).intValue();
            skipped += ((Number)count.get("skipped")).intValue(); reports.addAll(Json.array(count.get("reports"))); failures.addAll(Json.array(count.get("failures")));
        }
        return Json.map("executed", executed, "failed", failed, "skipped", skipped, "reports", reports, "failures", failures);
    }
    static Map<String, Object> read(Map<String, Object> model, String task, String runId) throws Exception {
        int executed = 0, failed = 0, skipped = 0; List<String> files = new ArrayList<>(); List<Object> failures = new ArrayList<>();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        for (Object item : Json.array(model.get("projects"))) {
            var project = Json.object(item); Path directory = Path.of(project.get("directory").toString()).resolve("build/modaccel/test-results/" + runId + "/" + task);
            if (!Files.isDirectory(directory)) continue;
            try (var stream = Files.list(directory)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                    var suite = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    int skip = number(suite.getAttribute("skipped"));
                    executed += number(suite.getAttribute("tests")) - skip; skipped += skip;
                    failed += number(suite.getAttribute("failures")) + number(suite.getAttribute("errors")); files.add(file.toString());
                    var cases = suite.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength(); i++) {
                        var testcase = (org.w3c.dom.Element) cases.item(i);
                        for (String tag : List.of("failure", "error")) {
                            var found = testcase.getElementsByTagName(tag);
                            if (found.getLength() == 0) continue;
                            var failure = (org.w3c.dom.Element) found.item(0);
                            String message = failure.getAttribute("message");
                            if (message.isBlank()) message = failure.getAttribute("type");
                            failures.add(Json.map("test", testcase.getAttribute("classname") + "." + testcase.getAttribute("name"), "error", message));
                        }
                    }
                }
            }
        }
        return Json.map("executed", executed, "failed", failed, "skipped", skipped, "reports", files, "failures", failures);
    }
    static int number(String text) { return text.isEmpty() ? 0 : Integer.parseInt(text); }
    /** "3 tests passed", or which ones failed and why, for a person. */
    static String text(Map<String, Object> counts) {
        int executed = ((Number) counts.getOrDefault("executed", 0)).intValue(), failed = ((Number) counts.getOrDefault("failed", 0)).intValue(),
                skipped = ((Number) counts.getOrDefault("skipped", 0)).intValue();
        StringBuilder out = new StringBuilder();
        if (failed == 0) out.append(executed).append(executed == 1 ? " test passed" : " tests passed");
        else out.append(failed).append(" of ").append(executed).append(executed == 1 ? " test failed:" : " tests failed:");
        for (Object item : Json.array(counts.getOrDefault("failures", List.of()))) {
            var failure = Json.object(item);
            String error = String.valueOf(failure.get("error")).strip();
            out.append("\n  ").append(failure.get("test")).append(": ").append(error.length() > 300 ? error.substring(0, 300) + "..." : error);
        }
        if (skipped > 0) out.append(failed == 0 ? "" : "\n").append(failed == 0 ? " (" : "  (").append(skipped).append(" skipped)");
        return out.toString();
    }
}
