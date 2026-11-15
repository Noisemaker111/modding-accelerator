import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * pack-check's checks, as a registry. Each check has an id (the kind of finding it reports), the stage it runs at,
 * and a body that reads what that stage has: "static" the jars' metadata, before any game runs; "new-world" the first
 * server start, which makes a world; "reopen" the second start, which opens that world again; "user-world" a copy of
 * the user's own save. A new check is one more entry in ALL. Findings are maps with kind, severity (problem or note),
 * summary and evidence. What the jars' metadata predicts (a missing or wrong-version dependency, a duplicate id, a mod
 * for another loader) is a warning until the game decides it: "loader-verdict" makes it a problem when the loader
 * stopped on that jar, and a note when the loader loaded it anyway. Only problems fail the run, unless a proven repair
 * clears them.
 */
final class PackChecks {

    private PackChecks() {}

    interface Body {
        void run(Context context) throws Exception;
    }

    record Check(String id, String stage, String what, Body body) {}

    /** What a check can read. start is the stage's server start; first is start 1 when the stage is the reopen. */
    static final class Context {

        final PackCheck.Pack pack;
        final PackMods.Scan scan;
        final PackLoaders.Loader loader;
        final List<Object> found;
        Map<String, Object> start = Map.of(), first = Map.of();
        /** The jars the server was started with (the pack's, less client-only ones). */
        Set<String> onServer = Set.of();

        Context(PackCheck.Pack pack, PackMods.Scan scan, PackLoaders.Loader loader, List<Object> found) {
            this.pack = pack;
            this.scan = scan;
            this.loader = loader;
            this.found = found;
        }

        Map<String, Object> probe() {
            return Json.object(start.getOrDefault("probe", Map.of()));
        }

        boolean opened() {
            return Boolean.TRUE.equals(start.get("opened"));
        }

        void add(String kind, String severity, String summary, Object... more) {
            var finding = Json.map("kind", kind, "severity", severity, "summary", summary);
            for (int i = 0; i + 1 < more.length; i += 2) finding.put(String.valueOf(more[i]), more[i + 1]);
            found.add(finding);
        }
    }

    static final List<Check> ALL = List.of(
        new Check("unreadable-jar", "static", "a file in mods/ that is not a readable jar", PackChecks::unreadable),
        new Check("loader-mismatch", "static", "a mod written for another loader than the pack's", PackChecks::foreign),
        new Check("duplicate-mod", "static", "one mod id in two jars", PackChecks::duplicates),
        new Check("missing-mod", "static", "a required mod no jar provides", PackChecks::missing),
        new Check("dependency-version", "static", "a required mod present in a version its dependant does not accept", PackChecks::versions),
        new Check("minecraft-mismatch", "static", "a mod that does not accept the pack's game version", PackChecks::minecraft),
        new Check("loader-version", "static", "a mod that does not accept the pack's loader version", PackChecks::loaderVersion),
        new Check("declared-conflict", "static", "two mods, one of which says it breaks the other", PackChecks::conflicts),
        new Check("same-block-file", "static", "two mods ship the same block look or drop file", PackChecks::sameBlockFiles),
        new Check("same-block-name", "static", "blocks from two mods with the same English name", PackChecks::sameBlockNames),
        new Check("server-start", "new-world", "the server did not start a new world; the log names the mod", PackChecks::serverStart),
        new Check("loader-verdict", "new-world", "what the metadata predicted, confirmed or refuted by the loader on the real server", PackChecks::verdict),
        new Check("dimension-save", "new-world", "a dimension that saves in a form the next start rejects", PackChecks::dimensions),
        new Check("placed-other-block", "new-world", "a block item that places another block", PackChecks::placements),
        new Check("world-reopen", "reopen", "a world that opens once and not again; the log names the mod", PackChecks::reopen),
        new Check("changed-by-save", "reopen", "a placed block that is different after saving and reopening", PackChecks::changedBySave),
        new Check("user-world", "user-world", "your own save (a copy) does not open, or saves badly", PackChecks::userWorld)
    );

    static void run(String stage, Context context) throws Exception {
        for (Check check : ALL) if (check.stage().equals(stage)) check.body().run(context);
    }

    // ------------------------------------------------------------------ before the game
    static void unreadable(Context c) {
        for (String jar : c.scan.unreadable) c.add("unreadable-jar", "problem", jar + " is not a readable jar.");
        if (!c.scan.notMods.isEmpty()) c.add(
            "not-a-mod",
            "note",
            c.scan.notMods.size() + " jar(s) in mods/ carry no loader metadata, so no loader loads them as mods: " + examples(c.scan.notMods),
            "jars",
            c.scan.notMods
        );
    }

    static void foreign(Context c) {
        for (var entry : c.scan.foreign.entrySet()) c.add(
            "loader-mismatch",
            "warning",
            entry.getKey() + " is written for another loader (" + String.join(", ", entry.getValue()) + "); " + c.loader.id() + " " +
            c.pack.minecraft() + " reads " + c.loader.metadata(c.pack.minecraft()) + ", so this mod is not loaded.",
            "jars",
            List.of(entry.getKey())
        );
    }

    static void duplicates(Context c) {
        for (var entry : c.scan.providers.entrySet()) {
            List<String> top = entry.getValue().stream().filter(p -> !p.bundled()).map(PackMods.Provider::jar).distinct().toList();
            if (top.size() > 1) c.add(
                "duplicate-mod",
                "warning",
                "Mod id " + entry.getKey() + " is in " + top.size() + " jars; " + c.loader.id() + " refuses to start with both.",
                "jars",
                top
            );
        }
    }

    static void missing(Context c) {
        Map<String, List<String>> needed = new TreeMap<>();
        Set<String> builtIn = c.loader.builtIn();
        for (var d : c.scan.dependencies) if (
            d.kind().equals("required") && !c.scan.providers.containsKey(d.id()) && !builtIn.contains(d.id()) && !isLoader(c, d.id())
        ) needed.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(d.owner());
        for (var entry : needed.entrySet()) c.add(
            "missing-mod",
            "warning",
            entry.getValue().stream().distinct().toList() + " require mod id " + entry.getKey() + ", which no jar in the pack provides.",
            "mod",
            entry.getKey(),
            "jars",
            entry.getValue().stream().distinct().toList()
        );
    }

    static void versions(Context c) {
        for (var d : c.scan.dependencies) {
            if (!d.kind().equals("required") || isLoader(c, d.id()) || d.id().equals("minecraft") || d.id().equals("java")) continue;
            PackMods.Provider used = c.scan.used(d.id());
            if (used == null || used.version().isBlank() || PackMods.accepts(d, used.version())) continue;
            c.add(
                "dependency-version",
                "warning",
                d.owner() + " needs " + d.id() + " " + String.join(" or ", d.ranges()) + "; the pack has " + used.version() + " (from " + used.jar() + ").",
                "mod",
                d.id(),
                "jars",
                List.of(d.owner(), used.jar())
            );
        }
    }

    static void minecraft(Context c) {
        for (var d : c.scan.dependencies) if (
            d.kind().equals("required") && d.id().equals("minecraft") && !d.owner().contains("!") && !PackMods.accepts(d, c.pack.minecraft())
        ) c.add(
            "minecraft-mismatch",
            "warning",
            d.owner() + " is for Minecraft " + String.join(" or ", d.ranges()) + ", not " + c.pack.minecraft() + ".",
            "jars",
            List.of(d.owner())
        );
    }

    static void loaderVersion(Context c) {
        if (c.pack.loaderVersion().isBlank()) return;
        for (var d : c.scan.dependencies) if (
            d.kind().equals("required") && isLoader(c, d.id()) && !d.owner().contains("!") && !PackMods.accepts(d, c.pack.loaderVersion())
        ) c.add(
            "loader-version",
            "warning",
            d.owner() + " needs " + d.id() + " " + String.join(" or ", d.ranges()) + "; the pack runs " + c.loader.id() + " " + c.pack.loaderVersion() + ".",
            "jars",
            List.of(d.owner())
        );
    }

    static void conflicts(Context c) {
        for (var d : c.scan.dependencies) {
            if (!d.kind().equals("breaks")) continue;
            PackMods.Provider used = c.scan.used(d.id());
            if (used == null || !PackMods.accepts(d, used.version().isBlank() ? "0" : used.version())) continue;
            c.add(
                "declared-conflict",
                "warning",
                d.owner() + " says it breaks " + d.id() + " " + String.join(" or ", d.ranges()) + ", and the pack has " + used.version() + " (from " + used.jar() + ").",
                "jars",
                List.of(d.owner(), used.jar())
            );
        }
    }

    /** The loader's own id (fabricloader, neoforge, forge, quilt_loader) is checked against the loader version, not as a mod. */
    static boolean isLoader(Context c, String id) {
        return switch (c.loader.id()) {
            case "fabric" -> id.equals("fabricloader");
            case "quilt" -> id.equals("quilt_loader") || id.equals("fabricloader");
            case "forge" -> id.equals("forge");
            case "neoforge" -> id.equals("neoforge");
            default -> false;
        };
    }

    static void sameBlockFiles(Context c) {
        // One note per set of jars that ship the same look or drop files: only one copy of each is used.
        Map<List<String>, List<String>> shared = new TreeMap<>(Comparator.comparing(Object::toString));
        for (var entry : c.scan.blockFiles.entrySet()) {
            List<String> jars = entry.getValue().stream().distinct().sorted().toList();
            if (jars.size() > 1) shared.computeIfAbsent(jars, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (var entry : shared.entrySet()) c.add(
            "same-block-file",
            "note",
            String.join(" and ", entry.getKey()) + " both ship " + entry.getValue().size() +
            " block or item look/drop file(s); only one copy of each is used, so a block or item can look or drop like the other mod's: " +
            examples(entry.getValue()),
            "jars",
            entry.getKey(),
            "files",
            entry.getValue()
        );
    }

    static void sameBlockNames(Context c) {
        // Blocks from different mods with the same English name: a player can hold one while meaning the other.
        Map<String, Object> sameName = new TreeMap<>();
        for (var entry : c.scan.blockNames.entrySet()) if (
            entry.getValue().stream().map(id -> id.split(":")[0]).distinct().count() > 1
        ) sameName.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        if (sameName.isEmpty()) return;
        List<String> shown = new ArrayList<>();
        for (var entry : sameName.entrySet()) shown.add("\"" + entry.getKey() + "\" " + entry.getValue());
        c.add(
            "same-block-name",
            "note",
            sameName.size() + " block name(s) belong to blocks from two or more mods, so a player can place one while expecting the other: " + examples(shown),
            "names",
            sameName
        );
    }

    // ------------------------------------------------------------------ in the game
    static void serverStart(Context c) {
        if (c.opened()) return;
        String failure = String.valueOf(c.start.get("failure"));
        List<Map<String, Object>> suspects = Json.array(c.start.getOrDefault("suspects", List.of())).stream().map(Json::object).toList();
        boolean clientOnly = c.loader.clientOnly().stream().anyMatch(failure::contains);
        String named = names(suspects);
        String summary = clientOnly
            ? "A client-only mod stopped the pack's server" +
            (named.isEmpty() ? " and the log names none. Neither CurseForge nor Modrinth knows the sides of the mods listed here; one of them is the likely cause." : ": " + named + ".")
            : "The pack's server did not finish starting a new world." + (named.isEmpty() ? " The log names no mod." : " The log names " + named + ".");
        c.add(
            "server-start",
            "problem",
            summary,
            "evidence",
            failure,
            "suspects",
            suspects,
            "unknownSides",
            clientOnly ? c.pack.mods().stream().filter(m -> m.side().equals("unknown")).map(PackCheck.Mod::file).toList() : List.of()
        );
    }

    /**
     * The game's verdict on each metadata warning. A start that failed and names the jar's mod confirms it (a problem);
     * a start that loaded the jar's mods refutes it (a note: the loader accepted it); a jar the server never had (a
     * client-only mod) stays a warning, since only a client would show it.
     */
    static void verdict(Context c) {
        Set<String> loaded = new HashSet<>();
        for (Object m : Json.array(c.probe().getOrDefault("mods", List.of()))) loaded.add(String.valueOf(m).split(" ")[0]);
        Set<String> blamed = new HashSet<>();
        for (Object s : Json.array(c.start.getOrDefault("suspects", List.of()))) blamed.add(Json.text(Json.object(s), "jar", "").split("!")[0]);
        for (Object f : c.found) {
            var finding = Json.object(f);
            if (!"warning".equals(finding.get("severity")) || !(finding.get("jars") instanceof List<?> jars) || jars.isEmpty()) continue;
            String jar = String.valueOf(jars.get(0)).split("!")[0];
            if (!c.opened() && blamed.contains(jar)) {
                finding.put("severity", "problem");
                finding.put("game", "confirmed: the server stopped and its log names " + jar);
            } else if (c.opened() && c.onServer.contains(jar) && !c.scan.idsOf(jar).isEmpty() && loaded.containsAll(c.scan.idsOf(jar))) {
                finding.put("severity", "note");
                finding.put("game", "refuted: " + c.loader.id() + " loaded " + c.scan.idsOf(jar) + " from " + jar + " anyway");
            } else if (!c.onServer.contains(jar)) finding.put("game", "not checked in the game: " + jar + " is client-only, so only a client would show it");
        }
    }

    static void dimensions(Context c) {
        if (c.opened()) dimensionRows(c, c.probe());
    }

    static void dimensionRows(Context c, Map<String, Object> probe) {
        for (Object d : Json.array(probe.get("dimensions"))) {
            var dimension = Json.object(d);
            if (dimension.get("write") == null && dimension.get("read") == null && dimension.get("error") == null) continue;
            List<Object> biomes = Json.array(dimension.getOrDefault("outOfRangeBiomes", List.of()));
            Set<String> mods = new TreeSet<>();
            for (Object b : biomes) mods.add(String.valueOf(b).split(":", 2)[0]);
            String what = dimension.get("write") != null
                ? "cannot be saved so the next start can read it"
                : "is saved in a form the next start rejects";
            c.add(
                "dimension-save",
                "problem",
                dimension.get("dimension") + " " + what + "; the world will not open again" +
                (mods.isEmpty() ? "." : ". Biomes from " + String.join(", ", mods) + " carry climate values outside -2..2: " + biomes),
                "dimension",
                dimension.get("dimension"),
                "evidence",
                Json.map("write", dimension.get("write"), "read", dimension.get("read"), "part", dimension.get("failingPart"), "error", dimension.get("error"))
            );
        }
    }

    static void placements(Context c) {
        if (!c.opened()) return;
        var probe = c.probe();
        // The world opened but pack-check's own probe stopped before placing: say so, as a gap of the check, not the pack.
        if (probe.get("error") != null && probe.get("different") == null) {
            c.add(
                "not-checked",
                "note",
                "pack-check's probe stopped before placing blocks, so block placement was not checked: " +
                String.valueOf(probe.get("error")).lines().findFirst().orElse(""),
                "evidence",
                String.valueOf(probe.get("error"))
            );
            return;
        }
        for (Object d : Json.array(probe.getOrDefault("different", List.of()))) {
            var row = Json.object(d);
            String item = Json.text(row, "item", ""), expected = Json.text(row, "expected", ""), landed = Json.text(row, "landed", "");
            String itemMod = item.split(":")[0], landedMod = landed.split(":")[0];
            boolean otherMod = !landedMod.equals(itemMod) && !landedMod.equals(expected.split(":")[0]) && !landedMod.equals("minecraft");
            c.add(
                "placed-other-block",
                otherMod ? "problem" : "note",
                "Placing " + item + " put down " + landed + " instead of " + expected +
                (otherMod ? ": a block from another mod." : ": the item's own mod does this (a growth stage or a deliberate swap)."),
                "evidence",
                row
            );
        }
        // Items that need a wall, water, a ceiling or more room never landed: say they went unchecked.
        List<Object> unplaced = Json.array(probe.getOrDefault("unplaced", List.of()));
        if (!unplaced.isEmpty()) {
            List<String> items = new ArrayList<>();
            for (Object u : unplaced) items.add(Json.text(Json.object(u), "item", ""));
            c.add(
                "not-checked",
                "note",
                unplaced.size() + " block item(s) did not place on stone, grass, farmland, sand, a wall, a ceiling or in water, so the swap check did not cover them" +
                " (some need other ground, darkness or an operator; a mod can also refuse a placement on purpose): " +
                String.join(", ", items.subList(0, Math.min(12, items.size()))) + (items.size() > 12 ? " ..." : ""),
                "items",
                items
            );
        }
        List<Object> failed = Json.array(probe.getOrDefault("failed", List.of()));
        if (!failed.isEmpty()) {
            List<String> items = new ArrayList<>();
            for (Object f : failed) items.add(Json.text(Json.object(f), "item", ""));
            c.add(
                "placement-error",
                "note",
                failed.size() + " block item(s) threw an error when a fake player placed them (a real player may differ): " +
                String.join(", ", items.subList(0, Math.min(12, items.size()))) + (items.size() > 12 ? " ..." : ""),
                "evidence",
                Json.text(Json.object(failed.get(0)), "error", "").lines().findFirst().orElse(""),
                "items",
                items
            );
        }
        if (probe.get("error") != null) c.add(
            "probe-error",
            "problem",
            "The world opened but checking it stopped with an error.",
            "evidence",
            String.valueOf(probe.get("error")).lines().limit(8).toList()
        );
    }

    static void reopen(Context c) {
        if (c.opened()) return;
        List<Map<String, Object>> suspects = Json.array(c.start.getOrDefault("suspects", List.of())).stream().map(Json::object).toList();
        String named = names(suspects);
        c.add(
            "world-reopen",
            "problem",
            "A world this pack made opens once and then fails to open again." + (named.isEmpty() ? "" : " The log names " + named + "."),
            "evidence",
            c.start.get("failure"),
            "suspects",
            suspects
        );
    }

    static void changedBySave(Context c) {
        if (!c.opened()) return;
        for (Object change : Json.array(c.probe().get("changed"))) c.add(
            "changed-by-save",
            "problem",
            "Saving and reopening changed a block: " + Json.text(Json.object(change), "before", "") + " became " + Json.text(Json.object(change), "after", ""),
            "evidence",
            change
        );
    }

    static void userWorld(Context c) {
        if (c.opened()) {
            dimensionRows(c, c.probe());
            return;
        }
        c.add(
            "user-world",
            "problem",
            "Your save does not open with this pack (checked on a copy; the original is untouched)." +
            (names(Json.array(c.start.getOrDefault("suspects", List.of())).stream().map(Json::object).toList()).isEmpty()
                ? ""
                : " The log names " + names(Json.array(c.start.get("suspects")).stream().map(Json::object).toList()) + "."),
            "evidence",
            c.start.get("failure")
        );
    }

    // ------------------------------------------------------------------ the log: which mod?
    static final Pattern FRAME = Pattern.compile("^\\s*at (?:[\\w.$-]+//)?(?:[A-Z]+/([a-z0-9_.\\-]+)@[^/]+/)?([\\w$.]+)\\.[\\w$<>]+\\(");
    static final Pattern MIXIN_CONFIG = Pattern.compile("([A-Za-z0-9_.\\-]+\\.mixins?\\.json|[A-Za-z0-9_.\\-]+\\.mixin\\.json|mixins\\.[A-Za-z0-9_.\\-]+\\.json)");
    static final Pattern HANDLER = Pattern.compile("\\$[a-z]{2,}[0-9a-f]{3}\\$([a-z0-9_]+)\\$");
    static final Pattern SUSPECTED = Pattern.compile("^\\s*([^\\n(]+) \\(([a-z0-9_.\\-]+)\\)");

    /**
     * The mods a failed start's log and crash reports point at, most direct first: the loader naming a mod, a crash
     * report's "Suspected Mods", a mixin config of a mod, a mixin handler carrying a mod id, then the first frame of each
     * cause's stack that runs a pack jar's code. Each comes with why it is named.
     */
    static List<Map<String, Object>> suspects(String log, List<String> reports, PackMods.Scan scan, PackLoaders.Loader loader) {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        // A server that logged Done finished its start: the start-up lines before it (mixin configs, mods loading) blame nobody.
        // Only what failed after it, or a crash report, can name a mod.
        String done = startedLine(log);
        if (done != null) {
            String after = log.substring(log.indexOf(done) + done.length());
            if (reports.isEmpty() && !FAILED_AFTER_START.matcher(after).find()) return List.of();
            log = after;
        }
        String all = plain(log + "\n" + String.join("\n", reports));
        for (Pattern named : loader.modNamed()) {
            Matcher m = named.matcher(all);
            while (m.find()) blame(found, scan, m.group(1), null, "the loader names it: " + line(all, m.start()));
        }
        for (String report : reports) {
            List<String> lines = report.lines().toList();
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches("\\s*Suspected Mods?:.*")) {
                for (int j = i + 1; j < lines.size() && !lines.get(j).isBlank(); j++) {
                    Matcher m = SUSPECTED.matcher(lines.get(j));
                    if (m.find() && !m.group(2).equals("minecraft")) blame(found, scan, m.group(2), null, "the crash report suspects it");
                }
            }
        }
        Matcher config = MIXIN_CONFIG.matcher(all);
        while (config.find()) {
            String jar = scan.mixins.get(config.group(1));
            if (jar != null) blame(found, scan, null, jar, "its mixin config " + config.group(1) + " is in the failure");
        }
        Matcher handler = HANDLER.matcher(all);
        while (handler.find()) if (scan.providers.containsKey(handler.group(1))) blame(
            found,
            scan,
            handler.group(1),
            null,
            "a mixin handler of this mod is in the stack"
        );
        // Each cause (the deepest last): its first frame in a pack jar.
        List<String> lines = all.lines().toList();
        boolean inCause = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Caused by:") || line.contains("Exception") && !line.startsWith("\t")) {
                inCause = true;
                continue;
            }
            Matcher frame = FRAME.matcher(line);
            if (!inCause || !frame.find()) continue;
            String module = frame.group(1), type = frame.group(2);
            if (type.startsWith("modaccel.packprobe")) continue;
            String jar = module != null && scan.providers.containsKey(module) ? null : owner(scan, type);
            if (module != null && scan.providers.containsKey(module)) {
                blame(found, scan, module, null, "its code (" + type + ") is in the stack: " + line.strip());
                inCause = false;
            } else if (jar != null) {
                blame(found, scan, null, jar, "its code (" + type + ") is in the stack: " + line.strip());
                inCause = false;
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Minecraft's colour codes (a section sign and one character), which Forge puts round mod names; a log read in the wrong code page shows the sign as U+FFFD. */
    static String plain(String text) {
        return text.replaceAll("[\u00a7\ufffd][0-9a-fk-orA-FK-OR]", "");
    }

    static String owner(PackMods.Scan scan, String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? null : scan.packages.get(type.substring(0, dot));
    }

    static void blame(Map<String, Map<String, Object>> found, PackMods.Scan scan, String id, String jar, String why) {
        if (id == null && jar != null) {
            List<String> ids = scan.idsOf(jar);
            id = ids.isEmpty() ? jar : ids.get(0);
        }
        if (id == null || id.equals("modaccel_pack_probe") || id.equals("minecraft")) return;
        if (jar == null) {
            List<PackMods.Provider> providers = scan.providers.getOrDefault(id, List.of());
            jar = providers.stream().filter(p -> !p.bundled()).map(PackMods.Provider::jar).findFirst().orElse(
                providers.isEmpty() ? "" : providers.get(0).jar()
            );
        }
        if (jar.startsWith("zz-modaccel")) return;
        Map<String, Object> suspect = found.computeIfAbsent(id, k -> new LinkedHashMap<>());
        suspect.put("mod", id);
        suspect.put("jar", jar);
        List<Object> reasons = Json.array(suspect.computeIfAbsent("why", k -> new ArrayList<>()));
        if (reasons.size() < 3 && !reasons.contains(why)) reasons.add(why.length() > 300 ? why.substring(0, 300) + "..." : why);
    }

    static String line(String text, int at) {
        int start = text.lastIndexOf('\n', at) + 1, end = text.indexOf('\n', at);
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }

    static String names(List<Map<String, Object>> suspects) {
        List<String> shown = new ArrayList<>();
        for (var s : suspects.subList(0, Math.min(4, suspects.size()))) shown.add(s.get("mod") + (Json.text(s, "jar", "").isEmpty() ? "" : " (" + s.get("jar") + ")"));
        return String.join(", ", shown);
    }

    /**
     * Mod ids a failed start shows to be client-only: a block of the log (a line and the stack under it) that has the
     * loader's client-only words, and names a mod by the loader's own patterns or, failing that, by the first frame of
     * a pack jar in that block. A mod failing for any other reason is not counted: leaving it out would hide a real fault.
     */
    static Set<String> clientOnly(String log, PackLoaders.Loader loader, PackMods.Scan scan) {
        Set<String> ids = new LinkedHashSet<>();
        log = plain(log);
        if (loader.failed().stream().noneMatch(log::contains)) return ids;
        List<String> lines = log.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("[") && i > 0) continue;
            int end = i + 1;
            while (end < lines.size() && !lines.get(end).startsWith("[")) end++;
            String block = String.join("\n", lines.subList(i, end));
            if (loader.clientOnly().stream().noneMatch(block::contains)) continue;
            Set<String> named = new LinkedHashSet<>();
            for (Pattern pattern : loader.modNamed()) {
                Matcher m = pattern.matcher(lines.get(i));
                if (m.find()) named.add(m.group(1));
            }
            // Quilt and Fabric name the mod a few lines into the block ("... provided by 'lilac'!").
            if (named.isEmpty()) for (Pattern pattern : loader.modNamed()) {
                Matcher m = pattern.matcher(block);
                if (m.find() && scan.providers.containsKey(m.group(1))) {
                    named.add(m.group(1));
                    break;
                }
            }
            // A line of its own (a mod printing the error it caught) names no mod: only a mod's failure block counts.
            if (named.isEmpty() && end > i + 1) for (String line : lines.subList(i + 1, end)) {
                Matcher frame = FRAME.matcher(line);
                if (!frame.find()) continue;
                String jar = owner(scan, frame.group(2));
                if (frame.group(1) != null && scan.providers.containsKey(frame.group(1))) named.add(frame.group(1));
                else if (jar != null && !scan.idsOf(jar).isEmpty()) named.add(scan.idsOf(jar).get(0));
                if (!named.isEmpty()) break;
            }
            ids.addAll(named);
        }
        ids.remove("modaccel_pack_probe");
        return ids;
    }

    /** What stopped a start: the loader's or game's own words, and their causes. */
    /** The dedicated server's "Done (12.3s)! For help" line: it finished starting. Null when the log has none. */
    static String startedLine(String log) {
        Matcher m = STARTED.matcher(log);
        return m.find() ? m.group() : null;
    }

    static final Pattern STARTED = Pattern.compile("(?m)^.*\\bDone \\(\\d+(?:[.,]\\d+)?s\\)! For help.*$");
    static final Pattern FAILED_AFTER_START = Pattern.compile("Caused by:|Exception in thread|/FATAL\\]|Crash report saved|This crash report has been saved");

    static String failure(String log, List<String> failed) {
        String done = startedLine(log);
        if (done != null && !FAILED_AFTER_START.matcher(log.substring(log.indexOf(done) + done.length())).find()) return (
            "The server finished starting (" + plain(done).strip() + ") but modaccel's probe never reported back: a fault of the probe, not of a mod in the pack."
        );
        List<String> lines = plain(log).lines().toList(), out = new ArrayList<>();
        for (String line : lines) if (
            failed.stream().anyMatch(line::contains) || line.startsWith("Caused by:") || line.contains("Exception message:") || line.contains("/FATAL]")
        ) out.add(line.strip());
        if (out.isEmpty()) out.addAll(lines.subList(Math.max(0, lines.size() - 12), lines.size()));
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(out));
        return String.join("\n", unique.subList(Math.max(0, unique.size() - 14), unique.size()));
    }

    static String examples(List<String> items) {
        return String.join(", ", items.subList(0, Math.min(6, items.size()))) + (items.size() > 6 ? " and " + (items.size() - 6) + " more" : "");
    }

    /** Crash reports a start left in the instance, newest last. */
    static List<String> crashReports(Path instance, long since) {
        List<String> reports = new ArrayList<>();
        Path folder = instance.resolve("crash-reports");
        if (!Files.isDirectory(folder)) return reports;
        try (var list = Files.list(folder)) {
            for (Path report : list.sorted().toList()) if (Files.getLastModifiedTime(report).toMillis() >= since) reports.add(
                new String(Files.readAllBytes(report), java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (java.io.IOException unreadable) {
            /* the log still says what failed */
        }
        return reports;
    }
}
