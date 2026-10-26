import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Hub-and-spoke route through the change index (coverage/changes/index.json): X, then every family hub
 * strictly between X and Y in walking order, then Y. Each hop lists the neighbouring updates it crosses, so
 * one port can cross several families and still say which update changed what. A backport walks the same
 * updates in reverse.
 */
final class Route {
    static final String FIRST_MAPPED = "1.14.4", FIRST_UNOBFUSCATED = "26.1";
    record Hop(String from, String to, List<String> steps) {}

    final String from, to;
    final List<String> versions = new ArrayList<>(), waypoints = new ArrayList<>();
    final List<Hop> hops = new ArrayList<>();
    final Map<String, String> linked = new HashMap<>(); // "a -> b" (forward order) -> exact | by-name

    private Route(String from, String to) { this.from = from; this.to = to; }

    static Path index(Path harness) { return harness.resolve("coverage/changes/index.json"); }

    /** A precise refusal for releases with no official names, or null when the versions are portable. */
    static Map<String, Object> blocked(Path harness, String from, String to) throws IOException {
        if (from.isBlank() || to.isBlank()) return null;
        for (String version : List.of(from, to)) {
            String reason = blockedReason(harness, version);
            if (reason != null) return Json.map("ok", false, "tier", "blocked", "sourcesChanged", false, "gameVerified", false,
                    "blocked", List.of(Json.map("version", version, "reason", reason)),
                    "details", "Blocked: Minecraft " + version + " is before " + FIRST_MAPPED + ". " + reason
                            + " modaccel ports between " + FIRST_MAPPED + " and the newest indexed release; nothing was changed.");
        }
        for (String version : List.of(from, to)) {
            String unknown = unknownRelease(harness, version);
            if (unknown != null) return Json.map("ok", false, "tier", "blocked", "sourcesChanged", false, "gameVerified", false,
                    "blocked", List.of(Json.map("version", version, "reason", unknown)), "reason", unknown, "details", unknown);
        }
        return null;
    }

    /**
     * A plain release number that is older than the newest release modaccel knows but is not one of
     * them is a typo (1.21.66, 26.0): say so before any build or download. Newer numbers pass, since
     * the list can be behind a fresh release; snapshots and pre-releases pass too.
     */
    static String unknownRelease(Path harness, String version) throws IOException {
        if (!version.matches("\\d+(\\.\\d+)+")) return null;
        Path index = index(harness);
        if (!Files.isRegularFile(index)) return null;
        List<String> known = new ArrayList<>();
        for (Object item : Json.array(Json.read(index).get("allVersions"))) known.add(String.valueOf(Json.object(item).get("id")));
        if (known.isEmpty() || known.contains(version) || known.contains(version.replaceFirst("\\.0$", ""))) return null;
        String newest = known.get(known.size() - 1);
        if (Versions.compare(version, newest) > 0) return null;
        List<String> near = new ArrayList<>();
        for (String id : known) if (Versions.compare(id, version) < 0) { near.clear(); near.add(id); }
        for (String id : known) if (Versions.compare(id, version) > 0) { near.add(id); break; }
        return "Minecraft " + version + " is not a Java Edition release (modaccel's list runs from " + known.get(0) + " to " + newest + "). "
                + "Check the number; the nearest releases are " + String.join(" and ", near) + ". Nothing was changed.";
    }

    static String blockedReason(Path harness, String version) throws IOException {
        if (!version.matches("\\d+(\\.\\d+)*")) return null;
        Path index = index(harness);
        if (Files.isRegularFile(index)) {
            for (Object item : Json.array(Json.read(index).get("blocked"))) {
                var row = Json.object(item);
                if (version.equals(row.get("id"))) return String.valueOf(row.get("reason"));
            }
        }
        if (Versions.compare(version, FIRST_MAPPED) < 0)
            return "Mojang published no official mappings before " + FIRST_MAPPED + " and Fabric intermediary ids start there, so no proven name links this release to newer ones (it would need MCP or other legacy community mappings, which modaccel does not use).";
        return null;
    }

    /** The route, or null when either release is not in the index (the caller then uses the one-jump map). */
    static Route plan(Path harness, String from, String to) throws IOException {
        Path index = index(harness);
        if (!Files.isRegularFile(index)) return null;
        var data = Json.read(index);
        List<String> all = new ArrayList<>();
        for (Object item : Json.array(data.get("versions"))) all.add(String.valueOf(Json.object(item).get("id")));
        int i = all.indexOf(from), j = all.indexOf(to);
        if (i < 0 || j < 0) return null;
        Route route = new Route(from, to);
        for (Object item : Json.array(data.get("steps"))) {
            var step = Json.object(item);
            route.linked.put(step.get("from") + " -> " + step.get("to"), String.valueOf(step.get("linked")));
        }
        int direction = i <= j ? 1 : -1;
        for (int k = i; ; k += direction) { route.versions.add(all.get(k)); if (k == j) break; }
        Set<String> hubs = new HashSet<>();
        for (Object item : Json.array(Json.object(data.get("families")).get("list"))) {
            var family = Json.object(item);
            if (!Boolean.TRUE.equals(family.get("estimated"))) hubs.add(String.valueOf(family.get("hub")));
        }
        route.waypoints.add(from);
        for (int k = 1; k < route.versions.size() - 1; k++) if (hubs.contains(route.versions.get(k))) route.waypoints.add(route.versions.get(k));
        if (!from.equals(to)) route.waypoints.add(to);
        for (int w = 0; w + 1 < route.waypoints.size(); w++) {
            int a = route.versions.indexOf(route.waypoints.get(w)), b = route.versions.indexOf(route.waypoints.get(w + 1));
            List<String> steps = new ArrayList<>();
            for (int k = a; k < b; k++) steps.add(route.versions.get(k) + " -> " + route.versions.get(k + 1));
            route.hops.add(new Hop(route.waypoints.get(w), route.waypoints.get(w + 1), steps));
        }
        return route;
    }

    List<String> steps() { List<String> list = new ArrayList<>(); for (Hop hop : hops) list.addAll(hop.steps()); return list; }

    /** How the index links an update: exact (shared intermediary ids) or by-name (26.1 and newer). */
    String linkOf(String step) {
        String[] p = step.split(" -> ");
        return linked.getOrDefault(step, linked.getOrDefault(p[1] + " -> " + p[0], "unknown"));
    }

    /** The releases a rename map can be joined across: the leading run of releases before 26.1. */
    List<String> mappedChain() {
        List<String> chain = new ArrayList<>();
        for (String version : versions) if (Versions.compare(version, FIRST_UNOBFUSCATED) < 0) chain.add(version);
        return chain;
    }

    /** The hop that crosses one update. */
    Hop hopOf(String step) {
        for (Hop hop : hops) if (hop.steps().contains(step)) return hop;
        return null;
    }

    /**
     * Whether a knowledge rule's jump ("1.21.1 -> 1.21.4 (mojmap)") covers an update this route crosses and its
     * names fit the source. A rule with no readable jump applies everywhere, as before the router.
     */
    List<String> stepsCovered(String jump, String names) {
        if (jump == null || jump.isBlank()) return steps();
        String tag = jump.contains("(") ? jump.substring(jump.indexOf('(')).toLowerCase(Locale.ROOT) : "";
        if (tag.contains("yarn") && !names.equals("yarn")) return List.of();
        if ((tag.contains("mojmap") || tag.contains("official")) && names.equals("yarn")) return List.of();
        String[] range = jump.replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
        if (range.length != 2 || !range[0].matches("\\d[\\w.]*") || !range[1].matches("\\d[\\w.]*")) return steps();
        // A rule rewrites old code into new code, so a forward rule never runs on a backport (or the reverse).
        if (Versions.compare(range[0], range[1]) < 0 != Versions.compare(from, to) < 0) return List.of();
        String lo = Versions.compare(range[0], range[1]) <= 0 ? range[0] : range[1], hi = lo.equals(range[0]) ? range[1] : range[0];
        List<String> covered = new ArrayList<>();
        for (String step : steps()) {
            String[] p = step.split(" -> ");
            String a = Versions.compare(p[0], p[1]) <= 0 ? p[0] : p[1], b = a.equals(p[0]) ? p[1] : p[0];
            if (Versions.compare(lo, a) <= 0 && Versions.compare(b, hi) <= 0) covered.add(step);
        }
        return covered;
    }

    Map<String, Object> record() {
        List<Object> list = new ArrayList<>();
        for (Hop hop : hops) {
            List<Object> steps = new ArrayList<>();
            for (String step : hop.steps()) steps.add(Json.map("step", step, "linked", linkOf(step)));
            list.add(Json.map("from", hop.from(), "to", hop.to(), "steps", steps));
        }
        return Json.map("from", from, "to", to, "waypoints", waypoints, "versions", versions, "hops", list,
                "rule", "X, then each family hub between X and Y, then Y (coverage/changes/index.json); one record per hop.");
    }
}
