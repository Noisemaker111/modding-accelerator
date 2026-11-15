import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Finds, at repair time, the jar a general repair needs (knowledge/pack-fixes.json actions replace-dependency and
 * add-newer-bundled): another release of a mod the pack already has, for the pack's loader and game version. The
 * project is found on Modrinth by the SHA-1 of a jar the pack carries for that mod id (a bundled copy included), or by
 * the mod id as the project's slug; each candidate is checked against the SHA-512 Modrinth publishes and must, by its
 * own metadata, provide the same mod id in a version the caller accepts. Nothing is chosen by name.
 */
final class PackResolve {

    private PackResolve() {}

    interface Accept {
        boolean version(String version);
    }

    /** A release of the mod id the pack's loader can use, newest first among those accepted; null when none is found. */
    static Path release(PackCheck check, String id, List<String> metadata, String loader, String minecraft, List<Path> jars, Accept accept) throws Exception {
        String project = project(id, metadata, jars);
        if (project == null) return null;
        String query = PackCheck.MODRINTH + "project/" + project + "/version?loaders=" + encode("[\"" + loader + "\"]") + "&game_versions=" + encode("[\"" + minecraft + "\"]");
        Object versions = Json.parse(new String(PackCheck.get(query), StandardCharsets.UTF_8));
        int tried = 0;
        for (Object v : Json.array(versions)) {
            if (tried++ == 8) break;
            for (Object f : Json.array(Json.object(v).get("files"))) {
                var file = Json.object(f);
                if (!Boolean.TRUE.equals(file.get("primary")) && Json.array(Json.object(v).get("files")).size() > 1) continue;
                String sha512 = Json.text(Json.object(file.get("hashes")), "sha512", ""), name = Json.text(file, "filename", "");
                if (name.isBlank() || name.contains("/") || name.contains("\\") || name.startsWith(".")) continue;
                Path jar = check.cache.resolve("fixes").resolve(name);
                if (!Files.isRegularFile(jar) || !PackCheck.hash("SHA-512", Files.readAllBytes(jar)).equals(sha512)) {
                    byte[] bytes = PackCheck.get(Json.text(file, "url", ""));
                    if (!PackCheck.hash("SHA-512", bytes).equals(sha512)) continue;
                    Files.createDirectories(jar.getParent());
                    Files.write(jar, bytes);
                }
                String version = provided(jar, id, metadata);
                if (version != null && accept.version(version)) return jar;
            }
        }
        return null;
    }

    /** The Modrinth project of a mod id: by the SHA-1 of a jar in the pack that provides it, else by the id as slug. */
    static String project(String id, List<String> metadata, List<Path> jars) throws Exception {
        for (Path jar : jars) for (byte[] bytes : providing(jar, id, metadata)) {
            byte[] answer;
            try {
                answer = PackCheck.get(PackCheck.MODRINTH + "version_file/" + PackCheck.hash("SHA-1", bytes) + "?algorithm=sha1");
            } catch (IOException unknown) {
                continue;
            }
            if (Json.parse(new String(answer, StandardCharsets.UTF_8)) instanceof Map<?, ?> version && version.get("project_id") != null) return version.get("project_id").toString();
        }
        try {
            if (Json.parse(new String(PackCheck.get(PackCheck.MODRINTH + "project/" + encode(id)), StandardCharsets.UTF_8)) instanceof Map<?, ?> found && found.get("id") != null) return found.get("id").toString();
        } catch (IOException none) {
            // no project with that slug
        }
        return null;
    }

    /** The bytes of each jar (the file itself, or a jar bundled in it) whose own metadata provides the id. */
    static List<byte[]> providing(Path jar, String id, List<String> metadata) throws IOException {
        List<byte[]> out = new ArrayList<>();
        byte[] bytes = Files.readAllBytes(jar);
        collect(bytes, id, metadata, out, 0);
        return out;
    }

    static void collect(byte[] bytes, String id, List<String> metadata, List<byte[]> out, int depth) throws IOException {
        if (depth > 2) return;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            boolean provides = false;
            List<byte[]> nested = new ArrayList<>();
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (metadata.contains(name)) provides |= ids(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).contains(id);
                else if (name.endsWith(".jar")) nested.add(zip.readAllBytes());
            }
            if (provides) out.add(bytes);
            for (byte[] inner : nested) collect(inner, id, metadata, out, depth + 1);
        }
    }

    /** The version a jar's own metadata gives the id, or null when it does not provide it. */
    static String provided(Path jar, String id, List<String> metadata) {
        var scan = PackMods.scan(List.of(new PackCheck.Mod(jar.getFileName().toString(), jar, "unknown", "repair")), metadata);
        for (var provider : scan.providers.getOrDefault(id, List.of())) if (!provider.bundled()) return provider.version();
        return null;
    }

    static Set<String> ids(String metadata) {
        Set<String> ids = new HashSet<>();
        var toml = java.util.regex.Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(metadata);
        while (toml.find()) ids.add(toml.group(1));
        var json = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        if (json.find()) ids.add(json.group(1));
        return ids;
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
