import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Select an installed JDK that can run the project's pinned Gradle wrapper. */
final class Runtimes {
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    record Jdk(Path home, int version) {
        Path java() { return home.resolve("bin/" + (WINDOWS ? "java.exe" : "java")); }
    }
    static List<Jdk> installed(Path harness) throws IOException {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(Path.of(System.getProperty("java.home")));
        for (String key : List.of("JAVA_HOME", "JDK_HOME", "JAVA8_HOME", "JAVA11_HOME", "JAVA17_HOME", "JAVA21_HOME", "JAVA25_HOME")) {
            String value = System.getenv(key); if (value != null && !value.isBlank()) paths.add(Path.of(value));
        }
        Path user = Path.of(System.getProperty("user.home"));
        for (Path parent : List.of(harness.resolve(".modaccel/jdks"), harness.resolve(".tools"), user.resolve(".modaccel/jdks"), user.resolve(".gradle/jdks"),
                user.resolve(".jdks"), user.resolve("tools"), Path.of("/usr/lib/jvm"), Path.of("/Library/Java/JavaVirtualMachines"))) {
            if (Files.isDirectory(parent)) try (var children = Files.list(parent)) {
                for (Path child : children.filter(Files::isDirectory).toList()) { paths.add(child); paths.add(child.resolve("Contents/Home")); }
            }
        }
        if (WINDOWS) for (String vendor : List.of("Eclipse Adoptium", "Java", "Microsoft", "JetBrains")) {
            Path parent = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), vendor);
            if (Files.isDirectory(parent)) try (var children = Files.list(parent)) { paths.addAll(children.filter(Files::isDirectory).toList()); }
        }
        List<Jdk> result = new ArrayList<>(); Set<Path> seen = new HashSet<>();
        for (Path path : paths) if (Files.isRegularFile(path.resolve("bin/" + (WINDOWS ? "javac.exe" : "javac")))) {
            path = path.toRealPath(); if (!seen.add(path)) continue;
            String release = ProjectModel.properties(path.resolve("release")).getProperty("JAVA_VERSION", "").replace("\"", "");
            try { int major = Integer.parseInt(release.split("[._+\\-]")[release.startsWith("1.") ? 1 : 0]); result.add(new Jdk(path, major)); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }
    static Jdk forGradle(Path harness, String version) throws IOException {
        String[] parts = version.split("\\."); int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
        int min = major >= 9 ? 17 : 8;
        int max = major >= 9 && minor >= 1 || major >= 10 ? 25 : major >= 9 ? 24
                : major == 8 && minor >= 5 ? 21 : major == 8 || major == 7 && minor >= 3 ? 17
                : major == 7 ? 16 : major == 6 && minor >= 3 ? 14 : major >= 5 ? 11 : 8;
        // LTS runtimes avoid accidentally using a newer JDK unsupported by an old wrapper.
        List<Jdk> candidates = installed(harness).stream().filter(j -> j.version >= min && j.version <= max).toList();
        for (int preferred : List.of(25, 21, 17, 11, 8)) for (Jdk jdk : candidates) if (jdk.version == preferred) return jdk;
        if (!candidates.isEmpty()) return candidates.get(0);
        throw new IOException("Gradle " + version + " needs an installed JDK " + min + "–" + max + "; set JAVA_HOME to a compatible JDK, then rerun setup.");
    }
    static Jdk atLeast(Path harness, int version) throws IOException {
        return installed(harness).stream().filter(j -> j.version >= version).min(Comparator.comparingInt(Jdk::version))
                .orElseThrow(() -> new IOException("This operation needs JDK " + version + " or newer; set JAVA" + version + "_HOME."));
    }
    static Jdk ensureForGradle(Path harness, String wrapper) throws IOException {
        try { return forGradle(harness, wrapper); }
        catch (IOException missing) {
            String[] parts = wrapper.split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            int min = major >= 9 ? 17 : 8;
            int max = major >= 9 && minor >= 1 || major >= 10 ? 25 : major >= 9 ? 24
                    : major == 8 && minor >= 5 ? 21 : major == 8 || major == 7 && minor >= 3 ? 17
                    : major == 7 ? 16 : major == 6 && minor >= 3 ? 14 : major >= 5 ? 11 : 8;
            for (int candidate : List.of(17, 11, 8, 21, 25)) if (candidate >= min && candidate <= max) {
                JdkDownloads.install(harness, candidate);
                return forGradle(harness, wrapper);
            }
            throw missing;
        }
    }
    static Jdk ensureAtLeast(Path harness, int minimum) throws IOException {
        try { return atLeast(harness, minimum); }
        catch (IOException missing) {
            int candidate = minimum <= 8 ? 8 : minimum <= 11 ? 11 : minimum <= 17 ? 17 : minimum <= 21 ? 21 : minimum <= 25 ? 25 : minimum;
            JdkDownloads.install(harness, candidate);
            return atLeast(harness, minimum);
        }
    }
    static Jdk ensureExact(Path harness, int version) throws IOException {
        for (Jdk jdk : installed(harness)) if (jdk.version == version) return jdk;
        JdkDownloads.install(harness, version);
        for (Jdk jdk : installed(harness)) if (jdk.version == version) return jdk;
        throw new IOException("Downloaded JDK " + version + " was not detected in the project cache");
    }
}
