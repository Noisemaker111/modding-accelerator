import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Verified, project-local Temurin provisioning. No system installation or PATH edits. */
final class JdkDownloads {
    private static final long MAX_ARCHIVE = 800L * 1024 * 1024;
    private static final long MAX_EXPANDED = 2L * 1024 * 1024 * 1024;
    static Path provision(Path harness, int feature) throws IOException { return install(harness, feature); }
    static Path install(Path harness, int feature) throws IOException {
        Path cache = harness.toAbsolutePath().resolve(".modaccel/jdks");
        Files.createDirectories(cache);
        String platform = platform();
        Path target = cache.resolve("temurin-" + feature + "-" + platform.replace('/', '-'));
        Path lockPath = cache.resolve("temurin-" + feature + "-" + platform.replace('/', '-') + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (valid(target, feature)) return target;
            if (Files.exists(target)) throw new IOException("Invalid JDK cache at " + target + "; remove that directory and retry");
            Asset asset = metadata(feature, platform);
            Path archive = Files.createTempFile(cache, "jdk-", asset.zip ? ".zip" : ".tar.gz");
            Path staging = Files.createTempDirectory(cache, "jdk-extract-");
            try {
                download(asset.url, archive);
                verifyHash(archive, asset.sha256);
                extract(archive, staging, asset.zip);
                Path home = findHome(staging);
                if (!valid(home, feature)) throw new IOException("Downloaded JDK failed java/javac validation");
                Files.move(home, target, StandardCopyOption.ATOMIC_MOVE);
                Json.save(cache.resolve(target.getFileName() + ".json"), Json.map("feature", feature,
                        "platform", platform, "url", asset.url.toString(), "sha256", asset.sha256,
                        "home", target.toString()));
                return target;
            } finally {
                Files.deleteIfExists(archive);
                deleteTree(staging);
            }
        }
    }
    private record Asset(URI url, String sha256, boolean zip) { }
    private static String platform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String system = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "mac" : os.contains("linux") ? "linux" : null;
        String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
        if (system == null || cpu == null) throw new IOException("No Temurin JDK provisioning for " + os + "/" + arch);
        return system + "/" + cpu;
    }
    private static Asset metadata(int feature, String platform) throws IOException {
        String[] parts = platform.split("/");
        URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + feature + "/hotspot?architecture=" + parts[1] + "&image_type=jdk&os=" + parts[0] + "&vendor=eclipse");
        byte[] body = readUrl(uri, 4 * 1024 * 1024);
        try {
            for (Object entry : Json.array(Json.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8)))) {
                Map<String,Object> root = Json.object(entry);
                Map<String,Object> binary = Json.object(root.get("binary"));
                Map<String,Object> pkg = Json.object(binary.get("package"));
                String url = Json.text(pkg, "link", "");
                String hash = Json.text(pkg, "checksum", "");
                if (!hash.matches("[0-9a-fA-F]{64}")) continue;
                URI download = URI.create(url);
                if (!download.getScheme().equals("https") || !download.getHost().endsWith("adoptium.net") && !download.getHost().equals("github.com")) continue;
                if (!url.endsWith(".zip") && !url.endsWith(".tar.gz")) continue;
                return new Asset(download, hash, url.endsWith(".zip"));
            }
        } catch (RuntimeException malformed) { throw new IOException("Invalid Adoptium JDK metadata", malformed); }
        throw new IOException("No published Temurin JDK " + feature + " for " + platform);
    }
    private static byte[] readUrl(URI uri, int limit) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(30000);
        try (InputStream input = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("JDK metadata too large"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    private static void download(URI uri, Path to) throws IOException {
        URLConnection conn = uri.toURL().openConnection(); conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
        try (InputStream input = conn.getInputStream(); OutputStream out = Files.newOutputStream(to, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[65536]; long size = 0; int n;
            while ((n = input.read(buffer)) != -1) { size += n; if (size > MAX_ARCHIVE) throw new IOException("JDK archive exceeds size limit"); out.write(buffer, 0, n); }
        }
    }
    static void verifyHash(Path archive, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) { byte[] buffer = new byte[65536]; int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) throw new IOException("JDK archive SHA-256 mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    static void extract(Path archive, Path into, boolean zip) throws IOException {
        try (InputStream file = Files.newInputStream(archive); InputStream compressed = zip ? new ZipInputStream(file) : new GZIPInputStream(file)) {
            if (zip) extractZip((ZipInputStream)compressed, into); else extractTar(compressed, into);
        }
    }
    private static Path safe(Path root, String name) throws IOException {
        Path normalized = root.resolve(name.replace('\\', '/')).normalize();
        if (name.startsWith("/") || name.contains(":") || !normalized.startsWith(root)) throw new IOException("Unsafe JDK archive path: " + name);
        return normalized;
    }
    private static void extractZip(ZipInputStream zip, Path root) throws IOException {
        ZipEntry entry; long total = 0; int count = 0;
        while ((entry = zip.getNextEntry()) != null) {
            count++;
            Path path = safe(root, entry.getName());
            if (entry.isDirectory()) Files.createDirectories(path);
            else { Files.createDirectories(path.getParent()); try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { total += copyBounded(zip, out, MAX_EXPANDED - total); } }
            zip.closeEntry();
        }
        if (count == 0) throw new IOException("Empty or corrupt JDK zip archive");
    }
    private static void extractTar(InputStream input, Path root) throws IOException {
        byte[] header = new byte[512]; long total = 0;
        List<Path[]> links = new ArrayList<>();
        while (true) {
            readFully(input, header); if (allZero(header)) break;
            String name = field(header, 0, 100), prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size;
            try { size = Long.parseLong(field(header, 124, 12).trim(), 8); } catch (NumberFormatException bad) { throw new IOException("Invalid tar size", bad); }
            if (size < 0 || size > MAX_EXPANDED - total) throw new IOException("JDK archive exceeds expanded size limit");
            Path path = safe(root, name); int type = header[156];
            if (type == '5') Files.createDirectories(path);
            else if (type == 0 || type == '0') {
                Files.createDirectories(path.getParent());
                try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { copyExact(input, out, size); }
                if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                    int mode;
                    try { mode = Integer.parseInt(field(header, 100, 8).trim(), 8); }
                    catch (NumberFormatException bad) { throw new IOException("Invalid tar permissions", bad); }
                    Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                    PosixFilePermission[] bits = { PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                            PosixFilePermission.OTHERS_EXECUTE };
                    for (int i = 0; i < bits.length; i++) if ((mode & (0400 >> i)) != 0) permissions.add(bits[i]);
                    Files.setPosixFilePermissions(path, permissions);
                }
            } else if (type == '2' || type == '1') {
                String link = field(header, 157, 100);
                if (link.startsWith("/") || link.contains(":")) throw new IOException("Unsafe JDK archive link: " + name);
                Path destination = (type == '2' ? path.getParent() : root).resolve(link).normalize();
                if (!destination.startsWith(root)) throw new IOException("Unsafe JDK archive link: " + name);
                links.add(new Path[] { path, destination, Path.of(type == '2' ? "symbolic" : "hard") });
            }
            else input.skipNBytes(size);
            total += size; long padding = (512 - size % 512) % 512; input.skipNBytes(padding);
        }
        for (Path[] link : links) {
            if (!Files.exists(link[1])) throw new IOException("JDK archive link target missing: " + link[1]);
            Files.createDirectories(link[0].getParent());
            if (link[2].toString().equals("symbolic")) Files.createSymbolicLink(link[0], link[0].getParent().relativize(link[1]));
            else Files.createLink(link[0], link[1]);
        }
    }
    private static String field(byte[] bytes, int start, int length) { int end = start; while (end < start + length && bytes[end] != 0) end++; return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.UTF_8); }
    private static boolean allZero(byte[] bytes) { for (byte b : bytes) if (b != 0) return false; return true; }
    private static void readFully(InputStream in, byte[] b) throws IOException { int at = 0, n; while (at < b.length) { n = in.read(b, at, b.length - at); if (n < 0) throw new EOFException("Truncated JDK archive"); at += n; } }
    private static long copyBounded(InputStream in, OutputStream out, long max) throws IOException { byte[] b = new byte[65536]; long count = 0; int n; while ((n = in.read(b)) != -1) { count += n; if (count > max) throw new IOException("JDK archive exceeds expanded size limit"); out.write(b, 0, n); } return count; }
    private static void copyExact(InputStream in, OutputStream out, long size) throws IOException { byte[] b = new byte[65536]; while (size > 0) { int n = in.read(b, 0, (int)Math.min(size, b.length)); if (n < 0) throw new EOFException("Truncated JDK archive"); out.write(b, 0, n); size -= n; } }
    private static Path findHome(Path root) throws IOException {
        try (var paths = Files.walk(root, 4)) {
            return paths.filter(path -> Files.isRegularFile(path.resolve("release")) && Files.isDirectory(path.resolve("bin")))
                    .findFirst().orElseThrow(() -> new IOException("JDK archive has no home directory"));
        }
    }
    private static boolean valid(Path home, int feature) throws IOException {
        Path javaPath = home.resolve("bin/" + (Runtimes.WINDOWS ? "java.exe" : "java"));
        Path javac = home.resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac"));
        if (!Files.isRegularFile(javaPath) || !Files.isRegularFile(javac)) return false;
        Path spawnHelper = home.resolve("lib/jspawnhelper");
        if (Files.exists(spawnHelper) && Files.getFileAttributeView(spawnHelper, PosixFileAttributeView.class) != null && !Files.isExecutable(spawnHelper)) return false;
        String version = ProjectModel.properties(home.resolve("release")).getProperty("JAVA_VERSION", "").replace("\"", "");
        if (!version.startsWith(feature == 8 ? "1.8." : feature + ".")) return false;
        try {
            for (Path executable : List.of(javaPath, javac)) {
                Process process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (process.exitValue() != 0 || !output.contains(version.split("\\+")[0])) return false;
            }
            return true;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted validating JDK", interrupted); }
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
