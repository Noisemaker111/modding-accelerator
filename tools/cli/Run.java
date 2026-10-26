import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Argument arrays throughout: no PowerShell, cmd, bash, or shell escaping. */
final class Run {
    record Result(int exit, Path log, double seconds) {}
    /** A command stopped at its time limit; the caller says what the limit was for. */
    static final class Timeout extends IOException {
        final int seconds; final Path log;
        Timeout(int seconds, Path log) { super("Timed out after " + seconds + " seconds; log: " + log); this.seconds = seconds; this.log = log; }
    }
    static Result command(List<String> command, Path directory, Map<String, String> environment, Path log, int timeoutSeconds) throws Exception {
        Files.createDirectories(log.toAbsolutePath().getParent()); long start = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            // Only this command and its descendants are owned by this invocation.
            process.descendants().forEach(ProcessHandle::destroy); process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            throw new Timeout(timeoutSeconds, log);
        }
        return new Result(process.exitValue(), log, (System.nanoTime() - start) / 1e9);
    }
    static String tail(Path log) throws IOException {
        if (!Files.exists(log)) return "";
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 24), lines.size()));
    }
    static String failure(Path log) throws IOException {
        if (!Files.exists(log)) return "";
        List<String> causes = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("Caused by:") || line.contains("/ERROR]") || line.startsWith("Error:") || line.contains("error:")).toList();
        return String.join("\n", causes.subList(Math.max(0, causes.size() - 8), causes.size())) + "\n" + tail(log);
    }
    private static final Pattern MISSING = Pattern.compile("^Could not (?:find|resolve) ([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+(?::\\S+?)?)\\.?$");
    /** Coordinates Gradle could not find or resolve, in the order it names them. */
    static List<String> missingDependencies(List<String> lines) {
        Set<String> found = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher match = MISSING.matcher(line.replaceFirst("^[\\s>]+", "").strip());
            if (match.find()) found.add(match.group(1));
        }
        return new ArrayList<>(found);
    }
    /**
     * Gradle's own account of a failed build: each "What went wrong" block up to "* Try:", led by the
     * dependencies it could not resolve and the hosts it searched, so a missing dependency names its
     * coordinate instead of hiding in a cut-off log tail. Without such a block, the log's causes and tail.
     */
    static String gradleFailure(Path log) throws IOException {
        if (!Files.exists(log)) return "";
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        StringBuilder out = new StringBuilder();
        List<String> missing = missingDependencies(lines);
        if (!missing.isEmpty()) {
            out.append("Gradle could not resolve ").append(missing.size() == 1 ? "a dependency" : missing.size() + " dependencies")
                    .append(": ").append(String.join(", ", missing)).append('.');
            Set<String> searched = new LinkedHashSet<>();
            boolean listing = false;
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.startsWith("Searched in the following locations")) { listing = true; continue; }
                if (listing && trimmed.startsWith("- ")) {
                    try { var uri = java.net.URI.create(trimmed.substring(2).strip()); if (uri.getHost() != null) searched.add(uri.getScheme() + "://" + uri.getHost()); }
                    catch (IllegalArgumentException notUrl) { }
                    continue;
                }
                listing = false;
            }
            if (!searched.isEmpty()) out.append(" Searched: ").append(String.join(", ", searched)).append('.');
            out.append(" Add the repository that publishes it, or a version that exists, then rerun.\n");
        }
        int shown = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("* What went wrong:")) continue;
            for (int j = i + 1; j < lines.size() && shown < 30; j++) {
                String line = lines.get(j);
                if (line.startsWith("* Try:") || line.startsWith("* Exception is:") || line.startsWith("* Get more help")) break;
                if (line.isBlank()) continue;
                out.append(line).append('\n'); shown++;
            }
        }
        if (shown == 0 && missing.isEmpty()) return failure(log).strip();
        return out.toString().strip();
    }
}
