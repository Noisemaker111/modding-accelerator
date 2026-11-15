import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * What a person reads when something goes wrong: the cause, where it is, and what to do.
 * From the stranger playthrough (an outside modder using modaccel on Diorama and F3Nope): a message that hid the
 * cause, or a pass that should have failed. Every case runs the real code path on real files (or real tool output)
 * and checks what a person would read.
 */
public final class ErrorMessageTests {

    static int count;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("modaccel stranger");
        try {
            invalidJson();
            compilerErrors();
            unreachableRepository();
            unknownRelease(Path.of(args[0]));
            options();
            setupText(scratch);
            staleLoaderCache(scratch);
            missingRuntime();
            optionalInjections();
            jdkTypo();
            gameStartup(scratch);
            insideCheckout(scratch);
            launcher();
            System.out.println(count + " error message checks passed: JSON positions, compiler errors, offline repository, version typos, --project, setup text, stale NeoForge cache, missing runtime files, optional injections, JDK typos, game start-up, --root inside a mod, Java 8 launcher");
        } finally {
            for (Path p : walk(scratch)) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** A recipe with a missing comma compiled and passed; the game skipped it with no word to the modder. */
    static void invalidJson() {
        try {
            Json.parseReporting("{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}");
            throw new AssertionError("a missing comma parsed");
        } catch (IllegalArgumentException expected) {
            require(
                expected.getMessage().startsWith("line 3, column 3") &&
                expected.getMessage().contains("after the value of \"type\""),
                "invalid JSON names the line, column and key: " + expected.getMessage()
            );
        }
        try {
            Json.parseReporting("[1, 2\n 3]");
            throw new AssertionError("a list without a comma parsed");
        } catch (IllegalArgumentException expected) {
            require(
                expected.getMessage().startsWith("line 2,") && expected.getMessage().contains("in a list"),
                "a list error names its line: " + expected.getMessage()
            );
        }
        require(Json.parseReporting("{\"a\": [1, 2]}") instanceof Map, "valid JSON still parses");
        require(
            ModChecks.Gson.parse("{\"url\": \"http://x\" // note\n}", true) instanceof Map<?, ?> m &&
            "http://x".equals(m.get("url")),
            "lenient JSON: comments go, strings with // stay"
        );
    }

    /** A Java typo came back as Gradle's tail twice over; a test without JUnit got raw javac errors and no hint. */
    static void compilerErrors() {
        Path modRoot = Path.of(System.getProperty("java.io.tmpdir"), "mods", "diorama").toAbsolutePath();
        List<String> typo = List.of(
            "> Task :compileJava FAILED",
            modRoot.resolve("src/main/java/dev/diorama/feature/PinMath.java") + ":7: error: cannot find symbol",
            "        return Math.rond(distance);",
            "                   ^",
            "  symbol:   method rond(double)",
            "  location: class Math",
            "1 error",
            "",
            "FAILURE: Build failed with an exception."
        );
        String text = Run.compilerErrors(typo, modRoot);
        require(
            text.startsWith("The code does not compile (1 error):") &&
            text.contains("PinMath.java:7  cannot find symbol: method rond(double) (in class Math)") &&
            text.contains("return Math.rond(distance);") &&
            !text.contains(modRoot.toString()),
            "a typo is file:line, message, symbol and code, relative to the mod: " + text
        );
        List<String> junit = List.of(
            "/home/me/mod/src/test/java/dev/PinMathTest.java:3: error: package org.junit.jupiter.api does not exist",
            "import org.junit.jupiter.api.Test;",
            "                            ^"
        );
        require(
            Run.compilerErrors(junit, null).contains("testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\")"),
            "a test without JUnit gets the dependency lines to add"
        );
        require(Run.compilerErrors(List.of("BUILD SUCCESSFUL"), null).isEmpty(), "no errors, no text");
    }

    /** No network: modaccel said "add the repository that publishes it" while the cause was "No such host is known". */
    static void unreachableRepository() throws Exception {
        List<String> log = List.of(
            "* What went wrong:",
            "Execution failed for task ':compileJava'.",
            "> Could not resolve all files for configuration ':compileClasspath'.",
            "   > Could not resolve com.example:pinlib:1.0.",
            "     Required by:",
            "         root project 'diorama'",
            "      > Could not resolve com.example:pinlib:1.0.",
            "         > Could not get resource 'https://maven.unreachable.example/com/example/pinlib/1.0/pinlib-1.0.pom'.",
            "            > Could not GET 'https://maven.unreachable.example/com/example/pinlib/1.0/pinlib-1.0.pom'.",
            "               > No such host is known (maven.unreachable.example)"
        );
        String text = Run.unreachable(log);
        require(
            text != null &&
            text.startsWith("Gradle could not reach maven.unreachable.example: the name could not be looked up") &&
            text.contains("not your code"),
            "an unreachable repository is named as the network: " + text
        );
        Path file = Files.createTempFile("gradle", ".log");
        try {
            Files.write(file, log, StandardCharsets.UTF_8);
            require(
                Run.gradleFailure(file).startsWith("Gradle could not reach maven.unreachable.example"),
                "the build failure leads with the unreachable host"
            );
        } finally {
            Files.deleteIfExists(file);
        }
        require(
            Run.unreachable(List.of("> Could not find com.example:pinlib:1.0.")) == null,
            "a dependency that is simply missing is not called a network problem"
        );
        // NeoGradle's downloader names only the URL (seen on 2026-11-07 fetching Mojang's version manifest).
        String neo = Run.unreachable(
            List.of(
                "> Uncaught exception while processing replacement of dependency net.neoforged:neoforge using handler x: Failed to download the file from: https://piston-meta.mojang.com/mc/game/version_manifest.json to: C:\\g\\launcher_metadata.json"
            )
        );
        require(
            neo != null && neo.startsWith("Gradle could not reach piston-meta.mojang.com: a download from it failed"),
            "a failed NeoGradle download is named as the network and its host: " + neo
        );
        // The wrapper's download timing out puts the URL above a cause without one (CI, 2026-11-08).
        String wrapper = Run.unreachable(
            List.of(
                "Downloading https://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
                "",
                "Attempt 1/1 failed. Reason: Downloading from https://services.gradle.org/distributions/gradle-9.7.1-bin.zip failed: timeout (10000ms)",
                "\tat org.gradle.wrapper.Download.download(SourceFile:1)",
                "Caused by: java.net.SocketTimeoutException: Read timed out"
            )
        );
        require(
            wrapper != null &&
            wrapper.startsWith("Gradle could not reach services.gradle.org: the connection failed (Read timed out)"),
            "a timed-out Gradle download names services.gradle.org: " + wrapper
        );
    }

    /** --to 1.21.66 came back as "Target dependency lookup failed (HTTP 400)". */
    static void unknownRelease(Path home) throws Exception {
        String typo = Route.unknownRelease(home, "1.21.66");
        require(
            typo != null &&
            typo.startsWith("Minecraft 1.21.66 is not a Java Edition release") &&
            typo.contains("Nothing was changed"),
            "a version typo is caught before any build: " + typo
        );
        require(
            Route.unknownRelease(home, "1.21.6") == null && Route.unknownRelease(home, "1.21") == null,
            "real releases pass"
        );
        require(Route.unknownRelease(home, "99.1") == null, "a release newer than the list is not called a typo");
    }

    /** --project 26.3 (no colon) failed with "Task with name 'modaccelProjectMetadata' not found". */
    static void options() {
        var options = new Modaccel.Options(new String[] { "explain", "--project", "26.3", "--against", "26.2, :26.1" });
        require(
            options.get("project", "").equals(":26.3") && options.get("against", "").equals(":26.2,:26.1"),
            "project paths get their leading colon: " + options.values
        );
        require(
            new Modaccel.Options(new String[] { "fast", "--project", ":" }).get("project", "").equals(":"),
            "the root project stays ':'"
        );
        require(
            Modaccel.unknownCommand("complie").startsWith("Unknown command complie. Did you mean compile?"),
            "a typed command slip gets the real one"
        );
        require(!Modaccel.unknownCommand("xyzzy").contains("Did you mean"), "no guess for nonsense");
        // Gradle's message for --project :26.4, as Diorama's build printed it.
        String noProject = Modaccel.plainError(
            new java.io.IOException(
                Build.discoveryHeadline("A problem") +
                "\nA problem occurred configuring root project 'diorama'.\n> Could not create task ':modaccelMetadata'.\n   > No project :26.4; available projects: [:, :26.2, :26.3, :26.3-neoforge]\nLog: x"
            )
        );
        require(
            noProject.startsWith("There is no project :26.4 in this build. It has 3: :26.2, :26.3, :26.3-neoforge."),
            "a wrong --project lists the real ones plainly: " + noProject
        );
        try {
            new Modaccel.Options(new String[] { "fast", "--nope", "x" });
            throw new AssertionError("an unknown option passed");
        } catch (IllegalArgumentException expected) {
            require(
                expected.getMessage().contains("modaccel help fast"),
                "an unknown option points to the command's help: " + expected.getMessage()
            );
        }
    }

    /** setup printed 30KB of JSON for Diorama; the plain text lists the mod projects and the --project to use. */
    static void setupText(Path scratch) throws Exception {
        Path root = scratch.resolve("setup");
        write(root.resolve("settings.gradle"), "");
        ProjectModel project = new ProjectModel(root);
        Map<String, Object> model = Json.map(
            "gradle",
            "9.7.1",
            "java",
            "21",
            "projects",
            List.of(
                Json.map("path", ":", "dependencies", List.of(), "sourceSets", List.of()),
                Json.map(
                    "path",
                    ":26.3",
                    "dependencies",
                    List.of(Json.map("group", "net.fabricmc", "name", "fabric-loader")),
                    "pins",
                    Json.map("minecraft_version", "26.3"),
                    "sourceSets",
                    List.of(Json.map("name", "main"))
                ),
                Json.map(
                    "path",
                    ":26.3-neoforge",
                    "dependencies",
                    List.of(Json.map("group", "net.neoforged", "name", "neoforge")),
                    "pins",
                    Json.map("minecraft_version", "26.3"),
                    "sourceSets",
                    List.of()
                )
            )
        );
        String text = Modaccel.setupText(Modaccel.summary(model, project), project);
        require(
            text.contains("2 mod projects:") &&
            text.contains(":26.3  fabric, Minecraft 26.3") &&
            text.contains(":26.3-neoforge  neoforge") &&
            text.contains("--project :26.3-neoforge"),
            "setup lists the mod projects and how to pick one: " + text
        );
    }

    /**
     * IAS :26.3-neoforge failed with "cannot find symbol getMinecraft()" in its generated INeoForge.java:
     * the cached NeoForge game jar lacked NeoForge's own patch to Screen. NeoForge's patch text decides.
     */
    static void staleLoaderCache(Path scratch) throws Exception {
        List<String> log = List.of(
            "C:\\mods\\ias\\versions\\26.3-neoforge\\build\\generated\\stonecutter\\main\\java\\ru\\vidtu\\ias\\platform\\INeoForge.java:94: error: cannot find symbol",
            "        Minecraft minecraft = screen.getMinecraft();",
            "                                    ^",
            "  symbol:   method getMinecraft()",
            "  location: variable screen of type Screen",
            "1 error"
        );
        List<LoaderPatches.Missing> missing = LoaderPatches.missing(log);
        require(
            missing.equals(List.of(new LoaderPatches.Missing("method", "getMinecraft", "Screen"))),
            "the missing member and its type are read from javac: " + missing
        );
        Path userdev = scratch.resolve("neoforge-26.3.0.97-userdev.jar"), game = scratch.resolve(
            "stale/neoforge-26.3.0.97-merged.jar"
        );
        Files.createDirectories(game.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(userdev))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("patches/net/minecraft/client/gui/screens/Screen.java.patch"));
            zip.write(
                ("--- a/net/minecraft/client/gui/screens/Screen.java\n+++ b/net/minecraft/client/gui/screens/Screen.java\n@@ -108,8 +_,12 @@\n" +
                    "     public void tick() {\n+    public Minecraft getMinecraft() {\n+        return this.minecraft;\n+    }\n").getBytes(
                        StandardCharsets.UTF_8
                    )
            );
            zip.closeEntry();
        }
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(game))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/client/gui/screens/Screen.class"));
            zip.write(new byte[] { 1 });
            zip.closeEntry();
        }
        String hint = LoaderPatches.hint(userdev, "26.3.0.97", missing, List.of(game));
        require(
            hint.startsWith(
                "NeoForge 26.3.0.97 adds Screen.getMinecraft() to the game (its own patch to net.minecraft.client.gui.screens.Screen)"
            ) &&
            hint.contains("not a mistake in your code") &&
            hint.contains("Delete " + game),
            "a member NeoForge's patch adds is called a stale cache, naming the jar: " + hint
        );
        require(
            LoaderPatches.hint(
                userdev,
                "26.3.0.97",
                List.of(new LoaderPatches.Missing("method", "getMinecrat", "Screen")),
                List.of(game)
            ).isEmpty(),
            "a misspelt member is still the code's mistake"
        );
        require(
            !LoaderPatches.adds(
                "+        return this.minecraft.getMinecraft();\n",
                new LoaderPatches.Missing("method", "getMinecraft", "Screen")
            ),
            "a call on an added line is not a declaration"
        );
    }

    /** On Apple silicon Forge's lwjgl-freetype natives-macos-patch never downloads; a game check must say so before launching. */
    static void missingRuntime() {
        Map<String, Object> model = Json.map(
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":1.21.1-forge",
                    "sourceSets",
                    List.of(
                        Json.map(
                            "name",
                            "main",
                            "runtimeUnresolved",
                            List.of(
                                "Could not find lwjgl-freetype-3.3.3-natives-macos-patch.jar (org.lwjgl:lwjgl-freetype:3.3.3)."
                            )
                        ),
                        Json.map("name", "test", "runtimeUnresolved", List.of("test-only.jar"))
                    )
                ),
                Json.map("path", ":1.21.1-fabric", "sourceSets", List.of(Json.map("name", "main")))
            )
        );
        String text = Game.missingRuntime(model, ":1.21.1-forge");
        require(
            text != null &&
            text.startsWith("The game cannot start: 1 runtime file it needs could not be downloaded") &&
            text.contains("lwjgl-freetype-3.3.3-natives-macos-patch.jar") &&
            !text.contains("test-only") &&
            text.contains("The game was not started"),
            "a missing runtime file stops the game check by name: " + text
        );
        require(Game.missingRuntime(model, ":1.21.1-fabric") == null, "another node with everything downloaded starts");
    }

    /** A require = 0 injection on a misspelt method passed mixin-check as "9 applied": Mixin skips it without a word. The line is MixinAudit's own, from Diorama. */
    static void optionalInjections() {
        String text = Modaccel.optionalInjections(
            List.of(
                "# 9 mixins, 9 targets, 20 members checked, 0 dead",
                "note: src/main/java/dev/diorama/mixin/PauseScreenTitleMixin.java:12 optional (require = 0): @Inject method 'inti' not found in net.minecraft.client.gui.screens.TitleScreen (methods: init, <init>, tick, <clinit>, added)"
            )
        );
        require(
            text.startsWith(
                "1 optional injection (require = 0) found no target in this game, so Mixin skips it without an error and it never runs:"
            ) &&
            text.contains("PauseScreenTitleMixin.java:12 @Inject method 'inti' not found") &&
            !text.contains("note:"),
            "an optional injection with no target is named: " + text
        );
        require(
            Modaccel.optionalInjections(List.of("# 9 mixins, 9 targets, 21 members checked, 0 dead")).isEmpty(),
            "nothing optional, nothing said"
        );
    }

    /** explain called Math.rond "likely newer than the Java release". */
    static void jdkTypo() {
        List<String> similar = Explain.jdkSimilar("java.lang.Math", "rond");
        require(similar != null && similar.contains("round"), "a misspelt JDK member gets the real name: " + similar);
        require(Explain.jdkSimilar("java.lang.Math", "round") == null, "a real JDK member is not a typo");
        // A build compiling for a newer Java than modaccel runs on: its own JDK's modules image is read.
        Path home = Path.of(System.getProperty("java.home"));
        List<String> fromImage = Explain.jdkSimilar("java.lang.Math", "rond", home);
        require(
            fromImage != null && fromImage.contains("round"),
            "the build's JDK image is read for the real name: " + fromImage
        );
        require(
            Explain.jdkSimilar("java.lang.String", "hashCode", home) == null,
            "a member inherited from Object is found through the supertypes"
        );
    }

    /** A registry clash crash came back inside ExecutionException wrappers in a JSON blob. */
    static void gameStartup(Path scratch) throws Exception {
        Path crash = scratch.resolve("run/crash-reports/crash-2026-10-26_12.00.00-client.txt");
        write(
            crash,
            "---- Minecraft Crash Report ----\n// Oops.\n\nTime: 2026-10-26 12:00:00\nDescription: Initializing game\n\n" +
            "java.lang.RuntimeException: Could not execute entrypoint stage 'client' due to errors, provided by 'diorama' at 'dev.diorama.fabric.DioramaFabric'!\n" +
            "\tat net.fabricmc.loader.impl.FabricLoaderImpl.lambda$invokeEntrypoints$0(FabricLoaderImpl.java:413)\n\tat net.fabricmc.loader.impl.util.ExceptionUtil.gatherExceptions(ExceptionUtil.java:33)\n" +
            "\tat knot//net.minecraft.client.Minecraft.<init>(Minecraft.java:469)\n" +
            "Caused by: java.lang.IllegalStateException: Adding duplicate key 'ResourceKey[minecraft:item / diorama:compass_pin]' to registry\n" +
            "\tat knot//net.minecraft.core.MappedRegistry.register(MappedRegistry.java:120)\n\tat knot//net.minecraft.core.Registry.register(Registry.java:150)\n" +
            "\tat knot//dev.diorama.feature.CompassPin.register(CompassPin.java:19)\n\tat knot//dev.diorama.fabric.DioramaFabric.onInitializeClient(DioramaFabric.java:40)\n\t... 12 more\n\n\nA detailed walkthrough of the error\n"
        );
        Path log = scratch.resolve("run/client.log");
        write(
            log,
            "[12:00:00] [Render thread/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.2\n#@!@# Game crashed! Crash report saved to: #@!@# " +
            crash +
            "\n"
        );
        String text = Game.startupFailure(
            new ExecutionException(new java.io.IOException("Client exited with code -1. Log: " + log)),
            log
        );
        require(
            text.startsWith("The game did not start: Client exited with code -1.") &&
            !text.contains("ExecutionException"),
            "the wrapper exceptions are gone: " + text
        );
        require(
            text.contains("Minecraft crashed. Description: Initializing game") &&
            text.contains(
                "Caused by: java.lang.IllegalStateException: Adding duplicate key 'ResourceKey[minecraft:item / diorama:compass_pin]'"
            ) &&
            text.contains(
                "first frame in the mod's code: dev.diorama.feature.CompassPin.register(CompassPin.java:19)"
            ) &&
            !text.contains("gatherExceptions") &&
            text.contains("Crash report: " + crash),
            "the crash report's cause and path are shown: " + text
        );
    }

    /** --root src gave a generic "no Gradle build" message. */
    static void insideCheckout(Path scratch) throws Exception {
        Path mod = scratch.resolve("inside");
        write(mod.resolve("settings.gradle.kts"), "");
        Files.createDirectories(mod.resolve("src/main"));
        try {
            new ProjectModel(mod.resolve("src")).requireGradleBuild();
            throw new AssertionError("src was accepted as a build");
        } catch (java.io.IOException expected) {
            require(
                expected.getMessage().contains("it is inside the mod checkout " + mod.toRealPath()) &&
                expected.getMessage().contains("Pass --root"),
                "a folder inside the mod names the checkout: " + expected.getMessage()
            );
        }
    }

    /** Java 8 on PATH gave an UnsupportedClassVersionError stack trace. The packaged entry point must load on Java 8. */
    static void launcher() throws Exception {
        Path own = Path.of(ErrorMessageTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classPath = System.getProperty("java.class.path");
        for (String entry : classPath.split(java.io.File.pathSeparator)) if (
            entry.endsWith(".jar") && Files.isRegularFile(Path.of(entry))
        ) {
            try (var jar = new java.util.jar.JarFile(entry)) {
                if (jar.getEntry("Modaccel.class") == null) continue;
                require(
                    "ModaccelLauncher".equals(jar.getManifest().getMainAttributes().getValue("Main-Class")),
                    "the jar starts at the Java 8 launcher"
                );
                byte[] header = jar.getInputStream(jar.getEntry("ModaccelLauncher.class")).readNBytes(8);
                require(
                    (((header[6] & 255) << 8) | (header[7] & 255)) == 52,
                    "the launcher class is Java 8 (class version 52)"
                );
                return;
            }
        }
        System.out.println(
            "note: running from source, so the packaged launcher is checked by the jar self-test (" + own + ")"
        );
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static List<Path> walk(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.sorted(Comparator.reverseOrder()).toList();
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }
}
