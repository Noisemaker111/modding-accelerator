import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Run after package: PackageAcceptanceTest ZIP ZIP.sha256 scratch-directory. */
public final class PackageAcceptanceTest {
    static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); System.out.println("PASS " + message); }
    public static void main(String[] args) throws Exception {
        Path zip = Path.of(args[0]), sha = Path.of(args[1]), extracted = Path.of(args[2]).toAbsolutePath().resolve("portable package with spaces");
        String digest = sha256(zip);
        check(Files.readString(sha).equals(digest + "  modaccel-portable.zip\n"), "ZIP SHA-256 matches distribution checksum");
        Set<String> expected = Set.of("modaccel.jar", "modaccel.jar.sha256", "modaccel", "modaccel.cmd", "LICENSE", "NOTICE", "GETTING_STARTED.txt", "bootstrap-jre.ps1");
        Files.createDirectories(extracted);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Set<String> actual = new HashSet<>();
            for (var entries = archive.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement(); actual.add(entry.getName());
                if (!expected.contains(entry.getName())) throw new AssertionError("unexpected package entry: " + entry.getName());
                try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, extracted.resolve(entry.getName()), StandardCopyOption.REPLACE_EXISTING); }
            }
            check(actual.equals(expected), "ZIP has complete self-contained file set");
        }
        String jarDigest = sha256(extracted.resolve("modaccel.jar"));
        check(Files.readString(extracted.resolve("modaccel.jar.sha256")).equals(jarDigest + "  modaccel.jar\n"), "extracted JAR matches bundled checksum");
        byte[] bytes = Files.readAllBytes(zip); int end = -1;
        for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--)
            if (u32(bytes, p) == 0x06054b50L && p + 22 + u16(bytes, p + 20) == bytes.length) { end = p; break; }
        check(end >= 0, "ZIP has valid end record");
        int pos = (int) u32(bytes, end + 16), limit = end; boolean shellExecutable = false;
        while (pos < limit) {
            check(u32(bytes, pos) == 0x02014b50L, "ZIP central entry is valid");
            int length = u16(bytes, pos + 28), next = pos + 46 + length + u16(bytes, pos + 30) + u16(bytes, pos + 32);
            String name = new String(bytes, pos + 46, length, StandardCharsets.UTF_8);
            if (name.equals("modaccel")) shellExecutable = bytes[pos + 5] == 3 && (u32(bytes, pos + 38) >>> 16 & 0111) == 0111;
            pos = next;
        }
        check(shellExecutable, "POSIX launcher is marked executable in ZIP");
        Path outside = Path.of(args[2]).toAbsolutePath();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Path launcher = extracted.resolve("modaccel.cmd");
            String javaHome = System.getProperty("java.home");
            Outcome help = run(List.of(launcher.toString(), "help"), extracted, Map.of("JAVA_HOME", javaHome));
            check(help.exit == 0 && help.output.contains("modaccel <"), "extracted Windows launcher starts JAR from path with spaces");
            Outcome support = run(List.of(launcher.toString(), "support", "--json"), outside, Map.of("JAVA_HOME", javaHome));
            check(support.exit == 0 && support.output.contains("\"testedExamples\""), "packaged support runs outside a project");
            Outcome badRoot = run(List.of(launcher.toString(), "discover", "--root", extracted.resolve("modaccel.jar").toString()), outside, Map.of("JAVA_HOME", javaHome));
            check(badRoot.exit == 2 && badRoot.output.contains("Gradle source directory"), "compiled JAR input gives source checkout guidance before Gradle");
            Outcome legacy = run(List.of(launcher.toString(), "ui"), extracted, Map.of("JAVA_HOME", javaHome));
            check(legacy.exit == 2 && legacy.output.contains("ui-check"), "missing legacy verb explains portable equivalent");
            ProcessBuilder missing = new ProcessBuilder(launcher.toString(), "help");
            missing.directory(extracted.toFile()).redirectErrorStream(true);
            missing.environment().remove("JAVA_HOME");
            missing.environment().put("PATH", System.getenv("SystemRoot") + "\\System32");
            missing.environment().put("MODACCEL_NO_DOWNLOAD", "1");
            Process process = missing.start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor() == 2 && output.contains("requires Java 17 or newer"), "missing Java explains prerequisite");
        } else {
            Path launcher = extracted.resolve("modaccel");
            Files.setPosixFilePermissions(launcher, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
            String javaHome = System.getProperty("java.home");
            Outcome help = run(List.of(launcher.toString(), "help"), outside, Map.of("JAVA_HOME", javaHome));
            check(help.exit == 0 && help.output.contains("modaccel <"), "extracted POSIX launcher starts JAR from path with spaces");
            Outcome support = run(List.of(launcher.toString(), "support", "--json"), outside, Map.of("JAVA_HOME", javaHome));
            check(support.exit == 0 && support.output.contains("\"testedExamples\""), "packaged support runs outside a project");
            Outcome badRoot = run(List.of(launcher.toString(), "discover", "--root", extracted.resolve("modaccel.jar").toString()), outside, Map.of("JAVA_HOME", javaHome));
            check(badRoot.exit == 2 && badRoot.output.contains("Gradle source directory"), "compiled JAR input gives source checkout guidance before Gradle");
            Outcome legacy = run(List.of(launcher.toString(), "ui"), outside, Map.of("JAVA_HOME", javaHome));
            check(legacy.exit == 2 && legacy.output.contains("ui-check"), "missing POSIX legacy verb explains portable equivalent");
            Path bin = outside.resolve("no-java-bin"); Files.createDirectories(bin);
            Path dirname = bin.resolve("dirname");
            if (!Files.exists(dirname)) Files.createSymbolicLink(dirname, Path.of("/usr/bin/dirname"));
            Path emptyHome = outside.resolve("no-java-home"); Files.createDirectories(emptyHome);
            ProcessBuilder missing = new ProcessBuilder(launcher.toString(), "help");
            missing.directory(outside.toFile()).redirectErrorStream(true);
            missing.environment().remove("JAVA_HOME"); missing.environment().put("PATH", bin.toString());
            missing.environment().put("HOME", emptyHome.toString());
            Process process = missing.start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor() == 2 && output.contains("requires Java 17 or newer"), "missing Java explains prerequisite");
        }
    }
    record Outcome(int exit, String output) { }
    private static Outcome run(List<String> command, Path directory, Map<String,String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command); builder.directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment); Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Outcome(process.waitFor(), output);
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static long u32(byte[] b, int p) { return u16(b, p) | ((long)u16(b, p + 2) << 16); }
    private static String sha256(Path file) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(hash);
    }
}
