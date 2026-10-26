import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Keep Gradle authoritative; reuse compiled outputs only after hashing their inputs AND bytes. */
final class FastLane {
    static Map<String, Object> run(Build build, Map<String, Object> model, Modaccel.Options options) throws Exception {
        List<String> tasks = options.values.containsKey("task") ? List.of(options.get("task", "")) : build.tasks(model, "modaccelPure");
        if (tasks.isEmpty()) throw new IOException("No test source set, so no logic has been verified. Add JUnit 5 tests under src/test/java (tag them @Tag(\"pure\") when Minecraft is on the test classpath).");
        // Custom tasks may have their own frameworks and setup: never bypass them.
        if (options.values.containsKey("task")) return gradle(build, model, tasks, null);
        // With no game or loader on the test classpath (plain Java, a Paper/Bukkit plugin) every test is game-free
        // by construction, so all of them run; with Minecraft present only tests tagged pure do.
        Selection selection = gameFree(model) ? Selection.ALL : Selection.PURE;
		// A merged JVM can load only one class for a shared FQN across modules; Gradle isolates each test task.
		if (tasks.size() > 1) return gradle(build, model, tasks, selection);
        Path stamp = build.project.state().resolve("fast-stamp.json");
        Set<Path> outputs = new TreeSet<>(); Set<String> classpath = new LinkedHashSet<>(); List<String> roots = new ArrayList<>();
        int release = 17;
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            for (Object c : Json.array(p.get("compile"))) outputs.add(Path.of(Json.object(c).get("destination").toString()));
            for (Object s : Json.array(p.get("sourceSets"))) if ("test".equals(Json.object(s).get("name"))) {
                classpath.addAll(ProjectModel.strings(Json.object(s).get("runtime")));
                roots.addAll(ProjectModel.strings(Json.object(s).get("output"))); // includes resources; JUnit discovers only classes
            }
            for (Object c : Json.array(p.get("compile"))) {
                String value = String.valueOf(Json.object(c).get("release")).replace("1.", "");
                try { release = Math.max(release, Integer.parseInt(value)); } catch (NumberFormatException ignored) { }
            }
        }
        String key = Fingerprints.build(model, build.project, build.init);
        boolean reuse = false;
        if (Files.exists(stamp) && !options.has("refresh")) {
            var cached = Json.read(stamp);
            reuse = key.equals(cached.get("inputs")) && Fingerprints.files(outputs).equals(cached.get("outputs"));
        }
        if (!reuse) {
            Map<String, Object> result = gradle(build, model, tasks, selection);
            if (Boolean.TRUE.equals(result.get("ok"))) Json.save(stamp, Json.map("inputs", key, "outputs", Fingerprints.files(outputs)));
            return result;
        }
        classpath.removeIf(path -> path.contains("fabric-loader-junit"));
        Path runner = build.project.state().resolve("pure-runner"); Files.createDirectories(runner);
        Path source = build.harness.resolve("tools/runner/PureTests.java"), json = build.harness.resolve("tools/cli/Json.java");
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(build.harness, release);
        Path runnerStamp = runner.resolve("source.sha256"); String sourceKey = Fingerprints.files(List.of(source, json)) + "|" + jdk.home() + "|" + key;
        String cp = String.join(File.pathSeparator, classpath);
        if (!Files.exists(runnerStamp) || !Files.readString(runnerStamp).equals(sourceKey)) {
            var compile = Run.command(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                    "--release", Integer.toString(release), "-cp", cp, "-d", runner.toString(), source.toString(), json.toString()),
                    build.project.root, Map.of(), build.report.log("runner-compile"), 120);
            build.report.stage("runner-compile", compile, Map.of());
            if (compile.exit() != 0) throw new IOException("JUnit Platform is unavailable on the test runtime classpath. " + Run.tail(compile.log()));
            Files.writeString(runnerStamp, sourceKey);
        }
        Path result = build.report.directory.resolve("tests.json");
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-cp", runner + File.pathSeparator + cp, "PureTests", result.toString()));
        if (selection == Selection.ALL) command.add(1, "-Dmodaccel.allTests=true");
        command.addAll(roots);
        var run = Run.command(command, build.project.root, Map.of(), build.report.log("pure-tests"), 120);
        build.report.stage("pure-tests", run, Json.map("compiledOutputs", "verified reuse"));
        if (!Files.exists(result)) return Json.map("ok", false, "tier", "logic", "gameVerified", false, "selection", selection.label,
                "tests", Json.map("executed", 0, "failed", 0, "skipped", 0), "log", run.log().toString(),
                "details", "The test JVM stopped before reporting any result. " + Run.tail(run.log()));
        Map<String, Object> counts = Json.read(result);
        int executed = ((Number)counts.get("executed")).intValue(), failed = ((Number)counts.get("failed")).intValue();
        boolean ok = run.exit() == 0 && Boolean.TRUE.equals(counts.get("ok"));
        String details = ok ? passed(executed, selection) : executed == 0 ? none(selection, testClasses(model))
                : failed + " of " + executed + " tests failed: " + Json.write(counts.get("failures"));
        return Json.map("ok", ok, "tier", "logic", "gameVerified", false, "selection", selection.label, "tests", counts,
                "reuse", "compiled outputs verified by content; tests rerun in a fresh JVM", "details", details);
    }
    /** Which tests fast runs: all of them when nothing game-side is on the test classpath, else only @Tag("pure"). */
    enum Selection {
        ALL("all tests: no Minecraft or loader on the test classpath"), PURE("tests tagged pure: Minecraft or a loader is on the test classpath");
        final String label; Selection(String label) { this.label = label; }
    }
    /** A jar or directory of the game or a mod loader. Bukkit/Paper API jars are not the game and stay game-free. */
    static final java.util.regex.Pattern GAME = java.util.regex.Pattern.compile(
            "net[\\\\/.]minecraft[\\\\/.]|minecraft-(client|server|merged|common|clientonly)|fabric-loader|neoforged|minecraftforge|neoform|loom-cache|paperweight|paper-server|craftbukkit|spigot-\\d");
    static boolean gameFree(Map<String, Object> model) {
        boolean anyTest = false;
        for (Object item : Json.array(model.get("projects")))
            for (Object s : Json.array(Json.object(item).get("sourceSets")))
                if ("test".equals(Json.object(s).get("name"))) {
                    anyTest = true;
                    for (String path : ProjectModel.strings(Json.object(s).get("runtime")))
                        if (GAME.matcher(path.toLowerCase(Locale.ROOT)).find()) return false;
                }
        return anyTest;
    }
    /** Compiled top-level test classes, to tell "no tests at all" from "tests exist but none matched". */
    static long testClasses(Map<String, Object> model) throws IOException {
        long count = 0;
        for (Object item : Json.array(model.get("projects")))
            for (Object s : Json.array(Json.object(item).get("sourceSets")))
                if ("test".equals(Json.object(s).get("name")))
                    for (String root : ProjectModel.strings(Json.object(s).get("output"))) {
                        Path dir = Path.of(root); if (!Files.isDirectory(dir)) continue;
                        try (var files = Files.walk(dir)) {
                            count += files.filter(f -> f.toString().endsWith(".class") && !f.getFileName().toString().contains("$")).count();
                        }
                    }
        return count;
    }
    static String passed(int executed, Selection selection) {
        return executed + " test" + (executed == 1 ? "" : "s") + " passed (" + (selection == null ? "custom task" : selection.label) + ").";
    }
    static String none(Selection selection, long classes) {
        if (selection == null) return "No tests ran in the requested task, so no logic has been verified.";
        if (classes == 0) return "No tests found: the test source set has no compiled test classes. Add JUnit 5 tests under src/test/java"
                + (selection == Selection.PURE ? ", tagged @Tag(\"pure\") when they never touch Minecraft." : ".");
        if (selection == Selection.ALL) return "No tests ran: " + classes + " test classes compiled, but JUnit Jupiter found no tests in them (fast runs JUnit 5 Jupiter tests).";
        return "No tests tagged pure ran. Minecraft or a mod loader is on the test classpath, so fast runs only game-free tests: "
                + "tag those with @Tag(\"pure\"), or use `modaccel test` for the whole suite (" + classes + " test classes found).";
    }
    static Map<String, Object> gradle(Build build, Map<String, Object> model, List<String> tasks, Selection selection) throws Exception {
        var arguments = new ArrayList<>(List.of("-Dmodaccel.recompile=true"));
        if (selection == Selection.ALL) arguments.add("-Dmodaccel.allTests=true");
        arguments.addAll(tasks);
        var run = build.gradle(arguments, "pure-tests");
        var counts = TestResults.readTasks(model, tasks, build.report.id);
        int executed = ((Number)counts.get("executed")).intValue(), failed = ((Number)counts.get("failed")).intValue();
        boolean ok = run.exit() == 0 && executed > 0 && failed == 0;
        String details = ok ? passed(executed, selection)
                : failed > 0 ? failed + " of " + executed + " tests failed. " + Run.tail(run.log())
                : run.exit() != 0 ? "Tests did not run: the Gradle build failed. " + Run.tail(run.log())
                : none(selection, testClasses(model));
        return Json.map("ok", ok, "tier", "logic", "gameVerified", false, "selection", selection == null ? "custom task" : selection.label,
                "tests", counts, "log", run.log().toString(), "details", details);
    }
}
