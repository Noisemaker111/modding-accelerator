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
        Run.Result result = Run.command(command(arguments), project.root, Map.of("JAVA_HOME", jdk.home().toString()), report.log(stage), 900);
        report.stage(stage, result, Json.map("java", jdk.version(), "gradle", project.wrapperVersion())); return result;
    }
    List<String> command(List<String> arguments) throws IOException {
        Path wrapper = project.root.resolve("gradle/wrapper/gradle-wrapper.jar");
        if (!Files.isRegularFile(wrapper)) throw new IOException("Missing " + wrapper + "; restore this project's Gradle wrapper jar.");
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-cp", wrapper.toString(), "org.gradle.wrapper.GradleWrapperMain",
                "--console=plain", "--no-configuration-cache", "-I", init.toString()));
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
        command.add("-Dorg.gradle.daemon.idletimeout=" + DAEMON_IDLE_MS);
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
        Run.Result result = gradle(planning ? List.of("-Dmodaccel.metadataMode=plan", "modaccelMetadata") : List.of("modaccelMetadata"), planning ? "metadata-plan" : "metadata");
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
