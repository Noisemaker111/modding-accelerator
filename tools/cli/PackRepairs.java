import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * pack-check's repairs, as data: each entry in knowledge/pack-fixes.json names the loaders and game versions it is for
 * ("*" for any), a "match" (when it applies), an "action" (what it changes) and a "test" (jars, and for some matches
 * unavailable files or a start's log, that must match before the action and must not after it), plus the evidence that
 * proved it. A match is read from the jars before any game runs, or, with "when": "start-failed", from a server start
 * that failed. Matchers and actions are small named functions here; a repair that needs neither a new matcher nor a new
 * action is data only. A repair is applied to the user's pack only after the repaired pack, run beside the shipped one,
 * passes.
 *
 * Two kinds of entry: a fixed repair names its file (a URL and its SHA-512), and a rule names a kind of fault (a file the
 * pack's host no longer serves, a library no jar provides, a library pinned to another version, a server-optional mod
 * that stops servers) and finds the file at run time from a source that publishes a hash or a size, checking the jar's
 * own metadata before it is used.
 */
final class PackRepairs {

    private PackRepairs() {}

    /** What a matcher reads: the pack, its jars, its loader, the files its host no longer serves, and a failed start (its log) or null before the game. */
    record Facts(PackCheck.Pack pack, PackMods.Scan scan, PackLoaders.Loader loader, List<PackCheck.Unavailable> unavailable, String startLog) {}

    /** One place a repair applies: a summary for the finding, and what the action works on. */
    record Hit(String summary, Map<String, Object> target) {}

    interface Matcher {
        List<Hit> match(Map<String, Object> spec, Facts facts);
    }

    static final Map<String, Matcher> MATCHERS = Map.of(
        "mod-below",
        one(PackRepairs::modBelow),
        "mod-range",
        one(PackRepairs::modRange),
        "mods-together",
        one(PackRepairs::modsTogether),
        "dependency-unmet",
        PackRepairs::dependenciesUnmet,
        "dependency-missing",
        PackRepairs::dependenciesMissing,
        "unavailable-file",
        PackRepairs::unavailableFiles,
        "start-failure",
        PackRepairs::startFailure,
        "bundled-in-use",
        PackRepairs::bundledInUse,
        "crash-culprit",
        PackRepairs::crashCulprit
    );

    static final Set<String> ACTIONS = Set.of(
        "add-jar", "remove-mod", "replace-mod", "fetch-dependency", "substitute-file", "leave-out-of-server", "add-newer-bundled", "previous-release"
    );

    /**
     * A failed start whose log matches "failure", in a pack where the loader takes a library from inside another mod's
     * jar (no copy of its own is as new): each such library is a hit. Its newer release on its own replaces the bundled
     * copy, since the loader takes the highest version.
     */
    static List<Hit> bundledInUse(Map<String, Object> spec, Facts facts) {
        if (facts.startLog() == null || !Pattern.compile(Json.text(spec, "failure", ".")).matcher(facts.startLog()).find()) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (String id : facts.scan().providers.keySet()) {
            PackMods.Provider used = facts.scan().used(id);
            if (used == null || !used.bundled()) continue;
            hits.add(new Hit(id + " " + used.version() + " (inside " + used.jar() + ") is the copy the loader uses", Json.map("id", id, "version", used.version(), "jar", used.jar())));
        }
        return hits;
    }

    static final Pattern FRAME = Pattern.compile("\\bat ([a-z][\\w$]*(?:\\.[a-z][\\w$]*)+)\\.[A-Z][\\w$]*");

    /**
     * A failed start whose log matches "failure" (a crash once the world runs, not a loading error), with a stack frame in
     * a class one jar of the pack carries: that jar's mod is the culprit. Its previous release for the same loader and game
     * version replaces it. The first such frame decides; game and loader classes are in no pack jar.
     */
    static List<Hit> crashCulprit(Map<String, Object> spec, Facts facts) {
        if (facts.startLog() == null || !Pattern.compile(Json.text(spec, "failure", ".")).matcher(facts.startLog()).find()) return List.of();
        java.util.regex.Matcher frame = FRAME.matcher(facts.startLog());
        while (frame.find()) {
            String pkg = frame.group(1);
            String jar = null;
            for (String p = pkg; p.contains(".") && jar == null; p = p.substring(0, p.lastIndexOf('.'))) jar = facts.scan().packages.get(p);
            if (jar == null) continue;
            for (String id : facts.scan().idsOf(jar)) {
                PackMods.Provider used = facts.scan().used(id);
                if (used == null || used.bundled()) continue;
                return List.of(new Hit(id + " " + used.version() + " (" + jar + ") is in the crash's stack (" + pkg + ")", Json.map("id", id, "version", used.version(), "jar", jar)));
            }
        }
        return List.of();
    }

    /** A release of the hit's mod from Modrinth (PackResolve), newer or older than the pack's as the action asks. */
    static Path release(PackCheck check, Facts facts, Map<String, Object> target, boolean newer) throws Exception {
        String id = Json.text(target, "id", ""), version = Json.text(target, "version", "");
        List<String> metadata = facts.loader().metadata(facts.pack().minecraft());
        List<Path> jars = facts.pack().mods().stream().map(PackCheck.Mod::jar).toList();
        return PackResolve.release(check, id, metadata, facts.pack().loader(), facts.pack().minecraft(), jars,
            found -> newer ? Versions.compare(found, version) > 0 : Versions.compare(found, version) < 0);
    }

    /**
     * What the chosen repairs change. add: jars put in the mods folder (verified downloads); remove: the pack's jars taken
     * out; sources: each added jar's origin (a URL, or a CurseForge project and file that stands in for an unavailable one);
     * serverOnly: jars a server leaves out while the pack keeps them for its players; dropped: unavailable CurseForge files
     * ("project/file") nothing could stand in for; notes: what each repair did, in words.
     */
    record Change(
        List<Path> add,
        List<String> remove,
        Map<String, Map<String, Object>> sources,
        List<String> serverOnly,
        List<String> dropped,
        List<String> notes
    ) {
        Change(List<Path> add, List<String> remove) {
            this(add, remove, Map.of(), List.of(), List.of(), List.of());
        }

        /** Everything the server leaves out: the jars the repair removes and the ones only the server goes without. */
        List<String> offServer() {
            List<String> all = new ArrayList<>(remove);
            all.addAll(serverOnly);
            return all;
        }

        boolean empty() {
            return add.isEmpty() && remove.isEmpty() && serverOnly.isEmpty() && dropped.isEmpty();
        }
    }

    static Matcher one(java.util.function.BiFunction<Map<String, Object>, PackMods.Scan, String> single) {
        return (spec, facts) -> {
            String found = single.apply(spec, facts.scan());
            return found == null ? List.of() : List.of(new Hit(found, Map.of()));
        };
    }

    static List<Map<String, Object>> all(Path harness) throws IOException {
        List<Map<String, Object>> fixes = new ArrayList<>();
        for (Object f : Json.array(Json.read(harness.resolve("knowledge/pack-fixes.json")).get("fixes"))) fixes.add(Json.object(f));
        return fixes;
    }

    /** The repairs, read from the jars before the game, for this pack; each as a known-fault finding. */
    static List<Map<String, Object>> match(Path harness, PackCheck.Pack pack, PackMods.Scan scan, List<Object> findings) throws IOException {
        return match(harness, new Facts(pack, scan, PackLoaders.find(harness, pack.loader()), List.of(), null), findings, List.of());
    }

    /**
     * The repairs whose match holds on these facts (before the game when startLog is null, else on that failed start),
     * less what an earlier round already chose. Each comes back as a copy of its entry with its "hits"; each adds a
     * known-fault finding.
     */
    static List<Map<String, Object>> match(Path harness, Facts facts, List<Object> findings, List<Map<String, Object>> already) throws IOException {
        Set<String> seen = new HashSet<>();
        for (var fix : already) for (Object h : Json.array(fix.getOrDefault("hits", List.of()))) seen.add(fix.get("id") + " " + Json.object(h).get("target"));
        Set<String> handled = new HashSet<>();
        for (var fix : already) for (Object h : Json.array(fix.getOrDefault("hits", List.of()))) {
            var target = Json.object(Json.object(h).get("target"));
            if (target.get("id") != null) handled.add(String.valueOf(target.get("id")));
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (var fix : all(harness)) {
            List<Hit> hits = applies(fix, facts);
            List<Object> fresh = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            for (Hit hit : hits) {
                Object id = hit.target().get("id");
                if (seen.contains(fix.get("id") + " " + hit.target()) || (id != null && handled.contains(String.valueOf(id)))) continue;
                if (id != null) handled.add(String.valueOf(id));
                fresh.add(Json.map("summary", hit.summary(), "target", hit.target()));
                summaries.add(hit.summary());
            }
            if (fresh.isEmpty()) continue;
            var chosen = new LinkedHashMap<>(fix);
            chosen.put("hits", fresh);
            matched.add(chosen);
            findings.add(
                Json.map(
                    "kind",
                    "known-fault",
                    "severity",
                    "problem",
                    "id",
                    fix.get("id"),
                    "summary",
                    String.join("; ", summaries) + ": " + fix.get("symptom"),
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

    /** The places a repair applies on these facts: none when it is for another loader, game version or stage. */
    static List<Hit> applies(Map<String, Object> fix, Facts facts) {
        if (!forLoader(fix, facts.pack().loader())) return List.of();
        Object versions = fix.get("minecraft");
        if (!(versions instanceof List<?> list && (list.contains("*") || list.contains(facts.pack().minecraft())))) return List.of();
        boolean inGame = "start-failed".equals(fix.get("when"));
        if (inGame != (facts.startLog() != null)) return List.of();
        var spec = Json.object(fix.get("match"));
        Matcher matcher = MATCHERS.get(Json.text(spec, "type", ""));
        if (matcher == null) throw new IllegalStateException("pack-fixes.json: " + fix.get("id") + " has an unknown match type " + spec.get("type"));
        return matcher.match(spec, facts);
    }

    /** "loader": one loader's id, a list of them, or "*". */
    static boolean forLoader(Map<String, Object> fix, String loader) {
        Object named = fix.get("loader");
        if (named instanceof List<?> list) return list.contains(loader) || list.contains("*");
        return "*".equals(named) || loader.equals(named);
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

    /**
     * dependency-unmet: one named mod and dependency, or "mod": "*" for every required dependency the pack has in a version
     * its dependant does not accept. A rule ("*") run on a failed start only takes a dependency that start's log names.
     */
    static List<Hit> dependenciesUnmet(Map<String, Object> spec, Facts facts) {
        if (!"*".equals(spec.get("mod"))) return one(PackRepairs::dependencyUnmet).match(spec, facts);
        Map<String, List<PackMods.Dependency>> wanted = new TreeMap<>();
        for (var d : facts.scan().dependencies) {
            if (!d.kind().equals("required") || ignored(facts, d.id())) continue;
            PackMods.Provider used = facts.scan().used(d.id());
            if (used == null || used.version().isBlank() || PackMods.accepts(d, used.version())) continue;
            if (facts.startLog() != null && !named(facts.startLog(), d.id())) continue;
            wanted.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(d);
        }
        List<Hit> hits = new ArrayList<>();
        for (var entry : wanted.entrySet()) {
            PackMods.Provider used = facts.scan().used(entry.getKey());
            if (used.bundled()) continue;
            hits.add(
                new Hit(
                    describe(entry.getValue()) + ", and the pack has " + used.version() + " (" + used.jar() + ")",
                    target(entry.getKey(), facts, true)
                )
            );
        }
        return hits;
    }

    /** dependency-missing: one named mod and dependency, or "mod": "*" for every required mod id no jar provides (on a failed start: one its log names). */
    static List<Hit> dependenciesMissing(Map<String, Object> spec, Facts facts) {
        if (!"*".equals(spec.get("mod"))) return one(PackRepairs::dependencyMissing).match(spec, facts);
        Map<String, List<PackMods.Dependency>> wanted = new TreeMap<>();
        for (var d : facts.scan().dependencies) {
            if (!d.kind().equals("required") || ignored(facts, d.id()) || facts.scan().providers.containsKey(d.id())) continue;
            if (facts.startLog() != null && !named(facts.startLog(), d.id())) continue;
            wanted.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(d);
        }
        List<Hit> hits = new ArrayList<>();
        for (var entry : wanted.entrySet()) hits.add(new Hit(describe(entry.getValue()) + ", which no jar in the pack provides", target(entry.getKey(), facts, false)));
        return hits;
    }

    /** The game, Java, the loader itself and what it supplies are never a library to fetch. */
    static boolean ignored(Facts facts, String id) {
        if (id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.equals("quilt_loader") || id.equals("forge") || id.equals("neoforge")) return true;
        return facts.loader() != null && facts.loader().builtIn().contains(id);
    }

    /** A start's log that names a mod id as a word (Forge's "Mod ID: 'x'", Fabric's "of x, which"), not inside another id. */
    static boolean named(String log, String id) {
        return Pattern.compile("(?<![A-Za-z0-9_\\-])" + Pattern.quote(id) + "(?![A-Za-z0-9_\\-])").matcher(log).find();
    }

    static String describe(List<PackMods.Dependency> wanted) {
        List<String> who = new ArrayList<>();
        for (var d : wanted) who.add(PackCheck.top(d.owner()) + " requires " + d.id() + " " + String.join(" or ", d.ranges()));
        return String.join("; ", new LinkedHashSet<>(who));
    }

    /** What fetch-dependency works on: the mod id, every range the pack's mods require it in, the jars that do, and whether the present one is replaced. */
    static Map<String, Object> target(String id, Facts facts, boolean replace) {
        List<Object> ranges = new ArrayList<>(), owners = new ArrayList<>();
        for (var d : facts.scan().dependencies) if (d.kind().equals("required") && d.id().equals(id)) {
            ranges.add(Json.map("ranges", d.ranges(), "format", d.format(), "owner", d.owner()));
            if (!owners.contains(PackCheck.top(d.owner()))) owners.add(PackCheck.top(d.owner()));
        }
        return Json.map("id", id, "ranges", ranges, "owners", owners, "replace", replace);
    }

    /** unavailable-file: each mod file the pack lists that its host (CurseForge) no longer serves. */
    static List<Hit> unavailableFiles(Map<String, Object> spec, Facts facts) {
        List<Hit> hits = new ArrayList<>();
        for (var gone : facts.unavailable()) hits.add(
            new Hit(gone.describe(), Json.map("project", gone.project(), "file", gone.file(), "name", gone.name()))
        );
        return hits;
    }

    /**
     * start-failure: a failed start whose log, by one of "patterns" (group 1 a mod id), stops at a mod's own start-up code,
     * when that mod is one the server can do without ("side": "server-optional": its Modrinth page or the pack's .mrpack
     * says a server does not need it). The jars on the server are the pack's, so a jar already left out does not match.
     */
    static List<Hit> startFailure(Map<String, Object> spec, Facts facts) {
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object p : Json.array(spec.get("patterns"))) {
            java.util.regex.Matcher m = Pattern.compile(String.valueOf(p)).matcher(facts.startLog());
            while (m.find()) {
                String id = m.group(1);
                if (!seen.add(id)) continue;
                for (var provider : facts.scan().providers.getOrDefault(id, List.of())) {
                    if (provider.bundled()) continue;
                    PackCheck.Mod mod = facts.pack().mods().stream().filter(x -> x.file().equals(provider.jar())).findFirst().orElse(null);
                    if (mod == null) continue;
                    // A mod other mods in the pack require is not optional to this pack, whatever its own page says (KubeJS, which
                    // its addons need, stops a server when a script names a mod that is missing).
                    if (requiredByOthers(facts.scan(), provider.jar())) continue;
                    String side = PackCheck.serverSide(mod);
                    if ("server-optional".equals(spec.get("side")) && !side.equals("optional") && !side.equals("unsupported")) continue;
                    hits.add(
                        new Hit(
                            id + " (" + provider.jar() + ") stopped the server at start-up, and " +
                            side.replace("optional", "a server does not need it").replace("unsupported", "a server cannot run it") +
                            " (" + mod.source() + ")",
                            Json.map("mod", id, "jar", provider.jar())
                        )
                    );
                }
            }
        }
        return hits;
    }

    static final Pattern FAILING = Pattern.compile(
        "Caused by:|Exception|ERROR\\]|FATAL\\]|/ERROR|/FATAL|Mod ID:|[Rr]equire|which is missing|Incompatible|[Mm]issing or unsupported|has failed to load|entrypoint|Unmet|depends on|is not installed"
    );

    /**
     * The lines of a start's log that say why it failed: exceptions and their causes, errors, and the loader's dependency
     * lines (Forge's "Mod ID: 'x', Requested by", Fabric's "requires version y of x, which is missing"). Not the list of
     * every mod a log or crash report prints, so a rule that asks whether the failure names a mod id is not fooled by it.
     */
    static String failing(String log) {
        List<String> lines = new ArrayList<>();
        int frames = 0;
        for (String line : log.split("\\R")) {
            String plain = line.replaceAll("[\u00a7\ufffd].", "");
            boolean failing = FAILING.matcher(plain).find() && !plain.contains("|");
            // The first frames under an exception say whose code threw it (crash-culprit reads them).
            boolean frame = !failing && frames > 0 && plain.strip().startsWith("at ");
            if ((failing || frame) && lines.size() < 400) lines.add(plain.strip());
            frames = failing && plain.contains("Exception") ? 8 : frame ? frames - 1 : 0;
        }
        return String.join("\n", new LinkedHashSet<>(lines));
    }

    // ------------------------------------------------------------------ actions
    /** Every chosen repair as one change: its jars found, downloaded and checked, the pack's jars it takes out, and what a server leaves out. */
    static Change change(PackCheck check, List<Map<String, Object>> fixes, Facts facts) throws Exception {
        List<Path> add = new ArrayList<>();
        List<String> remove = new ArrayList<>(), serverOnly = new ArrayList<>(), dropped = new ArrayList<>(), notes = new ArrayList<>();
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        // Ids the added jars provide: a library a substitute already brings back is not fetched again.
        PackMods.Scan added = new PackMods.Scan();
        List<String> metadata = facts.loader() == null ? PackMods.FORMATS : facts.loader().metadata(facts.pack().minecraft());
        for (var fix : fixes) {
            var action = Json.object(fix.get("action"));
            String type = Json.text(action, "type", "");
            if (!ACTIONS.contains(type)) throw new IllegalStateException("pack-fixes.json: " + fix.get("id") + " has an unknown action " + type);
            List<Object> hits = Json.array(fix.getOrDefault("hits", List.of(Json.map("summary", "", "target", Map.of()))));
            for (Object h : hits) {
                var target = Json.object(Json.object(h).get("target"));
                switch (type) {
                    case "remove-mod", "replace-mod", "add-jar" -> {
                        if (!type.equals("add-jar")) for (var p : facts.scan().providers.getOrDefault(Json.text(action, "mod", ""), List.of())) {
                            if (!p.bundled() && !remove.contains(p.jar())) remove.add(p.jar());
                        }
                        if (!type.equals("remove-mod")) {
                            Path jar = download(check, action);
                            add.add(jar);
                            sources.put(jar.getFileName().toString(), action);
                            notes.add(fix.get("id") + ": " + (type.equals("add-jar") ? "added " : "replaced " + action.get("mod") + " with ") + jar.getFileName());
                        } else notes.add(fix.get("id") + ": removed " + action.get("mod"));
                    }
                    case "substitute-file" -> {
                        var found = substitute(check, facts, target);
                        if (found == null) {
                            dropped.add(target.get("project") + "/" + target.get("file"));
                            notes.add(
                                fix.get("id") + ": project " + target.get("project") + " serves no file for Minecraft " + facts.pack().minecraft() + " on " +
                                facts.pack().loader() + " to stand in for file " + target.get("file") + "; it is left out of the pack, which was checked without it"
                            );
                            continue;
                        }
                        Path jar = (Path) found.remove("jar");
                        add.add(jar);
                        sources.put(jar.getFileName().toString(), found);
                        remember(jar, metadata, added);
                        notes.add(fix.get("id") + ": file " + target.get("file") + " of project " + target.get("project") + " is served no more; " + found.get("why"));
                    }
                    case "fetch-dependency" -> {
                        String id = Json.text(target, "id", "");
                        boolean replace = Boolean.TRUE.equals(target.get("replace"));
                        if (!replace && added.providers.containsKey(id)) continue;
                        var found = fetchDependency(check, facts, target);
                        if (found == null && !replace && "leave-out-dependants".equals(action.get("otherwise"))) {
                            // No source serves the library: a mod that needs it cannot run. One nothing else in the pack needs is
                            // optional to the pack, so it is left out with a note; one other mods need is not, and nothing changes.
                            List<String> optional = new ArrayList<>(), needed = new ArrayList<>();
                            for (Object o : Json.array(target.get("owners"))) (requiredByOthers(facts.scan(), String.valueOf(o)) ? needed : optional).add(String.valueOf(o));
                            if (needed.isEmpty() && !optional.isEmpty()) {
                                for (String jar : optional) if (!remove.contains(jar)) remove.add(jar);
                                notes.add(
                                    fix.get("id") + ": no source serves " + id + " in a version for Minecraft " + facts.pack().minecraft() + " on " + facts.pack().loader() +
                                    " that its dependants accept (checked: the dependants' own Modrinth releases, Modrinth's releases for this game version, CurseForge's files); " +
                                    optional + ", which need it and which no other mod needs, are left out of the pack"
                                );
                                continue;
                            }
                        }
                        if (found == null) {
                            notes.add(fix.get("id") + ": no verified file of " + id + " in the range its dependants ask for was found, so nothing was changed for it");
                            continue;
                        }
                        Path jar = (Path) found.remove("jar");
                        if (replace) for (var p : facts.scan().providers.getOrDefault(id, List.of())) if (!p.bundled() && !remove.contains(p.jar())) remove.add(p.jar());
                        add.add(jar);
                        sources.put(jar.getFileName().toString(), found);
                        remember(jar, metadata, added);
                        notes.add(fix.get("id") + ": " + (replace ? "replaced " + id + " with " : "added ") + jar.getFileName() + " (" + found.get("why") + ")");
                    }
                    case "leave-out-of-server" -> {
                        String jar = Json.text(target, "jar", "");
                        if (!jar.isEmpty() && !serverOnly.contains(jar)) serverOnly.add(jar);
                        notes.add(fix.get("id") + ": " + jar + " is left out of the server; players keep it");
                    }
                    case "add-newer-bundled", "previous-release" -> {
                        boolean newer = type.equals("add-newer-bundled");
                        Path jar = release(check, facts, target, newer);
                        if (jar == null) {
                            notes.add(fix.get("id") + ": Modrinth has no " + (newer ? "newer" : "earlier") + " release of " + target.get("id") + " for this game version and loader, so nothing was changed for it");
                            continue;
                        }
                        if (!newer && !remove.contains(Json.text(target, "jar", ""))) remove.add(Json.text(target, "jar", ""));
                        add.add(jar);
                        sources.put(jar.getFileName().toString(), Json.map("type", type, "mod", target.get("id"), "from", "Modrinth, by the project of the pack's own jar"));
                        notes.add(fix.get("id") + ": " + (newer ? "added " : "replaced " + target.get("jar") + " with ") + jar.getFileName());
                    }
                    default -> throw new IllegalStateException(type);
                }
            }
        }
        return new Change(add, remove, sources, serverOnly, dropped, notes);
    }

    /** Whether another jar in the pack requires an id this jar provides (or a jar bundled in it provides). */
    static boolean requiredByOthers(PackMods.Scan scan, String jar) {
        Set<String> own = new HashSet<>();
        for (var entry : scan.providers.entrySet()) for (var p : entry.getValue()) if (PackCheck.top(p.jar()).equals(jar)) own.add(entry.getKey());
        for (var d : scan.dependencies) if (d.kind().equals("required") && own.contains(d.id()) && !PackCheck.top(d.owner()).equals(jar)) {
            boolean elsewhere = scan.providers.getOrDefault(d.id(), List.of()).stream().anyMatch(p -> !PackCheck.top(p.jar()).equals(jar));
            if (!elsewhere) return true;
        }
        return false;
    }

    /** The ids an added jar provides, so a later repair does not fetch them again. */
    static void remember(Path jar, List<String> metadata, PackMods.Scan added) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            PackMods.read(zip, jar.getFileName().toString(), false, metadata, added);
        }
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

    /** CurseForge's names for a loader in a file's game versions; Quilt runs Fabric mods too. */
    static List<String> curseforgeLoaders(String loader) {
        return switch (loader) {
            case "forge" -> List.of("Forge");
            case "neoforge" -> List.of("NeoForge");
            case "fabric" -> List.of("Fabric");
            case "quilt" -> List.of("Quilt", "Fabric");
            default -> List.of(loader);
        };
    }

    /** Modrinth's names for a loader; Quilt runs Fabric mods too. */
    static List<String> modrinthLoaders(String loader) {
        return loader.equals("quilt") ? List.of("quilt", "fabric") : List.of(loader);
    }

    /**
     * A file to stand in for one the pack lists and CurseForge no longer serves: the same project's file for the pack's
     * game version and loader, the nearest newer one first, then older ones. Each is checked against the size CurseForge
     * publishes, must be a mod for this loader and game version by its own metadata, and must satisfy every range the
     * pack's mods require its ids in; when Modrinth has a file with its SHA-1, that is said too. null: none passes.
     */
    static Map<String, Object> substitute(PackCheck check, Facts facts, Map<String, Object> target) throws Exception {
        if (Boolean.getBoolean("modaccel.pack.offline")) return null;
        int project = ((Number) target.get("project")).intValue(), missing = ((Number) target.get("file")).intValue();
        List<Map<String, Object>> files = PackCheck.curseforgeFiles(project);
        List<String> loaders = curseforgeLoaders(facts.pack().loader());
        List<Map<String, Object>> fitting = new ArrayList<>();
        for (var file : files) {
            List<Object> versions = Json.array(file.getOrDefault("gameVersions", List.of()));
            if (versions.contains(facts.pack().minecraft()) && loaders.stream().anyMatch(versions::contains)) fitting.add(file);
        }
        fitting.sort(Comparator.comparingInt(f -> {
            int id = ((Number) f.get("id")).intValue();
            return id > missing ? id - missing : Integer.MAX_VALUE / 2 + (missing - id);
        }));
        int tried = 0;
        for (var file : fitting) {
            if (tried++ >= 4) break;
            int id = ((Number) file.get("id")).intValue();
            Path jar;
            try {
                jar = check.curseforgeJar(project, id, file);
            } catch (IOException unserved) {
                continue;
            }
            String why = fits(jar, facts, null);
            if (why != null) continue;
            String modrinth = PackCheck.modrinthHas(jar);
            return Json.map(
                "jar",
                jar,
                "curseforge",
                Json.map("project", project, "file", id, "fileName", file.get("fileName")),
                "stands",
                missing,
                "why",
                "the same project's " + file.get("fileName") + " (file " + id + ") for Minecraft " + facts.pack().minecraft() + " stands in, checked by its size and its own metadata" +
                (modrinth.isEmpty() ? "" : ", and Modrinth has a file with the same SHA-1 (" + modrinth + ")")
            );
        }
        return null;
    }

    /**
     * Why a jar cannot stand in, or null when it can: it must be a mod for the pack's loader, accept the pack's game
     * version, provide id (when given), and satisfy every range the pack's mods require any of its ids in.
     */
    static String fits(Path jar, Facts facts, String id) {
        List<String> metadata = facts.loader() == null ? PackMods.FORMATS : facts.loader().metadata(facts.pack().minecraft());
        PackMods.Scan scan = PackMods.scan(List.of(new PackCheck.Mod(jar.getFileName().toString(), jar, "unknown", "candidate")), metadata);
        List<String> ids = scan.idsOf(jar.getFileName().toString());
        if (ids.isEmpty()) return "not a mod for " + facts.pack().loader();
        if (id != null && !ids.contains(id)) return "does not provide " + id;
        for (var d : scan.dependencies) if (
            d.kind().equals("required") && d.id().equals("minecraft") && !d.owner().contains("!") && !PackMods.accepts(d, facts.pack().minecraft())
        ) return "is for Minecraft " + d.ranges();
        for (String own : ids) {
            PackMods.Provider provider = scan.used(own);
            for (var d : facts.scan().dependencies) if (
                d.kind().equals("required") && d.id().equals(own) && !PackMods.accepts(d, provider.version())
            ) return own + " " + provider.version() + " is outside " + d.ranges() + " (" + d.owner() + ")";
        }
        return null;
    }

    /**
     * A jar of the library a target names, in a version every mod in the pack that requires it accepts: first the version
     * the dependant's own Modrinth release names (found by the dependant jar's SHA-1), then the releases of the projects it
     * names, or that Modrinth finds by the mod id, for this game version and loader, oldest first. Each download is
     * checked against Modrinth's SHA-512 and must provide the id by its own metadata. null: none passes.
     */
    static Map<String, Object> fetchDependency(PackCheck check, Facts facts, Map<String, Object> target) throws Exception {
        if (Boolean.getBoolean("modaccel.pack.offline")) return null;
        String id = Json.text(target, "id", "");
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> projects = new LinkedHashSet<>();
        for (Object owner : Json.array(target.get("owners"))) {
            PackCheck.Mod mod = facts.pack().mods().stream().filter(m -> m.file().equals(owner)).findFirst().orElse(null);
            if (mod == null || mod.jar() == null) continue;
            var version = PackCheck.modrinthVersion(PackCheck.sha1(mod.jar()));
            if (version == null) continue;
            for (Object d : Json.array(version.getOrDefault("dependencies", List.of()))) {
                var dependency = Json.object(d);
                if (!"required".equals(dependency.get("dependency_type")) && !"optional".equals(dependency.get("dependency_type"))) continue;
                if (dependency.get("version_id") instanceof String versionId) {
                    var named = PackCheck.modrinthJson("version/" + versionId);
                    if (named instanceof Map<?, ?> v) candidates.add(Json.map("version", v, "why", "the version " + owner + "'s own Modrinth release names"));
                }
                if (dependency.get("project_id") instanceof String projectId) projects.add(projectId);
            }
        }
        projects.add(id);
        projects.add(id.replace('_', '-'));
        String facets = Json.write(
            List.of(
                modrinthLoaders(facts.pack().loader()).stream().map(l -> "categories:" + l).toList(),
                List.of("versions:" + facts.pack().minecraft())
            )
        );
        String search = "search?limit=5&query=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8);
        if (PackCheck.modrinthJson(search) instanceof Map<?, ?> found && found.get("hits") instanceof List<?> hits) {
            for (Object hit : hits) if (hit instanceof Map<?, ?> h) projects.add(String.valueOf(h.get("project_id")));
        }
        String loaders = URLEncoder.encode(Json.write(modrinthLoaders(facts.pack().loader())), StandardCharsets.UTF_8);
        String games = URLEncoder.encode(Json.write(List.of(facts.pack().minecraft())), StandardCharsets.UTF_8);
        for (String project : projects) {
            String path = "project/" + URLEncoder.encode(project, StandardCharsets.UTF_8) + "/version?loaders=" + loaders + "&game_versions=" + games;
            if (!(PackCheck.modrinthJson(path) instanceof List<?> versions)) continue;
            List<Map<String, Object>> sorted = new ArrayList<>();
            for (Object v : versions) if (v instanceof Map<?, ?> version) sorted.add(Json.object(version));
            sorted.sort(Comparator.comparing(v -> Json.text(v, "date_published", "")));
            for (var v : sorted) candidates.add(Json.map("version", v, "why", "Modrinth project " + project + "'s release " + v.get("version_number") + " for Minecraft " + facts.pack().minecraft()));
        }
        Set<String> tried = new HashSet<>();
        int downloads = 0;
        for (var candidate : candidates) {
            var version = Json.object(candidate.get("version"));
            Map<String, Object> file = null;
            for (Object f : Json.array(version.getOrDefault("files", List.of()))) {
                var each = Json.object(f);
                if (file == null || Boolean.TRUE.equals(each.get("primary"))) file = each;
            }
            if (file == null || !tried.add(Json.text(file, "url", ""))) continue;
            if (downloads++ >= 8) break;
            Path jar = PackCheck.modrinthFile(check.cache, file);
            if (jar == null) continue;
            if (fits(jar, facts, id) != null) continue;
            return Json.map(
                "jar",
                jar,
                "url",
                file.get("url"),
                "sha512",
                Json.object(file.get("hashes")).get("sha512"),
                "page",
                "https://modrinth.com/project/" + version.get("project_id") + "/version/" + version.get("id"),
                "why",
                candidate.get("why") + ", checked against Modrinth's SHA-512 and its own metadata"
            );
        }
        return null;
    }

    // ------------------------------------------------------------------ the tests in pack-fixes.json
    /**
     * Runs every repair's test: its jars (written in the loader's own metadata format), with its unavailable files and its
     * start's log when the match reads them, must match before the action, and must not once the action is done (jars
     * added as the test describes them, jars removed, unavailable files stood in for, jars left out of the server). Also
     * checks that each repair names a known matcher, action and check, and that a single-loader repair does not match on
     * another loader.
     */
    static List<String> selfTest(Path harness, Path scratch) throws Exception {
        List<String> passed = new ArrayList<>();
        Set<String> checks = new HashSet<>();
        for (var check : PackChecks.ALL) checks.add(check.id());
        // A file the pack's host no longer serves is found before the checks run, by pack-check itself.
        checks.add("unavailable-file");
        Map<String, PackLoaders.Loader> loaders = PackLoaders.all(harness);
        for (var fix : all(harness)) {
            String id = Json.text(fix, "id", "");
            var test = Json.object(fix.get("test"));
            if (test.isEmpty()) throw new AssertionError(id + " has no test");
            if (!checks.contains(Json.text(fix, "check", ""))) throw new AssertionError(id + " names no known check: " + fix.get("check"));
            if (!ACTIONS.contains(Json.text(Json.object(fix.get("action")), "type", ""))) throw new AssertionError(id + " has an unknown action");
            // A fixed download is used only if it matches its recorded SHA-512, so the record must be one, from an https source.
            var fixed = Json.object(fix.get("action"));
            if (fixed.containsKey("url") || fixed.containsKey("sha512")) {
                if (!Json.text(fixed, "sha512", "").matches("[0-9a-f]{128}")) throw new AssertionError(id + ": its download's sha512 is not a SHA-512");
                if (!Json.text(fixed, "url", "").startsWith("https://")) throw new AssertionError(id + ": its download is not from an https address");
            }
            String loaderId = Json.text(test, "loader", fix.get("loader") instanceof String one && !one.equals("*") ? one : "");
            String minecraft = Json.text(test, "minecraft", String.valueOf(Json.array(fix.get("minecraft")).get(0)));
            PackLoaders.Loader loader = loaders.get(loaderId);
            if (loader == null) throw new AssertionError(id + " is for an unknown loader " + loaderId + " (a rule's test names its loader)");
            if (!forLoader(fix, loaderId)) throw new AssertionError(id + ": its test's loader " + loaderId + " is not one it is for");
            String format = loader.metadata(minecraft).get(0);
            Path mods = Files.createDirectories(scratch.resolve(id).resolve("mods"));
            List<PackCheck.Mod> jars = new ArrayList<>();
            for (Object j : Json.array(test.get("jars"))) jars.add(testJar(mods, Json.object(j), format));
            List<PackCheck.Unavailable> unavailable = new ArrayList<>();
            for (Object u : Json.array(test.getOrDefault("unavailable", List.of()))) {
                var gone = Json.object(u);
                unavailable.add(new PackCheck.Unavailable(((Number) gone.get("project")).intValue(), ((Number) gone.get("file")).intValue(), Json.text(gone, "name", ""), "test"));
            }
            String log = "start-failed".equals(fix.get("when")) ? Json.text(test, "log", "") : null;
            PackCheck.Pack pack = new PackCheck.Pack("test", "", minecraft, loaderId, "", jars, List.of(mods.getParent()), mods.getParent(), true, "test");
            PackMods.Scan before = PackMods.scan(jars, loader.metadata(minecraft));
            Facts facts = new Facts(pack, before, loader, unavailable, log);
            if (applies(fix, facts).isEmpty()) throw new AssertionError(id + ": its test does not match it");
            if (fix.get("loader") instanceof String only && !only.equals("*")) for (String other : loaders.keySet()) {
                var otherPack = new PackCheck.Pack("test", "", minecraft, other, "", jars, List.of(mods.getParent()), mods.getParent(), true, "test");
                Facts elsewhere = new Facts(otherPack, before, loaders.get(other), unavailable, log);
                if (!other.equals(loaderId) && !applies(fix, elsewhere).isEmpty()) throw new AssertionError(id + " also matches on " + other);
            }
            var action = Json.object(fix.get("action"));
            String type = Json.text(action, "type", "");
            List<PackCheck.Mod> after = new ArrayList<>(jars);
            List<PackCheck.Unavailable> stillGone = new ArrayList<>(unavailable);
            Set<String> gone = new HashSet<>();
            if (type.equals("remove-mod") || type.equals("replace-mod")) {
                for (var p : before.providers.getOrDefault(Json.text(action, "mod", ""), List.of())) if (!p.bundled()) gone.add(p.jar());
                if (gone.isEmpty()) throw new AssertionError(id + ": its action removes nothing from its test jars");
            }
            if (type.equals("fetch-dependency")) for (Hit hit : applies(fix, facts)) if (Boolean.TRUE.equals(hit.target().get("replace"))) {
                for (var p : before.providers.getOrDefault(String.valueOf(hit.target().get("id")), List.of())) if (!p.bundled()) gone.add(p.jar());
            }
            if (type.equals("leave-out-of-server") || type.equals("previous-release")) for (Hit hit : applies(fix, facts)) gone.add(String.valueOf(hit.target().get("jar")));
            if (type.equals("substitute-file")) stillGone.clear();
            after.removeIf(m -> gone.contains(m.file()));
            // A general repair names no mod and no file: its jar is found when it runs, and its test describes it.
            if (fix.get("loader") instanceof String any && any.equals("*") && (fix.containsKey("mod") || fixed.containsKey("url") || fixed.containsKey("file"))) {
                throw new AssertionError(id + " is general: it may not name a mod or a file");
            }
            if (type.equals("add-jar") || type.equals("replace-mod") || type.equals("fetch-dependency") || type.equals("substitute-file") || type.equals("add-newer-bundled") || type.equals("previous-release")) {
                var added = Json.object(test.get("added"));
                if (added.isEmpty()) throw new AssertionError(id + ": its test does not describe the jar it adds");
                added.putIfAbsent("file", Json.text(action, "file", "added.jar"));
                after.add(testJar(mods, added, format));
            }
            PackCheck.Pack afterPack = new PackCheck.Pack("test", "", minecraft, loaderId, "", after, List.of(mods.getParent()), mods.getParent(), true, "test");
            if (!applies(fix, new Facts(afterPack, PackMods.scan(after, loader.metadata(minecraft)), loader, stillGone, log)).isEmpty()) throw new AssertionError(
                id + ": its test still matches after its action"
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
            // Class files by name only, so a crash's stack frame can be traced to this jar.
            for (Object c : Json.array(spec.getOrDefault("classes", List.of()))) write(out, c.toString(), "");
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
        String side = Json.text(spec, "serverSide", "");
        return new PackCheck.Mod(jar.getFileName().toString(), jar, "unknown", side.isEmpty() ? "test" : "test, server_side " + side);
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
            List<Object> depends = new ArrayList<>();
            for (var d : Json.object(mod.getOrDefault("depends", Map.of())).entrySet()) depends.add(Json.map("id", d.getKey(), "versions", d.getValue()));
            return Json.write(Json.map("schema_version", 1, "quilt_loader", Json.map("id", mod.get("id"), "version", mod.get("version"), "jars", paths, "depends", depends)));
        }
        return Json.write(Json.map("schemaVersion", 1, "id", mod.get("id"), "version", mod.get("version"), "jars", jars, "depends", mod.getOrDefault("depends", Map.of())));
    }

    static void write(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
