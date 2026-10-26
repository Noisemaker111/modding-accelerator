import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * The first-use moments an outside modder hit with modaccel on Diorama and F3Nope, each one from the
 * stranger playthrough: a message that hid the cause, or a pass that should have failed. Every case
 * runs the real code path on real files (or real tool output) and checks what a person would read.
 */
public final class StrangerTest {
    static int count;
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("lock")) { lockChild(Path.of(args[1])); return; }
        if (args.length == 1 && args[0].equals("sleep")) { Thread.sleep(6000); return; }
        Path scratch = Files.createTempDirectory("modaccel stranger");
        try {
            invalidJson();
            modChecks(scratch);
            compilerErrors();
            unreachableRepository();
            testCounts(scratch);
            noTests(scratch);
            projectLock(scratch);
            interruptedPortUndo(scratch);
            unknownRelease(Path.of(args[0]));
            readOnly(scratch);
            options();
            setupText(scratch);
            mixinVerdict(scratch);
            jdkTypo();
            gameStartup(scratch);
            insideCheckout(scratch);
            launcher();
            System.out.println(count + " stranger checks passed: JSON positions, resource and metadata checks, compiler errors, offline repository, test counts, no tests, one command at a time, interrupted-port undo, version typos, read-only files, --project, JDK typos, game start-up, --root inside a mod, Java 8 launcher");
        } finally {
            for (Path p : walk(scratch)) { p.toFile().setWritable(true); Files.deleteIfExists(p); }
        }
    }

    /** A recipe with a missing comma compiled and passed; the game skipped it with no word to the modder. */
    static void invalidJson() {
        try { Json.parseReporting("{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}"); throw new AssertionError("a missing comma parsed"); }
        catch (IllegalArgumentException expected) {
            require(expected.getMessage().startsWith("line 3, column 3") && expected.getMessage().contains("after the value of \"type\""), "invalid JSON names the line, column and key: " + expected.getMessage());
        }
        try { Json.parseReporting("[1, 2\n 3]"); throw new AssertionError("a list without a comma parsed"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().startsWith("line 2,") && expected.getMessage().contains("in a list"), "a list error names its line: " + expected.getMessage()); }
        require(Json.parseReporting("{\"a\": [1, 2]}") instanceof Map, "valid JSON still parses");
        require(ModChecks.withoutComments("{\"url\": \"http://x\" // note\n}").contains("http://x") && !ModChecks.withoutComments("{} // note").contains("note"), "comments go, strings with // stay");
    }

    /** fabric.mod.json errors, a mixin listed but never compiled, a MOD_ID the metadata disagrees with, a missing model and name. */
    static void modChecks(Path scratch) throws Exception {
        Path project = scratch.resolve("mod"), resources = project.resolve("build/resources/main"), classes = project.resolve("build/classes/java/main");
        Path sourceResources = project.resolve("src/main/resources"), java = project.resolve("src/main/java");
        write(resources.resolve("fabric.mod.json"), "{\n  \"schemaVersion\": 1,\n  \"id\": \"compasspin\",\n  \"entrypoints\": {\"main\": [\"dev.pin.PinMod\"]},\n  \"mixins\": [\"pin.mixins.json\"]\n}");
        write(resources.resolve("pin.mixins.json"), "{\"package\": \"dev.pin.mixin\", \"client\": [\"TitleMixin\", \"GoneMixin\"]}");
        write(classes.resolve("dev/pin/mixin/TitleMixin.class"), "x");
        write(classes.resolve("dev/pin/Other.class"), "x");
        write(resources.resolve("data/compasspin/recipe/pin.json"), "{\"type\": \"minecraft:crafting_shapeless\", \"result\": {\"id\": \"compasspin:pin\"}}");
        write(resources.resolve("data/compasspin/recipe/broken.json"), "{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}");
        write(sourceResources.resolve("data/compasspin/recipe/broken.json"), "{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}");
        write(resources.resolve("assets/compasspin/lang/en_us.json"), "{\"item.compasspin.other\": \"Other\"}");
        write(java.resolve("dev/pin/PinMod.java"), "package dev.pin;\n// public static final String MOD_ID = \"old\";\npublic class PinMod {\n    public static final String MOD_ID = \"compass_pin\";\n}\n");
        Map<String, Object> model = Json.map("projects", List.of(Json.map("path", ":", "compile", List.of(Json.map("destination", classes.toString())), "sourceSets", List.of(
                Json.map("name", "main", "output", List.of(classes.toString(), resources.toString()), "resources", List.of(sourceResources.toString()), "java", List.of(java.toString())),
                Json.map("name", "test", "output", List.of(project.resolve("build/resources/test").toString()))))));
        Map<String, Object> result = ModChecks.run(model);
        String problems = String.join("\n", ProjectModel.strings(result.get("problems"))), warnings = String.join("\n", ProjectModel.strings(result.get("warnings")));
        require(Boolean.FALSE.equals(result.get("ok")), "broken resources fail the check");
        require(problems.contains(sourceResources.resolve("data/compasspin/recipe/broken.json") + ": invalid JSON, line 3, column 3"), "the broken recipe is named by its source file, line and column: " + problems);
        require(problems.contains("lists mixin \"GoneMixin\" (client), but there is no compiled class dev.pin.mixin.GoneMixin") && !problems.contains("\"TitleMixin\""), "only the uncompiled mixin is a problem: " + problems);
        require(problems.contains("entrypoint dev.pin.PinMod names no compiled class"), "an entrypoint in the mod's own package must be compiled: " + problems);
        require(problems.contains("PinMod.java:4: the code says the mod id is \"compass_pin\"") && !problems.contains("\"old\""), "a MOD_ID that disagrees with fabric.mod.json is found, a commented-out one is not: " + problems);
        require(warnings.contains("compasspin:pin is made by") && warnings.contains("has no item model"), "a crafted item with no model is a warning: " + warnings);
        require(warnings.contains("no \"item.compasspin.pin\" entry"), "a crafted item with no name is a warning: " + warnings);
        require(ModChecks.text(result).startsWith(ProjectModel.strings(result.get("problems")).size() + " problems the game would hit at start-up:"), "the text leads with the problem count");

        write(resources.resolve("fabric.mod.json"), "{\"schemaVersion\": 1, \"id\": \"Compass Pin\"}");
        Files.delete(resources.resolve("data/compasspin/recipe/broken.json"));
        String badId = String.join("\n", ProjectModel.strings(ModChecks.run(model).get("problems")));
        require(badId.contains("mod id \"Compass Pin\" is not valid"), "an invalid Fabric id is a problem: " + badId);

        Path neo = scratch.resolve("neo"), neoResources = neo.resolve("build/resources/main");
        write(neoResources.resolve("META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n[[mods]]\nmodId=\"Bad-Id\"\n[[dependencies.x]]\nmodId=\"minecraft\"\n[[mixins]]\nconfig=\"missing.mixins.json\"\n");
        Map<String, Object> neoModel = Json.map("projects", List.of(Json.map("sourceSets", List.of(Json.map("name", "main", "output", List.of(neoResources.toString()))))));
        String neoProblems = String.join("\n", ProjectModel.strings(ModChecks.run(neoModel).get("problems")));
        require(neoProblems.contains("mod id \"Bad-Id\" is not valid") && !neoProblems.contains("\"minecraft\" is not valid") && neoProblems.contains("lists mixin config \"missing.mixins.json\""),
                "neoforge.mods.toml: the [[mods]] id is checked, dependency ids are not, a missing mixin config is a problem: " + neoProblems);
    }

    /** A Java typo came back as Gradle's tail twice over; a test without JUnit got raw javac errors and no hint. */
    static void compilerErrors() {
        List<String> typo = List.of("> Task :compileJava FAILED",
                "C:\\mods\\diorama\\src\\main\\java\\dev\\diorama\\feature\\PinMath.java:7: error: cannot find symbol",
                "        return Math.rond(distance);",
                "                   ^",
                "  symbol:   method rond(double)",
                "  location: class Math",
                "1 error", "", "FAILURE: Build failed with an exception.");
        String text = Run.compilerErrors(typo, Path.of("C:\\mods\\diorama"));
        require(text.startsWith("The code does not compile (1 error):") && text.contains("PinMath.java:7  cannot find symbol: method rond(double) (in class Math)") && text.contains("return Math.rond(distance);")
                && !text.contains("C:\\mods\\diorama\\src"), "a typo is file:line, message, symbol and code, relative to the mod: " + text);
        List<String> junit = List.of("/home/me/mod/src/test/java/dev/PinMathTest.java:3: error: package org.junit.jupiter.api does not exist", "import org.junit.jupiter.api.Test;", "                            ^");
        require(Run.compilerErrors(junit, null).contains("testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\")"), "a test without JUnit gets the dependency lines to add");
        require(Run.compilerErrors(List.of("BUILD SUCCESSFUL"), null).isEmpty(), "no errors, no text");
    }

    /** No network: modaccel said "add the repository that publishes it" while the cause was "No such host is known". */
    static void unreachableRepository() throws Exception {
        List<String> log = List.of("* What went wrong:", "Execution failed for task ':compileJava'.",
                "> Could not resolve all files for configuration ':compileClasspath'.",
                "   > Could not resolve com.example:pinlib:1.0.",
                "     Required by:",
                "         root project 'diorama'",
                "      > Could not resolve com.example:pinlib:1.0.",
                "         > Could not get resource 'https://maven.unreachable.example/com/example/pinlib/1.0/pinlib-1.0.pom'.",
                "            > Could not GET 'https://maven.unreachable.example/com/example/pinlib/1.0/pinlib-1.0.pom'.",
                "               > No such host is known (maven.unreachable.example)");
        String text = Run.unreachable(log);
        require(text != null && text.startsWith("Gradle could not reach maven.unreachable.example: the name could not be looked up") && text.contains("not your code"), "an unreachable repository is named as the network: " + text);
        Path file = Files.createTempFile("gradle", ".log");
        try {
            Files.write(file, log, StandardCharsets.UTF_8);
            require(Run.gradleFailure(file).startsWith("Gradle could not reach maven.unreachable.example"), "the build failure leads with the unreachable host");
        } finally { Files.deleteIfExists(file); }
        require(Run.unreachable(List.of("> Could not find com.example:pinlib:1.0.")) == null, "a dependency that is simply missing is not called a network problem");
    }

    /** fast printed raw counts; a failing test's name and message were only in the XML. */
    static void testCounts(Path scratch) throws Exception {
        Path project = scratch.resolve("counts"), results = project.resolve("build/modaccel/test-results/run1/test");
        write(results.resolve("TEST-dev.PinMathTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="dev.PinMathTest" tests="3" skipped="1" failures="1" errors="0" timestamp="2026-10-26T12:00:00" hostname="pc" time="0.02">
                  <properties/>
                  <testcase name="rounds()" classname="dev.PinMathTest" time="0.001"/>
                  <testcase name="clamps()" classname="dev.PinMathTest" time="0.003">
                    <failure message="expected: &lt;4&gt; but was: &lt;5&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;4&gt; but was: &lt;5&gt;</failure>
                  </testcase>
                  <testcase name="later()" classname="dev.PinMathTest" time="0.0"><skipped/></testcase>
                </testsuite>
                """);
        Map<String, Object> counts = TestResults.read(Json.map("projects", List.of(Json.map("directory", project.toString()))), "test", "run1");
        String text = TestResults.text(counts);
        require(text.startsWith("1 of 2 tests failed:") && text.contains("dev.PinMathTest.clamps(): expected: <4> but was: <5>") && text.contains("(1 skipped)"), "a failing test is named with its message: " + text);
        require(TestResults.text(Json.map("executed", 1, "failed", 0, "skipped", 0)).equals("1 test passed"), "a pass is one plain line");
    }

    /** "No successful logic check." when the real cause was: no tests, or none tagged pure. */
    static void noTests(Path scratch) throws Exception {
        Path tests = scratch.resolve("notests/src/test/java");
        Map<String, Object> model = Json.map("projects", List.of(Json.map("sourceSets", List.of(Json.map("name", "test", "java", List.of(tests.toString()))))));
        require(FastLane.noTests(model, true).startsWith("No tests ran: this project has no test files"), "no test files is said plainly");
        write(tests.resolve("dev/PinTest.java"), "class PinTest { @org.junit.jupiter.api.Test void t() {} }");
        String untagged = FastLane.noTests(model, true);
        require(untagged.contains("none is tagged @Tag(\"pure\")") && untagged.contains("modaccel test"), "untagged tests are named as the reason, with the way out: " + untagged);
        require(!FastLane.noTests(model, false).contains("pure"), "test (all tests) does not talk about pure tags");
    }

    /** Two commands at once broke each other with "previous-compilation-data.bin" errors. */
    static void projectLock(Path scratch) throws Exception {
        Path root = scratch.resolve("locked mod"); write(root.resolve("settings.gradle"), "");
        ProjectModel project = new ProjectModel(root);
        try (ProjectLock held = ProjectLock.acquire(project, "compile")) {
            String other = child("lock", project.root.toString());
            require(other.contains("Another modaccel command (compile, process " + ProcessHandle.current().pid()) && other.contains("this one did nothing"), "a second command is refused and names the first: " + other);
        }
        require(child("lock", project.root.toString()).contains("acquired"), "the lock is free once the first command ends");
        // A game or Gradle daemon started by a command outlives it: it must not keep the project locked.
        Process sleeper;
        try (ProjectLock held = ProjectLock.acquire(project, "ui-check")) { sleeper = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), "StrangerTest", "sleep").start(); }
        try { require(child("lock", project.root.toString()).contains("acquired"), "a process the command started does not inherit its lock"); }
        finally { sleeper.destroyForcibly(); sleeper.waitFor(); }
    }
    static void lockChild(Path root) throws Exception {
        try (ProjectLock lock = ProjectLock.acquire(new ProjectModel(root), "fast")) { System.out.println("acquired"); }
        catch (java.io.IOException refused) { System.out.println(refused.getMessage()); }
    }

    /** A port killed part-way printed no undo id; --undo alone said "Missing value"; a second port stacked on the half-ported tree. */
    static void interruptedPortUndo(Path scratch) throws Exception {
        Path root = scratch.resolve("f3 nope"); write(root.resolve("settings.gradle"), "");
        write(root.resolve("gradle.properties"), "minecraft_version=1.21.6\n");
        ProjectModel project = new ProjectModel(root);
        Path transaction = project.state().resolve("ports/2026-10-26T12-47-41.5Z-2bd1a0ab");
        write(transaction.resolve("before/gradle.properties"), "minecraft_version=1.21.5\n");
        Json.save(transaction.resolve("transaction.json"), Json.map("root", project.root.toString(), "status", "in-progress",
                "before", Json.map("gradle.properties", Fingerprints.bytes("minecraft_version=1.21.5\n".getBytes(StandardCharsets.UTF_8))),
                "plan", Json.map("edits", List.of("gradle.properties"), "removals", List.of())));
        String warning = Port.interruptedText(project);
        require(warning != null && warning.contains("stopped part-way (2026-10-26 at 12:47:41 UTC)") && warning.contains("gradle.properties"), "the next command warns about the half-finished port: " + warning);
        require(new Modaccel.Options(new String[] {"port", "--root", ".", "--undo"}).get("undo", "").equals("latest"), "--undo alone means the newest port");
        Map<String, Object> undone = Port.undo(project, "latest");
        require(Boolean.TRUE.equals(undone.get("ok")) && Files.readString(root.resolve("gradle.properties")).equals("minecraft_version=1.21.5\n"), "undo puts the interrupted port's files back: " + undone.get("details"));
        require(Files.readString(transaction.resolve("current-before-undo/gradle.properties")).equals("minecraft_version=1.21.6\n"), "the half-ported copy is kept, so a later hand edit is not lost");
        require(Port.interruptedText(project) == null, "an undone port warns no more");
        try { Port.undo(project, "latest"); throw new AssertionError("a second undo found something"); }
        catch (java.io.IOException expected) { require(expected.getMessage().contains("already undone"), "nothing left to undo is said: " + expected.getMessage()); }
    }

    /** --to 1.21.66 came back as "Target dependency lookup failed (HTTP 400)". */
    static void unknownRelease(Path home) throws Exception {
        require(Files.isRegularFile(home.resolve("coverage/changes/index.json")), "the release list is at " + home);
        String typo = Route.unknownRelease(home, "1.21.66");
        require(typo != null && typo.startsWith("Minecraft 1.21.66 is not a Java Edition release") && typo.contains("Nothing was changed"), "a version typo is caught before any build: " + typo);
        require(Route.unknownRelease(home, "1.21.6") == null && Route.unknownRelease(home, "1.21") == null, "real releases pass");
        require(Route.unknownRelease(home, "99.1") == null, "a release newer than the list is not called a typo");
    }

    /** A read-only gradle.properties stopped a port with only its path, and an "Undo this port" for a port that changed nothing. */
    static void readOnly(Path scratch) throws Exception {
        Path file = scratch.resolve("read only/gradle.properties"); write(file, "minecraft_version=1.21.5\n");
        require(file.toFile().setReadOnly(), "the fixture file is read-only");
        try { Target.requireWritable(List.of(new Target.Edit(file, "minecraft_version=1.21.5\n", "minecraft_version=1.21.6\n"))); throw new AssertionError("a read-only file was accepted"); }
        catch (java.io.IOException expected) { require(expected.getMessage().contains("read-only") && expected.getMessage().contains("Nothing was changed"), "a read-only file stops the port before any write: " + expected.getMessage()); }
        require(Files.readString(file).equals("minecraft_version=1.21.5\n"), "nothing was written");
        String plain = Modaccel.plainError(new java.io.IOException("Port failed", new java.nio.file.AccessDeniedException(file.toString())));
        require(plain.startsWith("Cannot write " + file + ": access was denied"), "an access-denied error is a sentence, not a path: " + plain);
    }

    /** --project 26.3 (no colon) failed with "Task with name 'modaccelProjectMetadata' not found". */
    static void options() {
        var options = new Modaccel.Options(new String[] {"explain", "--project", "26.3", "--against", "26.2, :26.1"});
        require(options.get("project", "").equals(":26.3") && options.get("against", "").equals(":26.2,:26.1"), "project paths get their leading colon: " + options.values);
        require(new Modaccel.Options(new String[] {"fast", "--project", ":"}).get("project", "").equals(":"), "the root project stays ':'");
        try { new Modaccel.Options(new String[] {"fast", "--nope", "x"}); throw new AssertionError("an unknown option passed"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().contains("modaccel help fast"), "an unknown option points to the command's help: " + expected.getMessage()); }
    }

    /** setup printed 30KB of JSON for Diorama; the plain text lists the mod projects and the --project to use. */
    static void setupText(Path scratch) throws Exception {
        Path root = scratch.resolve("setup"); write(root.resolve("settings.gradle"), "");
        ProjectModel project = new ProjectModel(root);
        Map<String, Object> model = Json.map("gradle", "9.7.1", "java", "21", "projects", List.of(
                Json.map("path", ":", "dependencies", List.of(), "sourceSets", List.of()),
                Json.map("path", ":26.3", "dependencies", List.of(Json.map("group", "net.fabricmc", "name", "fabric-loader")), "pins", Json.map("minecraft_version", "26.3"), "sourceSets", List.of(Json.map("name", "main"))),
                Json.map("path", ":26.3-neoforge", "dependencies", List.of(Json.map("group", "net.neoforged", "name", "neoforge")), "pins", Json.map("minecraft_version", "26.3"), "sourceSets", List.of())));
        String text = Modaccel.setupText(Modaccel.summary(model, project), project);
        require(text.contains("2 mod projects:") && text.contains(":26.3  fabric, Minecraft 26.3") && text.contains(":26.3-neoforge  neoforge") && text.contains("--project :26.3-neoforge"),
                "setup lists the mod projects and how to pick one: " + text);
    }

    /** A mixin on a missing method compiled and passed; an optional one (require = 0) was silently never applied. Lines are MixinAudit's own, from Diorama. */
    static void mixinVerdict(Path scratch) throws Exception {
        Path log = scratch.resolve("mixin-audit.log");
        Files.writeString(log, "src/main/java/dev/diorama/mixin/PauseScreenTitleMixin.java:12 @Inject method 'inti' not found in net.minecraft.client.gui.screens.TitleScreen (methods: init, <init>, tick, <clinit>, added)\n"
                + "# 9 mixins, 9 targets, 20 members checked, 1 dead\n");
        Map<String, Object> dead = Modaccel.mixinResult(log, 1);
        require(Boolean.FALSE.equals(dead.get("ok")) && String.valueOf(dead.get("reason")).startsWith("1 mixin problem the game would hit") && String.valueOf(dead.get("reason")).contains("method 'inti' not found"), "a dead target fails by file and line: " + dead.get("reason"));
        Files.writeString(log, "note: src/main/java/dev/diorama/mixin/PauseScreenTitleMixin.java:12 optional (require = 0): @Inject method 'inti' not found in net.minecraft.client.gui.screens.TitleScreen (methods: init)\n"
                + "# 9 mixins, 9 targets, 20 members checked, 0 dead\n");
        String optional = String.valueOf(Modaccel.mixinResult(log, 0).get("details"));
        require(optional.contains("1 optional injection (require = 0) whose target is missing: the game skips it without an error, so it never runs") && !optional.contains("note: note:"),
                "an optional injection with no target is said to never run: " + optional);
    }

    /** explain called Math.rond "likely newer than the Java release". */
    static void jdkTypo() {
        List<String> similar = Explain.jdkSimilar("java.lang.Math", "rond");
        require(similar != null && similar.contains("round"), "a misspelt JDK member gets the real name: " + similar);
        require(Explain.jdkSimilar("java.lang.Math", "round") == null, "a real JDK member is not a typo");
    }

    /** A registry clash crash came back inside ExecutionException wrappers in a JSON blob. */
    static void gameStartup(Path scratch) throws Exception {
        Path crash = scratch.resolve("run/crash-reports/crash-2026-10-26_12.00.00-client.txt");
        write(crash, "---- Minecraft Crash Report ----\n// Oops.\n\nTime: 2026-10-26 12:00:00\nDescription: Initializing game\n\n"
                + "java.lang.IllegalStateException: Duplicate registration diorama:compass_pin\n\tat net.minecraft.core.MappedRegistry.register(MappedRegistry.java:120)\n\n\nA detailed walkthrough of the error\n");
        Path log = scratch.resolve("run/client.log");
        write(log, "[12:00:00] [Render thread/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.2\n#@!@# Game crashed! Crash report saved to: #@!@# " + crash + "\n");
        String text = Game.startupFailure(new ExecutionException(new java.io.IOException("Client exited with code -1. Log: " + log)), log);
        require(text.startsWith("The game did not start: Client exited with code -1.") && !text.contains("ExecutionException"), "the wrapper exceptions are gone: " + text);
        require(text.contains("Minecraft crashed. Description: Initializing game") && text.contains("Duplicate registration diorama:compass_pin") && text.contains("Crash report: " + crash),
                "the crash report's cause and path are shown: " + text);
    }

    /** --root src gave a generic "no Gradle build" message. */
    static void insideCheckout(Path scratch) throws Exception {
        Path mod = scratch.resolve("inside"); write(mod.resolve("settings.gradle.kts"), ""); Files.createDirectories(mod.resolve("src/main"));
        try { new ProjectModel(mod.resolve("src")).requireGradleBuild(); throw new AssertionError("src was accepted as a build"); }
        catch (java.io.IOException expected) { require(expected.getMessage().contains("it is inside the mod checkout " + mod.toRealPath()) && expected.getMessage().contains("Pass --root"), "a folder inside the mod names the checkout: " + expected.getMessage()); }
    }

    /** Java 8 on PATH gave an UnsupportedClassVersionError stack trace. The packaged entry point must load on Java 8. */
    static void launcher() throws Exception {
        Path own = Path.of(StrangerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classPath = System.getProperty("java.class.path");
        for (String entry : classPath.split(java.io.File.pathSeparator)) if (entry.endsWith(".jar") && Files.isRegularFile(Path.of(entry))) {
            try (var jar = new java.util.jar.JarFile(entry)) {
                if (jar.getEntry("Modaccel.class") == null) continue;
                require("ModaccelLauncher".equals(jar.getManifest().getMainAttributes().getValue("Main-Class")), "the jar starts at the Java 8 launcher");
                byte[] header = jar.getInputStream(jar.getEntry("ModaccelLauncher.class")).readNBytes(8);
                require((((header[6] & 255) << 8) | (header[7] & 255)) == 52, "the launcher class is Java 8 (class version 52)");
                return;
            }
        }
        System.out.println("note: running from source, so the packaged launcher is checked by the jar self-test (" + own + ")");
    }

    static String child(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"), "StrangerTest")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }
    static String java() { return ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()); }
    static void write(Path file, String text) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file, text, StandardCharsets.UTF_8); }
    static List<Path> walk(Path root) throws Exception { try (var paths = Files.walk(root)) { return paths.sorted(Comparator.reverseOrder()).toList(); } }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); count++; }
}
