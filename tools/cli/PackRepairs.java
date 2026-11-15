import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * pack-check's repairs, as data: each entry in knowledge/pack-fixes.json names the loader and game versions it is for,
 * a "match" (when it applies, read from the jars before any game runs), an "action" (what it changes) and a "test"
 * (jars that must match before the action and must not after it), plus the evidence that proved it. Matchers and
 * actions are small named functions here; a repair that needs neither a new matcher nor a new action is data only.
 * A repair is applied to the user's pack only after the repaired pack, run beside the shipped one, passes.
 */
final class PackRepairs {

    private PackRepairs() {}

    interface Matcher {
        /** null when the repair does not apply; otherwise what was found, for the finding's summary. */
        String match(Map<String, Object> spec, PackMods.Scan scan);
    }

    static final Map<String, Matcher> MATCHERS = Map.of(
        "mod-below",
        PackRepairs::modBelow,
        "mod-range",
        PackRepairs::modRange,
        "mods-together",
        PackRepairs::modsTogether,
        "dependency-unmet",
        PackRepairs::dependencyUnmet,
        "dependency-missing",
        PackRepairs::dependencyMissing
    );

    static final Set<String> ACTIONS = Set.of("add-jar", "remove-mod", "replace-mod");

    /** What a repair changes in the mods folder: jars to add (verified downloads) and jar file names to take out. */
    record Change(List<Path> add, List<String> remove, Map<String, Map<String, Object>> sources) {
        Change(List<Path> add, List<String> remove) {
            this(add, remove, Map.of());
        }
    }

    static List<Map<String, Object>> all(Path harness) throws IOException {
        List<Map<String, Object>> fixes = new ArrayList<>();
        for (Object f : Json.array(Json.read(harness.resolve("knowledge/pack-fixes.json")).get("fixes"))) fixes.add(Json.object(f));
        return fixes;
    }

    /** The repairs for this pack's loader and game version whose match holds, each as a known-fault finding. */
    static List<Map<String, Object>> match(Path harness, PackCheck.Pack pack, PackMods.Scan scan, List<Object> findings) throws IOException {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (var fix : all(harness)) {
            String found = applies(fix, pack.loader(), pack.minecraft(), scan);
            if (found == null) continue;
            matched.add(fix);
            findings.add(
                Json.map(
                    "kind",
                    "known-fault",
                    "severity",
                    "problem",
                    "id",
                    fix.get("id"),
                    "summary",
                    found + ": " + fix.get("symptom"),
                    "cause",
                    fix.get("cause"),
                    "evidence",
                    fix.get("evidence"),
                    "fix",
                    fix
                )
            );
        }
        return matched;
    }

    static String applies(Map<String, Object> fix, String loader, String minecraft, PackMods.Scan scan) {
        if (!Json.text(fix, "loader", "").equals(loader)) return null;
        if (!Json.array(fix.get("minecraft")).contains(minecraft)) return null;
        var spec = Json.object(fix.get("match"));
        Matcher matcher = MATCHERS.get(Json.text(spec, "type", ""));
        if (matcher == null) throw new IllegalStateException("pack-fixes.json: " + fix.get("id") + " has an unknown match type " + spec.get("type"));
        return matcher.match(spec, scan);
    }

    /** The loader uses the highest version of a mod id found anywhere, bundled or not; this applies while that one is older than the fix. */
    static String modBelow(Map<String, Object> spec, PackMods.Scan scan) {
        PackMods.Provider used = scan.used(Json.text(spec, "mod", ""));
        if (used == null || Versions.compare(used.version(), Json.text(spec, "below", "")) >= 0) return null;
        return spec.get("mod") + " " + used.version() + " (from " + used.jar() + ") has a proven fault, fixed in " + spec.get("below");
    }

    static String modRange(Map<String, Object> spec, PackMods.Scan scan) {
        PackMods.Provider used = scan.used(Json.text(spec, "mod", ""));
        if (used == null) return null;
        var range = new PackMods.Dependency("", "", List.of(Json.text(spec, "range", "*")), used.format(), "required");
        if (!PackMods.accepts(range, used.version())) return null;
        return spec.get("mod") + " " + used.version() + " (from " + used.jar() + ") has a proven fault in " + spec.get("range");
    }

    static String modsTogether(Map<String, Object> spec, PackMods.Scan scan) {
        List<String> found = new ArrayList<>();
        for (Object id : Json.array(spec.get("mods"))) {
            PackMods.Provider used = scan.used(String.valueOf(id));
            if (used == null) return null;
            found.add(id + " " + used.version() + " (" + used.jar() + ")");
        }
        return String.join(" with ", found) + " have a proven fault together";
    }

    /** A mod's own required dependency that the version in the pack does not satisfy (an exact pin on a library the pack has newer). */
    static String dependencyUnmet(Map<String, Object> spec, PackMods.Scan scan) {
        String mod = Json.text(spec, "mod", ""), dependency = Json.text(spec, "dependency", "");
        PackMods.Provider owner = scan.used(mod), used = scan.used(dependency);
        if (owner == null || used == null) return null;
        for (var d : scan.dependencies) if (
            d.owner().equals(owner.jar()) && d.id().equals(dependency) && d.kind().equals("required") && !PackMods.accepts(d, used.version())
        ) return mod + " " + owner.version() + " (" + owner.jar() + ") requires " + dependency + " " + String.join(" or ", d.ranges()) +
            ", and the pack has " + used.version() + " (" + used.jar() + ")";
        return null;
    }

    /** A mod's own required dependency that no jar in the pack provides (a library its source no longer serves). */
    static String dependencyMissing(Map<String, Object> spec, PackMods.Scan scan) {
        String mod = Json.text(spec, "mod", ""), dependency = Json.text(spec, "dependency", "");
        PackMods.Provider owner = scan.used(mod);
        if (owner == null || scan.providers.containsKey(dependency)) return null;
        for (var d : scan.dependencies) if (d.owner().equals(owner.jar()) && d.id().equals(dependency) && d.kind().equals("required")) return mod + " " +
            owner.version() + " (" + owner.jar() + ") requires " + dependency + " " + String.join(" or ", d.ranges()) + ", which no jar in the pack provides";
        return null;
    }

    /** Every chosen repair as one change: its jars downloaded and checked against their SHA-512, and the pack's jars it takes out. */
    static Change change(PackCheck check, List<Map<String, Object>> fixes, PackMods.Scan scan) throws Exception {
        List<Path> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        for (var fix : fixes) {
            var action = Json.object(fix.get("action"));
            String type = Json.text(action, "type", "");
            if (!ACTIONS.contains(type)) throw new IllegalStateException("pack-fixes.json: " + fix.get("id") + " has an unknown action " + type);
            if (type.equals("remove-mod") || type.equals("replace-mod")) for (var p : scan.providers.getOrDefault(Json.text(action, "mod", ""), List.of())) {
                if (!p.bundled() && !remove.contains(p.jar())) remove.add(p.jar());
            }
            if (type.equals("add-jar") || type.equals("replace-mod")) {
                Path jar = download(check, action);
                add.add(jar);
                sources.put(jar.getFileName().toString(), action);
            }
        }
        return new Change(add, remove, sources);
    }

    static Path download(PackCheck check, Map<String, Object> action) throws Exception {
        String file = Json.text(action, "file", "");
        if (file.isEmpty() || file.contains("/") || file.contains("\\") || file.startsWith(".")) throw new IOException("Unsafe repair file name " + file);
        Path jar = check.cache.resolve("fixes").resolve(file);
        if (!Files.isRegularFile(jar) || !PackCheck.hash("SHA-512", Files.readAllBytes(jar)).equals(action.get("sha512"))) {
            byte[] bytes = PackCheck.get(Json.text(action, "url", ""));
            if (!PackCheck.hash("SHA-512", bytes).equals(action.get("sha512"))) throw new IOException(
                file + " from " + action.get("url") + " does not match its recorded SHA-512; not applied."
            );
            Files.createDirectories(jar.getParent());
            Files.write(jar, bytes);
        }
        return jar;
    }

    // ------------------------------------------------------------------ the tests in pack-fixes.json
    /**
     * Runs every repair's test: its jars (written in the loader's own metadata format) must match before the action,
     * and must not once the action is done (jars added as the test describes them, jars removed). Also checks that
     * each repair names a known matcher, action and check, and that it does not match on another loader.
     */
    static List<String> selfTest(Path harness, Path scratch) throws Exception {
        List<String> passed = new ArrayList<>();
        Set<String> checks = new HashSet<>();
        for (var check : PackChecks.ALL) checks.add(check.id());
        Map<String, PackLoaders.Loader> loaders = PackLoaders.all(harness);
        for (var fix : all(harness)) {
            String id = Json.text(fix, "id", "");
            var test = Json.object(fix.get("test"));
            if (test.isEmpty()) throw new AssertionError(id + " has no test");
            if (!checks.contains(Json.text(fix, "check", ""))) throw new AssertionError(id + " names no known check: " + fix.get("check"));
            if (!ACTIONS.contains(Json.text(Json.object(fix.get("action")), "type", ""))) throw new AssertionError(id + " has an unknown action");
            String loaderId = Json.text(fix, "loader", ""), minecraft = String.valueOf(Json.array(fix.get("minecraft")).get(0));
            PackLoaders.Loader loader = loaders.get(loaderId);
            if (loader == null) throw new AssertionError(id + " is for an unknown loader " + loaderId);
            String format = loader.metadata(minecraft).get(0);
            Path mods = Files.createDirectories(scratch.resolve(id).resolve("mods"));
            List<PackCheck.Mod> jars = new ArrayList<>();
            for (Object j : Json.array(test.get("jars"))) jars.add(testJar(mods, Json.object(j), format));
            PackMods.Scan before = PackMods.scan(jars, loader.metadata(minecraft));
            if (applies(fix, loaderId, minecraft, before) == null) throw new AssertionError(id + ": its test jars do not match it");
            for (String other : loaders.keySet()) if (!other.equals(loaderId) && applies(fix, other, minecraft, before) != null) throw new AssertionError(
                id + " also matches on " + other
            );
            var action = Json.object(fix.get("action"));
            String type = Json.text(action, "type", "");
            List<PackCheck.Mod> after = new ArrayList<>(jars);
            if (type.equals("remove-mod") || type.equals("replace-mod")) {
                Set<String> gone = new HashSet<>();
                for (var p : before.providers.getOrDefault(Json.text(action, "mod", ""), List.of())) if (!p.bundled()) gone.add(p.jar());
                if (gone.isEmpty()) throw new AssertionError(id + ": its action removes nothing from its test jars");
                after.removeIf(m -> gone.contains(m.file()));
            }
            if (type.equals("add-jar") || type.equals("replace-mod")) {
                var added = Json.object(test.get("added"));
                if (added.isEmpty()) throw new AssertionError(id + ": its test does not describe the jar it adds");
                added.putIfAbsent("file", Json.text(action, "file", "added.jar"));
                after.add(testJar(mods, added, format));
            }
            if (applies(fix, loaderId, minecraft, PackMods.scan(after, loader.metadata(minecraft))) != null) throw new AssertionError(
                id + ": its test jars still match after its action"
            );
            passed.add(id);
        }
        return passed;
    }

    /** A jar with the given mods (and bundled mods) in one loader's metadata format. */
    static PackCheck.Mod testJar(Path mods, Map<String, Object> spec, String format) throws IOException {
        Path jar = mods.resolve(Json.text(spec, "file", "test.jar"));
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(out, format, metadata(Json.array(spec.get("mods")), Json.array(spec.getOrDefault("bundles", List.of())), format));
            int n = 0;
            for (Object b : Json.array(spec.getOrDefault("bundles", List.of()))) {
                var bundle = Json.object(b);
                var inner = new ByteArrayOutputStream();
                try (var nested = new ZipOutputStream(inner)) {
                    write(nested, format, metadata(List.of(bundle), List.of(), format));
                }
                out.putNextEntry(new ZipEntry(nestedPath(format, n++)));
                out.write(inner.toByteArray());
                out.closeEntry();
            }
            if (format.endsWith(".toml") && n > 0) {
                List<Object> paths = new ArrayList<>();
                for (int i = 0; i < n; i++) paths.add(Json.map("path", nestedPath(format, i)));
                write(out, "META-INF/jarjar/metadata.json", Json.write(Json.map("jars", paths)));
            }
        }
        return new PackCheck.Mod(jar.getFileName().toString(), jar, "unknown", "test");
    }

    static String nestedPath(String format, int n) {
        return (format.endsWith(".toml") ? "META-INF/jarjar/" : "META-INF/jars/") + "bundled-" + n + ".jar";
    }

    static String metadata(List<Object> mods, List<Object> bundles, String format) {
        StringBuilder text = new StringBuilder();
        if (format.endsWith(".toml")) {
            text.append("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n");
            for (Object m : mods) {
                var mod = Json.object(m);
                text.append("[[mods]]\nmodId=\"").append(mod.get("id")).append("\"\nversion=\"").append(mod.get("version")).append("\"\n");
            }
            for (Object m : mods) {
                var mod = Json.object(m);
                for (var d : Json.object(mod.getOrDefault("depends", Map.of())).entrySet()) text
                    .append("[[dependencies.").append(mod.get("id")).append("]]\nmodId=\"").append(d.getKey())
                    .append("\"\nmandatory=true\ntype=\"required\"\nversionRange=\"").append(d.getValue()).append("\"\nside=\"BOTH\"\n");
            }
            return text.toString();
        }
        var mod = Json.object(mods.get(0));
        List<Object> jars = new ArrayList<>();
        for (int i = 0; i < bundles.size(); i++) jars.add(Json.map("file", nestedPath(format, i)));
        if (format.equals("quilt.mod.json")) {
            List<Object> paths = new ArrayList<>();
            for (int i = 0; i < bundles.size(); i++) paths.add(nestedPath(format, i));
            return Json.write(Json.map("schema_version", 1, "quilt_loader", Json.map("id", mod.get("id"), "version", mod.get("version"), "jars", paths)));
        }
        return Json.write(Json.map("schemaVersion", 1, "id", mod.get("id"), "version", mod.get("version"), "jars", jars, "depends", mod.getOrDefault("depends", Map.of())));
    }

    static void write(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
