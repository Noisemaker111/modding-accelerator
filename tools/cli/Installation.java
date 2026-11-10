import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

final class Installation {

    static final List<String> RESOURCES = List.of(
        "tools/cli/gradle.init.gradle",
        "tools/cli/Json.java",
        "tools/cli/Relocate.java",
        "tools/cli/QuietOptions.java",
        "tools/agent/PortableAgent.java",
        "tools/agent/BackgroundWindows.java",
        "tools/agent/GameLaunchAgent.java",
        "tools/agent/Console.java",
        "tools/agent/HotReload.java",
        "tools/runner/PureTests.java",
        "tools/plugincheck/ModaccelPluginCheck.java",
        "knowledge/mockbukkit.json",
        "tools/servercheck/ModaccelServerCheck.java",
        "knowledge/mcprotocollib.json",
        "knowledge/viaproxy.json",
        "tools/packcheck/ModaccelPackProbe.java",
        "tools/packcheck/ModaccelPackOpener.java",
        "tools/packcheck/mods.toml",
        "tools/packcheck/pack.mcmeta",
        "tools/packcheck/ProbeNames.java",
        "knowledge/pack-fixes.json",
        "tools/VersionMap.java",
        "tools/MixinAudit.java",
        "tools/mixincheck/ModaccelMixinCheck.java",
        "tools/mixincheck/ModaccelMixinErrors.java",
        "tools/mixincheck/ModaccelLaunchRecorder.java",
        "tools/mixincheck/ModaccelNeoForgeTarget.java",
        "tools/datacheck/ModaccelDataCheck.java",
        "tools/datacheck/ModaccelDataProbe.java",
        "tools/datacheck/mod/SkipShapeCache.java",
        "tools/datacheck/mod/fabric.mod.json",
        "tools/datacheck/mod/modaccel_datacheck.mixins.json",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinErrorHandler.java",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinInfo.java",
        "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinConfig.java",
        "tools/GamePrep.java",
        "tools/tests/PortableTests.java",
        "tools/tests/JdkDownloadsTest.java",
        "tools/tests/DependenciesTest.java",
        "tools/tests/FabricMetadataTest.java",
        "tools/tests/HardeningTest.java",
        "tools/tests/MixinCheckTest.java",
        "tools/tests/TrustTest.java",
        "tools/tests/StrangerTest.java",
        "tools/tests/PackCheckTest.java",
        "tools/tests/RulesTest.java",
        "tools/tests/rules-without-test.txt",
        "knowledge/fixes.json",
        "knowledge/structural.json",
        "knowledge/port-corpus.json",
        "coverage/changes/index.json"
    );

    static Path home() throws Exception {
        Path location = Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!location.toString().endsWith(".jar")) return Path.of(System.getProperty("modaccel.home", "."))
            .toAbsolutePath()
            .normalize();
        Path directory = Path.of(
            System.getProperty("user.home"),
            ".modaccel/packages",
            Fingerprints.bytes(Files.readAllBytes(location))
        );
        try (var jar = new JarFile(location.toFile())) {
            for (String name : RESOURCES) {
                Path file = directory.resolve(name);
                byte[] bytes;
                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                    bytes = input.readAllBytes();
                }
                if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
                    Files.createDirectories(file.getParent());
                    Path temporary = Files.createTempFile(file.getParent(), "resource-", ".tmp");
                    Files.write(temporary, bytes);
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return directory;
    }

    static Map<String, Object> pack(Path harness, Modaccel.Report report) throws Exception {
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
                harness.resolve("tools/launcher/ModaccelLauncher.java").toString()
            ),
            report.root,
            Map.of(),
            report.log("package-launcher"),
            60
        );
        report.stage("package-launcher", launcher, Map.of());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", launcher.exit() == 0 ? "ModaccelLauncher" : "Modaccel");
        Path target = directory.resolve("modaccel.jar"), temporary = directory.resolve("modaccel.jar.tmp");
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
            for (String name : RESOURCES) {
                var entry = new JarEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(harness.resolve(name), output);
                output.closeEntry();
            }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        String sha = Fingerprints.bytes(Files.readAllBytes(target));
        Files.writeString(directory.resolve("modaccel.jar.sha256"), sha + "  modaccel.jar\n");
        Path zip = directory.resolve("modaccel-portable.zip"), zipTemporary = directory.resolve(
            "modaccel-portable.zip.tmp"
        );
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("modaccel.jar", Files.readAllBytes(target));
        contents.put("modaccel.jar.sha256", Files.readAllBytes(directory.resolve("modaccel.jar.sha256")));
        for (String file : List.of("modaccel", "modaccel.cmd", "LICENSE", "NOTICE")) contents.put(
            file,
            Files.readAllBytes(harness.resolve(file))
        );
        contents.put("bootstrap-jre.ps1", Files.readAllBytes(harness.resolve("tools/bootstrap-jre.ps1")));
        contents.put(
            "GETTING_STARTED.txt",
            ("Modding Accelerator portable beta\n\n" +
                "Windows needs no Java installed: if Java 17 or newer is missing, the launcher downloads a Java 21 runtime\n" +
                "once into %LOCALAPPDATA%\\modaccel\\jre after checking its published SHA-256. macOS and Linux need Java 17 or newer\n" +
                "on PATH or JAVA_HOME; project builds may download other JDKs into the modaccel cache.\n" +
                "Extract the entire ZIP, then run ./modaccel (macOS/Linux) or modaccel.cmd (Windows) from this folder.\n" +
                "Example: modaccel.cmd doctor --root \"C:\\path to\\your mod\"\n" +
                "Use port --from VERSION --to VERSION --fix to prepare a target; use compile, test, ui-check or world-check to verify.\n" +
                "Run help for commands and support --json for current compatibility evidence. No Minecraft code or game assets are included.\n\n" +
                "Support: Include the command, its receipt path under your mod's .runs/portable folder, and the failing log.\n" +
                "Gameplay and behavior need an actual game check. Linux rendering and macOS execution remain unverified.\n" +
                "See LICENSE and NOTICE for terms and attribution.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
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
            if (name.equals("modaccel")) {
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
        Files.writeString(directory.resolve("modaccel-portable.zip.sha256"), zipSha + "  modaccel-portable.zip\n");
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
            "requires",
            "Java 17 or newer; project builds may require other JDKs",
            "published",
            false
        );
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
        new Suite("portable", "PortableTests", true, true),
        new Suite("jdk", "JdkDownloadsTest", false, false),
        new Suite("dependency", "DependenciesTest", false, false),
        new Suite("manifest", "FabricMetadataTest", false, false),
        new Suite("hardening", "HardeningTest", false, false),
        new Suite("trust", "TrustTest", false, false),
        new Suite("mixin-check", "MixinCheckTest", true, false),
        new Suite("stranger", "StrangerTest", true, false),
        new Suite("pack-check", "PackCheckTest", true, false),
        new Suite("rules", "RulesTest", true, false)
    );

    /**
     * Runs the suites (all of them, or the comma-separated names in only) and reports the failed ones first,
     * each with the end of its log, so the cause is the first thing read. A passed suite is one line.
     */
    static Map<String, Object> test(Path harness, Modaccel.Report report, String only) throws Exception {
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
        Path own = Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> javac = new ArrayList<>(
            List.of(
                jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-cp",
                own.toString(),
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
                    " (modaccel self-test --only " +
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
