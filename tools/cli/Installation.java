import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

final class Installation {

    static final List<String> RESOURCES = List.of(
        "tools/cli/gradle.init.gradle",
        "PERFORMANCE.md",
        "tools/perf/PerfAgent.java",
        "tools/perf/suite.html",
        "tools/perf/lib/asm-9.9.jar",
        "tools/perf/lib/ASM-LICENSE.txt",
        "tools/cli/Json.java",
        "tools/cli/Relocate.java",
        "tools/cli/QuietOptions.java",
        "tools/agent/PortableAgent.java",
        "tools/agent/BackgroundWindows.java",
        "tools/agent/GameLaunchAgent.java",
        "tools/agent/Console.java",
        "tools/agent/HotReload.java",
        "tools/runner/PureTests.java",
        "tools/plugincheck/MmtPluginCheck.java",
        "tools/servercheck/MmtServerCheck.java",
        "tools/packcheck/MmtPackProbe.java",
        "tools/packcheck/MmtPackProbeFabric.java",
        "tools/packcheck/MmtPackOpener.java",
        "tools/packcheck/stubs/net/minecraftforge/fml/common/Mod.java",
        "tools/packcheck/stubs/net/neoforged/fml/common/Mod.java",
        "tools/packcheck/stubs/net/fabricmc/api/DedicatedServerModInitializer.java",
        "tools/packcheck/mods.toml",
        "tools/packcheck/neoforge.mods.toml",
        "tools/packcheck/fabric.mod.json",
        "tools/packcheck/pack.mcmeta",
        "tools/packcheck/game/1.16/MmtPackGame.java",
        "tools/packcheck/game/1.18/MmtPackGame.java",
        "tools/packcheck/game/1.19.3/MmtPackGame.java",
        "tools/packcheck/game/1.21.2/MmtPackGame.java",
        "tools/packcheck/game/1.21.5/MmtPackGame.java",
        "tools/packcheck/game/1.21.11/MmtPackGame.java",
        "tools/packcheck/game/26.1/MmtPackGame.java",
        "tools/VersionMap.java",
        "tools/GameDiff.java",
        "tools/MixinAudit.java",
        "tools/mixincheck/MmtMixinCheck.java",
        "tools/mixincheck/MmtMixinErrors.java",
        "tools/mixincheck/MmtLaunchRecorder.java",
        "tools/mixincheck/MmtNeoForgeTarget.java",
        "tools/datacheck/MmtDataCheck.java",
        "tools/datacheck/MmtDataProbe.java",
        "tools/datacheck/mod/SkipShapeCache.java",
        "tools/datacheck/mod/fabric.mod.json",
        "tools/datacheck/mod/mmt_datacheck.mixins.json",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinErrorHandler.java",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinInfo.java",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinConfig.java",
        "tools/GamePrep.java",
        "tools/tests/PortTests.java",
        "tools/tests/PortRepairTests.java",
        "tools/tests/ConvertTests.java",
        "tools/tests/BuildScriptTests.java",
        "tools/tests/CommandTests.java",
        "tools/tests/GameTests.java",
        "tools/tests/PerfTest.java",
        "tools/tests/JdkDownloadsTest.java",
        "tools/tests/DependenciesTest.java",
        "tools/tests/FabricMetadataTest.java",
        "tools/tests/HardeningTest.java",
        "tools/tests/MixinCheckTest.java",
        "tools/tests/TrustTest.java",
        "tools/tests/ModFileTests.java",
        "tools/tests/ErrorMessageTests.java",
        "tools/tests/ProjectSafetyTests.java",
        "tools/tests/PackCheckFixtures.java",
        "tools/tests/PackMetadataTests.java",
        "tools/tests/PackLogTests.java",
        "tools/tests/PackServerTests.java",
        "tools/tests/PackRepairTests.java",
        "tools/tests/RulesTest.java",
        "knowledge/",
        "coverage/changes/index.json"
    );

    /** The jar entry listing every resource the package carries, so unpacking restores exactly what was packed. */
    static final String RESOURCE_LIST = "mmt-resources.txt";

    /**
     * Every file a package carries: RESOURCES with each directory (an entry ending in /) expanded, and the change
     * index's step files for updates into unobfuscated releases, which port's by-name lookups read (Port.byName).
     * A hand-kept list once missed knowledge/meaning-changes.json and perf-patterns.json, so the shipped port and
     * perf-optimize silently ran without them.
     */
    static List<String> resources(Path harness) throws IOException {
        List<String> files = new ArrayList<>();
        for (String name : RESOURCES) {
            if (!name.endsWith("/")) {
                files.add(name);
                continue;
            }
            try (var walk = Files.walk(harness.resolve(name))) {
                walk
                    .filter(Files::isRegularFile)
                    .map(p -> harness.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(files::add);
            }
        }
        try (var steps = Files.list(harness.resolve("coverage/changes"))) {
            steps
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json") && n.contains("__"))
                .filter(n -> {
                    String[] sides = n.substring(0, n.length() - 5).split("__");
                    return Versions.compare(sides[1], Route.FIRST_UNOBFUSCATED) >= 0;
                })
                .sorted()
                .forEach(n -> files.add("coverage/changes/" + n));
        }
        return files;
    }

    static Path home() throws Exception {
        Path location = Path.of(Mmt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // A child started by perf-optimize (PerfOptimize.childJava) is handed its parent's resources; a test hands its own.
        // Not mmt.home: the portable launchers set that to the ZIP's folder, which has no resources.
        if (System.getProperty("mmt.resources") != null) return Path.of(System.getProperty("mmt.resources"))
            .toAbsolutePath()
            .normalize();
        if (!location.toString().endsWith(".jar")) return Path.of(System.getProperty("mmt.home", "."))
            .toAbsolutePath()
            .normalize();
        Path directory = Path.of(
            System.getProperty("user.home"),
            ".mmt/packages",
            Fingerprints.bytes(Files.readAllBytes(location))
        );
        try (var jar = new JarFile(location.toFile())) {
            List<String> names;
            try (var input = jar.getInputStream(jar.getJarEntry(RESOURCE_LIST))) {
                names = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            }
            for (String name : names) {
                Path file = directory.resolve(name);
                byte[] bytes;
                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                    bytes = input.readAllBytes();
                }
                if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
                    Files.createDirectories(file.getParent());
                    Path temporary = Files.createTempFile(file.getParent(), "resource-", ".tmp");
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException busy) {
                        // Two commands from one package starting at once both unpack it; on Windows the second cannot
                        // replace a file the first just wrote and holds open. Those bytes are the same ones.
                        Files.deleteIfExists(temporary);
                        if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) throw busy;
                    }
                }
            }
        }
        return directory;
    }

    static Map<String, Object> pack(Path harness, Mmt.Report report) throws Exception {
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17);
        Path directory = report.root.resolve("build/distributions");
        Files.createDirectories(directory);
        Path classes = report.directory.resolve("package-classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(
            List.of(
                jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString()
            )
        );
        try (var files = Files.list(harness.resolve("tools/cli"))) {
            files
                .filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("Bootstrap.java"))
                .sorted()
                .forEach(p -> command.add(p.toString()));
        }
        var compile = Run.command(command, report.root, Map.of(), report.log("package-compile"), 120);
        report.stage("package-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));
        // The entry point is built for Java 8 so `java -jar` on an old Java says which Java it needs instead of a class-version stack trace.
        var launcher = Run.command(
            List.of(
                command.get(0),
                "--release",
                "8",
                "-nowarn",
                "-Xlint:-options",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                harness.resolve("tools/launcher/MmtLauncher.java").toString()
            ),
            report.root,
            Map.of(),
            report.log("package-launcher"),
            60
        );
        report.stage("package-launcher", launcher, Map.of());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", launcher.exit() == 0 ? "MmtLauncher" : "Mmt");
        Path target = directory.resolve("mmt.jar"), temporary = directory.resolve("mmt.jar.tmp");
        try (
            var output = new JarOutputStream(Files.newOutputStream(temporary), manifest);
            var files = Files.walk(classes)
        ) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
            List<String> resources = resources(harness);
            for (String name : resources) {
                var entry = new JarEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(harness.resolve(name), output);
                output.closeEntry();
            }
            var list = new JarEntry(RESOURCE_LIST);
            list.setTime(0);
            output.putNextEntry(list);
            output.write(String.join("\n", resources).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        String sha = Fingerprints.bytes(Files.readAllBytes(target));
        Files.writeString(directory.resolve("mmt.jar.sha256"), sha + "  mmt.jar\n");
        Path zip = directory.resolve("mmt-portable.zip"), zipTemporary = directory.resolve(
            "mmt-portable.zip.tmp"
        );
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("mmt.jar", Files.readAllBytes(target));
        contents.put("mmt.jar.sha256", Files.readAllBytes(directory.resolve("mmt.jar.sha256")));
        for (String file : List.of("mmt", "mmt.cmd", "LICENSE", "NOTICE", "PERFORMANCE.md")) contents.put(
            file,
            Files.readAllBytes(harness.resolve(file))
        );
        contents.put("bootstrap-jre.ps1", Files.readAllBytes(harness.resolve("tools/bootstrap-jre.ps1")));
        List<String> desktop = desktopApps(harness, contents);
        String desktopText = desktop.isEmpty()
            ? ""
            : "\nDesktop app (Minecraft Modding Tools), for working without a command line: open " +
            String.join(" or ", desktop) +
            " in this folder.\n";
        contents.put(
            "GETTING_STARTED.txt",
            ("Minecraft Modding Tools portable beta\n\n" +
                "Windows needs no Java installed: if Java 17 or newer is missing, the launcher downloads a Java 21 runtime\n" +
                "once into %LOCALAPPDATA%\\mmt\\jre after checking its published SHA-256. macOS and Linux need Java 17 or newer\n" +
                "on PATH or JAVA_HOME; project builds may download other JDKs into the mmt cache.\n" +
                "Extract the entire ZIP, then run ./mmt (macOS/Linux) or mmt.cmd (Windows) from this folder.\n" +
                "Example: mmt.cmd doctor --root \"C:\\path to\\your mod\"\n" +
                "Use port --from VERSION --to VERSION --fix to prepare a target; use compile, test, ui-check or world-check to verify.\n" +
                "Run help for commands and support --json for current compatibility evidence. No Minecraft code or game assets are included.\n\n" +
                "Support: Include the command, its receipt path under your mod's .runs/portable folder, and the failing log.\n" +
                "Gameplay and behavior need an actual game check. Linux rendering and macOS execution remain unverified.\n" +
                "See LICENSE and NOTICE for terms and attribution.\n" +
                desktopText).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        try (var output = new ZipOutputStream(Files.newOutputStream(zipTemporary))) {
            for (var item : contents.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(item.getValue());
                output.closeEntry();
            }
        }
        // ZipOutputStream has no Unix-mode API; mark the shell launcher executable in central directory.
        byte[] archive = Files.readAllBytes(zipTemporary);
        int end = -1;
        for (int p = archive.length - 22; p >= Math.max(0, archive.length - 65557); p--) {
            if (u32(archive, p) == 0x06054b50L && p + 22 + u16(archive, p + 20) == archive.length) {
                end = p;
                break;
            }
        }
        if (end < 0) throw new IOException("Missing ZIP central directory");
        long start = u32(archive, end + 16), size = u32(archive, end + 12);
        if (start + size != end || start > Integer.MAX_VALUE) throw new IOException(
            "Unsupported ZIP central directory"
        );
        int p = (int) start, limit = end;
        boolean executable = false;
        while (p < limit) {
            if (p + 46 > limit || u32(archive, p) != 0x02014b50L) throw new IOException(
                "Invalid ZIP central directory entry"
            );
            int nameLength = u16(archive, p + 28), next =
                p + 46 + nameLength + u16(archive, p + 30) + u16(archive, p + 32);
            if (next > limit) throw new IOException("Invalid ZIP central directory entry length");
            String name = new String(archive, p + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals("mmt") || name.equals(DESKTOP_LINUX)) {
                archive[p + 5] = 3; // made by Unix
                int mode = 0100755 << 16;
                for (int i = 0; i < 4; i++) archive[p + 38 + i] = (byte) (mode >>> (i * 8));
                executable = true;
            }
            p = next;
        }
        if (!executable || p != limit) throw new IOException("Missing shell launcher ZIP entry");
        Files.write(zipTemporary, archive);
        Files.move(zipTemporary, zip, StandardCopyOption.REPLACE_EXISTING);
        String zipSha = Fingerprints.bytes(Files.readAllBytes(zip));
        Files.writeString(directory.resolve("mmt-portable.zip.sha256"), zipSha + "  mmt-portable.zip\n");
        return Json.map(
            "jar",
            target.toString(),
            "bytes",
            Files.size(target),
            "sha256",
            sha,
            "zip",
            zip.toString(),
            "zipBytes",
            Files.size(zip),
            "zipSha256",
            zipSha,
            "desktopApp",
            desktop,
            "requires",
            "Java 17 or newer; project builds may require other JDKs",
            "published",
            false
        );
    }

    static final String DESKTOP_WINDOWS = "mmt-desktop.exe", DESKTOP_LINUX = "mmt-desktop-linux";

    /**
     * The desktop app (app/, built with cargo build --release) goes into the ZIP once it is built: the Windows exe,
     * and an ELF binary as the Linux one. Without a build the package is the command line alone.
     */
    static List<String> desktopApps(Path harness, Map<String, byte[]> contents) throws Exception {
        List<String> added = new ArrayList<>();
        Path app = harness.resolve("app"), release = app.resolve("target/release");
        Path exe = release.resolve(DESKTOP_WINDOWS);
        // A built app older than its sources is not the app: rebuild it with cargo, or refuse to package it.
        for (Path binary : List.of(exe, release.resolve("mmt-desktop"))) {
            if (!Files.isRegularFile(binary) || newerSource(app, binary) == null) continue;
            Path cargo = onPath("cargo");
            if (cargo != null) {
                Process build = new ProcessBuilder(cargo.toString(), "build", "--release").directory(app.toFile()).inheritIO().start();
                if (build.waitFor() != 0) throw new IOException("cargo build --release failed in " + app + "; the desktop app was not packaged.");
            }
            Path newer = newerSource(app, binary);
            if (newer != null) throw new IOException(
                binary + " is older than " + newer + ", so it is not the app in this checkout. Run cargo build --release in " + app +
                " (cargo was " + (cargo == null ? "not found" : "run, and did not rebuild it") + ") or delete the stale build, then package again."
            );
        }
        if (Files.isRegularFile(exe)) {
            contents.put(DESKTOP_WINDOWS, Files.readAllBytes(exe));
            added.add(DESKTOP_WINDOWS);
        }
        Path unix = release.resolve("mmt-desktop");
        byte[] bytes = Files.isRegularFile(unix) ? Files.readAllBytes(unix) : new byte[0];
        boolean elf = bytes.length > 4 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F';
        if (elf) {
            contents.put(DESKTOP_LINUX, bytes);
            added.add(DESKTOP_LINUX);
        }
        return added;
    }

    /** A file of the app's sources (anything in app/ but its build output and hidden folders) changed after a built binary; null when none. */
    static Path newerSource(Path app, Path binary) throws IOException {
        if (!Files.isDirectory(app)) return null;
        var built = Files.getLastModifiedTime(binary);
        try (var walk = Files.walk(app)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path rel = app.relativize(p);
                String first = rel.getName(0).toString();
                if (first.equals("target") || first.startsWith(".")) continue;
                if (Files.getLastModifiedTime(p).compareTo(built) > 0) return p;
            }
        }
        return null;
    }

    /** An executable on PATH (with Windows' extensions), or null. */
    static Path onPath(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            for (String ext : List.of("", ".exe", ".cmd", ".bat")) {
                try {
                    Path candidate = Path.of(dir).resolve(name + ext);
                    if (!dir.isBlank() && Files.isRegularFile(candidate)) return candidate;
                } catch (java.nio.file.InvalidPathException unusable) {
                    break;
                }
            }
        }
        return null;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) & 65535L) | ((u16(bytes, offset + 2) & 65535L) << 16);
    }

    /** One self-test suite: a class in tools/tests with a main, and what it is given beside the classpath. */
    record Suite(String name, String main, boolean wantsHarness, boolean wantsScratch) {}

    static final List<Suite> SUITES = List.of(
        new Suite("port", "PortTests", true, true),
        new Suite("port-repair", "PortRepairTests", true, true),
        new Suite("convert", "ConvertTests", true, true),
        new Suite("build-script", "BuildScriptTests", true, true),
        new Suite("command", "CommandTests", true, true),
        new Suite("game", "GameTests", true, true),
        new Suite("perf", "PerfTest", true, true),
        new Suite("jdk", "JdkDownloadsTest", false, false),
        new Suite("dependency", "DependenciesTest", false, false),
        new Suite("manifest", "FabricMetadataTest", false, false),
        new Suite("hardening", "HardeningTest", false, false),
        new Suite("trust", "TrustTest", false, false),
        new Suite("mixin-check", "MixinCheckTest", true, false),
        new Suite("mod-files", "ModFileTests", true, false),
        new Suite("error-messages", "ErrorMessageTests", true, false),
        new Suite("project-safety", "ProjectSafetyTests", true, false),
        new Suite("pack-metadata", "PackMetadataTests", true, false),
        new Suite("pack-logs", "PackLogTests", true, false),
        new Suite("pack-server", "PackServerTests", true, false),
        new Suite("pack-repairs", "PackRepairTests", true, false),
        new Suite("rules", "RulesTest", true, false)
    );

    /**
     * Runs the suites (all of them, or the comma-separated names in only) and reports the failed ones first,
     * each with the end of its log, so the cause is the first thing read. A passed suite is one line.
     */
    static Map<String, Object> test(Path harness, Mmt.Report report, String only) throws Exception {
        List<String> wanted = only.isBlank() ? List.of() : Arrays.stream(only.split(",")).map(String::strip).toList();
        List<String> known = SUITES.stream().map(Suite::name).toList();
        for (String name : wanted) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException(
                    "self-test has no suite " + name + ". Suites: " + String.join(", ", known) + "."
                );
            }
        }
        List<Suite> suites = SUITES.stream().filter(s -> wanted.isEmpty() || wanted.contains(s.name())).toList();

        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17);
        Path classes = report.directory.resolve("test-classes");
        Files.createDirectories(classes);
        Path own = Path.of(Mmt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> javac = new ArrayList<>(
            List.of(
                jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-cp",
                own.toString(),
                // A suite may use a shared fixture class beside it (PackCheckFixtures); javac compiles it on demand.
                "-sourcepath",
                harness.resolve("tools/tests").toString(),
                "-d",
                classes.toString()
            )
        );
        for (Suite suite : suites) javac.add(harness.resolve("tools/tests/" + suite.main() + ".java").toString());
        var compile = Run.command(javac, report.root, Map.of(), report.log("test-compile"), 120);
        report.stage("test-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));

        List<Object> results = new ArrayList<>();
        List<String> failed = new ArrayList<>(), passedLines = new ArrayList<>(), failedText = new ArrayList<>();
        for (Suite suite : suites) {
            List<String> command = new ArrayList<>(
                List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, suite.main())
            );
            if (suite.wantsHarness()) command.add(harness.toString());
            if (suite.wantsScratch()) command.add(report.directory.resolve("fixtures").toString());
            var run = Run.command(command, report.root, Map.of(), report.log(suite.name() + "-tests"), 120);
            report.stage(suite.name() + "-tests", run, Map.of());
            boolean ok = run.exit() == 0;
            results.add(
                Json.map("name", suite.name(), "ok", ok, "seconds", run.seconds(), "log", run.log().toString())
            );
            String tail = Run.tail(run.log()).strip();
            if (ok) {
                passedLines.add("  " + suite.name() + ": " + tail.substring(tail.lastIndexOf('\n') + 1).strip());
            } else {
                failed.add(suite.name());
                failedText.add(
                    "FAILED " +
                    suite.name() +
                    " (mmt self-test --only " +
                    suite.name() +
                    " runs it alone)\n" +
                    tail +
                    "\nLog: " +
                    run.log()
                );
            }
        }
        String summary = failed.isEmpty()
            ? suites.size() + " of " + suites.size() + " suites passed"
            : failed.size() + " of " + suites.size() + " suites failed: " + String.join(", ", failed);
        String details =
            (failedText.isEmpty() ? "" : String.join("\n\n", failedText) + "\n\n") +
            summary +
            (passedLines.isEmpty() ? "" : "\nPassed:\n" + String.join("\n", passedLines));
        return Json.map("ok", failed.isEmpty(), "suites", results, "failed", failed, "details", details);
    }
}
