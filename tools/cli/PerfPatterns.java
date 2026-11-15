import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The library of optimizations that were accepted by perf-optimize: knowledge/perf-patterns.json.
 * Each pattern has a symptom, a condition over a capture's per-mod attribution, so a later capture of
 * any mod that shows the same shape is pointed at the change that worked, the gain it measured and
 * the gate that proved it. Nothing here edits code; it only names what was proven elsewhere.
 */
final class PerfPatterns {
    static final Set<String> LISTS = Set.of("methods", "features", "threads");

    static Path file(Path home) {
        return home.resolve("knowledge/perf-patterns.json");
    }

    static List<Object> load(Path home) {
        Path file = file(home);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Json.array(Json.read(file).get("patterns"));
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /**
     * Whether one mod's attribution row shows the symptom: every clause holds. A clause sums the samples
     * of the entries in {@code in} (methods, features or threads) whose name matches the regex and
     * compares that with the mod's own samples.
     */
    static Map<String, Object> evidence(Map<String, Object> symptom, Map<String, Object> mod) {
        double total = ((Number) mod.getOrDefault("samples", 0)).doubleValue();
        if (total <= 0) return null;
        List<Object> found = new ArrayList<>();
        for (Object item : Json.array(symptom.get("when"))) {
            Map<String, Object> clause = Json.object(item);
            Pattern pattern = Pattern.compile(clause.get("matches").toString());
            double samples = 0;
            for (Object entry : Json.array(mod.getOrDefault(clause.get("in").toString(), List.of()))) {
                Map<String, Object> row = Json.object(entry);
                if (pattern.matcher(String.valueOf(row.get("name"))).find()) samples += ((Number) row.getOrDefault("samples", 0)).doubleValue();
            }
            double share = samples / total;
            double needed = ((Number) clause.getOrDefault("minShare", 0)).doubleValue();
            if (samples == 0 || share < needed) return null;
            found.add(Json.map("in", clause.get("in"), "matches", clause.get("matches"), "share", share, "minShare", needed));
        }
        return found.isEmpty() ? null : Json.map("clauses", found);
    }

    /** Every pattern whose symptom one of the capture's mods shows, the mod's own first. */
    static List<Object> match(List<Object> patterns, Map<String, Object> attribution) {
        List<Object> matches = new ArrayList<>();
        List<Object> mods = Json.array(attribution.getOrDefault("mods", List.of()));
        for (int m = 0; m < mods.size() && m < 12; m++) {
            Map<String, Object> mod = Json.object(mods.get(m));
            for (Object item : patterns) {
                Map<String, Object> pattern = Json.object(item);
                Map<String, Object> found;
                try {
                    found = evidence(Json.object(pattern.get("symptom")), mod);
                } catch (RuntimeException malformed) {
                    continue;
                }
                if (found == null) continue;
                Map<String, Object> proven = Json.object(pattern.getOrDefault("provenIn", Map.of()));
                matches.add(Json.map("pattern", pattern.get("id"), "mod", mod.get("id"), "yourMod", mod.get("yourMod"),
                    "symptom", Json.object(pattern.get("symptom")).get("text"), "change", pattern.get("change"),
                    "gain", pattern.get("gain"), "gate", Json.object(pattern.getOrDefault("gate", Map.of())).get("name"),
                    "provenIn", proven.get("mod") + " " + proven.getOrDefault("version", "") + " · " + proven.getOrDefault("scope", ""),
                    "evidence", found.get("clauses")));
            }
        }
        return matches;
    }

    /** Problems with the library; empty when every pattern is complete and its own test passes. */
    static List<String> validate(Map<String, Object> library, Set<String> acceptedRecords) {
        List<String> problems = new ArrayList<>();
        if (!Long.valueOf(1).equals(library.get("schema")) && !Integer.valueOf(1).equals(library.get("schema"))) problems.add("schema must be 1");
        Set<String> ids = new HashSet<>();
        for (Object item : Json.array(library.getOrDefault("patterns", List.of()))) {
            Map<String, Object> p = Json.object(item);
            String id = Json.text(p, "id", "");
            String where = "pattern " + (id.isBlank() ? "(no id)" : id) + ": ";
            if (!id.matches("[a-z0-9]+(-[a-z0-9]+)*")) problems.add(where + "id must be kebab-case");
            if (!ids.add(id)) problems.add(where + "duplicate id");
            Map<String, Object> symptom = p.get("symptom") instanceof Map<?, ?> s ? Json.object(s) : Map.of();
            if (Json.text(symptom, "text", "").isBlank()) problems.add(where + "symptom.text says what the perf report shows");
            List<Object> clauses = symptom.get("when") instanceof List<?> w ? Json.array(w) : List.of();
            if (clauses.isEmpty()) problems.add(where + "symptom.when needs at least one clause");
            for (Object c : clauses) {
                Map<String, Object> clause = Json.object(c);
                if (!LISTS.contains(Json.text(clause, "in", ""))) problems.add(where + "clause in must be methods, features or threads");
                try {
                    Pattern.compile(Json.text(clause, "matches", ""));
                } catch (RuntimeException bad) {
                    problems.add(where + "clause matches is not a regex: " + bad.getMessage());
                }
                if (Json.text(clause, "matches", "").isBlank()) problems.add(where + "clause needs matches");
                if (!(clause.get("minShare") instanceof Number share) || share.doubleValue() <= 0 || share.doubleValue() > 1) problems.add(where + "clause minShare must be in (0, 1]");
            }
            if (Json.text(p, "change", "").isBlank()) problems.add(where + "change says what was changed");
            Map<String, Object> gain = p.get("gain") instanceof Map<?, ?> g ? Json.object(g) : Map.of();
            if (Json.text(gain, "scope", "").isBlank()) problems.add(where + "gain.scope names what was measured");
            for (String phase : List.of("cold", "warm")) {
                Map<String, Object> s = gain.get(phase) instanceof Map<?, ?> x ? Json.object(x) : Map.of();
                if (!(s.get("geometricMean") instanceof Number) || !(s.get("low") instanceof Number low) || !(s.get("high") instanceof Number)) {
                    problems.add(where + "gain." + phase + " needs geometricMean, low and high");
                } else if (low.doubleValue() <= 1) {
                    problems.add(where + "gain." + phase + ".low must be above 1: only accepted changes are patterns");
                }
            }
            Map<String, Object> gate = p.get("gate") instanceof Map<?, ?> g ? Json.object(g) : Map.of();
            if (Json.text(gate, "name", "").isBlank() || Json.text(gate, "compare", "").isBlank()) problems.add(where + "gate needs name and compare");
            Map<String, Object> proven = p.get("provenIn") instanceof Map<?, ?> g ? Json.object(g) : Map.of();
            for (String field : List.of("mod", "version", "loader", "scope", "record")) {
                if (Json.text(proven, field, "").isBlank()) problems.add(where + "provenIn." + field + " is required");
            }
            if (!Json.text(proven, "record", "").isBlank() && !acceptedRecords.contains(proven.get("record").toString())) {
                problems.add(where + "provenIn.record " + proven.get("record") + " is not an accepted run in coverage/perf-runs.json");
            }
            Map<String, Object> test = p.get("test") instanceof Map<?, ?> t ? Json.object(t) : Map.of();
            if (!(test.get("fires") instanceof Map<?, ?> fires) || !(test.get("quiet") instanceof Map<?, ?> quiet)) {
                problems.add(where + "test needs fires and quiet: two attribution rows, one showing the symptom and one not");
            } else if (clauses.size() > 0 && problems.stream().noneMatch(text -> text.startsWith(where + "clause"))) {
                if (evidence(symptom, Json.object(fires)) == null) problems.add(where + "test.fires does not match the symptom");
                if (evidence(symptom, Json.object(quiet)) != null) problems.add(where + "test.quiet matches the symptom");
            }
        }
        return problems;
    }

    /** Ids of the accepted runs in coverage/perf-runs.json, which patterns cite as their proof. */
    static Set<String> acceptedRecords(Path home) throws Exception {
        Path ledger = home.resolve("coverage/perf-runs.json");
        Set<String> ids = new HashSet<>();
        if (!Files.isRegularFile(ledger)) return ids;
        for (Object item : Json.array(Json.read(ledger).get("runs"))) {
            Map<String, Object> run = Json.object(item);
            if ("accepted".equals(run.get("verdict"))) ids.add(run.get("id").toString());
        }
        return ids;
    }

    static String text(List<Object> matches) {
        StringBuilder out = new StringBuilder();
        for (Object item : matches) {
            Map<String, Object> m = Json.object(item);
            out.append("\nKnown pattern ").append(m.get("pattern")).append(" in ").append(m.get("mod")).append(": ").append(m.get("change"))
                .append(" (proven in ").append(m.get("provenIn")).append(")");
        }
        return out.toString();
    }
}
