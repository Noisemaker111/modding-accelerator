import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * port-plan must not refork Gradle to re-record the compile classpath when nothing that determines it changed.
 * The first run records; an unchanged rerun reuses the records (no Gradle); a changed build input re-records; and
 * the recorded touch points are byte-identical across all of it. Drives the packaged jar, offline (deps already cached).
 *
 *   java tools/tests/PortPlanCacheTest.java fixtures/prism-lab build/distributions/mmt.jar
 */
public class PortPlanCacheTest {

    static Path root, placesFile, buildFile;
    static String jar;
    static int checks = 0;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        jar = args[1];
        placesFile = root.resolve(".mmt/portable/port-plan.json");
        buildFile = root.resolve("build.gradle");

        // 1. --refresh forces a fresh record even if a stamp exists.
        String a = run("--refresh");
        check(ok(a), "refresh run succeeds");
        check("recorded".equals(field(a)), "--refresh records (got " + field(a) + ")");
        byte[] placesA = Files.readAllBytes(placesFile);

        // 2. Nothing changed: reuse the records, no Gradle, identical touch points.
        String b = run();
        check(ok(b), "unchanged rerun succeeds");
        check("cached".equals(field(b)), "unchanged rerun reuses records (got " + field(b) + ")");
        check(Arrays.equals(placesA, Files.readAllBytes(placesFile)), "cached touch points byte-identical");
        check(count(a) == count(b) && count(b) > 0, "same non-zero place count (" + count(b) + ")");

        // 3. A changed build input must invalidate the cache and re-record.
        byte[] original = Files.readAllBytes(buildFile);
        try {
            Files.write(buildFile, (new String(original, StandardCharsets.UTF_8)
                + "\n// port-plan cache invalidation probe\n").getBytes(StandardCharsets.UTF_8));
            String c = run();
            check(ok(c), "invalidation run succeeds");
            check("recorded".equals(field(c)), "changed build input re-records (got " + field(c) + ")");
            check(Arrays.equals(placesA, Files.readAllBytes(placesFile)), "re-recorded touch points byte-identical");

            // 4. With the change still present and stable, the next run reuses again.
            String d = run();
            check("cached".equals(field(d)), "re-caches after invalidation (got " + field(d) + ")");
        } finally {
            Files.write(buildFile, original);
        }
        System.out.println("ok PortPlanCacheTest: " + checks + " checks (reuse, invalidation, identical places)");
    }

    /** Run the packaged jar's port-plan and return its final JSON line. */
    static String run(String... extra) throws Exception {
        List<String> command = new ArrayList<>(List.of("java", "-jar", jar, "port-plan", "--root", root.toString(), "--json"));
        command.addAll(List.of(extra));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IllegalStateException("port-plan exit " + exit + ": " + tail(out));
        String line = null;
        for (String l : out.split("\n")) if (l.trim().startsWith("{")) line = l.trim();
        if (line == null) throw new IllegalStateException("port-plan printed no JSON: " + tail(out));
        return line;
    }

    static boolean ok(String json) {
        return json.contains("\"ok\":true");
    }

    static String field(String json) {
        Matcher m = Pattern.compile("\"compileRecords\":\"([a-z]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static int count(String json) {
        Matcher m = Pattern.compile("\"placesTouchingGameCode\":(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String tail(String s) {
        return s.length() > 600 ? s.substring(s.length() - 600) : s;
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}