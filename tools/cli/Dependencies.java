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
    static String displayLoader(String loader) { return loader.equals("neoforge") ? "NeoForge" : loader.equals("forge") ? "Forge" : "Fabric"; }
    static String[] coordinate(String value) {
        String[] parts = value.split(":", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalArgumentException("Dependency coordinates need group:name[:version]; got " + value);
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
            String source = Json.text(entry, "source", "").trim(); coordinate(source);
            boolean disable = Boolean.TRUE.equals(entry.get("disable"));
            String target = Json.text(entry, "target", "").trim(), modrinth = Json.text(entry, "modrinth", "").trim();
            String repository = Json.text(entry, "repository", "").trim(), reason = Json.text(entry, "reason", "").trim();
            if (disable) {
                if (!target.isEmpty()) throw new IOException("Selection for " + source + " cannot both target and disable");
                if (reason.isEmpty()) throw new IOException("Disabling " + source + " needs a reason; the removed feature is reported and undone with the port");
                if (modrinth.isEmpty()) throw new IOException("Disabling " + source + " needs modrinth so missing target support can be checked");
            } else {
                if (coordinate(target).length != 3) throw new IOException("Selection target needs group:name:version; got " + target);
                if (modrinth.isEmpty() && repository.isEmpty()) throw new IOException("Selection for " + source + " needs either modrinth (published project) or a repository so target-game support can be verified");
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
    static void resolve(Target.Setup setup) throws Exception { resolve(setup, "fabric"); }
    static void resolve(Target.Setup setup, String loader) throws Exception {
        Set<String> seen = new HashSet<>();
        for (Object item : Json.array(setup.project.get("dependencies"))) {
            var dep = Json.object(item); String group = Json.text(dep, "group", ""), name = Json.text(dep, "name", ""), old = Json.text(dep, "version", "");
            if (group.startsWith("net.fabricmc") || group.equals("com.mojang") || old.isEmpty() || !seen.add(group + ":" + name + ":" + old)) continue;
            Path binary = artifact(setup.project, group, name, old);
            if (binary == null) continue;
            Selection selection = select(setup.selections, group, name, old);
            try {
                if (selection != null && selection.disable()) disable(setup, group, name, old, binary, selection);
                else if (selection != null) choose(setup, group, name, old, binary, selection);
                else discover(setup, group, name, old, binary, Json.text(dep, "configuration", ""), loader);
            } catch (IOException failure) {
                String configuration = Json.text(dep, "configuration", "");
                // A compile-only integration is never packaged or loaded with the mod, and authors often
                // already compile against an older release of it. Keep it and let the compiler decide.
                if (selection == null && compileOnly(configuration)) {
                    setup.applied.add(Json.map("artifact", group + ":" + name, "from", old, "to", old, "kept", true, "configuration", configuration,
                            "reason", failure.getMessage() + "; kept at " + old + " because a " + configuration + " dependency is not shipped or loaded with the mod. The build decides whether the integration still compiles."));
                    continue;
                }
                setup.blockers.add(blocker(group, name, old, binary, failure.getMessage(), configuration, setup.to));
            }
        }
    }
    /** compileOnly, modCompileOnly, modCompileOnlyApi: on the compile classpath only, never in the jar or the game. */
    static boolean compileOnly(String configuration) {
        return configuration.matches("(?i)(mod)?compileOnly(Api)?");
    }
    /** Verified selection: the coordinate must publish, and its target-game support must be proven. */
    static void choose(Target.Setup setup, String group, String name, String old, Path binary, Selection selection) throws Exception {
        String[] target = coordinate(selection.target());
        if (target[2].contains("${")) throw new IOException("Selection target for " + group + ":" + name + " needs a literal version, not " + target[2]);
        Map<String, Object> evidence = verify(setup.build, selection, target, setup.project, setup.to);
        apply(setup, group, name, old, target[0], target[1], target[2], binary, evidence);
    }
    /**
     * Prove a selected coordinate resolves and supports the target game version.
     * A published project version is the strongest evidence; otherwise the artifact's own
     * Fabric manifest must declare a constraint that accepts the target.
     */
    interface Artifact { Path get(String url) throws Exception; }
    static Map<String, Object> verify(Build build, Selection selection, String[] target, Map<String, Object> project, String to) throws Exception {
        return verify(selection, target, project, to, "fabric", url -> Target.fetch(build, url), url -> Target.download(build, url));
    }
    static Map<String, Object> verify(Selection selection, String[] target, Map<String, Object> project, String to, Fetch fetch, Artifact download) throws Exception { return verify(selection, target, project, to, "fabric", fetch, download); }
    static Map<String, Object> verify(Selection selection, String[] target, Map<String, Object> project, String to, String loader, Fetch fetch, Artifact download) throws Exception {
        String group = target[0], name = target[1], version = target[2];
        List<String> repositories = new ArrayList<>();
        if (!selection.repository().isBlank()) repositories.add(selection.repository());
        for (String repository : ProjectModel.strings(project.get("repositories"))) if (!repositories.contains(repository)) repositories.add(repository);
        String pom = "", found = "";
        for (String repository : repositories) {
            if (!repository.startsWith("https://")) continue;
            String url = repository.replaceFirst("/+$", "") + "/" + group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version + ".pom";
            try { fetch.get(url); pom = url; found = repository; break; }
            catch (IOException unpublished) { }
        }
        if (pom.isEmpty()) throw new IOException("Selected " + group + ":" + name + ":" + version + " is not published in " + repositories
                + "; add the repository that publishes it to the source build, then retry this selection");
        Map<String, Object> evidence = Json.map("identity", "explicit selection", "repository", found, "pom", pom);
        if (!selection.modrinth().isBlank()) {
            String query = "/version?game_versions=" + URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8)
                    + "&loaders=" + URLEncoder.encode(Json.write(List.of(loader)), StandardCharsets.UTF_8);
            String published = "";
            for (Object item : Json.array(Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + selection.modrinth() + query)))) {
                String number = Json.text(Json.object(item), "version_number", "");
                if (number.split("\\+", 2)[0].equals(version)) { published = number; break; }
            }
            if (published.isEmpty()) throw new IOException("Published project " + selection.modrinth() + " has no Fabric " + version + " release for " + to
                    + "; select a published version, or disable the dependency with a reason");
            evidence.put("publishedProject", selection.modrinth()); evidence.put("publishedVersion", published); evidence.put("gameVersion", to);
            evidence.put("evidence", "published " + displayLoader(loader) + " release lists " + to);
        } else {
            Path jar = download.get(found.replaceFirst("/+$", "") + "/" + group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version + ".jar");
            String constraint = minecraftConstraint(jar);
            if (constraint.isEmpty()) throw new IOException("Selected artifact " + group + ":" + name + ":" + version
                    + " does not declare its Minecraft dependency; name its published project with modrinth so " + to + " support is checked");
            if (!Versions.matches(constraint, to)) throw new IOException("Selected artifact " + group + ":" + name + ":" + version
                    + " declares Minecraft " + constraint + ", which excludes " + to);
            evidence.put("gameVersion", to); evidence.put("minecraftConstraint", constraint);
            evidence.put("evidence", "artifact manifest accepts " + to);
        }
        return evidence;
    }
    static void apply(Target.Setup setup, String group, String name, String old, String newGroup, String newName, String version, Path binary, Map<String, Object> evidence) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>(evidence);
        record.put("artifact", group + ":" + name); record.put("from", old); record.put("to", version); record.put("sourceSha512", sha512(binary));
        boolean renamed = !group.equals(newGroup) || !name.equals(newName);
        Set<String> keys = propertyKeys(setup.build.project, old);
        if (!renamed && keys.size() == 1) {
            String key = keys.iterator().next(), previous = setup.replacements.putIfAbsent(key, version);
            setup.pins.putIfAbsent(key, old);
            if (previous != null && !previous.equals(version)) throw new IOException("Dependencies sharing " + key + " need one selected version; found " + previous + " and " + version);
        } else if (keys.isEmpty()) {
            Path file = Path.of(setup.project.get("buildFile").toString());
            String declared = declaredLiteral(setup.text(file), group + ":" + name, file);
            if (declared.contains("${")) throw new IOException("Dependency " + group + ":" + name + " is declared through " + declared + "; update that property's owner first");
            setup.replace(file, declared, newGroup + ":" + newName + ":" + version);
        } else throw new IOException("Dependency " + group + ":" + name + " is owned by " + keys + "; a rename cannot stay in one property, so edit its owner first");
        setup.applied.add(record);
    }
    /** Disable an optional dependency only after proving the published project has no target release. */
    static void disable(Target.Setup setup, String group, String name, String old, Path binary, Selection selection) throws Exception {
        var project = unavailableProject(selection.modrinth(), setup.to, url -> Target.fetch(setup.build, url));
        Path file = Path.of(setup.project.get("buildFile").toString());
        String block = statement(setup.text(file), group + ":" + name + ":", file);
        setup.replace(file, block, comment(block));
        Set<Path> importers = importers(setup.build.project, binary);
        if (importers.isEmpty()) throw new IOException("Disabling " + group + ":" + name + " found no importing sources; remove the declaration manually");
        setup.excluded.addAll(importers);
        List<String> removed = entrypoints(setup, importers);
        setup.applied.add(Json.map("artifact", group + ":" + name, "from", old, "disabled", true, "reason", selection.reason(),
                "removedSources", importers.stream().map(p -> setup.build.project.root.relativize(p).toString().replace('\\', '/')).sorted().toList(),
                "removedEntrypoints", removed, "publishedProject", selection.modrinth(), "gameVersion", setup.to,
                "evidence", "published project lists no " + setup.to + " release"));
    }
    /** A disabled dependency is only allowed when its published project really has no target release. */
    static Map<String, Object> unavailableProject(String modrinth, String to, Fetch fetch) throws Exception {
        var project = Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + modrinth)));
        if (ProjectModel.strings(project.get("game_versions")).contains(to))
            throw new IOException("Published project " + modrinth + " supports " + to + "; select its target version instead of disabling it");
        return project;
    }
    /** Automatic discovery: exact artifact hash, then a uniquely matching published source repository. */
    static void discover(Target.Setup setup, String group, String name, String old, Path binary, String configuration) throws Exception {
        discover(setup, group, name, old, binary, configuration, "fabric");
    }
    static void discover(Target.Setup setup, String group, String name, String old, Path binary, String configuration, String loader) throws Exception {
        String to = setup.to;
        String hash = sha512(binary);
        var current = publishedProject(binary, hash, to, loader, url -> Target.fetch(setup.build, url));
        String query = "?game_versions=" + URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8) + "&loaders=" + URLEncoder.encode(Json.write(List.of(loader)), StandardCharsets.UTF_8);
        var releases = Json.array(Json.parse(Target.fetch(setup.build, "https://api.modrinth.com/v2/project/" + current.get("project_id") + "/version" + query)));
        if (releases.isEmpty()) throw new IOException("No published " + displayLoader(loader) + " target for " + group + ":" + name + " on " + to);
        Map<String, Object> release = Json.object(releases.get(0)); String toolchainNote = null;
        String loom = Json.text(setup.project, "loomVersion", "");
        if (loader.equals("fabric") && !loom.isEmpty()) {
            // The newest release can be built by a newer Loom whose files (class tweakers) this
            // project's Loom cannot read, which fails before any code compiles. Prefer the newest
            // release this build can consume; the project's own toolchain stays unchanged.
            Map<String, Object> compatible = null; List<String> skipped = new ArrayList<>();
            for (Object item : releases) {
                var candidate = Json.object(item);
                String built = builtWithLoom(setup.build, candidate);
                if (built.isEmpty() || Versions.compare(minor(built), minor(loom)) <= 0) { compatible = candidate; break; }
                skipped.add(Json.text(candidate, "version_number", "") + " (Loom " + built + ")");
            }
            if (compatible != null) release = compatible;
            if (!skipped.isEmpty()) toolchainNote = (compatible != null ? "Skipped " : "Every release needs a newer Loom than this project's " + loom + ": ")
                    + String.join(", ", skipped) + (compatible != null ? ", built with a newer Loom than this project's " + loom : "; raise loom_version if configuration fails");
        }
        String published = Json.text(release, "version_number", ""), target;
        if (group.equals(MODRINTH_GROUP)) {
            var sameGame = Json.array(Json.parse(Target.fetch(setup.build, "https://api.modrinth.com/v2/project/" + current.get("project_id")
                    + "/version?game_versions=" + URLEncoder.encode(Json.write(List.of(to)), StandardCharsets.UTF_8))));
            target = modrinthNeedsId(old, current, release, sameGame) ? Json.text(release, "id", "") : mavenVersion(setup, group, name, published);
        } else target = mavenVersion(setup, group, name, published);
        if (target.isEmpty()) throw new IOException("Published target " + published + " has no verified Maven coordinate for " + group + ":" + name);
        Map<String, Object> evidence = Json.map("sourceSha512", hash, "identity", current.getOrDefault("identity", "exact artifact hash"),
                "publishedProject", current.get("project_id"), "publishedVersion", published, "gameVersion", to, "evidence", "published " + displayLoader(loader) + " release lists " + to);
        if (toolchainNote != null) evidence.put("toolchain", toolchainNote);
        apply(setup, group, name, old, group, name, target, binary, evidence);
        setup.build.report.stages.add(Json.map("name", "target-mod-dependency", "artifact", group + ":" + name, "from", old, "to", target,
                "sourceSha512", hash, "identity", evidence.get("identity"), "publishedProject", current.get("project_id"), "gameVersion", to, "configuration", configuration));
    }
    static final String MODRINTH_GROUP = "maven.modrinth";
    /** Modrinth's Maven serves a version number from whichever loader's release it finds first, and
     *  multiloader projects publish one number for Fabric, NeoForge and Forge (Serene Seasons 21.6.0.0
     *  resolved to the Forge jar). The version id is exact; use it then, or when the project pinned an id. */
    static boolean modrinthNeedsId(String old, Map<String, Object> current, Map<String, Object> release, List<Object> sameGame) {
        if (old.equals(Json.text(current, "id", ""))) return true;
        String number = Json.text(release, "version_number", "");
        return sameGame.stream().filter(v -> number.equals(Json.text(Json.object(v), "version_number", ""))).count() > 1;
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
                try (var jar = new java.util.jar.JarFile(Target.download(build, url).toFile())) {
                    var manifest = jar.getManifest();
                    String value = manifest == null ? null : manifest.getMainAttributes().getValue("Fabric-Loom-Version");
                    return value == null ? "" : value.trim();
                }
            }
        } catch (Exception unreadable) { }
        return "";
    }
    /** The published version must exist in a declared Maven repository, with or without its build suffix. */
    static String mavenVersion(Target.Setup setup, String group, String name, String published) throws Exception {
        Set<String> maven = new HashSet<>();
        for (String repository : ProjectModel.strings(setup.project.get("repositories"))) {
            if (!repository.startsWith("https://")) continue;
            try {
                String xml = Target.fetch(setup.build, repository.replaceFirst("/$", "") + "/" + group.replace('.', '/') + "/" + name + "/maven-metadata.xml");
                var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("version");
                for (int i = 0; i < nodes.getLength(); i++) maven.add(nodes.item(i).getTextContent());
            } catch (IOException unavailable) { }
        }
        String base = published.split("\\+", 2)[0];
        return maven.contains(published) ? published : maven.contains(base) ? base : "";
    }
    static Set<String> propertyKeys(ProjectModel project, String version) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path file : project.inputs()) if (file.getFileName().toString().equals("gradle.properties"))
            ProjectModel.properties(file).forEach((k, v) -> { if (version.equals(v.toString())) keys.add(k.toString()); });
        return keys;
    }
    static Path artifact(Map<String, Object> project, String group, String name, String version) {
        for (Object item : Json.array(project.get("artifacts"))) {
            var artifact = Json.object(item);
            if (!group.equals(artifact.get("group")) || !name.equals(artifact.get("name")) || !version.equals(artifact.get("version"))) continue;
            Path file = Path.of(artifact.get("file").toString()); if (!file.toString().endsWith(".jar")) continue;
            try (var jar = new ZipFile(file.toFile())) { if (jar.getEntry("fabric.mod.json") != null) return file; }
            catch (IOException unreadable) { }
        }
        return null;
    }
    static String sha512(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-512");
        try (var input = Files.newInputStream(file)) { byte[] bytes = new byte[65536]; int n; while ((n = input.read(bytes)) >= 0) digest.update(bytes, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    /** A blocker names what failed, what was tried, and the exact selection to add. */
    static Map<String, Object> blocker(String group, String name, String old, Path binary, String reason, String configuration, String to) {
        String artifact = group + ":" + name;
        return Json.map("artifact", artifact, "version", old, "configuration", configuration, "file", binary.toString(), "reason", reason,
                "suggestion", Json.map("source", artifact + ":" + old, "target", artifact + ":<published " + to + " version>",
                        "modrinth", "<published project id or slug>", "repository", "<Maven repository that publishes it>"),
                "alternative", Json.map("source", artifact + ":" + old, "disable", true,
                        "reason", "<why this optional feature can be dropped>", "modrinth", "<published project with no " + to + " release>"));
    }
    /** The whole declaration block, including a trailing closure, located by its artifact prefix. */
    static String statement(String text, String marker, Path file) throws IOException {
        int found = -1;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + 1)) {
            int lineStart = text.lastIndexOf('\n', at) + 1;
            String head = text.substring(lineStart, at).trim();
            if (head.startsWith("//") || head.startsWith("*") || head.startsWith("/*")) continue;
            if (found >= 0) throw new IOException("Multiple active " + marker + " declarations in " + file + "; comment out or remove the one you do not need");
            found = at;
        }
        if (found < 0) throw new IOException("Cannot locate an active " + marker + " dependency declaration in " + file);
        int start = text.lastIndexOf('\n', found) + 1;
        String head = text.substring(start, found).trim();
        if (!head.matches("[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s+[A-Za-z_$][A-Za-z0-9_$.]*)*\\s*\\(?\\s*[\"']?"))
            throw new IOException("The " + marker + " declaration in " + file + " is not a standalone statement: " + head);
        int depth = 0, end = -1; boolean quoted = false, escape = false; char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == quote) quoted = false; continue; }
            if (c == '"' || c == '\'') { quoted = true; quote = c; continue; }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == '\n' && depth == 0) { end = i; break; }
        }
        if (end < 0) end = text.length();
        return text.substring(start, end);
    }
    static String comment(String block) {
        String[] lines = block.split("\n", -1); StringBuilder out = new StringBuilder();
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
                if (!entry.endsWith(".class") || entry.equals("module-info.class") || entry.endsWith("package-info.class")) continue;
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
        for (Path file : Port.sources(project)) {
            if (!file.toString().endsWith(".java")) continue;
            String text = Files.readString(file);
            boolean owns = false;
            Matcher imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.$]+)\\s*;").matcher(text);
            while (imports.find()) {
                String name = imports.group(1).replace('.', '/');
                int owner = name.lastIndexOf('/');
                if (classes.contains(name) || owner > 0 && classes.contains(name.substring(0, owner))) { owns = true; break; }
            }
            if (!owns) for (String prefix : prefixes) if (text.contains(prefix)) { owns = true; break; }
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
            try { metadata = Json.object(Json.parse(before)); } catch (IllegalArgumentException invalid) { throw new IOException("Cannot safely edit source Fabric manifest " + file + ": " + invalid.getMessage(), invalid); }
            if (!(metadata.get("entrypoints") instanceof Map<?, ?>)) continue;
            var entrypoints = Json.object(metadata.get("entrypoints")); boolean changed = false;
            for (String key : new ArrayList<>(entrypoints.keySet())) {
                if (!names(entrypoints.get(key), classes)) continue;
                entrypoints.remove(key); removed.add(key); changed = true;
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
    static Map<String, Object> publishedProject(Path binary, String hash, String to, String loader, Fetch fetch) throws Exception {
        try { return Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/version_file/" + hash + "?algorithm=sha512"))); }
        catch (IOException unindexed) { if (!unindexed.getMessage().contains("HTTP 404")) throw unindexed; }
        Map<String, Object> metadata;
        try (var jar = new ZipFile(binary.toFile()); var input = jar.getInputStream(jar.getEntry("fabric.mod.json"))) {
            metadata = Json.object(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        }
        var contact = Json.object(metadata.getOrDefault("contact", Map.of()));
        String source = Json.text(contact, "sources", "");
        if (source.isEmpty()) source = directGithubRepository(Json.text(contact, "homepage", ""));
        if (source.isEmpty()) throw new IOException("Unindexed dependency has no direct source repository in contact.sources or contact.homepage: " + binary
                + "; add a selection for it in .modaccel/dependencies.json");
        return findPublishedProject(source, Json.text(metadata, "name", Json.text(metadata, "id", "")), to, loader, fetch);
    }
    static String directGithubRepository(String value) {
        try {
            var uri = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) return "";
            String path = uri.getPath();
            return path != null && path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?") ? value : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    interface Fetch { String get(String url) throws Exception; }
    static Map<String, Object> findPublishedProject(String source, String name, String to, Fetch fetch) throws Exception { return findPublishedProject(source, name, to, "fabric", fetch); }
    static Map<String, Object> findPublishedProject(String source, String name, String to, String loader, Fetch fetch) throws Exception {
        String identity = repository(source);
        if (identity.isEmpty() || !identity.contains("/")) throw new IOException("Unindexed dependency has no usable source repository: " + source);
        String sourcePath = java.net.URI.create(source).getPath().replaceFirst("/+$", "").replaceFirst("\\.git$", "");
        Set<String> queries = new LinkedHashSet<>(List.of(name, sourcePath.substring(sourcePath.lastIndexOf('/') + 1)));
        Set<String> candidates = new LinkedHashSet<>();
        for (String term : queries) {
            if (term.isBlank()) continue;
            String query = URLEncoder.encode(term, StandardCharsets.UTF_8);
            var search = Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/search?query=" + query + "&limit=5")));
            for (Object hit : Json.array(search.get("hits"))) {
                String id = Json.text(Json.object(hit), "project_id", "");
                if (!id.isBlank()) candidates.add(id);
            }
        }
        Set<String> projects = new HashSet<>();
        for (String id : candidates) {
            var project = Json.object(Json.parse(fetch.get("https://api.modrinth.com/v2/project/" + id)));
            if (identity.equals(repository(Json.text(project, "source_url", "")))
                    && ProjectModel.strings(project.get("loaders")).contains(loader)
                    && ProjectModel.strings(project.get("game_versions")).contains(to)) projects.add(id);
        }
        if (projects.size() != 1) throw new IOException(projects.isEmpty()
                ? "No published " + displayLoader(loader) + " project with matching source repository for " + source + " on " + to + "; add a selection for it in .modaccel/dependencies.json."
                : "Multiple published " + displayLoader(loader) + " projects match source repository " + source + " on " + to + "; add a selection naming the intended one.");
        return Json.map("project_id", projects.iterator().next(), "identity", "verified source repository");
    }
    static String repository(String url) {
        try { var uri = java.net.URI.create(url); return (Objects.toString(uri.getHost(), "") + Objects.toString(uri.getPath(), "")).replaceFirst("/$", "").replaceFirst("\\.git$", "").toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException invalid) { return ""; }
    }
    /** The artifact literal as declared, without its quotes, so a version or coordinate change keeps them. */
    static String declaredLiteral(String text, String artifact, Path file) throws IOException {
        Matcher literal = Pattern.compile("[\"'](" + Pattern.quote(artifact) + ":[^\"']*)[\"']").matcher(text);
        if (!literal.find()) throw new IOException("Cannot locate an unambiguous dependency setting for " + artifact + " in " + file);
        String declared = literal.group(1);
        if (literal.find()) throw new IOException("Multiple dependency literals for " + artifact + " in " + file);
        return declared;
    }
    static String literal(String text, String artifact, String old, String target) throws IOException {
        Matcher literal = Pattern.compile("([\"'])" + Pattern.quote(artifact + ":" + old) + "([\"'])").matcher(text);
        if (!literal.find()) throw new IOException("Cannot locate an unambiguous dependency setting for " + artifact);
        int start = literal.start(), end = literal.end(); String replacement = literal.group(1) + artifact + ":" + target + literal.group(2);
        if (literal.find()) throw new IOException("Multiple dependency literals for " + artifact);
        return text.substring(0, start) + replacement + text.substring(end);
    }
    static String minecraftConstraint(Path jar) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return "";
            var metadata = Json.object(Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)));
            if (!(metadata.get("depends") instanceof Map<?, ?>)) return "";
            Object constraint = Json.object(metadata.get("depends")).get("minecraft");
            return constraint instanceof String value ? value : "";
        }
    }
}