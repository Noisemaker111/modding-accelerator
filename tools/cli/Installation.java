import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

final class Installation {
    static final List<String> RESOURCES = List.of("tools/cli/gradle.init.gradle", "tools/cli/Json.java", "tools/cli/Relocate.java", "tools/cli/QuietOptions.java", "tools/agent/PortableAgent.java", "tools/agent/Console.java", "tools/agent/HotReload.java", "tools/runner/PureTests.java",
            "tools/VersionMap.java", "tools/MixinAudit.java", "tools/mixincheck/ModaccelMixinCheck.java", "tools/mixincheck/ModaccelMixinErrors.java", "tools/mixincheck/ModaccelLaunchRecorder.java", "tools/mixincheck/ModaccelNeoForgeTarget.java",
            "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinErrorHandler.java", "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinInfo.java", "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinConfig.java", "tools/GamePrep.java", "tools/tests/PortableTests.java", "tools/tests/JdkDownloadsTest.java", "tools/tests/DependenciesTest.java", "tools/tests/FabricMetadataTest.java", "tools/tests/HardeningTest.java", "tools/tests/MixinCheckTest.java", "knowledge/fixes.json", "knowledge/structural.json", "knowledge/port-corpus.json", "coverage/changes/index.json");
    static Path home() throws Exception {
        Path location = Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!location.toString().endsWith(".jar")) return Path.of(System.getProperty("modaccel.home", ".")).toAbsolutePath().normalize();
        Path directory = Path.of(System.getProperty("user.home"), ".modaccel/packages", Fingerprints.bytes(Files.readAllBytes(location)));
        try (var jar = new JarFile(location.toFile())) {
            for (String name : RESOURCES) {
                Path file = directory.resolve(name); byte[] bytes;
                try (var input = jar.getInputStream(jar.getJarEntry(name))) { bytes = input.readAllBytes(); }
                if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
                    Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "resource-", ".tmp");
                    Files.write(temporary, bytes); Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return directory;
    }
    static Map<String, Object> pack(Path harness, Modaccel.Report report) throws Exception {
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17); Path directory = report.root.resolve("build/distributions"); Files.createDirectories(directory);
        Path classes = report.directory.resolve("package-classes"); Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(), "--release", "17", "-encoding", "UTF-8", "-d", classes.toString()));
        try (var files = Files.list(harness.resolve("tools/cli"))) { files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("Bootstrap.java")).sorted().forEach(p -> command.add(p.toString())); }
        var compile = Run.command(command, report.root, Map.of(), report.log("package-compile"), 120); report.stage("package-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Main-Class", "Modaccel");
        Path target = directory.resolve("modaccel.jar"), temporary = directory.resolve("modaccel.jar.tmp");
        try (var output = new JarOutputStream(Files.newOutputStream(temporary), manifest); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/')); entry.setTime(0); output.putNextEntry(entry); Files.copy(file, output); output.closeEntry();
            }
            for (String name : RESOURCES) { var entry = new JarEntry(name); entry.setTime(0); output.putNextEntry(entry); Files.copy(harness.resolve(name), output); output.closeEntry(); }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); String sha = Fingerprints.bytes(Files.readAllBytes(target));
        Files.writeString(directory.resolve("modaccel.jar.sha256"), sha + "  modaccel.jar\n");
        Path zip = directory.resolve("modaccel-portable.zip"), zipTemporary = directory.resolve("modaccel-portable.zip.tmp");
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("modaccel.jar", Files.readAllBytes(target));
        contents.put("modaccel.jar.sha256", Files.readAllBytes(directory.resolve("modaccel.jar.sha256")));
        for (String file : List.of("modaccel", "modaccel.cmd", "LICENSE", "NOTICE")) contents.put(file, Files.readAllBytes(harness.resolve(file)));
        contents.put("bootstrap-jre.ps1", Files.readAllBytes(harness.resolve("tools/bootstrap-jre.ps1")));
        contents.put("GETTING_STARTED.txt", ("Modding Accelerator portable beta\n\n"
                + "Windows needs no Java installed: if Java 17 or newer is missing, the launcher downloads a Java 21 runtime\n"
                + "once into %LOCALAPPDATA%\\modaccel\\jre after checking its published SHA-256. macOS and Linux need Java 17 or newer\n"
                + "on PATH or JAVA_HOME; project builds may download other JDKs into the modaccel cache.\n"
                + "Extract the entire ZIP, then run ./modaccel (macOS/Linux) or modaccel.cmd (Windows) from this folder.\n"
                + "Example: modaccel.cmd doctor --root \"C:\\path to\\your mod\"\n"
                + "Use port --from VERSION --to VERSION --fix to prepare a target; use compile, test, ui-check or world-check to verify.\n"
                + "Run help for commands and support --json for current compatibility evidence. No Minecraft code or game assets are included.\n\n"
                + "Support: Include the command, its receipt path under your mod's .runs/portable folder, and the failing log.\n"
                + "Gameplay and behavior need an actual game check. Linux rendering and macOS execution remain unverified.\n"
                + "See LICENSE and NOTICE for terms and attribution.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var output = new ZipOutputStream(Files.newOutputStream(zipTemporary))) {
            for (var item : contents.entrySet()) {
                var entry = new ZipEntry(item.getKey()); entry.setTime(0); output.putNextEntry(entry);
                output.write(item.getValue()); output.closeEntry();
            }
        }
        // ZipOutputStream has no Unix-mode API; mark the shell launcher executable in central directory.
        byte[] archive = Files.readAllBytes(zipTemporary);
        int end = -1;
        for (int p = archive.length - 22; p >= Math.max(0, archive.length - 65557); p--) {
            if (u32(archive, p) == 0x06054b50L && p + 22 + u16(archive, p + 20) == archive.length) { end = p; break; }
        }
        if (end < 0) throw new IOException("Missing ZIP central directory");
        long start = u32(archive, end + 16), size = u32(archive, end + 12);
        if (start + size != end || start > Integer.MAX_VALUE) throw new IOException("Unsupported ZIP central directory");
        int p = (int) start, limit = end; boolean executable = false;
        while (p < limit) {
            if (p + 46 > limit || u32(archive, p) != 0x02014b50L) throw new IOException("Invalid ZIP central directory entry");
            int nameLength = u16(archive, p + 28), next = p + 46 + nameLength + u16(archive, p + 30) + u16(archive, p + 32);
            if (next > limit) throw new IOException("Invalid ZIP central directory entry length");
            String name = new String(archive, p + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals("modaccel")) {
                archive[p + 5] = 3; // made by Unix
                int mode = 0100755 << 16;
                for (int i = 0; i < 4; i++) archive[p + 38 + i] = (byte)(mode >>> (i * 8));
                executable = true;
            }
            p = next;
        }
        if (!executable || p != limit) throw new IOException("Missing shell launcher ZIP entry");
        Files.write(zipTemporary, archive);
        Files.move(zipTemporary, zip, StandardCopyOption.REPLACE_EXISTING);
        String zipSha = Fingerprints.bytes(Files.readAllBytes(zip));
        Files.writeString(directory.resolve("modaccel-portable.zip.sha256"), zipSha + "  modaccel-portable.zip\n");
        return Json.map("jar", target.toString(), "bytes", Files.size(target), "sha256", sha,
                "zip", zip.toString(), "zipBytes", Files.size(zip), "zipSha256", zipSha,
                "requires", "Java 17 or newer; project builds may require other JDKs", "published", false);
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static long u32(byte[] bytes, int offset) { return (u16(bytes, offset) & 65535L) | ((u16(bytes, offset + 2) & 65535L) << 16); }
    static Map<String, Object> test(Path harness, Modaccel.Report report) throws Exception {
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17); Path classes = report.directory.resolve("test-classes"); Files.createDirectories(classes);
        Path own = Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compile = Run.command(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(), "--release", "17", "-encoding", "UTF-8", "-cp", own.toString(), "-d", classes.toString(), harness.resolve("tools/tests/PortableTests.java").toString(), harness.resolve("tools/tests/JdkDownloadsTest.java").toString(), harness.resolve("tools/tests/DependenciesTest.java").toString(), harness.resolve("tools/tests/FabricMetadataTest.java").toString(), harness.resolve("tools/tests/HardeningTest.java").toString(), harness.resolve("tools/tests/MixinCheckTest.java").toString()),
                report.root, Map.of(), report.log("test-compile"), 120); report.stage("test-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));
        var run = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "PortableTests", harness.toString(), report.directory.resolve("fixtures").toString()),
                report.root, Map.of(), report.log("portable-tests"), 120); report.stage("portable-tests", run, Map.of());
        var jdkRun = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "JdkDownloadsTest"),
                report.root, Map.of(), report.log("jdk-tests"), 120); report.stage("jdk-tests", jdkRun, Map.of());
        var dependencyRun = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "DependenciesTest"),
                report.root, Map.of(), report.log("dependency-tests"), 120); report.stage("dependency-tests", dependencyRun, Map.of());
        var metadataRun = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "FabricMetadataTest"),
                report.root, Map.of(), report.log("manifest-tests"), 120); report.stage("manifest-tests", metadataRun, Map.of());
        var hardeningRun = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "HardeningTest"),
                report.root, Map.of(), report.log("hardening-tests"), 120); report.stage("hardening-tests", hardeningRun, Map.of());
        var mixinRun = Run.command(List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, "MixinCheckTest", harness.toString()),
                report.root, Map.of(), report.log("mixin-check-tests"), 120); report.stage("mixin-check-tests", mixinRun, Map.of());
        return Json.map("ok", run.exit() == 0 && jdkRun.exit() == 0 && dependencyRun.exit() == 0 && metadataRun.exit() == 0 && hardeningRun.exit() == 0 && mixinRun.exit() == 0,
                "details", Run.tail(run.log()) + "\n" + Run.tail(jdkRun.log()) + "\n" + Run.tail(dependencyRun.log()) + "\n" + Run.tail(metadataRun.log()) + "\n" + Run.tail(hardeningRun.log()) + "\n" + Run.tail(mixinRun.log()));
    }
}
