import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** Bounded repairs use the existing typed mapper and rule owner, with byte-exact guarded undo. */
final class Port {
    final Build build; Map<String, Object> model; String targetVersion = "";
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
        Set<String> actual = versions(model);
        if (!actual.equals(Set.of(to)) && !actual.equals(Set.of(from))) throw new IllegalArgumentException("Build targets " + actual + "; select the project matching --from or --to.");
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        long targetStart = System.nanoTime();
        Target.Setup setup = actual.equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Target.Edit> target = setup == null ? Target.manifestEditsForModel(model, to) : setup.edits();
        List<Path> excluded = setup == null ? List.of() : setup.excluded;
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied;
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
        Path map = options.values.containsKey("map") ? Path.of(options.get("map", "")).toAbsolutePath() : build.project.state().resolve("maps/" + names + "-" + from + "-" + to + ".json");
        if (!Files.isRegularFile(map)) {
            var mapped = tools.run("VersionMap", List.of(from, to, "--names", names, "--out", map.toString()), "mapping");
            if (mapped.exit() != 0) throw new IOException("No verified mapping for this jump. " + Run.tail(mapped.log()));
        }
        var mapInfo = Json.read(map);
        if (!from.equals(mapInfo.get("from")) || !to.equals(mapInfo.get("to")) || !names.equals(mapInfo.get("names"))) throw new IOException("Mapping identity does not match the requested source, target and namespace.");
        if (!options.has("fix")) {
            if (!options.values.containsKey("log")) return Json.map("ok", true, "tier", "mapping", "map", map.toString(), "gameVerified", false);
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
        Json.save(transaction.resolve("transaction.json"), Json.map("root", build.project.root.toString(), "before", before, "after", Map.of(), "removed", List.of(), "status", "in-progress"));
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
                    "details", passed ? (built ? "Built and mixin targets checked. Gameplay and behavior remain unverified."
                            : "Compiled and mixin targets checked. No standard build task was available; gameplay remains unverified.")
                            : Run.tail(audit != null && audit.exit() != 0 ? audit.log() : log));
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
                List<Object> unresolved = errors(log);
                result.put("unresolved", unresolved);
                result.put("stoppedSafely", true);
                result.put("reason", unresolved.size() + " compiler error(s) remain after " + roundsTried + " repair round(s) and no typed rule matched. "
                        + "This needs a semantic migration; the port stopped instead of guessing. Resolve them in source, or add a verified recipe, then rerun.");
            }
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
                "resolved", applied, "blocked", blocked, "unusedSelections", unused, "ready", ready,
                "details", ready ? "Every target dependency resolves; port --fix can apply these changes."
                        : blockerText(blocked));
    }
    Path selectionFile(Modaccel.Options options) throws IOException {
        if (options.values.containsKey("selection")) return Path.of(options.get("selection", "")).toAbsolutePath();
        Path file = build.project.root.resolve(".modaccel/dependencies.json");
        return Files.isRegularFile(file) ? file : null;
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
        boolean neoforge = false;
        for (Object item : Json.array(model.get("projects"))) if (Support.loaders(Json.object(item)).contains("neoforge")) neoforge = true;
        if (!neoforge) return null;
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map("ok", true, "checked", false, "details", "No packaged jar to inspect.");
        Pattern block = Pattern.compile("(?s)\\[\\[dependencies\\.([^\\]]+)\\]\\]([^\\[]*?modId\\s*=\\s*\"minecraft\"[^\\[]*?versionRange\\s*=\\s*\")([^\"]+)\"");
        List<String> jars = new ArrayList<>(), ranges = new ArrayList<>();
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
                        if (!range.contains(to)) return Json.map("ok", false, "checked", true, "jar", jar.toString(), "declared", range,
                                "details", "The packaged mod declares Minecraft " + range + " but was ported to " + to
                                        + "; NeoForge refuses it at runtime and the client would list 0 mods. Update the property behind the metadata range and rerun.");
                    }
                    if (found) break;
                }
                if (found) break;
            }
        }
        return Json.map("ok", true, "checked", !ranges.isEmpty(), "jars", jars, "ranges", ranges,
                "details", ranges.isEmpty() ? "No Minecraft dependency range in the packaged metadata." : "Packaged metadata accepts " + to + ".");
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
            var apply = Json.object(rule.get("apply"));
            for (Path file : all) {
                if (!"repo".equals(rule.get("scope")) && !logged.contains(file)) continue;
                String original = Files.readString(file), text = original;
                String when = Json.text(rule, "when", ""); if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) continue;
                if (apply.containsKey("literal")) text = text.replace(apply.get("literal").toString(), Json.text(apply, "replace", ""));
                else if (apply.containsKey("find")) text = Pattern.compile(apply.get("find").toString()).matcher(text).replaceAll(Json.text(apply, "replace", ""));
                if (!text.equals(original)) {
                    for (String name : ProjectModel.strings(apply.get("ensureImports"))) if (!text.contains("import " + name + ";")) {
                        Matcher header = Pattern.compile("(?m)^package [^;]+;").matcher(text);
                        text = header.find() ? text.substring(0, header.end()) + "\nimport " + name + ";" + text.substring(header.end()) : "import " + name + ";\n" + text;
                    }
                    Files.writeString(file, text);
                }
            }
        }
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
        return Json.map("ok", true, "restoredFiles", after.size(), "transaction", transaction.toString());
    }
}
