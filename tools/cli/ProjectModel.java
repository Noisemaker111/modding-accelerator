import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Cheap discovery is a hint; Gradle's evaluated model is authoritative. */
public final class ProjectModel {
    static final Set<String> IGNORED = Set.of(".git", ".gradle", ".modaccel", ".runs", ".tools", "build", "out", "node_modules", ".idea");
    final Path root;
    ProjectModel(Path root) throws IOException { this.root = root.toRealPath(); }
    Path state() { return root.resolve(".modaccel/portable"); }
    String wrapperVersion() throws IOException {
        Path props = root.resolve("gradle/wrapper/gradle-wrapper.properties");
        Matcher matcher = Pattern.compile("gradle-([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)-(?:bin|all)").matcher(read(props));
        if (!matcher.find()) throw new IOException("No Gradle wrapper distribution found in " + props);
        return matcher.group(1);
    }
    /** Explicit compile/toolchain requirement; zero means the project does not state one plainly. */
    int javaRequirement() throws IOException {
        int required = 0;
        required = Math.max(required, javaNumber(properties(root.resolve("gradle/gradle-daemon-jvm.properties")).getProperty("toolchainVersion")));
        for (Path file : inputs()) {
            String name = file.getFileName().toString();
            if (name.equals("gradle.properties")) {
                Properties props = properties(file);
                required = Math.max(required, javaNumber(props.getProperty("java_version")));
                required = Math.max(required, javaNumber(props.getProperty("javaVersion")));
            }
            if (!name.endsWith(".gradle") && !name.endsWith(".gradle.kts")) continue;
            String script = read(file).replaceAll("(?s)/\\*.*?\\*/", "");
            for (String line : script.split("\\R")) {
                line = line.replaceFirst("//.*$", "");
                required = Math.max(required, matchJava(line, "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)"));
                required = Math.max(required, matchJava(line, "JavaVersion\\.VERSION_(1_\\d+|\\d+)"));
                required = Math.max(required, matchJava(line, "JavaVersion\\.toVersion\\s*\\(\\s*(\\d+)\\s*\\)"));
                required = Math.max(required, matchJava(line, "\\b(?:sourceCompatibility|targetCompatibility|options\\.release)\\s*=\\s*['\"]?(\\d+)['\"]?"));
                required = Math.max(required, matchJava(line, "\\boptions\\.release\\.set\\s*\\(\\s*(\\d+)\\s*\\)"));
                required = Math.max(required, matchJava(line, "\\b(?:def|val|var|int)\\s+targetJavaVersion\\s*=\\s*(\\d+)\\b"));
            }
        }
        return required;
    }
    Set<Integer> javaToolchainVersions() throws IOException {
        Set<Integer> versions = new TreeSet<>();
        Pattern literal = Pattern.compile("JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)");
        Pattern property = Pattern.compile("JavaLanguageVersion\\.of\\s*\\(\\s*(?:project\\.)?(java_version|javaVersion)(?:\\s+as\\s+int)?\\s*\\)");
        for (Path file : inputs()) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".gradle") && !name.endsWith(".gradle.kts")) continue;
            Properties props = properties(root.resolve("gradle.properties"));
            props.putAll(properties(file.getParent().resolve("gradle.properties")));
            String script = read(file).replaceAll("(?s)/\\*.*?\\*/", "");
            for (String line : script.split("\\R")) {
                line = line.replaceFirst("//.*$", "");
                Matcher found = literal.matcher(line);
                while (found.find()) { int version = javaNumber(found.group(1)); if (version > 0) versions.add(version); }
                found = property.matcher(line);
                while (found.find()) { int version = javaNumber(props.getProperty(found.group(1))); if (version > 0) versions.add(version); }
            }
        }
        return versions;
    }
    private static int matchJava(String line, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(line);
        int result = 0;
        while (matcher.find()) result = Math.max(result, javaNumber(matcher.group(1)));
        return result;
    }
    private static int javaNumber(String value) {
        if (value == null || !value.trim().matches("(?:1[._])?\\d{1,2}")) return 0;
        int parsed = Integer.parseInt(value.trim().replaceFirst("^1[._]", ""));
        return parsed >= 6 ? parsed : 0;
    }
    Map<String, Object> discover() throws IOException {
        List<Object> builds = new ArrayList<>();
        for (Path file : inputs()) if (file.getFileName().toString().matches("build\\.gradle(?:\\.kts)?")) {
            Path dir = file.getParent(); String script = read(file);
            Properties pins = properties(root.resolve("gradle.properties"));
            pins.putAll(properties(dir.resolve("gradle.properties")));
            String mc = first(pins, "minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version");
            Set<String> loaders = new TreeSet<>();
            if (script.matches("(?s).*fabric-loom.*") || Files.exists(dir.resolve("src/main/resources/fabric.mod.json"))) loaders.add("fabric");
            if (script.matches("(?s).*net\\.neoforged\\.(?:moddev|gradle).*")) loaders.add("neoforge");
            if (script.matches("(?si).*(?:forgegradle|net\\.minecraftforge).*")) loaders.add("forge");
            if (script.contains("architectury")) loaders.add("architectury");
            builds.add(Json.map("directory", root.relativize(dir).toString().replace('\\', '/'), "dsl", file.toString().endsWith(".kts") ? "kotlin" : "groovy",
                    "minecraftHint", mc, "loaderHints", loaders));
        }
        return Json.map("root", root.toString(), "gradle", wrapperVersion(), "builds", builds,
                "stonecutter", Files.exists(root.resolve("stonecutter.gradle.kts")), "authoritative", false,
                "next", "Run modaccel setup to evaluate the build without changing its scripts.");
    }
    List<Path> inputs() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && (IGNORED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git"))))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                Path relative = root.relativize(path);
                String name = path.getFileName().toString();
                if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.equals("gradle.properties")
                        || name.equals("gradle-wrapper.properties") || name.equals("gradle-daemon-jvm.properties") || name.endsWith(".versions.toml")
                        || name.equals("gradle.lockfile") || name.equals("verification-metadata.xml")
                        || name.endsWith(".java") && relative.startsWith("buildSrc")) files.add(path);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder()); return files;
    }
    String configurationHash(Path init) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(root.toString().getBytes(StandardCharsets.UTF_8));
        for (Path file : inputs()) { digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8)); digest.update(Files.readAllBytes(file)); }
        digest.update(Files.readAllBytes(init));
        // Java/Gradle environment changes invalidate captured launch and classpath data.
        for (String key : List.of("JAVA_HOME", "GRADLE_USER_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS"))
            digest.update((key + "=" + System.getenv().getOrDefault(key, "")).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
    static Properties properties(Path file) throws IOException {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) try (var reader = Files.newBufferedReader(file)) { props.load(reader); }
        return props;
    }
    static String read(Path path) throws IOException { return Files.isRegularFile(path) ? Files.readString(path) : ""; }
    static String first(Properties props, String... keys) {
        for (String key : keys) if (props.containsKey(key)) return props.getProperty(key);
        return "";
    }
    static List<String> strings(Object value) {
        if (value == null) return List.of();
        return Json.array(value).stream().map(String::valueOf).toList();
    }
}
