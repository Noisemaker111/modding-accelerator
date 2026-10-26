import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Game-free checks of what a compile cannot see: the resource and metadata files the game reads at
 * start-up. A broken recipe or fabric.mod.json compiles fine and only fails (or is silently skipped)
 * inside the game, so these read the processed resources the build just wrote and the compiled
 * classes they name. Problems fail the command; warnings are printed and kept in the receipt.
 */
final class ModChecks {
    static final Pattern FABRIC_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    static final Pattern FORGE_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    final List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
    final Set<String> ids = new TreeSet<>();
    int json, mixinConfigs, mixinClasses, entrypoints;

    /** One source set's processed resources, compiled classes and the source folders they came from. */
    record Set_(List<Path> resources, List<Path> classes, List<Path> sourceResources, List<Path> sourceJava) {}

    static Map<String, Object> run(Map<String, Object> model) throws IOException {
        ModChecks checks = new ModChecks();
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
            for (Object s : p.get("sourceSets") instanceof List<?> sourceSets ? sourceSets : List.of()) {
                var set = Json.object(s); String name = String.valueOf(set.get("name")).toLowerCase(Locale.ROOT);
                if (name.contains("test")) continue;
                for (String path : ProjectModel.strings(set.get("output"))) {
                    Path dir = Path.of(path);
                    if (classes.contains(dir) || dir.toString().replace('\\', '/').contains("/classes/")) classDirs.add(dir); else resources.add(dir);
                }
                for (String path : ProjectModel.strings(set.get("resources"))) sourceResources.add(Path.of(path));
                for (String path : ProjectModel.strings(set.get("java"))) sourceJava.add(Path.of(path));
            }
            if (!resources.isEmpty() || !classDirs.isEmpty()) out.add(new Set_(resources, classDirs, sourceResources, sourceJava));
        }
        return out;
    }

    /** The file a person edits: the source copy of a processed resource when one exists. */
    static Path source(Set_ set, Path outputRoot, Path file) {
        Path relative = outputRoot.relativize(file);
        for (Path dir : set.sourceResources()) if (Files.isRegularFile(dir.resolve(relative))) return dir.resolve(relative);
        return file;
    }

    void check(Set_ set) throws IOException {
        for (Path root : set.resources()) {
            if (!Files.isDirectory(root)) continue;
            List<Path> files;
            try (var walk = Files.walk(root)) { files = walk.filter(Files::isRegularFile).filter(f -> { String n = f.getFileName().toString(); return n.endsWith(".json") || n.endsWith(".mcmeta"); }).sorted().toList(); }
            for (Path file : files) {
                if (Files.size(file) > 8_000_000) continue;
                json++;
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                try { Json.parseReporting(withoutComments(text)); }
                catch (IllegalArgumentException invalid) {
                    String message = invalid.getMessage();
                    if (message.contains("Duplicate JSON key")) warnings.add(source(set, root, file) + ": " + message + " (the game keeps the last one)");
                    else problems.add(source(set, root, file) + ": invalid JSON, " + message + ". The game skips or rejects this file; fix the syntax.");
                }
            }
            Path fabric = root.resolve("fabric.mod.json");
            if (Files.isRegularFile(fabric)) fabric(set, root, fabric);
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                Path toml = root.resolve(name);
                if (Files.isRegularFile(toml)) forge(set, root, toml);
            }
        }
    }

    void fabric(Set_ set, Path root, Path file) throws IOException {
        Map<String, Object> manifest;
        try { manifest = Json.object(Json.parseReporting(withoutComments(Files.readString(file, StandardCharsets.UTF_8)))); }
        catch (IllegalArgumentException invalid) { return; } // already reported as invalid JSON
        Path shown = source(set, root, file);
        Object id = manifest.get("id");
        if (!(id instanceof String text) || text.isBlank()) problems.add(shown + ": no \"id\". Fabric refuses to load a mod without one; add \"id\": \"yourmod\".");
        else if (!FABRIC_ID.matcher(text).matches()) problems.add(shown + ": mod id \"" + text + "\" is not valid. Fabric ids are 2-64 characters of lowercase letters, digits, '-' or '_', starting with a letter.");
        else ids.add(text);
        if (manifest.get("mixins") instanceof List<?> mixins) for (Object entry : mixins) {
            String config = entry instanceof String s ? s : entry instanceof Map<?, ?> m && m.get("config") instanceof String s ? s : null;
            if (config != null) mixinConfig(set, shown, config);
        }
        if (manifest.get("entrypoints") instanceof Map<?, ?> points) for (var point : points.entrySet()) {
            if (!(point.getValue() instanceof List<?> list)) continue;
            for (Object entry : list) {
                String value = entry instanceof String s ? s : entry instanceof Map<?, ?> m && m.get("value") instanceof String s ? s : null;
                if (value == null) continue;
                entrypoints++;
                String className = value.replaceFirst("::.*$", "");
                if (!compiled(set, className) && ownPackage(set, className))
                    problems.add(shown + ": the \"" + point.getKey() + "\" entrypoint " + value + " names no compiled class. Fabric stops at start-up with \"ClassNotFoundException\"; fix the class name.");
            }
        }
        if (manifest.get("accessWidener") instanceof String widener && !resource(set, widener))
            problems.add(shown + ": accessWidener \"" + widener + "\" is not in the resources. Loom and the game both stop on a missing access widener.");
    }

    void forge(Set_ set, Path root, Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path shown = source(set, root, file);
        // Each [[mods]] block names one of this jar's mods; modId lines in [[dependencies]] blocks are other mods.
        Pattern modId = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]*)\"");
        for (String block : text.split("(?m)^\\s*\\[\\[")) {
            if (!block.startsWith("mods]]")) continue;
            Matcher id = modId.matcher(block);
            if (!id.find() || id.group(1).contains("${")) continue;
            String value = id.group(1);
            if (!FORGE_ID.matcher(value).matches()) problems.add(shown + ": mod id \"" + value + "\" is not valid. NeoForge and Forge ids are 2-64 characters of lowercase letters, digits or '_', starting with a letter.");
            else ids.add(value);
        }
        Matcher mixin = Pattern.compile("(?ms)^\\s*\\[\\[mixins]]\\s*\\R\\s*config\\s*=\\s*\"([^\"]+)\"").matcher(text);
        while (mixin.find()) mixinConfig(set, shown, mixin.group(1));
    }

    void mixinConfig(Set_ set, Path owner, String config) throws IOException {
        Path file = null;
        for (Path root : set.resources()) if (Files.isRegularFile(root.resolve(config))) { file = root.resolve(config); break; }
        if (file == null) { problems.add(owner + ": lists mixin config \"" + config + "\", but no such file is in the resources. The game stops at start-up; fix the name or add the file."); return; }
        mixinConfigs++;
        Map<String, Object> mixins;
        try { mixins = Json.object(Json.parseReporting(withoutComments(Files.readString(file, StandardCharsets.UTF_8)))); }
        catch (IllegalArgumentException invalid) { return; }
        String pkg = mixins.get("package") instanceof String s ? s : "";
        Path shown = null;
        for (Path root : set.resources()) if (Files.isRegularFile(root.resolve(config))) { shown = source(set, root, root.resolve(config)); break; }
        for (String side : List.of("mixins", "client", "server")) {
            if (!(mixins.get(side) instanceof List<?> names)) continue;
            for (Object name : names) {
                if (!(name instanceof String simple)) continue;
                mixinClasses++;
                String className = pkg.isEmpty() ? simple : pkg + "." + simple;
                if (!compiled(set, className) && set.classes().stream().anyMatch(Files::isDirectory))
                    problems.add(shown + ": lists mixin \"" + simple + "\" (" + side + "), but there is no compiled class " + className
                            + ". Mixin stops the game at start-up; fix the name, or move the class into package " + (pkg.isEmpty() ? "(none)" : pkg) + ".");
            }
        }
    }

    boolean compiled(Set_ set, String className) {
        String path = className.replace('.', '/') + ".class";
        for (Path dir : set.classes()) if (Files.isRegularFile(dir.resolve(path))) return true;
        return false;
    }
    /** An entrypoint in a package this mod compiles is the mod's own; others may come from a library. */
    boolean ownPackage(Set_ set, String className) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) return true;
        String pkg = className.substring(0, dot).replace('.', '/');
        for (Path dir : set.classes()) if (Files.isDirectory(dir.resolve(pkg))) return true;
        return false;
    }
    boolean resource(Set_ set, String name) {
        for (Path root : set.resources()) if (Files.isRegularFile(root.resolve(name))) return true;
        return false;
    }

    /** A MOD_ID constant or @Mod annotation that disagrees with the metadata loads assets and data under the wrong namespace. */
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
                    problems.add(file + ":" + line + ": the code says the mod id is \"" + id + "\", but the mod metadata says " + String.join(", ", ids.stream().map(i -> "\"" + i + "\"").toList())
                            + ". Textures, translations, recipes and registry names are looked up under the id the code uses; make them match.");
                }
            }
        }
    }

    /** Items a recipe makes or that have a model: each needs an en_us name, or the game shows the raw key. */
    void translations(List<Set_> sets) throws IOException {
        for (String id : ids) {
            Map<String, String> items = new TreeMap<>(); // path -> where it was seen
            Set<String> blocks = new TreeSet<>(), models = new TreeSet<>();
            Map<String, Object> lang = null; Path langFile = null;
            for (Set_ set : sets) for (Path root : set.resources()) {
                Path assets = root.resolve("assets").resolve(id);
                for (String folder : List.of("recipe", "recipes")) {
                    Path dir = root.resolve("data").resolve(id).resolve(folder);
                    if (!Files.isDirectory(dir)) continue;
                    try (var walk = Files.walk(dir)) {
                        for (Path file : walk.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                            Object result;
                            try { result = Json.object(Json.parseReporting(withoutComments(Files.readString(file, StandardCharsets.UTF_8)))).get("result"); }
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
                    try { lang = Json.object(Json.parseReporting(withoutComments(Files.readString(en, StandardCharsets.UTF_8)))); langFile = source(set, root, en); }
                    catch (IllegalArgumentException invalid) { lang = Map.of(); langFile = source(set, root, en); }
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

    /** Line comments and block comments become spaces, so positions and line numbers stay true. */
    static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text);
        boolean string = false;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (string) { if (c == '\\') i++; else if (c == '"') string = false; continue; }
            if (c == '"') { string = true; continue; }
            if (c == '/' && i + 1 < out.length() && out.charAt(i + 1) == '/') { while (i < out.length() && out.charAt(i) != '\n') out.setCharAt(i++, ' '); }
            else if (c == '/' && i + 1 < out.length() && out.charAt(i + 1) == '*') {
                int end = out.indexOf("*/", i + 2); end = end < 0 ? out.length() : end + 2;
                for (; i < end; i++) if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
                i--;
            }
        }
        return out.toString();
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
