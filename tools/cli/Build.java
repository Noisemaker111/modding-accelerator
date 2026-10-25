import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Build {
    final ProjectModel project; final Path harness, init; final String selected;
    final Modaccel.Report report; final Runtimes.Jdk jdk;
    Build(ProjectModel project, Path harness, String selected, Modaccel.Report report) throws Exception {
        this.project = project; this.harness = harness; this.selected = selected; this.report = report;
        init = harness.resolve("tools/cli/gradle.init.gradle");
        long start = System.nanoTime();
        int required = project.javaRequirement();
        for (int version : project.javaToolchainVersions()) Runtimes.ensureExact(harness, version);
        if (required > 0) Runtimes.ensureAtLeast(harness, required);
        jdk = Runtimes.ensureForGradle(harness, project.wrapperVersion());
        report.stages.add(Json.map("name", "java-setup", "seconds", (System.nanoTime() - start) / 1e9,
                "required", required, "gradleJava", jdk.version(), "home", jdk.home().toString()));
    }
    static final long DAEMON_IDLE_MS = 30 * 60 * 1000;
    /** True when the user's Gradle home, the build, or GRADLE_OPTS already sets the daemon idle timeout. */
    boolean userSetsIdleTimeout() throws IOException {
        String key = "org.gradle.daemon.idletimeout";
        if (System.getenv().getOrDefault("GRADLE_OPTS", "").contains(key)) return true;
        Path gradleHome = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
        for (Path properties : List.of(gradleHome.resolve("gradle.properties"), project.root.resolve("gradle.properties")))
            if (!ProjectModel.properties(properties).getProperty(key, "").isBlank()) return true;
        return false;
    }
    Path daemons() { return project.state().resolve("gradle-daemons").toAbsolutePath(); }
    /** Stops the Gradle daemons modaccel started for this checkout; null if it never started any. */
    Run.Result stopDaemons() throws Exception {
        if (!Files.isDirectory(daemons())) return null;
        Path wrapper = project.root.resolve("gradle/wrapper/gradle-wrapper.jar");
        Run.Result result = Run.command(List.of(jdk.java().toString(), "-cp", wrapper.toString(), "org.gradle.wrapper.GradleWrapperMain", "--stop",
                "-Dorg.gradle.daemon.registry.base=" + daemons()), project.root, Map.of("JAVA_HOME", jdk.home().toString()), report.log("stop-daemons"), 120);
        report.stage("stop-daemons", result, Json.map("registry", daemons().toString())); return result;
    }
    Run.Result gradle(List<String> arguments, String stage) throws Exception {
        List<String> command = command(arguments);
        Run.Result result = Run.command(command, project.root, Map.of("JAVA_HOME", jdk.home().toString()), report.log(stage), 900);
        boolean fallback = result.exit() != 0 && configuresOnDemand() && selectionFailed(result.log());
        report.stage(stage, result, Json.map("java", jdk.version(), "gradle", project.wrapperVersion(), "configureOnDemand", configuresOnDemand(),
                "selectionFailure", fallback));
        if (!fallback) return result;
        // The init script registers modaccel's tasks as each project configures. If a build still hides
        // a task from Gradle's on-demand selection, configure every project once, as before, and say so.
        command.add(command.indexOf("-I") + 2, "--no-configure-on-demand");
        Run.Result retry = Run.command(command, project.root, Map.of("JAVA_HOME", jdk.home().toString()), report.log(stage + "-all-projects"), 900);
        report.stage(stage + "-all-projects", retry, Json.map("java", jdk.version(), "gradle", project.wrapperVersion(), "configureOnDemand", false,
                "fallback", "Gradle could not select a task while configuring on demand; retried once with --no-configure-on-demand", "firstLog", result.log().toString()));
        System.err.println("Note: " + stage + " could not select its task with configure-on-demand; retried with every project configured (receipt stage " + stage + "-all-projects).");
        return retry;
    }
    static final java.util.regex.Pattern SELECTION_FAILURE = java.util.regex.Pattern.compile("Task '[^']*' not found in |Cannot locate tasks? that match ");
    static boolean selectionFailed(Path log) throws IOException {
        return Files.exists(log) && SELECTION_FAILURE.matcher(Files.readString(log, java.nio.charset.StandardCharsets.UTF_8)).find();
    }
    Boolean configureOnDemand;
    /** True when the build or the user's Gradle home turns on org.gradle.configureondemand. */
    boolean configuresOnDemand() throws IOException {
        if (configureOnDemand != null) return configureOnDemand;
        String key = "org.gradle.configureondemand"; String value = "";
        Path gradleHome = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
        // The user's Gradle home overrides the build's own setting.
        for (Path properties : List.of(project.root.resolve("gradle.properties"), gradleHome.resolve("gradle.properties"))) {
            String set = ProjectModel.properties(properties).getProperty(key, "").trim();
            if (!set.isEmpty()) value = set;
        }
        return configureOnDemand = value.equalsIgnoreCase("true");
    }
    /**
     * A compile with nothing of modaccel's in it, so Gradle can reuse its configuration cache:
     * on a 21-project build that is about 3 s instead of 11 s per reload. A build that can't use
     * the cache still compiles; Gradle just reports the problem and configures as usual.
     */
    Run.Result compileCached(List<String> tasks, String stage) throws Exception {
        List<String> command = new ArrayList<>(command(tasks));
        int init = command.indexOf("-I"); if (init >= 0) { command.remove(init + 1); command.remove(init); }
        command.replaceAll(argument -> argument.equals("--no-configuration-cache") ? "--configuration-cache" : argument);
        command.removeIf(argument -> argument.startsWith("-Dmodaccel.") || argument.equals("-Dfabric.loom.ci=true"));
        Run.Result result = Run.command(command, project.root, Map.of("JAVA_HOME", jdk.home().toString()), report.log(stage), 900);
        report.stage(stage, result, Json.map("java", jdk.version(), "gradle", project.wrapperVersion(), "configurationCache", true)); return result;
    }
    List<String> command(List<String> arguments) throws IOException {
        Path wrapper = project.root.resolve("gradle/wrapper/gradle-wrapper.jar");
        if (!Files.isRegularFile(wrapper)) throw new IOException("Missing " + wrapper + "; restore this project's Gradle wrapper jar.");
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-cp", wrapper.toString(), "org.gradle.wrapper.GradleWrapperMain",
                // The init script registers its tasks as each project configures, so a build with
                // org.gradle.configureondemand=true configures only the projects a command names.
                "--console=plain", "--no-configuration-cache", "-I", init.toString()));
        // Test hook: make the init script miss under configure-on-demand to exercise the fallback.
        if ("true".equals(System.getenv("MODACCEL_SIMULATE_COD_MISS"))) command.add("-Dmodaccel.simulateCodMiss=true");
        List<String> toolchains = new ArrayList<>();
        Path projectCache = harness.resolve(".modaccel/jdks").toAbsolutePath().normalize();
        Path userCache = Path.of(System.getProperty("user.home"), ".modaccel/jdks").toAbsolutePath().normalize();
        for (Runtimes.Jdk installed : Runtimes.installed(harness)) {
            Path home = installed.home().toAbsolutePath().normalize();
            if (home.startsWith(projectCache) || home.startsWith(userCache)) toolchains.add(home.toString());
        }
        if (!toolchains.isEmpty()) {
            Path gradleHome = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
            for (Path properties : List.of(gradleHome.resolve("gradle.properties"), project.root.resolve("gradle.properties"))) {
                String existing = ProjectModel.properties(properties).getProperty("org.gradle.java.installations.paths", "");
                if (!existing.isBlank()) toolchains.add(existing);
            }
            command.add("-Dorg.gradle.java.installations.paths=" + String.join(",", toolchains));
        }
        if (!selected.isBlank()) command.add("-Dmodaccel.project=" + selected);
        // Loom skips dependency source remapping for CLI checks; compiled dependency remapping still runs.
        command.add("-Dfabric.loom.ci=true");
        command.add("-Dmodaccel.runId=" + report.id);
        // Daemons of modaccel's own, so game-stop can stop them without touching the user's or another
        // agent's (gradle --stop would end them all), and one left running leaves after half an hour idle.
        command.add("-Dorg.gradle.daemon.registry.base=" + daemons());
        // A command-line value would override one the user set, so only fill in a default.
        if (!userSetsIdleTimeout()) command.add("-Dorg.gradle.daemon.idletimeout=" + DAEMON_IDLE_MS);
        command.addAll(arguments);
        return command;
    }
    Map<String, Object> model(boolean refresh) throws Exception { return model(refresh, false); }
    Map<String, Object> model(boolean refresh, boolean planning) throws Exception {
        Path model = project.state().resolve(planning ? "plan-model.json" : "model.json");
        Path stamp = project.state().resolve(planning ? "plan-model-stamp.json" : "model-stamp.json");
        String key = project.configurationHash(init) + "|" + selected + "|" + jdk.home() + "|" + (planning ? "plan" : "full");
        if (!refresh && Files.isRegularFile(model) && Files.isRegularFile(stamp)) {
            Map<String, Object> cached = Json.read(stamp);
            if (key.equals(cached.get("key")) && System.currentTimeMillis() - ((Number)cached.get("at")).longValue() < 3600000
                    && classpathExists(Json.read(model))) {
                report.stages.add(Json.map("name", "metadata", "cache", "hit", "seconds", 0)); return Json.read(model);
            }
        }
        Run.Result result = gradle(planning ? List.of("-Dmodaccel.metadataMode=plan", ":modaccelMetadata") : List.of(":modaccelMetadata"), planning ? "metadata-plan" : "metadata");
        if (result.exit() != 0) throw new IOException("Build discovery failed. " + Run.tail(result.log()) + "\nLog: " + result.log());
        Map<String, Object> value = Json.read(model);
        Json.save(stamp, Json.map("key", key, "at", System.currentTimeMillis())); return value;
    }
    static boolean classpathExists(Map<String, Object> model) {
        for (Object p : Json.array(model.get("projects"))) {
            var project = Json.object(p);
            for (Object artifact : Json.array(project.get("artifacts")))
                if (!Files.isRegularFile(Path.of(Json.text(Json.object(artifact), "file", "")))) return false;
            for (Object c : Json.array(project.get("compile")))
                for (String path : ProjectModel.strings(Json.object(c).get("classpath"))) if (path.endsWith(".jar") && !Files.isRegularFile(Path.of(path))) return false;
        }
        return true;
    }
    List<String> tasks(Map<String, Object> model, String name) {
        List<String> result = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            if (name.equals("classes")) {
                for (Object s : Json.array(p.get("sourceSets"))) {
                    var set = Json.object(s);
                    String task = Json.text(set, "classesTask", "");
                    if (!task.isEmpty()) result.add((p.get("path").equals(":") ? "" : p.get("path")) + ":" + task);
                }
                continue;
            }
            boolean found = name.equals("modaccelPure") ? Json.array(p.get("sourceSets")).stream().anyMatch(s -> "test".equals(Json.object(s).get("name")))
                    : Json.array(p.get("tasks")).stream().anyMatch(t -> Json.text(Json.object(t), "task", "").endsWith(":" + name));
            if (found) result.add((p.get("path").equals(":") ? "" : p.get("path")) + ":" + name);
        }
        return result;
    }
}
