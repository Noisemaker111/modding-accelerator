import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class JdkDownloadsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("modaccel-jdk-test-");
        Path archive = root.resolve("test.zip");
        try {
            Files.writeString(archive, "interrupted download");
            try { JdkDownloads.verifyHash(archive, "0".repeat(64)); throw new AssertionError("checksum accepted"); }
            catch (IOException expected) { if (!expected.getMessage().contains("SHA-256")) throw expected; }
            try { JdkDownloads.extract(archive, root.resolve("broken"), true); throw new AssertionError("truncated archive accepted"); }
            catch (IOException expected) { }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("../escape")); zip.write(1); zip.closeEntry();
            }
            try { JdkDownloads.extract(archive, root.resolve("out"), true); throw new AssertionError("path escape accepted"); }
            catch (IOException expected) { if (!expected.getMessage().contains("Unsafe")) throw expected; }
            if (Files.exists(root.resolve("escape"))) throw new AssertionError("path escaped root");
            Path project = root.resolve("project"); Files.createDirectories(project.resolve("gradle"));
            ProjectModel model = new ProjectModel(project);
            if (model.javaRequirement() != 0) throw new AssertionError("unknown Java requirement must be zero");
            Files.writeString(project.resolve("gradle.properties"), "java_version=21\n");
            Files.writeString(project.resolve("build.gradle"), "// JavaVersion.VERSION_99\nsourceCompatibility = JavaVersion.VERSION_25\n");
            if (model.javaRequirement() != 25) throw new AssertionError("explicit script requirement missed");
            Files.writeString(project.resolve("build.gradle"), "sourceCompatibility = '17'\n");
            if (model.javaRequirement() != 21) throw new AssertionError("property requirement missed");
            Files.writeString(project.resolve("build.gradle"), "java { toolchain { languageVersion = JavaLanguageVersion.of(8) } }\n");
            if (!model.javaToolchainVersions().equals(java.util.Set.of(8))) throw new AssertionError("exact toolchain missed");
            Files.writeString(project.resolve("gradle/gradle-daemon-jvm.properties"), "toolchainVersion=25\n");
            if (model.javaRequirement() != 25) throw new AssertionError("daemon requirement missed");
            Path tar = root.resolve("test.tar.gz");
            tar(tar, "jdk/bin/java", (byte)'0', "java");
            JdkDownloads.extract(tar, root.resolve("tar-out"), false);
            if (!Files.readString(root.resolve("tar-out/jdk/bin/java")).equals("java")) throw new AssertionError("tar extraction failed");
            tar(tar, "jdk/lib/jspawnhelper", (byte)'0', "helper", 0755);
            JdkDownloads.extract(tar, root.resolve("tar-helper"), false);
            if (Files.getFileStore(root).supportsFileAttributeView("posix") && !Files.isExecutable(root.resolve("tar-helper/jdk/lib/jspawnhelper")))
                throw new AssertionError("TAR executable permissions lost for JDK process helper");
            tar(tar, "jdk/lib/config", (byte)'0', "config", 0644);
            JdkDownloads.extract(tar, root.resolve("tar-config"), false);
            if (Files.getFileStore(root).supportsFileAttributeView("posix") && Files.isExecutable(root.resolve("tar-config/jdk/lib/config")))
                throw new AssertionError("TAR non-executable file became executable");
            tar(tar, "../escape", (byte)'0', "bad");
            try { JdkDownloads.extract(tar, root.resolve("tar-escape"), false); throw new AssertionError("tar escape accepted"); }
            catch (IOException expected) { if (!expected.getMessage().contains("Unsafe")) throw expected; }
            System.out.println("checksum, corruption, ZIP/TAR extraction, path escape, Java requirement: PASS");
            if (args.length == 2) System.out.println("provisioned: " + JdkDownloads.provision(Path.of(args[0]), Integer.parseInt(args[1])));
        } finally {
            try (var files = Files.walk(root)) { for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static void tar(Path destination, String name, byte type, String content) throws IOException {
        tar(destination, name, type, content, 0755);
    }
    private static void tar(Path destination, String name, byte type, String content, int mode) throws IOException {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        byte[] filename = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(filename, 0, header, 0, filename.length);
        byte[] permissions = String.format("%07o", mode).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(permissions, 0, header, 100, permissions.length);
        byte[] size = String.format("%011o", data.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length); header[156] = type;
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(destination))) {
            gzip.write(header); gzip.write(data); gzip.write(new byte[(512 - data.length % 512) % 512]); gzip.write(new byte[1024]);
        }
    }
}
