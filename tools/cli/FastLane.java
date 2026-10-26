import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Keep Gradle authoritative; reuse compiled outputs only after hashing their inputs AND bytes. */
final class FastLane {
    static Map<String, Object> run(Build build, Map<String, Object> model, Modaccel.Options options) throws Exception {
        List<String> tasks = options.values.containsKey("task") ? List.of(options.get("task", "")) : build.tasks(model, "modaccelPure");
        if (tasks.isEmpty()) throw new IOException("No test source set; no logic has been verified. Add tests tagged pure.");
        // Custom tasks may have their own frameworks and setup: never bypass them.
        if (options.values.containsKey("task")) return gradle(build, model, tasks);
		// A merged JVM can load only one class for a shared FQN across modules; Gradle isolates each test task.
		if (tasks.size() > 1) return gradle(build, model, tasks);
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
            Map<String, Object> result = gradle(build, model, tasks);
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
        command.addAll(roots);
        var run = Run.command(command, build.project.root, Map.of(), build.report.log("pure-tests"), 120);
        build.report.stage("pure-tests", run, Json.map("compiledOutputs", "verified reuse"));
        Map<String, Object> counts = Files.exists(result) ? Json.read(result) : Json.map("ok", false, "executed", 0, "error", Run.tail(run.log()));
        return Json.map("ok", run.exit() == 0 && Boolean.TRUE.equals(counts.get("ok")), "tier", "logic", "gameVerified", false, "tests", counts,
                "reuse", "compiled outputs verified by content; tests rerun in a fresh JVM",
                "details", counts.containsKey("error") ? "The test runner failed: " + counts.get("error") : TestResults.text(counts) + " (code unchanged since the last build, so tests reran without Gradle)");
    }
    static Map<String, Object> gradle(Build build, Map<String, Object> model, List<String> tasks) throws Exception {
        var arguments = new ArrayList<>(List.of("-Dmodaccel.recompile=true")); arguments.addAll(tasks);
        var run = build.gradle(arguments, "pure-tests");
        var counts = TestResults.readTasks(model, tasks, build.report.id);
        int executed = ((Number)counts.get("executed")).intValue(), failed = ((Number)counts.get("failed")).intValue();
        boolean ok = run.exit() == 0 && executed > 0 && failed == 0;
        String details;
        if (ok) details = TestResults.text(counts);
        else if (failed > 0) details = TestResults.text(counts) + "\nLog: " + run.log();
        else if (run.exit() != 0) details = Run.gradleFailure(run.log()) + "\nNo tests ran." + "\nLog: " + run.log();
        else details = noTests(model, true);
        return Json.map("ok", ok, "tier", "logic", "gameVerified", false, "tests", counts, "log", run.log().toString(), "details", details);
    }
    /**
     * Why nothing ran, in the words a newcomer needs: no test files at all, or test files none of which
     * is tagged pure (fast runs only those, since they must not need the game).
     */
    static String noTests(Map<String, Object> model, boolean pure) throws IOException {
        int files = 0, tagged = 0; List<String> folders = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(item).get("sourceSets"))) {
            var set = Json.object(s);
            if (!String.valueOf(set.get("name")).toLowerCase(Locale.ROOT).equals("test")) continue;
            for (String dir : ProjectModel.strings(set.get("java"))) {
                folders.add(dir);
                if (!Files.isDirectory(Path.of(dir))) continue;
                try (var walk = Files.walk(Path.of(dir))) {
                    for (Path file : walk.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt")).toList()) {
                        files++;
                        if (Files.readString(file).matches("(?s).*@Tag\\(\\s*\"pure\"\\s*\\).*")) tagged++;
                    }
                }
            }
        }
        String example = "\nA pure test is a JUnit 5 test that never touches a Minecraft class, for example:\n"
                + "  @org.junit.jupiter.api.Tag(\"pure\") class MathTest { @org.junit.jupiter.api.Test void adds() { org.junit.jupiter.api.Assertions.assertEquals(4, 2 + 2); } }\n"
                + "The build needs JUnit: testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\") and testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:1.11.4\").";
        if (files == 0) return "No tests ran: this project has no test files" + (folders.isEmpty() ? " (it has no test source set)" : " in " + folders.get(folders.size() - 1))
                + ", so no logic was checked." + (pure ? example : "");
        if (pure && tagged == 0) return "No tests ran: found " + files + " test file(s), but none is tagged @Tag(\"pure\"). fast runs only pure tests, since they run without the game;"
                + " tag the ones that never touch a Minecraft class, or run modaccel test for all of them." + example;
        return "No tests ran, though " + files + " test file(s) exist" + (pure ? " (" + tagged + " tagged pure)" : "") + ". Check that they are JUnit 5 tests (org.junit.jupiter) with @Test methods.";
    }
}
