import java.io.*;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Each case triggers a failure modaccel used to report badly (or as a pass) and checks the message. */
public final class HardeningTest {
    static int count;
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("modaccel-hardening");
        try {
            missingDependency(scratch);
            nonCompilerFailure(scratch);
            mixinAudit(scratch);
            interruptedUndo(scratch);
            neoForgeUnchecked(scratch);
            serverDeath(scratch);
            lookupFailures();
            badRoot(scratch);
            timeouts(scratch);
            loomVersions();
            System.out.println(count + " hardening checks passed: missing dependency, non-compiler failure, mixin audit summary, interrupted undo, NeoForge unchecked, server death, lookup errors, bad root, Gradle timeout");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    static final String CREATE_LOG = """
            > Task :compileJava FAILED

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':compileJava'.
            > Could not resolve all files for configuration ':compileClasspath'.
               > Could not find com.simibubi.create:create-1.21.1:6.0.4-55.
                 Searched in the following locations:
                   - https://maven.createmod.net/com/simibubi/create/create-1.21.1/6.0.4-55/create-1.21.1-6.0.4-55.pom
                   - https://maven.neoforged.net/releases/com/simibubi/create/create-1.21.1/6.0.4-55/create-1.21.1-6.0.4-55.pom
                 Required by:
                     root project 'reactive'
               > Could not find dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.2.
                 Required by:
                     root project 'reactive'

            * Try:
            > Run with --stacktrace option to get the stack trace.
            """ + "Gradle footer line\n".repeat(40);
    /** A missing dependency names its coordinate and where Gradle looked, even below a long footer. */
    static void missingDependency(Path scratch) throws Exception {
        Path log = scratch.resolve("missing.log"); Files.writeString(log, CREATE_LOG);
        String text = Run.gradleFailure(log);
        require(text.startsWith("Gradle could not resolve 2 dependencies: com.simibubi.create:create-1.21.1:6.0.4-55, dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.2."),
                "missing dependencies are named with their full coordinates: " + text);
        require(Build.discoveryHeadline(text).startsWith("Missing dependency:"), "a build that cannot be read for a missing library says so first: " + Build.discoveryHeadline(text));
        require(Build.discoveryHeadline(Run.unreachable(List.of("> Could not GET 'https://maven.example.com/a.pom'.", "Caused by: java.net.UnknownHostException: maven.example.com"))).startsWith("No connection"),
                "a network failure is headed as one");
        // Gradle's words when a build's plugins { } block names a plugin no repository has, as a real build printed them.
        String plugin = Build.discoveryHeadline("Plugin [id: 'com.example.ghost', version: '1.0'] was not found in any of the following sources:");
        require(plugin.startsWith("Missing build plugin: this project's build uses the Gradle plugin com.example.ghost 1.0,"), "a missing Gradle plugin is named: " + plugin);
        require(text.contains("https://maven.createmod.net") && text.contains("https://maven.neoforged.net"), "the searched repositories are named");
        require(text.contains("Execution failed for task ':compileJava'.") && !text.contains("Gradle footer"), "Gradle's own failure block is shown, not the footer");
        Path plain = scratch.resolve("plain.log"); Files.writeString(plain, "Caused by: java.lang.IllegalStateException: boom\n" + "tail\n".repeat(30));
        require(Run.gradleFailure(plain).contains("IllegalStateException: boom"), "a log without a Gradle block still shows its cause");
    }
    /** A build that fails with no compiler error is not called a semantic migration. */
    static void nonCompilerFailure(Path scratch) throws Exception {
        Path log = scratch.resolve("missing.log");
        String reason = Port.failureReason("1.21.4", false, null, 0, 1, "", log);
        require(reason.startsWith("The build failed without compiler errors") && reason.contains("com.simibubi.create:create-1.21.1:6.0.4-55") && !reason.contains("semantic migration"),
                "a dependency failure reads as a build failure with its block: " + reason);
        String packaged = Port.failureReason("1.21.4", true, Json.map("ok", true, "checked", true), 0, 1, "", log);
        require(packaged.contains("packaged build failed without a compiler error") && packaged.contains("Could not find"), "a failed packaged build shows why");
        require(Port.failureReason("1.21.4", false, null, 3, 2, "", log).startsWith("3 compiler error(s) remain"), "real compiler errors keep the migration message");
        String unchecked = Port.failureReason("1.21.4", true, Json.map("ok", false, "checked", false, "reason", "No client run task (runClient) in the selected project"), 0, 1, "", log);
        require(unchecked.contains("not checked by Mixin") && unchecked.contains("No client run task"), "mixins Mixin could not check are named as unchecked, never passed: " + unchecked);
        String failed = Port.failureReason("1.21.4", true, Json.map("ok", false, "checked", true), 0, 1, "", log);
        require(failed.contains("Mixin did not apply every mixin cleanly"), "a Mixin failure reads as one: " + failed);
    }
    /** The port result says what the audit looked at; "checked" never stands for nothing checked. */
    static void mixinAudit(Path scratch) throws Exception {
        Path log = scratch.resolve("mixin.log");
        Files.writeString(log, "# 0 mixins, nothing to audit\n");
        require(Port.auditSummary(log).equals("no mixins to audit"), "no mixins is reported as nothing to audit");
        Files.writeString(log, "note: a.java: target Foo is not in the given jars, not checked\n# 2 mixins, 1 target, 4 members checked, 0 dead, 1 targets not in the jar\n");
        String summary = Port.auditSummary(log);
        require(summary.startsWith("mixin targets checked (2 mixins, 1 target, 4 members checked, 0 dead)") && summary.contains("1 mixin target(s) are in no jar"),
                "skipped mixin targets are counted in the result: " + summary);
    }
    /**
     * An undo of a port that never finished puts the backups back, keeps every file it overwrites
     * beside them first (so a hand edit made after the port stopped is never lost), and says so.
     * It used to refuse and leave the modder to copy the backups by hand.
     */
    static void interruptedUndo(Path scratch) throws Exception {
        Path root = scratch.resolve("interrupted"); Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("settings.gradle"), "");
        Path source = root.resolve("src/A.java"); byte[] original = "class A {}\n".getBytes(StandardCharsets.UTF_8); Files.write(source, original);
        ProjectModel project = new ProjectModel(root);
        Path transaction = project.state().resolve("ports/interrupted"), backup = transaction.resolve("before/src/A.java");
        Files.createDirectories(backup.getParent()); Files.write(backup, original);
        Json.save(transaction.resolve("transaction.json"), Json.map("root", project.root.toString(), "before", Json.map("src/A.java", Fingerprints.bytes(original)),
                "after", Map.of(), "removed", List.of(), "plan", Json.map("edits", List.of("src/A.java"), "removals", List.of()), "status", "in-progress"));
        Files.writeString(source, "class A { half edited }\n");
        Map<String, Object> undone = Port.undo(project, transaction.toString());
        String details = String.valueOf(undone.get("details"));
        require(Boolean.TRUE.equals(undone.get("ok")) && details.contains("stopped part-way") && details.contains("src/A.java") && details.contains(transaction.resolve("current-before-undo").toString()),
                "interrupted undo says what it restored and where the overwritten copies are: " + details);
        require(Arrays.equals(Files.readAllBytes(source), original), "interrupted undo restores the exact original bytes");
        require(Files.readString(transaction.resolve("current-before-undo/src/A.java")).contains("half edited"), "the half-edited file is kept, not lost");
        Json.save(transaction.resolve("transaction.json"), Json.map("root", project.root.toString(), "before", Map.of(), "after", Map.of(), "removed", List.of(), "status", "undone"));
        try { Port.undo(project, transaction.toString()); throw new AssertionError("second undo passed"); }
        catch (IOException expected) { require(expected.getMessage().contains("already undone"), "a second undo says it was already undone"); }
    }
    /** A NeoForge mod dependency is listed as unchecked instead of being skipped silently. */
    static void neoForgeUnchecked(Path scratch) throws Exception {
        Path jar = scratch.resolve("create-1.21.1-6.0.4.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml")); zip.write("modId=\"create\"\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Path library = scratch.resolve("gson-2.10.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(library))) { zip.putNextEntry(new java.util.zip.ZipEntry("com/google/gson/Gson.class")); zip.closeEntry(); }
        Map<String, Object> project = Json.map("dependencies", List.of(
                Json.map("group", "com.simibubi.create", "name", "create-1.21.1", "version", "6.0.4", "configuration", "implementation"),
                Json.map("group", "com.google.code.gson", "name", "gson", "version", "2.10", "configuration", "implementation")),
                "artifacts", List.of(Json.map("group", "com.simibubi.create", "name", "create-1.21.1", "version", "6.0.4", "file", jar.toString()),
                        Json.map("group", "com.google.code.gson", "name", "gson", "version", "2.10", "file", library.toString())));
        Target.Setup setup = new Target.Setup(null, project, "1.21.4", List.of());
        Dependencies.resolve(setup, "neoforge");
        require(setup.unchecked.size() == 1 && "com.simibubi.create:create-1.21.1".equals(setup.unchecked.get(0).get("artifact")), "a NeoForge mod jar is listed as unchecked; a plain library is not");
        require(setup.blockers.isEmpty() && setup.applied.isEmpty(), "listing it unchecked blocks nothing");
        require(Port.uncheckedText(setup.unchecked).contains("com.simibubi.create:create-1.21.1 6.0.4") && Port.uncheckedText(setup.unchecked).contains("1.21.4"), "the result names it and the target");
    }
    /** A dedicated server that dies says how: exit code, crash report description, log. */
    static void serverDeath(Path scratch) throws Exception {
        Path crash = scratch.resolve("crash-2026.txt");
        Files.writeString(crash, "---- Minecraft Crash Report ----\nTime: now\nDescription: Exception in server tick loop\n\njava.lang.NullPointerException: zombie spawner\n\tat demo.Spawner.tick(Spawner.java:10)\n\nA detailed walkthrough\n");
        Path log = scratch.resolve("server.log");
        Files.writeString(log, "[Server thread/INFO]: Done (3.2s)!\n[Server thread/ERROR]: This crash report has been saved to: " + crash + "\n");
        String text = Server.death(1, log);
        require(text.contains("launcher exit code 1") && text.contains("Description: Exception in server tick loop") && text.contains("zombie spawner")
                && text.contains("Server log: " + log), "server death names exit, crash description and log: " + text);
        Files.writeString(log, "[Server thread/ERROR]: Failed to start the minecraft server\nCaused by: java.net.BindException: Address already in use\n");
        require(Server.death(1, log).contains("BindException"), "without a crash report, the error lines are shown");
        String netstat = "  Proto  Local Address          Foreign Address        State           PID\n  TCP    0.0.0.0:25565          0.0.0.0:0              LISTENING       4242\n  TCP    0.0.0.0:255650         0.0.0.0:0              LISTENING       1\n";
        require(Long.valueOf(4242).equals(Server.parseListeningPid(netstat, 25565, true)), "the real server pid comes from the listening port");
        require(Long.valueOf(77).equals(Server.parseListeningPid("77\n", 25565, false)) && Server.parseListeningPid("", 25565, true) == null, "lsof output and no listener");
    }
    /** A network error is never reported as "not published", and keeps the real error and the repositories. */
    static void lookupFailures() throws Exception {
        var selection = new Dependencies.Selection("a:b:1", "a:b:2", "", "", false, "");
        Map<String, Object> project = Json.map("repositories", List.of("https://maven.example.net/releases", "https://repo.example.org/maven"));
        try {
            Dependencies.verify(selection, new String[] {"a", "b", "2"}, project, "1.21.6",
                    url -> { throw Target.unreachable(url, new ConnectException("Connection refused")); }, url -> null);
            throw new AssertionError("unreachable repositories accepted");
        } catch (Target.LookupFailure expected) {
            require(expected.getMessage().contains("Could not check") && expected.getMessage().contains("Connection refused")
                    && expected.getMessage().contains("maven.example.net") && expected.getMessage().contains("repo.example.org") && !expected.getMessage().contains("is not published"),
                    "network errors keep the real error and the repository list: " + expected.getMessage());
        }
        try {
            Dependencies.verify(selection, new String[] {"a", "b", "2"}, project, "1.21.6", url -> { throw new IOException("Target dependency lookup failed (HTTP 404): " + url); }, url -> null);
            throw new AssertionError("missing version accepted");
        } catch (IOException expected) {
            require(!(expected instanceof Target.LookupFailure) && expected.getMessage().contains("is not published"), "a real 404 still reads as not published");
        }
        require(Target.unreachable("https://api.modrinth.com/v2/x", new IOException()).getMessage().contains("api.modrinth.com (IOException)"), "an exception without a message still names its type and host");
        List<Object> versions = List.of(Json.map("version_number", "2.0-beta", "version_type", "beta"), Json.map("version_number", "1.9", "version_type", "release"),
                Json.map("version_number", "1.8-alpha", "version_type", "alpha"));
        require("1.9".equals(Json.object(Dependencies.stableFirst(versions).get(0)).get("version_number")), "a full release is preferred over a newer beta");
        require("2.0-beta".equals(Json.object(Dependencies.stableFirst(versions.subList(0, 1)).get(0)).get("version_number")), "a beta is still used when nothing else exists");
        require(!Dependencies.stable(Json.map("version_type", "alpha")) && Dependencies.stable(Json.map()), "version types");
    }
    /** A bad --root or a folder with no Gradle build gets a plain message. */
    static void badRoot(Path scratch) throws Exception {
        try { new ProjectModel(scratch.resolve("no-such-mod")); throw new AssertionError("missing root accepted"); }
        catch (IOException expected) { require(expected.getMessage().contains("does not exist") && expected.getMessage().contains("no-such-mod"), "a missing root says so: " + expected.getMessage()); }
        Path file = scratch.resolve("mod.txt"); Files.writeString(file, "");
        try { new ProjectModel(file); throw new AssertionError("file root accepted"); }
        catch (IOException expected) { require(expected.getMessage().contains("is a file, not a folder"), "a file root says so"); }
        Path empty = Files.createDirectories(scratch.resolve("empty"));
        try { new ProjectModel(empty).requireGradleBuild(); throw new AssertionError("folder without a build accepted"); }
        catch (IOException expected) { require(expected.getMessage().startsWith("No Gradle build in") && expected.getMessage().contains("build.gradle"), "a folder without a build says so: " + expected.getMessage()); }
        Files.writeString(empty.resolve("settings.gradle.kts"), "");
        new ProjectModel(empty).requireGradleBuild();
        count++;
    }
    /** A Gradle timeout names its limit, whether it was a cold first setup, and how to raise it. */
    static void timeouts(Path scratch) throws Exception {
        require(Build.gradleTimeout(true, null) == Build.COLD_TIMEOUT && Build.gradleTimeout(false, "") == Build.WARM_TIMEOUT && Build.COLD_TIMEOUT == 900 && Build.WARM_TIMEOUT == 900,
                "a cold first setup and a warm build each get 15 minutes");
        require(Build.gradleTimeout(false, " 3600 ") == 3600 && Build.gradleTimeout(true, "120") == 120, "MODACCEL_GRADLE_TIMEOUT overrides both");
        try { Build.gradleTimeout(true, "soon"); throw new AssertionError("bad timeout accepted"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().contains("MODACCEL_GRADLE_TIMEOUT"), "a bad value names the variable"); }
        String message = Build.timeoutMessage(2700, true, scratch.resolve("metadata.log"));
        require(message.contains("2700 s") && message.contains("cold first setup") && message.contains("MODACCEL_GRADLE_TIMEOUT=5400") && message.contains("metadata.log"),
                "the timeout message names the limit, the cold setup and how to raise it: " + message);
        require(!Build.timeoutMessage(900, false, scratch).contains("cold"), "a warm timeout is not called cold");
    }
    /** The project's Loom version comes from its own files, so a dependency built by a newer Loom gets a warning. */
    static void loomVersions() {
        Properties props = new Properties(); props.setProperty("loom_version", "1.10-SNAPSHOT");
        require(ProjectModel.loomVersion(props, List.of()).equals("1.10-SNAPSHOT"), "loom_version property");
        Properties none = new Properties(); none.setProperty("loom", "1.9.2");
        require(ProjectModel.loomVersion(new Properties(), List.of("plugins {\n  id 'fabric-loom' version '1.11.4'\n}")).equals("1.11.4"), "groovy plugins block");
        require(ProjectModel.loomVersion(new Properties(), List.of("plugins { id(\"net.fabricmc.fabric-loom-remap\") version \"1.14-SNAPSHOT\" }")).equals("1.14-SNAPSHOT"), "kotlin plugins block");
        require(ProjectModel.loomVersion(none, List.of("plugins { id 'fabric-loom' version \"${loom}\" }")).equals("1.9.2"), "a plugin version from a property");
        require(ProjectModel.loomVersion(new Properties(), List.of("[versions]\nloom = \"1.8.13\"\n")).equals("1.8.13"), "version catalog");
        require(ProjectModel.loomVersion(new Properties(), List.of("apply plugin: 'java'")).isEmpty(), "no Loom stated");
        require(Dependencies.minor("1.10-SNAPSHOT").equals("1.10") && Versions.compare(Dependencies.minor("1.11.2"), Dependencies.minor("1.10-SNAPSHOT")) > 0, "Loom minors compare");
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); count++; }
}
