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
    /** gradleFailure, led by the stale-loader-cache explanation when NeoForge's own patches add a member the compile could not find. */
    static String gradleFailure(Path log, Map<String, Object> model) throws IOException {
        String text = gradleFailure(log);
        String stale = LoaderPatches.staleHint(model, new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8).lines().toList());
        return stale.isEmpty() ? text : stale + "\n" + text;
    }
    static String gradleFailure(Path log) throws IOException {
        if (!Files.exists(log)) return "";
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        // Compiler errors say what is wrong in the mod's own words; Gradle's block around them is noise.
        String compiler = compilerErrors(lines, null);
        if (!compiler.isEmpty()) return compiler;
        StringBuilder out = new StringBuilder();
        List<String> missing = missingDependencies(lines);
        String offline = unreachable(lines);
        if (offline != null) out.append(offline).append('\n');
        else if (!missing.isEmpty()) {
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
            boolean searched = false;
            for (int j = i + 1; j < lines.size() && shown < 30; j++) {
                String line = lines.get(j);
                if (line.startsWith("* Try:") || line.startsWith("* Exception is:") || line.startsWith("* Get more help")) break;
                if (line.isBlank()) continue;
                // The searched locations are summarised above, by host.
                if (line.strip().startsWith("Searched in the following locations")) { searched = !missing.isEmpty(); if (searched) continue; }
                if (searched && line.strip().startsWith("- ")) continue;
                searched = false;
                out.append(line).append('\n'); shown++;
            }
        }
        if (shown == 0 && missing.isEmpty() && offline == null) return failure(log).strip();
        return out.toString().strip();
    }
    private static final Pattern NETWORK = Pattern.compile("No such host is known|UnknownHostException|Unknown host|nodename nor servname|Name or service not known|Temporary failure in name resolution"
            + "|Connect(?:ion)? timed out|Connection refused|Network is unreachable|No route to host|Read timed out|PKIX path building failed|Remote host terminated the handshake");
    private static final Pattern URL = Pattern.compile("(https?://[^\\s'\"()]+)");
    /** A build that failed because a repository could not be reached: name the host and say it is the network, not the dependency. */
    static String unreachable(List<String> lines) {
        for (String line : lines) {
            Matcher cause = NETWORK.matcher(line);
            if (!cause.find()) continue;
            String host = null;
            Matcher named = Pattern.compile("\\(([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\)").matcher(line);
            if (named.find()) host = named.group(1);
            if (host == null) for (String other : lines) {
                Matcher url = URL.matcher(other);
                if (other.contains("Could not GET") || other.contains("Could not HEAD") || other.contains("Could not get resource")) if (url.find()) { host = java.net.URI.create(url.group(1)).getHost(); break; }
            }
            String what = cause.group().startsWith("PKIX") || cause.group().startsWith("Remote host") ? "a secure connection failed (" + cause.group() + ")"
                    : cause.group().matches("(?i).*(host|name|servname).*") ? "the name could not be looked up" : "the connection failed (" + cause.group() + ")";
            return "Gradle could not reach " + (host == null ? "a repository" : host) + ": " + what + ". This is the network or the repository address, not your code."
                    + " Check the internet connection, a proxy or VPN, and the repository URL in the build script, then rerun."
                    + " Without a network only dependencies already in the Gradle cache can be used.";
        }
        return null;
    }
    private static final Pattern JAVA_ERROR = Pattern.compile("^\\s*(.+\\.java):(\\d+): error: (.*)$");
    private static final Pattern KOTLIN_ERROR = Pattern.compile("^\\s*e: (?:file:///?)?(.+\\.kts?):(\\d+):(?:\\d+)? ?(.*)$");
    /**
     * javac and kotlinc errors as "file:line  message", with the source line, paths relative to
     * root when given. Empty when the log has none. A test file that cannot see JUnit gets the two
     * dependency lines to add, since that is the first wall a new test hits.
     */
    static String compilerErrors(List<String> lines, Path root) {
        List<String> errors = new ArrayList<>(); Set<String> seen = new HashSet<>();
        boolean junit = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher java = JAVA_ERROR.matcher(lines.get(i)), kotlin = KOTLIN_ERROR.matcher(lines.get(i));
            String file, line, message;
            if (java.find()) { file = java.group(1); line = java.group(2); message = java.group(3).strip(); }
            else if (kotlin.find()) { file = kotlin.group(1); line = kotlin.group(2); message = kotlin.group(3).strip(); }
            else continue;
            String code = "";
            // javac follows with the source line, a caret, then "symbol:" and "location:" lines.
            for (int j = i + 1; j < lines.size() && j <= i + 4; j++) {
                String next = lines.get(j).strip();
                if (JAVA_ERROR.matcher(lines.get(j)).find() || next.matches("\\d+ errors?") || next.startsWith("> Task") || next.startsWith("* ")) break;
                if (next.startsWith("symbol:")) message += ": " + next.substring("symbol:".length()).strip();
                else if (next.startsWith("location:")) message += " (in " + next.substring("location:".length()).strip() + ")";
                else if (code.isEmpty() && !next.matches("\\^+")) code = next;
            }
            String shown = file;
            try { Path path = Path.of(file); if (root != null && path.isAbsolute() && path.startsWith(root)) shown = root.relativize(path).toString(); } catch (InvalidPathException ignored) { }
            String entry = shown + ":" + line + "  " + message + (code.isEmpty() ? "" : "\n      " + code);
            if (!seen.add(entry)) continue;
            errors.add(entry);
            if (message.contains("org.junit") || (message.startsWith("static import only from classes and interfaces") && code.contains("org.junit"))
                    || (file.replace('\\', '/').contains("/src/test/") && message.startsWith("cannot find symbol") && message.matches(".*class (Test|Tag|Assertions|BeforeEach|AfterEach|DisplayName)\\b.*"))) junit = true;
        }
        if (errors.isEmpty()) return "";
        StringBuilder out = new StringBuilder("The code does not compile (" + errors.size() + (errors.size() == 1 ? " error" : " errors") + "):");
        for (String error : errors.subList(0, Math.min(errors.size(), 25))) out.append("\n  ").append(error);
        if (errors.size() > 25) out.append("\n  ... and ").append(errors.size() - 25).append(" more in the log");
        if (junit) out.append("\nJUnit is not on this project's test classpath. Add these to the build script's dependencies, then rerun:\n"
                + "  testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\")\n  testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:1.11.4\")");
        return out.toString();
    }
}
