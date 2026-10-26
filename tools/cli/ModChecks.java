import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/**
 * Game-free checks of what a compile cannot see: the resource and metadata files the game reads at
 * start-up. These read the processed resources the build just wrote and the compiled classes they name.
 *
 * A problem fails the command, so a problem is only what the real loader or game rejects: Fabric
 * Loader's strict fabric.mod.json reader and its id rule, FML's id rule, Mixin's lenient config
 * reader and its "required" flag, and Minecraft's (Gson) resource reader. Anything that depends on the
 * game version, on how the jar is assembled, or on files nothing in the game reads is a warning.
 */
final class ModChecks {
    /** Fabric Loader's MetadataVerifier.MOD_ID_PATTERN. */
    static final Pattern FABRIC_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    /** Forge's and NeoForge FML 1-4's ModInfo.VALID_MODID (mods.toml; NeoForge up to 1.20.6). */
    static final Pattern FORGE_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    /** NeoForge FML 5 and later (1.21 on): dotted ids such as "a.b" are valid too. */
    static final Pattern NEOFORGE_ID = Pattern.compile("(?=.{2,64}$)[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    final List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
    final Set<String> ids = new TreeSet<>();
    int json, mixinConfigs, mixinClasses, entrypoints;
    Path root; List<String> checkout; // the mod's checkout, and its files once something is missing

    /** One project's shipped source sets: processed resources, compiled classes, their source folders and the runtime classpath. */
    record Set_(List<Path> resources, List<Path> classes, List<Path> sourceResources, List<Path> sourceJava, List<Path> runtime) {
        Set_(List<Path> resources, List<Path> classes, List<Path> sourceResources, List<Path> sourceJava) { this(resources, classes, sourceResources, sourceJava, List.of()); }
    }
    /** Who reads a JSON file decides how strictly it is read and whether a bad one stops anything. */
    enum Reader { FABRIC, MIXIN, GAME, OTHER }

    static Map<String, Object> run(Map<String, Object> model) throws IOException {
        ModChecks checks = new ModChecks();
        if (model.get("root") != null) checks.root = Path.of(String.valueOf(model.get("root")));
        List<Set_> sets = sets(model);
        for (Set_ set : sets) checks.check(set);
        checks.codeIds(sets);
        checks.translations(sets);
        String summary = checks.json + " JSON file(s), " + checks.mixinConfigs + " mixin config(s) with " + checks.mixinClasses + " mixin class(es), "
                + checks.entrypoints + " entrypoint(s)" + (checks.ids.isEmpty() ? "" : ", mod id " + String.join(", ", checks.ids));
        return Json.map("ok", checks.problems.isEmpty(), "problems", checks.problems, "warnings", checks.warnings, "checked", summary, "mixinClasses", checks.mixinClasses);
    }

    /** Main, client and other shipped source sets; test sets are not part of the mod. */
    static List<Set_> sets(Map<String, Object> model) {
        List<Set_> out = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            Set<Path> classes = new LinkedHashSet<>();
            for (Object c : p.get("compile") instanceof List<?> compile ? compile : List.of()) { Object d = Json.object(c).get("destination"); if (d != null) classes.add(Path.of(d.toString())); }
            // main, client and the other shipped sets end up in one jar (Loom's split client set is the
            // usual case: fabric.mod.json in main names client mixins and entrypoints), so they are read as one.
            List<Path> resources = new ArrayList<>(), classDirs = new ArrayList<>(), sourceResources = new ArrayList<>(), sourceJava = new ArrayList<>();
            Set<Path> runtime = new LinkedHashSet<>();
            for (Object s : p.get("sourceSets") instanceof List<?> sourceSets ? sourceSets : List.of()) {
                var set = Json.object(s); String name = String.valueOf(set.get("name")).toLowerCase(Locale.ROOT);
                if (name.contains("test")) continue;
                for (String path : ProjectModel.strings(set.get("output"))) {
                    Path dir = Path.of(path);
                    boolean classesFolder = dir.toString().replace('\\', '/').contains("/classes/");
                    if (classes.contains(dir) || classesFolder) classDirs.add(dir);
                    // ForgeGradle 7 writes classes and processed resources into one build/sourceSets/main.
                    if (!classesFolder) resources.add(dir);
                }
                for (String path : ProjectModel.strings(set.get("resources"))) sourceResources.add(Path.of(path));
                for (String path : ProjectModel.strings(set.get("java"))) sourceJava.add(Path.of(path));
                // The game finds mixin configs, mixins and entrypoints through the whole classpath: another
                // project of the build (Architectury's common) or a library can supply them.
                for (String path : ProjectModel.strings(set.get("runtime"))) runtime.add(Path.of(path));
            }
            if (!resources.isEmpty() || !classDirs.isEmpty()) out.add(new Set_(resources, classDirs, sourceResources, sourceJava, List.copyOf(runtime)));
        }
        return out;
    }

    /** The file a person edits: the source copy of a processed resource when one exists. */
    static Path source(Set_ set, Path outputRoot, Path file) {
        Path relative = outputRoot.relativize(file);
        for (Path dir : set.sourceResources()) if (Files.isRegularFile(dir.resolve(relative))) return dir.resolve(relative);
        return file;
    }

    /** Minecraft reads pack.mcmeta and everything under assets/NAMESPACE/ and data/NAMESPACE/; nothing else in a jar. */
    static Reader reader(String relative, Set<String> mixinConfigs) {
        String path = relative.replace('\\', '/');
        if (path.equals("fabric.mod.json")) return Reader.FABRIC;
        if (mixinConfigs.contains(path)) return Reader.MIXIN;
        if (path.equals("pack.mcmeta")) return Reader.GAME;
        String[] parts = path.split("/");
        if (parts.length >= 3 && (parts[0].equals("assets") || parts[0].equals("data"))) return Reader.GAME;
        return Reader.OTHER;
    }

    /** Reads one JSON file as its reader would; problems and warnings are added here. Returns the value, or null when the reader cannot use it. */
    Object readJson(Path shown, String text, Reader reader) {
        Gson strict = new Gson(text, false);
        try {
            Object value = strict.document();
            if (!strict.duplicates.isEmpty() && reader != Reader.OTHER)
                warnings.add(shown + ": duplicate key " + String.join(", ", new TreeSet<>(strict.duplicates).stream().map(k -> "\"" + k + "\"").toList()) + "; only the last one is used.");
            return value;
        } catch (IllegalArgumentException notStrict) {
            if (text.strip().replace("\uFEFF", "").isEmpty()) {
                if (reader == Reader.FABRIC) problems.add(shown + ": the file is empty. Fabric Loader refuses to load the mod; it needs a JSON object with at least \"schemaVersion\", \"id\" and \"version\".");
                else if (reader != Reader.OTHER) warnings.add(shown + ": the file is empty, so " + (reader == Reader.MIXIN ? "Mixin reads no config from it" : "the game reads nothing from it (most resources log an error)") + ".");
                return null;
            }
            Gson lenient = new Gson(text, true);
            Object value;
            try { value = lenient.document(); }
            catch (IllegalArgumentException invalid) {
                switch (reader) {
                    case FABRIC -> problems.add(shown + ": invalid JSON, " + invalid.getMessage() + ". Fabric Loader refuses to load the mod; fix the syntax.");
                    case MIXIN -> problems.add(shown + ": invalid JSON, " + invalid.getMessage() + ". Mixin cannot read this config: it logs an error and none of its mixins apply; fix the syntax.");
                    case GAME -> problems.add(shown + ": invalid JSON, " + invalid.getMessage() + ". Minecraft cannot read it even leniently, so it logs an error and skips the file; fix the syntax.");
                    case OTHER -> warnings.add(shown + ": invalid JSON, " + invalid.getMessage() + ". Minecraft and the mod loader never read this file, so it cannot stop the mod; fix it if a JSON reader reads it (a JSON5 or HJSON reader may be fine).");
                }
                return null;
            }
            switch (reader) {
                case FABRIC -> { problems.add(shown + ": " + notStrict.getMessage() + ". Fabric Loader reads fabric.mod.json as strict JSON and refuses to load the mod; make it plain JSON (no comments, single quotes or trailing commas)."); return null; }
                case GAME -> warnings.add(shown + ": not strict JSON, " + notStrict.getMessage() + ". Minecraft reads most resources leniently, but some versions read some files (models, older data packs) strictly; plain JSON is safe everywhere.");
                default -> {} // Mixin's Gson reads configs leniently; files the game never reads are someone else's format.
            }
            return value;
        }
    }

    void check(Set_ set) throws IOException {
        for (Path root : set.resources()) {
            if (!Files.isDirectory(root)) continue;
            // Metadata first: it names the mixin configs, which are read by Mixin rather than the game.
            // A project runs on one loader; metadata for another loader (a multi-loader jar's spare
            // fabric.mod.json or mods.toml) is not read in this build, so it is only read as JSON here.
            Map<String, Path> configs = new LinkedHashMap<>(); // config -> the metadata file that lists it
            boolean fabricLoader = set.runtime().stream().anyMatch(p -> p.getFileName() != null && p.getFileName().toString().startsWith("fabric-loader"));
            boolean readsFabric = set.runtime().isEmpty() || fabricLoader, readsToml = set.runtime().isEmpty() || !fabricLoader;
            Path fabric = root.resolve("fabric.mod.json");
            if (Files.isRegularFile(fabric) && readsFabric) fabric(set, root, fabric, configs);
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                Path toml = root.resolve(name);
                if (Files.isRegularFile(toml) && readsToml) forge(set, root, toml, configs);
            }
            List<Path> files;
            try (var walk = Files.walk(root)) { files = walk.filter(Files::isRegularFile).filter(f -> { String n = f.getFileName().toString(); return n.endsWith(".json") || n.endsWith(".mcmeta"); }).sorted().toList(); }
            for (Path file : files) {
                if (Files.size(file) > 8_000_000) continue;
                json++;
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.equals("fabric.mod.json") && readsFabric) continue; // read above
                Reader reader = relative.equals("fabric.mod.json") ? Reader.OTHER : reader(relative, configs.keySet());
                readJson(source(set, root, file), new String(Files.readAllBytes(file), StandardCharsets.UTF_8), reader);
            }
            for (var config : configs.entrySet()) mixinConfig(set, config.getValue(), config.getKey());
        }
    }

    void fabric(Set_ set, Path root, Path file, Map<String, Path> configs) throws IOException {
        Path shown = source(set, root, file);
        if (!(readJson(shown, Files.readString(file, StandardCharsets.UTF_8), Reader.FABRIC) instanceof Map<?, ?> read)) {
            if (problems.stream().noneMatch(p -> p.startsWith(shown + ":"))) problems.add(shown + ": the top level is not a JSON object. Fabric Loader refuses to load the mod.");
            return;
        }
        @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) read;
        Object id = manifest.get("id");
        if (!(id instanceof String text) || text.isBlank()) problems.add(shown + ": no \"id\". Fabric refuses to load a mod without one; add \"id\": \"yourmod\".");
        else if (!FABRIC_ID.matcher(text).matches()) problems.add(shown + ": mod id \"" + text + "\" is not valid. Fabric ids are 2-64 characters of lowercase letters, digits, '-' or '_', starting with a letter.");
        else ids.add(text);
        if (manifest.get("mixins") instanceof List<?> mixins) for (Object entry : mixins) {
            String config = entry instanceof String s ? s : entry instanceof Map<?, ?> m && m.get("config") instanceof String s ? s : null;
            if (config != null) configs.putIfAbsent(config, shown);
        }
        if (manifest.get("entrypoints") instanceof Map<?, ?> points) for (var point : points.entrySet()) {
            if (!(point.getValue() instanceof List<?> list)) continue;
            for (Object entry : list) {
                String value = entry instanceof String s ? s : entry instanceof Map<?, ?> m && m.get("value") instanceof String s ? s : null;
                if (value == null) continue;
                String adapter = entry instanceof Map<?, ?> m && m.get("adapter") instanceof String a ? a : "default";
                entrypoints++;
                String className = value.replaceFirst("::.*$", "");
                if (onClasspath(set, className.replace('.', '/') + ".class")) continue;
                // Only Fabric's own adapter is certain to want a class of exactly that name; a library's
                // class may come in some other way (jar-in-jar), and other adapters read the value themselves.
                if (adapter.equals("default") && ownPackage(set, className))
                    missing(set, shown + ": the \"" + point.getKey() + "\" entrypoint " + value + " names no compiled class. Fabric stops at start-up with \"ClassNotFoundException\"; fix the class name.", className.replace('.', '/') + ".class");
                else warnings.add(shown + ": the \"" + point.getKey() + "\" entrypoint " + value + (adapter.equals("default") ? "" : " (adapter \"" + adapter + "\")")
                        + " names no class in this build or on its runtime classpath; Fabric stops at start-up if nothing else provides it.");
            }
        }
        if (manifest.get("accessWidener") instanceof String widener && !onClasspath(set, widener))
            missing(set, shown + ": accessWidener \"" + widener + "\" is not in the resources or on the runtime classpath. Fabric Loader stops at start-up on a missing access widener.", widener);
    }

    void forge(Set_ set, Path root, Path file, Map<String, Path> configs) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path shown = source(set, root, file);
        boolean neo = file.getFileName().toString().equals("neoforge.mods.toml");
        // Each [[mods]] block names one of this jar's mods; modId lines in [[dependencies]] blocks are other mods.
        // TOML strings may use double quotes or single (literal) quotes.
        Pattern modId = Pattern.compile("(?m)^\\s*modId\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
        for (String block : text.split("(?m)^\\s*\\[\\[")) {
            if (!block.startsWith("mods]]")) continue;
            Matcher id = modId.matcher(block);
            if (!id.find()) continue;
            String value = id.group(1) != null ? id.group(1) : id.group(2);
            if (value.contains("${")) continue;
            if (FORGE_ID.matcher(value).matches()) ids.add(value);
            else if (neo && NEOFORGE_ID.matcher(value).matches()) {
                ids.add(value);
                warnings.add(shown + ": mod id \"" + value + "\" has a '.'. NeoForge accepts that from 1.21 (FML 5) on, but NeoForge 1.20.5-1.20.6 and Forge refuse it.");
            } else problems.add(shown + ": mod id \"" + value + "\" is not valid. " + (neo ? "NeoForge ids are 2-64 characters of lowercase letters, digits, '_' (and from 1.21, '.' between parts), starting with a letter."
                    : "Forge ids are 2-64 characters of lowercase letters, digits or '_', starting with a letter."));
        }
        Matcher mixin = Pattern.compile("(?ms)^\\s*\\[\\[mixins]]\\s*\\R\\s*config\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')").matcher(text);
        while (mixin.find()) { String config = mixin.group(1) != null ? mixin.group(1) : mixin.group(2); if (!config.contains("${")) configs.putIfAbsent(config, shown); }
    }

    void mixinConfig(Set_ set, Path owner, String config) throws IOException {
        Path file = null, shown = null;
        for (Path root : set.resources()) if (Files.isRegularFile(root.resolve(config))) { file = root.resolve(config); shown = source(set, root, file); break; }
        if (file == null) {
            // Mixin looks the config up on the whole classpath: Architectury's fabric names common's config.
            if (onClasspath(set, config)) { mixinConfigs++; return; }
            missing(set, owner + ": lists mixin config \"" + config + "\", but no such file is in the resources or on the runtime classpath. Mixin logs an error and none of its mixins apply; fix the name or add the file.", config);
            return;
        }
        mixinConfigs++;
        Object read;
        try { read = Gson.parse(Files.readString(file, StandardCharsets.UTF_8), true); }
        catch (IllegalArgumentException invalid) { return; } // already reported as invalid JSON
        if (!(read instanceof Map<?, ?> mixins)) return;
        String pkg = mixins.get("package") instanceof String s ? s : "";
        boolean required = Boolean.TRUE.equals(mixins.get("required"));
        for (String side : List.of("mixins", "client", "server")) {
            if (!(mixins.get(side) instanceof List<?> names)) continue;
            for (Object name : names) {
                if (!(name instanceof String simple)) continue;
                mixinClasses++;
                String className = pkg.isEmpty() ? simple : pkg + "." + simple;
                if (onClasspath(set, className.replace('.', '/') + ".class") || set.classes().stream().noneMatch(Files::isDirectory)) continue;
                // Mixin's MixinProcessor: a broken mixin in a "required": true config is an error that stops
                // the game; in any other config it is a warning and the mixin is skipped.
                String what = shown + ": lists mixin \"" + simple + "\" (" + side + "), but there is no compiled class " + className + ". ";
                if (required) missing(set, what + "The config is \"required\": true, so Mixin stops the game at start-up; fix the name, or move the class into package " + (pkg.isEmpty() ? "(none)" : pkg) + ".", className.replace('.', '/') + ".class");
                else warnings.add(what + "The config is not \"required\", so Mixin logs a warning and that mixin never applies.");
            }
        }
    }

    /** A class or resource the game would find: this project's output, then its runtime classpath (folders and jars). */
    boolean onClasspath(Set_ set, String entry) {
        for (List<Path> dirs : List.of(set.classes(), set.resources())) for (Path dir : dirs) if (Files.isRegularFile(dir.resolve(entry))) return true;
        for (Path path : set.runtime()) {
            if (Files.isDirectory(path)) { if (Files.isRegularFile(path.resolve(entry))) return true; }
            else if (path.getFileName() != null && path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) { if (zip.getEntry(entry) != null) return true; }
                catch (IOException unreadable) { /* not a jar we can read; the game could not either */ }
            }
            // Another project's classes folder on the classpath: its resources and sources sit beside it by
            // Gradle's layout, and are bundled into the jar with it (Sodium's common and frapi, unbuilt at compile time).
            Matcher layout = CLASSES.matcher(path.toString().replace('\\', '/'));
            if (!layout.matches()) continue;
            Path project = Path.of(layout.group(1)); String setName = layout.group(2);
            if (Files.isRegularFile(project.resolve("build/resources/" + setName).resolve(entry)) || Files.isRegularFile(project.resolve("src/" + setName + "/resources").resolve(entry))) return true;
            if (entry.endsWith(".class")) {
                String source = entry.substring(0, entry.length() - 6).replaceFirst("\\$.*$", "");
                for (String language : List.of("java", "kotlin"))
                    if (Files.isRegularFile(project.resolve("src/" + setName + "/" + language).resolve(source + (language.equals("java") ? ".java" : ".kt")))) return true;
            }
        }
        return false;
    }
    static final Pattern CLASSES = Pattern.compile("(.*)/build/classes/(?:java|kotlin|groovy|scala)/([^/]+)/?");

    /**
     * Something the loader needs and cannot find. Jar assembly can pull files from other projects in ways
     * the model does not show (shadow, jar-in-jar, custom configurations: Sodium's frapi), so it is a
     * problem only when nothing in the checkout could supply it; otherwise a warning saying why.
     */
    void missing(Set_ set, String problem, String entry) throws IOException {
        Optional<Path> unbuilt = set.runtime().stream().filter(p -> !Files.exists(p)).findFirst();
        Optional<Path> elsewhere = elsewhere(entry);
        if (elsewhere.isPresent()) warnings.add(problem + " (Not certain: " + elsewhere.get() + " is in this checkout, and the build may bundle it from there.)");
        else if (unbuilt.isPresent()) warnings.add(problem + " (Not certain: " + unbuilt.get() + " is on the runtime classpath but not built yet, and may hold it.)");
        else problems.add(problem);
    }
    /** A resource, or a class's source file, anywhere in the checkout (outside tool folders). */
    Optional<Path> elsewhere(String entry) throws IOException {
        if (root == null || !Files.isDirectory(root)) return Optional.empty();
        if (checkout == null) {
            Set<String> skip = Set.of(".git", ".gradle", ".modaccel", ".runs", "node_modules", "run", "runs", ".idea");
            List<String> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return !dir.equals(root) && skip.contains(String.valueOf(dir.getFileName())) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) { files.add(file.toString().replace('\\', '/')); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failed) { return FileVisitResult.CONTINUE; }
            });
            checkout = files;
        }
        List<String> wanted = new ArrayList<>();
        if (entry.endsWith(".class")) {
            String source = entry.substring(0, entry.length() - 6).replaceFirst("\\$.*$", "");
            wanted.add("/" + source + ".java"); wanted.add("/" + source + ".kt"); wanted.add("/" + entry);
        } else wanted.add("/" + entry);
        for (String file : checkout) for (String end : wanted) if (file.endsWith(end)) return Optional.of(Path.of(file));
        return Optional.empty();
    }
    /** An entrypoint in a package this mod compiles is the mod's own; others may come from a library. */
    boolean ownPackage(Set_ set, String className) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) return true;
        String pkg = className.substring(0, dot).replace('.', '/');
        for (Path dir : set.classes()) if (Files.isDirectory(dir.resolve(pkg))) return true;
        return false;
    }

    /**
     * A MOD_ID constant or @Mod annotation that disagrees with the metadata. Only a warning: no loader
     * reads a constant, compat code keeps other mods' ids in MODID fields, and a multi-mod jar has several.
     */
    void codeIds(List<Set_> sets) throws IOException {
        if (ids.isEmpty()) return;
        Pattern constant = Pattern.compile("\\b(?:MOD_?ID|MODID)\\s*=\\s*\"([^\"]+)\"|@Mod\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
        Set<Path> seen = new HashSet<>();
        for (Set_ set : sets) for (Path dir : set.sourceJava()) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (var walk = Files.walk(dir)) { files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList(); }
            for (Path file : files) {
                if (!seen.add(file.toAbsolutePath().normalize())) continue;
                String text = blankComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher match = constant.matcher(text);
                while (match.find()) {
                    String id = match.group(1) != null ? match.group(1) : match.group(2);
                    // NeoForge ids cannot hold '-', so a shim for it spells the Fabric id "a-b" as "a_b": the same mod.
                    if (ids.contains(id) || ids.stream().anyMatch(known -> known.replace('-', '_').equals(id.replace('-', '_')))) continue;
                    int line = 1; for (int i = 0; i < match.start(); i++) if (text.charAt(i) == '\n') line++;
                    warnings.add(file + ":" + line + ": the code says the mod id is \"" + id + "\", but the mod metadata says " + String.join(", ", ids.stream().map(i -> "\"" + i + "\"").toList())
                            + ". If this is this mod's own id, textures, translations, recipes and registry names are looked up under the id the code uses; make them match.");
                }
            }
        }
    }

    /** Items a recipe makes or that have a model: each needs an en_us name, or the game shows the raw key. */
    void translations(List<Set_> sets) throws IOException {
        for (String id : ids) {
            Map<String, String> items = new TreeMap<>(); // path -> where it was seen
            Set<String> blocks = new TreeSet<>(), models = new TreeSet<>();
            Map<?, ?> lang = null; Path langFile = null;
            for (Set_ set : sets) for (Path root : set.resources()) {
                Path assets = root.resolve("assets").resolve(id);
                for (String folder : List.of("recipe", "recipes")) {
                    Path dir = root.resolve("data").resolve(id).resolve(folder);
                    if (!Files.isDirectory(dir)) continue;
                    try (var walk = Files.walk(dir)) {
                        for (Path file : walk.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                            Object result;
                            try { result = Gson.parse(Files.readString(file, StandardCharsets.UTF_8), true) instanceof Map<?, ?> recipe ? recipe.get("result") : null; }
                            catch (IllegalArgumentException invalid) { continue; }
                            String made = result instanceof String s ? s : result instanceof Map<?, ?> m ? (m.get("id") instanceof String s ? s : m.get("item") instanceof String s2 ? s2 : null) : null;
                            if (made != null && made.startsWith(id + ":")) items.putIfAbsent(made.substring(id.length() + 1), source(set, root, file).toString());
                        }
                    }
                }
                for (String folder : List.of("items", "models/item")) {
                    Path dir = assets.resolve(folder);
                    if (!Files.isDirectory(dir)) continue;
                    try (var list = Files.list(dir)) { list.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> models.add(n.substring(0, n.length() - 5))); }
                }
                Path states = assets.resolve("blockstates");
                if (Files.isDirectory(states)) try (var list = Files.list(states)) { list.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> blocks.add(n.substring(0, n.length() - 5))); }
                Path en = assets.resolve("lang/en_us.json");
                if (lang == null && Files.isRegularFile(en)) {
                    try { lang = Gson.parse(Files.readString(en, StandardCharsets.UTF_8), true) instanceof Map<?, ?> m ? m : Map.of(); }
                    catch (IllegalArgumentException invalid) { lang = Map.of(); }
                    langFile = source(set, root, en);
                }
            }
            for (var item : items.entrySet()) {
                String name = item.getKey();
                if (name.contains("/")) continue;
                if (!models.contains(name) && !blocks.contains(name))
                    warnings.add(id + ":" + name + " is made by " + item.getValue() + " but has no item model (assets/" + id + "/items/" + name + ".json or assets/" + id + "/models/item/" + name + ".json); the game draws it as the missing-texture cube.");
            }
            Set<String> named = new TreeSet<>(items.keySet()); named.addAll(blocks);
            for (String name : named) {
                if (name.contains("/")) continue;
                String item = "item." + id + "." + name, block = "block." + id + "." + name;
                if (lang == null) { warnings.add(id + ":" + name + " has no translation: there is no assets/" + id + "/lang/en_us.json, so the game shows the raw key " + (blocks.contains(name) ? block : item) + "."); continue; }
                if (!lang.containsKey(item) && !lang.containsKey(block))
                    warnings.add(langFile + ": no \"" + (blocks.contains(name) ? block : item) + "\" entry, so the game shows that raw key as the name of " + id + ":" + name + ".");
            }
        }
    }

    /** Java comments become spaces: a MOD_ID in a commented-out block is not the code's id. */
    static String blankComments(String text) {
        StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '"') { for (i++; i < out.length() && out.charAt(i) != '"' && out.charAt(i) != '\n'; i++) if (out.charAt(i) == '\\') i++; continue; }
            if (c == '\'') { for (i++; i < out.length() && out.charAt(i) != '\'' && out.charAt(i) != '\n'; i++) if (out.charAt(i) == '\\') i++; continue; }
            if (c == '/' && i + 1 < out.length() && out.charAt(i + 1) == '/') { while (i < out.length() && out.charAt(i) != '\n') out.setCharAt(i++, ' '); }
            else if (c == '/' && i + 1 < out.length() && out.charAt(i + 1) == '*') {
                int end = out.indexOf("*/", i + 2); end = end < 0 ? out.length() : end + 2;
                for (; i < end; i++) if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
                i--;
            }
        }
        return out.toString();
    }

    /**
     * JSON read the way Gson's JsonReader reads it, the reader behind Fabric Loader (its own copy, strict),
     * Mixin's configs and Minecraft's resources (lenient in most places). Strict here is Gson's legacy
     * strictness, not RFC 8259: a byte order mark is skipped, raw control characters inside strings,
     * {@code \'} and upper-case TRUE/FALSE/NULL are accepted. Lenient adds what setLenient(true) adds:
     * {@code //}, {@code #} and block comments, single-quoted and unquoted strings and names, '=' or '=>'
     * after a name, ';' between entries, and empty array slots ({@code [1,]} reads as [1, null]).
     * A trailing comma in an object is an error in both modes, as in Gson. Duplicate keys keep the last
     * value (JsonObject) and are collected in {@link #duplicates}.
     */
    static final class Gson {
        final String text; final boolean lenient; int at;
        final List<String> duplicates = new ArrayList<>();
        Gson(String text, boolean lenient) { this.text = text; this.lenient = lenient; if (text.startsWith("﻿")) at = 1; }

        /** The value, or IllegalArgumentException("line L, column C: what"). An empty file is an error here; callers decide. */
        static Object parse(String text, boolean lenient) { return new Gson(text, lenient).document(); }

        Object document() {
            try {
                if (next() < 0) throw error("the file is empty");
                Object value = value();
                if (next() >= 0) throw error("more text after the end of the JSON value");
                return value;
            } catch (IllegalStateException wrapped) { throw error(wrapped.getMessage()); }
        }
        IllegalArgumentException error(String what) {
            int line = 1, column = 1;
            for (int i = 0; i < Math.min(at, text.length()); i++) { if (text.charAt(i) == '\n') { line++; column = 1; } else column++; }
            return new IllegalArgumentException("line " + line + ", column " + column + ": " + what);
        }
        /** The next character that is not whitespace or (lenient) a comment, without taking it; -1 at the end. */
        int next() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { at++; continue; }
                if (c == '#' || c == '/' && at + 1 < text.length() && (text.charAt(at + 1) == '/' || text.charAt(at + 1) == '*')) {
                    if (!lenient) throw error("a comment (" + (c == '#' ? "#" : text.substring(at, at + 2)) + "); strict JSON has none");
                    if (c == '/' && text.charAt(at + 1) == '*') {
                        int end = text.indexOf("*/", at + 2);
                        if (end < 0) throw error("a /* comment that never ends");
                        at = end + 2;
                    } else { while (at < text.length() && text.charAt(at) != '\n' && text.charAt(at) != '\r') at++; }
                    continue;
                }
                return c;
            }
            return -1;
        }
        Object value() {
            int c = next();
            if (c < 0) throw error("the file ends where a value should be");
            if (c == '{') { at++; return object(); }
            if (c == '[') { at++; return array(); }
            if (c == '"') { at++; return string('"'); }
            if (c == '\'') { if (!lenient) throw error("a single-quoted string; strict JSON uses double quotes"); at++; return string('\''); }
            String literal = literal();
            if (literal.isEmpty()) throw error("expected a value, found '" + (char)c + "'");
            for (String keyword : List.of("true", "false", "null")) if (literal.length() == keyword.length() && sameLetters(literal, keyword))
                return keyword.equals("null") ? null : Boolean.valueOf(keyword);
            if (literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                try { return Long.valueOf(literal); } catch (NumberFormatException wide) { return Double.valueOf(literal); }
            if (!lenient) { at -= literal.length(); throw error("\"" + literal + "\" is not a JSON value (strings need quotes)"); }
            return literal;
        }
        /** Gson matches each keyword letter in either case: TRUE and True are true. */
        static boolean sameLetters(String literal, String keyword) {
            for (int i = 0; i < keyword.length(); i++) if (literal.charAt(i) != keyword.charAt(i) && literal.charAt(i) != Character.toUpperCase(keyword.charAt(i))) return false;
            return true;
        }
        /** Gson's unquoted literal: everything up to a character JSON gives a meaning to. */
        String literal() {
            int start = at;
            while (at < text.length() && "/\\;#={}[]:, \t\f\r\n\"'".indexOf(text.charAt(at)) < 0) at++;
            return text.substring(start, at);
        }
        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (next() == '}') { at++; return map; }
            while (true) {
                int c = next(); String key;
                if (c == '}') throw error("a comma before '}' (Gson rejects a trailing comma in an object, even when lenient)");
                if (c == '"') { at++; key = string('"'); }
                else if (c == '\'' && lenient) { at++; key = string('\''); }
                else if (lenient && c >= 0 && (key = literal()).length() > 0) {}
                else throw error(c < 0 ? "the file ends inside an object" : "expected a quoted name, found '" + (char)c + "'");
                c = next();
                if (c == ':') at++;
                else if (lenient && c == '=') { at++; if (at < text.length() && text.charAt(at) == '>') at++; }
                else throw error("expected ':' after the name \"" + key + "\"");
                if (map.containsKey(key)) duplicates.add(key);
                map.put(key, value());
                c = next();
                if (c == '}') { at++; return map; }
                if (c == ',' || lenient && c == ';') { at++; continue; }
                throw error(c < 0 ? "the file ends inside an object" : "expected ',' or '}' after the value of \"" + key + "\"");
            }
        }
        List<Object> array() {
            List<Object> list = new ArrayList<>();
            if (next() == ']') { at++; return list; }
            while (true) {
                int c = next();
                if (c == ',' || c == ';' || c == ']') {
                    // Lenient Gson reads an empty slot as null: [1,] is [1, null] and [,1] is [null, 1].
                    if (!lenient) throw error(c == ']' ? "a comma before ']' (strict JSON has no trailing comma)" : "an empty slot in a list");
                    list.add(null);
                } else list.add(value());
                c = next();
                if (c == ']') { at++; return list; }
                if (c == ',' || lenient && c == ';') { at++; if (lenient && next() == ']') { list.add(null); at++; return list; } continue; }
                throw error(c < 0 ? "the file ends inside a list" : "expected ',' or ']' in a list");
            }
        }
        String string(char quote) {
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == quote) return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (at >= text.length()) break;
                char e = text.charAt(at++);
                switch (e) {
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error("a \\u escape cut short");
                        try { out.append((char)Integer.parseInt(text.substring(at, at + 4), 16)); } catch (NumberFormatException bad) { throw error("a \\u escape that is not four hex digits"); }
                        at += 4;
                    }
                    case 't' -> out.append('\t'); case 'b' -> out.append('\b'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 'f' -> out.append('\f');
                    case '\n', '\'', '"', '\\', '/' -> out.append(e);
                    default -> { at--; throw error("the escape \\" + e + " is not valid JSON"); }
                }
            }
            throw error("a string that never ends");
        }
    }

    /** Problems, then warnings, as lines a person reads; empty when there is nothing to say. */
    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        List<Object> problems = Json.array(result.get("problems")), warnings = Json.array(result.get("warnings"));
        if (!problems.isEmpty()) {
            out.append(problems.size()).append(problems.size() == 1 ? " problem" : " problems").append(" the game would hit at start-up:");
            for (Object problem : problems) out.append("\n  ").append(problem);
        }
        if (!warnings.isEmpty()) {
            if (out.length() > 0) out.append('\n');
            out.append(warnings.size()).append(warnings.size() == 1 ? " warning:" : " warnings:");
            for (Object warning : warnings) out.append("\n  ").append(warning);
        }
        return out.toString();
    }
}
