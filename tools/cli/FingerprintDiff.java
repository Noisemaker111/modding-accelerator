import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Compares behaviour fingerprints (Fingerprint, FINGERPRINT.md). A fingerprint is flattened to leaves keyed by path
 * ("checkpoints/100/levels/minecraft:overworld/blockEntities/3,64,-2"), lists of names become sets, and two records
 * compare leaf by leaf.
 *
 * Same game version: every leaf counts. Across game versions (a port): only what the mod owns is compared, after the
 * game's own renames (knowledge/fingerprint-normalise.json): its registry entries and tags, what each of its items
 * places, which of its entities exist, its blocks, items and entities counted, the save round trip and its errors.
 *
 * perf-optimize's built-in gate (gate): leaves two baseline launches disagree on are not deterministic in this mod; they
 * are named and left out, and every other leaf must be the same in each candidate as in its paired baseline.
 */
final class FingerprintDiff {
    /** Leaves that describe the run, not the mod's behaviour. */
    static final Pattern META = Pattern.compile(
        "^(arguments|mods|loader|freezes|seeded|settled|settledAfterSeconds|loadedWhenSettled|unfrozen|schema|kind|target|out|dedicated|mode|area)(/.*)?$"
    );

    private FingerprintDiff() {}

    static Map<String, Object> run(Path home, Modaccel.Options options) throws Exception {
        String a = options.get("baseline", options.get("a", "")), b = options.get("candidate", options.get("b", ""));
        if (a.isBlank() || b.isBlank()) throw new IllegalArgumentException("fingerprint-diff needs two fingerprints: modaccel fingerprint-diff A.json B.json");
        if (a.contains(",") || b.contains(",")) {
            // perf-optimize's gate on recorded launches: paired baselines and candidates, in order.
            List<Path> base = Arrays.stream(a.split(",")).map(String::strip).map(Path::of).toList();
            List<Path> cand = Arrays.stream(b.split(",")).map(String::strip).map(Path::of).toList();
            if (base.size() != cand.size()) throw new IllegalArgumentException("--baseline and --candidate pair launch by launch: give as many of each");
            Map<String, Object> verdict = gate(base, cand);
            verdict.put("same", verdict.get("passed"));
            verdict.put("details", (Boolean.TRUE.equals(verdict.get("passed")) ? "Gate passed: " : "Gate failed: " + verdict.get("failures") + "; ")
                + verdict.get("compared") + " leaves compared, " + verdict.get("unstable") + " left out because the baselines disagree on them "
                + verdict.get("unstableSections") + (verdict.get("differingSections") instanceof Map<?, ?> d && !d.isEmpty() ? "; differing: " + d : ""));
            if (options.values.containsKey("out")) Json.save(Path.of(options.get("out", "")), verdict);
            return verdict;
        }
        Map<String, Object> left = Json.read(Path.of(a)), right = Json.read(Path.of(b));
        Normaliser normaliser = Normaliser.choose(home, options, left, right);
        Map<String, Object> diff = compare(left, right, normaliser);
        diff.put("a", Path.of(a).toAbsolutePath().toString());
        diff.put("b", Path.of(b).toAbsolutePath().toString());
        diff.put("details", text(diff));
        if (options.values.containsKey("out")) Json.save(Path.of(options.get("out", "")), diff);
        return diff;
    }

    static Map<String, Object> compare(Map<String, Object> left, Map<String, Object> right, Normaliser normaliser) {
        Map<String, String> a = normaliser.apply(flatten(left)), b = normaliser.apply(flatten(right));
        Map<String, Object> changed = new TreeMap<>(), added = new TreeMap<>(), removed = new TreeMap<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            String x = a.get(key), y = b.get(key);
            if (Objects.equals(x, y)) continue;
            if (x == null) added.put(key, y);
            else if (y == null) removed.put(key, x);
            else changed.put(key, Json.map("a", x, "b", y));
        }
        Map<String, Object> sections = new TreeMap<>();
        for (Map<String, Object> group : List.of(changed, added, removed)) for (String key : group.keySet()) sections.merge(section(key), 1, (p, q) -> (Integer) p + (Integer) q);
        return Json.map(
            "same", changed.isEmpty() && added.isEmpty() && removed.isEmpty(),
            "normalisation", normaliser.name,
            "compared", keys.size(),
            "sections", sections,
            "changed", first(changed, 300),
            "onlyInB", first(added, 300),
            "onlyInA", first(removed, 300),
            "counts", Json.map("changed", changed.size(), "onlyInB", added.size(), "onlyInA", removed.size())
        );
    }

    /** The part of the record a leaf belongs to, for a readable summary: "checkpoints/blockEntities", "registries/minecraft:item". */
    static String section(String key) {
        String[] parts = key.split("/");
        if (parts[0].equals("checkpoints") && parts.length > 4) return "checkpoints/" + parts[4];
        if (parts[0].equals("reloaded") && parts.length > 3) return "reloaded/" + parts[3];
        if (parts.length > 1 && Set.of("registries", "log", "placement", "ticketsDuringRun").contains(parts[0])) return parts[0] + "/" + parts[1];
        return parts[0];
    }

    /**
     * Leaves keyed by path. A list of checkpoints is keyed by tick; a list of strings is a set (each member a key with the
     * value "+"); any other list by index.
     */
    static Map<String, String> flatten(Object value) {
        Map<String, String> out = new TreeMap<>();
        flatten("", value, out);
        out.keySet().removeIf(key -> META.matcher(key).matches());
        return out;
    }

    static void flatten(String path, Object value, Map<String, String> out) {
        String prefix = path.isEmpty() ? "" : path + "/";
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) out.put(path, "{}");
            for (var entry : map.entrySet()) flatten(prefix + entry.getKey(), entry.getValue(), out);
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.put(path, "[]");
                return;
            }
            boolean strings = true, ticks = true;
            for (Object item : list) {
                strings &= item instanceof String;
                ticks &= item instanceof Map<?, ?> m && m.get("tick") instanceof Number;
            }
            if (strings) for (Object item : list) out.put(prefix + item, "+");
            else if (ticks) for (Object item : list) flatten(prefix + ((Number) ((Map<?, ?>) item).get("tick")).intValue(), item, out);
            else for (int i = 0; i < list.size(); i++) flatten(prefix + i, list.get(i), out);
        } else {
            out.put(path, value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? String.valueOf(n.longValue()) : String.valueOf(value));
        }
    }

    static Map<String, Object> first(Map<String, Object> all, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : all.entrySet()) {
            if (out.size() == limit) {
                out.put("...", (all.size() - limit) + " more");
                break;
            }
            Object value = entry.getValue();
            if (value instanceof String text && text.length() > 600) value = text.substring(0, 600) + "...";
            out.put(entry.getKey(), value);
        }
        return out;
    }

    static String hash(Map<String, Object> fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var leaf : flatten(fingerprint).entrySet()) digest.update((leaf.getKey() + "=" + leaf.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A readable diff: one line per section with its count, then the first differences of each. */
    static String text(Map<String, Object> diff) {
        StringBuilder out = new StringBuilder();
        if (Boolean.TRUE.equals(diff.get("same"))) {
            out.append("Same behaviour: ").append(diff.get("compared")).append(" leaves compared (").append(diff.get("normalisation")).append(")");
            return out.toString();
        }
        Map<String, Object> counts = Json.object(diff.get("counts"));
        out.append(String.format(Locale.ROOT, "Different behaviour (%s): %s changed, %s only in B, %s only in A, of %s leaves%n",
            diff.get("normalisation"), counts.get("changed"), counts.get("onlyInB"), counts.get("onlyInA"), diff.get("compared")));
        for (var section : Json.object(diff.get("sections")).entrySet()) out.append("  ").append(section.getKey()).append(": ").append(section.getValue()).append('\n');
        int shown = 0;
        for (var entry : Json.object(diff.get("changed")).entrySet()) {
            if (shown++ == 25) break;
            if (entry.getValue() instanceof Map<?, ?> pair) out.append("  ~ ").append(entry.getKey()).append(": ").append(clip(pair.get("a"))).append(" -> ").append(clip(pair.get("b"))).append('\n');
        }
        for (String side : List.of("onlyInB", "onlyInA")) {
            int n = 0;
            for (var entry : Json.object(diff.get(side)).entrySet()) {
                if (n++ == 15) break;
                out.append(side.equals("onlyInB") ? "  + " : "  - ").append(entry.getKey()).append(": ").append(clip(entry.getValue())).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    static String clip(Object value) {
        String text = String.valueOf(value);
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    // ------------------------------------------------------------------------------------------ perf-optimize's gate

    /**
     * Every candidate against its paired baseline, leaving out leaves that two baseline launches disagree on. With one
     * baseline launch nothing is known to be unstable, so every leaf counts.
     */
    static Map<String, Object> gate(List<Path> baselines, List<Path> candidates) throws Exception {
        List<Map<String, String>> base = new ArrayList<>(), cand = new ArrayList<>();
        for (Path p : baselines) base.add(flatten(Json.read(p)));
        for (Path p : candidates) cand.add(flatten(Json.read(p)));
        Set<String> unstable = new TreeSet<>();
        for (int i = 1; i < base.size(); i++) unstable.addAll(differing(base.get(0), base.get(i)));
        List<Object> failures = new ArrayList<>();
        // Every launch must start from the saved world: the same game time at the first checkpoint. A world that ran
        // before it froze started from another state, and nothing after it can be compared.
        Map<String, Set<String>> starts = new TreeMap<>();
        List<Map<String, String>> launches = new ArrayList<>(base);
        launches.addAll(cand);
        for (Map<String, String> launch : launches) {
            for (var leaf : launch.entrySet()) {
                if (leaf.getKey().matches("checkpoints/0/levels/[^/]+/gameTime")) starts.computeIfAbsent(leaf.getKey(), k -> new TreeSet<>()).add(leaf.getValue());
            }
        }
        for (var start : starts.entrySet()) {
            if (start.getValue().size() > 1) {
                failures.add("the launches did not start from the same state (" + start.getKey() + " was " + start.getValue()
                    + "): the world ran before it froze, so nothing after it decides the candidate");
            }
        }
        Map<String, Object> examples = new LinkedHashMap<>();
        Map<String, Integer> sections = new TreeMap<>();
        Set<String> compared = new TreeSet<>();
        for (int i = 0; i < Math.min(base.size(), cand.size()); i++) {
            Set<String> differ = differing(base.get(i), cand.get(i));
            compared.addAll(base.get(i).keySet());
            differ.removeAll(unstable);
            if (differ.isEmpty()) continue;
            failures.add("run " + i + ": " + differ.size() + " leaves differ from the paired baseline (" + sectionsOf(differ) + ")");
            for (String key : differ) {
                sections.merge(section(key), 1, Integer::sum);
                if (examples.size() < 40) examples.put("run " + i + " " + key, Json.map("baseline", clip(base.get(i).get(key)), "candidate", clip(cand.get(i).get(key))));
            }
        }
        compared.removeAll(unstable);
        if (compared.isEmpty()) failures.add("no leaf is the same in every baseline launch; the fingerprint decides nothing for this mod");
        Map<String, Integer> unstableSections = new TreeMap<>();
        for (String key : unstable) unstableSections.merge(section(key), 1, Integer::sum);
        return Json.map(
            "passed", failures.isEmpty(),
            "failures", failures,
            "compared", compared.size(),
            "unstable", unstable.size(),
            "unstableSections", unstableSections,
            "unstableExamples", first(new TreeMap<>(unstable.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> (Object) "baselines disagree"))), 20),
            "differingSections", sections,
            "examples", examples,
            "baselineLaunches", base.size(),
            "rule", "Leaves two baseline launches disagree on are left out and named; every other leaf of each candidate equals its paired baseline's."
        );
    }

    static Set<String> differing(Map<String, String> a, Map<String, String> b) {
        Set<String> out = new TreeSet<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) if (!Objects.equals(a.get(key), b.get(key))) out.add(key);
        return out;
    }

    static String sectionsOf(Set<String> keys) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String key : keys) counts.merge(section(key), 1, Integer::sum);
        return counts.toString();
    }

    // ------------------------------------------------------------------------------------------ normalisation

    /**
     * What to compare. "exact": every leaf (same game version). "across versions": only what the mod owns, after the
     * game's renames (knowledge/fingerprint-normalise.json), so a port is judged by the mod's behaviour, not by what
     * the game changed around it.
     */
    static final class Normaliser {
        final String name;
        final List<Pattern> keep = new ArrayList<>();
        final Map<String, String> renames = new LinkedHashMap<>();
        final Set<String> namespaces;

        Normaliser(String name, Set<String> namespaces) {
            this.name = name;
            this.namespaces = namespaces;
        }

        static Normaliser choose(Path home, Modaccel.Options options, Map<String, Object> a, Map<String, Object> b) throws Exception {
            String va = Json.text(Json.object(a.getOrDefault("target", Map.of())), "minecraft", ""), vb = Json.text(Json.object(b.getOrDefault("target", Map.of())), "minecraft", "");
            boolean across = options.has("across-versions") || !va.equals(vb);
            if (!across) return new Normaliser("exact", Set.of());
            Set<String> namespaces = new TreeSet<>();
            for (Map<String, Object> side : List.of(a, b)) {
                Map<String, Object> target = Json.object(side.getOrDefault("target", Map.of()));
                for (Object ns : Json.array(target.getOrDefault("namespaces", List.of()))) namespaces.add(ns.toString());
            }
            if (options.values.containsKey("namespaces")) namespaces = new TreeSet<>(Arrays.asList(options.get("namespaces", "").split(",")));
            Path rules = options.values.containsKey("map") ? Path.of(options.get("map", "")) : home.resolve("knowledge/fingerprint-normalise.json");
            return across(Json.read(rules), namespaces, va + " -> " + vb);
        }

        static Normaliser across(Map<String, Object> rules, Set<String> namespaces, String versions) {
            Normaliser n = new Normaliser("across versions " + versions + ", the mod's own " + namespaces, namespaces);
            Map<String, Object> across = Json.object(rules.get("acrossVersions"));
            String ns = namespaces.isEmpty() ? "(?!minecraft:)[a-z0-9_.-]+" : "(?:" + String.join("|", namespaces.stream().map(Pattern::quote).toList()) + ")";
            for (Object keep : Json.array(across.get("keep"))) n.keep.add(Pattern.compile(Json.text(Json.object(keep), "pattern", "").replace("{ns}", ns)));
            for (var rename : Json.object(across.getOrDefault("renames", Map.of())).entrySet()) n.renames.put(rename.getKey(), rename.getValue().toString());
            return n;
        }

        Map<String, String> apply(Map<String, String> leaves) {
            if (keep.isEmpty() && renames.isEmpty()) return leaves;
            Map<String, String> out = new TreeMap<>();
            for (var leaf : leaves.entrySet()) {
                String key = rename(leaf.getKey()), value = rename(leaf.getValue());
                for (Pattern pattern : keep) if (pattern.matcher(key).matches()) {
                    out.put(key, value);
                    break;
                }
            }
            return out;
        }

        String rename(String text) {
            for (var r : renames.entrySet()) if (text.contains(r.getKey())) text = text.replaceAll(Pattern.quote(r.getKey()) + "(?![a-z0-9_])", r.getValue());
            return text;
        }
    }
}
