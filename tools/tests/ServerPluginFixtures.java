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
    record Plugin(String id, String repo, String commit, int executed, int failed, String task, String file, String checkName, List<String> checkExpects) { }

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
            Run fast = modaccel(home, jar, root, "fast");
            if (fast.exit() != 0 || !fast.output().contains(counts) || !fast.output().contains("no Minecraft or loader on the test classpath"))
                throw new AssertionError(plugin.id() + " fast: expected exit 0, " + counts + " and every test selected; got exit "
                        + fast.exit() + "\n" + fast.output());
            System.out.printf(Locale.ROOT, "PASS %s fast: %d tests in %.1fs%n", plugin.id(), plugin.executed(), fast.seconds());
            Run build = modaccel(home, jar, root, "compile", "--task", plugin.task());
            Path built = root.resolve(plugin.file());
            if (build.exit() != 0 || !Files.isRegularFile(built)) throw new AssertionError(plugin.id() + " compile --task " + plugin.task()
                    + ": expected " + built + "; got exit " + build.exit() + "\n" + build.output());
            try (var zip = new java.util.zip.ZipFile(built.toFile())) {
                if (zip.getEntry("plugin.yml") == null && zip.getEntry("paper-plugin.yml") == null)
                    throw new AssertionError(plugin.id() + ": " + built + " has no plugin.yml or paper-plugin.yml");
            }
            System.out.printf(Locale.ROOT, "PASS %s compile --task %s: %s in %.1fs%n", plugin.id(), plugin.task(), plugin.file(), build.seconds());
            if (plugin.checkName() != null) pluginCheck(home, jar, root, plugin);
        }
        System.out.println("PASS server-plugin fixtures: " + plugins.size());
    }

    /**
     * plugin-check passes on the untouched plugin, then each break made on purpose in one throwaway copy must fail
     * with its own message and file:line, and a wired-up command must pass again: the check decides, not the fixture.
     */
    static void pluginCheck(Path home, Path jar, Path root, Plugin plugin) throws Exception {
        Run clean = modaccel(home, jar, root, "plugin-check");
        if (clean.exit() != 0 || !clean.output().contains("plugin-check passed for " + plugin.checkName()) || clean.output().contains("FAIL ") || clean.output().contains("UNCHECKED "))
            throw new AssertionError(plugin.id() + " plugin-check: expected a pass with nothing unchecked; got exit " + clean.exit() + "\n" + clean.output());
        for (String expected : plugin.checkExpects()) if (!clean.output().contains(expected))
            throw new AssertionError(plugin.id() + " plugin-check: expected \"" + expected + "\"\n" + clean.output());
        System.out.printf(Locale.ROOT, "PASS %s plugin-check in %.1fs%n", plugin.id(), clean.seconds());
        Path copy = Files.createTempDirectory(root.getParent(), plugin.id() + "-broken-").resolve(plugin.id());
        copyTree(root, copy);
        Path yml = copy.resolve("src/main/resources/plugin.yml");
        String descriptor = Files.readString(yml, StandardCharsets.UTF_8);
        Matcher mainMatch = Pattern.compile("(?m)^main:\\s*(\\S+)").matcher(descriptor);
        if (!mainMatch.find()) throw new AssertionError(plugin.id() + ": plugin.yml has no main:");
        String main = mainMatch.group(1);
        Path mainSource = copy.resolve("src/main/java/" + main.replace('.', '/') + ".java");
        String source = Files.readString(mainSource, StandardCharsets.UTF_8);
        if (descriptor.contains("\ncommands:") || descriptor.contains("\ndepend:")) throw new AssertionError(plugin.id() + ": the break cases expect no commands: or depend: in plugin.yml");
        Matcher enable = Pattern.compile("public void onEnable\\(\\)\\s*\\{\\r?\\n").matcher(source);
        if (!enable.find()) throw new AssertionError(plugin.id() + ": " + mainSource + " has no onEnable() { line");
        String command = "commands:\n  modaccelcheck:\n    description: added by the plugin-check gate\n    usage: /modaccelcheck\n";
        String simple = main.substring(main.lastIndexOf('.') + 1);
        record Break(String name, String yml, String java, int exit, List<String> expects) { }
        List<Break> breaks = List.of(
                new Break("missing main class", descriptor.replace("main: " + main, "main: " + main + "Missing"), source, 1,
                        List.of("FAIL main-class", "has no class " + main + "Missing", "plugin.yml:" + line(descriptor, "main:"))),
                new Break("command in plugin.yml with no handler", descriptor + command, source, 1,
                        List.of("FAIL commands: /modaccelcheck is declared in plugin.yml but nothing handles it", "plugin.yml:" + (descriptor.lines().count() + 2))),
                new Break("exception in onEnable", descriptor, source.substring(0, enable.end()) + "        if (getServer() != null) throw new IllegalStateException(\"broken on purpose\");\n" + source.substring(enable.end()), 1,
                        List.of("FAIL enable: onEnable threw java.lang.IllegalStateException: broken on purpose", simple + ".java:" + (source.substring(0, enable.end()).lines().count() + 1))),
                new Break("missing hard dependency", descriptor + "depend: [ModaccelMissingDependency]\n", source, 1,
                        List.of("FAIL dependencies: plugin.yml needs the plugin ModaccelMissingDependency", "plugin.yml:" + (descriptor.lines().count() + 1))),
                new Break("command in plugin.yml with an executor (control)", descriptor + command,
                        source.substring(0, enable.end()) + "        getCommand(\"modaccelcheck\").setExecutor((sender, cmd, label, args) -> true);\n" + source.substring(enable.end()), 0,
                        List.of("plugin-check passed for " + plugin.checkName(), "1 command(s) in plugin.yml all have a handler")));
        for (Break b : breaks) {
            Files.writeString(yml, b.yml(), StandardCharsets.UTF_8); Files.writeString(mainSource, b.java(), StandardCharsets.UTF_8);
            Run check = modaccel(home, jar, copy, "plugin-check");
            boolean expected = check.exit() == b.exit() && b.expects().stream().allMatch(check.output()::contains);
            if (!expected) throw new AssertionError(plugin.id() + " plugin-check with " + b.name() + ": expected exit " + b.exit() + " and " + b.expects() + "; got exit " + check.exit() + "\n" + check.output());
            System.out.printf(Locale.ROOT, "PASS %s plugin-check %s: exit %d in %.1fs%n", plugin.id(), b.name(), check.exit(), check.seconds());
        }
        modaccel(home, jar, copy, "game-stop");
    }
    static int line(String text, String prefix) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i + 1;
        throw new AssertionError("no line starts with " + prefix);
    }
    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && Set.of(".git", ".gradle", "build", ".modaccel", ".runs").contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
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
            Matcher check = Pattern.compile("\"pluginCheck\"\\s*:\\s*\\{([^}]*)}").matcher(entry);
            String checkName = null; List<String> expects = List.of();
            if (check.find()) {
                checkName = field(check.group(1), "name");
                Matcher list = Pattern.compile("\"expects\"\\s*:\\s*\\[([^\\]]*)]").matcher(check.group(1));
                if (list.find()) { expects = new ArrayList<>(); Matcher s = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(list.group(1)); while (s.find()) expects.add(s.group(1).replace("\\\"", "\"")); }
            }
            plugins.add(new Plugin(field(entry, "id"), field(entry, "repo"), field(entry, "commit"),
                    Integer.parseInt(number(fast.group(1), "executed")), Integer.parseInt(number(fast.group(1), "failed")),
                    field(jar.group(1), "task"), field(jar.group(1), "file"), checkName, expects));
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
