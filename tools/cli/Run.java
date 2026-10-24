import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Argument arrays throughout: no PowerShell, cmd, bash, or shell escaping. */
final class Run {
    record Result(int exit, Path log, double seconds) {}
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
            throw new IOException("Timed out after " + timeoutSeconds + " seconds; log: " + log);
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
}
