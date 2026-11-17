import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

/** Identify exact resolved binaries; when discovery cannot finish, apply a verified explicit selection. */
final class Dependencies {

    /**
     * One user choice for a dependency that automatic identity could not resolve.
     * A selection either names a target coordinate plus how its target-game support was checked,
     * or disables an optional dependency after proving it has no release for the chosen version.
     */
    record Selection(String source, String target, String repository, String modrinth, boolean disable, String reason) {
        boolean matches(String group, String name, String version) {
            String[] parts = coordinate(source);
            if (!parts[0].equals(group) || !parts[1].equals(name)) return false;
            return parts.length < 3 || parts[2].equals(version);
        }
    }

    static String displayLoader(String loader) {
        return loader.equals("neoforge") ? "NeoForge" : loader.equals("forge") ? "Forge" : "Fabric";
    }

    static String[] coordinate(String value) {
        String[] parts = value.split(":", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException(
            "Dependency coordinates need group:name[:version]; got " + value
        );
        return parts;
    }

    /** Read an explicit selection file; a malformed entry fails before anything is edited. */
    static List<Selection> selections(Path file) throws IOException {
        if (file == null) return List.of();
        if (!Files.isRegularFile(file)) throw new IOException("Dependency selection file does not exist: " + file);
        var root = Json.object(Json.parse(Files.readString(file)));
        List<Selection> list = new ArrayList<>();
        for (Object item : Json.array(root.getOrDefault("selections", List.of()))) {
            var entry = Json.object(item);
            String source = Json.text(entry, "source", "").trim();
            coordinate(source);
            boolean disable = Boolean.TRUE.equals(entry.get("disable"));
            String target = Json.text(entry, "target", "").trim(), modrinth = Json.text(entry, "modrinth", "").trim();
            String repository = Json.text(entry, "repository", "").trim(), reason = Json.text(
                entry,
                "reason",
                ""
            ).trim();
            if (disable) {
                if (!target.isEmpty()) throw new IOException(
                    "Selection for " + source + " cannot both target and disable"
                );
                if (reason.isEmpty()) throw new IOException(
                    "Disabling " + source + " needs a reason; the removed feature is reported and undone with the port"
                );
                if (modrinth.isEmpty()) throw new IOException(
                    "Disabling " + source + " needs modrinth so missing target support can be checked"
                );
            } else {
                if (coordinate(target).length != 3) throw new IOException(
                    "Selection target needs group:name:version; got " + target
                );
                if (modrinth.isEmpty() && repository.isEmpty()) throw new IOException(
                    "Selection for " +
                    source +
                    " needs either modrinth (published project) or a repository so target-game support can be verified"
                );
            }
            list.add(new Selection(source, target, repository, modrinth, disable, reason));
        }
        return list;
    }

    static Selection select(List<Selection> selections, String group, String name, String version) {
        for (Selection selection : selections) if (selection.matches(group, name, version)) return selection;
        return null;
    }

    /** Every non-loader dependency becomes resolved evidence, a blocker, or an explicit verified change. */
    static void resolve(Target.Setup setup) throws Exception {
        resolve(setup, "fabric");
    }

    static void resolve(Target.Setup setup, String loader) throws Exception {
        Set<String> seen = new HashSet<>();
        for (Object item : Json.array(setup.project.get("dependencies"))) {
            var dep = Json.object(item);
            String group = Json.text(dep, "group", ""), name = Json.text(dep, "name", ""), old = Json.text(
                dep,
                "version",
                ""
            );
            if (
                group.startsWith("net.fabricmc") ||
                group.startsWith("loom_mappings_") || // Loom's own remapped copy of a dependency listed by its real coordinate too
                group.matches("[a-z0-9_]+_v[0-9]+\\..*") ||
                group.equals("com.mojang") ||
                old.isEmpty() ||
                !seen.add(group + ":" + name + ":" + old)
            ) continue;
            Path binary = artifact(setup.project, group, name, old);
            if (binary == null) {
                // Target lookup reads Fabric metadata only. A NeoForge or Forge mod jar is still a mod the
                // port depends on, so it is listed as unchecked instead of disappearing from the result.
                Path mod = forgeModJar(setup.project, group, name, old);
                if (gameVersioned(setup, group, name, old, mod, Json.text(dep, "configuration", ""))) continue;
                // A NeoForge mod jar is looked up like a Fabric one (its file's hash on Modrinth): left on the old game, the
                // loader refuses to start any mod (Corpse's OpenHUD 1.3.0+1.21.1 asked for Minecraft [1.21.1] exactly).
                if (mod != null && loader.equals("neoforge") && setup.build != null) binary = mod;
                else if (mod != null) setup.unchecked.add(
                    Json.map(
                        "artifact",
                        group + ":" + name,
                        "version",
                        old,
                        "configuration",
                        Json.text(dep, "configuration", ""),
                        "file",
                        mod.toString(),
                        "reason",
                        "a " +
                        (loader.equals("neoforge") ? "NeoForge" : "Forge") +
                        " mod; its " +
                        setup.to +
                        " release was not looked up, so the build decides whether " +
                        old +
                        " still works"
                    )
                );
                if (binary == null) continue;
            }
            Selection selection = select(setup.selections, group, name, old);
            // A library released per game version (in its name or its version) is looked up by that before anything else.
            if (selection == null && !forgeMod(setup.project, group, name, old, binary) && gameVersioned(setup, group, name, old, binary, Json.text(dep, "configuration", ""))) continue;
            try {
                if (selection != null && selection.disable()) disable(setup, group, name, old, binary, selection);
                else if (selection != null) choose(setup, group, name, old, binary, selection);
                else if (group.equals(CURSE_MAVEN)) curseMaven(setup, group, name, old, binary, Json.text(dep, "configuration", ""), loader);
                else try {
                    discover(setup, group, name, old, binary, Json.text(dep, "configuration", ""), loader);
                } catch (IOException unpublished) {
                    if (unpublished instanceof Target.LookupFailure || !loader.equals("fabric")) throw unpublished;
                    if (!mavenRelease(setup, group, name, old, binary)) {
                        // A Fabric library whose own metadata names no Minecraft version (MixinExtras) runs on any game: it stays.
                        if (!gameIndependent(binary)) throw unpublished;
                        setup.applied.add(
                            Json.map(
                                "artifact",
                                group + ":" + name,
                                "from",
                                old,
                                "to",
                                old,
                                "kept",
                                true,
                                "evidence",
                                "its fabric.mod.json depends on no Minecraft version, so it is not tied to the game it was built beside"
                            )
                        );
                    }
                }
            } catch (IOException failure) {
                String configuration = Json.text(dep, "configuration", "");
                // A compile-only integration is never packaged or loaded with the mod, and authors often
                // already compile against an older release of it. Keep it and let the compiler decide.
                if (selection == null && compileOnly(configuration) && !(failure instanceof Target.LookupFailure)) {
                    setup.applied.add(
                        Json.map(
                            "artifact",
                            group + ":" + name,
                            "from",
                            old,
                            "to",
                            old,
                            "kept",
                            true,
                            "configuration",
                            configuration,
                            "reason",
                            failure.getMessage() +
                            "; kept at " +
                            old +
                            " because a " +
                            configuration +
                            " dependency is not shipped or loaded with the mod. The build decides whether the integration still compiles."
                        )
                    );
                    continue;
                }
                setup.blockers.add(
                    blocker(
                        group,
                        name,
                        old,
                        binary,
                        String.valueOf(failure.getMessage() == null ? failure : failure.getMessage()),
                        configuration,
                        setup.to
                    )
                );
            }
        }
        // The policy for a dependency with no target release: an optional one is disabled, a required one blocks the port.
        for (var blocker : new ArrayList<>(setup.blockers)) if (disableOptional(setup, blocker)) setup.blockers.remove(blocker);
        // What only arrives with a disabled dependency (JEI's core, lib and gui with its API) goes with it: same group and
        // version, and declared in no build script.
        for (var blocker : new ArrayList<>(setup.blockers)) {
            String artifact = Json.text(blocker, "artifact", ""), version = Json.text(blocker, "version", "");
            String group = artifact.contains(":") ? artifact.substring(0, artifact.indexOf(':')) : artifact;
            Map<String, Object> with = null;
            for (var done : setup.applied) {
                boolean sameRelease = Json.text(done, "artifact", "").startsWith(group + ":") && version.equals(Json.text(done, "from", ""));
                if (Boolean.TRUE.equals(done.get("disabled")) && sameRelease) with = done;
            }
            if (with == null || declaredAnywhere(setup, artifact)) continue;
            setup.blockers.remove(blocker);
            setup.applied.add(Json.map("artifact", artifact, "from", version, "disabled", true, "reason", "it only arrives with " + Json.text(with, "artifact", "") + ", which was disabled"));
        }
        for (var blocker : new ArrayList<>(setup.blockers)) if (portLibrary(setup, blocker, loader)) setup.blockers.remove(blocker);
    }

    /** Whether any build script of the project declares group:name, literally or through properties. */
    static boolean declaredAnywhere(Target.Setup setup, String artifact) {
        if (setup.build == null) return true;
        try (var scripts = Files.walk(setup.build.project.root, 3)) {
            for (Path script : scripts.filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?") && !p.toString().contains(File.separator + "build" + File.separator)).toList()) {
                if (!declarations(setup, script, artifact).isEmpty()) return true;
            }
        } catch (IOException unreadable) {
            return true;
        }
        return false;
    }

    /** The game version the running port starts from; set by port --fix, which may port a library it needs. */
    static String portFrom = null;

    /** Whether binary is the NeoForge or Forge mod jar of group:name:old (already looked up by its game version). */
    static boolean forgeMod(Map<String, Object> project, String group, String name, String old, Path binary) {
        return binary != null && binary.equals(forgeModJar(project, group, name, old)) && artifact(project, group, name, old) == null;
    }

    /** The simple names of a jar's classes (nested ones by their own name). */
    static Set<String> simpleNames(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var e : Collections.list(zip.entries())) {
                String n = e.getName();
                if (n.endsWith(".class")) names.add(n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('$')) + 1, n.length() - 6));
            }
        }
        return names;
    }

    /** Whether a mod source outside the removed set names this source's class. */
    static boolean calledElsewhere(Target.Setup setup, Path source, Set<Path> removed) throws IOException {
        String cls = source.getFileName().toString().replaceFirst("\\.java$", "");
        Pattern use = Pattern.compile("\\b" + Pattern.quote(cls) + "\\b");
        for (Path other : Port.sources(setup.build.project)) {
            if (removed.contains(other) || !other.toString().endsWith(".java")) continue;
            if (use.matcher(Files.readString(other)).find()) return true;
        }
        return false;
    }

    /**
     * The class as it is when its optional mod is absent: same package and name, every static or instance method whose
     * signature names nothing of the library, each returning false, 0 or null (a void one does nothing), and nothing
     * else. Null when the class itself extends or implements something, or has no such method.
     */
    static String absentStub(String text, Set<String> library) {
        Matcher head = Pattern.compile("(?m)^((?:public\\s+|final\\s+|abstract\\s+)*)class\\s+(\\w+)\\s*\\{").matcher(text);
        Matcher pkg = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;").matcher(text);
        if (!head.find() || !pkg.find()) return null;
        int open = text.indexOf('{', head.start()), close = Port.closingBrace(text, open);
        if (close < 0) return null;
        StringBuilder out = new StringBuilder(pkg.group());
        out.append("\n\n/** Written by mmt port: the optional mod this integrated with has no release for the target, so it is never present. */\n");
        out.append(head.group(1)).append("class ").append(head.group(2)).append(" {\n");
        Matcher method = Pattern.compile(
            "(?m)^[ \\t]+((?:public|protected|private|static|final|synchronized|\\s)+)([\\w<>\\[\\]?, .]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\s*\\{"
        ).matcher(text);
        int kept = 0, at = open + 1;
        while (method.find(at) && method.start() < close) {
            int bodyClose = Port.closingBrace(text, method.end() - 1);
            if (bodyClose < 0) break;
            at = bodyClose + 1;
            String type = method.group(2).strip(), signature = type + " " + method.group(4);
            boolean namesLibrary = false;
            for (Matcher word = Pattern.compile("\\b[A-Z]\\w*").matcher(signature); word.find();) namesLibrary |= library.contains(word.group());
            if (namesLibrary || type.equals("class") || type.equals("new")) continue;
            String value = switch (type) {
                case "void" -> "";
                case "boolean" -> "return false;";
                case "int", "long", "short", "byte", "float", "double" -> "return 0;";
                case "char" -> "return '\\0';";
                default -> "return null;";
            };
            out.append("    ").append(method.group(1).strip()).append(' ').append(type).append(' ').append(method.group(3));
            out.append('(').append(method.group(4).strip()).append(") {").append(value.isEmpty() ? "" : " " + value + " ").append("}\n\n");
            kept++;
        }
        if (kept == 0) return null;
        // Types the kept signatures use (a Screen, a ResourceKey) keep their imports; the library's go.
        StringBuilder imports = new StringBuilder();
        for (Matcher imp = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)\\s*;").matcher(text); imp.find();) {
            String simple = imp.group(1).substring(imp.group(1).lastIndexOf('.') + 1);
            boolean used = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(out).find();
            if (!library.contains(simple) && used) imports.append(imp.group()).append('\n');
        }
        String body = out.toString().replaceFirst("\\n\\n$", "\n") + "}\n";
        return body.replaceFirst("(?m)^(package\\s+[\\w.]+\\s*;)\\n", "$1\n\n" + Matcher.quoteReplacement(imports.toString()));
    }

    /**
     * A required dependency with no target release is ported from its own source (LibraryPort) when the running port may
     * do so, and the build then uses that port from the local repository. When it cannot be, the blocker says why.
     */
    static boolean portLibrary(Target.Setup setup, Map<String, Object> blocker, String loader) {
        String artifact = Json.text(blocker, "artifact", ""), old = Json.text(blocker, "version", ""), file = Json.text(blocker, "file", "");
        if (portFrom == null || setup.build == null || !artifact.contains(":")) return false;
        String group = artifact.substring(0, artifact.indexOf(':')), name = artifact.substring(artifact.indexOf(':') + 1);
        // A NeoForge mod dependency's blocker carries no file; its jar is in Gradle's cache under the old version.
        if (file.isEmpty()) {
            Path cached = cachedJar(artifact, old);
            if (cached == null) return false;
            file = cached.toString();
        }
        try {
            var receipt = LibraryPort.ensure(setup, group, name, old, Path.of(file), portFrom, loader);
            apply(setup, group, name, old, group, name, LibraryPort.version(old, setup.to), Path.of(file), receipt);
            return true;
        } catch (Exception failure) {
            blocker.put("reason", Json.text(blocker, "reason", "") + "; mmt could not port it from source: " + failure.getMessage());
            return false;
        }
    }

    /**
     * A dependency with no release for the target is disabled when the mod itself says it can run without it: it is run-time
     * only (never compiled against, never shipped), or its jar's mod id is not a required dependency in the mod's own
     * fabric.mod.json or neoforge.mods.toml. Its declaration is commented out, the sources that use it go with the port
     * (and come back with its undo), and the receipt says why. A required one, a plain library (no mod id), a declaration
     * the port cannot place, or one the mod's main or client entry class uses stays a blocker naming it. Never a guess.
     */
    static boolean disableOptional(Target.Setup setup, Map<String, Object> blocker) {
        try {
            String artifact = Json.text(blocker, "artifact", ""), version = Json.text(blocker, "version", "");
            String configuration = Json.text(blocker, "configuration", ""), file = Json.text(blocker, "file", "");
            if (artifact.isEmpty() || version.isEmpty() || setup.project.get("buildFile") == null) return false;
            boolean runtimeOnly = configuration.matches("(?i)(mod)?(runtimeOnly|localRuntime)");
            Path jar = file.isEmpty() ? cachedJar(artifact, version) : Path.of(file);
            String modId = jar != null && Files.isRegularFile(jar) ? modId(jar) : null;
            if (modId == null && artifact.matches(".*-api(?:-[\\w.]+)?")) {
                // An API artifact carries no mod metadata; its mod is the same project's artifact without -api, same version.
                Path mod = cachedJar(artifact.replaceFirst("-api(?=-[\\w.]+$|$)", ""), version);
                if (mod != null) modId = modId(mod);
            }
            if (modId == null) modId = groupModId(artifact, version);
            String why;
            if (runtimeOnly) why = "a " + configuration + " dependency, never compiled against or shipped";
            else if (modId == null) return false;
            else if (requiredBy(setup, modId)) return false;
            else why = "the mod's own metadata does not require " + modId;
            Path script = Path.of(setup.project.get("buildFile").toString());
            List<String> blocks = declarations(setup, script, artifact);
            if (blocks.isEmpty()) {
                // A multi-loader build declares it in a sibling module's script (JEI's fabric/build.gradle.kts).
                Path own = script;
                try (var scripts = Files.walk(setup.build.project.root, 3)) {
                    for (Path other : scripts.filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?")
                        && !p.toString().contains(File.separator + "build" + File.separator) && !p.equals(own)).toList()) {
                        List<String> found = declarations(setup, other, artifact);
                        if (!found.isEmpty()) {
                            script = other;
                            blocks = found;
                            break;
                        }
                    }
                }
            }
            if (blocks.isEmpty()) return false;
            Set<Path> importers = runtimeOnly || jar == null ? Set.of() : importers(setup.build.project, jar);
            for (Path source : importers) {
                String text = Files.readString(source);
                if (text.contains("@Mod(") || text.contains("implements ModInitializer") || text.contains("implements ClientModInitializer")) return false;
            }
            for (String block : blocks) setup.replace(script, block, comment(block));
            // An integration class the rest of the mod still calls (Corpse: if (OpenHudIntegration.isLoaded()) ...) stays
            // as a stub saying the optional mod is absent; the others go with the dependency.
            Set<String> library = jar == null ? Set.of() : simpleNames(jar);
            Set<Path> gone = new LinkedHashSet<>();
            for (Path source : importers) {
                String stub = calledElsewhere(setup, source, importers) ? absentStub(Files.readString(source), library) : null;
                if (stub == null) gone.add(source);
                else setup.replace(source, Files.readString(source), stub);
            }
            importers = gone;
            setup.excluded.addAll(importers);
            List<String> removed = importers.isEmpty() ? List.of() : entrypoints(setup, importers);
            setup.applied.add(
                Json.map(
                    "artifact",
                    artifact,
                    "from",
                    version,
                    "disabled",
                    true,
                    "reason",
                    "no " + setup.to + " release (" + Json.text(blocker, "reason", "") + "), and it is optional: " + why,
                    "removedSources",
                    importers.stream().map(p -> setup.build.project.root.relativize(p).toString().replace('\\', '/')).sorted().toList(),
                    "removedEntrypoints",
                    removed
                )
            );
            return true;
        } catch (Exception unplaceable) {
            return false;
        }
    }

    /**
     * The declaration of group:name in the build script: written literally, or with the game version in its name made from a
     * property (jei-${minecraft_version}-neoforge), as the build resolved it.
     */
    static String declaration(Target.Setup setup, Path script, String artifact) throws IOException {
        String text = setup.text(script);
        try {
            return statement(text, artifact + ":", script);
        } catch (IOException notLiteral) {
            Matcher game = Pattern.compile("(?<![0-9.])\\d+\\.\\d+(?:\\.\\d+)?(?![0-9.])").matcher(artifact.substring(artifact.indexOf(':') + 1));
            if (!game.find()) throw notLiteral;
            String name = artifact.substring(artifact.indexOf(':') + 1);
            Matcher written = Pattern.compile(
                Pattern.quote(artifact.substring(0, artifact.indexOf(':') + 1) + name.substring(0, game.start())) +
                "\\$\\{?[\\w.]+\\}?" +
                Pattern.quote(name.substring(game.end()) + ":")
            ).matcher(text);
            if (!written.find()) throw notLiteral;
            return statement(text, written.group(), script);
        }
    }

    /**
     * Every active declaration of the artifact (Cyclic declares Curios compileOnly and localRuntime): one statement, or, when
     * there are several, each single-line one. Empty when a declaration cannot be placed exactly.
     */
    static List<String> declarations(Target.Setup setup, Path script, String artifact) throws IOException {
        try {
            return List.of(declaration(setup, script, artifact));
        } catch (IOException several) {
            if (several.getMessage() == null || !several.getMessage().startsWith("Multiple active")) return written(setup, script, artifact);
            Matcher marker = Pattern.compile("Multiple active (.+) declarations in").matcher(several.getMessage());
            if (!marker.find()) return List.of();
            List<String> lines = new ArrayList<>();
            for (String line : setup.text(script).split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!line.contains(marker.group(1)) || trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                if (line.chars().filter(c -> c == '(').count() != line.chars().filter(c -> c == ')').count() || line.contains("{")) return List.of();
                if (!lines.contains(line)) lines.add(line);
            }
            return lines;
        }
    }

    /**
     * Declarations that name the artifact only once the build's properties are filled in ("mezz.jei:jei-${jei_mc_version}:
     * ${jei_version}" with jei_mc_version=1.21.1-neoforge): each single-line, active one. Empty when there is none, or one
     * spans lines and cannot be placed exactly.
     */
    static List<String> written(Target.Setup setup, Path script, String artifact) throws IOException {
        java.util.Properties properties = new java.util.Properties();
        // The script's own folder first, then up to the build's root (a subproject reads its root's properties too).
        int depth = 0;
        for (Path dir = script.toAbsolutePath().getParent(); dir != null && depth < 3; dir = dir.getParent(), depth++) {
            Path file = dir.resolve("gradle.properties");
            if (Files.isRegularFile(file)) {
                for (var e : ProjectModel.properties(file).entrySet()) properties.putIfAbsent(e.getKey(), e.getValue());
            }
            if (Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) break;
        }
        List<String> lines = new ArrayList<>();
        for (String line : setup.text(script).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || !line.contains("$")) continue;
            Matcher reference = Pattern.compile("\\$\\{?([\\w.]+)\\}?").matcher(line);
            StringBuilder resolved = new StringBuilder();
            while (reference.find()) {
                String value = properties.getProperty(reference.group(1));
                reference.appendReplacement(resolved, Matcher.quoteReplacement(value == null ? reference.group() : value));
            }
            reference.appendTail(resolved);
            if (!resolved.toString().contains(artifact + ":")) continue;
            String plain = line.replaceAll("\\$\\{[\\w.]+\\}", "");
            if (plain.chars().filter(c -> c == '(').count() != plain.chars().filter(c -> c == ')').count() || plain.contains("{")) return List.of();
            if (!lines.contains(line)) lines.add(line);
        }
        return lines;
    }

    /** The jar Gradle downloaded for group:name:version, in its module cache, or null. */
    static Path cachedJar(String artifact, String version) {
        String[] parts = artifact.split(":");
        if (parts.length != 2) return null;
        Path home = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
        Path dir = home.resolve("caches/modules-2/files-2.1").resolve(parts[0]).resolve(parts[1]).resolve(version);
        if (!Files.isDirectory(dir)) return null;
        try (var walk = Files.walk(dir, 2)) {
            return walk.filter(p -> p.getFileName().toString().equals(parts[1] + "-" + version + ".jar")).findFirst().orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The one mod id among the jars a project publishes with the same group and version (JEI's jei-1.21.1-common-api has
     * none; its jei-1.21.1-fabric says jei), or null when they declare none or several.
     */
    static String groupModId(String artifact, String version) {
        String[] parts = artifact.split(":");
        if (parts.length != 2) return null;
        Path home = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
        Path group = home.resolve("caches/modules-2/files-2.1").resolve(parts[0]);
        if (!Files.isDirectory(group)) return null;
        Set<String> ids = new TreeSet<>();
        try (var walk = Files.walk(group, 4)) {
            for (Path jar : walk.filter(p -> p.getFileName().toString().endsWith("-" + version + ".jar") && p.getParent().getParent().getFileName().toString().equals(version)).toList()) {
                String id = modId(jar);
                if (id != null) ids.add(id);
            }
        } catch (IOException unreadable) {
            return null;
        }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    /** The mod id a mod jar declares (fabric.mod.json id, or the first modId of its mods.toml), or null for a plain library. */
    static String modId(Path jar) {
        try (var zip = new ZipFile(jar.toFile())) {
            var fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) return Json.text(Json.object(Json.parse(new String(zip.getInputStream(fabric).readAllBytes(), StandardCharsets.UTF_8))), "id", null);
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                var toml = zip.getEntry(name);
                if (toml == null) continue;
                Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8));
                if (id.find()) return id.group(1);
            }
        } catch (Exception unreadable) {}
        return null;
    }

    /** Whether the project's own fabric.mod.json "depends" or neoforge.mods.toml names modId as a required dependency. */
    static boolean requiredBy(Target.Setup setup, String modId) throws IOException {
        for (Object set : Json.array(setup.project.get("sourceSets"))) for (String resources : ProjectModel.strings(Json.object(set).get("resources"))) {
            Path dir = Path.of(resources);
            Path fabric = dir.resolve("fabric.mod.json");
            if (Files.isRegularFile(fabric) && requiredInFabric(Files.readString(fabric), modId)) return true;
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                Path toml = dir.resolve(name);
                if (Files.isRegularFile(toml) && requiredInToml(Files.readString(toml), modId)) return true;
            }
        }
        Object dir = setup.project.get("directory");
        Path root = dir == null ? setup.build.project.root : Path.of(dir.toString());
        for (String name : List.of("src/main/templates/META-INF/neoforge.mods.toml", "src/main/templates/META-INF/mods.toml")) {
            Path toml = root.resolve(name);
            if (Files.isRegularFile(toml) && requiredInToml(Files.readString(toml), modId)) return true;
        }
        return false;
    }

    static boolean requiredInFabric(String json, String modId) {
        try {
            return Json.object(Json.object(Json.parse(json)).getOrDefault("depends", Map.of())).containsKey(modId);
        } catch (RuntimeException unreadable) {
            return true; // cannot tell: treat it as required, never guess
        }
    }

    /** A [[dependencies.X]] block with modId=modId whose type is "required" (or mandatory=true). */
    static boolean requiredInToml(String toml, String modId) {
        for (String block : toml.split("(?m)^\\s*\\[\\[")) {
            if (!block.startsWith("dependencies")) continue;
            if (!Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"" + Pattern.quote(modId) + "\"").matcher(block).find()) continue;
            Matcher type = Pattern.compile("(?m)^\\s*type\\s*=\\s*\"(\\w+)\"").matcher(block);
            Matcher mandatory = Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*(\\w+)").matcher(block);
            if (type.find()) return type.group(1).equalsIgnoreCase("required");
            if (mandatory.find()) return mandatory.group(1).equals("true");
            return true;
        }
        return false;
    }

    /**
     * A library released once per game version names the game in its own version: corelib 1.21.1-2.1.11, openhud
     * 1.3.0+1.21.1. Retargeting Minecraft changes (or should change) that coordinate, and the same library number is
     * rarely published for the new game, so the target is the library's own newest release for the target game, read
     * from the project's repositories (maven-metadata.xml): a version of the same shape with the target game in place of
     * the source game. Written where the build owns it: the whole version's property, or the library part's property
     * when the build joins it to the Minecraft property (${minecraft_version}-${corelib_version}). A coordinate built on
     * the Minecraft property with no release for the target is a blocker, not a guess. Returns false when the version does
     * not name the source game, so the caller keeps its usual handling.
     */
    static boolean gameVersioned(Target.Setup setup, String group, String name, String old, Path binary, String configuration)
        throws Exception {
        String from = null;
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (
            setup.pins.get(key) != null
        ) from = setup.pins.get(key);
        if (from == null || from.equals(setup.to)) return false;
        // Parchment, the loaders and the game itself have their own followers.
        if (group.startsWith("org.parchmentmc") || group.startsWith("net.neoforged") || group.startsWith("net.minecraftforge") || group.startsWith("net.fabricmc")) return false;
        // The game in the artifact's name (JEI: jei-${minecraft_version}-neoforge-api): the build renames it with the
        // Minecraft property, and the version is the newest of the same shape that artifact publishes for the target.
        Matcher named = Pattern.compile("(?<![0-9.])" + Pattern.quote(from) + "(?![0-9])(?!\\.[0-9])").matcher(name);
        if (named.find()) {
            String renamed = name.substring(0, named.start()) + setup.to + name.substring(named.end());
            Set<String> versions = new TreeSet<>();
            for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
                if (!repository.startsWith("https://")) continue;
                try {
                    Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(
                        Target.fetch(setup.build, repository.replaceFirst("/$", "") + "/" + group.replace('.', '/') + "/" + renamed + "/maven-metadata.xml")
                    );
                    while (version.find()) versions.add(version.group(1).strip());
                } catch (IOException notHere) {}
            }
            String shape = old.replaceAll("[0-9]+", "0"), chosen = null;
            for (String v : versions) if (v.replaceAll("[0-9]+", "0").equals(shape) && (chosen == null || numbers(v).compareTo(numbers(chosen)) > 0)) chosen = v;
            Set<String> owners = propertyKeys(setup.build.project, old, group, name);
            if (chosen == null) {
                // A compile-only integration written out by name keeps its old coordinate, as any compile-only one does
                // (mods compile against JEI's API): the build decides whether it still compiles.
                // Only a name the build makes from the Minecraft property (jei-${minecraft_version}-...) must change with it; a
                // name written out, or one that only arrives with another dependency, keeps its old coordinate.
                String head = name.substring(0, named.start());
                boolean interpolated = false;
                for (Path input : setup.build.project.inputs()) {
                    if (!input.getFileName().toString().contains(".gradle")) continue;
                    try {
                        if (setup.text(input).contains(head + "$")) interpolated = true;
                    } catch (java.nio.charset.CharacterCodingException notText) {}
                }
                if (!interpolated) return false;
                setup.blockers.add(
                    blocker(
                        group,
                        name,
                        old,
                        binary,
                        "its artifact name carries the Minecraft version, and " + renamed + " publishes no release in the project's repositories",
                        configuration,
                        setup.to
                    )
                );
                return true;
            }
            if (owners.size() != 1) return false;
            // A name written out in the build script (bookshelf-fabric-1.21.1) is renamed where it is written.
            Object buildFile = setup.project.get("buildFile");
            if (buildFile != null && Files.isRegularFile(Path.of(buildFile.toString()))) {
                Path script = Path.of(buildFile.toString());
                String text = setup.text(script);
                if (text.contains(name)) {
                    setup.originals.putIfAbsent(script, text);
                    setup.texts.put(script, text.replace(name, renamed));
                }
            }
            setup.replacements.put(owners.iterator().next(), chosen);
            setup.pins.putIfAbsent(owners.iterator().next(), old);
            setup.applied.add(
                Json.map("artifact", group + ":" + renamed, "from", old, "to", chosen, "configuration", configuration, "evidence", "the newest " + renamed + " release in the project's repositories")
            );
            recordLoom(setup, group, renamed, chosen);
            return true;
        }
        Matcher at = Pattern.compile("(?<![0-9.])" + Pattern.quote(from) + "(?![0-9])(?!\\.[0-9])").matcher(old);
        if (!at.find()) return false;
        String prefix = old.substring(0, at.start()), suffix = old.substring(at.end());
        Set<String> published = new TreeSet<>();
        for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
            if (!repository.startsWith("https://")) continue;
            try {
                String xml = Target.fetch(
                    setup.build,
                    repository.replaceFirst("/$", "") + "/" + group.replace('.', '/') + "/" + name + "/maven-metadata.xml"
                );
                Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
                while (version.find()) published.add(version.group(1).strip());
            } catch (IOException unavailable) {}
        }
        String chosen = chooseGameRelease(published, old, from, setup.to);
        Set<String> whole = propertyKeys(setup.build.project, old);
        // The library part beside the game: "2.1.11" in 1.21.1-2.1.11, "1.3.0" in 1.3.0+1.21.1.
        String part = prefix.isEmpty() ? suffix.replaceFirst("^[-+_.]", "") : suffix.isEmpty() ? prefix.replaceFirst("[-+_.]$", "") : "";
        Set<String> partKeys = part.isEmpty() ? Set.of() : propertyKeys(setup.build.project, part);
        if (chosen == null) {
            if (whole.isEmpty() && partKeys.size() == 1) {
                // The coordinate follows the Minecraft property, so keeping it is not possible: it would name a release that does not exist.
                setup.blockers.add(
                    blocker(
                        group,
                        name,
                        old,
                        binary,
                        "its version is built from the Minecraft version, and no " + setup.to + " release of it is published in the project's repositories",
                        configuration,
                        setup.to
                    )
                );
                return true;
            }
            return false;
        }
        String newPart = prefix.isEmpty()
            ? chosen.substring(chosen.indexOf(setup.to) + setup.to.length()).replaceFirst("^[-+_.]", "")
            : chosen.substring(0, chosen.lastIndexOf(setup.to)).replaceFirst("[-+_.]$", "");
        if (whole.size() == 1) {
            setup.replacements.put(whole.iterator().next(), chosen);
            setup.pins.putIfAbsent(whole.iterator().next(), old);
        } else if (whole.isEmpty() && partKeys.size() == 1) {
            setup.replacements.put(partKeys.iterator().next(), newPart);
            setup.pins.putIfAbsent(partKeys.iterator().next(), part);
        } else {
            // A property that owns the game and what follows it (Sound Physics: fabric-${voicechat_version},
            // voicechat_version=1.21.1-2.5.20), or what comes before it: the same span of the chosen release.
            String owner = null, value = null, was = null;
            for (int cut = 1; cut <= at.start() && owner == null; cut++) {
                Set<String> keys = propertyKeys(setup.build.project, old.substring(cut));
                if (keys.size() == 1 && chosen.startsWith(old.substring(0, cut))) {
                    owner = keys.iterator().next();
                    value = chosen.substring(cut);
                    was = old.substring(cut);
                }
            }
            for (int cut = old.length() - 1; cut >= at.end() && owner == null; cut--) {
                Set<String> keys = propertyKeys(setup.build.project, old.substring(0, cut));
                String tail = old.substring(cut);
                if (keys.size() == 1 && chosen.endsWith(tail)) {
                    owner = keys.iterator().next();
                    value = chosen.substring(0, chosen.length() - tail.length());
                    was = old.substring(0, cut);
                }
            }
            if (owner == null) return false;
            setup.replacements.put(owner, value);
            setup.pins.putIfAbsent(owner, was);
        }
        setup.applied.add(
            Json.map(
                "artifact",
                group + ":" + name,
                "from",
                old,
                "to",
                chosen,
                "configuration",
                configuration,
                "evidence",
                "the library's own release for Minecraft " + setup.to + ", read from its repository's maven-metadata.xml"
            )
        );
        recordLoom(setup, group, name, chosen);
        return true;
    }

    /**
     * The Loom a chosen Fabric library's jar was built with, for Target.loomFollows: a library looked up by its game version
     * (not through Modrinth) says it too (a Fabric port 1.20.1 -> 1.21.1 kept a Loom older than its JEI's 1.18.2). The jar is
     * read from the first project repository that has it; a jar that cannot be read records nothing.
     */
    static void recordLoom(Target.Setup setup, String group, String name, String version) {
        if (!Support.loaders(setup.project).contains("fabric")) return;
        for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
            if (!repository.startsWith("https://")) continue;
            try {
                Path jar = Target.download(
                    setup.build,
                    repository.replaceFirst("/$", "") + "/" + group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version + ".jar"
                );
                String built = builtWithLoom(jar);
                if (!built.isEmpty()) setup.loomBuilt.put(group + ":" + name + " " + version, built);
                return;
            } catch (Exception notThere) {}
        }
    }

    /** The newest published version shaped like old with the target game in place of the source game; stable before pre-releases. */
    static String chooseGameRelease(Collection<String> published, String old, String from, String to) {
        String shape = old.replace(from, "@").replaceAll("[0-9]+", "0");
        String best = null;
        for (String version : published) {
            if (!version.contains(to)) continue;
            String candidate = version.replaceFirst(Pattern.quote(to), "@");
            if (!candidate.replaceAll("[0-9]+", "0").equals(shape)) continue;
            if (best == null || numbers(candidate).compareTo(numbers(best.replaceFirst(Pattern.quote(to), "@"))) > 0) best = version;
        }
        return best;
    }

    /** The numbers of a version, zero-padded so text order is number order: 2.1.11 after 2.1.9. */
    private static String numbers(String version) {
        StringBuilder key = new StringBuilder();
        Matcher number = Pattern.compile("[0-9]+").matcher(version);
        while (number.find()) key.append(String.format("%012d", Long.parseLong(number.group().length() > 12 ? "999999999999" : number.group()))).append('.');
        return key.toString();
    }

    /**
     * A Fabric library published only to Maven (teamreborn:energy on maven.fabricmc.net, with no Modrinth project to
     * match): the newest version in the project's own repositories whose jar's fabric.mod.json accepts the target game.
     * A jar that states no Minecraft range proves nothing and is not chosen. Returns false when none does.
     */
    static boolean mavenRelease(Target.Setup setup, String group, String name, String old, Path binary) throws Exception {
        for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
            if (!repository.startsWith("https://")) continue;
            String base = repository.replaceFirst("/$", "") + "/" + group.replace('.', '/') + "/" + name + "/";
            List<String> versions = new ArrayList<>();
            try {
                Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(Target.fetch(setup.build, base + "maven-metadata.xml"));
                while (version.find()) if (!version.group(1).contains("SNAPSHOT")) versions.add(version.group(1).strip());
            } catch (IOException notHere) {
                continue;
            }
            versions.sort((a, b) -> Versions.compare(b, a));
            int tried = 0;
            for (String version : versions) {
                if (tried++ >= 40) break;
                String accepts;
                try {
                    accepts = minecraftConstraint(Target.download(setup.build, base + version + "/" + name + "-" + version + ".jar"));
                } catch (Exception unreadable) {
                    continue;
                }
                if (accepts.isEmpty() || !Versions.matches(accepts, setup.to)) continue;
                apply(
                    setup,
                    group,
                    name,
                    old,
                    group,
                    name,
                    version,
                    binary,
                    Json.map(
                        "repository",
                        repository,
                        "gameVersion",
                        setup.to,
                        "evidence",
                        "its fabric.mod.json accepts Minecraft " + setup.to + " (" + accepts + "), the newest such release in " + repository
                    )
                );
                recordLoom(setup, group, name, version);
                return true;
            }
        }
        return false;
    }

    static final String CURSE_MAVEN = "curse.maven";

    /**
     * A CurseMaven coordinate (curse.maven:SLUG-PROJECTID:FILEID) names one CurseForge file, so the target is the file the
     * project itself lists for the target game and loader: CurseForge's public file listing for that project id, newest
     * first, a release before a beta or alpha. Its file id goes where the build keeps the old one.
     */
    static void curseMaven(Target.Setup setup, String group, String name, String old, Path binary, String configuration, String loader)
        throws Exception {
        Matcher id = Pattern.compile("-(\\d+)$").matcher(name);
        if (!id.find()) throw new IOException("CurseMaven name " + name + " ends in no project id");
        String loaderName = loader.equals("neoforge") ? "NeoForge" : loader.equals("forge") ? "Forge" : "Fabric";
        Map<String, Object> best = null;
        for (int page = 0; page < 8; page++) {
            var files = Json.array(
                Json.object(
                    Json.parse(
                        Target.fetch(
                            setup.build,
                            "https://www.curseforge.com/api/v1/mods/" + id.group(1) + "/files?pageIndex=" + page + "&pageSize=50&sort=dateCreated&sortDescending=true"
                        )
                    )
                ).get("data")
            );
            best = curseFile(files, best, setup.to, loaderName);
            if (files.size() < 50 || best != null && ((Number) best.getOrDefault("releaseType", 1)).intValue() == 1) break;
        }
        if (best == null) throw new IOException(
            "CurseForge lists no " + loaderName + " file of project " + id.group(1) + " for " + setup.to
        );
        Map<String, Object> evidence = Json.map(
            "publishedFile",
            best.get("displayName"),
            "gameVersion",
            setup.to,
            "evidence",
            "CurseForge's file listing for project " + id.group(1) + " lists this " + loaderName + " file for " + setup.to
        );
        if (((Number) best.getOrDefault("releaseType", 1)).intValue() != 1) evidence.put(
            "preRelease",
            name + " has no release file for " + setup.to + ", so its newest " + (((Number) best.get("releaseType")).intValue() == 2 ? "beta" : "alpha") + " was chosen"
        );
        apply(setup, group, name, old, group, name, String.valueOf(((Number) best.get("id")).longValue()), binary, evidence);
    }

    /** The better of best and the files of one listing page for a game and loader: a release first, then the newest. */
    static Map<String, Object> curseFile(List<Object> files, Map<String, Object> best, String to, String loaderName) {
        for (Object item : files) {
            var file = Json.object(item);
            var versions = ProjectModel.strings(file.get("gameVersions"));
            if (!versions.contains(to) || !versions.contains(loaderName)) continue;
            int type = ((Number) file.getOrDefault("releaseType", 3)).intValue();
            if (best == null || type < ((Number) best.getOrDefault("releaseType", 3)).intValue()) best = file;
        }
        return best;
    }

    /** compileOnly, modCompileOnly, modCompileOnlyApi: on the compile classpath only, never in the jar or the game. */
    static boolean compileOnly(String configuration) {
        return configuration.matches("(?i)(mod)?compileOnly(Api)?");
    }

    /** Verified selection: the coordinate must publish, and its target-game support must be proven. */
    static void choose(Target.Setup setup, String group, String name, String old, Path binary, Selection selection)
        throws Exception {
        String[] target = coordinate(selection.target());
        if (target[2].contains("${")) throw new IOException(
            "Selection target for " + group + ":" + name + " needs a literal version, not " + target[2]
        );
        Map<String, Object> evidence = verify(setup.build, selection, target, setup.project, setup.to);
        apply(setup, group, name, old, target[0], target[1], target[2], binary, evidence);
    }

    /**
     * Prove a selected coordinate resolves and supports the target game version.
     * A published project version is the strongest evidence; otherwise the artifact's own
     * Fabric manifest must declare a constraint that accepts the target.
     */
    interface Artifact {
        Path get(String url) throws Exception;
    }

    static Map<String, Object> verify(
        Build build,
        Selection selection,
        String[] target,
        Map<String, Object> project,
        String to
    ) throws Exception {
        return verify(
            selection,
            target,
            project,
            to,
            "fabric",
            url -> Target.fetch(build, url),
            url -> Target.download(build, url)
        );
    }

    static Map<String, Object> verify(
        Selection selection,
        String[] target,
        Map<String, Object> project,
        String to,
        Fetch fetch,
        Artifact download
    ) throws Exception {
        return verify(selection, target, project, to, "fabric", fetch, download);
    }

    static Map<String, Object> verify(
        Selection selection,
        String[] target,
        Map<String, Object> project,
        String to,
        String loader,
        Fetch fetch,
        Artifact download
    ) throws Exception {
        String group = target[0], name = target[1], version = target[2];
        List<String> repositories = new ArrayList<>();
        if (!selection.repository().isBlank()) repositories.add(selection.repository());
        for (String repository : ProjectModel.strings(project.get("repositories"))) if (
            !repositories.contains(repository)
        ) repositories.add(repository);
        String pom = "", found = "";
        List<String> unreachable = new ArrayList<>();
        for (String repository : repositories) {
            if (!repository.startsWith("https://")) continue;
            String url =
                repository.replaceFirst("/+$", "") +
                "/" +
                group.replace('.', '/') +
                "/" +
                name +
                "/" +
                version +
                "/" +
                name +
                "-" +
                version +
                ".pom";
            try {
                fetch.get(url);
                pom = url;
                found = repository;
                break;
            } catch (Target.LookupFailure lookup) {
                unreachable.add(lookup.getMessage());
            } catch (IOException unpublished) {}
        }
        // A repository that could not answer is not proof the artifact is missing: say which, and why.
        if (pom.isEmpty() && !unreachable.isEmpty()) throw new Target.LookupFailure(
            "Could not check whether " +
            group +
            ":" +
            name +
            ":" +
            version +
            " is published: " +
            String.join("; ", unreachable) +
            ". Repositories tried: " +
            repositories +
            ". This is a lookup failure, not proof the version is missing; retry when those repositories answer.",
            null
        );
        if (pom.isEmpty()) throw new IOException(
            "Selected " +
            group +
            ":" +
            name +
            ":" +
            version +
            " is not published in " +
            repositories +
            "; add the repository that publishes it to the source build, then retry this selection"
        );
        Map<String, Object> evidence = Json.map("identity", "explicit selection", "repository", found, "pom", pom);
        if (!selection.modrinth().isBlank()) {
            String query =
                "/version?game_versions=" +
                URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8) +
                "&loaders=" +
                URLEncoder.encode(Json.write(List.of(loader)), StandardCharsets.UTF_8);
            String published = "";
            for (Object item : Json.array(
                Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + selection.modrinth() + query))
            )) {
                String number = Json.text(Json.object(item), "version_number", "");
                if (number.split("\\+", 2)[0].equals(version)) {
                    published = number;
                    break;
                }
            }
            if (published.isEmpty()) throw new IOException(
                "Published project " +
                selection.modrinth() +
                " has no Fabric " +
                version +
                " release for " +
                to +
                "; select a published version, or disable the dependency with a reason"
            );
            evidence.put("publishedProject", selection.modrinth());
            evidence.put("publishedVersion", published);
            evidence.put("gameVersion", to);
            evidence.put("evidence", "published " + displayLoader(loader) + " release lists " + to);
        } else {
            Path jar = download.get(
                found.replaceFirst("/+$", "") +
                "/" +
                group.replace('.', '/') +
                "/" +
                name +
                "/" +
                version +
                "/" +
                name +
                "-" +
                version +
                ".jar"
            );
            String constraint = minecraftConstraint(jar);
            if (constraint.isEmpty()) throw new IOException(
                "Selected artifact " +
                group +
                ":" +
                name +
                ":" +
                version +
                " does not declare its Minecraft dependency; name its published project with modrinth so " +
                to +
                " support is checked"
            );
            if (!Versions.matches(constraint, to)) throw new IOException(
                "Selected artifact " +
                group +
                ":" +
                name +
                ":" +
                version +
                " declares Minecraft " +
                constraint +
                ", which excludes " +
                to
            );
            evidence.put("gameVersion", to);
            evidence.put("minecraftConstraint", constraint);
            evidence.put("evidence", "artifact manifest accepts " + to);
        }
        return evidence;
    }

    static void apply(
        Target.Setup setup,
        String group,
        String name,
        String old,
        String newGroup,
        String newName,
        String version,
        Path binary,
        Map<String, Object> evidence
    ) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>(evidence);
        record.put("artifact", group + ":" + name);
        record.put("from", old);
        record.put("to", version);
        record.put("sourceSha512", sha512(binary));
        boolean renamed = !group.equals(newGroup) || !name.equals(newName);
        Set<String> keys = propertyKeys(setup.build.project, old, group, name);
        if (!renamed && keys.size() == 1) {
            String key = keys.iterator().next(), previous = setup.replacements.putIfAbsent(key, version);
            setup.pins.putIfAbsent(key, old);
            if (previous != null && !previous.equals(version)) throw new IOException(
                "Dependencies sharing " + key + " need one selected version; found " + previous + " and " + version
            );
        } else if (keys.isEmpty()) {
            Path file = Path.of(setup.project.get("buildFile").toString());
            String declared = declaredLiteral(setup.text(file), group + ":" + name, file);
            // A library mmt ported is written out as its literal version, whatever properties made the old one.
            if (declared.contains("${") && version.contains("-mmt-")) {
                String rest = declared.substring(group.length() + name.length() + 2);
                String classifier = rest.contains(":") ? rest.substring(rest.indexOf(':')) : "";
                setup.replace(file, declared, newGroup + ":" + newName + ":" + version + classifier);
                setup.applied.add(record);
                return;
            }
            if (declared.contains("${")) throw new IOException(
                "Dependency " +
                group +
                ":" +
                name +
                " is declared through " +
                declared +
                "; update that property's owner first"
            );
            setup.replace(file, declared, newGroup + ":" + newName + ":" + version);
        } else throw new IOException(
            "Dependency " +
            group +
            ":" +
            name +
            " is owned by " +
            keys +
            "; a rename cannot stay in one property, so edit its owner first"
        );
        setup.applied.add(record);
    }

    /** Disable an optional dependency only after proving the published project has no target release. */
    static void disable(Target.Setup setup, String group, String name, String old, Path binary, Selection selection)
        throws Exception {
        var project = unavailableProject(selection.modrinth(), setup.to, url -> Target.fetch(setup.build, url));
        Path file = Path.of(setup.project.get("buildFile").toString());
        String block = statement(setup.text(file), group + ":" + name + ":", file);
        setup.replace(file, block, comment(block));
        Set<Path> importers = importers(setup.build.project, binary);
        if (importers.isEmpty()) throw new IOException(
            "Disabling " + group + ":" + name + " found no importing sources; remove the declaration manually"
        );
        setup.excluded.addAll(importers);
        List<String> removed = entrypoints(setup, importers);
        setup.applied.add(
            Json.map(
                "artifact",
                group + ":" + name,
                "from",
                old,
                "disabled",
                true,
                "reason",
                selection.reason(),
                "removedSources",
                importers
                    .stream()
                    .map(p -> setup.build.project.root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList(),
                "removedEntrypoints",
                removed,
                "publishedProject",
                selection.modrinth(),
                "gameVersion",
                setup.to,
                "evidence",
                "published project lists no " + setup.to + " release"
            )
        );
    }

    /** A disabled dependency is only allowed when its published project really has no target release. */
    static Map<String, Object> unavailableProject(String modrinth, String to, Fetch fetch) throws Exception {
        var project = Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + modrinth)));
        if (ProjectModel.strings(project.get("game_versions")).contains(to)) throw new IOException(
            "Published project " + modrinth + " supports " + to + "; select its target version instead of disabling it"
        );
        return project;
    }

    /** Automatic discovery: exact artifact hash, then a uniquely matching published source repository. */
    static void discover(Target.Setup setup, String group, String name, String old, Path binary, String configuration)
        throws Exception {
        discover(setup, group, name, old, binary, configuration, "fabric");
    }

    static void discover(
        Target.Setup setup,
        String group,
        String name,
        String old,
        Path binary,
        String configuration,
        String loader
    ) throws Exception {
        String to = setup.to;
        String hash = sha512(binary);
        var current = publishedProject(binary, hash, to, loader, url -> Target.fetch(setup.build, url));
        String query =
            "?game_versions=" +
            URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8) +
            "&loaders=" +
            URLEncoder.encode(Json.write(List.of(loader)), StandardCharsets.UTF_8);
        var listed = Json.array(
            Json.parse(
                Target.fetch(
                    setup.build,
                    "https://api.modrinth.com/v2/project/" + current.get("project_id") + "/version" + query
                )
            )
        );
        if (listed.isEmpty()) throw new IOException(
            "No published " + displayLoader(loader) + " target for " + group + ":" + name + " on " + to
        );
        // Releases first: a beta or alpha is picked only when the project published nothing else for the
        // target, and the record says so. A selection in .mmt/dependencies.json can name any version.
        var releases = stableFirst(listed);
        boolean preRelease = !stable(Json.object(releases.get(0)));
        Map<String, Object> release = Json.object(releases.get(0));
        // The Loom each target jar was built with; Target.loomFollows raises the project's Loom to the newest of them.
        if (loader.equals("fabric")) {
            String built = builtWithLoom(setup.build, release);
            if (!built.isEmpty()) setup.loomBuilt.put(Json.text(release, "version_number", group + ":" + name), built);
        }
        String published = Json.text(release, "version_number", ""), target;
        if (group.equals(MODRINTH_GROUP)) {
            var sameGame = Json.array(
                Json.parse(
                    Target.fetch(
                        setup.build,
                        "https://api.modrinth.com/v2/project/" +
                        current.get("project_id") +
                        "/version?game_versions=" +
                        URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8)
                    )
                )
            );
            target = modrinthNeedsId(old, current, release, sameGame)
                ? Json.text(release, "id", "")
                : mavenVersion(setup, group, name, published);
        } else target = mavenVersion(setup, group, name, published);
        if (target.isEmpty()) throw new IOException(
            "Published target " + published + " has no verified Maven coordinate for " + group + ":" + name
        );
        Map<String, Object> evidence = Json.map(
            "sourceSha512",
            hash,
            "identity",
            current.getOrDefault("identity", "exact artifact hash"),
            "publishedProject",
            current.get("project_id"),
            "publishedVersion",
            published,
            "gameVersion",
            to,
            "evidence",
            "published " + displayLoader(loader) + " release lists " + to
        );
        if (preRelease) evidence.put(
            "preRelease",
            Json.text(release, "version_type", "pre-release") +
            ": " +
            group +
            ":" +
            name +
            " published no full release for " +
            to +
            ", so its newest " +
            Json.text(release, "version_type", "pre-release") +
            " " +
            published +
            " was chosen. Pin another version with a selection if that is not wanted."
        );
        apply(setup, group, name, old, group, name, target, binary, evidence);
        setup.build.report.stages.add(
            Json.map(
                "name",
                "target-mod-dependency",
                "artifact",
                group + ":" + name,
                "from",
                old,
                "to",
                target,
                "sourceSha512",
                hash,
                "identity",
                evidence.get("identity"),
                "publishedProject",
                current.get("project_id"),
                "gameVersion",
                to,
                "configuration",
                configuration
            )
        );
    }

    static boolean stable(Map<String, Object> version) {
        String type = Json.text(version, "version_type", "release");
        return type.isEmpty() || type.equals("release");
    }

    /** Modrinth's newest-first list with full releases ahead of betas and alphas; the order within each stays. */
    static List<Object> stableFirst(List<Object> versions) {
        List<Object> ordered = new ArrayList<>();
        for (Object item : versions) if (stable(Json.object(item))) ordered.add(item);
        for (Object item : versions) if (!stable(Json.object(item))) ordered.add(item);
        return ordered;
    }

    /** A Forge or NeoForge mod jar (it carries mods.toml): a mod dependency, not a plain library. */
    static Path forgeModJar(Map<String, Object> project, String group, String name, String version) {
        for (Object item : Json.array(project.get("artifacts"))) {
            var artifact = Json.object(item);
            if (
                !group.equals(artifact.get("group")) ||
                !name.equals(artifact.get("name")) ||
                !version.equals(artifact.get("version"))
            ) continue;
            Path file = Path.of(String.valueOf(artifact.get("file")));
            if (!file.toString().endsWith(".jar")) continue;
            try (var jar = new ZipFile(file.toFile())) {
                if (
                    jar.getEntry("META-INF/neoforge.mods.toml") != null || jar.getEntry("META-INF/mods.toml") != null
                ) return file;
            } catch (IOException unreadable) {}
        }
        // A dependency the model lists without its file (Corpse's maven.modrinth:openhud): Gradle's cache has the jar.
        Path cached = cachedJar(group + ":" + name, version);
        if (cached != null) try (var jar = new ZipFile(cached.toFile())) {
            if (jar.getEntry("META-INF/neoforge.mods.toml") != null || jar.getEntry("META-INF/mods.toml") != null) return cached;
        } catch (IOException unreadable) {}
        return null;
    }

    static final String MODRINTH_GROUP = "maven.modrinth";

    /** Modrinth's Maven serves a version number from whichever loader's release it finds first, and
     *  multiloader projects publish one number for Fabric, NeoForge and Forge (Serene Seasons 21.6.0.0
     *  resolved to the Forge jar). The version id is exact; use it then, or when the project pinned an id. */
    static boolean modrinthNeedsId(
        String old,
        Map<String, Object> current,
        Map<String, Object> release,
        List<Object> sameGame
    ) {
        if (old.equals(Json.text(current, "id", ""))) return true;
        String number = Json.text(release, "version_number", "");
        return (
            sameGame.stream().filter(v -> number.equals(Json.text(Json.object(v), "version_number", ""))).count() > 1
        );
    }

    /**
     * A release built by a newer Loom is kept: skipping it would silently choose an older dependency.
     * Target.loomFollows raises the project's Loom to match; this warning is for a build whose Loom
     * version it cannot find to raise. The jar's own Fabric-Loom-Version is the fact reported.
     */
    static String loomWarning(Map<String, Object> release, String built, String loom) {
        if (built.isEmpty() || loom.isEmpty() || Versions.compare(minor(built), minor(loom)) <= 0) return null;
        return (
            Json.text(release, "version_number", "") +
            " was built with Loom " +
            built +
            " (its jar's Fabric-Loom-Version), newer than this project's Loom " +
            loom +
            ". It was kept, and this build states its Loom version where mmt cannot raise it: raise it by hand or select an older release in .mmt/dependencies.json."
        );
    }

    /** "1.10.5" or "1.10-SNAPSHOT" -> "1.10". */
    static String minor(String version) {
        String[] parts = version.split("[.\\-+]");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    /** The Loom version a published release's primary jar records in its manifest, or "" when unknown. */
    static String builtWithLoom(Build build, Map<String, Object> release) {
        try {
            for (Object item : Json.array(release.get("files"))) {
                var file = Json.object(item);
                if (!Boolean.TRUE.equals(file.get("primary")) && Json.array(release.get("files")).size() > 1) continue;
                String url = Json.text(file, "url", "");
                if (!url.startsWith("https://")) continue;
                return builtWithLoom(Target.download(build, url));
            }
        } catch (Exception unreadable) {}
        return "";
    }

    /** The Loom version a mod jar's manifest records (Fabric-Loom-Version), or "" when it has none. */
    static String builtWithLoom(Path file) throws IOException {
        try (var jar = new java.util.jar.JarFile(file.toFile())) {
            var manifest = jar.getManifest();
            String value = manifest == null ? null : manifest.getMainAttributes().getValue("Fabric-Loom-Version");
            return value == null ? "" : value.trim();
        }
    }

    /** The published version must exist in a declared Maven repository, with or without its build suffix. */
    static String mavenVersion(Target.Setup setup, String group, String name, String published) throws Exception {
        Set<String> maven = new HashSet<>();
        List<String> unreachable = new ArrayList<>();
        String found = mavenVersion(setup, group, name, published, maven, unreachable);
        if (found.isEmpty() && !unreachable.isEmpty()) throw new Target.LookupFailure(
            "Could not check the Maven coordinate for " +
            group +
            ":" +
            name +
            " " +
            published +
            ": " +
            String.join("; ", unreachable) +
            ". This is a lookup failure, not proof the version is missing; retry when those repositories answer.",
            null
        );
        return found;
    }

    private static String mavenVersion(
        Target.Setup setup,
        String group,
        String name,
        String published,
        Set<String> maven,
        List<String> unreachable
    ) throws Exception {
        for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
            if (!repository.startsWith("https://")) continue;
            try {
                String xml = Target.fetch(
                    setup.build,
                    repository.replaceFirst("/$", "") +
                    "/" +
                    group.replace('.', '/') +
                    "/" +
                    name +
                    "/maven-metadata.xml"
                );
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var nodes = factory
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName("version");
                for (int i = 0; i < nodes.getLength(); i++) maven.add(nodes.item(i).getTextContent());
            } catch (Target.LookupFailure lookup) {
                unreachable.add(lookup.getMessage());
            } catch (IOException unavailable) {}
        }
        String base = published.split("\\+", 2)[0];
        return maven.contains(published) ? published : maven.contains(base) ? base : "";
    }

    /**
     * The properties holding a dependency's version; when several hold the same value (bookshelf_version and prickle_version
     * both 21.1.2), the ones named after the artifact.
     */
    static Set<String> propertyKeys(ProjectModel project, String version, String group, String name) throws IOException {
        Set<String> keys = propertyKeys(project, version);
        if (keys.size() < 2) return keys;
        String artifact = (group + "." + name).toLowerCase(Locale.ROOT);
        Set<String> named = new TreeSet<>();
        for (String key : keys) {
            String stem = key.replaceAll("(?i)[_.-]?version$", "").toLowerCase(Locale.ROOT);
            if (stem.length() >= 3 && artifact.contains(stem)) named.add(key);
        }
        return named.isEmpty() ? keys : named;
    }

    static Set<String> propertyKeys(ProjectModel project, String version) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path file : project.inputs()) if (
            file.getFileName().toString().equals("gradle.properties")
        ) ProjectModel.properties(file).forEach((k, v) -> {
            if (version.equals(v.toString())) keys.add(k.toString());
        });
        return keys;
    }

    static Path artifact(Map<String, Object> project, String group, String name, String version) {
        for (Object item : Json.array(project.get("artifacts"))) {
            var artifact = Json.object(item);
            if (
                !group.equals(artifact.get("group")) ||
                !name.equals(artifact.get("name")) ||
                !version.equals(artifact.get("version"))
            ) continue;
            Path file = Path.of(artifact.get("file").toString());
            if (!file.toString().endsWith(".jar")) continue;
            try (var jar = new ZipFile(file.toFile())) {
                if (jar.getEntry("fabric.mod.json") != null) return file;
            } catch (IOException unreadable) {}
        }
        return null;
    }

    static String sha512(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-512");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536];
            int n;
            while ((n = input.read(bytes)) >= 0) digest.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** A blocker names what failed, what was tried, and the exact selection to add. */
    static Map<String, Object> blocker(
        String group,
        String name,
        String old,
        Path binary,
        String reason,
        String configuration,
        String to
    ) {
        String artifact = group + ":" + name;
        return Json.map(
            "artifact",
            artifact,
            "version",
            old,
            "configuration",
            configuration,
            "file",
            binary == null ? "" : binary.toString(),
            "reason",
            reason,
            "suggestion",
            Json.map(
                "source",
                artifact + ":" + old,
                "target",
                artifact + ":<published " + to + " version>",
                "modrinth",
                "<published project id or slug>",
                "repository",
                "<Maven repository that publishes it>"
            ),
            "alternative",
            Json.map(
                "source",
                artifact + ":" + old,
                "disable",
                true,
                "reason",
                "<why this optional feature can be dropped>",
                "modrinth",
                "<published project with no " + to + " release>"
            )
        );
    }

    /** The whole declaration block, including a trailing closure, located by its artifact prefix. */
    static String statement(String text, String marker, Path file) throws IOException {
        int found = -1;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + 1)) {
            int lineStart = text.lastIndexOf('\n', at) + 1;
            String head = text.substring(lineStart, at).trim();
            if (head.startsWith("//") || head.startsWith("*") || head.startsWith("/*")) continue;
            if (found >= 0) throw new IOException(
                "Multiple active " +
                marker +
                " declarations in " +
                file +
                "; comment out or remove the one you do not need"
            );
            found = at;
        }
        if (found < 0) throw new IOException(
            "Cannot locate an active " + marker + " dependency declaration in " + file
        );
        int start = text.lastIndexOf('\n', found) + 1;
        String head = text.substring(start, found).trim();
        if (
            !head.matches("[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s+[A-Za-z_$][A-Za-z0-9_$.]*)*\\s*\\(?\\s*[\"']?")
        ) throw new IOException(
            "The " + marker + " declaration in " + file + " is not a standalone statement: " + head
        );
        int depth = 0, end = -1;
        boolean quoted = false, escape = false;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == quote) quoted = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == '\n' && depth == 0) {
                end = i;
                break;
            }
        }
        if (end < 0) end = text.length();
        return text.substring(start, end);
    }

    static String comment(String block) {
        String[] lines = block.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i].isBlank() ? lines[i] : "// " + lines[i]);
        }
        return out.toString();
    }

    /** Source files that import any class from the disabled artifact, proven from its actual entries. */
    static Set<Path> importers(ProjectModel project, Path binary) throws Exception {
        Set<String> classes = new HashSet<>();
        try (var jar = new ZipFile(binary.toFile())) {
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                String entry = entries.nextElement().getName();
                if (
                    !entry.endsWith(".class") ||
                    entry.equals("module-info.class") ||
                    entry.endsWith("package-info.class")
                ) continue;
                classes.add(entry.substring(0, entry.length() - 6));
            }
        }
        Set<Path> files = new TreeSet<>();
        // A source can name the library without importing it (a fully-qualified @Mixin target, for example),
        // so a package reference counts as ownership too.
        Set<String> prefixes = new TreeSet<>();
        for (String type : classes) {
            int first = type.indexOf('/'), second = first < 0 ? -1 : type.indexOf('/', first + 1);
            if (second > 0) prefixes.add(type.substring(0, second).replace('/', '.') + ".");
        }
        // A library by the mod's own author shares its vendor prefix (OpenHUD's de.maxhenkel. beside Corpse's
        // de.maxhenkel.corpse): a prefix the mod's own packages start with says nothing about who uses the library.
        List<Path> sources = Port.sources(project);
        for (Path file : sources) {
            if (!file.toString().endsWith(".java")) continue;
            Matcher own = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(Files.readString(file));
            if (own.find()) prefixes.removeIf(p -> (own.group(1) + ".").startsWith(p));
        }
        for (Path file : sources) {
            if (!file.toString().endsWith(".java")) continue;
            String text = Files.readString(file);
            boolean owns = false;
            Matcher imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.$]+)\\s*;").matcher(text);
            while (imports.find()) {
                String name = imports.group(1).replace('.', '/');
                int owner = name.lastIndexOf('/');
                if (classes.contains(name) || (owner > 0 && classes.contains(name.substring(0, owner)))) {
                    owns = true;
                    break;
                }
            }
            if (!owns) for (String prefix : prefixes) if (text.contains(prefix)) {
                owns = true;
                break;
            }
            if (owns) files.add(file);
        }
        return files;
    }

    /** Remove manifest entrypoints that name an excluded class; returns the removed keys. */
    static List<String> entrypoints(Target.Setup setup, Set<Path> excluded) throws Exception {
        Set<String> classes = new TreeSet<>();
        for (Path file : excluded) {
            String text = Files.readString(file);
            Matcher packageName = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;").matcher(text);
            String name = file.getFileName().toString().replaceFirst("\\.java$", "");
            classes.add((packageName.find() ? packageName.group(1) + "." : "") + name);
        }
        List<String> removed = new ArrayList<>();
        for (Path file : Target.manifests(setup.project)) {
            String before = setup.text(file);
            Map<String, Object> metadata;
            try {
                metadata = Json.object(Json.parse(before));
            } catch (IllegalArgumentException invalid) {
                throw new IOException(
                    "Cannot safely edit source Fabric manifest " + file + ": " + invalid.getMessage(),
                    invalid
                );
            }
            if (!(metadata.get("entrypoints") instanceof Map<?, ?>)) continue;
            var entrypoints = Json.object(metadata.get("entrypoints"));
            boolean changed = false;
            for (String key : new ArrayList<>(entrypoints.keySet())) {
                if (!names(entrypoints.get(key), classes)) continue;
                entrypoints.remove(key);
                removed.add(key);
                changed = true;
            }
            if (changed) setup.replace(file, before, Json.write(metadata) + (before.endsWith("\n") ? "\n" : ""));
        }
        return removed;
    }

    private static boolean names(Object value, Set<String> classes) {
        for (Object item : Json.array(value)) {
            Object className = item instanceof Map<?, ?> map ? Json.object(item).get("value") : item;
            if (className != null && classes.contains(className.toString().trim())) return true;
        }
        return false;
    }

    static Map<String, Object> publishedProject(Build build, Path binary, String hash, String to) throws Exception {
        return publishedProject(binary, hash, to, "fabric", url -> Target.fetch(build, url));
    }

    static Map<String, Object> publishedProject(Path binary, String hash, String to, Fetch fetch) throws Exception {
        return publishedProject(binary, hash, to, "fabric", fetch);
    }

    static Map<String, Object> publishedProject(Path binary, String hash, String to, String loader, Fetch fetch)
        throws Exception {
        try {
            return Json.object(
                Json.parse(fetch.get("https://api.modrinth.com/v2/version_file/" + hash + "?algorithm=sha512"))
            );
        } catch (IOException unindexed) {
            if (!unindexed.getMessage().contains("HTTP 404")) throw unindexed;
        }
        Map<String, Object> metadata;
        try (var jar = new ZipFile(binary.toFile()); var input = jar.getInputStream(jar.getEntry("fabric.mod.json"))) {
            metadata = Json.object(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        }
        var contact = Json.object(metadata.getOrDefault("contact", Map.of()));
        String source = Json.text(contact, "sources", "");
        if (source.isEmpty()) source = directGithubRepository(Json.text(contact, "homepage", ""));
        if (source.isEmpty()) throw new IOException(
            "Unindexed dependency has no direct source repository in contact.sources or contact.homepage: " +
            binary +
            "; add a selection for it in .mmt/dependencies.json"
        );
        return findPublishedProject(
            source,
            Json.text(metadata, "name", Json.text(metadata, "id", "")),
            to,
            loader,
            fetch
        );
    }

    static String directGithubRepository(String value) {
        try {
            var uri = java.net.URI.create(value);
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                !"github.com".equalsIgnoreCase(uri.getHost()) ||
                uri.getUserInfo() != null ||
                uri.getPort() != -1 ||
                uri.getQuery() != null ||
                uri.getFragment() != null
            ) return "";
            String path = uri.getPath();
            return path != null && path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?") ? value : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    interface Fetch {
        String get(String url) throws Exception;
    }

    static Map<String, Object> findPublishedProject(String source, String name, String to, Fetch fetch)
        throws Exception {
        return findPublishedProject(source, name, to, "fabric", fetch);
    }

    static Map<String, Object> findPublishedProject(String source, String name, String to, String loader, Fetch fetch)
        throws Exception {
        String identity = repository(source);
        if (identity.isEmpty() || !identity.contains("/")) throw new IOException(
            "Unindexed dependency has no usable source repository: " + source
        );
        String sourcePath = java.net.URI.create(source).getPath().replaceFirst("/+$", "").replaceFirst("\\.git$", "");
        Set<String> queries = new LinkedHashSet<>(List.of(name, sourcePath.substring(sourcePath.lastIndexOf('/') + 1)));
        Set<String> candidates = new LinkedHashSet<>();
        for (String term : queries) {
            if (term.isBlank()) continue;
            String query = URLEncoder.encode(term, StandardCharsets.UTF_8);
            var search = Json.object(
                Json.parse(fetch.get("https://api.modrinth.com/v2/search?query=" + query + "&limit=5"))
            );
            for (Object hit : Json.array(search.get("hits"))) {
                String id = Json.text(Json.object(hit), "project_id", "");
                if (!id.isBlank()) candidates.add(id);
            }
        }
        Set<String> projects = new HashSet<>();
        for (String id : candidates) {
            var project = Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + id)));
            if (
                identity.equals(repository(Json.text(project, "source_url", ""))) &&
                ProjectModel.strings(project.get("loaders")).contains(loader) &&
                ProjectModel.strings(project.get("game_versions")).contains(to)
            ) projects.add(id);
        }
        if (projects.size() != 1) throw new IOException(
            projects.isEmpty()
                ? "No published " +
                displayLoader(loader) +
                " project with matching source repository for " +
                source +
                " on " +
                to +
                "; add a selection for it in .mmt/dependencies.json."
                : "Multiple published " +
                displayLoader(loader) +
                " projects match source repository " +
                source +
                " on " +
                to +
                "; add a selection naming the intended one."
        );
        return Json.map("project_id", projects.iterator().next(), "identity", "verified source repository");
    }

    static String repository(String url) {
        try {
            var uri = java.net.URI.create(url);
            return (Objects.toString(uri.getHost(), "") + Objects.toString(uri.getPath(), "")).replaceFirst("/$", "")
                .replaceFirst("\\.git$", "")
                .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    /** The artifact literal as declared, without its quotes, so a version or coordinate change keeps them. */
    static String declaredLiteral(String text, String artifact, Path file) throws IOException {
        Matcher literal = Pattern.compile("[\"'](" + Pattern.quote(artifact) + ":[^\"']*)[\"']").matcher(text);
        if (!literal.find()) throw new IOException(
            "Cannot locate an unambiguous dependency setting for " + artifact + " in " + file
        );
        String declared = literal.group(1);
        if (literal.find()) throw new IOException("Multiple dependency literals for " + artifact + " in " + file);
        return declared;
    }

    static String literal(String text, String artifact, String old, String target) throws IOException {
        Matcher literal = Pattern.compile("([\"'])" + Pattern.quote(artifact + ":" + old) + "([\"'])").matcher(text);
        if (!literal.find()) throw new IOException("Cannot locate an unambiguous dependency setting for " + artifact);
        int start = literal.start(), end = literal.end();
        String replacement = literal.group(1) + artifact + ":" + target + literal.group(2);
        if (literal.find()) throw new IOException("Multiple dependency literals for " + artifact);
        return text.substring(0, start) + replacement + text.substring(end);
    }

    /** A Fabric mod jar whose fabric.mod.json exists and depends on no Minecraft version. */
    static boolean gameIndependent(Path jar) {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return false;
            var metadata = Json.object(Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)));
            return !(metadata.get("depends") instanceof Map<?, ?> depends) || !depends.containsKey("minecraft");
        } catch (Exception unreadable) {
            return false;
        }
    }

    static String minecraftConstraint(Path jar) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return "";
            var metadata = Json.object(
                Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
            );
            if (!(metadata.get("depends") instanceof Map<?, ?>)) return "";
            Object constraint = Json.object(metadata.get("depends")).get("minecraft");
            return constraint instanceof String value ? value : "";
        }
    }
}
