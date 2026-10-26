import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Whether the game really loaded this project's mods. The expected list comes from the metadata
 * Gradle processed into the build output the client runs from (fabric.mod.json, neoforge.mods.toml
 * or mods.toml); the answer comes from the running loader's own mod list, through the game agent.
 * A rendered frame proves neither: NeoForge shows its title screen with "0 mods" after refusing one.
 */
final class LoadedMods {
    private LoadedMods() { }
    /** One mod a metadata file declares. kind: fabric (fabric.mod.json), neoforge (neoforge.mods.toml), forge (mods.toml). */
    record Declared(String kind, String id, String version, Path file) { }

    /** The project that owns a client task: ":runClient" is ":", ":fabric:runClient" is ":fabric". */
    static String owner(String task) {
        int at = task.lastIndexOf(':');
        return at <= 0 ? ":" : task.substring(0, at);
    }
    /**
     * The build-output folders the client loads this project's mods from: the main and client
     * source sets' runtime entries that are folders inside the build root. Test and gametest source
     * sets are left out, since the client run does not load their mods.
     */
    static List<Path> outputs(Map<String, Object> model, String projectPath) {
        Set<Path> found = new LinkedHashSet<>();
        Path root = Path.of(Json.text(model, "root", ".")).toAbsolutePath().normalize();
        for (Object item : Json.array(model.get("projects"))) {
            Map<String, Object> project = Json.object(item);
            if (!projectPath.equals(Json.text(project, "path", ""))) continue;
            for (Object set : Json.array(project.get("sourceSets"))) {
                Map<String, Object> sourceSet = Json.object(set);
                if (!List.of("main", "client").contains(Json.text(sourceSet, "name", ""))) continue;
                for (String entry : ProjectModel.strings(sourceSet.get("runtime"))) {
                    Path path = Path.of(entry).toAbsolutePath().normalize();
                    if (path.startsWith(root) && !path.toString().endsWith(".jar")) found.add(path);
                }
            }
        }
        return new ArrayList<>(found);
    }
    /** Every mod the processed metadata in these folders declares. */
    static List<Declared> declared(List<Path> outputs) throws IOException {
        List<Declared> mods = new ArrayList<>();
        for (Path dir : outputs) {
            Path fabric = dir.resolve("fabric.mod.json");
            if (Files.isRegularFile(fabric)) {
                Map<String, Object> metadata;
                try { metadata = Json.object(Json.parse(Files.readString(fabric, StandardCharsets.UTF_8))); }
                catch (IllegalArgumentException invalid) { throw new IOException("The built " + fabric + " is not valid JSON: " + invalid.getMessage()); }
                mods.add(new Declared("fabric", Json.text(metadata, "id", ""), Json.text(metadata, "version", ""), fabric));
            }
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                Path toml = dir.resolve(name);
                if (!Files.isRegularFile(toml)) continue;
                for (Map<String, String> mod : tomlMods(Files.readString(toml, StandardCharsets.UTF_8)))
                    mods.add(new Declared(name.contains("neoforge") ? "neoforge" : "forge", mod.getOrDefault("modId", ""), mod.getOrDefault("version", ""), toml));
            }
        }
        return mods;
    }
    /** modId and version of each [[mods]] table in a mods.toml. Only those two keys are read. */
    static List<Map<String, String>> tomlMods(String text) {
        List<Map<String, String>> mods = new ArrayList<>();
        Map<String, String> current = null;
        Pattern key = Pattern.compile("^\\s*(modId|version)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')");
        for (String line : text.split("\\R")) {
            String strip = line.strip();
            if (strip.startsWith("[")) {
                current = strip.matches("\\[\\[\\s*mods\\s*]]\\s*(#.*)?") ? new LinkedHashMap<>() : null;
                if (current != null) mods.add(current);
                continue;
            }
            if (current == null) continue;
            Matcher found = key.matcher(line);
            if (found.find()) {
                String quoted = found.group(2);
                current.put(found.group(1), quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return mods;
    }
    /** Which metadata the running loader reads. Old NeoForge (1.20.x) still read mods.toml. */
    static List<Declared> forLoader(List<Declared> all, String loader) {
        List<Declared> exact = all.stream().filter(d -> d.kind().equals(loader)).toList();
        if (!exact.isEmpty() || !loader.equals("neoforge")) return exact;
        return all.stream().filter(d -> d.kind().equals("forge")).toList();
    }
    static List<String> ids(List<Declared> declared) {
        Set<String> ids = new LinkedHashSet<>();
        for (Declared mod : declared) ids.add(mod.id());
        return new ArrayList<>(ids);
    }
    /**
     * The verdict: ok only when the loader lists every declared id, each with the declared version
     * when the metadata states one. Anything that cannot be looked at fails with the reason.
     */
    static Map<String, Object> verdict(List<Declared> all, List<Path> outputs, Map<String, Object> answer) {
        String loader = Json.text(answer, "loader", "unknown");
        Map<String, Object> listed = answer.get("all") instanceof Map<?, ?> ? Json.object(answer.get("all")) : Map.of();
        List<Object> rows = new ArrayList<>(); List<String> missing = new ArrayList<>(), wrong = new ArrayList<>(), unchecked = new ArrayList<>();
        String loaderList = listed.size() + (listed.size() == 1 ? " mod" : " mods") + (listed.isEmpty() ? "" : ": " + String.join(", ", listed.entrySet().stream().limit(12).map(e -> e.getKey() + " " + e.getValue()).toList()) + (listed.size() > 12 ? ", ..." : ""));
        Map<String, Object> result = Json.map("loader", loader, "loaderModCount", listed.size(), "outputs", outputs.stream().map(Path::toString).toList());
        if (loader.equals("unknown")) {
            result.putAll(Json.map("ok", false, "details", "Could not ask the game's mod loader for its mod list (no Fabric Loader, NeoForge or Forge mod list found in the running game), so whether the mod loaded is unknown."));
            return result;
        }
        List<Declared> expected = forLoader(all, loader);
        if (expected.isEmpty()) {
            result.putAll(Json.map("ok", false, "details", "The build output the client runs from has no " + (loader.equals("fabric") ? "fabric.mod.json" : loader.equals("neoforge") ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml")
                    + " (looked in " + (outputs.isEmpty() ? "no main or client source-set output" : String.join(", ", outputs.stream().map(Path::toString).toList())) + "), so which mods should be loaded is unknown. The " + loader + " loader lists " + loaderList + "."));
            return result;
        }
        for (Declared mod : expected) {
            if (mod.id().isEmpty() || mod.id().contains("${")) {
                result.putAll(Json.map("ok", false, "details", "The built " + mod.file() + " declares mod id \"" + mod.id() + "\", which the build did not fill in, so the mod cannot be looked up in the loader's list."));
                return result;
            }
            Object version = listed.get(mod.id());
            boolean present = listed.containsKey(mod.id());
            boolean versionStated = !mod.version().isEmpty() && !mod.version().contains("${");
            Map<String, Object> row = Json.map("id", mod.id(), "loaded", present, "declaredVersion", mod.version(), "loadedVersion", version, "metadata", mod.file().toString());
            if (!present) missing.add(mod.id());
            else if (versionStated && !mod.version().equals(String.valueOf(version))) wrong.add(mod.id() + " (declared " + mod.version() + ", loaded " + version + ")");
            else if (!versionStated) { unchecked.add(mod.id()); row.put("versionChecked", false); }
            if (present && versionStated) row.put("versionChecked", true);
            rows.add(row);
        }
        result.put("mods", rows);
        if (!missing.isEmpty()) {
            result.putAll(Json.map("ok", false, "missing", missing, "details", "The " + loader + " loader did not load " + String.join(", ", missing) + " (declared in " + expected.get(0).file() + "). It lists " + loaderList
                    + ". The game started without the mod; the reason is in the game log (a refused game or loader version range, or a missing dependency)."));
            return result;
        }
        if (!wrong.isEmpty()) {
            result.putAll(Json.map("ok", false, "details", "The " + loader + " loader loaded a different version than this build declares: " + String.join(", ", wrong) + ". Another copy of the mod is probably on the game's classpath or in its mods folder."));
            return result;
        }
        String said = String.join(", ", expected.stream().map(d -> d.id() + " " + listed.get(d.id())).distinct().toList());
        result.putAll(Json.map("ok", true, "details", "The " + loader + " loader lists " + said + " (" + listed.size() + " mods in all)"
                + (unchecked.isEmpty() ? "." : "; the version of " + String.join(", ", unchecked) + " comes from the jar at runtime, so only its id was matched.")));
        return result;
    }
    /** True when a class is this project's own compiled code: its class file is in the project's build output. */
    static boolean ownClass(List<Path> outputs, String className) {
        if (className == null || className.isBlank()) return false;
        String file = className.replace('.', '/') + ".class";
        for (Path dir : outputs) if (Files.isRegularFile(dir.resolve(file))) return true;
        return false;
    }
}
