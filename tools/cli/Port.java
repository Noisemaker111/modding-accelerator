import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** Bounded repairs use the existing typed mapper and rule owner, with byte-exact guarded undo. */
final class Port {

    final Build build;
    Map<String, Object> model;
    String targetVersion = "";
    /** The hub route this port walks (null for the one-jump path), its source namespace, and the rules that changed a file. */
    Route route;
    String namespace = "mojmap";
    final Map<String, List<String>> rulesApplied = new LinkedHashMap<>();
    /** The running port's undo record: each file's hash before the port (null for a file the port created) and the files it plans to change. */
    Path transaction;
    final Map<String, Object> before = new LinkedHashMap<>();
    final List<String> planned = new ArrayList<>();
    List<String> plannedRemovals = List.of();

    void saveInProgress() throws IOException {
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                build.project.root.toString(),
                "before",
                before,
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map(
                    "edits",
                    planned,
                    "removals",
                    plannedRemovals,
                    "backups",
                    transaction.resolve("before").toString()
                ),
                "status",
                "in-progress"
            )
        );
    }

    /**
     * Records a file the port is about to change or create that the opening backup did not cover (only sources and build
     * files are backed up up front), before the first write: undo restores it, or deletes it when the port created it.
     */
    void track(Path file) throws Exception {
        String relative = build.project.root
            .relativize(file.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/');
        if (before.containsKey(relative)) return;
        if (Files.isRegularFile(file)) {
            Path backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            before.put(relative, Fingerprints.bytes(Files.readAllBytes(file)));
        } else before.put(relative, null);
        planned.add(relative);
        saveInProgress();
    }

    Port(Build build, Map<String, Object> model) {
        this.build = build;
        this.model = model;
    }

    /**
     * Gradle plugins that only choose the decompiler an IDE shows the game's source with, by plugin id, with the
     * configuration blocks they add. Old builds name versions that are no longer published anywhere they look (owo-lib,
     * Polymer and Satin name loom-quiltflower 1.3.0 and 1.6.0, which lived on a repository that is gone), so the build
     * cannot even be read. Removing such a plugin changes nothing the mod's jar or the game sees.
     */
    static final Map<String, List<String>> DEV_ONLY_PLUGINS = Map.of(
        "io.github.juuxel.loom-quiltflower",
        List.of("quiltflower", "loomQuiltflower"),
        "io.github.juuxel.loom-vineflower",
        List.of("vineflower", "loomVineflower"),
        "io.github.juuxel.loom-quiltflower-mini",
        List.of("quiltflower")
    );

    /** The decompiler-only plugin a failed build read names as missing, or null. */
    static String deadDevPlugin(String failure) {
        if (failure == null) return null;
        Matcher plugin = Pattern.compile("Plugin \\[id: '([^']+)'").matcher(failure);
        return plugin.find() && DEV_ONLY_PLUGINS.containsKey(plugin.group(1)) ? plugin.group(1) : null;
    }

    /** Originals of build scripts changed before the port's transaction exists; the transaction takes them over. */
    static Path pendingOriginals(ProjectModel project) {
        return project.state().resolve("ports/pending-before");
    }

    /**
     * Removes a decompiler-only plugin's declaration and configuration blocks from every build script of the project; each
     * original waits in pendingOriginals until the port's transaction backs it up (or restorePending puts it back).
     */
    static List<String> removeDevPlugin(ProjectModel project, String id) throws IOException {
        // The build's own scripts only: a separate build in a subfolder is not part of this port (ProjectModel.inputs).
        List<Path> scripts = project.inputs().stream().filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?")).toList();
        Pattern declaration = Pattern.compile(
            "(?m)^[ \\t]*(?:id\\s*\\(?\\s*[\"']" +
            Pattern.quote(id) +
            "[\"']\\s*\\)?(?:\\s*version\\s*\\(?\\s*[\"'][^\"']*[\"']\\s*\\)?)?|apply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']" +
            Pattern.quote(id) +
            "[\"']\\s*\\)?)[ \\t]*(?:\\r?\\n|$)"
        );
        List<String> changed = new ArrayList<>();
        for (Path script : scripts) {
            String text = Files.readString(script), after = declaration.matcher(text).replaceAll("");
            if (after.equals(text)) continue;
            for (String block : DEV_ONLY_PLUGINS.get(id)) after = Target.removeStatements(
                after,
                Pattern.compile("(?m)^[ \\t]*" + block + "\\s*\\{")
            );
            Path saved = pendingOriginals(project).resolve(project.root.relativize(script));
            Files.createDirectories(saved.getParent());
            if (!Files.exists(saved)) Files.copy(script, saved);
            Files.writeString(script, after);
            changed.add(project.root.relativize(script).toString().replace('\\', '/'));
        }
        return changed;
    }

    /** Puts back what removeDevPlugin changed when no port transaction took it over. */
    static void restorePending(ProjectModel project) throws IOException {
        Path pending = pendingOriginals(project);
        if (!Files.isDirectory(pending)) return;
        List<Path> files;
        try (var walk = Files.walk(pending)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path saved : files) Files.copy(saved, project.root.resolve(pending.relativize(saved).toString()), StandardCopyOption.REPLACE_EXISTING);
        deletePending(project);
    }

    static void deletePending(ProjectModel project) throws IOException {
        Path pending = pendingOriginals(project);
        if (!Files.isDirectory(pending)) return;
        List<Path> all;
        try (var walk = Files.walk(pending)) {
            all = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) Files.deleteIfExists(p);
    }

    /**
     * One map from a yarn-named source to the unobfuscated game: names, the yarn -> Mojang table of the source release
     * (VersionMap, through Fabric's intermediary), joined with the game's own Mojang-named route map (GameDiff) from the
     * source release to the target. Every key stays in the source's yarn names and every value is the target's name, in
     * the usual map shape, so the typed fixer reads it as it reads any map. The route's history is kept for explaining
     * what is left.
     */
    static Map<String, Object> composeMaps(Map<String, Object> names, Map<String, Object> route) {
        Map<String, String> toMojang = new HashMap<>(), fromMojang = new HashMap<>(), moved = new HashMap<>();
        for (Object o : Json.array(names.get("classes"))) {
            var c = Json.object(o);
            toMojang.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
            fromMojang.put(Json.text(c, "to", ""), Json.text(c, "from", ""));
        }
        for (Object o : Json.array(route.get("classes"))) {
            var c = Json.object(o);
            moved.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
        }
        Set<String> classesGone = new HashSet<>(ProjectModel.strings(route.get("classesRemoved")));
        java.util.function.UnaryOperator<String> mojang = y -> toMojang.getOrDefault(y, y);
        java.util.function.UnaryOperator<String> forward = m -> moved.getOrDefault(m, m);
        java.util.function.UnaryOperator<String> yarn = m -> fromMojang.getOrDefault(m, m);
        List<Object> classes = new ArrayList<>(), classesRemoved = new ArrayList<>();
        Set<String> keyed = new HashSet<>();
        for (var e : toMojang.entrySet()) {
            keyed.add(e.getKey());
            if (classesGone.contains(e.getValue())) classesRemoved.add(e.getKey());
            else classes.add(Json.map("from", e.getKey(), "to", forward.apply(e.getValue())));
        }
        // Classes whose yarn and Mojang names agree are keyed by that name.
        for (var e : moved.entrySet()) if (!fromMojang.containsKey(e.getKey()) && keyed.add(e.getKey())) classes.add(
            Json.map("from", e.getKey(), "to", e.getValue())
        );
        for (String gone : classesGone) if (!fromMojang.containsKey(gone) && keyed.add(gone)) classesRemoved.add(gone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", names.get("from"));
        out.put("to", route.get("to"));
        out.put("names", "yarn");
        out.put("linked", "yarn-bridge");
        out.put(
            "source",
            "yarn -> Mojang names of " + names.get("from") + " (Fabric's yarn and intermediary with Mojang's mappings), joined with " + route.get("source")
        );
        out.put("chain", route.get("chain"));
        out.put("classes", classes);
        out.put("classesRemoved", classesRemoved);
        for (String kind : List.of("methods", "fields")) {
            // Every route change of this kind, by Mojang owner, name and descriptor, with the list it came from.
            Map<String, Map<String, Object>> changed = new HashMap<>();
            Map<String, String> listOf = new HashMap<>();
            List<String> lists = kind.equals("methods") ? List.of("methods", "methodsMoved", "signatures") : List.of("fields");
            for (String list : lists) for (Object o : Json.array(route.get(list))) {
                var m = Json.object(o);
                String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
                changed.put(key, m);
                listOf.put(key, list);
            }
            Set<String> gone = new HashSet<>();
            for (Object o : Json.array(route.get(kind + "Removed"))) {
                var m = Json.object(o);
                gone.add(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""));
            }
            Map<String, List<Object>> result = new LinkedHashMap<>();
            for (String list : lists) result.put(list, new ArrayList<>());
            List<Object> removed = new ArrayList<>();
            Set<String> reached = new HashSet<>();
            for (Object o : Json.array(names.get(kind))) {
                var m = Json.object(o);
                String owner = Json.text(m, "owner", ""), mojangOwner = Json.text(m, "newOwner", mojang.apply(owner));
                String key = mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", "");
                reached.add(key);
                if (gone.contains(key)) {
                    removed.add(Json.map("owner", owner, "from", m.get("from"), "desc", m.get("desc")));
                    continue;
                }
                var next = changed.get(key);
                String list = next == null ? lists.get(0) : listOf.get(key);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("owner", owner);
                entry.put("from", m.get("from"));
                entry.put("desc", m.get("desc"));
                entry.put("to", next == null ? m.get("to") : next.get("to"));
                entry.put("newDesc", next == null ? descriptor(Json.text(m, "newDesc", ""), forward) : next.get("newDesc"));
                String finalOwner = next != null && next.get("newOwner") != null ? Json.text(next, "newOwner", "") : forward.apply(mojangOwner);
                if (!finalOwner.equals(owner)) entry.put("newOwner", finalOwner);
                result.get(list).add(entry);
            }
            // Members whose yarn and Mojang names agree are keyed by the yarn owner and descriptor.
            for (var e : changed.entrySet()) {
                if (reached.contains(e.getKey())) continue;
                var m = e.getValue();
                Map<String, Object> entry = new LinkedHashMap<>(m);
                entry.put("owner", yarn.apply(Json.text(m, "owner", "")));
                entry.put("desc", descriptor(Json.text(m, "desc", ""), yarn));
                result.get(listOf.get(e.getKey())).add(entry);
            }
            for (Object o : Json.array(route.get(kind + "Removed"))) {
                var m = Json.object(o);
                String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
                if (!reached.contains(key)) removed.add(
                    Json.map("owner", yarn.apply(Json.text(m, "owner", "")), "from", m.get("from"), "desc", descriptor(Json.text(m, "desc", ""), yarn))
                );
            }
            out.putAll(result);
            out.put(kind + "Removed", removed);
        }
        out.put("history", route.getOrDefault("history", List.of()));
        // The game's call-site rewrites, as they are (for code the map has already moved to Mojang's names) and, where the
        // removed member is one of the source release's, keyed by its yarn name too (what javac reports before then).
        Map<String, Map<String, Object>> yarnOf = new HashMap<>();
        for (Object o : Json.array(names.get("methods"))) {
            var m = Json.object(o);
            String owner = Json.text(m, "owner", ""), mojangOwner = Json.text(m, "newOwner", mojang.apply(owner));
            yarnOf.put(mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", ""), m);
        }
        List<Object> rewrites = new ArrayList<>();
        for (Object o : Json.array(route.get("rewrites"))) {
            var r = Json.object(o);
            rewrites.add(r);
            var y = yarnOf.get(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""));
            if (y == null) continue;
            Map<String, Object> yarnKeyed = new LinkedHashMap<>(r);
            yarnKeyed.put("owner", y.get("owner"));
            yarnKeyed.put("from", y.get("from"));
            yarnKeyed.put("desc", y.get("desc"));
            rewrites.add(yarnKeyed);
        }
        out.put("rewrites", rewrites);
        List<Object> classRewrites = new ArrayList<>();
        for (Object o : Json.array(route.getOrDefault("classRewrites", List.of()))) {
            var r = Json.object(o);
            classRewrites.add(r);
            String yarnName = fromMojang.get(Json.text(r, "from", ""));
            if (yarnName == null) continue;
            Map<String, Object> yarnKeyed = new LinkedHashMap<>(r);
            yarnKeyed.put("from", yarnName);
            classRewrites.add(yarnKeyed);
        }
        out.put("classRewrites", classRewrites);
        return out;
    }

    /** The game's class rewrites (Mojang names) in yarn: the removed class as the source names it, its replacement as the target. */
    static List<Object> yarnClassRewrites(List<Object> game, Map<String, Object> source, Map<String, Object> target) {
        Map<String, String> sourceYarn = new HashMap<>(), targetYarn = new HashMap<>();
        for (Object o : Json.array(source.get("classes"))) sourceYarn.put(Json.text(Json.object(o), "to", ""), Json.text(Json.object(o), "from", ""));
        for (Object o : Json.array(target.get("classes"))) targetYarn.put(Json.text(Json.object(o), "to", ""), Json.text(Json.object(o), "from", ""));
        List<Object> out = new ArrayList<>();
        for (Object o : game) {
            var r = Json.object(o);
            String from = sourceYarn.get(Json.text(r, "from", "")), to = targetYarn.get(Json.text(r, "to", ""));
            if (from == null || to == null) continue;
            Map<String, Object> yarn = new LinkedHashMap<>(r);
            yarn.put("from", from);
            yarn.put("to", to);
            out.add(yarn);
        }
        return out;
    }

    /**
     * A yarn route (Fabric's tables) knows names, not what the game's own code does at a call site: the game's call-site
     * rewrites come from GameDiff over the same releases, in Mojang's names, and are added to the route map in yarn names,
     * the removed member by the source release's table and its replacement by the target's. A rewrite either side cannot
     * name in yarn is left out; without GameDiff or a table, the route map stays as it was.
     */
    static void yarnRewrites(JavaTools tools, List<String> chain, String from, String to, Path map) {
        try {
            Path game = map.resolveSibling(map.getFileName().toString().replace(".json", ".game.json"));
            var diff = tools.run("GameDiff", List.of("--chain", String.join(",", chain), "--out", game.toString()), "mapping-rewrites");
            if (diff.exit() != 0) return;
            Map<String, Map<String, Object>> tables = new HashMap<>();
            for (String version : new LinkedHashSet<>(List.of(from, to))) {
                Path table = map.resolveSibling("names-yarn-" + version + ".json");
                if (!Files.isRegularFile(table)) {
                    var made = tools.run(
                        "VersionMap",
                        List.of(version, version, "--from-names", "yarn", "--to-names", "mojmap", "--names", "yarn", "--out", table.toString()),
                        "mapping-yarn-" + version
                    );
                    if (made.exit() != 0) return;
                }
                tables.put(version, Json.read(table));
            }
            var gameMap = Json.read(game);
            Map<String, Object> route = new LinkedHashMap<>(Json.read(map));
            // What Fabric's tables call removed but the game's own map links (1.20.2: PacketByteBuf.getVarIntLength, Mojang's
            // FriendlyByteBuf.getVarIntSize, moved to VarInt.getByteSize): those links are rewrites too, static-ness read
            // from the call.
            Set<String> removed = new HashSet<>();
            for (Object o : Json.array(route.getOrDefault("methodsRemoved", List.of()))) {
                var m = Json.object(o);
                removed.add(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""));
            }
            List<Object> linked = new ArrayList<>();
            for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(gameMap.getOrDefault(list, List.of()))) {
                var m = Json.object(o);
                Map<String, Object> asRewrite = new LinkedHashMap<>();
                asRewrite.put("owner", m.get("owner"));
                asRewrite.put("from", m.get("from"));
                asRewrite.put("desc", m.get("desc"));
                asRewrite.put("toOwner", m.getOrDefault("newOwner", m.get("owner")));
                asRewrite.put("to", m.get("to"));
                asRewrite.put("newDesc", m.getOrDefault("newDesc", m.get("desc")));
                linked.add(asRewrite);
            }
            List<Object> rewrites = yarnRewrites(Json.array(gameMap.get("rewrites")), tables.get(from), tables.get(to));
            for (Object o : yarnRewrites(linked, tables.get(from), tables.get(to))) {
                var r = Json.object(o);
                if (removed.contains(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""))) rewrites.add(r);
            }
            List<Object> classRewrites = yarnClassRewrites(Json.array(gameMap.getOrDefault("classRewrites", List.of())), tables.get(from), tables.get(to));
            route.put("rewrites", rewrites);
            route.put("classRewrites", classRewrites);
            saveMap(map, route);
        } catch (Exception unavailable) {
            System.err.println("Warning: the game's call-site rewrites are not in this yarn route: " + unavailable.getMessage());
        }
    }

    /** The game's rewrites (Mojang names) in yarn: the removed member as the source table names it, the replacement as the target's. */
    static List<Object> yarnRewrites(List<Object> game, Map<String, Object> source, Map<String, Object> target) {
        Map<String, Map<String, Object>> sourceMember = new HashMap<>(), targetMember = new HashMap<>();
        for (var side : List.of(Map.entry(source, sourceMember), Map.entry(target, targetMember))) {
            Map<String, String> mojangClass = new HashMap<>();
            for (Object o : Json.array(side.getKey().get("classes"))) {
                var c = Json.object(o);
                mojangClass.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
            }
            for (Object o : Json.array(side.getKey().get("methods"))) {
                var m = Json.object(o);
                String owner = Json.text(m, "owner", "");
                String mojangOwner = Json.text(m, "newOwner", mojangClass.getOrDefault(owner, owner));
                side.getValue().put(mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", ""), m);
            }
        }
        List<Object> out = new ArrayList<>();
        for (Object o : game) {
            var r = Json.object(o);
            var old = sourceMember.get(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""));
            var neu = targetMember.get(Json.text(r, "toOwner", "") + "." + Json.text(r, "to", "") + Json.text(r, "newDesc", ""));
            boolean deleted = Json.text(r, "to", "").isEmpty();
            if (old == null || (neu == null && !deleted)) continue;
            Map<String, Object> yarn = new LinkedHashMap<>(r);
            yarn.put("owner", old.get("owner"));
            yarn.put("from", old.get("from"));
            yarn.put("desc", old.get("desc"));
            if (deleted) {
                out.add(yarn); // a call the game deleted has nothing to name on the target side
                continue;
            }
            yarn.put("toOwner", neu.get("owner"));
            yarn.put("to", neu.get("from"));
            yarn.put("newDesc", neu.get("desc"));
            out.add(yarn);
        }
        return out;
    }

    /** A JVM descriptor with each class name passed through names (dotted names, as the maps keep them). */
    static String descriptor(String desc, java.util.function.UnaryOperator<String> names) {
        Matcher type = Pattern.compile("L([^;]+);").matcher(desc);
        StringBuilder out = new StringBuilder();
        while (type.find()) type.appendReplacement(
            out,
            Matcher.quoteReplacement("L" + names.apply(type.group(1).replace('/', '.')).replace('.', '/') + ";")
        );
        return type.appendTail(out).toString();
    }

    /** Writes a map in the generators' own layout (one entry per line under each list), which the typed fixer reads line by line. */
    static void saveMap(Path file, Map<String, Object> map) throws IOException {
        StringBuilder out = new StringBuilder("{\n");
        List<String> keys = new ArrayList<>(map.keySet());
        for (int i = 0; i < keys.size(); i++) {
            Object value = map.get(keys.get(i));
            out.append("  ").append(Json.write(keys.get(i))).append(": ");
            if (value instanceof List<?> list) {
                out.append("[\n");
                for (int j = 0; j < list.size(); j++) {
                    Object item = list.get(j);
                    out.append("    ");
                    if (item instanceof Map<?, ?> entry) {
                        List<String> fields = new ArrayList<>();
                        for (var f : entry.entrySet()) fields.add(Json.write(String.valueOf(f.getKey())) + ": " + Json.write(f.getValue()));
                        out.append("{ ").append(String.join(", ", fields)).append(" }");
                    } else out.append(Json.write(item));
                    out.append(j + 1 < list.size() ? ",\n" : "\n");
                }
                out.append("  ]");
            } else out.append(Json.write(value));
            out.append(i + 1 < keys.size() ? ",\n" : "\n");
        }
        Files.writeString(file, out.append("}\n").toString(), StandardCharsets.UTF_8);
    }

    Explain.Index supertypeIndex;

    /**
     * The supertypes (simple names) of every receiver type the log names in a "location:" line, read from the target
     * build's own classpath, one "Type Super1 Super2 ..." line each, for the typed fixer: a member renamed on Player is
     * renamed where javac looked it up on LocalPlayer. Null when the log names none.
     */
    Path supertypes(Path log) throws IOException {
        String text = logText(log);
        Matcher located = Pattern.compile("location: (?:variable \\w+ of type |class |interface )([\\w.$]+)").matcher(text);
        Set<String> types = new TreeSet<>();
        while (located.find()) types.add(located.group(1).replaceAll("<.*", ""));
        Map<String, Set<String>> overriders = overridingClasses(text);
        if (types.isEmpty() && overriders.isEmpty()) return null;
        if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
        StringBuilder out = new StringBuilder();
        for (String type : types) {
            List<String> found = supertypeIndex.resolve(type, List.of());
            if (found.isEmpty()) {
                // A class of the mod itself: its game supertypes, read through its own source.
                String simple = simpleName(type.replace('/', '.'));
                Set<String> parents = new LinkedHashSet<>();
                for (Path file : sources(build.project)) if (file.getFileName().toString().equals(simple + ".java")) {
                    sourceParents(Files.readString(file), simple, parents, 0);
                    break;
                }
                Set<String> supers = new LinkedHashSet<>();
                for (String parent : parents) {
                    supers.add(parent);
                    supers.addAll(gameSupertypes(parent));
                }
                if (supers.isEmpty()) continue;
                out.append(simple);
                for (String parent : supers) out.append(' ').append(simpleName(parent.replace('/', '.')));
                out.append('\n');
                continue;
            }
            Set<String> supers = gameSupertypes(found.get(0));
            if (supers.isEmpty()) continue;
            out.append(simpleName(type.replace('/', '.')));
            for (String parent : supers) out.append(' ').append(simpleName(parent.replace('/', '.')));
            out.append('\n');
        }
        // A mod class whose @Override stopped overriding: its own game supertypes, so the fixer renames the method the
        // way the game renamed it on that supertype (Screen.render -> extractRenderState), not on some other owner.
        for (var entry : overriders.entrySet()) {
            Set<String> supers = new LinkedHashSet<>();
            for (String parent : entry.getValue()) {
                supers.add(parent.replace('/', '.'));
                supers.addAll(gameSupertypes(parent));
            }
            if (supers.isEmpty()) continue;
            out.append(entry.getKey());
            for (String parent : supers) out.append(' ').append(simpleName(parent.replace('/', '.')));
            out.append('\n');
        }
        if (out.length() == 0) return null;
        Path file = log.resolveSibling(log.getFileName().toString().replaceFirst("\\.log$", "") + "-supertypes.txt");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** Every supertype of a classpath class (internal names), nearest first. */
    Set<String> gameSupertypes(String internal) {
        Set<String> supers = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(internal));
        while (!queue.isEmpty()) {
            Explain.ClassInfo info = supertypeIndex.info(queue.poll());
            if (info == null) continue;
            List<String> next = new ArrayList<>(info.interfaces());
            if (info.superName() != null && !info.superName().equals("java/lang/Object")) next.add(0, info.superName());
            for (String parent : next) if (supers.add(parent)) queue.add(parent);
        }
        return supers;
    }

    /**
     * For each "method does not override" error, the enclosing class (simple name) and the classpath classes it extends
     * or implements (internal names). A parent the mod declares itself is followed through its own source, up to four
     * levels, until it reaches a class of the classpath.
     */
    Map<String, Set<String>> overridingClasses(String log) throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher error = Pattern.compile("(?m)^\\s*" + FILE + ":(\\d+): error: method does not override").matcher(log);
        while (error.find()) {
            Path file = Path.of(error.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            if (!Files.isRegularFile(file)) continue;
            List<String> lines = Files.readAllLines(file);
            int line = Integer.parseInt(error.group(2));
            String enclosing = null;
            for (int back = Math.min(lines.size(), line) - 1; back >= 0 && enclosing == null; back--) {
                Matcher type = Pattern.compile("\\b(?:class|enum|record)\\s+(\\w+)").matcher(lines.get(back));
                if (type.find()) enclosing = type.group(1);
            }
            if (enclosing == null || out.containsKey(enclosing)) continue;
            if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
            Set<String> parents = new LinkedHashSet<>();
            sourceParents(String.join("\n", lines), enclosing, parents, 0);
            if (!parents.isEmpty()) out.put(enclosing, parents);
        }
        return out;
    }

    /** The classpath classes a class declared in this source extends or implements, through the mod's own classes. */
    void sourceParents(String source, String type, Set<String> into, int depth) throws IOException {
        if (depth > 4) return;
        Matcher declared = Pattern.compile(
            "\\b(?:class|enum|record)\\s+" + Pattern.quote(type) + "\\b[^{]*?(?:\\bextends\\s+([\\w.]+)(?:<[^{]*?>)?)?\\s*(?:\\bimplements\\s+([^{]+))?\\{"
        ).matcher(source);
        if (!declared.find()) return;
        List<String> imports = new ArrayList<>();
        Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(source);
        while (imp.find()) imports.add(imp.group(1));
        List<String> named = new ArrayList<>();
        if (declared.group(1) != null) named.add(declared.group(1));
        if (declared.group(2) != null) for (String part : declared.group(2).replaceAll("<[^<>]*>", "").split(",")) {
            if (!part.isBlank()) named.add(part.trim());
        }
        for (String parent : named) {
            List<String> found = supertypeIndex.resolve(parent, imports);
            if (!found.isEmpty() && !found.get(0).startsWith("java/")) {
                into.add(found.get(0));
                continue;
            }
            // The mod's own class: read where it is declared.
            String simple = parent.substring(parent.lastIndexOf('.') + 1);
            for (Path other : sources(build.project)) {
                if (!other.getFileName().toString().equals(simple + ".java")) continue;
                sourceParents(Files.readString(other), simple, into, depth + 1);
                break;
            }
        }
    }

    /**
     * A class javac cannot find in a file that does not import it (a rule wrote RenderLayer::getGuiTextured, a class moved
     * package) is imported when exactly one class of the target game or loader has that simple name. Classes of the mod
     * itself, and names two game classes share, are left to the compiler. Returns the files changed.
     */
    int importMissingClasses(Path log) throws Exception {
        String errors = logText(log);
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + FILE + ":[0-9]+: error: cannot find symbol\\R(?:.*\\R){0,2}?\\s*symbol:\\s+(?:class|variable) ([A-Z]\\w*)\\s*$"
        ).matcher(errors);
        Map<Path, Set<String>> wanted = new LinkedHashMap<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            file = file.normalize();
            if (file.startsWith(build.project.root) && Files.isRegularFile(file)) wanted
                .computeIfAbsent(file, f -> new LinkedHashSet<>())
                .add(reported.group(2));
        }
        if (wanted.isEmpty()) return 0;
        if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
        int changed = 0;
        for (var entry : wanted.entrySet()) {
            String text = Files.readString(entry.getKey()), after = text;
            for (String simple : entry.getValue()) {
                if (Pattern.compile("(?m)^import\\s+[\\w.]+\\." + simple + "\\s*;").matcher(after).find()) continue;
                if (Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + simple + "\\b").matcher(after).find()) continue;
                List<String> game = new ArrayList<>();
                for (String name : supertypeIndex.bySimple.getOrDefault(simple, List.of())) if (
                    !name.contains("$") &&
                    (name.startsWith("net/minecraft/") || name.startsWith("com/mojang/") || name.startsWith("net/fabricmc/") || name.startsWith("net/neoforged/"))
                ) game.add(name);
                if (game.size() != 1) continue;
                after = addImport(after, game.get(0).replace('/', '.'));
            }
            if (!after.equals(text)) {
                Files.writeString(entry.getKey(), after);
                changed++;
            }
        }
        if (changed > 0) rulesApplied.putIfAbsent("import-missing-game-class", List.of());
        return changed;
    }

    /** The route map this port uses (for mixin target names, which javac never checks). */
    Path mapFile;
    Map<String, List<String[]>> mapMethods;

    /**
     * The target's name for a method a mixin targets by its old name: Mixin says it found no 'initTabNavigation' in
     * OptionsScreen, and the map renamed initTabNavigation to refreshWidgetPositions on Screen, OptionsScreen's supertype.
     * Null unless exactly one new name fits the owner or one of its supertypes in the target.
     */
    String mixinTargetRename(String owner, String name) throws Exception {
        if (mapFile == null || !Files.isRegularFile(mapFile)) return null;
        if (mapMethods == null) {
            mapMethods = new HashMap<>();
            var map = Json.read(mapFile);
            for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(map.get(list))) {
                var m = Json.object(o);
                String from = Json.text(m, "from", ""), to = Json.text(m, "to", "");
                if (!from.equals(to)) mapMethods
                    .computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new String[] { simpleName(Json.text(m, "owner", "")), to });
            }
        }
        List<String[]> candidates = mapMethods.get(name);
        if (candidates == null) return null;
        if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
        Set<String> owners = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!owners.add(simpleName(next.replace('/', '.')))) continue;
            Explain.ClassInfo info = supertypeIndex.info(next);
            if (info == null) continue;
            if (info.superName() != null) queue.add(info.superName());
            queue.addAll(info.interfaces());
        }
        Set<String> names = new HashSet<>();
        for (String[] c : candidates) if (owners.contains(c[0])) names.add(c[1]);
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /**
     * Drops from a route map the member renames knowledge/meaning-changes.json says change what the member returns
     * (1.21.2's getMaxBuildHeight -> getMaxY is one lower), so the typed fixer never applies them; the rules that keep
     * the old meaning run first, and what they cannot rewrite safely stays a reported error. Returns the lines dropped.
     */
    static int withoutMeaningChanges(Path map, Path knowledge) throws IOException {
        if (!Files.isRegularFile(map) || !Files.isRegularFile(knowledge)) return 0;
        List<String[]> pairs = new ArrayList<>();
        for (Object o : Json.array(Json.read(knowledge).get("changes"))) {
            var c = Json.object(o);
            pairs.add(new String[] { Json.text(c, "from", ""), Json.text(c, "to", "") });
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(map, StandardCharsets.UTF_8));
        String section = "";
        int dropped = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher heading = Pattern.compile("^  \"(\\w+)\": \\[").matcher(line);
            if (heading.find()) {
                section = heading.group(1);
                continue;
            }
            if (!List.of("methods", "methodsMoved", "signatures").contains(section)) continue;
            for (String[] pair : pairs) if (
                line.contains("\"from\": \"" + pair[0] + "\"") && line.contains("\"to\": \"" + pair[1] + "\"")
            ) {
                lines.remove(i--);
                dropped++;
                break;
            }
        }
        if (dropped == 0) return 0;
        // A list whose last entry went keeps no trailing comma.
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).startsWith("  ]") && lines.get(i - 1).endsWith(",")) lines.set(
            i - 1,
            lines.get(i - 1).substring(0, lines.get(i - 1).length() - 1)
        );
        Files.write(map, lines, StandardCharsets.UTF_8);
        return dropped;
    }

    /**
     * A build log as text: javac on Windows writes in the console's code page, so bytes that are not UTF-8 never stop a
     * port, and ends lines with \r\n, which become \n: a rule's match spans lines with \n.
     */
    static String logText(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static List<Path> sources(ProjectModel project) throws IOException {
        List<Path> paths = new ArrayList<>(project.inputs());
        Files.walkFileTree(
            project.root,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return (
                            !dir.equals(project.root) &&
                            (ProjectModel.IGNORED.contains(dir.getFileName().toString()) ||
                                Files.exists(dir.resolve(".git")) ||
                                ProjectModel.separateBuild(dir))
                        )
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (
                        attributes.isRegularFile() &&
                        (file.toString().endsWith(".java") || file.toString().endsWith(".kt"))
                    ) paths.add(file);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        return new TreeSet<>(paths).stream().toList();
    }

    Map<String, Object> run(Mmt.Options options) throws Exception {
        if (options.values.containsKey("undo")) return undo(options.get("undo", ""));
        String pending = interruptedText(build.project);
        if (pending != null) throw new IOException(
            pending +
            "\nUndo it before porting again, so the next port starts from the original files: mmt port --root \"" +
            build.project.root +
            "\" --undo"
        );
        String from = options.get("from", ""), to = options.get("to", "");
        if (from.isBlank() || to.isBlank()) throw new IllegalArgumentException(
            "Port needs --from <version> --to <version>; --fix selects supported target dependencies and repairs sources with guarded undo."
        );
        if (
            !from.matches("[A-Za-z0-9._+\\-]+") || !to.matches("[A-Za-z0-9._+\\-]+")
        ) throw new IllegalArgumentException("Invalid Minecraft version");
        targetVersion = to;
        learnFromMods = options.has("learn-from-mods");
        Map<String, Object> refusal = Route.blocked(build.harness, from, to);
        if (refusal != null) return refusal;
        // --check fingerprint: what the mod does on its own version, recorded before any file changes (FINGERPRINT.md).
        if ("fingerprint".equals(options.get("check", "")) && !options.has("dry-run")) behaviourBefore = behaviour(from, "fingerprint-before");
        // --direct keeps the one-jump map (A -> B joined in one diff); an explicit --map is used as given.
        route = options.has("direct") || options.values.containsKey("map") ? null : Route.plan(build.harness, from, to);
        Set<String> actual = versions(model);
        PluginCheck.Api plugin = actual.isEmpty() ? PluginCheck.api(model) : null;
        if (plugin != null) throw new IllegalArgumentException(
            "This is a server plugin: it builds against " +
            plugin.kind() +
            " " +
            plugin.version() +
            " (Paper " +
            PluginCheck.line(plugin.version()) +
            "), not a mod. " +
            "port does not move plugins between Paper versions yet. The move is usually config only: the " +
            plugin.kind() +
            " version in the build script, api-version in plugin.yml, " +
            "and the Java version (Paper 1.21.x runs on Java 21, 26.x on Java 25). Then mmt compile --task :jar and mmt plugin-check show whether it builds and enables. Nothing was changed."
        );
        if (!actual.equals(Set.of(to)) && !actual.equals(Set.of(from))) {
            String builds = actual.isEmpty()
                ? "no Minecraft version mmt could read"
                : "Minecraft " + String.join(", ", actual);
            throw new IllegalArgumentException(
                "--from " +
                from +
                " does not match this project: it builds for " +
                builds +
                ". " +
                (actual.size() == 1 ? "Use --from " + actual.iterator().next() + ", or select" : "Select") +
                " the project that builds " +
                from +
                " with --project (mmt setup lists them). Nothing was changed."
            );
        }
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        long targetStart = System.nanoTime();
        Target.Setup setup = actual.equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Target.Edit> target = setup == null ? Target.manifestEditsForModel(model, to) : setup.edits();
        List<Path> excluded = setup == null ? List.of() : setup.excluded;
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied;
        List<Map<String, Object>> unchecked = setup == null ? List.of() : setup.unchecked;
        build.report.stages.add(
            Json.map(
                "name",
                "target-dependencies",
                "seconds",
                (System.nanoTime() - targetStart) / 1e9,
                "changedFiles",
                target.size(),
                "resolved",
                applied.size(),
                "blocked",
                setup == null ? 0 : setup.blockers.size(),
                "selections",
                selections.size()
            )
        );
        if (setup != null && !setup.blockers.isEmpty()) return Json.map(
            "ok",
            false,
            "tier",
            "dependency-blockers",
            "blocked",
            setup.blockers,
            "resolvedDependencies",
            applied,
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "sourcesChanged",
            false,
            "details",
            blockerText(setup.blockers)
        );
        if (!target.isEmpty() && (!options.has("fix") || options.has("dry-run"))) return Json.map(
            "ok",
            true,
            "tier",
            "target-preview",
            "route",
            route == null ? null : route.record(),
            "sourcesChanged",
            false,
            "edits",
            target
                .stream()
                .map(e -> Json.map("file", e.file().toString(), "before", e.before(), "after", e.after()))
                .toList(),
            "resolvedDependencies",
            applied,
            "excludedSources",
            relative(build.project.root, excluded),
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "details",
            "Published target dependencies resolved; --fix applies them with guarded undo."
        );

        JavaTools tools = new JavaTools(build);
        String names = options.get("names", inferNames());
        if (
            !Set.of("yarn", "mojmap").contains(names) ||
            !from.matches("[A-Za-z0-9._+\\-]+") ||
            !to.matches("[A-Za-z0-9._+\\-]+")
        ) throw new IllegalArgumentException("Invalid version or mapping namespace");
        namespace = names;
        Path map = options.values.containsKey("map")
            ? Path.of(options.get("map", "")).toAbsolutePath()
            : build.project.state().resolve("maps/" + names + "-" + from + "-" + to + ".json");
        // A routed port joins every update it crosses into one map: old names resolve straight to final
        // names and each removal keeps the update that removed it. Only releases before 26.1 share
        // intermediary ids; the rest of a route into 26.x is left to the compiler and the rules. Yarn has no
        // names past 1.21.11, so a yarn source bound for 26.x keeps the identity map (a name bridge is step 14).
        // A Mojang-named source gets a map derived from the game itself (tools/GameDiff.java): Mojang's client jars and
        // mappings for every release on the route, 26.x included, linked release to release from their own bytecode, plus
        // NeoForge's own published classes; no intermediary. A yarn source's names are Fabric's, keyed by intermediary,
        // so it keeps the intermediary map; so does --intermediary.
        Set<String> sourceLoaders = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) sourceLoaders.addAll(Support.loaders(Json.object(p)));
        boolean own = names.equals("mojmap") && route != null && !options.has("intermediary");
        // A yarn source bound for the unobfuscated game (26.1+) crosses into Mojang's names: its map joins the source
        // release's yarn -> Mojang table with the game's own route map (composeMaps).
        boolean bridge =
            names.equals("yarn") &&
            route != null &&
            !options.has("intermediary") &&
            Versions.compare(to, Route.FIRST_UNOBFUSCATED) >= 0 &&
            Versions.compare(from, Route.FIRST_UNOBFUSCATED) < 0;
        List<String> chain = route == null ? List.of() : own || bridge ? route.versions : route.mappedChain();
        boolean routed =
            chain.size() >= 2 && (bridge || !(names.equals("yarn") && Versions.compare(to, Route.FIRST_UNOBFUSCATED) >= 0));
        // A generated map is named after the tools that made it, so a changed generator makes a new one.
        String generator = own || bridge ? tools.classes.getFileName().toString() : "";
        // A bridged source is in Mojang's names once the map has run, so the Mojang-named rules are the ones that fit it.
        if (bridge) namespace = "mojmap";
        if (routed) map = build.project.state().resolve(
            "maps/" +
            (own
                    ? "game-" + generator.substring(0, Math.min(12, generator.length())) + "-"
                    : bridge ? "bridge-" + generator.substring(0, Math.min(12, generator.length())) + "-" : "route-") +
            names +
            "-" +
            from +
            "-" +
            to +
            ".json"
        );
        String loaderDiff = own && sourceLoaders.contains("neoforge") ? "neoforge" : "";
        // 26.1 and newer ship with the game's own names and publish no intermediary, so there is nothing to
        // generate: a source already in that namespace compiles, and every difference left is an API change
        // the compiler names. The identity map keeps the repair rounds working on such a target.
        boolean unobfuscated = !routed && Versions.compare(to, "26.1") >= 0 && !options.values.containsKey("map");
        if (routed) {
            boolean current = false;
            if (Files.isRegularFile(map)) {
                var cached = Json.read(map);
                String cachedLoader = cached.get("loader") instanceof Map<?, ?> loader
                    ? String.valueOf(loader.get("name"))
                    : "";
                current =
                    chain.equals(ProjectModel.strings(cached.get("chain"))) &&
                    names.equals(cached.get("names")) &&
                    (bridge ? "yarn-bridge".equals(cached.get("linked")) : own == "game-bytecode".equals(cached.get("linked"))) &&
                    (!own || loaderDiff.equals(cachedLoader));
            }
            if (!current && bridge) {
                Files.createDirectories(map.getParent());
                Path game = map.resolveSibling(map.getFileName().toString().replace(".json", ".game.json"));
                Path yarnNames = map.resolveSibling(map.getFileName().toString().replace(".json", ".names.json"));
                var routeMap = tools.run("GameDiff", List.of("--chain", String.join(",", chain), "--out", game.toString()), "mapping-route");
                if (routeMap.exit() != 0) throw new IOException(
                    "No verified mapping along the route " + String.join(" -> ", chain) + ". " + Run.tail(routeMap.log())
                );
                var namesMap = tools.run(
                    "VersionMap",
                    List.of(from, from, "--from-names", "yarn", "--to-names", "mojmap", "--names", "yarn", "--out", yarnNames.toString()),
                    "mapping-yarn-to-mojang"
                );
                if (namesMap.exit() != 0) throw new IOException("No yarn -> Mojang table for " + from + ". " + Run.tail(namesMap.log()));
                saveMap(map, composeMaps(Json.read(yarnNames), Json.read(game)));
            } else if (!current) {
                Files.createDirectories(map.getParent());
                List<String> arguments = new ArrayList<>(List.of("--chain", String.join(",", chain), "--out", map.toString()));
                if (own && !loaderDiff.isEmpty()) arguments.addAll(List.of("--loader", loaderDiff));
                if (!own) arguments.addAll(List.of("--names", names));
                var mapped = tools.run(own ? "GameDiff" : "VersionMap", arguments, "mapping-route");
                if (mapped.exit() != 0) throw new IOException(
                    "No verified mapping along the route " + String.join(" -> ", chain) + ". " + Run.tail(mapped.log())
                );
                if (!own && names.equals("yarn")) yarnRewrites(tools, chain, from, to, map);
            }
            // A yarn route map made before the game's rewrites were added to it gets them now (once: the key marks it).
            if (current && !own && !bridge && names.equals("yarn") && !Json.read(map).containsKey("rewrites")) yarnRewrites(tools, chain, from, to, map);
            build.report.stages.add(
                Json.map("name", "route", "waypoints", route.waypoints, "mapped", chain, "cached", current)
            );
        } else if (unobfuscated) {
            map = build.project.state().resolve("maps/identity-" + names + "-" + to + ".json");
            if (!Files.isRegularFile(map)) {
                Files.createDirectories(map.getParent());
                Files.writeString(
                    map,
                    "{\n  \"from\": \"" +
                    to +
                    "\", \"to\": \"" +
                    to +
                    "\", \"names\": \"" +
                    names +
                    "\",\n" +
                    "  \"classes\": [], \"classesRemoved\": [], \"methods\": [], \"methodsMoved\": [], \"signatures\": [], \"methodsRemoved\": [], \"fields\": [], \"fieldsRemoved\": []\n}\n"
                );
            }
        } else if (!Files.isRegularFile(map)) {
            var mapped = tools.run(
                "VersionMap",
                List.of(from, to, "--names", names, "--out", map.toString()),
                "mapping"
            );
            if (mapped.exit() != 0) throw new IOException(
                "No verified mapping for this jump. " + Run.tail(mapped.log())
            );
        }
        int meaningChanges = withoutMeaningChanges(map, build.harness.resolve("knowledge/meaning-changes.json"));
        if (meaningChanges > 0) build.report.stages.add(Json.map("name", "meaning-changes", "dropped", meaningChanges, "source", "knowledge/meaning-changes.json"));
        var mapInfo = Json.read(map);
        mapFile = map;
        String mapFrom = routed ? chain.get(0) : from, mapTo = routed ? chain.get(chain.size() - 1) : to;
        if (
            !unobfuscated &&
            (!mapFrom.equals(mapInfo.get("from")) ||
                !mapTo.equals(mapInfo.get("to")) ||
                !names.equals(mapInfo.get("names")))
        ) throw new IOException("Mapping identity does not match the requested source, target and namespace.");
        Map<String, Object> routeRecord = route == null ? null : route.record();
        if (routeRecord != null) routeRecord.put(
            "mapping",
            routed && bridge
                ? "yarn names of " + from + " joined to Mojang's (Fabric's yarn and intermediary with Mojang's mappings), then one map across " +
                String.join(" -> ", chain) +
                " from the game itself"
                : routed && own
                ? "one map joined across " +
                String.join(" -> ", chain) +
                " from the game itself: Mojang's jars and mappings linked release to release by their own bytecode" +
                (loaderDiff.isEmpty() ? "" : ", with " + loaderDiff + "'s own published classes")
                : routed
                ? "one map joined across " +
                String.join(" -> ", chain) +
                " by Fabric intermediary ids" +
                (chain.size() < route.versions.size()
                        ? "; updates past " +
                        chain.get(chain.size() - 1) +
                        " have no shared ids and are left to the compiler and rules"
                        : "")
                : "identity (no shared ids on this route)"
        );
        if (!options.has("fix")) {
            if (!options.values.containsKey("log")) return Json.map(
                "ok",
                true,
                "tier",
                "mapping",
                "map",
                map.toString(),
                "route",
                routeRecord,
                "gameVerified",
                false
            );
            var explain = tools.run(
                "VersionMap",
                List.of(
                    "--map",
                    map.toString(),
                    "--explain",
                    Path.of(options.get("log", "")).toAbsolutePath().toString()
                ),
                "port-diagnosis"
            );
            return Json.map(
                "ok",
                explain.exit() == 0,
                "tier",
                "diagnosis",
                "details",
                Run.tail(explain.log()),
                "gameVerified",
                false
            );
        }
        if (options.has("dry-run")) {
            if (!options.values.containsKey("log")) throw new IllegalArgumentException(
                "A dry-run needs --log <compiler log>; no sources were changed."
            );
            var preview = tools.run(
                "VersionMap",
                List.of(
                    "--map",
                    map.toString(),
                    "--fix",
                    Path.of(options.get("log", "")).toAbsolutePath().toString(),
                    "--repo",
                    build.project.root.toString(),
                    "--dry-run"
                ),
                "port-preview"
            );
            return Json.map(
                "ok",
                preview.exit() == 0,
                "tier",
                "preview",
                "details",
                Run.tail(preview.log()),
                "sourcesChanged",
                false,
                "gameVerified",
                false
            );
        }
        Path learned = options.values.containsKey("rules") ? Path.of(options.get("rules", "")).toAbsolutePath() : null;
        boolean corpusLoaded = learned != null;
        Target.requireWritable(target);
        Path transaction = build.project.state().resolve("ports/" + build.report.id);
        this.transaction = transaction;
        Map<String, Object> before = this.before;
        Set<Path> backupPaths = new TreeSet<>(sources(build.project));
        for (Target.Edit edit : target) {
            Path file = edit.file().toRealPath();
            if (!file.startsWith(build.project.root)) throw new IOException(
                "Target edit is outside the source project: " + file
            );
            backupPaths.add(file);
        }
        List<String> takenOver = new ArrayList<>();
        for (Path file : backupPaths) {
            Path relative = build.project.root.relativize(file), backup = transaction
                .resolve("before")
                .resolve(relative);
            // A build script already changed before the transaction (a dead decompiler plugin removed) is backed up as it was.
            Path original = pendingOriginals(build.project).resolve(relative.toString());
            Path source = Files.isRegularFile(original) ? original : file;
            if (source == original) takenOver.add(relative.toString().replace('\\', '/'));
            Files.createDirectories(backup.getParent());
            Files.copy(source, backup);
            before.put(relative.toString().replace('\\', '/'), Fingerprints.bytes(Files.readAllBytes(source)));
        }
        planned.addAll(takenOver);
        deletePending(build.project);
        // The restore plan is on disk before the first edit: an interrupted port still knows what it may have changed.
        for (Target.Edit edit : target) planned.add(
            build.project.root.relativize(edit.file().toRealPath()).toString().replace('\\', '/')
        );
        plannedRemovals = relative(build.project.root, excluded);
        saveInProgress();
        Path log;
        boolean compiled = false;
        int rounds = Integer.parseInt(options.get("rounds", "12"));
        List<String> removed = new ArrayList<>();
        if (rounds < 1 || rounds > 20) throw new IllegalArgumentException("--rounds must be 1–20");
        try {
            if (!target.isEmpty()) {
                Target.apply(target);
                build.refreshJdk();
            }
            for (Path file : excluded) {
                Path owned = file.toRealPath();
                if (!owned.startsWith(build.project.root)) throw new IOException(
                    "Excluded source is outside the source project: " + owned
                );
                removed.add(build.project.root.relativize(owned).toString().replace('\\', '/'));
                Files.delete(owned);
            }
            model = build.model(options.has("refresh"), false);
            if (!versions(model).equals(Set.of(to))) throw new IOException(
                "Evaluated build did not select " + to + ": " + versions(model)
            );
            verifyLoaderVersion(model, to);
            var preflight = tools.run(
                "VersionMap",
                List.of("--map", map.toString(), "--preflight", "--repo", build.project.root.toString()),
                "port-preflight"
            );
            if (preflight.exit() != 0) throw new IOException("Typed preflight stopped: " + Run.tail(preflight.log()));
            boolean explicitTask = options.values.containsKey("task");
            List<String> tasks = new ArrayList<>(
                explicitTask ? List.of(options.get("task", "")) : build.tasks(model, "build")
            );
            if (tasks.isEmpty()) throw new IOException(
                explicitTask
                    ? "No compile task selected."
                    : "No evaluated build task; pass --task only for a compile-only port check."
            );
            excludeOtherTargets(tasks, model);
            tasks.add(0, "-Dmmt.recompile=true");
            // Every compile task whose inputs built reports its errors, not only the first one to fail.
            tasks.add(0, "--continue");
            // Repair rounds only need the compiler; the full build (tests, jar) runs once when the source is clean.
            List<String> repairTasks = new ArrayList<>(explicitTask ? tasks : build.tasks(model, "classes"));
            if (repairTasks.isEmpty()) repairTasks = tasks;
            else excludeOtherTargets(repairTasks, model);
            repairTasks.removeIf(argument -> argument.equals("--continue") || argument.equals("-Dmmt.recompile=true"));
            repairTasks.add(0, "-Dmmt.recompile=true");
            repairTasks.add(0, "--continue");
            applySilentRules(from, to);
            renameClassesInStrings(map);
            var initial = build.gradle(tasks, explicitTask ? "port-initial-compile" : "port-initial-build");
            log = initial.log();
            compiled = initial.exit() == 0;
            // The errors the port started from (every one: port builds lift javac's 100-error cap), before any repair.
            int errorsBefore = compiled ? 0 : errors(log).size();
            Set<String> seen = new HashSet<>();
            int roundsTried = 0;
            for (int round = 0; !compiled && round < rounds; round++) {
                String state = Fingerprints.files(sources(build.project));
                if (!seen.add(state)) break;
                roundsTried = round + 1;
                applyRules(log);
                importMissingClasses(log);
                restoreAnnotationLibraries(log);
                // Rules are not learned from other people's mods unless asked: the map comes from the game itself.
                if (!corpusLoaded && options.has("learn-from-mods")) {
                    learned = Corpus.rules(build, model, tools, map, from, to, names);
                    corpusLoaded = true;
                }
                var args = new ArrayList<>(
                    List.of("--map", map.toString(), "--fix", log.toString(), "--repo", build.project.root.toString())
                );
                if (learned != null) args.addAll(List.of("--rules", learned.toString()));
                Path supers = supertypes(log);
                if (supers != null) args.addAll(List.of("--supers", supers.toString()));
                var fix = tools.run("VersionMap", args, "port-fix-" + (round + 1));
                flushImports();
                if (fix.exit() != 0) throw new IOException("Typed repair stopped: " + Run.tail(fix.log()));
                if (state.equals(Fingerprints.files(sources(build.project)))) break;
                var compile = build.gradle(repairTasks, (explicitTask ? "port-compile-" : "port-build-") + (round + 1));
                log = compile.log();
                compiled = compile.exit() == 0;
            }
            // The mod's mixins are applied by Mixin itself against the target game (no window, no world).
            // The hand audit still runs as a fast hint for the report, never as the verdict.
            Map<String, Object> mixins = compiled ? mixinCheck(model, options) : null;
            // What Mixin itself reports against the target game and can be repaired from its words alone; Mixin checks each repair.
            for (
                int round = 0;
                mixins != null && !Boolean.TRUE.equals(mixins.get("ok")) && round < 3 && repairMixins(mixins);
                round++
            ) {
                var compile = build.gradle(repairTasks, "port-mixin-build-" + (round + 1));
                log = compile.log();
                compiled = compile.exit() == 0;
                roundsTried++;
                mixins = compiled ? mixinCheck(model, options) : null;
            }
            var audit = compiled ? tools.audit(model) : null;
            boolean passed = compiled && mixins != null && Boolean.TRUE.equals(mixins.get("ok"));
            boolean built = !explicitTask && compiled;
            if (passed && (!explicitTask ? roundsTried > 0 : true)) {
                List<String> buildTasks = new ArrayList<>(build.tasks(model, "build"));
                if (!buildTasks.isEmpty()) {
                    excludeOtherTargets(buildTasks, model);
                    var finalBuild = build.gradle(buildTasks, "port-build");
                    built = finalBuild.exit() == 0;
                    if (!built) {
                        passed = false;
                        log = finalBuild.log();
                    }
                }
            }
            Map<String, Object> metadataCheck = passed ? verifyModMetadata(build.project.root, model, to) : null;
            if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) passed = false;
            boolean mixinsApplied =
                mixins != null && Boolean.TRUE.equals(mixins.get("ok")) && Boolean.TRUE.equals(mixins.get("checked"));
            Map<String, Object> result = Json.map(
                "ok",
                passed,
                "tier",
                tier(compiled, mixins, built),
                "compiled",
                compiled,
                "errorsBefore",
                errorsBefore,
                "built",
                built,
                "mixinTargetsVerified",
                mixins != null && Boolean.TRUE.equals(mixins.get("ok")),
                "mixinsAppliedByMixin",
                mixinsApplied,
                "mixinCheck",
                mixins,
                "modMetadata",
                metadataCheck,
                "gameVerified",
                false,
                "behaviorVerified",
                false,
                "log",
                log.toString(),
                "undo",
                transaction.toString(),
                "resolvedDependencies",
                applied,
                "excludedSources",
                removed,
                "selectionFile",
                selectionFile == null ? null : selectionFile.toString(),
                "details",
                passed
                    ? (built
                            ? "Built, and " + mixinSummary(mixins) + ". Gameplay and behavior remain unverified."
                            : "Compiled, and " +
                            mixinSummary(mixins) +
                            ". No standard build task was available; gameplay remains unverified.") +
                    uncheckedText(unchecked) +
                    preReleaseText(applied)
                    : compiled && mixins != null && !Boolean.TRUE.equals(mixins.get("ok"))
                        ? String.valueOf(mixins.get("details"))
                        : Run.tail(log)
            );
            if (!unchecked.isEmpty()) result.put("uncheckedDependencies", unchecked);
            // A port is done when the mod works in the real game: --check runs the mod's own game check on the result.
            boolean checkFailed = false;
            if (passed && options.values.containsKey("check")) {
                Map<String, Object> check = behaviourBefore != null ? behaviourCheck(to) : gameCheck(options.get("check", ""));
                boolean works = Boolean.TRUE.equals(check.get("ok"));
                checkFailed = !works;
                result.put("gameCheck", check);
                result.put("gameVerified", works);
                result.put("behaviorVerified", works);
                result.put("ok", works);
                if (works) result.put("tier", "behaviour");
                else result.put("reason", "The port built, but the game check failed; see gameCheck.");
                result.put(
                    "details",
                    works
                        ? "Built, " + mixinSummary(mixins) + ", and the game check passed: " + check.get("command")
                        : "Built and " + mixinSummary(mixins) + ", but the game check failed: " + check.get("tail")
                );
            }
            if (audit != null) result.put(
                "mixinHint",
                Json.map(
                    "source",
                    "mmt's own bytecode audit (a guess: it reads class files, Mixin does not run)",
                    "summary",
                    auditSummary(audit.log()),
                    "log",
                    audit.log().toString()
                )
            );
            List<Object> named = passed ? List.of() : namedErrors(log, routed ? mapInfo : null);
            if (routeRecord != null) result.put("route", hopRecords(routeRecord, routed ? mapInfo : null, named));
            if (!passed) {
                if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) {
                    result.put("stoppedSafely", true);
                    result.put("reason", metadataCheck.get("details"));
                    return result;
                }
                Path repair = transaction.resolve("repair-request.json");
                List<Map<String, Object>> guides = compiled
                    ? List.of()
                    : repairGuides(build.harness, from, to, logText(log));
                Json.save(
                    repair,
                    Json.map(
                        "root",
                        build.project.root.toString(),
                        "from",
                        from,
                        "to",
                        to,
                        "namespace",
                        names,
                        "map",
                        map.toString(),
                        "model",
                        build.project.state().resolve("model.json").toString(),
                        "log",
                        log.toString(),
                        "mixinCheck",
                        mixins,
                        "diagnostics",
                        compiled && mixins != null ? String.valueOf(mixins.get("details")) : Run.tail(log),
                        "guides",
                        guides,
                        "requirements",
                        List.of(
                            "Preserve existing behavior, including debug hooks and rendering.",
                            "Do not remove required injections or disable checks to make a build pass.",
                            "Rebuild and exercise relevant real game scenarios after a semantic repair."
                        )
                    )
                );
                result.put("repairRequest", repair.toString());
                if (!guides.isEmpty()) result.put(
                    "repairGuides",
                    guides.stream().map(guide -> guide.get("id") + ": " + guide.get("title")).toList()
                );
                List<Object> unresolved = compiled ? List.of() : named;
                result.put("unresolved", unresolved);
                result.put("stoppedSafely", true);
                StringBuilder byUpdate = new StringBuilder();
                if (route != null) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (Object item : unresolved) {
                        List<Object> changed = Json.array(Json.object(item).get("changedIn"));
                        String step = changed.isEmpty()
                            ? "not in the mapping history"
                            : Json.object(changed.get(0)).get("step") +
                            (String.valueOf(Json.object(changed.get(0)).get("match")).startsWith("name only")
                                    ? " (name only)"
                                    : "");
                        counts.merge(step, 1, Integer::sum);
                    }
                    counts.forEach((step, n) ->
                        byUpdate
                            .append(byUpdate.length() == 0 ? " By update: " : "; ")
                            .append(step)
                            .append(": ")
                            .append(n)
                    );
                    if (byUpdate.length() > 0) byUpdate.append('.');
                }
                result.put(
                    "reason",
                    failureReason(to, compiled, mixins, unresolved.size(), roundsTried, byUpdate.toString(), log)
                );
            }
            if (route != null) result.put("rulesApplied", rulesApplied);
            result.put("rulesByProvenance", provenance(rulesApplied.keySet()));
            return result;
        } catch (Exception failure) {
            throw new IOException(
                failure.getMessage() + "\nUndo this port with: port --undo \"" + transaction + "\"",
                failure
            );
        } finally {
            finishTransaction(build.project, transaction, before, removed);
        }
    }

    /**
     * What a port reached, never more: a source that did not compile is "compile-failed" (a NeoForge mod's failed
     * 1.21.4 builds were once reported as compile-and-mixin-applied), mixins Mixin did not apply are
     * "compiled-mixins-failed", and only a packaged build is "build-and-mixin-applied".
     */
    static String tier(boolean compiled, Map<String, Object> mixins, boolean built) {
        if (!compiled) return "compile-failed";
        if (mixins == null || !Boolean.TRUE.equals(mixins.get("ok"))) return "compiled-mixins-failed";
        return built ? "build-and-mixin-applied" : "compile-and-mixin-applied";
    }

    /** Report every target dependency as resolved, selected, or blocked without changing the project. */
    Map<String, Object> dependencies(Mmt.Options options) throws Exception {
        String to = options.get("to", "");
        if (to.isBlank() || !to.matches("[A-Za-z0-9._+\\-]+")) throw new IllegalArgumentException(
            "dependencies needs --to <target Minecraft version>"
        );
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        Target.Setup setup = versions(model).equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied, blocked = setup == null
            ? List.of()
            : setup.blockers;
        Set<String> used = new HashSet<>();
        for (var record : applied) used.add(String.valueOf(record.get("artifact")));
        List<Object> unused = new ArrayList<>();
        for (Dependencies.Selection selection : selections) {
            String[] source = Dependencies.coordinate(selection.source());
            if (!used.contains(source[0] + ":" + source[1])) unused.add(selection.source());
        }
        boolean ready = blocked.isEmpty();
        return Json.map(
            "to",
            to,
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "selections",
            selections.size(),
            "resolved",
            applied,
            "blocked",
            blocked,
            "unchecked",
            setup == null ? List.of() : setup.unchecked,
            "unusedSelections",
            unused,
            "ready",
            ready,
            "details",
            (ready
                    ? (setup != null && !setup.unchecked.isEmpty()
                            ? "Every checked target dependency resolves; port --fix can apply these changes."
                            : "Every target dependency resolves; port --fix can apply these changes.")
                    : blockerText(blocked)) +
            (setup == null ? "" : uncheckedText(setup.unchecked) + preReleaseText(applied))
        );
    }

    Path selectionFile(Mmt.Options options) throws IOException {
        if (options.values.containsKey("selection")) return Path.of(options.get("selection", "")).toAbsolutePath();
        Path file = build.project.root.resolve(".mmt/dependencies.json");
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Why a port stopped, by what actually failed: the mixin audit, a build that failed for a reason the
     * compiler did not report (shown from Gradle's own failure block), or compiler errors no rule matched.
     */
    static String failureReason(
        String to,
        boolean compiled,
        Map<String, Object> mixins,
        int unresolved,
        int rounds,
        String byUpdate,
        Path log
    ) throws IOException {
        if (compiled && mixins != null && !Boolean.TRUE.equals(mixins.get("ok"))) return (
            "The code compiled against " +
            to +
            ", but " +
            (Boolean.TRUE.equals(mixins.get("checked"))
                    ? "Mixin did not apply every mixin cleanly against the " +
                    to +
                    " game. Each failure is listed in details in Mixin's own words; fix the mixin, then rerun."
                    : "the mixins were not checked by Mixin, so the port cannot say they work: " + mixins.get("reason"))
        );
        if (compiled) return (
            "The code compiled against " +
            to +
            ", but the packaged build failed without a compiler error:\n" +
            Run.gradleFailure(log) +
            "\nLog: " +
            log
        );
        if (unresolved == 0) return (
            "The build failed without compiler errors, so this is not a source migration and no rule can fix it:\n" +
            Run.gradleFailure(log) +
            "\nLog: " +
            log
        );
        return (
            unresolved +
            " compiler error(s) remain after " +
            rounds +
            " repair round(s) and no typed rule matched." +
            byUpdate +
            " This needs a semantic migration; the port stopped instead of guessing. Resolve them in source, or add a verified recipe, then rerun."
        );
    }

    /** The real Mixin check for the ported tree; a check that cannot run is an unchecked result, never a pass. */
    Map<String, Object> mixinCheck(Map<String, Object> model, Mmt.Options options) {
        try {
            return new MixinCheck(build, model).run(options);
        } catch (Exception failure) {
            String reason = "The Mixin check could not run: " + failure.getMessage();
            return Json.map(
                "ok",
                false,
                "checked",
                false,
                "tier",
                "mixins-unchecked",
                "reason",
                reason,
                "details",
                reason,
                "gameVerified",
                false
            );
        }
    }

    static String mixinSummary(Map<String, Object> mixins) {
        if (mixins == null) return "mixins unchecked";
        if ("no-mixins".equals(mixins.get("tier"))) return "the mod declares no mixins";
        return "Mixin applied its mixins against the target game (" + mixins.get("details") + ")";
    }

    /** What the mixin audit actually looked at, from its summary line: never "checked" when nothing was. */
    static String auditSummary(Path log) throws IOException {
        String summary = "";
        if (log != null && Files.exists(log)) for (String line : logText(log).lines().toList()) if (
            line.startsWith("# ")
        ) summary = line.substring(2).strip();
        if (summary.isEmpty()) return "the mixin audit passed without a summary";
        if (summary.startsWith("0 mixins")) return "no mixins to audit";
        Matcher skipped = Pattern.compile("(\\d+) targets not in the jar").matcher(summary);
        return (
            "mixin targets checked (" +
            summary.replaceFirst(",?\\s*\\d+ targets not in the jar", "") +
            ")" +
            (skipped.find()
                    ? "; " +
                    skipped.group(1) +
                    " mixin target(s) are in no jar on the classpath and were not checked (see the mixin-audit log)"
                    : "")
        );
    }

    static String uncheckedText(List<Map<String, Object>> unchecked) {
        if (unchecked.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nNot checked for the target version (" + unchecked.size() + "):");
        for (var item : unchecked) text
            .append("\n  ")
            .append(item.get("artifact"))
            .append(" ")
            .append(item.get("version"))
            .append(": ")
            .append(item.get("reason"));
        return text.toString();
    }

    static String preReleaseText(List<Map<String, Object>> applied) {
        StringBuilder text = new StringBuilder();
        for (var item : applied) if (item.get("preRelease") != null) text
            .append("\nPre-release chosen: ")
            .append(item.get("preRelease"));
        for (var item : applied) if (item.get("warning") != null) text
            .append("\nWarning: ")
            .append(item.get("warning"));
        return text.toString();
    }

    static String blockerText(List<Map<String, Object>> blockers) {
        StringBuilder text = new StringBuilder(
            blockers.size() + " target dependencies need a verified selection before any edit:"
        );
        for (var blocker : blockers) text
            .append("\n  ")
            .append(blocker.get("artifact"))
            .append(" ")
            .append(blocker.get("version"))
            .append(": ")
            .append(blocker.get("reason"))
            .append("\n    add to .mmt/dependencies.json: ")
            .append(Json.write(blocker.get("suggestion")));
        return text.toString();
    }

    static List<String> relative(Path root, List<Path> files) {
        return files.stream().map(file -> root.relativize(file).toString().replace('\\', '/')).sorted().toList();
    }

    /** Distinct compiler errors left in a log, so a stopped port names exactly what a person must decide. */
    static List<Object> errors(Path log) throws IOException {
        List<Object> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher reported = ERROR.matcher(logText(log));
        while (reported.find()) {
            String key = reported.group(1) + ":" + reported.group(2) + ":" + reported.group(3);
            if (!seen.add(key)) continue;
            list.add(
                Json.map(
                    "file",
                    reported.group(1),
                    "line",
                    Integer.parseInt(reported.group(2)),
                    "message",
                    reported.group(3)
                )
            );
        }
        return list;
    }

    /**
     * A loader-derived game version is the one that matters. NeoForge resolves Minecraft from its
     * own artifact, so a changed minecraft_version alone can still compile against the old game.
     */
    static void verifyLoaderVersion(Map<String, Object> model, String to) throws IOException {
        boolean neoforge = false;
        String resolved = "";
        for (Object item : Json.array(model.get("projects"))) {
            for (Object d : Json.array(Json.object(item).get("dependencies"))) {
                var dep = Json.object(d);
                if (!"net.neoforged".equals(dep.get("group")) || !"neoforge".equals(dep.get("name"))) continue;
                neoforge = true;
                resolved = dep.get("version") == null ? "" : dep.get("version").toString();
            }
        }
        if (!neoforge) return;
        String prefix = Target.neoForgePrefix(to);
        if (!resolved.startsWith(prefix)) throw new IOException(
            "The evaluated NeoForge dependency is " +
            resolved +
            ", which does not target " +
            to +
            " (expected " +
            prefix +
            "x). Update the NeoForge version owner and rerun; a passing build would use the old game."
        );
    }

    /**
     * A repo can build several Minecraft variants from one source. Only the ported target is
     * verified here; nested builds for other versions would fail on the ported sources and are skipped.
     */
    /**
     * A passable build is not a loadable mod: NeoForge refuses a jar whose declared Minecraft
     * range still names the old game. Check the packaged metadata, not just the compiler.
     */
    static Map<String, Object> verifyModMetadata(Path projectDir, Map<String, Object> model, String to)
        throws IOException {
        boolean neoforge = false, fabric = false;
        for (Object item : Json.array(model.get("projects"))) {
            Set<String> loaders = Support.loaders(Json.object(item));
            if (loaders.contains("neoforge")) neoforge = true;
            if (loaders.contains("fabric")) fabric = true;
        }
        if (!neoforge && fabric) return verifyFabricMetadata(projectDir, to);
        if (!neoforge) return null;
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map(
            "ok",
            true,
            "checked",
            false,
            "details",
            "No packaged jar to inspect."
        );
        Pattern block = Pattern.compile(
            "(?s)\\[\\[dependencies\\.([^\\]]+)\\]\\]([^\\[]*?modId\\s*=\\s*\"minecraft\"[^\\[]*?versionRange\\s*=\\s*\")([^\"]+)\""
        );
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
                        jars.add(jar.getFileName().toString());
                        ranges.add(range);
                        found = true;
                        // Read as FML reads it: a Maven version range, not text containing the version.
                        MavenRange.Answer answer = MavenRange.contains(range, to, classpath(model));
                        decidedBy = answer.decidedBy();
                        if (answer.error() != null) return Json.map(
                            "ok",
                            false,
                            "checked",
                            true,
                            "jar",
                            jar.toString(),
                            "declared",
                            range,
                            "decidedBy",
                            decidedBy,
                            "details",
                            "The packaged mod's Minecraft versionRange \"" +
                            range +
                            "\" is not a valid Maven range (" +
                            answer.error() +
                            "); NeoForge refuses the mod. Fix the range and rerun."
                        );
                        if (!answer.contains()) return Json.map(
                            "ok",
                            false,
                            "checked",
                            true,
                            "jar",
                            jar.toString(),
                            "declared",
                            range,
                            "decidedBy",
                            decidedBy,
                            "details",
                            "The packaged mod declares Minecraft " +
                            range +
                            " but was ported to " +
                            to +
                            "; NeoForge refuses it at runtime and the client would list 0 mods. Update the property behind the metadata range and rerun."
                        );
                    }
                    if (found) break;
                }
                if (found) break;
            }
        }
        return Json.map(
            "ok",
            true,
            "checked",
            !ranges.isEmpty(),
            "jars",
            jars,
            "ranges",
            ranges,
            "decidedBy",
            decidedBy,
            "details",
            ranges.isEmpty()
                ? "No Minecraft dependency range in the packaged metadata."
                : "Packaged metadata accepts " + to + " (range read by " + decidedBy + ")."
        );
    }

    /** Every jar on the evaluated compile classpaths. */
    static List<Path> classpath(Map<String, Object> model) {
        Set<Path> jars = new LinkedHashSet<>();
        for (Object item : Json.array(model.get("projects"))) if (
            Json.object(item).get("compile") instanceof List<?> compiles
        ) for (Object c : compiles) for (String path : ProjectModel.strings(Json.object(c).get("classpath"))) if (
            path.endsWith(".jar")
        ) jars.add(Path.of(path));
        return new ArrayList<>(jars);
    }

    /**
     * Fabric refuses a jar whose packaged fabric.mod.json does not accept the running game, even when
     * the source manifest used a placeholder the build expanded. Read the freshest jar, not the source.
     */
    static Map<String, Object> verifyFabricMetadata(Path projectDir, String to) throws IOException {
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map(
            "ok",
            true,
            "checked",
            false,
            "details",
            "No packaged jar to inspect."
        );
        List<Path> candidates;
        try (var stream = Files.list(libs)) {
            candidates = new ArrayList<>(
                stream
                    .filter(
                        p ->
                            p.toString().endsWith(".jar") &&
                            !p.getFileName().toString().matches(".*-(sources|api|javadoc|dev)\\.jar$")
                    )
                    .toList()
            );
        }
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path jar : candidates) {
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                var entry = zip.getEntry("fabric.mod.json");
                if (entry == null) continue;
                Map<String, Object> metadata;
                try {
                    metadata = Json.object(
                        Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    );
                } catch (IllegalArgumentException invalid) {
                    return Json.map(
                        "ok",
                        false,
                        "checked",
                        true,
                        "jar",
                        jar.toString(),
                        "details",
                        "The packaged fabric.mod.json is not valid JSON: " + invalid.getMessage()
                    );
                }
                Object depends = metadata.get("depends") instanceof Map<?, ?> map ? map.get("minecraft") : null;
                if (depends == null) return Json.map(
                    "ok",
                    true,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "details",
                    "The packaged mod declares no Minecraft dependency."
                );
                List<String> ranges = depends instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(depends));
                // A list is "any of": Fabric loads the mod when one entry accepts the game.
                boolean accepted = ranges.stream().anyMatch(range -> Versions.matches(range, to));
                if (!accepted) return Json.map(
                    "ok",
                    false,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "declared",
                    ranges,
                    "details",
                    "The packaged mod declares Minecraft " +
                    String.join(" or ", ranges) +
                    " but was ported to " +
                    to +
                    "; Fabric refuses to load it. Update the property or manifest behind that range and rerun."
                );
                return Json.map(
                    "ok",
                    true,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "declared",
                    ranges,
                    "details",
                    "Packaged metadata accepts " + to + "."
                );
            }
        }
        return Json.map("ok", true, "checked", false, "details", "No packaged fabric.mod.json to inspect.");
    }

    private static void excludeOtherTargets(List<String> tasks, Map<String, Object> model) {
        for (Object item : Json.array(model.get("projects"))) {
            Object composites = Json.object(item).get("composites");
            if (!(composites instanceof List<?> list)) continue;
            for (Object composite : list) {
                tasks.add("-x");
                tasks.add(composite.toString());
            }
        }
    }

    static Set<String> versions(Map<String, Object> model) {
        Set<String> actual = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            for (String key : List.of(
                "minecraft_version",
                "minecraftVersion",
                "exactMinecraftVersion",
                "mc_version"
            )) if (pins.containsKey(key)) actual.add(pins.get(key).toString());
            for (Object d : Json.array(Json.object(p).get("dependencies"))) {
                var dep = Json.object(d);
                if (
                    "com.mojang".equals(dep.get("group")) &&
                    "minecraft".equals(dep.get("name")) &&
                    dep.get("version") != null
                ) actual.add(dep.get("version").toString());
            }
        }
        return actual;
    }

    String inferNames() {
        for (Object p : Json.array(model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            if (pins.containsKey("yarn_mappings") || pins.containsKey("yarn_version")) return "yarn";
        }
        return "mojmap";
    }

    /**
     * The mod's own game check, run on the ported project: a command (a mmt eval/ui-check script, or a test program)
     * started in the project with MMT_PORTED_ROOT set to it. Its exit code is the verdict.
     */
    /** The fingerprint taken before the port (--check fingerprint), or null. */
    Path behaviourBefore;

    /** The mod's behaviour fingerprint on one game version, in its own development client (Fingerprint.record). */
    Path behaviour(String minecraft, String name) throws Exception {
        Path out = build.report.directory.resolve(name + ".json");
        Set<String> namespaces = Fingerprint.modIds(build.project.root);
        Map<String, Object> args = Json.map("ticks", 100, "every", 20, "seed", Fingerprint.SEED, "namespaces", new ArrayList<>(namespaces));
        Fingerprint.record(build.harness, build.project.root, "", minecraft, namespaces, args, build.report, out, name);
        return out;
    }

    /**
     * The port's behaviour check: the fingerprint after the port against the one before, across the two versions only
     * what the mod owns is compared, after the game's own renames (knowledge/fingerprint-normalise.json).
     */
    Map<String, Object> behaviourCheck(String to) throws Exception {
        long start = System.nanoTime();
        Path after = behaviour(to, "fingerprint-after");
        Map<String, Object> a = Json.read(behaviourBefore), b = Json.read(after);
        Set<String> namespaces = new TreeSet<>();
        for (Object ns : Json.array(Json.object(a.get("target")).get("namespaces"))) namespaces.add(ns.toString());
        FingerprintDiff.Normaliser normaliser = Json.text(Json.object(a.get("target")), "minecraft", "").equals(to)
            ? new FingerprintDiff.Normaliser("exact", Set.of())
            : FingerprintDiff.Normaliser.across(Json.read(build.harness.resolve("knowledge/fingerprint-normalise.json")), namespaces, Json.object(a.get("target")).get("minecraft") + " -> " + to);
        Map<String, Object> diff = FingerprintDiff.compare(a, b, normaliser);
        boolean same = Boolean.TRUE.equals(diff.get("same")) && ((Number) diff.get("compared")).intValue() > 0;
        return Json.map("ok", same, "command", "fingerprint " + behaviourBefore + " -> " + after, "before", behaviourBefore.toString(), "after", after.toString(),
            "seconds", (System.nanoTime() - start) / 1e9, "diff", diff, "tail", FingerprintDiff.text(diff));
    }

    Map<String, Object> gameCheck(String command) throws Exception {
        long start = System.nanoTime();
        List<String> shell = Runtimes.WINDOWS ? List.of("cmd.exe", "/d", "/s", "/c", command) : List.of("sh", "-c", command);
        Run.Result run = Run.command(
            shell,
            build.project.root,
            Map.of("MMT_PORTED_ROOT", build.project.root.toString(), "MODACCEL_PORTED_ROOT", build.project.root.toString()),
            build.report.log("port-game-check"),
            3600
        );
        build.report.stage("port-game-check", run, Map.of());
        return Json.map(
            "ok",
            run.exit() == 0,
            "command",
            command,
            "exit",
            run.exit(),
            "seconds",
            (System.nanoTime() - start) / 1e9,
            "log",
            run.log().toString(),
            "tail",
            Run.tail(run.log())
        );
    }

    /** How many of the rules a port applied came from each source (knowledge/fixes.json "provenance"). */
    /** Whether port --learn-from-mods was given: only then do rules mined from other people's mods run. */
    boolean learnFromMods;

    /**
     * The rule book's own rules always run; the older rules mined from third-party mods (provenance third-party-mods)
     * only with --learn-from-mods. Rules are never taken from other people's mods by default.
     */
    static boolean ownRule(Map<String, Object> rule, boolean learnFromMods) {
        return learnFromMods || !"third-party-mods".equals(Json.text(rule, "provenance", ""));
    }

    Map<String, Object> provenance(Set<String> ids) throws IOException {
        Map<String, Object> counts = new TreeMap<>();
        if (ids.isEmpty()) return counts;
        for (Object item : Json.array(Json.read(build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (!ids.contains(Json.text(rule, "id", ""))) continue;
            counts.merge(Json.text(rule, "provenance", "unrecorded"), 1, (a, b) -> (Integer) a + (Integer) b);
        }
        return counts;
    }

    /**
     * A rule with "loader" runs only on a project built for that loader: NeoForge's GuiLayer replaces 1.21.6's removed
     * LayeredDraw.Layer, while a Fabric mod needs Fabric's HUD API for the same error.
     */
    boolean loaderFits(Map<String, Object> rule) {
        Set<String> loaders = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) loaders.addAll(Support.loaders(Json.object(p)));
        return loaderFits(rule, loaders);
    }

    static final Pattern GAME_CLASS = Pattern.compile(
        "\\b(?:net\\.minecraft|com\\.mojang|net\\.neoforged|net\\.fabricmc)(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*"
    );

    /**
     * Whether every game class a rule writes (its ensureImports, and a qualified name in its replacement) is in the target
     * game. A rule from an earlier update can write a class a later one removed again (1.21.2's CoreShaders, gone in
     * 1.21.5): on a longer jump it would only trade one error for another, so it is skipped and the error stays named
     * by the later update. True when the target's classpath cannot be read.
     */
    boolean targetHas(Map<String, Object> rule) throws IOException {
        var apply = Json.object(rule.get("apply"));
        Set<String> written = new TreeSet<>(ProjectModel.strings(apply.get("ensureImports")));
        Matcher named = GAME_CLASS.matcher(Json.text(apply, "replace", ""));
        while (named.find()) written.add(named.group());
        written.removeIf(name -> !GAME_CLASS.matcher(name).matches());
        if (written.isEmpty()) return true;
        if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
        if (supertypeIndex.bySimple.getOrDefault("Minecraft", List.of()).isEmpty()) return true;
        for (String name : written) if (supertypeIndex.qualified(name) == null && !mapRenames(name)) return false;
        return true;
    }

    Set<String> mapClassesFrom;

    /** Whether the route map renames this class (dotted, nested with '.'): the typed fixer carries it to the target's name. */
    boolean mapRenames(String name) throws IOException {
        if (mapClassesFrom == null) {
            mapClassesFrom = new HashSet<>();
            if (mapFile != null && Files.isRegularFile(mapFile)) for (Object o : Json.array(Json.read(mapFile).get("classes"))) {
                mapClassesFrom.add(Json.text(Json.object(o), "from", "").replace('$', '.'));
            }
        }
        return mapClassesFrom.contains(name);
    }

    static boolean loaderFits(Map<String, Object> rule, Set<String> loaders) {
        String loader = Json.text(rule, "loader", "");
        return loader.isEmpty() || loaders.contains(loader);
    }

    /**
     * A mapping change can delete one target of a multi-target mixin. The annotation processor
     * names it and reports an empty match, so remove only that dead name and keep the rest.
     */
    void removeDeadMixinTargets(Path log) throws Exception {
        String errors = logText(log);
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + FILE + ":(\\d+): error: Multi-target reference conflict for @\\w+ target (\\w+): \\[\\]"
        ).matcher(errors);
        Set<String> repaired = new HashSet<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            file = file.normalize();
            if (!file.startsWith(build.project.root) || !Files.isRegularFile(file)) continue;
            String target = reported.group(3);
            if (!repaired.add(file + ":" + target)) continue;
            String before = Files.readString(file), after = removeDeadTarget(before, target);
            Files.writeString(file, after);
            Path lessons = build.project.state().resolve("lessons.jsonl");
            Files.writeString(
                lessons,
                Json.write(
                    Json.map(
                        "rule",
                        "mixin-removed-target",
                        "target",
                        target,
                        "file",
                        build.project.root.relativize(file).toString().replace('\\', '/'),
                        "to",
                        targetVersion
                    )
                ) +
                "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            build.report.stages.add(
                Json.map(
                    "name",
                    "mixin-target-removed",
                    "target",
                    target,
                    "file",
                    file.toString(),
                    "evidence",
                    "mixin annotation processor reported no target for this mapping"
                )
            );
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
            String replacement =
                annotation.group().substring(0, base + methods.start(1) - annotation.start()) +
                String.join(", ", names) +
                annotation.group().substring(base + methods.end(1) - annotation.start());
            return text.substring(0, annotation.start()) + replacement + text.substring(annotation.end());
        }
        throw new IOException(
            "The annotation processor reported a dead target " +
            target +
            ", but no single-line multi-target mixin declaration contains it; repair it by hand"
        );
    }

    /** The selected project's own Java sources: a repo can hold other versions' copies (a Forge 1.20.1 sibling, generated Stonecutter sources) that this port must not touch. */
    List<Path> ownedJava() throws IOException {
        Set<Path> owned = new HashSet<>();
        for (Object item : Json.array(model.get("projects"))) for (Object set : Json.array(
            Json.object(item).get("sourceSets")
        )) {
            for (String dir : ProjectModel.strings(Json.object(set).get("java"))) owned.add(
                Path.of(dir).toAbsolutePath().normalize()
            );
        }
        return sources(build.project)
            .stream()
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> owned.isEmpty() || owned.stream().anyMatch(p.toAbsolutePath().normalize()::startsWith))
            .toList();
    }

    /**
     * Rules for changes the compiler never reports (a rule with "silent" saying what goes wrong instead): they run once,
     * before the first build, over every owned source, when the port crosses their update. In 1.21.6 a GUI colour
     * without an alpha byte still compiles and draws nothing.
     */
    void applySilentRules(String from, String to) throws Exception {
        List<Path> all = ownedJava();
        for (Object item : Json.array(Json.read(build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (!rule.containsKey("silent") || !loaderFits(rule) || !ownRule(rule, learnFromMods)) continue;
            List<String> covered = route == null
                ? List.of()
                : route.stepsCovered(Json.text(rule, "jump", ""), namespace);
            if (route != null ? covered.isEmpty() : !crosses(Json.text(rule, "jump", ""), from, to)) continue;
            if (!targetHas(rule)) continue;
            for (Path file : all) {
                String original = Files.readString(file), text = rewrite(rule, original);
                if (!text.equals(original)) {
                    Files.writeString(file, text);
                    rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
            }
        }
    }

    /**
     * A moved or renamed game class written inside a string (a mixin target "of(Lnet/minecraft/client/item/TooltipData;)..."
     * or @Mixin(targets = "net.minecraft...")) gets its full new name from the port's class map, before anything renames
     * it by its simple name alone and leaves the old package behind.
     */
    void renameClassesInStrings(Path map) throws Exception {
        if (!Files.isRegularFile(map)) return;
        Object listed = Json.read(map).get("classes");
        if (!(listed instanceof List<?> list) || list.isEmpty()) return;
        Map<String, String> classes = new HashMap<>();
        for (Object item : list) {
            var entry = Json.object(item);
            classes.put(Json.text(entry, "from", ""), Json.text(entry, "to", ""));
        }
        for (Path file : ownedJava()) {
            String original = Files.readString(file), text = classNamesInStrings(original, classes);
            if (!text.equals(original)) {
                Files.writeString(file, text);
                rulesApplied.putIfAbsent("class-names-in-strings", List.of());
            }
        }
    }

    static String classNamesInStrings(String text, Map<String, String> classes) {
        Matcher literal = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"").matcher(text);
        StringBuilder out = new StringBuilder();
        while (literal.find()) {
            // A name starts the string, follows punctuation, or follows a descriptor's L ("(Lnet/minecraft/...;").
            Matcher name = Pattern.compile("(?:(?<=[^\\w$]L)|(?<![\\w$./]))net([/.])minecraft\\1[\\w/.$]*\\w").matcher(
                literal.group()
            );
            StringBuilder renamed = new StringBuilder();
            while (name.find()) {
                String separator = name.group(1), dotted = name.group().replace('/', '.'), to = classes.get(dotted);
                // A dotted name may run on into a member ("net.minecraft.Foo.bar"): try its longest known class prefix.
                String rest = "";
                while (to == null && dotted.contains(".")) {
                    rest = dotted.substring(dotted.lastIndexOf('.')) + rest;
                    dotted = dotted.substring(0, dotted.lastIndexOf('.'));
                    to = classes.get(dotted);
                }
                name.appendReplacement(
                    renamed,
                    Matcher.quoteReplacement(
                        to == null
                            ? name.group()
                            : (separator.equals("/") ? to.replace('.', '/') : to) +
                            (separator.equals("/") ? rest.replace('.', '/') : rest)
                    )
                );
            }
            name.appendTail(renamed);
            literal.appendReplacement(out, Matcher.quoteReplacement(renamed.toString()));
        }
        literal.appendTail(out);
        return out.toString();
    }

    /**
     * Repairs that Mixin's own failure messages fully determine, in the mixin's source file:
     * a target method pinned by a descriptor that no longer exists is named by its name alone, and a handler whose
     * descriptor Mixin rejects ("Expected (A;B;C) but found (A;C)") gets the parameters it now needs, keeping its own.
     * True when a file changed; the port then compiles again and Mixin checks the result.
     */
    boolean repairMixins(Map<String, Object> mixins) throws IOException {
        boolean changed = false;
        Object failures = mixins.get("failures");
        if (!(failures instanceof List<?> list)) return false;
        List<Object> repaired = new ArrayList<>();
        for (Object item : list) {
            var failure = Json.object(item);
            Path file = build.project.root.resolve(Json.text(failure, "file", "")).normalize();
            // A mixin in another project of the build (an Architectury :common) is found by its class name.
            if (Json.text(failure, "file", "").isEmpty() && !Json.text(failure, "mixin", "").isEmpty()) {
                String tail = Json.text(failure, "mixin", "").replace('.', '/') + ".java";
                for (Path source : sources(build.project)) if (source.toString().replace('\\', '/').endsWith("/" + tail)) {
                    file = source;
                    failure = new LinkedHashMap<>(failure);
                    failure.put("file", build.project.root.relativize(source).toString());
                    break;
                }
            }
            if (
                Json.text(failure, "file", "").isEmpty() ||
                !file.startsWith(build.project.root) ||
                !Files.isRegularFile(file)
            ) continue;
            String original = Files.readString(file), text = repairMixinSource(
                original,
                Json.text(failure, "message", "")
            );
            if (text.equals(original)) text = renameMixinTarget(original, Json.text(failure, "message", ""));
            if (!text.equals(original)) {
                Files.writeString(file, text);
                changed = true;
                repaired.add(Json.map("mixin", failure.get("mixin"), "file", failure.get("file")));
            }
        }
        if (changed) build.report.stages.add(Json.map("name", "mixin-repair", "repaired", repaired));
        return changed;
    }

    /** A mixin target the game renamed, renamed in the mixin's method strings, from Mixin's words and the route map. */
    String renameMixinTarget(String text, String message) throws IOException {
        Matcher missing = Pattern.compile("could not find any targets matching '(\\w+)(?:\\([^']*)?' in ([\\w/$]+)").matcher(message);
        if (!missing.find()) return text;
        String to;
        try {
            to = mixinTargetRename(missing.group(2), missing.group(1));
        } catch (Exception unread) {
            return text;
        }
        if (to == null) return text;
        rulesApplied.putIfAbsent("mixin-target-rename", List.of());
        return text.replaceAll("\"" + Pattern.quote(missing.group(1)) + "(?:\\([^\"]*\\)[^\"]*)?\"", Matcher.quoteReplacement("\"" + to + "\""));
    }

    static String repairMixinSource(String text, String message) {
        Matcher missing = Pattern.compile("could not find any targets matching '(\\w+)(\\([^']*)'").matcher(message);
        if (missing.find()) {
            String pinned = "\"" + missing.group(1) + missing.group(2) + "\"";
            if (text.contains(pinned)) return text.replace(pinned, "\"" + missing.group(1) + "\"");
        }
        Matcher descriptor = Pattern.compile(
            "->@\\w+::(\\w+)\\([^)]*\\)[^!]*! Expected \\(([^)]*)\\)\\S* but found \\(([^)]*)\\)"
        ).matcher(message);
        if (!descriptor.find()) return text;
        List<String> expected = descriptorTypes(descriptor.group(2)), found = descriptorTypes(descriptor.group(3));
        Matcher handler = Pattern.compile("\\b" + Pattern.quote(descriptor.group(1)) + "\\s*\\(").matcher(text);
        while (handler.find()) {
            List<int[]> params = arguments(text, handler.end() - 1);
            int close = params.isEmpty() ? text.indexOf(')', handler.end()) : params.get(params.size() - 1)[1];
            if (
                close < 0 ||
                !text.substring(close + 1).matches("(?s)\\s*(?:throws[^{]*)?\\{.*") ||
                params.size() != found.size()
            ) continue;
            List<String> written = new ArrayList<>();
            Set<String> imports = new LinkedHashSet<>();
            int next = 0;
            for (String type : expected) {
                if (next < found.size() && found.get(next).equals(type)) {
                    written.add(text.substring(params.get(next)[0], params.get(next)[1]).strip());
                    next++;
                    continue;
                }
                String java = javaType(type, imports);
                if (java == null) return text;
                written.add(
                    java +
                    " mmt$" +
                    Character.toLowerCase(simpleName(java).charAt(0)) +
                    simpleName(java).substring(1).replaceAll("\\W", "") +
                    written.size()
                );
            }
            if (next != found.size()) return text;
            String out = text.substring(0, handler.end()) + String.join(", ", written) + text.substring(close);
            for (String name : imports) out = addImport(out, name);
            return out;
        }
        return text;
    }

    /** A method descriptor's parameter list, one type descriptor each. */
    static List<String> descriptorTypes(String parameters) {
        List<String> types = new ArrayList<>();
        Matcher type = Pattern.compile("\\[*(?:L[^;]+;|[ZBCSIJFD])").matcher(parameters);
        while (type.find()) types.add(type.group());
        return types;
    }

    /** The Java source type for a descriptor (Item$TooltipContext as Item.TooltipContext), adding the import it needs; null for none. */
    static String javaType(String descriptor, Set<String> imports) {
        int dims = 0;
        while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
        String base = descriptor.substring(dims), java =
            switch (base) {
                case "Z" -> "boolean";
                case "B" -> "byte";
                case "C" -> "char";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                case "F" -> "float";
                case "D" -> "double";
                default -> null;
            };
        if (java == null) {
            if (!base.startsWith("L") || !base.endsWith(";")) return null;
            String binary = base.substring(1, base.length() - 1).replace('/', '.'), outer = binary.contains("$")
                ? binary.substring(0, binary.indexOf('$'))
                : binary;
            if (!outer.startsWith("java.lang.") || outer.substring(10).contains(".")) imports.add(outer);
            java = binary.substring(outer.lastIndexOf('.') + 1).replace('$', '.');
        }
        return java + "[]".repeat(dims);
    }

    /** True when a port from -> to crosses the release a jump ("A -> B" changed in B, "X+" in X) names, either way. */
    static boolean crosses(String jump, String from, String to) {
        Matcher release = Pattern.compile("(?:->\\s*)?(\\d+(?:\\.\\d+)+)(\\+)?(?!.*->)").matcher(jump);
        if (!release.find()) return false;
        String changed = release.group(1);
        return Versions.compare(from, changed) < 0
            ? Versions.compare(to, changed) >= 0
            : Versions.compare(to, changed) < 0;
    }

    /**
     * A game member the mod reached through its access transformer that the target game moved, renamed or re-typed is
     * private again: the old entry names a member that is gone. javac names the member and the class it lives in now
     * ("X(args) has private access in Y", "X is not public in Y"), so the entry is written for the member as the target
     * game has it, its descriptor read from the target's own class files. NeoForge mods only (accesstransformer.cfg);
     * an entry already there is not written twice. Returns the lines added.
     */
    List<String> repairAccess(Path log) throws Exception {
        Set<String> loaders = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) loaders.addAll(Support.loaders(Json.object(p)));
        if (!loaders.contains("neoforge")) return List.of();
        String errors = logText(log);
        Matcher denied = Pattern.compile(
            "(?m)^\\s*" +
            FILE +
            ":[0-9]+: error: (\\w+)(?:\\(([^)]*)\\))? (?:has private access in|is not public in) ([\\w.$]+)"
        ).matcher(errors);
        Map<String, String[]> wanted = new LinkedHashMap<>();
        while (denied.find()) wanted.putIfAbsent(
            denied.group(4) + "." + denied.group(2) + "(" + denied.group(3) + ")",
            new String[] { denied.group(2), denied.group(3), denied.group(4) }
        );
        // An overloaded call names the private candidate in its list: "(m(args) has private access in Y)".
        Matcher candidate = Pattern.compile("\\((\\w+)\\(([^)]*)\\) has private access in ([\\w.$]+)\\)").matcher(errors);
        while (candidate.find()) wanted.putIfAbsent(
            candidate.group(3) + "." + candidate.group(1) + "(" + candidate.group(2) + ")",
            new String[] { candidate.group(1), candidate.group(2), candidate.group(3) }
        );
        if (wanted.isEmpty()) return List.of();
        Path at = accessTransformer();
        if (at == null || !Files.isRegularFile(at)) return List.of();
        Explain.Index index = new Explain.Index(classpath(model));
        String text = Files.readString(at, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String[] member : wanted.values()) {
            String owner = null;
            for (String found : index.resolve(member[2], List.of())) if (
                found.startsWith("net/minecraft/") || found.startsWith("com/mojang/")
            ) {
                owner = found;
                break;
            }
            if (owner == null) continue;
            Explain.ClassInfo info = index.info(owner);
            if (info == null) continue;
            String entry = null;
            for (Explain.Member m : info.members()) {
                if (!m.name().equals(member[0]) || m.method() != (member[1] != null)) continue;
                if (m.method() && !parameters(m.descriptor()).equals(member[1].replace(" ", ""))) continue;
                entry = "public " + owner.replace('/', '.') + " " + m.name() + (m.method() ? m.descriptor() : "");
                break;
            }
            if (entry == null || text.contains(entry) || lines.contains(entry)) continue;
            lines.add(entry);
        }
        if (lines.isEmpty()) return lines;
        track(at);
        String added =
            (text.endsWith("\n") ? "" : "\n") +
            "\n# Written by mmt port: members the target game made private again, as it has them now\n" +
            String.join("\n", lines) +
            "\n";
        Files.writeString(at, text + added, StandardCharsets.UTF_8);
        rulesApplied.putIfAbsent("access-transformer-repair", List.of());
        return lines;
    }

    /** A descriptor's parameter types written the way javac's messages write them: (ILjava/lang/String;)V -> int,String. */
    static String parameters(String descriptor) {
        List<String> types = new ArrayList<>();
        int[] at = { 1 };
        while (descriptor.charAt(at[0]) != ')') types.add(Explain.Index.type(descriptor, at));
        return String.join(",", types);
    }

    /** The NeoForge access transformer the build applies: NeoGradle's accessTransformers file, or META-INF/accesstransformer.cfg. */
    Path accessTransformer() throws IOException {
        for (String script : List.of("build.gradle", "build.gradle.kts")) {
            Path file = build.project.root.resolve(script);
            if (!Files.isRegularFile(file)) continue;
            Matcher configured = Pattern.compile(
                "accessTransformers?[^\\n]*?file\\s*\\(?\\s*(?:rootProject\\.|project\\.)?file\\(\\s*['\"]([^'\"]+)['\"]"
            ).matcher(Files.readString(file));
            if (configured.find()) return build.project.root.resolve(configured.group(1)).normalize();
        }
        Path fallback = build.project.root.resolve("src/main/resources/META-INF/accesstransformer.cfg");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    /**
     * Annotation-only libraries an older game or loader brought along and a newer one does not (Trinkets 1.20.1 -> 1.21.1
     * lost javax.annotation): only nullness annotations, never read at run time, so compileOnly is safe.
     */
    static final Map<String, String> ANNOTATION_LIBRARIES = Map.of(
        "javax.annotation",
        "com.google.code.findbugs:jsr305:3.0.2",
        "org.jetbrains.annotations",
        "org.jetbrains:annotations:26.0.2",
        "org.jspecify.annotations",
        "org.jspecify:jspecify:1.0.0"
    );

    /** Adds the compileOnly annotation libraries the log says are missing to the selected project's build script. */
    void restoreAnnotationLibraries(Path log) throws Exception {
        String errors = logText(log);
        List<String> wanted = new ArrayList<>();
        for (var library : ANNOTATION_LIBRARIES.entrySet()) if (
            errors.contains("package " + library.getKey() + " does not exist")
        ) wanted.add(library.getValue());
        if (wanted.isEmpty()) return;
        for (Object p : Json.array(model.get("projects"))) {
            Object script = Json.object(p).get("buildFile");
            if (script == null || !Files.isRegularFile(Path.of(script.toString()))) continue;
            Path file = Path.of(script.toString());
            String text = Files.readString(file), after = withCompileOnly(text, wanted, file.toString().endsWith(".kts"));
            if (after.equals(text)) continue;
            track(file);
            Files.writeString(file, after);
            rulesApplied.putIfAbsent("annotation-library-compile-only", List.of());
        }
    }

    /** A build script with each coordinate added as compileOnly to its top-level dependencies block (not twice). */
    static String withCompileOnly(String text, List<String> coordinates, boolean kotlin) {
        Matcher block = Pattern.compile("(?m)^dependencies\\s*\\{[ \\t]*\\r?\\n").matcher(text);
        if (!block.find()) return text;
        StringBuilder lines = new StringBuilder();
        for (String coordinate : coordinates) {
            String name = coordinate.substring(0, coordinate.lastIndexOf(':'));
            if (text.contains(name)) continue;
            lines.append(kotlin ? "    compileOnly(\"" + coordinate + "\")\n" : "    compileOnly \"" + coordinate + "\"\n");
        }
        if (lines.length() == 0) return text;
        return text.substring(0, block.end()) + "    // mmt port: annotation-only library the target no longer brings along\n" + lines + text.substring(block.end());
    }

    /**
     * A mixin that extends a game class must declare a constructor, and Mixin never runs it (the mixin's own constructors
     * are dropped when it is applied). When the game changes that class's constructors (1.21.2: Level, MinecartChest), the
     * super call stops compiling for no reason the game cares about: its arguments become typed placeholders (null for an
     * object, zero or false otherwise) for a constructor the target class has, the one with fewest parameters. Only in
     * files annotated @Mixin, only on a super(...) call javac reported. Returns the calls changed.
     */
    int mixinConstructors(Path log) throws Exception {
        Matcher reported = Pattern.compile(
            "(?m)^\\s*" + FILE + ":(\\d+): error: constructor (\\w+) in class ([\\w.$]+)(?:<[^>]*>)? cannot be applied to given types"
        ).matcher(logText(log));
        Map<Path, List<String[]>> sites = new LinkedHashMap<>();
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            if (Files.isRegularFile(file)) sites.computeIfAbsent(file.normalize(), f -> new ArrayList<>()).add(new String[] { reported.group(2), reported.group(4) });
        }
        int changed = 0;
        for (var entry : sites.entrySet()) {
            String text = Files.readString(entry.getKey());
            if (!text.contains("@Mixin")) continue;
            List<String> imports = new ArrayList<>();
            Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(text);
            while (imp.find()) imports.add(imp.group(1));
            String after = text;
            for (String[] site : entry.getValue()) {
                if (supertypeIndex == null) supertypeIndex = new Explain.Index(classpath(model));
                List<String> found = supertypeIndex.resolve(site[1].replaceAll(".*\\.", ""), imports);
                Explain.ClassInfo info = found.isEmpty() ? null : supertypeIndex.info(found.get(0));
                if (info == null) continue;
                Explain.Member ctor = null;
                for (Explain.Member m : info.members()) {
                    if (!m.method() || !m.name().equals("<init>") || (m.access() & 0x0002) != 0) continue;
                    if (ctor == null || placeholders(m.descriptor()).size() < placeholders(ctor.descriptor()).size()) ctor = m;
                }
                if (ctor == null) continue;
                int lineStart = 0;
                for (int i = 1; i < Integer.parseInt(site[0]); i++) lineStart = after.indexOf('\n', lineStart) + 1;
                Matcher superCall = Pattern.compile("\\bsuper\\s*\\(").matcher(after);
                if (!superCall.find(lineStart) || after.substring(lineStart, superCall.start()).contains("\n")) continue;
                int open = superCall.end() - 1, close = closingParen(after, open);
                if (close < 0) continue;
                after = after.substring(0, open + 1) + String.join(", ", placeholders(ctor.descriptor())) + after.substring(close);
            }
            if (!after.equals(text)) {
                Files.writeString(entry.getKey(), after);
                changed++;
            }
        }
        if (changed > 0) rulesApplied.putIfAbsent("mixin-constructor-placeholders", List.of());
        return changed;
    }

    /** Typed placeholder arguments for a descriptor's parameters: (fully.Qualified) null, or the primitive's zero. */
    static List<String> placeholders(String descriptor) {
        List<String> out = new ArrayList<>();
        Matcher p = Pattern.compile("(\\[*)(?:L([^;]+);|([ZBCSIJFD]))").matcher(descriptor.substring(descriptor.indexOf('(') + 1, descriptor.indexOf(')')));
        while (p.find()) {
            if (!p.group(1).isEmpty() || p.group(2) != null) {
                String base = p.group(2) != null ? p.group(2).replace('/', '.').replace('$', '.') : switch (p.group(3)) {
                    case "Z" -> "boolean";
                    case "B" -> "byte";
                    case "C" -> "char";
                    case "S" -> "short";
                    case "I" -> "int";
                    case "J" -> "long";
                    case "F" -> "float";
                    default -> "double";
                };
                out.add("(" + base + "[]".repeat(p.group(1).length()) + ") null");
                continue;
            }
            out.add(switch (p.group(3)) {
                case "Z" -> "false";
                case "J" -> "0L";
                case "F" -> "0.0F";
                case "D" -> "0.0D";
                case "C" -> "(char) 0";
                case "B" -> "(byte) 0";
                case "S" -> "(short) 0";
                default -> "0";
            });
        }
        return out;
    }

    void applyRules(Path log) throws Exception {
        removeDeadMixinTargets(log);
        repairAccess(log);
        mixinConstructors(log);
        String errors = logText(log);
        List<Path> all = ownedJava();
        Set<Path> logged = new HashSet<>();
        Matcher reported = Pattern.compile("(?m)^\\s*" + FILE + ":[0-9]+: error:").matcher(errors);
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            file = file.normalize();
            if (file.startsWith(build.project.root)) logged.add(file);
        }
        // A file javac reported while building this target is part of it, even when another project owns it (an
        // Architectury :common compiled into :fabric).
        Set<Path> withLogged = new LinkedHashSet<>(all);
        for (Path file : logged) if (Files.isRegularFile(file) && file.toString().endsWith(".java")) withLogged.add(file);
        all = new ArrayList<>(withLogged);
        for (Object item : Json.array(Json.read(build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (
                rule.containsKey("silent") ||
                !ownRule(rule, learnFromMods) ||
                !loaderFits(rule) ||
                !Pattern.compile(rule.get("match").toString()).matcher(errors).find()
            ) continue;
            // A routed port runs a rule only when its jump covers an update the route crosses and its names fit.
            List<String> covered = route == null
                ? List.of()
                : route.stepsCovered(Json.text(rule, "jump", ""), namespace);
            if (route != null && covered.isEmpty()) continue;
            if (!targetHas(rule)) continue;
            Object transform = Json.object(rule.get("apply")).get("transform");
            if ("accessor".equals(transform) || "payload-bytes".equals(transform)) {
                if (
                    "accessor".equals(transform) ? applyAccessor(rule, errors) : applyPayloadBytes(rule, errors)
                ) rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                continue;
            }
            if ("line".equals(rule.get("scope"))) {
                for (Path file : logged) {
                    if (!all.contains(file)) continue;
                    String original = Files.readString(file), text = rewriteReported(rule, original, errors, named -> {
                        Path path = Path.of(named);
                        return (path.isAbsolute() ? path : build.project.root.resolve(path)).normalize().equals(file);
                    });
                    if (!text.equals(original)) {
                        Files.writeString(file, deferImports(file, original, text));
                        rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                    }
                }
                continue;
            }
            for (Path file : all) {
                if (!"repo".equals(rule.get("scope")) && !logged.contains(file)) continue;
                String original = Files.readString(file), text = rewrite(rule, original);
                if (!text.equals(original)) {
                    Files.writeString(file, deferImports(file, original, text));
                    rulesApplied.putIfAbsent(Json.text(rule, "id", "rule"), covered);
                }
            }
        }
    }

    /**
     * Imports a repair round adds wait until the round's line-based steps are done (these rules and the typed fixer read
     * javac's line numbers from the same log): an import line inserted first would aim every later step below it one
     * line off. Takes the import lines addImport put after the package line back out of text and keeps them for
     * flushImports.
     */
    final Map<Path, Set<String>> pendingImports = new LinkedHashMap<>();

    String deferImports(Path file, String original, String text) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        int added = lines.size() - original.split("\n", -1).length;
        if (added <= 0) return text;
        Set<String> had = new HashSet<>();
        for (String line : original.split("\n", -1)) had.add(line.strip());
        int at = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches("\\s*package\\s+[\\w.]+\\s*;.*")) {
            at = i + 1;
            break;
        }
        while (
            added > 0 &&
            at < lines.size() &&
            lines.get(at).strip().matches("import [\\w.]+;") &&
            !had.contains(lines.get(at).strip())
        ) {
            String line = lines.remove(at).strip();
            added--;
            pendingImports.computeIfAbsent(file, f -> new LinkedHashSet<>()).add(line.substring(7, line.length() - 1));
        }
        return String.join("\n", lines);
    }

    void flushImports() throws IOException {
        for (var entry : pendingImports.entrySet()) {
            if (!Files.isRegularFile(entry.getKey())) continue;
            String text = Files.readString(entry.getKey()), out = text;
            for (String name : entry.getValue()) out = addImport(out, name);
            if (!out.equals(text)) Files.writeString(entry.getKey(), out);
        }
        pendingImports.clear();
    }

    /**
     * The repair guides (knowledge/structural.json) for what a stopped port left: a guide is offered when its match finds
     * one of javac's errors and the port crosses the release its jump names ("A -> B" changed in B, "X+" in X), either way.
     */
    static List<Map<String, Object>> repairGuides(Path harness, String from, String to, String log) throws IOException {
        Path file = harness.resolve("knowledge/structural.json");
        if (!Files.exists(file)) return List.of();
        StringBuilder errors = new StringBuilder();
        // javac's own blocks (an error line and the lines under it), not Gradle's repeats or the rest of the log.
        List<String> lines = log.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!ERROR.matcher(lines.get(i)).find()) continue;
            errors.append(lines.get(i)).append('\n');
            for (int j = i + 1; j < lines.size() && j <= i + 5 && !ERROR.matcher(lines.get(j)).find(); j++) errors
                .append(lines.get(j))
                .append('\n');
        }
        List<Map<String, Object>> guides = new ArrayList<>();
        for (Object item : Json.array(Json.parse(Files.readString(file, StandardCharsets.UTF_8)))) {
            var guide = Json.object(item);
            if (
                !crosses(Json.text(guide, "jump", ""), from, to) ||
                !Pattern.compile(Json.text(guide, "match", "(?!)")).matcher(errors).find()
            ) continue;
            guides.add(
                Json.map(
                    "id",
                    guide.get("id"),
                    "title",
                    guide.get("title"),
                    "steps",
                    guide.get("steps"),
                    "note",
                    guide.get("note")
                )
            );
        }
        return guides;
    }

    /** The colour argument's position in each text draw call (mojmap GuiGraphics and yarn DrawContext). */
    private static final Map<String, Integer> COLOR_ARGUMENT = Map.of(
        "drawString",
        4,
        "drawCenteredString",
        4,
        "drawWordWrap",
        5,
        "drawText",
        4,
        "drawTextWithShadow",
        4,
        "drawCenteredTextWithShadow",
        4,
        "drawWrappedText",
        5
    );

    /**
     * 1.21.6 draws GUI colours as ARGB: before it, text with no alpha byte was drawn opaque, after it, invisible. Gives
     * an alpha byte to a 6-digit hex literal, to a decimal colour in a text draw call's colour position, and to a
     * decimal literal assigned to a name with "colo(u)r" in it.
     */
    static String opaqueGuiColors(String text) {
        text = Pattern.compile("\\b0[xX]([0-9a-fA-F]{6})\\b(?![0-9a-fA-F])").matcher(text).replaceAll("0xff$1");
        Matcher named = Pattern.compile("(\\b\\w*[Cc]olou?r\\w*\\s*=\\s*)(\\d+)\\b(?![.\\dLlFfDd])").matcher(text);
        StringBuilder out = new StringBuilder();
        while (named.find()) {
            long value = Long.parseLong(named.group(2));
            named.appendReplacement(
                out,
                Matcher.quoteReplacement(
                    value > 0 && value < 0x1000000L
                        ? named.group(1) + String.format(Locale.ROOT, "0xff%06x", value)
                        : named.group()
                )
            );
        }
        named.appendTail(out);
        text = out.toString();
        Matcher call = Pattern.compile("\\.(" + String.join("|", COLOR_ARGUMENT.keySet()) + ")\\(").matcher(text);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        while (call.find()) {
            List<int[]> arguments = arguments(text, call.end() - 1);
            int position = COLOR_ARGUMENT.get(call.group(1));
            if (position >= arguments.size()) continue;
            int[] span = arguments.get(position);
            String argument = text.substring(span[0], span[1]);
            if (!argument.strip().matches("\\d+")) continue;
            long value = Long.parseLong(argument.strip());
            if (value >= 0x1000000L) continue;
            result
                .append(text, copied, span[0])
                .append(argument.replace(argument.strip(), String.format(Locale.ROOT, "0xff%06x", value)));
            copied = span[1];
        }
        return result.append(text.substring(copied)).toString();
    }

    /**
     * The top-level arguments of the call whose '(' is at open, as [start, end) spans: nested calls, lambda bodies, string
     * and char literals are skipped over. Empty for f(); what was found so far when the call never closes.
     */
    static List<int[]> arguments(String text, int open) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0, start = open + 1;
        char quote = 0;
        for (int at = open + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quote != 0) {
                if (c == '\\') at++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
            else if (c == ')' || (c == ',' && depth == 0)) {
                if (c == ',' || !text.substring(start, at).isBlank() || !spans.isEmpty()) spans.add(
                    new int[] { start, at }
                );
                start = at + 1;
                if (c == ')') break;
            }
        }
        return spans;
    }

    /**
     * Fabric's 1.20.5 networking took away channel-plus-buffer packets. On the lines javac reported, each old call is
     * carried over to a payload that holds the same bytes (the port writes the class, named here): a send's channel and
     * buffer become name.of(channel, buf), a receiver's channel becomes name.type(channel), and its old lambda
     * (client, handler, buf, responseSender) or (server, player, handler, buf, responseSender) gets the new
     * (payload, context) one with the old names declared first, so its body runs unchanged. Every line when lines is null.
     */
    /**
     * A method that now takes a different argument type, written the same way for every argument: in each statement that
     * starts with apply.statement (a regex), every top-level argument of a call to one of apply.calls, across lines, is
     * rewritten by apply.wrap ($0 is the argument), unless it already matches apply.skip. 26.2's TagAppender.add takes a
     * ResourceKey: tag(T).add(BLOCK_A, BLOCK_B.get()) -> add(BLOCK_A.builtInRegistryHolder().key(), ...).
     */
    /**
     * 1.21.2 split a toast's Visibility render(GuiGraphics, ToastComponent, long) into update(ToastManager, long),
     * getWantedVisibility() and void render(GuiGraphics, Font, long); ToastComponent became ToastManager, with the same
     * methods. Every toast in the file keeps its drawing code unchanged as mmtRender: update keeps the manager, render
     * draws through mmtRender and keeps the visibility it answers, and getWantedVisibility hands that to the game, which
     * asks after each update. A toast that already has getWantedVisibility is left alone.
     */
    static String toastVisibility(String text) {
        Matcher old = Pattern.compile(
            "(?:@Override\\s+)?public\\s+((?:[\\w.]*\\.)?Visibility)\\s+render\\s*\\(\\s*(?:final\\s+)?GuiGraphics\\s+(\\w+)\\s*,\\s*" +
            "(?:final\\s+)?(?:[\\w.]*\\.)?(?:ToastComponent|ToastManager)\\s+(\\w+)\\s*,\\s*(?:final\\s+)?long\\s+(\\w+)\\s*\\)\\s*\\{"
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        int done = 0;
        while (old.find()) {
            int close = closingBrace(text, old.end() - 1);
            int classOpen = text.lastIndexOf("class ", old.start());
            if (close < 0 || classOpen < 0 || text.substring(classOpen, old.start()).contains("getWantedVisibility")) continue;
            String manager = "net.minecraft.client.gui.components.toasts.ToastManager";
            String visibility = "net.minecraft.client.gui.components.toasts.Toast.Visibility";
            out.append(text, done, old.start())
                .append("private ").append(visibility).append(" mmtRender(GuiGraphics ").append(old.group(2)).append(", ").append(manager)
                .append(' ').append(old.group(3)).append(", long ").append(old.group(4)).append(") {")
                .append(text, old.end(), close + 1)
                .append("\n\n    private ").append(manager).append(" mmtToastManager;\n")
                .append("    private ").append(visibility).append(" mmtVisibility = ").append(visibility).append(".SHOW;\n\n")
                .append("    @Override\n    public void update(").append(manager).append(" manager, long time) {\n")
                .append("        this.mmtToastManager = manager;\n    }\n\n")
                .append("    @Override\n    public ").append(visibility).append(" getWantedVisibility() {\n")
                .append("        return this.mmtVisibility;\n    }\n\n")
                .append("    @Override\n    public void render(GuiGraphics graphics, net.minecraft.client.gui.Font font, long time) {\n")
                .append("        this.mmtVisibility = mmtRender(graphics, this.mmtToastManager, time);\n    }");
            done = close + 1;
        }
        return done == 0 ? text : out.append(text.substring(done)).toString();
    }

    /**
     * 1.21.2 split entity rendering into extracting a render state and drawing it: EntityRenderer<T> became
     * EntityRenderer<T, S extends EntityRenderState>, and render(T entity, float yaw, float partialTicks, PoseStack,
     * MultiBufferSource, int) became render(S state, PoseStack, MultiBufferSource, int). A renderer that extends
     * EntityRenderer<E> directly keeps its drawing code unchanged behind a state of its own that carries what the old
     * method was given: the entity, the partial tick, and the yaw the 1.21.1 dispatcher computed
     * (Mth.lerp(partialTick, yRotO, getYRot())). The old parameter names are read back from it at the top of render, and
     * super.render passes the state. getTextureLocation(E) stays as a plain method: EntityRenderer has none any more.
     * Another renderer's render(entity, yaw, partialTicks, ...) is drawn through createRenderState(entity, partialTicks).
     * A generic renderer (class R<T> extends EntityRenderer<T>) is left to the compiler.
     */
    static String entityRenderState(String text) {
        Matcher cls = Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+EntityRenderer\\s*<\\s*([\\w.]+)\\s*>").matcher(text);
        if (!cls.find()) return text;
        String renderer = cls.group(1), entity = cls.group(2);
        int open = text.indexOf('{', cls.end());
        int close = open < 0 ? -1 : closingBrace(text, open);
        if (close < 0) return text;
        String body = text.substring(open + 1, close);
        Matcher render = Pattern.compile(
            "((?:@Override\\s+)?public\\s+void\\s+render\\s*)\\(\\s*(?:final\\s+)?" + Pattern.quote(entity) +
            "\\s+(\\w+)\\s*,\\s*(?:final\\s+)?float\\s+(\\w+)\\s*,\\s*(?:final\\s+)?float\\s+(\\w+)\\s*,\\s*" +
            "((?:final\\s+)?PoseStack\\s+(\\w+)\\s*,\\s*(?:final\\s+)?MultiBufferSource\\s+(\\w+)\\s*,\\s*(?:final\\s+)?int\\s+(\\w+))\\s*\\)\\s*\\{"
        ).matcher(body);
        if (!render.find()) return text;
        String entityName = render.group(2), yawName = render.group(3), tickName = render.group(4);
        int renderClose = closingBrace(body, render.end() - 1);
        if (renderClose < 0) return text;
        String renderBody = body.substring(render.end(), renderClose);
        // super.render(entity, yaw, partialTicks, poseStack, buffer, light) -> super.render(state, poseStack, buffer, light)
        StringBuilder sb = new StringBuilder();
        Matcher superRender = Pattern.compile("\\bsuper\\s*\\.\\s*render\\s*\\(").matcher(renderBody);
        int done = 0;
        while (superRender.find()) {
            int callOpen = superRender.end() - 1, callClose = closingParen(renderBody, callOpen);
            if (callClose < 0) break;
            List<String> args = topLevelArguments(renderBody.substring(callOpen + 1, callClose));
            if (args.size() != 6) continue;
            sb.append(renderBody, done, callOpen + 1).append("state, ").append(args.get(3).strip()).append(", ").append(args.get(4).strip())
                .append(", ").append(args.get(5).strip()).append(')');
            done = callClose + 1;
        }
        sb.append(renderBody.substring(done));
        String indent = "        ";
        String newRender =
            render.group(1) + "(MmtState state, " + render.group(5) + ") {\n" +
            indent + entity + " " + entityName + " = state.mmtEntity;\n" +
            indent + "float " + yawName + " = state.mmtYaw;\n" +
            indent + "float " + tickName + " = state.mmtPartialTick;" +
            sb;
        String added =
            "\n\n    @Override\n    public MmtState createRenderState() {\n        return new MmtState();\n    }\n\n" +
            "    @Override\n    public void extractRenderState(" + entity + " entity, MmtState state, float partialTick) {\n" +
            "        super.extractRenderState(entity, state, partialTick);\n" +
            "        state.mmtEntity = entity;\n        state.mmtPartialTick = partialTick;\n" +
            "        state.mmtYaw = net.minecraft.util.Mth.lerp(partialTick, entity.yRotO, entity.getYRot());\n    }\n\n" +
            "    /** What render was given before 1.21.2 split it into extracting and drawing a render state. */\n" +
            "    public static class MmtState extends EntityRenderState {\n        public " + entity + " mmtEntity;\n" +
            "        public float mmtPartialTick;\n        public float mmtYaw;\n    }\n";
        String newBody = body.substring(0, render.start()) + newRender + body.substring(renderClose) ;
        // EntityRenderer no longer declares getTextureLocation: the mod's stays as a plain method.
        newBody = newBody.replaceAll(
            "(?m)^([ \\t]*)@Override\\s+(public\\s+[\\w.]+\\s+getTextureLocation\\s*\\(\\s*" + Pattern.quote(entity) + "\\b)",
            "$1$2"
        );
        // Another renderer drawn with an entity: through its own render state.
        Matcher delegate = Pattern.compile("([\\w.]+(?:\\([^()]*\\))?)\\s*\\.\\s*render\\s*\\(").matcher(newBody);
        StringBuilder withDelegates = new StringBuilder();
        done = 0;
        while (delegate.find()) {
            if (delegate.group(1).equals("super")) continue;
            int callOpen = delegate.end() - 1, callClose = closingParen(newBody, callOpen);
            if (callClose < 0) continue;
            List<String> args = topLevelArguments(newBody.substring(callOpen + 1, callClose));
            if (args.size() != 6) continue;
            String target = delegate.group(1);
            withDelegates.append(newBody, done, callOpen + 1).append(target).append(".createRenderState(").append(args.get(0).strip())
                .append(", ").append(args.get(2).strip()).append("), ").append(args.get(3).strip()).append(", ").append(args.get(4).strip())
                .append(", ").append(args.get(5).strip()).append(')');
            done = callClose + 1;
        }
        withDelegates.append(newBody.substring(done));
        String header = text.substring(0, cls.start()) + text.substring(cls.start(), cls.end()).replaceFirst(
            "EntityRenderer\\s*<\\s*" + Pattern.quote(entity) + "\\s*>",
            Matcher.quoteReplacement("EntityRenderer<" + entity + ", " + renderer + ".MmtState>")
        );
        String out = header + text.substring(cls.end(), open + 1) + withDelegates + added + text.substring(close);
        return addImport(out, "net.minecraft.client.renderer.entity.state.EntityRenderState");
    }

    static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return -1;
    }

    static String callArguments(String text, Map<String, Object> apply) {
        Pattern statement = Pattern.compile(Json.text(apply, "statement", ""));
        // A method call (.name( ) or a constructor call (new Name( ); "rename" gives the call a new name, "argument" (from 0)
        // rewrites that argument alone.
        Pattern call = Pattern.compile("(\\.|\\bnew\\s+)(" + String.join("|", ProjectModel.strings(apply.get("calls"))) + ")(\\s*\\()");
        String skip = Json.text(apply, "skip", ""), wrap = Json.text(apply, "wrap", "$0"), rename = Json.text(apply, "rename", "");
        int only = apply.get("argument") instanceof Number n ? n.intValue() : -1;
        StringBuilder out = new StringBuilder();
        int done = 0;
        Matcher start = statement.matcher(text);
        while (start.find(done)) {
            int end = statementEnd(text, start.start());
            if (end < 0) break;
            out.append(text, done, start.start());
            String body = text.substring(start.start(), end);
            StringBuilder rewritten = new StringBuilder();
            int at = 0;
            Matcher c = call.matcher(body);
            while (c.find(at)) {
                int close = closingParen(body, c.end() - 1);
                if (close < 0) break;
                rewritten.append(body, at, c.start()).append(c.group(1)).append(rename.isEmpty() ? c.group(2) : rename).append(c.group(3));
                List<String> parts = topLevelArguments(body.substring(c.end(), close));
                for (int i = 0; i < parts.size(); i++) {
                    String part = parts.get(i), arg = part.strip();
                    boolean keep = arg.isEmpty() || (only >= 0 && i != only) || (!skip.isEmpty() && Pattern.compile(skip).matcher(arg).find());
                    String lead = part.substring(0, part.indexOf(arg.isEmpty() ? part : arg)), tail = part.substring(lead.length() + arg.length());
                    rewritten.append(lead).append(keep ? arg : wrap.replace("$0", arg)).append(tail);
                    if (i < parts.size() - 1) rewritten.append(',');
                }
                rewritten.append(')');
                at = close + 1;
            }
            rewritten.append(body.substring(at));
            out.append(rewritten);
            done = end;
        }
        return out.append(text.substring(done)).toString();
    }

    /** The index just after the ';' that ends the statement starting at from, outside brackets and strings; -1 if none. */
    static int statementEnd(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') depth--;
            else if (ch == ';' && depth <= 0) return i + 1;
        }
        return -1;
    }

    /** The index of the ')' closing the '(' at open; -1 if none. */
    static int closingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    static int stringEnd(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == '"') return i;
        }
        return text.length() - 1;
    }

    /** An argument list split at its top-level commas, each part with its own whitespace. */
    static List<String> topLevelArguments(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '"') i = stringEnd(args, i);
            else if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(args.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(args.substring(from));
        return parts;
    }

    static String payloadCalls(String text, String name, Set<Integer> lines) {
        Matcher call = Pattern.compile(
            "\\b(?:(Client|Server)PlayNetworking\\s*\\.\\s*(registerGlobalReceiver|send)|new\\s+CustomPayload(?:S2C|C2S)Packet)\\s*\\("
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0, line = 1, counted = 0;
        while (call.find()) {
            for (; counted < call.start(); counted++) if (text.charAt(counted) == '\n') line++;
            if ((lines != null && !lines.contains(line)) || call.start() < copied) continue;
            List<int[]> args = arguments(text, call.end() - 1);
            String side = call.group(1), verb = call.group(2), replacement = null;
            int from = 0, to = 0;
            if (verb == null || verb.equals("send")) {
                // new CustomPayloadS2CPacket(channel, buf), ClientPlayNetworking.send(channel, buf), ServerPlayNetworking.send(player, channel, buf)
                int first = "Server".equals(side) ? 1 : 0;
                if (args.size() != first + 2) continue;
                from = args.get(first)[0];
                to = args.get(first + 1)[1];
                String inner = text.substring(from, to);
                int lead = inner.length() - inner.stripLeading().length();
                replacement = inner.substring(0, lead) + name + ".of(" + inner.strip() + ")";
            } else {
                if (args.size() != 2) continue;
                Matcher lambda = Pattern.compile(
                    "\\A(\\s*)\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)(?:\\s*,\\s*(\\w+))?\\s*\\)\\s*->\\s*\\{"
                ).matcher(text.substring(args.get(1)[0], args.get(1)[1]));
                if (!lambda.find() || "Client".equals(side) != (lambda.group(6) == null)) continue;
                String declared = "Client".equals(side)
                    ? " var " +
                    lambda.group(2) +
                    " = mmt$context.client(); var " +
                    lambda.group(3) +
                    " = mmt$context.client().getNetworkHandler(); var " +
                    lambda.group(4) +
                    " = mmt$payload.buf(); var " +
                    lambda.group(5) +
                    " = mmt$context.responseSender();"
                    : " var " +
                    lambda.group(2) +
                    " = mmt$context.server(); var " +
                    lambda.group(3) +
                    " = mmt$context.player(); var " +
                    lambda.group(4) +
                    " = mmt$context.player().networkHandler; var " +
                    lambda.group(5) +
                    " = mmt$payload.buf(); var " +
                    lambda.group(6) +
                    " = mmt$context.responseSender();";
                from = args.get(0)[0];
                to = args.get(1)[0] + lambda.end();
                String channel = text.substring(from, args.get(0)[1]);
                int lead = channel.length() - channel.stripLeading().length();
                replacement =
                    channel.substring(0, lead) +
                    name +
                    ".type(" +
                    channel.strip() +
                    ")" +
                    text.substring(args.get(0)[1], args.get(1)[0]) +
                    lambda.group(1) +
                    "(mmt$payload, mmt$context) -> {" +
                    declared;
            }
            out.append(text, copied, from).append(replacement);
            copied = to;
        }
        return copied == 0 ? text : out.append(text.substring(copied)).toString();
    }

    /** Runs a "payload-bytes" rule: writes its class beside the first file it changes and imports it where it is used. */
    boolean applyPayloadBytes(Map<String, Object> rule, String errors) throws Exception {
        var apply = Json.object(rule.get("apply"));
        String name = Json.text(apply, "name", "MmtPacket");
        Map<Path, String> rewritten = new LinkedHashMap<>();
        for (var site : reportedLines(errors, Pattern.compile(rule.get("match").toString())).entrySet()) {
            String original = Files.readString(site.getKey()), text = payloadCalls(
                original,
                name,
                site.getValue().keySet()
            );
            if (!text.equals(original)) rewritten.put(site.getKey(), text);
        }
        if (rewritten.isEmpty()) return false;
        Path first = rewritten.keySet().iterator().next();
        Matcher declared = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(rewritten.get(first));
        if (!declared.find()) return false;
        Path file = first.getParent().resolve(name + ".java");
        if (Files.exists(file) && !Files.readString(file).contains(WRITTEN_MARK)) return false;
        if (!Files.exists(file)) {
            track(file);
            Files.writeString(file, Json.text(apply, "source", "").replace("{package}", declared.group(1)));
        }
        for (var entry : rewritten.entrySet()) Files.writeString(
            entry.getKey(),
            deferImports(
                entry.getKey(),
                Files.readString(entry.getKey()),
                addImport(entry.getValue(), declared.group(1) + "." + name)
            )
        );
        return true;
    }

    /** Each project file javac reported an error in whose block the pattern fits, with those lines and the symbol each error names. */
    Map<Path, Map<Integer, String>> reportedLines(String errors, Pattern match) {
        Map<Path, Map<Integer, String>> sites = new LinkedHashMap<>();
        List<String> log = errors.lines().toList();
        for (int i = 0; i < log.size(); i++) {
            Matcher error = ERROR.matcher(log.get(i));
            if (!error.find()) continue;
            StringBuilder block = new StringBuilder(log.get(i)).append('\n');
            for (int j = i + 1; j < log.size() && j <= i + 5 && !ERROR.matcher(log.get(j)).find(); j++) block
                .append(log.get(j))
                .append('\n');
            if (!match.matcher(block).find()) continue;
            Path file = Path.of(error.group(1));
            if (!file.isAbsolute()) file = build.project.root.resolve(file);
            file = file.normalize();
            if (!file.startsWith(build.project.root) || !Files.isRegularFile(file)) continue;
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+ (\\w+)").matcher(block);
            sites
                .computeIfAbsent(file, f -> new TreeMap<>())
                .put(Integer.parseInt(error.group(2)), symbol.find() ? symbol.group(1) : "");
        }
        return sites;
    }

    /** One knowledge rule on one source text: its `when` gate, the literal or regex rewrite, then any imports it needs. */
    /**
     * A rule's replacement for one match: $N is group N as usual, and $^N is group N in UPPER_SNAKE case, for a getter the
     * game turned into a constant (1.21.2's GameRenderer::getPositionTexShader -> CoreShaders.POSITION_TEX). The result
     * is still a Matcher replacement: replaceAll expands the plain $N itself.
     */
    static String expandReplacement(String replace, java.util.regex.MatchResult match) {
        Matcher token = Pattern.compile("\\$\\^(\\d)").matcher(replace);
        StringBuilder out = new StringBuilder();
        while (token.find()) {
            String group = match.group(Integer.parseInt(token.group(1)));
            String upper = group == null
                ? ""
                : group.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toUpperCase(Locale.ROOT);
            // Quoted twice: once for this appendReplacement, once for the replaceAll that reads the result.
            token.appendReplacement(out, Matcher.quoteReplacement(Matcher.quoteReplacement(upper)));
        }
        return token.appendTail(out).toString();
    }

    static String rewrite(Map<String, Object> rule, String original) {
        var apply = Json.object(rule.get("apply"));
        String text = original;
        String when = Json.text(rule, "when", "");
        if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return original;
        if ("opaque-gui-colors".equals(apply.get("transform"))) text = opaqueGuiColors(text);
        else if ("call-arguments".equals(apply.get("transform"))) text = callArguments(text, apply);
        else if ("entity-render-state".equals(apply.get("transform"))) text = entityRenderState(text);
        else if ("toast-visibility".equals(apply.get("transform"))) text = toastVisibility(text);
        // A rule's test has no project to put the accessor in: it reads as if the mod's mixin package were mod.mixin.
        else if ("accessor".equals(apply.get("transform"))) text = accessorCalls(
            text,
            apply,
            "mod.mixin." + simpleName(Json.text(apply, "owner", "")) + "Accessor",
            null
        );
        else if ("payload-bytes".equals(apply.get("transform"))) text = payloadCalls(
            text,
            Json.text(apply, "name", "MmtPacket"),
            null
        );
        else if (apply.containsKey("literal")) text = text.replace(
            apply.get("literal").toString(),
            Json.text(apply, "replace", "")
        );
        else if (apply.containsKey("find")) text = Pattern.compile(apply.get("find").toString())
            .matcher(text)
            .replaceAll(match -> expandReplacement(Json.text(apply, "replace", ""), match));
        if (!text.equals(original)) for (String name : ProjectModel.strings(apply.get("ensureImports"))) text =
            addImport(text, name);
        return text;
    }

    /**
     * A rule with scope "line" rewrites only the lines javac reported with an error its match fits, and {symbol} in its
     * find or replace stands for the symbol that error names. Used where the same call is right elsewhere in the file:
     * getCategory() needs .value() only where its receiver became a RegistryEntry.
     */
    /** A pattern that matches nothing: a {receiver} javac named no variable for leaves the line alone. */
    static final String NOTHING = "(?!)";

    static String rewriteReported(
        Map<String, Object> rule,
        String text,
        String errors,
        java.util.function.Predicate<String> sameFile
    ) {
        String when = Json.text(rule, "when", "");
        if (!when.isEmpty() && !Pattern.compile(when).matcher(text).find()) return text;
        Pattern match = Pattern.compile(rule.get("match").toString());
        List<String> log = errors.lines().toList();
        Map<Integer, String> sites = new TreeMap<>(), looked = new HashMap<>();
        for (int i = 0; i < log.size(); i++) {
            Matcher error = ERROR.matcher(log.get(i));
            if (!error.find() || !sameFile.test(error.group(1))) continue;
            StringBuilder block = new StringBuilder(log.get(i)).append('\n');
            for (int j = i + 1; j < log.size() && j <= i + 5 && !ERROR.matcher(log.get(j)).find(); j++) block
                .append(log.get(j))
                .append('\n');
            if (!match.matcher(block).find()) continue;
            Matcher symbol = Pattern.compile("symbol:\\s+\\w+ (\\w+)").matcher(block);
            sites.put(Integer.parseInt(error.group(2)), symbol.find() ? symbol.group(1) : "");
            // {receiver}: the variable javac looked the symbol up on ("location: variable stack of type ItemStack").
            Matcher receiver = Pattern.compile("location:\\s+variable (\\w+) of type").matcher(block);
            looked.put(Integer.parseInt(error.group(2)), receiver.find() ? receiver.group(1) : NOTHING);
        }
        if (sites.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        for (var site : sites.entrySet()) {
            int at = site.getKey() - 1;
            if (at < 0 || at >= lines.length) continue;
            var helper = Json.object(rule.get("apply"));
            // A helper rule's call: its own find and replace, or receiver.symbol(args) as name(receiver, args).
            if ("helper".equals(helper.get("transform"))) {
                lines[at] = helper.containsKey("find")
                    ? Pattern.compile(helper.get("find").toString())
                        .matcher(lines[at])
                        .replaceAll(Json.text(helper, "replace", ""))
                    : helperCalls(lines[at], helper, site.getValue());
                continue;
            }
            Map<String, Object> bound = new LinkedHashMap<>(rule), apply = new LinkedHashMap<>(
                Json.object(rule.get("apply"))
            );
            for (String key : List.of("find", "replace", "literal")) if (
                apply.get(key) instanceof String value
            ) apply.put(key, value.replace("{symbol}", site.getValue()).replace("{receiver}", looked.getOrDefault(site.getKey(), NOTHING)));
            apply.remove("ensureImports");
            bound.put("apply", apply);
            bound.put("when", "");
            lines[at] = rewrite(bound, lines[at]);
        }
        String out = String.join("\n", lines);
        if (!out.equals(text) && "helper".equals(Json.object(rule.get("apply")).get("transform"))) out = insertHelper(
            out,
            Json.object(rule.get("apply"))
        );
        if (!out.equals(text)) for (String name : ProjectModel.strings(
            Json.object(rule.get("apply")).get("ensureImports")
        )) out = addImport(out, name);
        return out;
    }

    /** receiver.symbol(args) on one line becomes name(receiver, args): the call goes through a helper the port adds to the class. */
    static String helperCalls(String line, Map<String, Object> apply, String symbol) {
        String name = Json.text(apply, "name", "");
        Matcher call = Pattern.compile("\\.\\s*" + Pattern.quote(symbol) + "\\s*\\(").matcher(line);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (call.find()) {
            int receiver = receiverStart(line, call.start());
            if (receiver >= call.start() || receiver < copied) continue;
            boolean noArguments = line.substring(call.end()).stripLeading().startsWith(")");
            out
                .append(line, copied, receiver)
                .append(name)
                .append('(')
                .append(line, receiver, call.start())
                .append(noArguments ? "" : ", ");
            copied = call.end();
        }
        return copied == 0 ? line : out.append(line.substring(copied)).toString();
    }

    /** Adds a helper rule's method to the end of the file's top-level class, once. */
    static String insertHelper(String text, Map<String, Object> apply) {
        String name = Json.text(apply, "name", "");
        if (Pattern.compile("static[^;{=]*\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text).find()) return text;
        int end = text.lastIndexOf('}');
        if (end < 0) return text;
        String body = text.substring(0, end).replaceAll("[ \\t]*$", "");
        return (
            body +
            (body.endsWith("\n") ? "" : "\n") +
            "\n" +
            Json.text(apply, "source", "") +
            "\n" +
            text.substring(end)
        );
    }

    static String addImport(String text, String name) {
        if (text.contains("import " + name + ";")) return text;
        Matcher header = Pattern.compile("(?m)^package ([^;]+);").matcher(text);
        if (header.find() && name.equals(header.group(1).strip() + "." + simpleName(name))) return text;
        return header.find(0)
            ? text.substring(0, header.end()) + "\nimport " + name + ";" + text.substring(header.end())
            : "import " + name + ";\n" + text;
    }

    /** The accessor method that stands in for a removed getter or setter: mmt$getExhaustion for the field exhaustion. */
    static String accessorMethod(String kind, String field) {
        return "mmt$" + kind + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /**
     * A getter or setter the game removed while keeping its private field (1.21.2's HungerManager.getExhaustion()): each
     * call on the given lines (every line when null) goes through the accessor mixin instead, as
     * ((HungerManagerAccessor) receiver).mmt$getExhaustion(). A call with no receiver is left for javac to report.
     */
    static String accessorCalls(String text, Map<String, Object> apply, String accessor, Set<Integer> lines) {
        String field = Json.text(apply, "field", ""), simple = simpleName(accessor);
        Map<String, String> methods = new LinkedHashMap<>();
        for (String kind : List.of("get", "set")) if (!Json.text(apply, kind, "").isEmpty()) methods.put(
            Json.text(apply, kind, ""),
            accessorMethod(kind, field)
        );
        if (methods.isEmpty() || field.isEmpty()) return text;
        Matcher call = Pattern.compile("\\.\\s*(" + String.join("|", methods.keySet()) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0, line = 1, counted = 0;
        while (call.find()) {
            for (; counted < call.start(); counted++) if (text.charAt(counted) == '\n') line++;
            if (lines != null && !lines.contains(line)) continue;
            int receiver = receiverStart(text, call.start());
            if (receiver >= call.start() || receiver < copied) continue;
            out
                .append(text, copied, receiver)
                .append("((")
                .append(simple)
                .append(") ")
                .append(text, receiver, call.start())
                .append(").")
                .append(methods.get(call.group(1)))
                .append('(');
            copied = call.end();
            // The removed setter took another type than the field ("setOwnerUUID(UUID)" for an EntityReference): its
            // argument goes through the rule's setArgument, {value} standing for what the mod passed.
            String wrap = Json.text(apply, "setArgument", "");
            List<int[]> passed = arguments(text, call.end() - 1);
            if (!wrap.isEmpty() && call.group(1).equals(Json.text(apply, "set", "")) && passed.size() == 1) {
                out.append(wrap.replace("{value}", text.substring(passed.get(0)[0], passed.get(0)[1]).strip()));
                copied = passed.get(0)[1];
            }
        }
        if (copied == 0) return text;
        return addImport(out.append(text.substring(copied)).toString(), accessor);
    }

    /** Where the receiver of the call whose '.' is at dot starts: a chain of names, calls and indexes such as a.b().c[0]. */
    static int receiverStart(String text, int dot) {
        int begin = dot;
        while (true) {
            int end = begin - 1;
            while (end >= 0 && Character.isWhitespace(text.charAt(end))) end--;
            if (end < 0) return begin;
            char c = text.charAt(end);
            int start;
            if (c == ')' || c == ']') {
                int depth = 0, at = end;
                for (; at >= 0; at--) {
                    char d = text.charAt(at);
                    if (d == ')' || d == ']') depth++;
                    else if ((d == '(' || d == '[') && --depth == 0) break;
                }
                if (at < 0) return begin;
                start = at;
                int name = start - 1;
                while (name >= 0 && Character.isJavaIdentifierPart(text.charAt(name))) name--;
                if (name + 1 < start) start = name + 1; // a call or an index on a name: foo(...), foo[...]
                else if (c == ']') {
                    begin = start;
                    continue;
                }
            } else if (Character.isJavaIdentifierPart(c)) {
                start = end;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            } else return begin;
            if (
                text.startsWith("new", Math.max(0, start - 4)) &&
                start >= 4 &&
                Character.isWhitespace(text.charAt(start - 1)) &&
                (start == 4 || !Character.isJavaIdentifierPart(text.charAt(start - 5)))
            ) start -= 4;
            begin = start;
            int previous = start - 1;
            while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
            if (previous >= 0 && text.charAt(previous) == '.') begin = previous;
            else return begin;
        }
    }

    /**
     * Runs an accessor rule on the errors it names: javac's "cannot find symbol" for the rule's getter or setter on its
     * owner class. Writes the accessor mixin into the package of the mod's main mixins config, lists it there, and sends
     * each failing call through it. False when no error is the rule's, or the mod has no mixins config to put it in
     * (then the error stays, with its repair guide).
     */
    boolean applyAccessor(Map<String, Object> rule, String errors) throws Exception {
        var apply = Json.object(rule.get("apply"));
        String owner = Json.text(apply, "owner", ""), ownerSimple = simpleName(owner);
        Set<String> names = new HashSet<>();
        for (String kind : List.of("get", "set")) if (!Json.text(apply, kind, "").isEmpty()) names.add(
            Json.text(apply, kind, "")
        );
        String methods = names.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("(?!)");
        // The receiver may be the owner or a subclass of it (a ZombieHorse for AbstractHorse's owner field): "receivers" lists them.
        String receivers = Json.text(apply, "receivers", Pattern.quote(ownerSimple));
        var sites = reportedLines(
            errors,
            Pattern.compile(
                "cannot find symbol[^\\n]*\\n(?:[^\\n]*\\n)*?\\s*symbol:\\s+method (?:" +
                methods +
                ")\\([^\\n]*\\n\\s*location:[^\\n]*\\b(?:" +
                receivers +
                ")\\b"
            )
        );
        if (sites.isEmpty()) return false;
        Set<String> used = new HashSet<>();
        for (var lines : sites.values()) for (String symbol : lines.values()) used.add(
            symbol.equals(Json.text(apply, "get", "")) ? "get" : "set"
        );
        Path config = mainMixinConfig();
        if (config == null) config = newMixinConfig();
        if (config == null) {
            System.err.println(
                "degraded: " +
                Json.text(rule, "id", "accessor") +
                " needs a mixins config and this mod has none mmt can add (it adds one to NeoForge mods only)"
            );
            return false;
        }
        String mixinPackage = Json.text(Json.read(config), "package", "");
        Path folder = null;
        for (Path source : ownedJava()) {
            Path parent = source.getParent();
            if (
                parent.endsWith(mixinPackage.replace('.', '/')) &&
                Files.readString(source).matches("(?s).*\\bpackage\\s+" + Pattern.quote(mixinPackage) + "\\s*;.*")
            ) {
                folder = parent;
                break;
            }
        }
        if (folder == null && newMixinFolder != null && mixinPackage.equals(newMixinPackage)) {
            folder = newMixinFolder;
            Files.createDirectories(folder);
        }
        if (folder == null) return false;
        String simple = ownerSimple + "Accessor";
        Path file = folder.resolve(simple + ".java");
        if (Files.exists(file) && !Files.readString(file).contains(WRITTEN_MARK)) {
            simple = ownerSimple + "MmtAccessor";
            file = folder.resolve(simple + ".java");
        }
        String existing = Files.exists(file) ? Files.readString(file) : "";
        for (String kind : List.of("get", "set")) if (
            existing.contains(accessorMethod(kind, Json.text(apply, "field", "")) + "(")
        ) used.add(kind);
        track(file);
        Files.writeString(file, accessorSource(mixinPackage, simple, apply, used));
        String listed = Files.readString(config), registered = registerMixin(listed, simple);
        if (!registered.equals(listed)) {
            track(config);
            Files.writeString(config, registered);
        }
        for (var site : sites.entrySet()) {
            String original = Files.readString(site.getKey()), text = accessorCalls(
                original,
                apply,
                mixinPackage + "." + simple,
                site.getValue().keySet()
            );
            if (!text.equals(original)) Files.writeString(site.getKey(), deferImports(site.getKey(), original, text));
        }
        return true;
    }

    static final String WRITTEN_MARK = "Written by mmt port";

    /** The accessor mixin's source: a getter and, when the mod wrote the value, a setter on the owner's private field. */
    static String accessorSource(String mixinPackage, String simple, Map<String, Object> apply, Set<String> kinds) {
        String owner = Json.text(apply, "owner", ""), field = Json.text(apply, "field", ""), type = Json.text(
            apply,
            "type",
            ""
        );
        StringBuilder out = new StringBuilder(
            "package " +
            mixinPackage +
            ";\n\nimport " +
            owner +
            ";\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.gen.Accessor;\n\n"
        )
            .append("/** ")
            .append(WRITTEN_MARK)
            .append(": ")
            .append(Json.text(apply, "why", simpleName(owner) + " no longer exposes " + field + "."))
            .append(" */\n@Mixin(")
            .append(simpleName(owner))
            .append(".class)\npublic interface ")
            .append(simple)
            .append(" {\n");
        if (kinds.contains("get")) out
            .append("    @Accessor(\"")
            .append(field)
            .append("\")\n    ")
            .append(type)
            .append(' ')
            .append(accessorMethod("get", field))
            .append("();\n");
        if (kinds.contains("set")) out
            .append("    @Accessor(\"")
            .append(field)
            .append("\")\n    void ")
            .append(accessorMethod("set", field))
            .append('(')
            .append(type)
            .append(" value);\n");
        return out.append("}\n").toString();
    }

    /** Lists a mixin class in a mixins config's common "mixins" array, adding the array when the config has none; formatting kept. */
    static String registerMixin(String config, String simple) {
        if (Pattern.compile("\"" + Pattern.quote(simple) + "\"").matcher(config).find()) return config;
        Matcher common = Pattern.compile("\"mixins\"\\s*:\\s*\\[(\\s*)").matcher(config);
        if (common.find()) {
            boolean empty = config.substring(common.end()).startsWith("]");
            String indent = common.group(1).isEmpty() ? "" : common.group(1);
            return (
                config.substring(0, common.end()) +
                "\"" +
                simple +
                "\"" +
                (empty ? "" : "," + (indent.isEmpty() ? " " : indent)) +
                config.substring(common.end())
            );
        }
        Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"[^\"]*\"\\s*,?").matcher(config);
        if (!pkg.find()) return config;
        String head = config.substring(0, pkg.end());
        return (
            (head.endsWith(",") ? head : head + ",") +
            "\n  \"mixins\": [\"" +
            simple +
            "\"]" +
            (head.endsWith(",") ? "," : "") +
            config.substring(pkg.end())
        );
    }

    /** The mod's own required mixins config (a *.json with a "package"), preferring one marked required and with common mixins. */
    Path newMixinFolder;
    String newMixinPackage;

    /**
     * A NeoForge mod with no mixins gets a mixins config of its own the first time a port needs an accessor (Zombie
     * Apocalypse Addon's horse owner, 1.21.5): <mod id>.mmt.mixins.json beside its mods.toml's resources, the
     * package <the @Mod class's package>.mixin, and a [[mixins]] entry in META-INF/neoforge.mods.toml. Every file is
     * tracked, so undo removes the config and the entry again. Null for other loaders or when the mod class is not found.
     */
    Path newMixinConfig() throws Exception {
        Path toml = build.project.root.resolve("src/main/resources/META-INF/neoforge.mods.toml");
        if (!Files.isRegularFile(toml)) return null;
        String modPackage = null;
        Path modFolder = null;
        for (Path source : ownedJava()) {
            String text = Files.readString(source);
            Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
            if (text.contains("@Mod(") && pkg.find()) {
                modPackage = pkg.group(1);
                modFolder = source.getParent();
                break;
            }
        }
        if (modPackage == null) return null;
        String tomlText = Files.readString(toml);
        Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(tomlText);
        String modId = id.find() ? id.group(1) : modPackage.substring(modPackage.lastIndexOf('.') + 1);
        Matcher template = Pattern.compile("^\\$\\{(\\w+)}$").matcher(modId);
        if (template.find()) {
            Path properties = build.project.root.resolve("gradle.properties");
            String value = Files.isRegularFile(properties)
                ? ProjectModel.properties(properties).getProperty(template.group(1))
                : null;
            modId = value != null ? value.strip() : modPackage.substring(modPackage.lastIndexOf('.') + 1);
        }
        String name = modId + ".mmt.mixins.json";
        Path config = toml.getParent().getParent().resolve(name);
        track(config);
        Files.writeString(
            config,
            "{\n  \"required\": true,\n  \"minVersion\": \"0.8\",\n  \"package\": \"" +
            modPackage +
            ".mixin\",\n  \"compatibilityLevel\": \"JAVA_21\",\n  \"mixins\": [],\n  \"injectors\": {\n    \"defaultRequire\": 1\n  }\n}\n"
        );
        track(toml);
        Files.writeString(
            toml,
            tomlText +
            (tomlText.endsWith("\n") ? "" : "\n") +
            "\n# " +
            WRITTEN_MARK +
            ": the accessor mixins a port writes.\n[[mixins]]\nconfig=\"" +
            name +
            "\"\n"
        );
        newMixinPackage = modPackage + ".mixin";
        newMixinFolder = modFolder.resolve("mixin");
        build.report.stages.add(
            Json.map(
                "name",
                "mixin-config",
                "added",
                build.project.root.relativize(config).toString(),
                "package",
                newMixinPackage
            )
        );
        return config;
    }

    Path mainMixinConfig() throws IOException {
        List<Path> configs = new ArrayList<>();
        Files.walkFileTree(
            build.project.root,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return (
                            !dir.equals(build.project.root) &&
                            (ProjectModel.IGNORED.contains(dir.getFileName().toString()) ||
                                Files.exists(dir.resolve(".git")))
                        )
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json") && name.contains("mixins") && !name.contains("refmap")) configs.add(
                        file
                    );
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        Path best = null;
        int bestScore = -1;
        for (Path file : new TreeSet<>(configs)) {
            Map<String, Object> config;
            try {
                config = Json.read(file);
            } catch (IOException | IllegalArgumentException unreadable) {
                continue;
            }
            if (Json.text(config, "package", "").isEmpty()) continue;
            int score = (Boolean.FALSE.equals(config.get("required")) ? 0 : 2) + (config.containsKey("mixins") ? 1 : 0);
            if (score > bestScore) {
                best = file;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * A reported source file: an absolute path (C:\... or /...) or a relative one starting with a plain name. Gradle's
     * failure summary repeats javac's errors with the previous line glued on ("^C:\...", "^Note: ... outputC:\...",
     * "(actual and formal argument lists differ in length)C:\..."); those repeats do not start like a path, so they are
     * skipped rather than read as one. The errors themselves were already read where javac printed them.
     */
    static final String FILE =
        "((?:[A-Za-z]:[\\\\/]|/)[^:\\r\\n]*?\\.(?:java|kt)|[\\w.-]+(?:[\\\\/][\\w .-]+)*\\.(?:java|kt))";
    static final Pattern ERROR = Pattern.compile("(?m)^\\s*" + FILE + ":(\\d+): error: ([^\\r\\n]+)");

    /**
     * Every distinct compiler error left, each with the update that changed the name it uses when the
     * route's map history knows it. Nothing is dropped: an error the history cannot place still appears,
     * with changedIn empty and the reason.
     */
    List<Object> namedErrors(Path log, Map<String, Object> mapInfo) throws IOException {
        String text = logText(log);
        Map<String, List<Map<String, Object>>> history = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            history.computeIfAbsent(simpleName(String.valueOf(event.get("name"))), k -> new ArrayList<>()).add(event);
            Object to = event.get("to");
            if (to != null) {
                String last = String.valueOf(to).replaceAll("\\(.*", "");
                String name = simpleName(
                    last.contains(".") && !"class".equals(event.get("kind"))
                        ? last.substring(last.lastIndexOf('.') + 1)
                        : last
                );
                if (!name.equals(simpleName(String.valueOf(event.get("name"))))) history
                    .computeIfAbsent(name, k -> new ArrayList<>())
                    .add(event);
            }
        }
        List<Object> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher reported = ERROR.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (reported.find()) {
            spans.add(new int[] { reported.start(), reported.end() });
            heads.add(new String[] { reported.group(1), reported.group(2), reported.group(3) });
        }
        for (int i = 0; i < spans.size(); i++) {
            String[] head = heads.get(i);
            if (!seen.add(head[0] + ":" + head[1] + ":" + head[2])) continue;
            String block = text.substring(
                spans.get(i)[1],
                i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(text.length(), spans.get(i)[1] + 2000)
            );
            Map<String, Object> entry = Json.map(
                "file",
                head[0],
                "line",
                Integer.parseInt(head[1]),
                "message",
                head[2]
            );
            // Placing an error is a report, never a reason to lose it: a failed lookup keeps the error listed.
            if (route != null) try {
                explain(entry, head[2], block, history);
            } catch (RuntimeException | IOException failure) {
                entry.put("changedIn", List.of());
                entry.put("why", "Could not look this name up in the route history: " + failure.getMessage());
            }
            list.add(entry);
        }
        return list;
    }

    static String simpleName(String name) {
        String s = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return s.contains("$") ? s.substring(s.lastIndexOf('$') + 1) : s;
    }

    void explain(
        Map<String, Object> entry,
        String message,
        String block,
        Map<String, List<Map<String, Object>>> history
    ) throws IOException {
        String symbol = null, kind = null, owner = null;
        Matcher m = Pattern.compile(
            "symbol:\\s+(class|method|variable|constructor|static|interface|enum|record)\\s+(\\w+)"
        ).matcher(block);
        if (m.find()) {
            kind = m.group(1);
            symbol = m.group(2);
        }
        Matcher where = Pattern.compile(
            "location:\\s+(?:(?:class|interface|enum|record)\\s+([\\w.$]+)|variable \\w+ of type ([\\w.$]+))"
        ).matcher(block);
        if (where.find()) owner = simpleName(
            (where.group(1) != null ? where.group(1) : where.group(2)).replaceAll("<.*", "")
        );
        Matcher applied = Pattern.compile(
            "(?:method|constructor) (\\w+) in (?:class|interface|enum|record) ([\\w.$]+)"
        ).matcher(message);
        Matcher suitable = Pattern.compile("no suitable (?:method|constructor) found for (\\w+)\\(").matcher(message);
        Matcher access = Pattern.compile(
            "(\\w+)(?:\\([^)]*\\))? has (?:private|protected) access in ([\\w.$]+)"
        ).matcher(message);
        Matcher pkg = Pattern.compile("package ([\\w.]+) does not exist").matcher(message);
        if (symbol == null && applied.find()) {
            kind = "method";
            symbol = applied.group(1);
            owner = simpleName(applied.group(2).replaceAll("<.*", ""));
        } else if (symbol == null && suitable.find()) {
            kind = "method";
            symbol = suitable.group(1);
        } else if (symbol == null && access.find()) {
            kind = "member";
            symbol = access.group(1);
            owner = simpleName(access.group(2));
        } else if (symbol == null && pkg.find()) {
            Matcher imported = Pattern.compile(
                "(?:import\\s+(?:static\\s+)?)?" + Pattern.quote(pkg.group(1)) + "\\.(\\w+)"
            ).matcher(block);
            kind = "class";
            symbol = imported.find() ? imported.group(1) : simpleName(pkg.group(1));
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
            Matcher declared = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(
                around.replaceAll("@\\w+(\\([^)]*\\))?", "")
            );
            Matcher parent = Pattern.compile("\\b(?:extends|implements)\\s+([\\w.]+)").matcher(
                String.join("\n", lines)
            );
            if (declared.find()) {
                kind = "method";
                symbol = declared.group(1);
                owner = parent.find() ? simpleName(parent.group(1)) : null;
            }
        }
        Matcher extendsClause = Pattern.compile("\\bextends\\s+([\\w.]+)").matcher(String.join("\n", lines));
        String parent = extendsClause.find() ? simpleName(extendsClause.group(1)) : null;
        String importedAs = null;
        if (symbol != null) for (String name : imports) if (name.endsWith("." + symbol)) importedAs = name;
        if (symbol != null) {
            entry.put("symbol", symbol);
            if (owner != null) entry.put("owner", owner);
        }
        List<Object> changed = new ArrayList<>();
        if (symbol != null) {
            List<Map<String, Object>> candidates = new ArrayList<>(), exact = new ArrayList<>();
            for (var event : history.getOrDefault(symbol, List.of())) {
                String eventKind = String.valueOf(event.get("kind"));
                boolean fits =
                    switch (kind == null ? "" : kind) {
                        case "class", "interface", "enum", "record" -> eventKind.equals("class");
                        case "method", "constructor" -> eventKind.equals("method");
                        case "variable" -> !eventKind.equals("method"); // a field, or a class used as a qualifier
                        default -> true; // a static import, an access error, or no symbol kind
                    };
                if (!fits) continue;
                candidates.add(event);
                String eventOwner = event.get("owner") == null ? null : simpleName(String.valueOf(event.get("owner")));
                String wasOwner = event.get("was") == null
                    ? null
                    : simpleName(String.valueOf(event.get("was")).replaceAll("\\(.*", "").replaceAll("\\.\\w+$", ""));
                if (owner != null && (owner.equals(eventOwner) || owner.equals(wasOwner))) exact.add(event);
                else if (
                    importedAs != null &&
                    eventKind.equals("class") &&
                    importedAs.equals(String.valueOf(event.get("name")).replace('$', '.'))
                ) exact.add(event);
                // Inherited from the class this file extends: its members, or a nested class used unqualified.
                else if (
                    importedAs == null &&
                    parent != null &&
                    (eventKind.equals("class")
                            ? String.valueOf(event.get("name")).endsWith("." + parent + "$" + symbol)
                            : parent.equals(eventOwner))
                ) exact.add(event);
            }
            // An imported class names its package; another class with the same simple name is not it.
            if (importedAs != null && exact.isEmpty()) candidates.removeIf(event -> "class".equals(event.get("kind")));
            List<String> decisive = List.of("removed", "signature", "moved", "renamed");
            exact.sort(Comparator.comparingInt(event -> decisive.indexOf(String.valueOf(event.get("what")))));
            // Without an owner match, a 26.x step file that names this owner beats a same-named member elsewhere.
            if (exact.isEmpty() && owner != null) changed.addAll(byName(symbol, owner));
            List<Map<String, Object>> pick = !exact.isEmpty() ? exact : changed.isEmpty() ? candidates : List.of();
            String match = !exact.isEmpty()
                ? "owner"
                : "name only: no change on " +
                (owner == null ? "a known owner" : owner) +
                "; the same name changed on another class, which may be a supertype";
            for (var event : pick.subList(0, Math.min(3, pick.size()))) {
                String step = String.valueOf(event.get("step"));
                Route.Hop hop = route.hopOf(step);
                String what = String.valueOf(event.get("what")), name = event.get("owner") == null
                    ? String.valueOf(event.get("name"))
                    : event.get("owner") + "." + event.get("name");
                String change =
                    event.get("kind") +
                    " " +
                    name +
                    " " +
                    what +
                    ("removed".equals(what) ? "" : " to " + event.get("to")) +
                    (event.get("was") != null &&
                            !String.valueOf(event.get("was")).startsWith(name) &&
                            !"signature".equals(what)
                            ? " (then named " + event.get("was") + ")"
                            : "");
                changed.add(
                    Json.map(
                        "step",
                        step,
                        "hop",
                        hop == null ? null : hop.from() + " -> " + hop.to(),
                        "change",
                        change,
                        "linked",
                        route.linkOf(step),
                        "match",
                        match
                    )
                );
            }
            if (exact.isEmpty() && pick == candidates && candidates.size() > 3) entry.put(
                "ambiguous",
                candidates.size() + " changes share this name; the first three are listed"
            );
            if (changed.isEmpty() && owner == null) changed.addAll(byName(symbol, null));
        }
        entry.put("changedIn", changed);
        if (changed.isEmpty()) entry.put(
            "why",
            symbol == null
                ? "The compiler names no symbol here; this is a type or behaviour change the map cannot place on one update."
                : "No update on this route renamed, moved or removed " +
                symbol +
                " in the mapping history; it is an API change the compiler reports here (or a name from a mod dependency)."
        );
    }

    /** Updates into 26.x have no shared ids; look the name up in the change index's own step files when present. */
    List<Object> byName(String symbol, String owner) throws IOException {
        List<Object> found = new ArrayList<>();
        for (String step : route.steps()) {
            if (!"by-name".equals(route.linkOf(step))) continue;
            String[] p = step.split(" -> ");
            boolean forward = Versions.compare(p[0], p[1]) < 0;
            Path file = build.harness.resolve(
                "coverage/changes/" + (forward ? p[0] + "__" + p[1] : p[1] + "__" + p[0]) + ".json"
            );
            if (!Files.isRegularFile(file)) continue;
            var data = Json.read(file);
            String removed = forward ? "Removed" : "Added";
            for (Object c : listOf(data.get("classes" + removed))) if (
                simpleName(String.valueOf(c)).equals(symbol)
            ) found.add(
                Json.map(
                    "step",
                    step,
                    "hop",
                    hopName(step),
                    "change",
                    "class " + c + " removed (matched by name)",
                    "linked",
                    "by-name",
                    "match",
                    "26.x step file, matched by name (no shared ids)"
                )
            );
            for (var e : Json.object(data.get("members")).entrySet()) {
                if (owner != null && !simpleName(e.getKey()).equals(owner)) continue;
                var members = Json.object(e.getValue());
                for (String section : List.of(
                    "methods" + removed,
                    "fields" + removed,
                    "methodsChanged",
                    "fieldsChanged",
                    "methodsRenamed",
                    "fieldsRenamed"
                )) for (Object member : listOf(members.get(section))) {
                    String sig = String.valueOf(member).replaceAll("^(?:\\w+ )*", ""), side = forward
                        ? sig
                        : sig.contains(" -> ") ? sig.substring(sig.indexOf(" -> ") + 4) : sig;
                    if (side.startsWith(symbol + "(") || side.startsWith(symbol + ":")) found.add(
                        Json.map(
                            "step",
                            step,
                            "hop",
                            hopName(step),
                            "change",
                            e.getKey() +
                            "." +
                            member +
                            " (" +
                            section.replaceAll("^(methods|fields)", "").toLowerCase(Locale.ROOT) +
                            ", matched by name)",
                            "linked",
                            "by-name",
                            "match",
                            "26.x step file, matched by name (no shared ids)"
                        )
                    );
                }
            }
            if (found.size() >= 3) break;
        }
        return found.size() > 3 ? new ArrayList<>(found.subList(0, 3)) : found;
    }

    static List<Object> listOf(Object value) {
        return value instanceof List<?> ? Json.array(value) : List.of();
    }

    String hopName(String step) {
        Route.Hop hop = route.hopOf(step);
        return hop == null ? null : hop.from() + " -> " + hop.to();
    }

    /** One record per hop: its updates, what the game changed there (names the source's first release had), rules run and errors placed. */
    Map<String, Object> hopRecords(Map<String, Object> record, Map<String, Object> mapInfo, List<Object> named) {
        Map<String, Map<String, Integer>> changes = new HashMap<>();
        if (mapInfo != null) for (Object item : Json.array(mapInfo.get("history"))) {
            var event = Json.object(item);
            changes
                .computeIfAbsent(String.valueOf(event.get("step")), k -> new TreeMap<>())
                .merge(event.get("kind") + "s " + event.get("what"), 1, Integer::sum);
        }
        for (Object item : Json.array(record.get("hops"))) {
            var hop = Json.object(item);
            Set<String> steps = new LinkedHashSet<>();
            for (Object s : Json.array(hop.get("steps"))) {
                var step = Json.object(s);
                String id = String.valueOf(step.get("step"));
                steps.add(id);
                step.put("changes", changes.getOrDefault(id, Map.of()));
            }
            List<String> rules = new ArrayList<>();
            rulesApplied.forEach((id, covered) -> {
                for (String step : covered) if (steps.contains(step)) {
                    rules.add(id);
                    break;
                }
            });
            int errors = 0;
            for (Object e : named) {
                List<Object> changed = Json.array(Json.object(e).get("changedIn"));
                if (
                    !changed.isEmpty() && steps.contains(String.valueOf(Json.object(changed.get(0)).get("step")))
                ) errors++;
            }
            hop.put("rulesApplied", rules);
            hop.put("errorsPlaced", errors);
        }
        return record;
    }

    void finishTransaction(Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(build.project, transaction, before, List.of());
    }

    static void finishTransaction(ProjectModel project, Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(project, transaction, before, List.of());
    }

    static void finishTransaction(
        ProjectModel project,
        Path transaction,
        Map<String, Object> before,
        List<String> removed
    ) throws Exception {
        Map<String, Object> after = new LinkedHashMap<>();
        StringBuilder patch = new StringBuilder();
        for (String relative : before.keySet()) {
            Path file = project.root.resolve(relative);
            boolean created = before.get(relative) == null && !removed.contains(relative);
            if (created && !Files.isRegularFile(file)) continue;
            if (!Files.isRegularFile(file)) {
                if (!removed.contains(relative)) throw new IOException(
                    "Repair unexpectedly removed " + relative + "; backups: " + transaction
                );
                after.put(relative, null);
                patch.append("deleted ").append(relative).append('\n');
                continue;
            }
            String hash = Fingerprints.bytes(Files.readAllBytes(file));
            if (!hash.equals(before.get(relative))) {
                after.put(relative, hash);
                String old = created ? "" : Files.readString(transaction.resolve("before").resolve(relative)), now =
                    Files.readString(file);
                patch
                    .append("diff --git a/")
                    .append(relative)
                    .append(" b/")
                    .append(relative)
                    .append("\n--- a/")
                    .append(relative)
                    .append("\n+++ b/")
                    .append(relative)
                    .append('\n');
                List<String> a = old.lines().toList(), b = now.lines().toList();
                patch.append("@@ -1,").append(a.size()).append(" +1,").append(b.size()).append(" @@\n");
                a.forEach(line -> patch.append('-').append(line).append('\n'));
                b.forEach(line -> patch.append('+').append(line).append('\n'));
            }
        }
        Files.writeString(transaction.resolve("changes.patch"), patch);
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                before,
                "after",
                after,
                "removed",
                removed,
                "status",
                "complete",
                "patch",
                transaction.resolve("changes.patch").toString()
            )
        );
    }

    Map<String, Object> undo(String id) throws Exception {
        return undo(build.project, id);
    }

    /** This project's port transactions, newest first. */
    static List<Path> transactions(ProjectModel project) throws IOException {
        return transactions(project, "ports");
    }

    /** The transactions recorded under one folder of the project's state (ports, or converts for convert), newest first. */
    static List<Path> transactions(ProjectModel project, String folder) throws IOException {
        Path ports = project.state().resolve(folder);
        if (!Files.isDirectory(ports)) return List.of();
        try (var list = Files.list(ports)) {
            return list
                .filter(p -> Files.isRegularFile(p.resolve("transaction.json")))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        }
    }

    /**
     * A port that stopped part-way (killed, closed terminal, power cut) and was never undone, as a
     * sentence naming the files it left changed; null when there is none. A command holds the
     * project lock while it runs, so an "in-progress" record seen under the lock is a dead one.
     */
    static String interruptedText(ProjectModel project) throws Exception {
        for (Path transaction : transactions(project)) {
            Map<String, Object> state;
            try {
                state = Json.read(transaction.resolve("transaction.json"));
            } catch (IOException | IllegalArgumentException unreadable) {
                continue;
            }
            if (!"in-progress".equals(state.get("status"))) continue;
            // Only the files the port planned to edit or remove count here: a source edited by hand
            // since then must not make an old record look like an unfinished port forever.
            Set<String> planned = new HashSet<>();
            if (state.get("plan") instanceof Map<?, ?> plan) for (String key : List.of(
                "edits",
                "removals"
            )) planned.addAll(ProjectModel.strings(plan.get(key)));
            List<String> changed = changedSince(project, state).stream().filter(planned::contains).toList();
            if (changed.isEmpty()) continue;
            return (
                "A port of this project stopped part-way (" +
                transaction
                    .getFileName()
                    .toString()
                    .replaceFirst("T(\\d\\d)-(\\d\\d)-(\\d\\d).*$", " at $1:$2:$3 UTC") +
                ") and left " +
                changed.size() +
                (changed.size() == 1 ? " file" : " files") +
                " changed: " +
                String.join(", ", changed) +
                "."
            );
        }
        return null;
    }

    /** Backed-up files that now differ from their backup, or are gone. */
    static List<String> changedSince(ProjectModel project, Map<String, Object> state) throws Exception {
        List<String> changed = new ArrayList<>();
        for (var entry : Json.object(state.get("before")).entrySet()) {
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(project.root)) continue;
            // A null hash is a file the port created: changed once it exists.
            if (
                entry.getValue() == null
                    ? Files.exists(file)
                    : !Files.isRegularFile(file) ||
                    !Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())
            ) changed.add(entry.getKey());
        }
        return changed;
    }

    static Map<String, Object> undo(ProjectModel project, String id) throws Exception {
        return undo(project, id, "ports");
    }

    /** Undoes one recorded transaction under the folder: a port (ports) or a loader conversion (converts). */
    static Map<String, Object> undo(ProjectModel project, String id, String folder) throws Exception {
        String kind = folder.equals("ports") ? "port" : "conversion";
        if (id.isBlank() || id.equals("latest")) {
            // --undo with no transaction: the newest port that has not been undone.
            Path newest = null;
            for (Path candidate : transactions(project, folder)) {
                try {
                    if (!"undone".equals(Json.read(candidate.resolve("transaction.json")).get("status"))) {
                        newest = candidate;
                        break;
                    }
                } catch (IOException | IllegalArgumentException unreadable) {}
            }
            if (newest == null) throw new IOException(
                "There is no " + kind + " of this project to undo" +
                (transactions(project, folder).isEmpty() ? "." : ": every recorded " + kind + " was already undone.")
            );
            id = newest.toString();
        }
        // The port's own id (the name its result and receipt carry) names its transaction as well as the full path does.
        Path transaction = id.matches("[\\w.-]+") && Files.isDirectory(project.state().resolve(folder).resolve(id))
            ? project.state().resolve(folder).resolve(id)
            : Path.of(id).toAbsolutePath().normalize();
        if (!transaction.startsWith(project.state().resolve(folder))) throw new IOException(
            "Undo must name this project's recorded " + kind + " transaction: its id or its folder under " +
            project.state().resolve(folder) +
            "."
        );
        var state = Json.read(transaction.resolve("transaction.json"));
        if (!project.root.toString().equals(state.get("root"))) throw new IOException(
            "Undo belongs to a different project."
        );
        String status = Json.text(state, "status", "");
        if (status.equals("undone")) throw new IOException(
            "This port was already undone; nothing to restore. Transaction: " + transaction
        );
        if (status.equals("in-progress")) return undoInterrupted(project, transaction, state);
        if (!status.equals("complete")) throw new IOException(interrupted(project, transaction, state));
        var after = Json.object(state.get("after"));
        List<String> removed = ProjectModel.strings(state.get("removed"));
        for (String relative : removed) {
            Path file = project.root.resolve(relative).normalize();
            if (!file.startsWith(project.root)) throw new IOException("Undo entry escapes the project: " + relative);
            if (Files.exists(file)) throw new IOException(
                "Refusing undo: " + relative + " was recreated after porting. Your later edits have been preserved."
            );
        }
        for (var entry : after.entrySet()) {
            if (removed.contains(entry.getKey())) continue;
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (
                !file.startsWith(project.root) ||
                !Files.isRegularFile(file) ||
                !Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())
            ) throw new IOException(
                "Refusing undo: " + entry.getKey() + " changed after porting. Your later edits have been preserved."
            );
        }
        var original = Json.object(state.get("before"));
        for (String relative : after.keySet()) {
            if (original.get(relative) == null && !removed.contains(relative)) deleteWithEmptiedFolders(
                project.root,
                project.root.resolve(relative)
            );
            else {
                // A removed file may have taken its emptied folder with it.
                Files.createDirectories(project.root.resolve(relative).getParent());
                Files.copy(
                    transaction.resolve("before").resolve(relative),
                    project.root.resolve(relative),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        }
        state.put("status", "undone");
        Json.save(transaction.resolve("transaction.json"), state);
        return Json.map(
            "ok",
            true,
            "restoredFiles",
            after.size(),
            "transaction",
            transaction.toString(),
            "details",
            after.isEmpty()
                ? "The " + kind + " changed no files, so there was nothing to restore."
                : "Restored " + after.size() + " file(s) to their state before the " + kind + "."
        );
    }

    /**
     * Deletes a file undo found the port or conversion made, and every folder that deleting it left empty, up to (not
     * including) the project root: a folder that held only made files was made with them.
     */
    static void deleteWithEmptiedFolders(Path root, Path file) throws IOException {
        Files.delete(file);
        for (Path parent = file.getParent(); parent != null && parent.startsWith(root) && !parent.equals(root); parent = parent.getParent()) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            }
            Files.delete(parent);
        }
    }

    /**
     * A port that stopped part-way recorded its backups before the first edit but never the files'
     * state after. Every file that differs from its backup is put back, and its current copy is kept
     * beside the backups first, so an edit made after the port stopped is never lost.
     */
    static Map<String, Object> undoInterrupted(ProjectModel project, Path transaction, Map<String, Object> state)
        throws Exception {
        List<String> changed = changedSince(project, state);
        var original = Json.object(state.get("before"));
        Path kept = transaction.resolve("current-before-undo");
        for (String relative : changed) {
            Path file = project.root.resolve(relative).normalize(), backup = transaction
                .resolve("before")
                .resolve(relative);
            if (original.get(relative) != null && !Files.isRegularFile(backup)) throw new IOException(
                "Refusing undo: no backup of " +
                relative +
                " in " +
                transaction.resolve("before") +
                ". Nothing was restored."
            );
            if (Files.isRegularFile(file)) {
                Files.createDirectories(kept.resolve(relative).getParent());
                Files.copy(file, kept.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String relative : changed) {
            Path file = project.root.resolve(relative).normalize();
            if (original.get(relative) == null) {
                deleteWithEmptiedFolders(project.root, file);
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.copy(transaction.resolve("before").resolve(relative), file, StandardCopyOption.REPLACE_EXISTING);
        }
        state.put("status", "undone");
        state.put("undoneAfterInterruption", changed);
        Json.save(transaction.resolve("transaction.json"), state);
        return Json.map(
            "ok",
            true,
            "restoredFiles",
            changed.size(),
            "transaction",
            transaction.toString(),
            "interrupted",
            true,
            "details",
            changed.isEmpty()
                ? "The port stopped before changing any file, so there was nothing to restore."
                : "The port had stopped part-way. Restored " +
                changed.size() +
                (changed.size() == 1 ? " file" : " files") +
                " to their state before it: " +
                String.join(", ", changed) +
                ".\nTheir versions from just before this undo are kept in " +
                kept +
                ", in case you edited them after the port stopped."
        );
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
            if (entry.getValue() == null) {
                if (Files.exists(file)) changed.add(entry.getKey());
            } else if (!Files.isRegularFile(file)) missing.add(entry.getKey());
            else if (!Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())) changed.add(
                entry.getKey()
            );
        }
        StringBuilder out = new StringBuilder(
            "Refusing undo: this port did not finish (status " +
            Json.text(state, "status", "unknown") +
            "), so it never recorded which files it changed and an undo could overwrite edits made since. Nothing was restored."
        );
        if (changed.isEmpty() && missing.isEmpty()) out.append(
            "\nEvery backed-up file still matches its backup, so the port changed nothing that needs restoring."
        );
        else {
            if (!changed.isEmpty()) out.append("\nDiffer from their backup: ").append(String.join(", ", changed));
            if (!missing.isEmpty()) out.append("\nMissing now: ").append(String.join(", ", missing));
            out
                .append("\nTo restore by hand, copy those files back from ")
                .append(backups)
                .append(" (same relative paths), after checking you made no edits to them since.");
        }
        return out.toString();
    }
}
