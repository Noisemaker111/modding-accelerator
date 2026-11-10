import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * data-check --once through the packaged command, on a copy of the Prism Lab demo mod (the repo root):
 * the command must take the switch, check the mod with the game's own code, and leave no game behind.
 * Every process modaccel starts is recorded while it runs; once it returns, only its Gradle daemons
 * (and their workers) may still be alive, and no loaded or spare game may be recorded for the project.
 *
 *   java tools/tests/DataCheckOnceTest.java HARNESS MODACCEL_JAR
 *
 * It downloads the mod's dependencies on first use and starts the game's code, so it runs by hand or in
 * bench/data_check_linux.sh, not on the PR.
 */
public final class DataCheckOnceTest {

    static final List<String> MOD_FILES = List.of(
        "src",
        "gradle",
        "build.gradle",
        "settings.gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat"
    );

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]).toAbsolutePath(), jar = Path.of(args[1]).toAbsolutePath();
        String java = ProcessHandle.current().info().command().orElse("java");
        Path copy = Files.createTempDirectory("modaccel-once");
        try {
            for (String name : MOD_FILES) copyTree(harness.resolve(name), copy.resolve(name));
            Set<Long> seen = ConcurrentHashMap.newKeySet();
            Process run = new ProcessBuilder(
                java,
                "-jar",
                jar.toString(),
                "data-check",
                "--once",
                "--root",
                copy.toString(),
                "--json"
            )
                .redirectErrorStream(true)
                .start();
            // The game runs for seconds; record every process modaccel starts while it runs.
            Thread watch = new Thread(() -> {
                while (run.isAlive()) {
                    run.descendants().forEach(p -> seen.add(p.pid()));
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            });
            watch.start();
            String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = run.waitFor();
            watch.join();
            String last = output.strip().isEmpty() ? "" : output.strip().lines().reduce((a, b) -> b).orElse("");
            check(
                exit == 0 && last.contains("\"ok\":true"),
                "data-check --once passes on the demo mod (exit " + exit + "): " + tail(output)
            );
            check(
                last.contains("\"checked\":true") && last.contains("prismlab:"),
                "the game's own code read the mod's items: " + tail(last)
            );

            Path state = copy.resolve(".modaccel/portable");
            for (String file : List.of("data-check.json", "data-check-spare.json")) check(
                !Files.exists(state.resolve(file)),
                "no " + file + " is left: --once keeps no loaded or spare game"
            );

            Set<Long> daemons = new HashSet<>();
            Path registry = state.resolve("gradle-daemons");
            if (Files.isDirectory(registry)) try (var logs = Files.walk(registry)) {
                logs
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("daemon-\\d+\\.out\\.log"))
                    .forEach(n -> daemons.add(Long.parseLong(n.replaceAll("\\D", ""))));
            }
            List<String> left = new ArrayList<>();
            int started = 0;
            for (long pid : seen) {
                if (daemons.contains(pid) || underDaemon(pid, daemons)) continue;
                started++;
                ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .ifPresent(p -> left.add(pid + " " + p.info().command().orElse("?")));
            }
            check(started > 0, "the run's own processes were seen (the watch works): " + seen);
            check(left.isEmpty(), "nothing modaccel started is still running except its Gradle daemons: " + left);
            System.out.println(
                "data-check --once: passed on the demo mod, " +
                started +
                " processes started and all ended, no game state left"
            );
        } finally {
            new ProcessBuilder(java, "-jar", jar.toString(), "game-stop", "--root", copy.toString(), "--json")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(3, TimeUnit.MINUTES);
            // A stopping daemon holds its project folder for a moment after game-stop returns.
            for (int attempt = 0; attempt < 20 && Files.exists(copy); attempt++) {
                try (var paths = Files.walk(copy)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) try {
                        Files.deleteIfExists(p);
                    } catch (IOException busy) {}
                } catch (IOException gone) {}
                if (Files.exists(copy)) Thread.sleep(500);
            }
        }
    }

    /** A worker a Gradle daemon started (a compiler or test worker) belongs to the daemon, not to the check. */
    static boolean underDaemon(long pid, Set<Long> daemons) {
        Optional<ProcessHandle> p = ProcessHandle.of(pid);
        for (int depth = 0; p.isPresent() && depth < 8; depth++) {
            p = p.get().parent();
            if (p.isPresent() && daemons.contains(p.get().pid())) return true;
        }
        return false;
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static String tail(String text) {
        return text.length() > 1500 ? text.substring(text.length() - 1500) : text;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
