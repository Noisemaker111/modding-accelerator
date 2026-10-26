import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * A "cannot find symbol" on a game member that NeoForge itself adds (Screen.getMinecraft() is one of
 * its patches) means the patched game jar the build compiled against is stale, not that the mod's
 * code is wrong. NeoForge's own userdev artifact ships every patch as text, so the patch decides:
 * when it adds the missing member, say the cache is stale and name the jar to rebuild.
 */
final class LoaderPatches {
    private static final Pattern MISSING = Pattern.compile("error: cannot find symbol");
    private static final Pattern SYMBOL = Pattern.compile("^\\s*symbol:\\s+(method|variable|class)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern LOCATION = Pattern.compile("^\\s*location:\\s+(?:(?:class|interface)\\s+([A-Za-z0-9_$.]+)|variable\\s+\\S+\\s+of\\s+type\\s+([A-Za-z0-9_$.]+))");

    record Missing(String kind, String member, String type) {}

    /** The stale-cache explanation, or "" when no error is a member NeoForge's patches add. */
    static String staleHint(Map<String, Object> model, List<String> lines) {
        try {
            List<Missing> missing = missing(lines);
            if (missing.isEmpty()) return "";
            for (String version : neoForgeVersions(model)) {
                Path userdev = userdev(version);
                if (userdev == null) continue;
                String hint = hint(userdev, version, missing, compileClasspath(model));
                if (!hint.isEmpty()) return hint;
            }
        } catch (IOException | RuntimeException unreadable) { }
        return "";
    }

    static String hint(Path userdev, String version, List<Missing> missing, List<Path> classpath) throws IOException {
        try (ZipFile zip = new ZipFile(userdev.toFile())) {
            for (Missing item : missing) {
                String simple = item.type().substring(item.type().lastIndexOf('.') + 1);
                String exact = item.type().contains(".") ? "patches/" + item.type().replace('.', '/') + ".java.patch" : null;
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!(exact != null ? name.equals(exact) : name.startsWith("patches/") && name.endsWith("/" + simple + ".java.patch"))) continue;
                    String patch = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    if (!adds(patch, item)) continue;
                    String className = name.substring("patches/".length(), name.length() - ".java.patch".length()).replace('/', '.');
                    Path jar = holder(classpath, className);
                    return "NeoForge " + version + " adds " + simple + "." + item.member() + (item.kind().equals("method") ? "()" : "") + " to the game (its own patch to " + className
                            + "), but the game jar this build compiled against does not have it: that jar is a stale NeoForge build in the Gradle cache, not a mistake in your code. "
                            + (jar == null ? "Rebuild NeoForge's patched game jar (delete it from the Gradle cache)" : "Delete " + jar + " so NeoForge rebuilds it")
                            + ", then compile again. Decided by " + userdev.getFileName() + ".";
                }
            }
        }
        return "";
    }

    /** Whether a patch's added lines declare the member: a method header, or a field. */
    static boolean adds(String patch, Missing item) {
        Pattern declared = item.kind().equals("method")
                ? Pattern.compile("(?m)^\\+(?!\\+)\\s*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|default|abstract|synchronized)\\s+)*[\\w<>\\[\\].,? ]+\\s+" + Pattern.quote(item.member()) + "\\s*\\(")
                : Pattern.compile("(?m)^\\+(?!\\+)\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*[\\w<>\\[\\].,? ]+\\s+" + Pattern.quote(item.member()) + "\\s*[=;]");
        return declared.matcher(patch).find();
    }

    /** javac's "cannot find symbol" blocks, as the member and the type it was looked up in. */
    static List<Missing> missing(List<String> lines) {
        List<Missing> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!MISSING.matcher(lines.get(i)).find()) continue;
            String kind = null, member = null, type = null;
            for (int j = i + 1; j < lines.size() && j <= i + 5; j++) {
                Matcher symbol = SYMBOL.matcher(lines.get(j)), location = LOCATION.matcher(lines.get(j));
                if (symbol.find()) { kind = symbol.group(1); member = symbol.group(2); }
                else if (location.find()) type = location.group(1) != null ? location.group(1) : location.group(2);
            }
            if (member != null && type != null && !kind.equals("class")) {
                Missing found = new Missing(kind, member, type);
                if (!out.contains(found)) out.add(found);
            }
        }
        return out;
    }

    static Set<String> neoForgeVersions(Map<String, Object> model) {
        Set<String> versions = new LinkedHashSet<>();
        for (Object item : model.get("projects") instanceof List<?> projects ? projects : List.of()) {
            var project = Json.object(item);
            for (Object dependency : project.get("dependencies") instanceof List<?> deps ? deps : List.of()) {
                var d = Json.object(dependency);
                if ("net.neoforged".equals(d.get("group")) && "neoforge".equals(d.get("name")) && d.get("version") instanceof String v && !v.isBlank()) versions.add(v);
            }
        }
        return versions;
    }

    static Path userdev(String version) throws IOException {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        Path base = (gradleHome == null || gradleHome.isBlank() ? Path.of(System.getProperty("user.home"), ".gradle") : Path.of(gradleHome))
                .resolve("caches/modules-2/files-2.1/net.neoforged/neoforge").resolve(version);
        if (!Files.isDirectory(base)) return null;
        try (var walk = Files.walk(base, 2)) {
            return walk.filter(p -> p.getFileName().toString().equals("neoforge-" + version + "-userdev.jar")).findFirst().orElse(null);
        }
    }

    static List<Path> compileClasspath(Map<String, Object> model) {
        List<Path> out = new ArrayList<>();
        for (Object item : model.get("projects") instanceof List<?> projects ? projects : List.of())
            for (Object compile : Json.object(item).get("compile") instanceof List<?> list ? list : List.of())
                for (String entry : ProjectModel.strings(Json.object(compile).get("classpath"))) if (entry.endsWith(".jar")) out.add(Path.of(entry));
        return out;
    }

    /** The classpath jar that holds the class: the stale one. */
    static Path holder(List<Path> classpath, String className) {
        String entry = className.replace('.', '/') + ".class";
        for (Path jar : classpath) {
            if (!Files.isRegularFile(jar)) continue;
            try (ZipFile zip = new ZipFile(jar.toFile())) { if (zip.getEntry(entry) != null) return jar; } catch (IOException unreadable) { }
        }
        return null;
    }
}
