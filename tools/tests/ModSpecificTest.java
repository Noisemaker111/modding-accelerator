import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No rule, repair, bridge, pattern condition or tool source may name a mod from the corpora: what modaccel does must
 * work on the next mod as on the ones it was proven on. The names come from the corpus and bench manifests (convert,
 * port, real mods, server plugins, the perf corpus and the version-port corpus); a mod is matched by its id, its repository
 * name and its display name, as a whole word.
 *
 * Scanned: every source under tools/ except tools/tests (the tests and their fixtures prove things on real mods), every
 * file under knowledge/bridges, and the executable fields of every rule file in knowledge/ (what a rule matches, what it
 * changes, its id). A rule's provenance (where it was found or proven: examples, provenIn, verified, from, evidence,
 * notes, cause, symptom and why prose) and its test inputs may name the real case they came from. Corpus manifests and the
 * per-mod experiment folders under bench/ are allowed by not being scanned.
 *
 * A pack repair is also checked by shape: a general repair (knowledge/pack-fixes.json) names no mod, no file and no URL.
 *
 * A library API many mods build against is platform, not a mod: PLATFORM lists the ones modaccel maps, each with why.
 */
public final class ModSpecificTest {

    /** Libraries other mods implement or build with: mapping their API is about every mod that uses it. */
    static final Map<String, String> PLATFORM = Map.of(
        "modmenu", "Mod Menu's ModMenuApi entrypoint: a library API mods implement; convert maps it to NeoForge's config-screen extension point",
        "Mod Menu", "the display name of the same library API",
        "ModMenu", "the repository name of the same library API",
        "architectury", "Architectury Loom, the Gradle plugin multi-loader builds use; build detection reads it"
    );

    /** Words that are a corpus mod's name and also ordinary words of this codebase or the game; they are matched only as ids. */
    static final Set<String> COMMON = Set.of("Notes", "Carpet", "Satin", "Iris", "Traverse", "Bobby", "Krypton", "Curios", "Trinkets", "Zombie", "Fabric");

    /** Fields of a rule that record where it came from and the case that proved it, never what it does. */
    static final Set<String> PROVENANCE = Set.of(
        "examples", "provenIn", "verified", "from", "note", "notes", "source", "sources", "evidence", "about", "why", "cause",
        "text", "change", "gain", "gate", "description", "page", "license", "test", "tests", "record", "comment", "detail",
        "reason", "measured", "symptom", "learnedFrom", "proof", "proven", "what", "rationale"
    );

    /** knowledge files that are corpus manifests or game data read from the game's own jars, not rules. */
    static final Set<String> NOT_RULES = Set.of("port-corpus.json");

    static int checked;

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, Pattern> names = names(harness);
        if (names.size() < 30) throw new AssertionError("Read only " + names.size() + " corpus names; the manifests moved?");
        List<String> violations = new ArrayList<>();
        try (var walk = Files.walk(harness.resolve("tools"))) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = harness.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("tools/tests/") || !rel.matches(".*\\.(java|ps1|sh|py|json|toml|gradle|kts)$")) continue;
                scanText(rel, Files.readString(file, StandardCharsets.UTF_8), names, violations);
            }
        }
        try (var walk = Files.walk(harness.resolve("knowledge"))) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = harness.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("knowledge/bridges/")) {
                    scanText(rel, Files.readString(file, StandardCharsets.UTF_8), names, violations);
                    continue;
                }
                if (!rel.endsWith(".json") || NOT_RULES.contains(file.getFileName().toString()) || rel.startsWith("knowledge/learned/") || file.getFileName().toString().startsWith("api-")) continue;
                scanRules(rel, Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "", names, violations);
            }
        }
        repairShapes(harness, violations);
        selfCheck(names);
        if (!violations.isEmpty()) {
            throw new AssertionError(violations.size() + " place(s) name a mod from the corpora; restate each as a general rule or move it to a test or bench folder:\n  "
                + String.join("\n  ", violations.subList(0, Math.min(60, violations.size()))));
        }
        System.out.println(checked + " files and rule fields checked against " + names.size() + " corpus mod names: no rule, repair, bridge, pattern condition or tool source names a mod");
    }

    /** Every corpus mod's id, repository name and display name, each with the pattern that finds it. */
    static Map<String, Pattern> names(Path harness) throws Exception {
        Set<String> raw = new TreeSet<>();
        for (String file : List.of("bench/convert-sweep/corpus.json:mods", "bench/real-mods/manifest.json:mods", "bench/server-plugins/manifest.json:plugins",
            "bench/port-cases.json:cases", "bench/perf-corpus.json:mods", "knowledge/port-corpus.json:repos")) {
            String[] parts = file.split(":");
            Path path = harness.resolve(parts[0]);
            if (!Files.isRegularFile(path)) throw new AssertionError("Missing corpus manifest " + parts[0]);
            for (Object item : Json.array(Json.object(Json.parse(Files.readString(path))).get(parts[1]))) {
                Map<String, Object> mod = Json.object(item);
                for (String key : List.of("id", "name", "mod", "modId")) if (mod.get(key) instanceof String text) raw.add(text);
                for (String key : List.of("repo", "repository")) if (mod.get(key) instanceof String repo) raw.add(repo.replaceAll("\\.git$", "").replaceAll("/+$", "").replaceAll(".*/", ""));
                if (mod.get("namespaces") instanceof List<?> namespaces) for (Object ns : namespaces) raw.add(ns.toString());
            }
        }
        Map<String, Pattern> out = new TreeMap<>();
        for (String name : raw) {
            String clean = name.replaceAll("\\s*\\(.*\\)$", "").replaceAll("\\s*:.*$", "").strip();
            if (clean.length() < 4 || PLATFORM.containsKey(clean) || clean.matches("\\d.*")) continue;
            // Corpus ids that only say what kind of entry it is (an MDK, an example, a whole-corpus selector) name no mod.
            if (clean.matches("(?i).*(mdk|example|-all)$|(?i)mdk-.*") || clean.equalsIgnoreCase("fabric")) continue;
            boolean id = clean.equals(clean.toLowerCase(Locale.ROOT)) && clean.matches("[a-z0-9_.-]+");
            if (!id && COMMON.contains(clean)) continue;
            String quoted = Pattern.quote(clean);
            out.put(clean, Pattern.compile(id ? "(?<![A-Za-z0-9_.-])" + quoted + "(?![A-Za-z0-9_])" : "(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])"));
        }
        return out;
    }

    static void scanText(String rel, String text, Map<String, Pattern> names, List<String> violations) {
        checked++;
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) for (var name : names.entrySet()) {
            Matcher m = name.getValue().matcher(lines[i]);
            if (m.find()) violations.add(rel + ":" + (i + 1) + " names " + name.getKey() + ": " + lines[i].strip());
        }
    }

    /** The executable fields of a rule file: everything outside its provenance fields. */
    static void scanRules(String rel, Object value, String path, Map<String, Pattern> names, List<String> violations) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (PROVENANCE.contains(key)) continue;
                scanRules(rel, entry.getValue(), path.isEmpty() ? key : path + "." + key, names, violations);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) scanRules(rel, list.get(i), path + "[" + i + "]", names, violations);
        } else if (value instanceof String text) {
            checked++;
            for (var name : names.entrySet()) {
                if (!name.getValue().matcher(text).find()) continue;
                violations.add(rel + " " + path + " names " + name.getKey() + ": " + (text.length() > 120 ? text.substring(0, 120) + "..." : text));
            }
        }
    }

    /** A pack repair names a kind of fault: no mod, no file, no download of one mod's release. */
    static void repairShapes(Path harness, List<String> violations) throws Exception {
        for (Object item : Json.array(Json.object(Json.parse(Files.readString(harness.resolve("knowledge/pack-fixes.json")))).get("fixes"))) {
            Map<String, Object> fix = Json.object(item);
            String id = String.valueOf(fix.get("id"));
            checked++;
            if (fix.containsKey("mod")) violations.add("knowledge/pack-fixes.json " + id + " names a mod (\"mod\": " + fix.get("mod") + ")");
            for (String side : List.of("match", "action")) {
                Map<String, Object> part = Json.object(fix.getOrDefault(side, Map.of()));
                for (String key : List.of("mod", "dependency")) if (part.get(key) instanceof String named && !named.equals("*")) violations.add("knowledge/pack-fixes.json " + id + " " + side + "." + key + " names " + named);
                if (part.get("mods") instanceof List<?> mods && !mods.isEmpty()) violations.add("knowledge/pack-fixes.json " + id + " " + side + ".mods names " + mods);
                for (String key : List.of("url", "file", "sha512")) if (part.containsKey(key)) violations.add("knowledge/pack-fixes.json " + id + " " + side + "." + key + " fixes one mod's file");
            }
        }
    }

    /** The scan must find what it is for: a planted rule naming a corpus mod fails it. */
    static void selfCheck(Map<String, Pattern> names) {
        List<String> found = new ArrayList<>();
        String some = names.keySet().iterator().next();
        scanRules("planted.json", Json.parse("{\"rules\":[{\"id\":\"x\",\"match\":\"" + some + "\",\"verified\":\"" + some + "\"}]}"), "", names, found);
        if (found.size() != 1 || !found.get(0).contains("rules[0].match")) throw new AssertionError("the scan misses a planted rule (or flags its provenance): " + found);
        found.clear();
        scanText("Planted.java", "class A { String s = \"" + some + "\"; }", names, found);
        if (found.size() != 1) throw new AssertionError("the scan misses a planted tool source: " + found);
        found.clear();
        scanText("Clean.java", "class A { String s = \"minecraft:stone\"; }", names, found);
        if (!found.isEmpty()) throw new AssertionError("the scan flags vanilla content: " + found);
        checked -= 3;
    }
}
