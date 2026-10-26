import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** Bounded repairs use the existing typed mapper and rule owner, with byte-exact guarded undo. */
final class Port {
    final Build build; Map<String, Object> model; String targetVersion = "";
    /** The hub route this port walks (null for the one-jump path), its source namespace, and the rules that changed a file. */
    Route route; String namespace = "mojmap"; final Map<String, List<String>> rulesApplied = new LinkedHashMap<>();
    Port(Build build, Map<String, Object> model) { this.build = build; this.model = model; }    static List<Path> sources(ProjectModel project) throws IOException {
        List<Path> paths = new ArrayList<>(project.inputs());
        Files.walkFileTree(project.root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return !dir.equals(project.root) && (ProjectModel.IGNORED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git"))) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && (file.toString().endsWith(".java") || file.toString().endsWith(".kt"))) paths.add(file); return FileVisitResult.CONTINUE;
            }
        });
        return new TreeSet<>(paths).stream().toList();
    }
    Map<String, Object> run(Modaccel.Options options) throws Exception {
        if (options.values.containsKey("undo")) return undo(options.get("undo", ""));
        String from = options.get("from", ""), to = options.get("to", "");
        if (from.isBlank() || to.isBlank()) throw new IllegalArgumentException("Port needs --from <version> --to <version>; --fix selects supported target dependencies and repairs sources with guarded undo.");
        if (!from.matches("[A-Za-z0-9._+\\-]+") || !to.matches("[A-Za-z0-9._+\\-]+")) throw new IllegalArgumentException("Invalid Minecraft version");
        targetVersion = to;
        Map<String, Object> refusal = Route.blocked(build.harness, from, to);
        if (refusal != null) return refusal;
        // --direct keeps the one-jump map (A -> B joined in one diff); an explicit --map is used as given.
        route = options.has("direct") || options.values.containsKey("map") ? null : Route.plan(build.harness, from, to);
        Set<String> actual = versions(model);
        if (!actual.equals(Set.of(to)) && !actual.equals(Set.of(from))) throw new IllegalArgumentException("Build targets " + actual + "; select the project matching --from or --to.");
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        long targetStart = System.nanoTime();
        Target.Setup setup = actual.equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Target.Edit> target = setup == null ? Target.manifestEditsForModel(model, to) : setup.edits();
        List<Path> excluded = setup == null ? List.of() : setup.excluded;
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied;
        List<Map<String, Object>> unchecked = setup == null ? List.of() : setup.unchecked;
        build.report.stages.add(Json.map("name", "target-dependencies", "seconds", (System.nanoTime() - targetStart) / 1e9, "changedFiles", target.size(),
                "resolved", applied.size(), "blocked", setup == null ? 0 : setup.blockers.size(), "selections", selections.size()));
        if (setup != null && !setup.blockers.isEmpty())
            return Json.map("ok", false, "tier", "dependency-blockers", "blocked", setup.blockers, "resolvedDependencies", applied,
                    "selectionFile", selectionFile == null ? null : selectionFile.toString(), "sourcesChanged", false,
                    "details", blockerText(setup.blockers));
        if (!target.isEmpty() && (!options.has("fix") || options.has("dry-run")))
            return Json.map("ok", true, "tier", "target-preview", "sourcesChanged", false, "edits", target.stream().map(e -> Json.map("file", e.file().toString(), "before", e.before(), "after", e.after())).toList(),
                    "resolvedDependencies", applied, "excludedSources", relative(build.project.root, excluded),
                    "selectionFile", selectionFile == null ? null : selectionFile.toString(),
                    "details", "Published target dependencies resolved; --fix applies them with guarded undo.");

        JavaTools tools = new JavaTools(build); String names = options.get("names", inferNames());
        if (!Set.of("yarn", "mojmap").contains(names) || !from.matches("[A-Za-z0-9._+\\-]+") || !to.matches("[A-Za-z0-9._+\\-]+"))
            throw new IllegalArgumentException("Invalid version or mapping namespace");
        namespace = names;
        Path map = options.values.containsKey("map") ? Path.of(options.get("map", "")).toAbsolutePath() : build.project.state().resolve("maps/" + names + "-" + from + "-" + to + ".json");
        // A routed port joins every update it crosses into one map: old names resolve straight to final
        // names and each removal keeps the update that removed it. Only releases before 26.1 share
        // intermediary ids; the rest of a route into 26.x is left to the compiler and the rules. Yarn has no
        // names past 1.21.11, so a yarn source bound for 26.x keeps the identity map (a name bridge is step 14).
        List<String> chain = route == null ? List.of() : route.mappedChain();
        boolean routed = chain.size() >= 2 && !(names.equals("yarn") && Versions.compare(to, Route.FIRST_UNOBFUSCATED) >= 0);
        if (routed) map = build.project.state().resolve("maps/route-" + names + "-" + from + "-" + to + ".json");
        // 26.1 and newer ship with the game's own names and publish no intermediary, so there is nothing to
        // generate: a source already in that namespace compiles, and every difference left is an API change
        // the compiler names. The identity map keeps the repair rounds working on such a target.
        boolean unobfuscated = !routed && Versions.compare(to, "26.1") >= 0 && !options.values.containsKey("map");
        if (routed) {
            boolean current = false;
            if (Files.isRegularFile(map)) {
                var cached = Json.read(map);
                current = chain.equals(ProjectModel.strings(cached.get("chain"))) && names.equals(cached.get("names"));
            }
            if (!current) {
                Files.createDirectories(map.getParent());
                var mapped = tools.run("VersionMap", List.of("--chain", String.join(",", chain), "--names", names, "--out", map.toString()), "mapping-route");
                if (mapped.exit() != 0) throw new IOException("No verified mapping along the route " + String.join(" -> ", chain) + ". " + Run.tail(mapped.log()));
            }
            build.report.stages.add(Json.map("name", "route", "waypoints", route.waypoints, "mapped", chain, "cached", current));
        } else if (unobfuscated) {
            map = build.project.state().resolve("maps/identity-" + names + "-" + to + ".json");
            if (!Files.isRegularFile(map)) {
                Files.createDirectories(map.getParent());
                Files.writeString(map, "{\n  \"from\": \"" + to + "\", \"to\": \"" + to + "\", \"names\": \"" + names + "\",\n"
                        + "  \"classes\": [], \"classesRemoved\": [], \"methods\": [], \"methodsMoved\": [], \"signatures\": [], \"methodsRemoved\": [], \"fields\": [], \"fieldsRemoved\": []\n}\n");
            }
        } else if (!Files.isRegularFile(map)) {
            var mapped = tools.run("VersionMap", List.of(from, to, "--names", names, "--out", map.toString()), "mapping");
            if (mapped.exit() != 0) throw new IOException("No verified mapping for this jump. " + Run.tail(mapped.log()));
        }
        var mapInfo = Json.read(map);
        String mapFrom = routed ? chain.get(0) : from, mapTo = routed ? chain.get(chain.size() - 1) : to;
        if (!unobfuscated && (!mapFrom.equals(mapInfo.get("from")) || !mapTo.equals(mapInfo.get("to")) || !names.equals(mapInfo.get("names")))) throw new IOException("Mapping identity does not match the requested source, target and namespace.");
        Map<String, Object> routeRecord = route == null ? null : route.record();
        if (routeRecord != null) routeRecord.put("mapping", routed ? "one map joined across " + String.join(" -> ", chain) + " by Fabric intermediary ids"
                + (chain.size() < route.versions.size() ? "; updates past " + chain.get(chain.size() - 1) + " have no shared ids and are left to the compiler and rules" : "")
                : "identity (no shared ids on this route)");
        if (!options.has("fix")) {
            if (!options.values.containsKey("log")) return Json.map("ok", true, "tier", "mapping", "map", map.toString(), "route", routeRecord, "gameVerified", false);
            var explain = tools.run("VersionMap", List.of("--map", map.toString(), "--explain", Path.of(options.get("log", "")).toAbsolutePath().toString()), "port-diagnosis");
            return Json.map("ok", explain.exit() == 0, "tier", "diagnosis", "details", Run.tail(explain.log()), "gameVerified", false);
        }
        if (options.has("dry-run")) {
            if (!options.values.containsKey("log")) throw new IllegalArgumentException("A dry-run needs --log <compiler log>; no sources were changed.");
            var preview = tools.run("VersionMap", List.of("--map", map.toString(), "--fix", Path.of(options.get("log", "")).toAbsolutePath().toString(), "--repo", build.project.root.toString(), "--dry-run"), "port-preview");
            return Json.map("ok", preview.exit() == 0, "tier", "preview", "details", Run.tail(preview.log()), "sourcesChanged", false, "gameVerified", false);
        }
        Path learned = options.values.containsKey("rules") ? Path.of(options.get("rules", "")).toAbsolutePath() : null;
        boolean corpusLoaded = learned != null;
        Path transaction = build.project.state().resolve("ports/" + build.report.id);
        Map<String, Object> before = new LinkedHashMap<>();
        Set<Path> backupPaths = new TreeSet<>(sources(build.project));
        for (Target.Edit edit : target) {
            Path file = edit.file().toRealPath();
            if (!file.startsWith(build.project.root)) throw new IOException("Target edit is outside the source project: " + file);
            backupPaths.add(file);
        }
        for (Path file : backupPaths) {
            Path relative = build.project.root.relativize(file), backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent()); Files.copy(file, backup); before.put(relative.toString().replace('\\', '/'), Fingerprints.bytes(Files.readAllBytes(file)));
        }
        // The restore plan is on disk before the first edit: an interrupted port still knows what it may have changed.
        List<String> planned = new ArrayList<>();
        for (Target.Edit edit : target) planned.add(build.project.root.relativize(edit.file().toRealPath()).toString().replace('\\', '/'));
        Json.save(transaction.resolve("transaction.json"), Json.map("root", build.project.root.toString(), "before", before, "after", Map.of(), "removed", List.of(),
                "plan", Json.map("edits", planned, "removals", relative(build.project.root, excluded), "backups", transaction.resolve("before").toString()), "status", "in-progress"));
        Path log; boolean compiled = false; int rounds = Integer.parseInt(options.get("rounds", "5"));
        List<String> removed = new ArrayList<>();
        if (rounds < 1 || rounds > 20) throw new IllegalArgumentException("--rounds must be 1–20");
        try {
            if (!target.isEmpty()) Target.apply(target);
            for (Path file : excluded) {
                Path owned = file.toRealPath();
                if (!owned.startsWith(build.project.root)) throw new IOException("Excluded source is outside the source project: " + owned);
                removed.add(build.project.root.relativize(owned).toString().replace('\\', '/')); Files.delete(owned);
            }
            model = build.model(options.has("refresh"), false);
            if (!versions(model).equals(Set.of(to))) throw new IOException("Evaluated build did not select " + to + ": " + versions(model));
            verifyLoaderVersion(model, to);
            var preflight = tools.run("VersionMap", List.of("--map", map.toString(), "--preflight", "--repo", build.project.root.toString()), "port-preflight");
            if (preflight.exit() != 0) throw new IOException("Typed preflight stopped: " + Run.tail(preflight.log()));
            boolean explicitTask = options.values.containsKey("task");
            List<String> tasks = new ArrayList<>(explicitTask ? List.of(options.get("task", "")) : build.tasks(model, "build"));
            if (tasks.isEmpty()) throw new IOException(explicitTask ? "No compile task selected." : "No evaluated build task; pass --task only for a compile-only port check.");
            excludeOtherTargets(tasks, model);
            tasks.add(0, "-Dmodaccel.recompile=true");
            // Repair rounds only need the compiler; the full build (tests, jar) runs once when the source is clean.
            List<String> repairTasks = new ArrayList<>(explicitTask ? tasks : build.tasks(model, "classes"));
            if (repairTasks.isEmpty()) repairTasks = tasks;
            else excludeOtherTargets(repairTasks, model);
            repairTasks.add(0, "-Dmodaccel.recompile=true");
            var initial = build.gradle(tasks, explicitTask ? "port-initial-compile" : "port-initial-build"); log = initial.log(); compiled = initial.exit() == 0;
            Set<String> seen = new HashSet<>();
            int roundsTried = 0;
            for (int round = 0; !compiled && round < rounds; round++) {
                String state = Fingerprints.files(sources(build.project)); if (!seen.add(state)) break;
                roundsTried = round + 1;
                applyRules(log);
                if (!corpusLoaded) { learned = Corpus.rules(build, model, tools, map, from, to, names); corpusLoaded = true; }
                var args = new ArrayList<>(List.of("--map", map.toString(), "--fix", log.toString(), "--repo", build.project.root.toString()));
                if (learned != null) args.addAll(List.of("--rules", learned.toString()));
                var fix = tools.run("VersionMap", args, "port-fix-" + (round + 1));
                if (fix.exit() != 0) throw new IOException("Typed repair stopped: " + Run.tail(fix.log()));
                if (state.equals(Fingerprints.files(sources(build.project)))) break;
                var compile = build.gradle(repairTasks, (explicitTask ? "port-compile-" : "port-build-") + (round + 1)); log = compile.log(); compiled = compile.exit() == 0;
            }
            var audit = compiled ? tools.audit(model) : null;
            boolean passed = compiled && audit != null && audit.exit() == 0;
            boolean built = !explicitTask && compiled;
            if (passed && (!explicitTask ? roundsTried > 0 : true)) {
                List<String> buildTasks = new ArrayList<>(build.tasks(model, "build"));
                if (!buildTasks.isEmpty()) {
                    excludeOtherTargets(buildTasks, model);
                    var finalBuild = build.gradle(buildTasks, "port-build");
                    built = finalBuild.exit() == 0;
                    if (!built) { passed = false; log = finalBuild.log(); }
                }
            }
            Map<String, Object> metadataCheck = passed ? verifyModMetadata(build.project.root, model, to) : null;
            if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) passed = false;
            Map<String, Object> result = Json.map("ok", passed, "tier", built ? "build-and-mixin-targets" : "compile-and-mixin-targets", "compiled", compiled,
                    "built", built, "mixinTargetsVerified", audit != null && audit.exit() == 0,
                    "modMetadata", metadataCheck,
                    "gameVerified", false, "behaviorVerified", false, "log", log.toString(), "undo", transaction.toString(),
                    "resolvedDependencies", applied, "excludedSources", removed,
                    "selectionFile", selectionFile == null ? null : selectionFile.toString(),
                    "details", passed ? (built ? "Built and " + auditSummary(audit.log()) + ". Gameplay and behavior remain unverified."
                            : "Compiled and " + auditSummary(audit.log()) + ". No standard build task was available; gameplay remains unverified.")
                            + uncheckedText(unchecked) + preReleaseText(applied)
                            : Run.tail(audit != null && audit.exit() != 0 ? audit.log() : log));
            if (!unchecked.isEmpty()) result.put("uncheckedDependencies", unchecked);
            if (audit != null) result.put("mixinAudit", auditSummary(audit.log()));
            List<Object> named = passed ? List.of() : namedErrors(log, routed ? mapInfo : null);
            if (routeRecord != null) result.put("route", hopRecords(routeRecord, routed ? mapInfo : null, named));
            if (!passed) {
                if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) {
                    result.put("stoppedSafely", true);
                    result.put("reason", metadataCheck.get("details"));
                    return result;
                }
                Path repair = transaction.resolve("repair-request.json");
                Json.save(repair, Json.map("root", build.project.root.toString(), "from", from, "to", to, "namespace", names, "map", map.toString(), "model", build.project.state().resolve("model.json").toString(),
                        "log", log.toString(), "mixinLog", audit == null ? null : audit.log().toString(), "diagnostics", Run.tail(audit == null ? log : audit.log()),
                        "requirements", List.of("Preserve existing behavior, including debug hooks and rendering.", "Do not remove required injections or disable checks to make a build pass.", "Rebuild and exercise relevant real game scenarios after a semantic repair.")));
                result.put("repairRequest", repair.toString());
                List<Object> unresolved = compiled ? List.of() : named;
                result.put("unresolved", unresolved);
                result.put("stoppedSafely", true);
                StringBuilder byUpdate = new StringBuilder();
                if (route != null) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (Object item : unresolved) {
                        List<Object> changed = Json.array(Json.object(item).get("changedIn"));
                        String step = changed.isEmpty() ? "not in the mapping history" : Json.object(changed.get(0)).get("step") + (String.valueOf(Json.object(changed.get(0)).get("match")).startsWith("name only") ? " (name only)" : "");
                        counts.merge(step, 1, Integer::sum);
                    }
                    counts.forEach((step, n) -> byUpdate.append(byUpdate.length() == 0 ? " By update: " : "; ").append(step).append(": ").append(n));
                    if (byUpdate.length() > 0) byUpdate.append('.');
                }
                result.put("reason", failureReason(to, compiled, audit, unresolved.size(), roundsTried, byUpdate.toString(), log));
            }
            if (route != null) result.put("rulesApplied", rulesApplied);
            return result;
        } catch (Exception failure) {
            throw new IOException(failure.getMessage() + "\nUndo this port with: port --undo \"" + transaction + "\"", failure);
        } finally { finishTransaction(build.project, transaction, before, removed); }
    }
    /** Report every target dependency as resolved, selected, or blocked without changing the project. */
    Map<String, Object> dependencies(Modaccel.Options options) throws Exception {
        String to = options.get("to", "");
        if (to.isBlank() || !to.matches("[A-Za-z0-9._+\\-]+")) throw new IllegalArgumentException("dependencies needs --to <target Minecraft version>");
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        Target.Setup setup = versions(model).equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied, blocked = setup == null ? List.of() : setup.blockers;
        Set<String> used = new HashSet<>();
        for (var record : applied) used.add(String.valueOf(record.get("artifact")));
        List<Object> unused = new ArrayList<>();
        for (Dependencies.Selection selection : selections) {
            String[] source = Dependencies.coordinate(selection.source());
            if (!used.contains(source[0] + ":" + source[1])) unused.add(selection.source());
        }
        boolean ready = blocked.isEmpty();
        return Json.map("to", to, "selectionFile", selectionFile == null ? null : selectionFile.toString(), "selections", selections.size(),
                "resolved", applied, "blocked", blocked, "unchecked", setup == null ? List.of() : setup.unchecked, "unusedSelections", unused, "ready", ready,
                "details", (ready ? (setup != null && !setup.unchecked.isEmpty() ? "Every checked target dependency resolves; port --fix can apply these changes."
                        : "Every target dependency resolves; port --fix can apply these changes.")
                        : blockerText(blocked)) + (setup == null ? "" : uncheckedText(setup.unchecked) + preReleaseText(applied)));
    }
    Path selectionFile(Modaccel.Options options) throws IOException {
        if (options.values.containsKey("selection")) return Path.of(options.get("selection", "")).toAbsolutePath();
        Path file = build.project.root.resolve(".modaccel/dependencies.json");
        return Files.isRegularFile(file) ? file : null;
    }
    /**
     * Why a port stopped, by what actually failed: the mixin audit, a build that failed for a reason the
     * compiler did not report (shown from Gradle's own failure block), or compiler errors no rule matched.
     */
    static String failureReason(String to, boolean compiled, Run.Result audit, int unresolved, int rounds, String byUpdate, Path log) throws IOException {
        if (compiled && audit != null && audit.exit() != 0)
            return "The code compiled against " + to + ", but the mixin audit did not pass: "
                    + (audit.exit() == 1 ? "it found injections that would fail or do nothing in the game. Each one is listed in details; fix the mixin target, then rerun."
                            : Run.failure(audit.log()).strip());
        if (compiled) return "The code compiled against " + to + ", but the packaged build failed without a compiler error:\n" + Run.gradleFailure(log) + "\nLog: " + log;
        if (unresolved == 0) return "The build failed without compiler errors, so this is not a source migration and no rule can fix it:\n" + Run.gradleFailure(log) + "\nLog: " + log;
        return unresolved + " compiler error(s) remain after " + rounds + " repair round(s) and no typed rule matched."
                + byUpdate + " This needs a semantic migration; the port stopped instead of guessing. Resolve them in source, or add a verified recipe, then rerun.";
    }
    /** What the mixin audit actually looked at, from its summary line: never "checked" when nothing was. */
    static String auditSummary(Path log) throws IOException {
        String summary = "";
        if (log != null && Files.exists(log)) for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) if (line.startsWith("# ")) summary = line.substring(2).strip();
        if (summary.isEmpty()) return "the mixin audit passed without a summary";
        if (summary.startsWith("0 mixins")) return "no mixins to audit";
        Matcher skipped = Pattern.compile("(\\d+) targets not in the jar").matcher(summary);
        return "mixin targets checked (" + summary.replaceFirst(",?\\s*\\d+ targets not in the jar", "") + ")"
                + (skipped.find() ? "; " + skipped.group(1) + " mixin target(s) are in no jar on the classpath and were not checked (see the mixin-audit log)" : "");
    }
    static String uncheckedText(List<Map<String, Object>> unchecked) {
        if (unchecked.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nNot checked for the target version (" + unchecked.size() + "):");
        for (var item : unchecked) text.append("\n  ").append(item.get("artifact")).append(" ").append(item.get("version")).append(": ").append(item.get("reason"));
        return text.toString();
    }
    static String preReleaseText(List<Map<String, Object>> applied) {
        StringBuilder text = new StringBuilder();
        for (var item : applied) if (item.get("preRelease") != null) text.append("\nPre-release chosen: ").append(item.get("preRelease"));
        for (var item : applied) if (item.get("warning") != null) text.append("\nWarning: ").append(item.get("warning"));
        return text.toString();
    }
    static String blockerText(List<Map<String, Object>> blockers) {
        StringBuilder text = new StringBuilder(blockers.size() + " target dependencies need a verified selection before any edit:");
        for (var blocker : blockers) text.append("\n  ").append(blocker.get("artifact")).append(" ").append(blocker.get("version")).append(": ").append(blocker.get("reason"))
                .append("\n    add to .modaccel/dependencies.json: ").append(Json.write(blocker.get("suggestion")));
        return text.toString();
    }
    static List<String> relative(Path root, List<Path> files) {
        return files.stream().map(file -> root.relativize(file).toString().replace('\\', '/')).sorted().toList();
    }
    /** Distinct compiler errors left in a log, so a stopped port names exactly what a person must decide. */
    static List<Object> errors(Path log) throws IOException {
        List<Object> list = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Matcher reported = Pattern.compile("(?m)^\\s*(\\S.*?\\.(?:java|kt)):(\\d+): error: ([^\\r\\n]+)").matcher(Files.readString(log));
        while (reported.find()) {
            String key = reported.group(1) + ":" + reported.group(2) + ":" + reported.group(3);
            if (!seen.add(key)) continue;
            list.add(Json.map("file", reported.group(1), "line", Integer.parseInt(reported.group(2)), "message", reported.group(3)));
        }
        return list;
    }
    /**
     * A loader-derived game version is the one that matters. NeoForge resolves Minecraft from its
     * own artifact, so a changed minecraft_version alone can still compile against the old game.
     */
    static void verifyLoaderVersion(Map<String, Object> model, String to) throws IOException {
        boolean neoforge = false; String resolved = "";
        for (Object item : Json.array(model.get("projects"))) {
            for (Object d : Json.array(Json.object(item).get("dependencies"))) {
                var dep = Json.object(d);
                if (!"net.neoforged".equals(dep.get("group")) || !"neoforge".equals(dep.get("name"))) continue;
                neoforge = true; resolved = dep.get("version") == null ? "" : dep.get("version").toString();
            }
        }
        if (!neoforge) return;
        String prefix = Target.neoForgePrefix(to);
        if (!resolved.startsWith(prefix))
            throw new IOException("The evaluated NeoForge dependency is " + resolved + ", which does not target " + to + " (expected " + prefix + "x). Update the NeoForge version owner and rerun; a passing build would use the old game.");
    }
    /**
     * A repo can build several Minecraft variants from one source. Only the ported target is
     * verified here; nested builds for other versions would fail on the ported sources and are skipped.
     */
    /**
     * A passable build is not a loadable mod: NeoForge refuses a jar whose declared Minecraft
     * range still names the old game. Check the packaged metadata, not just the compiler.
     */
    static Map<String, Object> verifyModMetadata(Path projectDir, Map<String, Object> model, String to) throws IOException {
        boolean neoforge = false, fabric = false;
        for (Object item : Json.array(model.get("projects"))) {
            Set<String> loaders = Support.loaders(Json.object(item));
            if (loaders.contains("neoforge")) neoforge = true;
            if (loaders.contains("fabric")) fabric = true;
        }
        if (!neoforge && fabric) return verifyFabricMetadata(projectDir, to);
        if (!neoforge) return null;
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map("ok", true, "checked", false, "details", "No packaged jar to inspect.");
        Pattern block = Pattern.compile("(?s)\\[\\[dependencies\\.([^\\]]+)\\]\\]([^\\[]*?modId\\s*=\\s*\"minecraft\"[^\\[]*?versionRange\\s*=\\s*\")([^\"]+)\"");
        List<String> jars = new ArrayList<>(), ranges = new ArrayList<>();
        String decidedBy = null;
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(libs)) {
            for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                if (jar.getFileName().toString().matches(".*-(sources|api|javadoc)\\.jar$")) continue;
                candidates.add(jar);
            }
        }
        // Only the freshest jar counts: a stale artifact from an earlier build must not decide the check.
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path jar : candidates) {
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                boolean found = false;
                for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                    var entry = zip.getEntry(name);
                    if (entry == null) continue;
                    String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    Matcher match = block.matcher(text);
                    while (match.find()) {
                        String range = match.group(3);
                        jars.add(jar.getFileName().toString()); ranges.add(range); found = true;
                        // Read as FML reads it: a Maven version range, not text containing the version.
                        MavenRange.Answer answer = MavenRange.contains(range, to, classpath(model));
                        decidedBy = answer.decidedBy();
                        if (answer.error() != null) return Json.map("ok", false, "checked", true, "jar", jar.toString(), "declared", range, "decidedBy", decidedBy,
                                "details", "The packaged mod's Minecraft versionRange \"" + range + "\" is not a valid Maven range (" + answer.error() + "); NeoForge refuses the mod. Fix the range and rerun.");
                        if (!answer.contains()) return Json.map("ok", false, "checked", true, "jar", jar.toString(), "declared", range, "decidedBy", decidedBy,
                                "details", "The packaged mod declares Minecraft " + range + " but was ported to " + to
                                        + "; NeoForge refuses it at runtime and the client would list 0 mods. Update the property behind the metadata range and rerun.");
                    }
                    if (found) break;
                }
                if (found) break;
            }
        }
        return Json.map("ok", true, "checked", !ranges.isEmpty(), "jars", jars, "ranges", ranges, "decidedBy", decidedBy,
                "details", ranges.isEmpty() ? "No Minecraft dependency range in the packaged metadata." : "Packaged metadata accepts " + to + " (range read by " + decidedBy + ").");
    }
    /** Every jar on the evaluated compile classpaths. */
    static List<Path> classpath(Map<String, Object> model) {
        Set<Path> jars = new LinkedHashSet<>();
        for (Object item : Json.array(model.get("projects")))
            if (Json.object(item).get("compile") instanceof List<?> compiles) for (Object c : compiles)
                for (String path : ProjectModel.strings(Json.object(c).get("classpath"))) if (path.endsWith(".jar")) jars.add(Path.of(path));
        return new ArrayList<>(jars);
    }
    /**
     * Fabric refuses a jar whose packaged fabric.mod.json does not accept the running game, even when
     * the source manifest used a placeholder the build expanded. Read the freshest jar, not the source.
     */
    static Map<String, Object> verifyFabricMetadata(Path projectDir, String to) throws IOException {
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map("ok", true, "checked", false, "details", "No packaged jar to inspect.");
        List<Path> candidates;
        try (var stream = Files.list(libs)) {
            candidates = new ArrayList<>(stream.filter(p -> p.toString().endsWith(".jar") && !p.getFileName().toString().matches(".*-(sources|api|javadoc|dev)\\.jar$")).toList());
        }
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path jar : candidates) {
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                var entry = zip.getEntry("fabric.mod.json");
                if (entry == null) continue;
                Map<String, Object> metadata;
                try { metadata = Json.object(Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))); }
                catch (IllegalArgumentException invalid) {
                    return Json.map("ok", false, "checked", true, "jar", jar.toString(), "details", "The packaged fabric.mod.json is not valid JSON: " + invalid.getMessage());
                }
                Object depends = metadata.get("depends") instanceof Map<?, ?> map ? map.get("minecraft") : null;
                if (depends == null) return Json.map("ok", true, "checked", true, "jar", jar.toString(), "details", "The packaged mod declares no Minecraft dependency.");
                List<String> ranges = depends instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(String.valueOf(depends));
                // A list is "any of": Fabric loads the mod when one entry accepts the game.
                boolean accepted = ranges.stream().anyMatch(range -> Versions.matches(range, to));
                if (!accepted) return Json.map("ok", false, "checked", true, "jar", jar.toString(), "declared", ranges,
                        "details", "The packaged mod declares Minecraft " + String.join(" or ", ranges) + " but was ported to " + to
                                + "; Fabric refuses to load it. Update the property or manifest behind that range and rerun.");
                return Json.map("ok", true, "checked", true, "jar", jar.toString(), "declared", ranges, "details", "Packaged metadata accepts " + to + ".");
            }
        }
        return Json.map("ok", true, "checked", false, "details", "No packaged fabric.mod.json to inspect.");
    }
    private static void excludeOtherTargets(List<String> tasks, Map<String, Object> model) {
        for (Object item : Json.array(model.get("projects"))) {
            Object composites = Json.object(item).get("composites");
            if (!(composites instanceof List<?> list)) continue;
            for (Object composite : list) { tasks.add("-x"); tasks.add(composite.toString()); }
        }
    }
    static Set<String> versions(Map<String, Object> model) {        Set<String> actual = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (pins.containsKey(key)) actual.add(pins.get(key).toString());
            for (Object d : Json.array(Json.object(p).get("dependencies"))) {
                var dep = Json.object(d); if ("com.mojang".equals(dep.get("group")) && "minecraft".equals(dep.get("name")) && dep.get("version") != null) actual.add(dep.get("version").toString());
            }
        }
        return actual;
    }
    String inferNames() {
        for (Object p : Json.array(model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins")); if (pins.containsKey("yarn_mappings") || pins.containsKey("yarn_version")) return "yarn";
        }
        return "mojmap";
    }
    /**
     * A mapping change can delete one target of a multi-target mixin. The annotation processor
     * names it and reports an empty match, so remove only that dead name and keep the rest.
     */
    void removeDeadMixinTargets(Path log) throws Exception {
        String errors = Files.readString(log);
        Matcher reported = Pattern.compile("(?m)^\\s*(\\S.*?\\.java):(\\d+): error: Multi-target reference conflict for @\\w+ target (\\w+): \\[\\]").matcher(errors);
        Set<String> repaired = new HashSet<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1)); if (!file.isAbsolute()) file = build.project.root.resolve(file); file = file.normalize();
            if (!file.startsWith(build.project.root) || !Files.isRegularFile(file)) continue;
            String target = reported.group(3);
            if (!repaired.add(file + ":" + target)) continue;
            String before = Files.readString(file), after = removeDeadTarget(before, target);
            Files.writeString(file, after);
            Path lessons = build.project.state().resolve("lessons.jsonl");
            Files.writeString(lessons, Json.write(Json.map("rule", "mixin-removed-target", "target", target,
                    "file", build.project.root.relativize(file).toString().replace('\\', '/'), "to", targetVersion))
                    + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            build.report.stages.add(Json.map("name", "mixin-target-removed", "target", target, "file", file.toString(),
                    "evidence", "mixin annotation processor reported no target for this mapping"));
        }
    }
    /** Remove one quoted method name from a single-line multi-target mixin annotation. */
    static String removeDeadTarget(String text, String target) throws IOException {
        Matcher annotation = Pattern.compile("@(\\w+)\\s*\\(((?:[^()\\n]|\\([^()\\n]*\\))*)\\)").matcher(text);
        while (annotation.find()) {
            Matcher methods = Pattern.compile("method\\s*=\\s*\\{([^}]*)\\}").matcher(annotation.group(2));
            if (!methods.find()) continue;
            List<String> names = new ArrayList<>();
            for (String item : methods.group(1).split(",")) names.add(item.trim());
            String quoted = "\"" + target + "\"";
            if (names.size() < 2 || !names.contains(quoted)) continue;
            names.remove(quoted);
            int base = annotation.start(2);
            String replacement = annotation.group().substring(0, base + methods.start(1) - annotation.start()) + String.join(", ", names)
                    + annotation.group().substring(base + methods.end(1) - annotation.start());
            return text.substring(0, annotation.start()) + replacement + text.substring(annotation.end());
        }
        throw new IOException("The annotation processor reported a dead target " + target
                + ", but no single-line multi-target mixin declaration contains it; repair it by hand");
    }
    void applyRules(Path log) throws Exception {
        removeDeadMixinTargets(log);
        String errors = Files.readString(log);
        // Only the selected project's own sources are repaired; a repo can hold other versions' copies
        // (a Forge 1.20.1 sibling, generated Stonecutter sources) that this port must not touch.
        Set<Path> owned = new HashSet<>();
        for (Object item : Json.array(model.get("projects"))) for (Object set : Json.array(Json.object(item).get("sourceSets"))) {
            for (String dir : ProjectModel.strings(Json.object(set).get("java"))) owned.add(Path.of(dir).toAbsolutePath().normalize());
        }
        List<Path> all = sources(build.project).stream().filter(p -> p.toString().endsWith(".java"))
                .filter(p -> owned.isEmpty() || owned.stream().anyMatch(p.toAbsolutePath().normalize()::startsWith)).toList();
        Set<Path> logged = new HashSet<>();
        Matcher reported = Pattern.compile("(?m)^\\s*(\\S.*?\\.java):[0-9]+: error:").matcher(errors);
        while (reported.find()) { Path file = Path.of(reported.group(1)); if (!file.isAbsolute()) file = build.project.root.resolve(file); file = file.normalize(); if (file.startsWith(build.project.root)) logged.add(file); }
        for (Object item : Json.array(Json.read(build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item); if (!Pattern.compile(rule.get("match").toString()).matcher(errors).find()) continue;
            // A routed port runs a rule only when its jump covers an update the route crosses and its names fit.
            List<String> covered = route == null ? List.of() : route.stepsCovered(Json.text(rule, "jump", ""), namespace);
            if (route != null && covered.isEmpty()) continue;
            for (Path file : all) {
                if (!"repo".equals(rule.get("scope")) && !logged.contains(file)) continue;
                String original = Files.readString(file), text = rewrite(rule, original);
                if (!text.equals(original)) {
                    Files.writeString(file, text);
                    rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
            }
        }
    }
    /** One knowledge rule on one source text: its `when` gate, the literal or regex rewrite, then any imports it needs. */
    static String rewrite(Map<String, Object> rule, String original) {
        var apply = Json.object(rule.get("apply"));
        String text = original;
        String when = Json.text(rule, "when", ""); if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return original;
        if (apply.containsKey("literal")) text = text.replace(apply.get("literal").toString(), Json.text(apply, "replace", ""));
        else if (apply.containsKey("find")) text = Pattern.compile(apply.get("find").toString()).matcher(text).replaceAll(Json.text(apply, "replace", ""));
        if (!text.equals(original)) {
            for (String name : ProjectModel.strings(apply.get("ensureImports"))) if (!text.contains("import " + name + ";")) {
                Matcher header = Pattern.compile("(?m)^package [^;]+;").matcher(text);
                text = header.find() ? text.substring(0, header.end()) + "\nimport " + name + ";" + text.substring(header.end()) : "import " + name + ";\n" + text;
            }
        }
        return text;
    }
    static final Pattern ERROR = Pattern.compile("(?m)^\\s*(\\S.*?\\.(?:java|kt)):(\\d+): error: ([^\\r\\n]+)");
    /**
     * Every distinct compiler error left, each with the update that changed the name it uses when the
     * route's map history knows it. Nothing is dropped: an error the history cannot place still appears,
     * with changedIn empty and the reason.
     */
    List<Object> namedErrors(Path log, Map<String, Object> mapInfo) throws IOException {
        String text = Files.readString(log);
        Map<String, List<Map<String, Object>>> history = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            history.computeIfAbsent(simpleName(String.valueOf(event.get("name"))), k -> new ArrayList<>()).add(event);
            Object to = event.get("to");
            if (to != null) {
                String last = String.valueOf(to).replaceAll("\\(.*", "");
                String name = simpleName(last.contains(".") && !"class".equals(event.get("kind")) ? last.substring(last.lastIndexOf('.') + 1) : last);
                if (!name.equals(simpleName(String.valueOf(event.get("name"))))) history.computeIfAbsent(name, k -> new ArrayList<>()).add(event);
            }
        }
        List<Object> list = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Matcher reported = ERROR.matcher(text);
        List<int[]> spans = new ArrayList<>(); List<String[]> heads = new ArrayList<>();
        while (reported.find()) { spans.add(new int[] { reported.start(), reported.end() }); heads.add(new String[] { reported.group(1), reported.group(2), reported.group(3) }); }
        for (int i = 0; i < spans.size(); i++) {
            String[] head = heads.get(i);
            if (!seen.add(head[0] + ":" + head[1] + ":" + head[2])) continue;
            String block = text.substring(spans.get(i)[1], i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(text.length(), spans.get(i)[1] + 2000));
            Map<String, Object> entry = Json.map("file", head[0], "line", Integer.parseInt(head[1]), "message", head[2]);
            // Placing an error is a report, never a reason to lose it: a failed lookup keeps the error listed.
            if (route != null) try { explain(entry, head[2], block, history); }
            catch (RuntimeException | IOException failure) { entry.put("changedIn", List.of()); entry.put("why", "Could not look this name up in the route history: " + failure.getMessage()); }
            list.add(entry);
        }
        return list;
    }
    static String simpleName(String name) {
        String s = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return s.contains("$") ? s.substring(s.lastIndexOf('$') + 1) : s;
    }
    void explain(Map<String, Object> entry, String message, String block, Map<String, List<Map<String, Object>>> history) throws IOException {
        String symbol = null, kind = null, owner = null;
        Matcher m = Pattern.compile("symbol:\\s+(class|method|variable|constructor|static|interface|enum|record)\\s+(\\w+)").matcher(block);
        if (m.find()) { kind = m.group(1); symbol = m.group(2); }
        Matcher where = Pattern.compile("location:\\s+(?:(?:class|interface|enum|record)\\s+([\\w.$]+)|variable \\w+ of type ([\\w.$]+))").matcher(block);
        if (where.find()) owner = simpleName((where.group(1) != null ? where.group(1) : where.group(2)).replaceAll("<.*", ""));
        Matcher applied = Pattern.compile("(?:method|constructor) (\\w+) in (?:class|interface|enum|record) ([\\w.$]+)").matcher(message);
        Matcher suitable = Pattern.compile("no suitable (?:method|constructor) found for (\\w+)\\(").matcher(message);
        Matcher access = Pattern.compile("(\\w+)(?:\\([^)]*\\))? has (?:private|protected) access in ([\\w.$]+)").matcher(message);
        Matcher pkg = Pattern.compile("package ([\\w.]+) does not exist").matcher(message);
        if (symbol == null && applied.find()) { kind = "method"; symbol = applied.group(1); owner = simpleName(applied.group(2).replaceAll("<.*", "")); }
        else if (symbol == null && suitable.find()) { kind = "method"; symbol = suitable.group(1); }
        else if (symbol == null && access.find()) { kind = "member"; symbol = access.group(1); owner = simpleName(access.group(2)); }
        else if (symbol == null && pkg.find()) {
            Matcher imported = Pattern.compile("(?:import\\s+(?:static\\s+)?)?" + Pattern.quote(pkg.group(1)) + "\\.(\\w+)").matcher(block);
            kind = "class"; symbol = imported.find() ? imported.group(1) : simpleName(pkg.group(1));
        }
        // The source itself narrows a name: an import gives a class its package, and an @Override that no
        // longer overrides names the method and the supertype it was declared in.
        List<String> lines = List.of(), imports = new ArrayList<>();
        Path source = Path.of(String.valueOf(entry.get("file")));
        if (Files.isRegularFile(source)) {
            lines = Files.readAllLines(source);
            Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(String.join("\n", lines));
            while (imp.find()) imports.add(imp.group(1));
        }
        if (symbol == null && message.startsWith("method does not override") && !lines.isEmpty()) {
            int at = (Integer) entry.get("line") - 1;
            String around = String.join("\n", lines.subList(Math.max(0, at), Math.min(lines.size(), at + 4)));
            Matcher declared = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(around.replaceAll("@\\w+(\\([^)]*\\))?", ""));
            Matcher parent = Pattern.compile("\\b(?:extends|implements)\\s+([\\w.]+)").matcher(String.join("\n", lines));
            if (declared.find()) { kind = "method"; symbol = declared.group(1); owner = parent.find() ? simpleName(parent.group(1)) : null; }
        }
        Matcher extendsClause = Pattern.compile("\\bextends\\s+([\\w.]+)").matcher(String.join("\n", lines));
        String parent = extendsClause.find() ? simpleName(extendsClause.group(1)) : null;
        String importedAs = null;
        if (symbol != null) for (String name : imports) if (name.endsWith("." + symbol)) importedAs = name;
        if (symbol != null) { entry.put("symbol", symbol); if (owner != null) entry.put("owner", owner); }
        List<Object> changed = new ArrayList<>();
        if (symbol != null) {
            List<Map<String, Object>> candidates = new ArrayList<>(), exact = new ArrayList<>();
            for (var event : history.getOrDefault(symbol, List.of())) {
                String eventKind = String.valueOf(event.get("kind"));
                boolean fits = switch (kind == null ? "" : kind) {
                    case "class", "interface", "enum", "record" -> eventKind.equals("class");
                    case "method", "constructor" -> eventKind.equals("method");
                    case "variable" -> !eventKind.equals("method"); // a field, or a class used as a qualifier
                    default -> true; // a static import, an access error, or no symbol kind
                };
                if (!fits) continue;
                candidates.add(event);
                String eventOwner = event.get("owner") == null ? null : simpleName(String.valueOf(event.get("owner")));
                String wasOwner = event.get("was") == null ? null : simpleName(String.valueOf(event.get("was")).replaceAll("\\(.*", "").replaceAll("\\.\\w+$", ""));
                if (owner != null && (owner.equals(eventOwner) || owner.equals(wasOwner))) exact.add(event);
                else if (importedAs != null && eventKind.equals("class") && importedAs.equals(String.valueOf(event.get("name")).replace('$', '.'))) exact.add(event);
                // Inherited from the class this file extends: its members, or a nested class used unqualified.
                else if (importedAs == null && parent != null && (eventKind.equals("class") ? String.valueOf(event.get("name")).endsWith("." + parent + "$" + symbol) : parent.equals(eventOwner))) exact.add(event);
            }
            // An imported class names its package; another class with the same simple name is not it.
            if (importedAs != null && exact.isEmpty()) candidates.removeIf(event -> "class".equals(event.get("kind")));
            List<String> decisive = List.of("removed", "signature", "moved", "renamed");
            exact.sort(Comparator.comparingInt(event -> decisive.indexOf(String.valueOf(event.get("what")))));
            // Without an owner match, a 26.x step file that names this owner beats a same-named member elsewhere.
            if (exact.isEmpty() && owner != null) changed.addAll(byName(symbol, owner));
            List<Map<String, Object>> pick = !exact.isEmpty() ? exact : changed.isEmpty() ? candidates : List.of();
            String match = !exact.isEmpty() ? "owner" : "name only: no change on " + (owner == null ? "a known owner" : owner) + "; the same name changed on another class, which may be a supertype";
            for (var event : pick.subList(0, Math.min(3, pick.size()))) {
                String step = String.valueOf(event.get("step"));
                Route.Hop hop = route.hopOf(step);
                String what = String.valueOf(event.get("what")), name = event.get("owner") == null ? String.valueOf(event.get("name")) : event.get("owner") + "." + event.get("name");
                String change = event.get("kind") + " " + name + " " + what + ("removed".equals(what) ? "" : " to " + event.get("to"))
                        + (event.get("was") != null && !String.valueOf(event.get("was")).startsWith(name) && !"signature".equals(what) ? " (then named " + event.get("was") + ")" : "");
                changed.add(Json.map("step", step, "hop", hop == null ? null : hop.from() + " -> " + hop.to(), "change", change, "linked", route.linkOf(step), "match", match));
            }
            if (exact.isEmpty() && pick == candidates && candidates.size() > 3) entry.put("ambiguous", candidates.size() + " changes share this name; the first three are listed");
            if (changed.isEmpty() && owner == null) changed.addAll(byName(symbol, null));
        }
        entry.put("changedIn", changed);
        if (changed.isEmpty()) entry.put("why", symbol == null
                ? "The compiler names no symbol here; this is a type or behaviour change the map cannot place on one update."
                : "No update on this route renamed, moved or removed " + symbol + " in the mapping history; it is an API change the compiler reports here (or a name from a mod dependency).");
    }
    /** Updates into 26.x have no shared ids; look the name up in the change index's own step files when present. */
    List<Object> byName(String symbol, String owner) throws IOException {
        List<Object> found = new ArrayList<>();
        for (String step : route.steps()) {
            if (!"by-name".equals(route.linkOf(step))) continue;
            String[] p = step.split(" -> ");
            boolean forward = Versions.compare(p[0], p[1]) < 0;
            Path file = build.harness.resolve("coverage/changes/" + (forward ? p[0] + "__" + p[1] : p[1] + "__" + p[0]) + ".json");
            if (!Files.isRegularFile(file)) continue;
            var data = Json.read(file);
            String removed = forward ? "Removed" : "Added";
            for (Object c : listOf(data.get("classes" + removed))) if (simpleName(String.valueOf(c)).equals(symbol))
                found.add(Json.map("step", step, "hop", hopName(step), "change", "class " + c + " removed (matched by name)", "linked", "by-name", "match", "26.x step file, matched by name (no shared ids)"));
            for (var e : Json.object(data.get("members")).entrySet()) {
                if (owner != null && !simpleName(e.getKey()).equals(owner)) continue;
                var members = Json.object(e.getValue());
                for (String section : List.of("methods" + removed, "fields" + removed, "methodsChanged", "fieldsChanged", "methodsRenamed", "fieldsRenamed"))
                    for (Object member : listOf(members.get(section))) {
                        String sig = String.valueOf(member).replaceAll("^(?:\\w+ )*", ""), side = forward ? sig : sig.contains(" -> ") ? sig.substring(sig.indexOf(" -> ") + 4) : sig;
                        if (side.startsWith(symbol + "(") || side.startsWith(symbol + ":"))
                            found.add(Json.map("step", step, "hop", hopName(step), "change", e.getKey() + "." + member + " (" + section.replaceAll("^(methods|fields)", "").toLowerCase(Locale.ROOT) + ", matched by name)", "linked", "by-name", "match", "26.x step file, matched by name (no shared ids)"));
                    }
            }
            if (found.size() >= 3) break;
        }
        return found.size() > 3 ? new ArrayList<>(found.subList(0, 3)) : found;
    }
    static List<Object> listOf(Object value) { return value instanceof List<?> ? Json.array(value) : List.of(); }
    String hopName(String step) { Route.Hop hop = route.hopOf(step); return hop == null ? null : hop.from() + " -> " + hop.to(); }
    /** One record per hop: its updates, what the game changed there (names the source's first release had), rules run and errors placed. */
    Map<String, Object> hopRecords(Map<String, Object> record, Map<String, Object> mapInfo, List<Object> named) {
        Map<String, Map<String, Integer>> changes = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            changes.computeIfAbsent(String.valueOf(event.get("step")), k -> new TreeMap<>()).merge(event.get("kind") + "s " + event.get("what"), 1, Integer::sum);
        }
        for (Object item : Json.array(record.get("hops"))) {
            var hop = Json.object(item);
            Set<String> steps = new LinkedHashSet<>();
            for (Object s : Json.array(hop.get("steps"))) {
                var step = Json.object(s); String id = String.valueOf(step.get("step")); steps.add(id);
                step.put("changes", changes.getOrDefault(id, Map.of()));
            }
            List<String> rules = new ArrayList<>();
            rulesApplied.forEach((id, covered) -> { for (String step : covered) if (steps.contains(step)) { rules.add(id); break; } });
            int errors = 0;
            for (Object e : named) {
                List<Object> changed = Json.array(Json.object(e).get("changedIn"));
                if (!changed.isEmpty() && steps.contains(String.valueOf(Json.object(changed.get(0)).get("step")))) errors++;
            }
            hop.put("rulesApplied", rules); hop.put("errorsPlaced", errors);
        }
        return record;
    }
    void finishTransaction(Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(build.project, transaction, before, List.of());
    }
    static void finishTransaction(ProjectModel project, Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(project, transaction, before, List.of());
    }
    static void finishTransaction(ProjectModel project, Path transaction, Map<String, Object> before, List<String> removed) throws Exception {
        Map<String, Object> after = new LinkedHashMap<>(); StringBuilder patch = new StringBuilder();
        for (String relative : before.keySet()) {
            Path file = project.root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                if (!removed.contains(relative)) throw new IOException("Repair unexpectedly removed " + relative + "; backups: " + transaction);
                after.put(relative, null); patch.append("deleted ").append(relative).append('\n'); continue;
            }
            String hash = Fingerprints.bytes(Files.readAllBytes(file));
            if (!hash.equals(before.get(relative))) {
                after.put(relative, hash);
                String old = Files.readString(transaction.resolve("before").resolve(relative)), now = Files.readString(file);
                patch.append("diff --git a/").append(relative).append(" b/").append(relative).append("\n--- a/").append(relative).append("\n+++ b/").append(relative).append('\n');
                List<String> a = old.lines().toList(), b = now.lines().toList();
                patch.append("@@ -1,").append(a.size()).append(" +1,").append(b.size()).append(" @@\n");
                a.forEach(line -> patch.append('-').append(line).append('\n')); b.forEach(line -> patch.append('+').append(line).append('\n'));
            }
        }
        Files.writeString(transaction.resolve("changes.patch"), patch);
        Json.save(transaction.resolve("transaction.json"), Json.map("root", project.root.toString(), "before", before, "after", after, "removed", removed, "status", "complete", "patch", transaction.resolve("changes.patch").toString()));
    }
    Map<String, Object> undo(String id) throws Exception { return undo(build.project, id); }
    static Map<String, Object> undo(ProjectModel project, String id) throws Exception {
        Path transaction = Path.of(id).toAbsolutePath().normalize();
        if (!transaction.startsWith(project.state().resolve("ports"))) throw new IOException("Undo must name this project's recorded port transaction.");
        var state = Json.read(transaction.resolve("transaction.json"));
        if (!project.root.toString().equals(state.get("root"))) throw new IOException("Undo belongs to a different project.");
        String status = Json.text(state, "status", "");
        if (status.equals("undone")) throw new IOException("This port was already undone; nothing to restore. Transaction: " + transaction);
        if (!status.equals("complete")) throw new IOException(interrupted(project, transaction, state));
        var after = Json.object(state.get("after"));
        List<String> removed = ProjectModel.strings(state.get("removed"));
        for (String relative : removed) {
            Path file = project.root.resolve(relative).normalize();
            if (!file.startsWith(project.root)) throw new IOException("Undo entry escapes the project: " + relative);
            if (Files.exists(file)) throw new IOException("Refusing undo: " + relative + " was recreated after porting. Your later edits have been preserved.");
        }
        for (var entry : after.entrySet()) {
            if (removed.contains(entry.getKey())) continue;
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(project.root) || !Files.isRegularFile(file) || !Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue()))
                throw new IOException("Refusing undo: " + entry.getKey() + " changed after porting. Your later edits have been preserved.");
        }
        for (String relative : after.keySet()) Files.copy(transaction.resolve("before").resolve(relative), project.root.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
        state.put("status", "undone"); Json.save(transaction.resolve("transaction.json"), state);
        return Json.map("ok", true, "restoredFiles", after.size(), "transaction", transaction.toString(),
                "details", after.isEmpty() ? "The port changed no files, so there was nothing to restore." : "Restored " + after.size() + " file(s) to their state before the port.");
    }
    /**
     * A port that never finished recorded no "after" state, so an undo cannot prove which files it
     * changed or that they were not edited since. Refuse, and name what differs from the backups.
     */
    static String interrupted(ProjectModel project, Path transaction, Map<String, Object> state) throws Exception {
        Path backups = transaction.resolve("before");
        List<String> changed = new ArrayList<>(), missing = new ArrayList<>();
        for (var entry : Json.object(state.get("before")).entrySet()) {
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(project.root)) continue;
            if (!Files.isRegularFile(file)) missing.add(entry.getKey());
            else if (!Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())) changed.add(entry.getKey());
        }
        StringBuilder out = new StringBuilder("Refusing undo: this port did not finish (status " + Json.text(state, "status", "unknown")
                + "), so it never recorded which files it changed and an undo could overwrite edits made since. Nothing was restored.");
        if (changed.isEmpty() && missing.isEmpty()) out.append("\nEvery backed-up file still matches its backup, so the port changed nothing that needs restoring.");
        else {
            if (!changed.isEmpty()) out.append("\nDiffer from their backup: ").append(String.join(", ", changed));
            if (!missing.isEmpty()) out.append("\nMissing now: ").append(String.join(", ", missing));
            out.append("\nTo restore by hand, copy those files back from ").append(backups).append(" (same relative paths), after checking you made no edits to them since.");
        }
        return out.toString();
    }
}
