import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A changed recipe, loot table, tag or texture without a Gradle run. After the launch's Gradle run, modaccel
 * records each input file's hash, which resource folder Gradle copied to which output folder, and which
 * resource files Gradle changed on the way (fabric.mod.json's ${version}, any expand or token filter). Later,
 * when every changed input is a resource file Gradle copied as it was, modaccel copies (or deletes) those
 * files in the output itself: what processResources would have written, with no Gradle start (2 to 25 s).
 *
 * Gradle stays the authority: a changed class source, build file or dependency, a file Gradle filtered, a
 * file with a ${...} placeholder, or anything outside a resource folder runs Gradle as before.
 */
final class ResourceCopy {

    private ResourceCopy() {}

    /** Each source set's resource folders with the output folder Gradle copied them to (the output holding most of their files). */
    static List<List<String>> roots(Map<String, Object> model) throws IOException {
        List<List<String>> roots = new ArrayList<>();
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(
            Json.object(p).get("sourceSets")
        )) {
            var set = Json.object(s);
            for (String resources : ProjectModel.strings(set.get("resources"))) {
                Path source = Path.of(resources);
                if (!Files.isDirectory(source)) continue;
                List<Path> files;
                try (var walk = Files.walk(source)) {
                    files = walk.filter(Files::isRegularFile).toList();
                }
                String best = null;
                long most = 0;
                for (String output : ProjectModel.strings(set.get("output"))) {
                    long found = files
                        .stream()
                        .filter(f -> Files.isRegularFile(Path.of(output).resolve(source.relativize(f).toString())))
                        .count();
                    if (found > most) {
                        most = found;
                        best = output;
                    }
                }
                if (best != null) roots.add(List.of(source.toString(), best));
            }
        }
        return roots;
    }

    /** The resource files whose built copy differs from the source: Gradle filtered them, so only Gradle may write them. */
    static List<String> filtered(List<List<String>> roots) throws IOException {
        List<String> filtered = new ArrayList<>();
        for (List<String> root : roots) {
            Path source = Path.of(root.get(0)), output = Path.of(root.get(1));
            try (var walk = Files.walk(source)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    Path built = output.resolve(source.relativize(file).toString());
                    if (!Files.isRegularFile(built) || Files.mismatch(file, built) != -1) filtered.add(file.toString());
                }
            }
        }
        return filtered;
    }

    /**
     * Copies the changed resource files into the output when that is all that changed since the record was
     * made, and returns how many; -1 when Gradle must run (nothing is written then).
     */
    static int copy(Map<String, Object> record, Map<String, String> now) throws IOException {
        // A launch recorded before modaccel kept these runs Gradle once, which records them.
        if (!(record.get("inputs") instanceof Map<?, ?>) || record.get("resourceRoots") == null) return -1;
        Map<String, Object> then = Json.object(record.get("inputs"));
        Set<String> filtered = new HashSet<>(ProjectModel.strings(record.get("filtered")));
        List<List<String>> roots = new ArrayList<>();
        for (Object r : Json.array(record.get("resourceRoots"))) roots.add(ProjectModel.strings(r));
        Set<String> keys = new TreeSet<>(then.keySet());
        keys.addAll(now.keySet());
        List<Path[]> plan = new ArrayList<>();
        for (String key : keys) {
            if (Objects.equals(then.get(key), now.get(key))) continue;
            Path file = Path.of(key);
            List<String> root = null;
            for (List<String> r : roots) if (file.startsWith(Path.of(r.get(0)))) root = r;
            if (root == null || filtered.contains(key)) return -1;
            Path target = Path.of(root.get(1)).resolve(Path.of(root.get(0)).relativize(file).toString());
            boolean present = now.get(key) != null && !"missing".equals(now.get(key)) && Files.isRegularFile(file);
            // A placeholder may be one an expand filter fills in, which only Gradle knows about.
            if (present && new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("${")) return -1;
            plan.add(new Path[] { present ? file : null, target });
        }
        for (Path[] step : plan) {
            if (step[0] == null) Files.deleteIfExists(step[1]);
            else {
                Files.createDirectories(step[1].getParent());
                Files.copy(step[0], step[1], StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return plan.size();
    }
}
