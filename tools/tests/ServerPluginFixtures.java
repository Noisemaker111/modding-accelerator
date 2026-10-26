import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Server-plugin merge gate: each pinned plugin in bench/server-plugins/manifest.json is cloned fresh and must
 * run its exact test count through `fast` (cold, then warm) and build its jar through `compile --task`.
 * Usage: java ServerPluginFixtures.java <checkout> <modaccel.jar>
 */
public final class ServerPluginFixtures {
    record Run(int exit, String output, double seconds) { }
    record Plugin(String id, String repo, String commit, int executed, int failed, String task, String file) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java ServerPluginFixtures.java <checkout> <modaccel.jar>");
        run(Path.of(args[0]).toRealPath(), Path.of(args[1]).toAbsolutePath().normalize());
    }

    static void run(Path home, Path jar) throws Exception {
        if (!Files.isRegularFile(jar)) throw new IOException("Missing package: " + jar);
        List<Plugin> plugins = manifest(home.resolve("bench/server-plugins/manifest.json"));
        if (plugins.isEmpty()) throw new AssertionError("bench/server-plugins/manifest.json lists no plugins");
        Path runs = home.resolve(".runs"); Files.createDirectories(runs);
        for (Plugin plugin : plugins) {
            Path root = Files.createTempDirectory(runs, "server-plugin-" + plugin.id() + "-").resolve(plugin.id());
            command(List.of("git", "clone", "--quiet", plugin.repo(), root.toString()), home, 5);
            command(List.of("git", "-C", root.toString(), "checkout", "--quiet", plugin.commit()), home, 2);
            String counts = "\"executed\":" + plugin.executed() + ",\"failed\":" + plugin.failed();
            for (String label : List.of("cold", "warm")) {
                Run fast = modaccel(home, jar, root, "fast");
                if (fast.exit() != 0 || !fast.output().contains(counts) || !fast.output().contains("no Minecraft or loader on the test classpath"))
                    throw new AssertionError(plugin.id() + " fast " + label + ": expected exit 0, " + counts + " and every test selected; got exit "
                            + fast.exit() + "\n" + fast.output());
                System.out.printf(Locale.ROOT, "PASS %s fast %s: %d tests in %.1fs%n", plugin.id(), label, plugin.executed(), fast.seconds());
            }
            Run build = modaccel(home, jar, root, "compile", "--task", plugin.task());
            Path built = root.resolve(plugin.file());
            if (build.exit() != 0 || !Files.isRegularFile(built)) throw new AssertionError(plugin.id() + " compile --task " + plugin.task()
                    + ": expected " + built + "; got exit " + build.exit() + "\n" + build.output());
            try (var zip = new java.util.zip.ZipFile(built.toFile())) {
                if (zip.getEntry("plugin.yml") == null && zip.getEntry("paper-plugin.yml") == null)
                    throw new AssertionError(plugin.id() + ": " + built + " has no plugin.yml or paper-plugin.yml");
            }
            System.out.printf(Locale.ROOT, "PASS %s compile --task %s: %s in %.1fs%n", plugin.id(), plugin.task(), plugin.file(), build.seconds());
        }
        System.out.println("PASS server-plugin fixtures: " + plugins.size());
    }

    /** The manifest is ours and flat; a field reader keeps this file free of a JSON dependency. */
    static List<Plugin> manifest(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<Plugin> plugins = new ArrayList<>();
        String[] entries = text.split("\"id\"\\s*:");
        for (int i = 1; i < entries.length; i++) {
            String entry = "\"id\":" + entries[i];
            Matcher fast = Pattern.compile("\"fast\"\\s*:\\s*\\{([^}]*)}").matcher(entry), jar = Pattern.compile("\"jar\"\\s*:\\s*\\{([^}]*)}").matcher(entry);
            if (!fast.find() || !jar.find()) throw new IOException("Plugin entry needs fast and jar: " + entry);
            plugins.add(new Plugin(field(entry, "id"), field(entry, "repo"), field(entry, "commit"),
                    Integer.parseInt(number(fast.group(1), "executed")), Integer.parseInt(number(fast.group(1), "failed")),
                    field(jar.group(1), "task"), field(jar.group(1), "file")));
        }
        return plugins;
    }
    static String field(String text, String name) throws IOException {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        if (!m.find()) throw new IOException("Missing " + name + " in " + text); return m.group(1);
    }
    static String number(String text, String name) throws IOException {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(text);
        if (!m.find()) throw new IOException("Missing " + name + " in " + text); return m.group(1);
    }

    static Run modaccel(Path home, Path jar, Path root, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString()));
        command.addAll(List.of(args)); command.addAll(List.of("--root", root.toString(), "--json"));
        return process(command, home, 10);
    }
    static void command(List<String> command, Path directory, int minutes) throws Exception {
        Run run = process(command, directory, minutes);
        if (run.exit() != 0) throw new IOException(String.join(" ", command) + " failed with exit " + run.exit() + "\n" + run.output());
    }
    static Run process(List<String> command, Path directory, int minutes) throws Exception {
        Path output = Files.createTempFile("modaccel-plugin-", ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(Duration.ofMinutes(minutes).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new AssertionError("Timed out after " + minutes + " min: " + String.join(" ", command));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        String text = Files.readString(output, StandardCharsets.UTF_8); Files.deleteIfExists(output);
        return new Run(process.exitValue(), text, seconds);
    }

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"); }
}
