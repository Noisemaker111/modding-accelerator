import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Finds, for a port rule, the first place in real mod source that the rule rewrites, and runs the porter's own
 * rewrite on it. bench/rule_test.py drives this: it turns each answer into the rule's test {error, before, after}.
 *
 *   java -cp modaccel.jar:classes RuleExamples request.json response.json
 *
 * request:  {"rules": [rule, ...], "samples": {id: text the rule's match should find}, "sources": [{"dir", "names", "label"}]}
 * response: {"found": [{"id", "label", "file", "line", "error", "before", "after"}], "missed": [{"id", "why"}]}
 */
public final class RuleExamples {
    public static void main(String[] args) throws Exception {
        var request = Json.read(Path.of(args[0]));
        var samples = Json.object(request.get("samples"));
        List<Object> found = new ArrayList<>(), missed = new ArrayList<>();
        Map<String, List<Path>> files = new HashMap<>();
        for (Object item : Json.array(request.get("rules"))) {
            var rule = Json.object(item);
            String id = Json.text(rule, "id", "");
            String names = Json.text(rule, "jump", "").contains("yarn") ? "yarn" : "mojmap";
            Map<String, Object> hit = null;
            String why = "no pinned " + names + " source has code its find matches";
            for (Object s : Json.array(request.get("sources"))) {
                var source = Json.object(s);
                if (!names.equals(source.get("names"))) continue;
                Path dir = Path.of(Json.text(source, "dir", ""));
                for (Path file : files.computeIfAbsent(dir.toString(), k -> javaFiles(dir))) {
                    String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    int[] span = span(rule, text);
                    if (span == null) continue;
                    String before = snippet(rule, text, span);
                    String after = before == null ? null : Port.rewrite(rule, before);
                    if (before == null || after.equals(before)) { why = "it matches " + dir.relativize(file) + " but not once the lines around the match are cut out"; continue; }
                    int line = 1 + (int) text.substring(0, span[0]).chars().filter(c -> c == '\n').count();
                    String sample = Json.text(samples, id, "");
                    String error = file.getFileName() + ":" + line + ": error: " + (sample.startsWith("symbol:") ? "cannot find symbol\n  " + sample : sample) + "\n";
                    if (!Pattern.compile(Json.text(rule, "match", "")).matcher(error).find()) { why = "could not word a compiler error its match finds (tried: " + sample + ")"; continue; }
                    hit = Json.map("id", id, "label", source.get("label"), "file", dir.relativize(file).toString().replace('\\', '/'), "line", line,
                            "error", error, "before", before, "after", after);
                    break;
                }
                if (hit != null) break;
            }
            if (hit != null) found.add(hit);
            else missed.add(Json.map("id", id, "why", why));
        }
        Json.save(Path.of(args[1]), Json.map("found", found, "missed", missed));
    }

    static List<Path> javaFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            // The mod's own src/ first: a checkout may also hold the same mod for another loader in a subfolder.
            return walk.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/build/") && !p.toString().contains("\\build\\"))
                    .sorted(Comparator.comparing((Path p) -> !dir.relativize(p).startsWith("src")).thenComparing(Comparator.naturalOrder())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Where the rule's find (or literal) first matches text, when its "when" gate lets it run at all; null when it does not. */
    static int[] span(Map<String, Object> rule, String text) {
        String when = Json.text(rule, "when", "");
        if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return null;
        var apply = Json.object(rule.get("apply"));
        if (apply.containsKey("literal")) {
            int at = text.indexOf(apply.get("literal").toString());
            return at < 0 ? null : new int[] { at, at + apply.get("literal").toString().length() };
        }
        if (!apply.containsKey("find") || apply.get("find").toString().contains("{symbol}")) return null;
        Matcher m = Pattern.compile(apply.get("find").toString()).matcher(text);
        return m.find() ? new int[] { m.start(), m.end() } : null;
    }

    /**
     * The file cut down to what a reader needs: its package line, the line its "when" gate looks for, and the
     * matched lines with one line either side. Null when the match is longer than a test should be.
     */
    static String snippet(Map<String, Object> rule, String text, int[] span) {
        String[] lines = text.split("\n", -1);
        int first = 0, last = 0, offset = 0;
        for (int i = 0; i < lines.length; i++) {
            if (offset <= span[0]) first = i;
            if (offset < Math.max(span[1], span[0] + 1)) last = i;
            offset += lines[i].length() + 1;
        }
        if (last - first > 30) return null;
        int from = Math.max(0, first - 1), to = Math.min(lines.length - 1, last + 1);
        List<String> out = new ArrayList<>();
        Matcher pkg = Pattern.compile("(?m)^package [^;]+;").matcher(text);
        if (pkg.find() && from > 0) { out.add(pkg.group()); out.add(""); }
        String when = Json.text(rule, "when", "");
        String body = String.join("\n", Arrays.copyOfRange(lines, from, to + 1));
        if (!when.isEmpty() && !Pattern.compile(when).matcher(String.join("\n", out) + "\n" + body).find()) {
            for (String line : lines) if (Pattern.compile(when).matcher(line).find()) { out.add(line); break; }
        }
        out.add(body);
        return String.join("\n", out) + "\n";
    }
}
