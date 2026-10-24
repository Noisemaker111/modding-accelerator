import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;

/** Count this invocation's XML, never yesterday's successful test run. */
final class TestResults {
    static Map<String, Object> readTasks(Map<String, Object> model, List<String> tasks, String runId) throws Exception {
        int executed = 0, failed = 0, skipped = 0; List<Object> reports = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String task : tasks) {
            String name = task.substring(task.lastIndexOf(':') + 1); if (!seen.add(name)) continue;
            var count = read(model, name, runId);
            executed += ((Number)count.get("executed")).intValue(); failed += ((Number)count.get("failed")).intValue();
            skipped += ((Number)count.get("skipped")).intValue(); reports.addAll(Json.array(count.get("reports")));
        }
        return Json.map("executed", executed, "failed", failed, "skipped", skipped, "reports", reports);
    }
    static Map<String, Object> read(Map<String, Object> model, String task, String runId) throws Exception {
        int executed = 0, failed = 0, skipped = 0; List<String> files = new ArrayList<>();
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
                }
            }
        }
        return Json.map("executed", executed, "failed", failed, "skipped", skipped, "reports", files);
    }
    static int number(String text) { return text.isEmpty() ? 0 : Integer.parseInt(text); }
}
