import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Goal 4: moves a mod's source between Fabric and NeoForge on the same Minecraft version. Every
 * change comes from knowledge/loaders.json or from the fixed steps here (metadata, entrypoints,
 * build); nothing is guessed. What has no rule is named with its file and line and left as it was,
 * never dropped. Each run is one transaction that convert --undo restores byte for byte.
 */
final class Convert {

    static final String FOLDER = "converts";

    final Build build;
    final ProjectModel project;
    Map<String, Object> rules;
    Path transaction;
    final Map<String, Object> before = new LinkedHashMap<>();
    final List<String> removed = new ArrayList<>(), changed = new ArrayList<>(), notes = new ArrayList<>();
    final List<Map<String, Object>> left = new ArrayList<>();
    final Map<String, List<String>> applied = new TreeMap<>();
    /** Access transformer lines the rewritten code needs on NeoForge. */
    final Set<String> accessTransformers = new LinkedHashSet<>();
    static final String AT_FILE = "src/main/resources/META-INF/accesstransformer.cfg";
    String modid = "", fabricId = "", unit = "\t";
    /** --dry-run: files change here, never on disk; null is a removed file. */
    boolean dryRun;
    final Map<String, String> staged = new HashMap<>();
    static final Pattern SOURCE = Pattern.compile("src/([^/]+)/java/.+\\.java");

    /**
     * A Fabric access widener (or class tweaker) as NeoForge access transformer lines, by the two loaders' own formats:
     * accessible is public; extendable is public and not final for a class, protected and not final for a method;
     * mutable is not final. Entries for one target merge. inject-interface has no transformer counterpart and goes to
     * unconverted, as does every entry when the file is not in Mojang's names.
     */
    static List<String> widenerToTransformer(String text, boolean mojangNames, List<String> unconverted) {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        boolean header = false;
        for (String raw : text.lines().toList()) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            if (!header) {
                header = true;
                if (t[0].equals("accessWidener") || t[0].equals("classTweaker")) continue;
            }
            if (!mojangNames) {
                unconverted.add(line + " (not in Mojang's names)");
                continue;
            }
            String access = t[0].replaceFirst("^transitive-", "");
            if (t.length < 3 || !Set.of("accessible", "extendable", "mutable").contains(access)) {
                unconverted.add(line);
                continue;
            }
            String kind = t[1], owner = t[2].replace('/', '.');
            String key = switch (kind) {
                case "class" -> owner;
                case "method" -> t.length >= 5 ? owner + " " + t[3] + t[4] : null;
                case "field" -> t.length >= 4 ? owner + " " + t[3] : null;
                default -> null;
            };
            if (key == null) {
                unconverted.add(line);
                continue;
            }
            targets.computeIfAbsent(kind + "|" + key, k -> new LinkedHashSet<>()).add(access);
            // Fabric makes the owner of an accessible member accessible too (a method of a private nested class can
            // then be called); a transformer line opens only what it names, so the owner gets its own line.
            if (!kind.equals("class") && access.equals("accessible")) targets.computeIfAbsent("class|" + owner, k -> new LinkedHashSet<>()).add("accessible");
        }
        List<String> out = new ArrayList<>();
        for (var entry : targets.entrySet()) {
            String kind = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            String target = entry.getKey().substring(kind.length() + 1);
            Set<String> a = entry.getValue();
            String modifier = switch (kind) {
                case "class" -> a.contains("extendable") ? "public-f" : "public";
                case "method" -> (a.contains("accessible") ? "public" : "protected") + (a.contains("extendable") ? "-f" : "");
                default -> "public" + (a.contains("mutable") ? "-f" : "");
            };
            out.add(modifier + " " + target);
        }
        return out;
    }

    /**
     * NeoForge access transformer lines as a Fabric access widener (v2), the reverse of widenerToTransformer. A field
     * line names no field type, which an access widener needs: fieldTypes gives it from the game (owner/name to
     * descriptor), and a field it does not know goes to unconverted with wildcards and +f.
     */
    static String transformerToWidener(String text, boolean official, List<String> unconverted) {
        return transformerToWidener(text, official, unconverted, (owner, field) -> null);
    }

    static String transformerToWidener(
        String text,
        boolean official,
        List<String> unconverted,
        java.util.function.BiFunction<String, String, String> fieldTypes
    ) {
        StringBuilder out = new StringBuilder("accessWidener v2 " + (official ? "official" : "named") + "\n");
        for (String raw : text.lines().toList()) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            Matcher m = Pattern.compile("^(public|protected|default|private)(-f|\\+f)?$").matcher(t[0]);
            if (!m.find() || "+f".equals(m.group(2)) || t.length < 2 || line.contains("*")) {
                unconverted.add(line);
                continue;
            }
            boolean definal = "-f".equals(m.group(2));
            String owner = t[1].replace('.', '/');
            if (t.length == 2) {
                out.append(definal ? "extendable" : "accessible").append(" class ").append(owner).append('\n');
                continue;
            }
            int paren = t[2].indexOf('(');
            if (paren < 0) {
                String type = fieldTypes.apply(owner, t[2]);
                if (type == null) {
                    unconverted.add(line + " (an access widener names the field's type, which the transformer line does not)");
                    continue;
                }
                out.append("accessible field ").append(owner).append(' ').append(t[2]).append(' ').append(type).append('\n');
                if (definal) out.append("mutable field ").append(owner).append(' ').append(t[2]).append(' ').append(type).append('\n');
                continue;
            }
            String name = t[2].substring(0, paren), desc = t[2].substring(paren);
            out.append("accessible method ").append(owner).append(' ').append(name).append(' ').append(desc).append('\n');
            if (definal) out.append("extendable method ").append(owner).append(' ').append(name).append(' ').append(desc).append('\n');
        }
        return out.toString();
    }

    /**
     * The game's field types (owner in slash form, field name, to its descriptor), in Mojang's names, read only when a
     * field line asks: before 26.1 from Mojang's client mappings, which give every field's type; from 26.1 from the class
     * files of Mojang's client jar, which is no longer obfuscated. Null for a field the game does not have, or offline.
     */
    java.util.function.BiFunction<String, String, String> gameFieldTypes(String minecraft) {
        Map<String, String> known = new HashMap<>();
        Map<String, java.util.zip.ZipFile> jar = new HashMap<>();
        boolean[] loaded = { false };
        return (owner, field) -> {
            try {
                if (!loaded[0]) {
                    loaded[0] = true;
                    var version = Json.object(Json.parse(Target.fetch(build, gameVersionUrl(minecraft))));
                    var downloads = Json.object(version.get("downloads"));
                    if (downloads.get("client_mappings") instanceof Map<?, ?> mappings) known.putAll(
                        mappingFieldTypes(Target.fetch(build, Json.text(Json.object(mappings), "url", "")))
                    );
                    else {
                        var client = Json.object(downloads.get("client"));
                        Path file = Path.of(System.getProperty("user.home"), ".modaccel", "minecraft", minecraft, "client.jar");
                        if (!Files.isRegularFile(file)) {
                            Files.createDirectories(file.getParent());
                            PackCheck.download(Json.text(client, "url", ""), file, Json.text(client, "sha1", ""));
                        }
                        jar.put("client", new java.util.zip.ZipFile(file.toFile()));
                    }
                }
                String key = owner + " " + field;
                if (known.containsKey(key)) return known.get(key);
                var zip = jar.get("client");
                if (zip == null) return null;
                var entry = zip.getEntry(owner + ".class");
                if (entry == null) return null;
                try (var in = zip.getInputStream(entry)) {
                    for (var member : Explain.Index.parse(in.readAllBytes()).members()) if (!member.method()) known.put(owner + " " + member.name(), member.descriptor());
                }
                return known.get(key);
            } catch (Exception offline) {
                return null;
            }
        };
    }

    String gameVersionUrl(String minecraft) throws Exception {
        for (Object v : Json.array(Json.object(Json.parse(Target.fetch(build, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))).get("versions"))) {
            if (minecraft.equals(Json.object(v).get("id"))) return Json.text(Json.object(v), "url", "");
        }
        throw new IOException("Mojang lists no Minecraft " + minecraft);
    }

    /** Every field of Mojang's ProGuard mappings: "owner/Class name" to its descriptor. */
    static Map<String, String> mappingFieldTypes(String mappings) {
        Map<String, String> out = new HashMap<>();
        String owner = null;
        for (String line : mappings.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                owner = line.substring(0, line.indexOf(" -> ")).replace('.', '/');
                continue;
            }
            Matcher field = Pattern.compile("^\\s+([\\w.$\\[\\]]+) ([\\w$]+) -> [\\w$]+\\s*$").matcher(line);
            if (owner != null && field.find()) out.put(owner + " " + field.group(2), descriptor(field.group(1)));
        }
        return out;
    }

    /** A Java type as written in Mojang's mappings (int, java.util.List, a.B[]) as a JVM descriptor. */
    static String descriptor(String type) {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        return switch (type) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> "L" + type.replace('.', '/') + ";";
        };
    }

    /** A Fabric mod id as NeoForge accepts it (^[a-z][a-z0-9_]{1,63}$): Fabric also allows "-". */
    static String neoforgeModId(String id) {
        String out = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (out.isEmpty() || !Character.isLetter(out.charAt(0))) out = "mod_" + out;
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    Convert(Build build) {
        this.build = build;
        this.project = build.project;
    }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        return run(options, true);
    }

    /** compile false writes the conversion without compiling it (self-test, which has no network). */
    Map<String, Object> run(Modaccel.Options options, boolean compile) throws Exception {
        if (options.values.containsKey("undo")) return Port.undo(project, options.get("undo", ""), FOLDER);
        String to = options.get("to", "").toLowerCase(Locale.ROOT);
        String from = loader(project.root);
        if (from == null) throw new IOException(
            "convert found neither src/main/resources/fabric.mod.json nor src/main/resources/META-INF/neoforge.mods.toml in " +
            project.root +
            ". It converts one-module Fabric and NeoForge mods."
        );
        if (!Set.of("fabric", "neoforge").contains(to)) throw new IllegalArgumentException(
            "convert needs --to fabric or --to neoforge. Forge, Quilt and Paper are not converted yet; see modaccel help convert."
        );
        if (to.equals(from)) throw new IllegalArgumentException("This project is already a " + from + " mod.");
        if (
            Files.isRegularFile(project.root.resolve("build.gradle.kts")) ||
            !Files.isRegularFile(project.root.resolve("build.gradle"))
        ) {
            throw new IOException(
                "convert rewrites a Groovy build.gradle; this project has " +
                (Files.isRegularFile(project.root.resolve("build.gradle.kts"))
                        ? "build.gradle.kts"
                        : "no build.gradle") +
                "."
            );
        }
        rules = Json.read(build.harness.resolve("knowledge/loaders.json"));
        Map<String, String> gradleProperties = properties(read("gradle.properties"));
        String minecraft = gradleProperties.getOrDefault("minecraft_version", "");
        // A NeoForge build that names only its NeoForge release: NeoForge numbers each release by its Minecraft version.
        if (minecraft.isEmpty() && from.equals("neoforge")) minecraft = minecraftOfNeoForge(
            gradleProperties.getOrDefault("neo_version", gradleProperties.getOrDefault("neoforge_version", ""))
        );
        if (minecraft.isEmpty()) throw new IOException(
            "convert reads the Minecraft version from minecraft_version in gradle.properties (or, for NeoForge, neo_version); there is none."
        );
        if (from.equals("neoforge") && !gradleProperties.containsKey("minecraft_version")) notes.add(
            "Minecraft " + minecraft + ", read from the NeoForge release in gradle.properties (NeoForge numbers its releases by the Minecraft version)."
        );
        gameVersion = minecraft;
        dryRun = options.has("dry-run");
        if (!dryRun) {
            transaction = project.state().resolve(FOLDER).resolve(Instant.now().toString().replace(':', '-'));
            Files.createDirectories(transaction.resolve("before"));
            saveInProgress();
        }
        if (from.equals("fabric")) toNeoForge(minecraft);
        else toFabric(minecraft);
        if (!neutralDependencies.isEmpty()) notes.add(
            "Dependencies kept as they are, since they name no loader (libraries, or a mod's loader-neutral API): " +
            String.join(", ", neutralDependencies)
        );
        if (!dryRun) Port.finishTransaction(project, transaction, before, removed);
        Map<String, Object> result = Json.map(
            "from",
            from,
            "to",
            to,
            "minecraft",
            minecraft,
            "changed",
            changed,
            "removed",
            removed,
            "rulesApplied",
            applied,
            "left",
            left,
            "notes",
            notes,
            "dryRun",
            dryRun
        );
        if (!dryRun) {
            result.put("undoConvert", transaction.getFileName().toString());
            result.put("transaction", transaction.toString());
        }
        if (dryRun || !compile || options.has("no-compile")) {
            result.put("compiled", null);
            // A dry run succeeds when it ran; what it could not convert is in left.
            result.put("ok", dryRun || left.isEmpty());
        } else compile(result);
        result.put("details", text(result));
        return result;
    }

    /**
     * convert --dry-run from the command line: the dry run (which stages every change in memory) runs on a copy of the
     * project outside it, so even the release lookups it caches and its receipt stay out of the project's folder. The
     * copy is removed afterwards; the result lists what would change and be left.
     */
    static Map<String, Object> preview(Path root, Path harness, Modaccel.Options options) throws Exception {
        Path copy = Files.createTempDirectory("modaccel-convert-preview-");
        try {
            copyProject(root, copy);
            Convert convert = new Convert(new Build(new ProjectModel(copy), harness, "", new Modaccel.Report(copy, "convert")));
            Map<String, Object> result = convert.run(options);
            result.put(
                "details",
                String.valueOf(result.get("details")) + "\nDry run: nothing in " + root + " was written; this lists what convert would change."
            );
            return result;
        } finally {
            OwnCompile.deleteTree(copy);
        }
    }

    /** The project's sources and build, without Git, Gradle's and modaccel's own state (but with its cached release lookups). */
    static void copyProject(Path from, Path to) throws IOException {
        Set<String> skip = Set.of(".git", ".gradle", "build", ".runs", ".modaccel", "run", "out", "bin");
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && skip.contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path metadata = from.resolve(".modaccel/portable/metadata");
        if (Files.isDirectory(metadata)) try (var walk = Files.walk(metadata)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(".modaccel/portable/metadata").resolve(metadata.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The Minecraft version a NeoForge release is for, from NeoForge's own numbering: 21.1.x is 1.21.1 and 20.4.x is
     * 1.20.4 (21.0.x is 1.21); from 26.1, the first three parts are the Minecraft version (26.1.2.48 is 26.1.2, 26.1.0.x
     * is 26.1). Empty when the text is no NeoForge release.
     */
    static String minecraftOfNeoForge(String neo) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(neo.strip());
        if (!m.find()) return "";
        int major = Integer.parseInt(m.group(1));
        if (major >= 26) {
            if (m.group(4) == null) return "";
            return major + "." + m.group(2) + (m.group(3).equals("0") ? "" : "." + m.group(3));
        }
        if (major < 20) return "";
        return "1." + major + (m.group(2).equals("0") ? "" : "." + m.group(2));
    }

    /** The Minecraft version being converted, for the rules written for a range of versions. */
    String gameVersion;

    /** The project's loader by its metadata file; null when it has neither. */
    static String loader(Path root) {
        if (Files.isRegularFile(root.resolve("src/main/resources/fabric.mod.json"))) return "fabric";
        if (Files.isRegularFile(root.resolve(NEO_META))) return "neoforge";
        if (Files.isRegularFile(root.resolve(NEO_TEMPLATE))) return "neoforge";
        return null;
    }

    static final String NEO_META = "src/main/resources/META-INF/neoforge.mods.toml";
    /** NeoForge's MDK keeps neoforge.mods.toml as a template its build expands with gradle.properties (generateModMetadata). */
    static final String NEO_TEMPLATE = "src/main/templates/META-INF/neoforge.mods.toml";

    /**
     * Metadata with Gradle's ${property} placeholders, as the build's own expand would write it: each one gradle.properties
     * defines is replaced; the rest stay as they are.
     */
    String expandProperties(String text) throws IOException {
        Map<String, String> props = properties(read("gradle.properties"));
        Matcher m = Pattern.compile("\\$\\{(?:project\\.)?([\\w.]+)}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(
            out,
            Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group()))
        );
        m.appendTail(out);
        return out.toString();
    }

    // ---- the transaction ----------------------------------------------------------------------------

    void saveInProgress() throws IOException {
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                before,
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map("edits", new ArrayList<>(before.keySet()), "removals", removed),
                "status",
                "in-progress"
            )
        );
    }

    String relative(Path file) {
        return project.root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    void track(Path file) throws Exception {
        String relative = relative(file);
        if (before.containsKey(relative)) return;
        if (Files.isRegularFile(file)) {
            Path backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            before.put(relative, Fingerprints.bytes(Files.readAllBytes(file)));
        } else before.put(relative, null);
        saveInProgress();
    }

    /** Files whose lines end in CRLF: they are read with LF and written back with CRLF. */
    final Set<String> crlf = new HashSet<>();

    String read(String relative) throws IOException {
        String text = current(relative);
        if (text == null) return "";
        if (text.contains("\r\n")) crlf.add(relative);
        return text.replace("\r\n", "\n");
    }

    /** A file's text, with a dry run's changes; null when there is none. */
    String current(String relative) throws IOException {
        if (staged.containsKey(relative)) return staged.get(relative);
        Path file = project.root.resolve(relative);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    boolean exists(String relative) throws IOException {
        return current(relative) != null;
    }

    void write(String relative, String text) throws Exception {
        Path file = project.root.resolve(relative);
        if (crlf.contains(relative) || (!exists(relative) && !crlf.isEmpty())) text = text
            .replace("\r\n", "\n")
            .replace("\n", "\r\n");
        if (text.equals(current(relative))) return;
        if (dryRun) {
            staged.put(relative, text);
            if (!changed.contains(relative)) changed.add(relative);
            return;
        }
        track(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        if (!changed.contains(relative)) changed.add(relative);
    }

    void delete(String relative) throws Exception {
        Path file = project.root.resolve(relative);
        if (!exists(relative)) return;
        if (dryRun) {
            staged.put(relative, null);
            removed.add(relative);
            changed.remove(relative);
            return;
        }
        track(file);
        removed.add(relative);
        saveInProgress();
        Files.delete(file);
        changed.remove(relative);
        for (
            Path parent = file.getParent();
            parent != null && !parent.equals(project.root);
            parent = parent.getParent()
        ) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            }
            Files.delete(parent);
        }
    }

    void leave(String file, int line, String what, String why) {
        left.add(Json.map("file", file, "line", line, "what", what, "why", why));
    }

    // ---- sources -------------------------------------------------------------------------------------

    List<String> javaFiles() throws IOException {
        Path src = project.root.resolve("src").toAbsolutePath().normalize();
        Map<Path, TreeSet<Path>> sets = new TreeMap<>();
        if (Files.isDirectory(src)) try (var list = Files.list(src)) {
            for (Path set : list.toList()) {
                Path java = set.resolve("java");
                if (!Files.isDirectory(java)) continue;
                TreeSet<Path> files = sets.computeIfAbsent(set, k -> new TreeSet<>());
                try (var walk = Files.walk(java)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        }
        // A dry run's files, where the run that writes them would find them.
        for (var entry : staged.entrySet()) {
            Matcher source = SOURCE.matcher(entry.getKey());
            if (!source.matches()) continue;
            Path set = src.resolve(source.group(1));
            Path file = project.root.resolve(entry.getKey()).toAbsolutePath().normalize();
            if (entry.getValue() != null) sets.computeIfAbsent(set, k -> new TreeSet<>()).add(file);
            else if (sets.containsKey(set)) sets.get(set).remove(file);
        }
        List<String> out = new ArrayList<>();
        for (var set : sets.entrySet()) if (!set.getKey().getFileName().toString().startsWith("test")) {
            for (Path file : set.getValue()) out.add(relative(file));
        }
        return out;
    }

    /** The source file of a class, in any source set; null when none has it. */
    String classFile(String name) throws IOException {
        String path = name.replace('.', '/') + ".java";
        for (String file : javaFiles()) if (file.endsWith("/java/" + path)) return file;
        return null;
    }

    void rewriteSources(String from, String to) throws Exception {
        for (String file : javaFiles()) {
            String text = read(file);
            LoaderRules.Result result = new LoaderRules(rules, from, to, modid, gameVersion).apply(text);
            for (String id : result.applied) applied.computeIfAbsent(id, k -> new ArrayList<>()).add(file);
            for (LoaderRules.Left l : result.left) leave(file, l.line(), l.what(), l.why());
            accessTransformers.addAll(result.accessTransformers);
            write(file, result.text);
        }
    }

    /** Names every use of the old loader that no rule changed: the class and where. */
    void leftovers(List<String> prefixes) throws Exception {
        for (String file : javaFiles()) {
            String text = dropUnusedImports(read(file), prefixes);
            write(file, text);
            Matcher m = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)").matcher(text);
            while (m.find()) for (String prefix : prefixes) if (m.group(1).startsWith(prefix)) leave(
                file,
                LoaderRules.line(text, m.start()),
                m.group(1),
                "no rule in knowledge/loaders.json moves this to the other loader yet"
            );
        }
    }

    /** Imports of the old loader's classes that nothing in the file uses any more: they go, since nothing needs them moved. */
    static String dropUnusedImports(String text, List<String> prefixes) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;[ \\t]*\\n?").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean old = prefixes.stream().anyMatch(name::startsWith);
            boolean used = false;
            if (old) {
                Matcher use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(simple) + "(?![\\w$])").matcher(text);
                while (use.find()) if (code[use.start()] && (use.start() < m.start() || use.start() >= m.end())) {
                    used = true;
                    break;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(old && !used ? "" : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- Fabric to NeoForge ----------------------------------------------------------------------------

    void toNeoForge(String minecraft) throws Exception {
        String metaFile = "src/main/resources/fabric.mod.json";
        Map<String, Object> meta = Json.object(Json.parse(read(metaFile)));
        fabricId = Json.text(meta, "id", "");
        if (fabricId.isEmpty()) throw new IOException("fabric.mod.json has no id.");
        modid = neoforgeModId(fabricId);
        if (!modid.equals(fabricId)) notes.add(
            "NeoForge mod ids allow only a-z, 0-9 and _, so " + fabricId + " is " + modid + " on NeoForge; " +
            "its resources and registry ids keep the " + fabricId + " namespace, and neoforge.mods.toml keeps the Fabric id for the way back."
        );
        Set<String> unbridged = unbridge();
        if (Versions.compare(minecraft, ONLY_IN_ERROR) >= 0) dropSideOnly();
        rewriteSources("fabric", "neoforge");
        applyFabricBridge(meta, minecraft);
        Map<String, Object> entrypoints = new LinkedHashMap<>();
        if (meta.get("entrypoints") instanceof Map<?, ?>) for (var e : Json.object(meta.get("entrypoints")).entrySet()) {
            // Entrypoints the NeoForge bridge made of @Mod classes are @Mod classes again (unbridge).
            List<Object> kept = new ArrayList<>();
            for (Object item : Json.array(e.getValue())) {
                String name = item instanceof String str ? str : item instanceof Map<?, ?> ? Json.text(Json.object(item), "value", "") : "";
                if (!unbridged.contains(name)) kept.add(item);
            }
            if (!kept.isEmpty()) entrypoints.put(e.getKey(), kept);
        }
        String datagen = datagenToNeoForge(entrypoints, minecraft);
        String menu = null;
        for (var entry : entrypoints.entrySet()) {
            List<String> classes = new ArrayList<>();
            for (Object item : Json.array(entry.getValue())) {
                if (item instanceof String s) classes.add(s);
                else if (item instanceof Map<?, ?>) classes.add(Json.text(Json.object(item), "value", ""));
            }
            for (String name : classes) {
                if (name.contains("::")) {
                    leave(
                        metaFile,
                        0,
                        "entrypoint " + entry.getKey() + ": " + name,
                        "a method or field entrypoint has no NeoForge counterpart; make it a class"
                    );
                    continue;
                }
                switch (entry.getKey()) {
                    case "main" -> entryToNeoForge(name, "ModInitializer", "onInitialize", null, datagen);
                    case "client" -> entryToNeoForge(
                        name,
                        "ClientModInitializer",
                        "onInitializeClient",
                        "CLIENT",
                        null
                    );
                    case "server" -> entryToNeoForge(
                        name,
                        "DedicatedServerModInitializer",
                        "onInitializeServer",
                        "DEDICATED_SERVER",
                        null
                    );
                    case "modmenu" -> menu = name;
                    case "fabric-datagen" -> {
                        if (datagen == null) leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "Fabric's data generation is converted for Minecraft 1.21.4 to 26.2 (the PackOutput providers); this one was not"
                        );
                        else if (!entrypoints.containsKey("main")) leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "NeoForge runs data generation from the mod's @Mod class, and this mod has no main entrypoint to become one"
                        );
                    }
                    default -> {
                        // Kept in neoforge.mods.toml's modproperties, where the Fabric bridge's FabricLoader reads it. That
                        // serves the mod itself (an entrypoint named by its own id) and converted mods; others are named.
                        ownEntrypoints.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(name);
                        if (!entry.getKey().equals(fabricId) || fabricBridgePackage == null) leave(
                            metaFile,
                            0,
                            "entrypoint " + entry.getKey() + ": " + name,
                            "an entrypoint another mod reads; NeoForge has no counterpart (kept in neoforge.mods.toml as modproperties fabric.entrypoints." + entry.getKey() + ", which converted mods read)"
                        );
                    }
                }
            }
        }
        if (menu != null) menuToNeoForge(menu, entrypoints);
        if (meta.get("accessWidener") instanceof String widener) {
            String path = "src/main/resources/" + widener;
            String text = read(path);
            if (text.isEmpty()) leave(metaFile, 0, "accessWidener " + widener, "the file is not in src/main/resources");
            else {
                // An access widener in yarn's names cannot become a transformer in Mojang's names.
                boolean yarn = properties(read("gradle.properties")).keySet().stream().anyMatch(k -> k.startsWith("yarn_"));
                List<String> unconverted = new ArrayList<>();
                List<String> lines = widenerToTransformer(text, !yarn, unconverted);
                accessTransformers.addAll(lines);
                for (String u : unconverted) leave(path, 0, u, "an access widener entry with no access transformer counterpart");
                delete(path);
                widenerConverted = true;
                applied.computeIfAbsent("access-widener", k -> new ArrayList<>()).add(path);
            }
        }
        if (!accessTransformers.isEmpty()) {
            // NeoForge applies a mod's access transformers when its mods.toml names the file (modsToml adds that).
            String existing = read(AT_FILE);
            StringBuilder lines = new StringBuilder(
                existing.isEmpty() || existing.endsWith("\n") ? existing : existing + "\n"
            );
            for (String line : accessTransformers) if (!existing.contains(line)) lines.append(line).append('\n');
            write(AT_FILE, lines.toString());
            notes.add(
                "Access transformer " +
                AT_FILE +
                " opens " +
                accessTransformers.size() +
                " member(s) the converted code calls."
            );
        }
        write("src/main/resources/META-INF/neoforge.mods.toml", modsToml(meta, minecraft));
        delete(metaFile);
        buildToNeoForge(minecraft);
        leftovers(List.of("net.fabricmc.", "com.terraformersmc.modmenu."));
    }

    // ---- the Fabric bridge on NeoForge ------------------------------------------------------------------------

    /** The package the Fabric bridge goes in (beside the main entrypoint), once the code uses it; null otherwise. */
    String fabricBridgePackage;

    /** The bridge from Fabric to NeoForge for this game version (knowledge/loaders.json bridges[]), or null. */
    Map<String, Object> fabricBridgeFor(String minecraft) {
        for (Object item : LoaderRules.list(rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"fabric".equals(b.get("from")) || !"neoforge".equals(b.get("to"))) continue;
            if (Versions.compare(minecraft, Json.text(b, "minecraft", "99")) >= 0 || bridgeLayer(b, minecraft) != null) return b;
        }
        return null;
    }

    /**
     * The Fabric API classes a bridge mirrors, from its template files: a file at fabric/api/event/Event.java mirrors
     * net.fabricmc.fabric.api.event.Event. Files at the bridge folder's root, and in mixin/, are the bridge's own.
     */
    static Set<String> mirroredClasses(Map<String, Path> templates) {
        Set<String> out = new TreeSet<>();
        for (String rel : templates.keySet()) {
            if (!rel.contains("/") || rel.startsWith("mixin/")) continue;
            String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            out.add(FABRIC_ROOTS.contains(name.substring(0, name.indexOf('.'))) ? "net.fabricmc." + name : name);
        }
        return out;
    }

    /** Fabric's own packages under net.fabricmc: a bridge file under one of these mirrors net.fabricmc.FILE. */
    static final Set<String> FABRIC_ROOTS = Set.of("fabric", "loader", "api");

    /**
     * The bridge's name for a class or package it mirrors, or null: net.fabricmc.X becomes PACKAGE.X, and a Fabric-only
     * library's API the bridge mirrors (Team Reborn Energy, Forge Config API Port's Fabric API) keeps its name under PACKAGE.
     */
    static String mirrored(String name, Set<String> classes, String pkg) {
        // An import of a nested class names its outer class's file: the longest mirrored prefix.
        for (String prefix = name; prefix.contains("."); prefix = prefix.substring(0, prefix.lastIndexOf('.'))) {
            if (classes.contains(prefix)) return pkg + "." + (name.startsWith("net.fabricmc.") ? name.substring("net.fabricmc.".length()) : name);
        }
        return null;
    }

    /**
     * Fabric API classes no rule rewrote, mirrored on NeoForge (knowledge/loaders.json bridges, from fabric to neoforge):
     * the bridge's classes keep Fabric's names and members, written on NeoForge's API in knowledge/bridges/fabric-on-neoforge
     * and copied into the mod under its main entrypoint's package (net.fabricmc.X becomes PACKAGE.fabricbridge.X, so two
     * converted mods never share a package). Imports and qualified names of a mirrored class point at it; the @Mod
     * constructors call FabricBridge.init(modBus), which listens for the NeoForge events the Fabric callbacks are posted from.
     */
    void applyFabricBridge(Map<String, Object> meta, String minecraft) throws Exception {
        Map<String, Object> b = fabricBridgeFor(minecraft);
        if (b == null) return;
        Map<String, Object> layer = Versions.compare(minecraft, Json.text(b, "minecraft", "99")) >= 0 ? null : bridgeLayer(b, minecraft);
        Map<String, Path> templates = bridgeTemplates(
            build.harness.resolve(Json.text(b, "folder", "")),
            layer == null ? null : build.harness.resolve(Json.text(layer, "folder", ""))
        );
        Set<String> classes = mirroredClasses(templates);
        String entry = null;
        if (meta.get("entrypoints") instanceof Map<?, ?> eps) for (String side : List.of("main", "client", "server")) {
            for (Object item : Json.array(Json.object(eps).getOrDefault(side, List.of()))) {
                String name = item instanceof String s ? s : item instanceof Map<?, ?> ? Json.text(Json.object(item), "value", "") : "";
                if (entry == null && name.contains(".") && !name.contains("::")) entry = name;
            }
        }
        if (entry == null) return;
        String pkg = entry.substring(0, entry.lastIndexOf('.')) + ".fabricbridge";
        Pattern imports = Pattern.compile("(?m)^import\\s+(static\\s+)?([a-z]\\w*\\.[\\w.]+?)(\\.\\*)?\\s*;");
        // Qualified names start with the first two parts of a mirrored class (net.fabricmc, team.reborn ...).
        Set<String> heads = new TreeSet<>();
        for (String c : classes) heads.add(Pattern.quote(c.substring(0, c.indexOf('.', c.indexOf('.') + 1))));
        Pattern qualified = Pattern.compile("\\b(?:" + String.join("|", heads) + ")\\.[\\w.]*\\w");
        boolean used = false;
        for (String file : javaFiles()) {
            String text = read(file);
            StringBuilder out = new StringBuilder();
            Matcher m = imports.matcher(text);
            while (m.find()) {
                String name = m.group(2);
                String to = mirrored(name, classes, pkg);
                // A wildcard import of a package the bridge mirrors classes of.
                if (to == null && m.group(3) != null && classes.stream().anyMatch(c -> c.startsWith(name + ".") && c.indexOf('.', name.length() + 1) < 0)) to =
                    pkg + "." + (name.startsWith("net.fabricmc.") ? name.substring("net.fabricmc.".length()) : name);
                m.appendReplacement(out, Matcher.quoteReplacement(
                    to == null ? m.group() : "import " + (m.group(1) == null ? "" : m.group(1)) + to + (m.group(3) == null ? "" : m.group(3)) + ";"
                ));
            }
            m.appendTail(out);
            String next = out.toString();
            boolean[] code = LoaderRules.code(next);
            StringBuilder fq = new StringBuilder();
            Matcher q = qualified.matcher(next);
            while (q.find()) {
                String to = code[q.start()] && !next.startsWith("import", next.lastIndexOf('\n', q.start()) + 1) ? mirrored(q.group(), classes, pkg) : null;
                q.appendReplacement(fq, Matcher.quoteReplacement(to == null ? q.group() : to));
            }
            q.appendTail(fq);
            next = fq.toString();
            if (!next.equals(text)) {
                write(file, next);
                used = true;
                applied.computeIfAbsent("fabric-bridge", k -> new ArrayList<>()).add(file);
            }
        }
        if (!used) return;
        fabricBridgePackage = pkg;
        if (b.get("replaces") instanceof Map<?, ?> replaces) for (var e : Json.object(replaces).entrySet()) fabricBridgeReplaces.put(e.getKey(), String.valueOf(e.getValue()));
        String folder = "src/main/java/" + pkg.replace('.', '/');
        for (var template : templates.entrySet()) write(
            folder + "/" + template.getKey(),
            fillBridge(Files.readString(template.getValue(), StandardCharsets.UTF_8), pkg)
        );
        bridgeMixinConfig = bridgeMixins(b, layer, pkg, javaFiles().stream().filter(f -> !f.startsWith(folder + "/")).toList());
        notes.add(
            "Fabric bridge: " +
            pkg +
            " holds the Fabric API classes the code uses that no rule rewrites, written on NeoForge's API with Fabric's names and members; the @Mod constructors start it."
        );
    }

    /** gradle.properties keys of a Fabric build that a NeoForge build has no use for: convert removes them. */
    static final List<String> FABRIC_PROPERTIES = List.of(
        "loader_version",
        "fabric_loader_version",
        "fabric_version",
        "fabric_api_version",
        "loom_version",
        "fabric_loom_version",
        "yarn_mappings",
        "modmenu_version"
    );

    /**
     * A Groovy map entry (key: project.NAME, or key: NAME) for a property the conversion removes, dropped with its comma:
     * such entries only passed a loader's version to the metadata the other loader's build no longer has.
     */
    static String dropPropertyEntries(String gradle, List<String> names, List<String> dropped) {
        for (String name : names) {
            String value = "(?:project\\.)?" + Pattern.quote(name) + "(?![\\w.])";
            for (String form : List.of(",\\s*(\\w+)\\s*:\\s*" + value, "(\\w+)\\s*:\\s*" + value + "\\s*,\\s*")) {
                Matcher m = Pattern.compile(form).matcher(gradle);
                StringBuilder out = new StringBuilder();
                while (m.find()) {
                    dropped.add(m.group(1) + ": " + name);
                    m.appendReplacement(out, "");
                }
                m.appendTail(out);
                gradle = out.toString();
            }
        }
        return gradle;
    }

    /**
     * Build code written against the source loader's Gradle plugin classes (import net.fabricmc.loom.task..., import
     * net.neoforged.moddevgradle.internal...): the imports and every top-level statement that names an imported class go,
     * since the other loader's plugin has no such class; each removed statement is named.
     */
    String dropPluginCode(String gradle, String pluginPackage) {
        Matcher m = Pattern.compile("(?m)^import\\s+(" + Pattern.quote(pluginPackage) + "[\\w.]*\\.(\\w+))\\s*;?[ \\t]*\\n?").matcher(gradle);
        List<String> simple = new ArrayList<>();
        while (m.find()) simple.add(m.group(2));
        if (simple.isEmpty()) return gradle;
        gradle = m.reset().replaceAll("");
        Pattern names = Pattern.compile("\\b(?:" + String.join("|", simple) + ")\\b");
        List<String> removed = new ArrayList<>();
        gradle = dropStatements(gradle, s -> names.matcher(s).find(), removed);
        for (String statement : removed) leave(
            "build.gradle",
            0,
            statement.strip().lines().findFirst().orElse(""),
            "build code using " + pluginPackage + " classes, which the other loader's Gradle plugin does not have; removed"
        );
        return gradle;
    }

    /** The loader a publishing plugin names (mod-publish-plugin's modLoaders.add, publishMods' loaders) follows the build. */
    static String publishLoader(String gradle, String from, String to) {
        return gradle.replaceAll("(modLoaders\\.add\\(\\s*[\"'])" + from + "([\"']\\s*\\))", "$1" + to + "$2");
    }

    /** Fabric-only libraries the Fabric bridge mirrors, by group:artifact, when the code uses the bridge (bridges[].replaces). */
    final Map<String, String> fabricBridgeReplaces = new LinkedHashMap<>();

    /** The library a dependency line names when the Fabric bridge mirrors its API, else null. */
    String bridgeReplaces(String line) {
        if (fabricBridgePackage == null) return null;
        Matcher c = COORDINATE.matcher(line);
        if (!c.find()) return null;
        String coordinate = c.group(2) + ":" + c.group(3);
        for (var e : fabricBridgeReplaces.entrySet()) if (coordinate.equals(e.getKey())) return e.getValue();
        return null;
    }

    /** The Fabric bridge's marker: its FabricBridge class says this in its first comment. */
    static final String FABRIC_BRIDGE_MARK = "The Fabric bridge on NeoForge";

    /**
     * A mod convert moved to NeoForge through the Fabric bridge, going back to Fabric: imports and names of the bridge's
     * classes are Fabric's again, the @Mod constructors' FabricBridge.init goes (entryToFabric), and the bridge's files
     * and mixin config are removed.
     */
    void unbridgeFabric() throws Exception {
        String pkg = null;
        for (String file : javaFiles()) if (file.endsWith("/FabricBridge.java") && read(file).contains(FABRIC_BRIDGE_MARK)) {
            Matcher m = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(read(file));
            if (m.find()) pkg = m.group(1);
        }
        if (pkg == null) return;
        String folder = "src/main/java/" + pkg.replace('.', '/') + "/";
        Pattern names = Pattern.compile("\\b" + Pattern.quote(pkg) + "\\.([a-z]\\w*)((?:\\.\\w+)*(?:\\.\\*)?)");
        for (String file : javaFiles()) {
            if (file.startsWith(folder)) continue;
            String text = read(file);
            String out = text.replaceAll("(?m)^[ \\t]*FabricBridge\\.init\\(modBus\\);[ \\t]*\\n", "");
            out = LoaderRules.dropImport(out, pkg + ".FabricBridge");
            out = names.matcher(out).replaceAll(r -> Matcher.quoteReplacement(
                (FABRIC_ROOTS.contains(r.group(1)) ? "net.fabricmc." : "") + r.group(1) + r.group(2)
            ));
            if (!out.equals(text)) {
                write(file, out);
                applied.computeIfAbsent("fabric-bridge-back", k -> new ArrayList<>()).add(file);
            }
        }
        for (String file : javaFiles()) if (file.startsWith(folder)) delete(file);
        Path resources = project.root.resolve("src/main/resources");
        if (Files.isDirectory(resources)) try (var list = Files.list(resources)) {
            for (Path config : list.filter(p -> p.getFileName().toString().endsWith(".fabricbridge.mixins.json")).toList()) delete(relative(config));
        }
        notes.add("Fabric bridge: " + pkg + " is removed; the code names Fabric API's own classes again.");
    }

    /** Fabric entrypoints other than main, client, server, modmenu and fabric-datagen, by key: kept in modproperties. */
    final Map<String, List<String>> ownEntrypoints = new LinkedHashMap<>();

    /** A static field whose initializer registers something: Registry.register, buildAndRegister, registerBlock ... */
    static final Pattern STATIC_REGISTRATION = Pattern.compile(
        "(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)?static\\s+(?:final\\s+)?[^;=(){}]+=[^;]*?\\b\\w*[Rr]egister\\w*\\s*\\("
    );

    /** The file of the NeoForge @Mod class each Fabric entrypoint became, by its Fabric interface. */
    final Map<String, String> modClasses = new HashMap<>();

    static final Pattern CLASS = Pattern.compile(
        "(?m)^([ \\t]*)((?:public\\s+|final\\s+|abstract\\s+)*)class\\s+(\\w+)([^{]*)\\{"
    );

    /** True when the file imports a ModContainer that is not NeoForge's (the Fabric bridge mirrors Fabric Loader's under that name). */
    static boolean otherModContainer(String text) {
        Matcher m = Pattern.compile("(?m)^import\\s+([\\w.]+\\.ModContainer)\\s*;").matcher(text);
        while (m.find()) if (!m.group(1).equals("net.neoforged.fml.ModContainer")) return true;
        return false;
    }

    /** Makes a Fabric entrypoint class a NeoForge @Mod class whose constructor runs the entrypoint where NeoForge lets mods register. */
    void entryToNeoForge(String name, String api, String method, String dist, String extra) throws Exception {
        String file = classFile(name);
        if (file == null) {
            leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint " + name,
                "its source file is not in this project"
            );
            return;
        }
        String text = read(file), simple = name.substring(name.lastIndexOf('.') + 1);
        unit = LoaderRules.unit(text);
        Matcher c = CLASS.matcher(text);
        boolean found = false;
        while (c.find()) if (c.group(3).equals(simple)) {
            found = true;
            break;
        }
        if (!found) {
            leave(file, 0, "entrypoint " + name, "the class declaration was not found");
            return;
        }
        if (text.contains("@Mod(")) {
            leave(
                file,
                LoaderRules.line(text, c.start()),
                "entrypoint " + name,
                "this class is already a NeoForge @Mod class, so it cannot also take " +
                api +
                "; give each entrypoint its own class"
            );
            return;
        }
        String heritage = c.group(4);
        String cleaned = heritage
            .replaceAll("\\bimplements\\s+" + api + "\\s*$", "")
            .replaceAll("\\bimplements\\s+" + api + "\\s*,\\s*", "implements ")
            .replaceAll(",\\s*" + api + "\\b", "");
        if (cleaned.equals(heritage)) {
            leave(file, LoaderRules.line(text, c.start()), "entrypoint " + name, "it does not implement " + api);
            return;
        }
        String indent = c.group(1);
        String inner = indent + unit;
        String annotation = dist == null
            ? "@Mod(" + LoaderRules.quote(modid) + ")"
            : "@Mod(value = " + LoaderRules.quote(modid) + ", dist = Dist." + dist + ")";
        // NeoForge loads a @Mod class before any registry is open. An entry class that registers in a static field
        // (a game rule, a block) must not be that class: a small generated holder runs it once registries are open.
        boolean holder = STATIC_REGISTRATION.matcher(text).find();
        String modClass = holder ? simple + "NeoForge" : simple, call = holder
            ? "new " + simple + "()." + method + "()"
            : method + "()";
        if (holder) inner = unit;
        boolean noArgs = !holder && Pattern.compile("\\b" + simple + "\\s*\\(\\s*\\)\\s*\\{").matcher(text).find();
        StringBuilder ctor = new StringBuilder();
        boolean client = "CLIENT".equals(dist);
        // Fabric runs main entrypoints where mods register, and client ones once Minecraft exists, before its options load.
        // On NeoForge those are the first RegisterEvent and the first client event, RegisterKeyMappingsEvent (posted one
        // priority phase at a time, so key mappings the entrypoint adds at normal priority are still registered).
        ctor
            .append('\n')
            .append(inner)
            .append(
                client
                    ? "/** Runs the Fabric client entrypoint at NeoForge's first client event, once Minecraft exists and before its options load. */\n"
                    : "/** Runs the Fabric entrypoint where NeoForge lets a mod register: inside its first registry event. */\n"
            );
        ctor
            .append(inner)
            .append("public ")
            .append(modClass)
            .append("(IEventBus modBus")
            // The file may import another ModContainer (the Fabric bridge's mirror of Fabric Loader's): NeoForge's is then written in full.
            .append(dist == null ? "" : otherModContainer(text) ? ", net.neoforged.fml.ModContainer container" : ", ModContainer container")
            .append(") {\n");
        if (noArgs) ctor.append(inner).append(unit).append("this();\n");
        // The Fabric bridge listens on the mod's bus for the events its Fabric API classes are posted from.
        if (fabricBridgePackage != null) ctor.append(inner).append(unit).append("FabricBridge.init(modBus);\n");
        if (extra != null) ctor.append(inner).append(unit).append(extra).append('\n');
        if (client) ctor
            .append(inner)
            .append(unit)
            .append("modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> ")
            .append(call)
            .append(");\n");
        else {
            ctor
                .append(inner)
                .append(unit)
                .append("modBus.addListener(")
                .append(dist == null ? "" : "EventPriority.LOW, ")
                .append("RegisterEvent.class, event -> {\n");
            ctor
                .append(inner)
                .append(unit)
                .append(unit)
                .append("if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) ")
                .append(call)
                .append(";\n");
            ctor.append(inner).append(unit).append("});\n");
        }
        ctor.append(inner).append("}\n");
        String declaration =
            c.group(1) + c.group(2) + "class " + simple + (cleaned.isBlank() ? " " : " " + cleaned.strip() + " ") + "{";
        String out =
            text.substring(0, c.start()) +
            (holder ? "" : indent + annotation + "\n") +
            declaration +
            text.substring(c.end());
        out = out.replaceAll(
            "(?m)^([ \\t]*)@Override[ \\t]*\\n[ \\t]*(public\\s+void\\s+" + method + "\\s*\\()",
            "$1$2"
        );
        out = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.api\\." + api + "\\s*;[^\\n]*\\n?")
            .matcher(out)
            .replaceAll("");
        List<String> needed = new ArrayList<>(
            List.of("net.neoforged.fml.common.Mod", "net.neoforged.bus.api.IEventBus")
        );
        if (client) needed.add("net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent");
        else needed.addAll(
            List.of("net.neoforged.neoforge.registries.RegisterEvent", "net.minecraft.core.registries.Registries")
        );
        if (dist != null) needed.addAll(
            List.of(
                "net.neoforged.api.distmarker.Dist",
                "net.neoforged.fml.ModContainer",
                "net.neoforged.bus.api.EventPriority"
            )
        );
        if (extra != null && extra.contains("GatherDataEvent")) needed.add(
            "net.neoforged.neoforge.data.event.GatherDataEvent"
        );
        if (fabricBridgePackage != null) needed.add(fabricBridgePackage + ".FabricBridge");
        if (holder) {
            write(file, LoaderRules.tidy(out));
            Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            String generated =
                (pkg.find() ? "package " + pkg.group(1) + ";\n\n" : "") +
                "/** NeoForge's entry for " +
                simple +
                ", which registers in its static fields: it may load only once NeoForge opens the registries. */\n" +
                annotation +
                "\npublic final class " +
                modClass +
                " {\n" +
                ctor.substring(1) +
                "}\n";
            for (String name2 : needed) generated = LoaderRules.addImport(generated, name2);
            String holderFile = file.substring(0, file.lastIndexOf('/') + 1) + modClass + ".java";
            write(holderFile, generated);
            modClasses.put(api, holderFile);
            notes.add(
                simple +
                " registers in its static fields, so its NeoForge @Mod class is the generated " +
                modClass +
                ", which runs " +
                simple +
                "." +
                method +
                "() once the registries are open."
            );
            applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(holderFile);
            return;
        }
        // The constructor goes just before the entry method it runs, with its own blank line.
        Matcher entry = Pattern.compile(
            "(?m)^[ \\t]*(?:/\\*\\*(?:[^*]|\\*(?!/))*\\*/\\s*)?public\\s+void\\s+" + method + "\\s*\\("
        ).matcher(out);
        if (entry.find()) out =
            out.substring(0, entry.start()) + ctor.substring(1) + "\n" + out.substring(entry.start());
        else {
            int open = out.indexOf('{', out.indexOf(annotation) + annotation.length()) + 1;
            out = out.substring(0, open) + ctor + out.substring(open);
        }
        for (String name2 : needed) out = LoaderRules.addImport(out, name2);
        write(file, out);
        modClasses.put(api, file);
        applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
    }

    /** Where Fabric data generation writes: the datagen run's fabric-api.datagen.output-dir, else Loom's src/main/generated. */
    String datagenOutput = null;

    /**
     * Fabric data generation onto NeoForge's: each provider class onto the vanilla provider it wraps, the
     * fabric-datagen entrypoint onto a GatherDataEvent.Client listener the main @Mod class adds. Returns that
     * listener's line for the main constructor, or null when the mod has no datagen or it cannot be converted.
     */
    String datagenToNeoForge(Map<String, Object> entrypoints, String minecraft) throws Exception {
        List<String> names = new ArrayList<>();
        for (Object item : Json.array(entrypoints.getOrDefault("fabric-datagen", List.of()))) names.add(
            item instanceof String s ? s : Json.text(Json.object(item), "value", "")
        );
        if (names.size() != 1 || !entrypoints.containsKey("main")) return null;
        // The providers' PackOutput shape is Minecraft's from 1.21.4 to 26.2; 26.3 changed the vanilla provider constructors.
        if (Versions.compare(minecraft, "1.21.4") < 0 || Versions.compare(minecraft, "26.3") >= 0) return null;
        Map<String, String> kinds = new HashMap<>();
        boolean complete = true;
        for (String file : javaFiles()) {
            LoaderRules.Provider provider = LoaderRules.datagenProvider(read(file), modid);
            if (provider == null) continue;
            if (provider.kind().isEmpty()) {
                leave(
                    file,
                    0,
                    "data provider " + file.substring(file.lastIndexOf('/') + 1),
                    "it extends a Fabric datagen base with no rule yet (models, block loot, recipes and advancements have one)"
                );
                complete = false;
                continue;
            }
            write(file, provider.text());
            kinds.put(file.substring(file.lastIndexOf('/') + 1).replace(".java", ""), provider.kind());
            applied.computeIfAbsent("datagen-" + provider.kind(), k -> new ArrayList<>()).add(file);
        }
        String file = classFile(names.get(0));
        if (file == null) return null;
        List<String> unknown = new ArrayList<>();
        String entry = LoaderRules.datagenEntry(read(file), kinds, unknown);
        if (entry == null) {
            leave(file, 0, "entrypoint fabric-datagen", "onInitializeDataGenerator(FabricDataGenerator) was not found");
            return null;
        }
        for (String provider : unknown) leave(
            file,
            0,
            "data provider " + provider,
            "its kind has no rule, so it is not registered with GatherDataEvent"
        );
        write(file, entry);
        applied.computeIfAbsent("datagen-entrypoint", k -> new ArrayList<>()).add(file);
        datagenOutput = "src/main/generated";
        if (complete && unknown.isEmpty()) notes.add(
            "Data generation: modaccel compile --task :runClientData writes the generated files to the folder the Fabric datagen used."
        );
        return (
            "modBus.addListener(GatherDataEvent.Client.class, event -> new " +
            names.get(0) +
            "().onInitializeDataGenerator(event));"
        );
    }

    /** Mod Menu's config screen becomes NeoForge's IConfigScreenFactory, registered by the client entrypoint. */
    void menuToNeoForge(String menu, Map<String, Object> entrypoints) throws Exception {
        String file = classFile(menu);
        if (file == null) {
            leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint modmenu: " + menu,
                "its source file is not in this project"
            );
            return;
        }
        String text = read(file);
        if (
            Pattern.compile("getProvidedConfigScreenFactories|getUpdateChecker|attachModpackBadges")
                .matcher(text)
                .find()
        ) leave(
            file,
            0,
            "Mod Menu API",
            "only getModConfigScreenFactory has a NeoForge counterpart (IConfigScreenFactory)"
        );
        write(file, LoaderRules.modMenuToNeoForge(text));
        for (String other : javaFiles()) {
            String body = read(other);
            String next = body.replace(".getModConfigScreenFactory().create(", ".getModConfigScreenFactory().apply(");
            write(other, next);
        }
        String register =
            "container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new " +
            menu +
            "().getModConfigScreenFactory().apply(parent));";
        String client = null;
        for (Object item : Json.array(entrypoints.getOrDefault("client", List.of()))) client = item instanceof String s
            ? s
            : Json.text(Json.object(item), "value", "");
        String clientFile = client == null ? null : modClasses.getOrDefault("ClientModInitializer", classFile(client));
        if (clientFile == null) {
            leave(
                file,
                0,
                "Mod Menu entry " + menu,
                "the mod has no client entrypoint to register NeoForge's config screen from"
            );
            return;
        }
        String text2 = read(clientFile);
        Matcher ctor = Pattern.compile("ModContainer container\\) \\{\\n").matcher(text2);
        if (!ctor.find()) {
            leave(
                clientFile,
                0,
                "Mod Menu entry " + menu,
                "the client entrypoint was not converted, so the config screen is not registered"
            );
            return;
        }
        int at = ctor.end();
        text2 = text2.substring(0, at) + LoaderRules.indent(text2, at) + register + "\n" + text2.substring(at);
        text2 = LoaderRules.addImport(text2, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
        write(clientFile, text2);
        applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
        notes.add(
            "Mod Menu's config screen opens from NeoForge's own mod list (IConfigScreenFactory); the modmenu dependency is gone."
        );
    }

    static String tomlString(String value) {
        if (value.contains("\n")) return "'''\n" + value + "\n'''";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A Fabric version predicate as a Maven range: >=1.2 is [1.2,), ~26.3 is [26.3,26.4), * is any. */
    static String mavenRange(Object predicate) {
        String value = predicate instanceof List<?> list && !list.isEmpty()
            ? String.valueOf(list.get(0))
            : String.valueOf(predicate);
        value = value.strip();
        if (value.equals("*") || value.isEmpty()) return "[0,)";
        Matcher tilde = Pattern.compile("^~(\\d+)\\.(\\d+)(.*)$").matcher(value);
        if (tilde.find()) return (
            "[" + value.substring(1) + "," + tilde.group(1) + "." + (Integer.parseInt(tilde.group(2)) + 1) + ")"
        );
        Matcher caret = Pattern.compile("^\\^(\\d+)(.*)$").matcher(value);
        if (caret.find()) return "[" + value.substring(1) + "," + (Integer.parseInt(caret.group(1)) + 1) + ")";
        String low = null, high = null;
        boolean lowIn = true, highIn = false;
        for (String part : value.split("\\s+")) {
            if (part.startsWith(">=")) low = part.substring(2);
            else if (part.startsWith(">")) {
                low = part.substring(1);
                lowIn = false;
            } else if (part.startsWith("<=")) {
                high = part.substring(2);
                highIn = true;
            } else if (part.startsWith("<")) high = part.substring(1);
            else if (part.startsWith("=")) return "[" + part.substring(1) + "]";
            else return "[" + part + "]";
        }
        return (
            (low == null ? "(" : lowIn ? "[" : "(") +
            (low == null ? "" : low) +
            "," +
            (high == null ? "" : high) +
            (high == null ? ")" : highIn ? "]" : ")")
        );
    }

    /** A Maven range as a Fabric version predicate. */
    static String fabricPredicate(String range) {
        String value = range.strip();
        if (value.equals("[0,)") || value.equals("*") || value.isEmpty()) return "*";
        Matcher m = Pattern.compile("^([\\[(])([^,]*)(?:,([^\\])]*))?([\\])])$").matcher(value);
        if (!m.find()) return value;
        if (m.group(3) == null) return m.group(2);
        Matcher minor = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.0)?$").matcher(m.group(2));
        if (
            m.group(1).equals("[") &&
            m.group(4).equals(")") &&
            minor.find() &&
            m.group(3).equals(minor.group(1) + "." + (Integer.parseInt(minor.group(2)) + 1))
        ) return "~" + m.group(2);
        List<String> parts = new ArrayList<>();
        if (!m.group(2).isEmpty()) parts.add((m.group(1).equals("[") ? ">=" : ">") + m.group(2));
        if (!m.group(3).isEmpty()) parts.add((m.group(4).equals("]") ? "<=" : "<") + m.group(3));
        return parts.isEmpty() ? "*" : String.join(" ", parts);
    }

    static final Set<String> FABRIC_ONLY = Set.of(
        "fabricloader",
        "fabric",
        "fabric-api",
        "fabric-api-base",
        "java",
        "modmenu"
    );

    String modsToml(Map<String, Object> meta, String minecraft) {
        StringBuilder out = new StringBuilder();
        out.append("modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\n");
        Object license = meta.get("license");
        String licence = license instanceof List<?> l
            ? String.join(", ", l.stream().map(String::valueOf).toList())
            : license == null ? "All Rights Reserved" : String.valueOf(license);
        out.append("license = ").append(tomlString(licence)).append('\n');
        Map<String, Object> contact = meta.get("contact") instanceof Map<?, ?>
            ? Json.object(meta.get("contact"))
            : Map.of();
        if (contact.get("issues") != null) out
            .append("issueTrackerURL = ")
            .append(tomlString(String.valueOf(contact.get("issues"))))
            .append('\n');
        out.append("\n[[mods]]\nmodId = ").append(tomlString(modid)).append('\n');
        out.append("version = ").append(tomlString(Json.text(meta, "version", "${version}"))).append('\n');
        out.append("displayName = ").append(tomlString(Json.text(meta, "name", modid))).append('\n');
        List<String> authors = new ArrayList<>();
        for (Object a : meta.get("authors") instanceof List<?>
            ? Json.array(meta.get("authors"))
            : List.of()) authors.add(
            a instanceof Map<?, ?> ? Json.text(Json.object(a), "name", "") : String.valueOf(a)
        );
        if (!authors.isEmpty()) out.append("authors = ").append(tomlString(String.join(", ", authors))).append('\n');
        if (contact.get("homepage") != null) out
            .append("displayURL = ")
            .append(tomlString(String.valueOf(contact.get("homepage"))))
            .append('\n');
        Object icon = meta.get("icon");
        // NeoForge 26.2 reads a square iconFile and warns about logoFile; earlier releases read only logoFile.
        String iconKey = Versions.compare(minecraft, "26.2") >= 0 ? "iconFile" : "logoFile";
        if (icon instanceof String s) out.append(iconKey + " = ").append(tomlString(s)).append('\n');
        else if (icon instanceof Map<?, ?> sizes && !sizes.isEmpty()) out
            .append(iconKey + " = ")
            .append(tomlString(String.valueOf(new TreeMap<>(Json.object(icon)).lastEntry().getValue())))
            .append('\n');
        out.append("description = ").append(tomlString(Json.text(meta, "description", ""))).append('\n');
        StringBuilder properties = new StringBuilder();
        if (!modid.equals(fabricId)) properties.append("fabricId = ").append(tomlString(fabricId)).append('\n');
        for (var e : ownEntrypoints.entrySet()) properties
            .append(tomlString("fabric.entrypoints." + e.getKey()))
            .append(" = [")
            .append(String.join(", ", e.getValue().stream().map(Convert::tomlString).toList()))
            .append("]\n");
        Map<String, Object> custom = meta.get("custom") instanceof Map<?, ?> values ? withoutBridgeInterfaces(Json.object(values)) : Map.of();
        if (!custom.isEmpty()) properties
            .append(tomlString("fabric.custom"))
            .append(" = ")
            .append(tomlString(Json.write(custom)))
            .append('\n');
        if (properties.length() > 0) out.append("\n[modproperties.").append(modid).append("]\n").append(properties);
        if ("client".equals(meta.get("environment"))) notes.add(
            "fabric.mod.json said environment client: on NeoForge the mod's only entry class is @Mod(dist = Dist.CLIENT), so a server loads none of its code."
        );
        if ("server".equals(meta.get("environment"))) notes.add(
            "fabric.mod.json said environment server: on NeoForge the mod's only entry class is @Mod(dist = Dist.DEDICATED_SERVER)."
        );
        String neoRange = "[" + neoforgePrefix(minecraft).replaceAll("\\.$", "") + ",)";
        out
            .append("\n[[dependencies.")
            .append(modid)
            .append("]]\nmodId = \"neoforge\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString(neoRange))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("depends", "required");
        kinds.put("recommends", "optional");
        kinds.put("suggests", "optional");
        kinds.put("breaks", "incompatible");
        kinds.put("conflicts", "discouraged");
        boolean minecraftDone = false;
        for (var kind : kinds.entrySet()) {
            if (!(meta.get(kind.getKey()) instanceof Map<?, ?>)) continue;
            for (var dep : Json.object(meta.get(kind.getKey())).entrySet()) {
                String id = dep.getKey();
                if (FABRIC_ONLY.contains(id)) continue;
                if (id.equals("another-mod")) continue;
                String range = mavenRange(dep.getValue());
                if (id.equals("minecraft")) {
                    minecraftDone = true;
                    if (range.equals("[0,)")) range = "[" + minecraft + ",)";
                }
                out
                    .append("\n[[dependencies.")
                    .append(modid)
                    .append("]]\nmodId = ")
                    .append(tomlString(id))
                    .append("\ntype = ")
                    .append(tomlString(kind.getValue()))
                    .append("\nversionRange = ")
                    .append(tomlString(range))
                    .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
                if (!id.equals("minecraft")) notes.add(
                    "Dependency " +
                    id +
                    " (" +
                    kind.getKey() +
                    ") is kept by id; it needs a NeoForge build of that mod."
                );
            }
        }
        if (!minecraftDone) out
            .append("\n[[dependencies.")
            .append(modid)
            .append("]]\nmodId = \"minecraft\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString("[" + minecraft + ",)"))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        if (!accessTransformers.isEmpty()) out.append(
            "\n[[accessTransformers]]\nfile = \"META-INF/accesstransformer.cfg\"\n"
        );
        for (Object m : meta.get("mixins") instanceof List<?> ? Json.array(meta.get("mixins")) : List.of()) {
            String config = m instanceof Map<?, ?> ? Json.text(Json.object(m), "config", "") : String.valueOf(m);
            // The NeoForge bridge's own mixins go with the bridge (unbridge removes their config).
            if (config.endsWith(".loaderbridge.mixins.json")) continue;
            out.append("\n[[mixins]]\nconfig = ").append(tomlString(config)).append('\n');
        }
        if (bridgeMixinConfig != null) out.append("\n[[mixins]]\nconfig = ").append(tomlString(bridgeMixinConfig)).append('\n');
        if (meta.get("accessWidener") != null && !widenerConverted) leave(
            "src/main/resources/fabric.mod.json",
            0,
            "accessWidener " + meta.get("accessWidener"),
            "access wideners are not converted to NeoForge access transformers yet"
        );
        if (meta.get("jars") != null) leave(
            "src/main/resources/fabric.mod.json",
            0,
            "jars",
            "nested jars are declared by the build (jarJar) on NeoForge; convert them by hand"
        );
        return out.toString();
    }

    String neoforgePrefix(String minecraft) {
        String[] parts = minecraft.split("\\.");
        if (parts[0].equals("1")) return (
            (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + "."
        );
        return parts[0] + "." + (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + ".";
    }

    static List<String> metadataVersions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1).strip());
        return out;
    }

    static String newest(List<String> versions) {
        String best = null;
        for (String v : versions) if (best == null || Versions.compare(v, best) > 0) best = v;
        return best;
    }

    String latestNeoForge(String minecraft) throws Exception {
        String prefix = neoforgePrefix(minecraft);
        List<String> found = new ArrayList<>();
        for (String v : metadataVersions(
            Target.fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        )) if (v.startsWith(prefix) && !v.contains("-")) found.add(v);
        if (found.isEmpty()) throw new IOException(
            "NeoForge has published no release for Minecraft " +
            minecraft +
            " (no net.neoforged:neoforge " +
            prefix +
            "x on maven.neoforged.net)."
        );
        return newest(found);
    }

    /**
     * The newest stable release of a Gradle plugin that runs on this project's Gradle: its published Gradle module
     * metadata names the oldest Gradle it supports (org.gradle.plugin.api-version), and alphas and snapshots are skipped.
     */
    String stablePlugin(String repository, String group, String artifact) throws Exception {
        String base = repository + "/" + group.replace('.', '/') + "/" + artifact;
        List<String> stable = new ArrayList<>(
            metadataVersions(Target.fetch(build, base + "/maven-metadata.xml"))
                .stream()
                .filter(v -> !v.contains("-"))
                .toList()
        );
        stable.sort((a, b) -> Versions.compare(b, a));
        String gradle = project.wrapperVersion(), newestNeeds = null;
        for (String version : stable.subList(0, Math.min(12, stable.size()))) {
            String needs = null;
            try {
                for (Object variant : Json.array(
                    Json.object(
                        Json.parse(
                            Target.fetch(build, base + "/" + version + "/" + artifact + "-" + version + ".module")
                        )
                    ).get("variants")
                )) {
                    Object attributes = Json.object(variant).get("attributes");
                    if (
                        attributes instanceof Map<?, ?> &&
                        Json.object(attributes).get("org.gradle.plugin.api-version") != null
                    ) needs = String.valueOf(Json.object(attributes).get("org.gradle.plugin.api-version"));
                }
            } catch (IOException | IllegalArgumentException noModule) {
                // No Gradle module metadata: nothing says which Gradle it needs.
            }
            if (needs == null || gradle == null || Versions.compare(gradle, needs) >= 0) return version;
            if (newestNeeds == null) newestNeeds = needs;
        }
        if (stable.isEmpty()) throw new IOException("No stable " + group + ":" + artifact + " release in " + repository + ".");
        // None runs on this Gradle: the newest release, and the wrapper moves to the Gradle its own metadata asks for.
        upgradeWrapper(newestNeeds, group + ":" + artifact + " " + stable.get(0));
        return stable.get(0);
    }

    /**
     * Points the Gradle wrapper at the newest final Gradle release of the line a plugin's metadata asks for (at least
     * that version), with the SHA-256 Gradle publishes beside it when the wrapper pins one.
     */
    void upgradeWrapper(String needs, String why) throws Exception {
        String file = "gradle/wrapper/gradle-wrapper.properties";
        String text = read(file);
        String line = needs.replaceAll("^(\\d+\\.\\d+).*", "$1");
        String best = null;
        for (Object item : Json.array(Json.parse(Target.fetch(build, "https://services.gradle.org/versions/all")))) {
            var v = Json.object(item);
            String version = Json.text(v, "version", "");
            if (
                Boolean.TRUE.equals(v.get("snapshot")) ||
                Boolean.TRUE.equals(v.get("broken")) ||
                !Json.text(v, "rcFor", "").isEmpty() ||
                !Json.text(v, "milestoneFor", "").isEmpty() ||
                version.contains("-")
            ) continue;
            if (!(version.equals(line) || version.startsWith(line + "."))) continue;
            if (Versions.compare(version, needs) < 0) continue;
            if (best == null || Versions.compare(version, best) > 0) best = version;
        }
        if (best == null) throw new IOException("Gradle has no final release " + needs + " or later in the " + line + " line, which " + why + " needs.");
        String kind = text.contains("-all.zip") ? "all" : "bin";
        String url = "https\\://services.gradle.org/distributions/gradle-" + best + "-" + kind + ".zip";
        String out = text.replaceAll("(?m)^distributionUrl=.*$", Matcher.quoteReplacement("distributionUrl=" + url));
        if (Pattern.compile("(?m)^distributionSha256Sum=").matcher(out).find()) {
            String sum = Target.fetch(build, "https://services.gradle.org/distributions/gradle-" + best + "-" + kind + ".zip.sha256").strip();
            out = out.replaceAll("(?m)^distributionSha256Sum=.*$", "distributionSha256Sum=" + sum);
        }
        String old = project.wrapperVersion();
        write(file, out);
        notes.add("Gradle wrapper: " + old + " to " + best + ", the Gradle " + why + " asks for in its published metadata (" + needs + " or later).");
        // Gradle's foojay toolchain resolver before 1.0.0 names JvmVendorSpec.IBM_SEMERU, which Gradle 9 removed: a build
        // that provisions a toolchain then fails to evaluate. 1.0.0 is the resolver release written for Gradle 9.
        String settings = read("settings.gradle");
        Matcher foojay = Pattern.compile("(id\\s*\\(?\\s*['\"]org\\.gradle\\.toolchains\\.foojay-resolver-convention['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"])([\\d.]+)(['\"])").matcher(settings);
        if (Versions.compare(best, "9") >= 0 && foojay.find() && Versions.compare(foojay.group(2), "1.0.0") < 0) {
            write("settings.gradle", settings.substring(0, foojay.start(2)) + "1.0.0" + settings.substring(foojay.end(2)));
            notes.add("Gradle's foojay toolchain resolver: " + foojay.group(2) + " to 1.0.0, the first release that runs on Gradle 9.");
        }
    }

    static Map<String, String> properties(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            String s = line.strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            out.put(s.substring(0, s.indexOf('=')).strip(), s.substring(s.indexOf('=') + 1).strip());
        }
        return out;
    }

    /** Sets, adds or removes (value null) gradle.properties keys, keeping comments and order. */
    static String setProperties(String text, Map<String, String> values) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            String key = s.substring(0, s.indexOf('=')).strip();
            if (!values.containsKey(key)) continue;
            done.add(key);
            if (values.get(key) == null) lines.remove(i--);
            else lines.set(i, key + "=" + values.get(key));
        }
        for (var entry : values.entrySet()) if (entry.getValue() != null && !done.contains(entry.getKey())) lines.add(
            entry.getKey() + "=" + entry.getValue()
        );
        return String.join("\n", lines) + "\n";
    }

    /** The span of a top-level block "name {" ... "}" in a build script, or null. */
    static int[] block(String text, String name) {
        // A top-level block first: buildscript { dependencies { ... } } is not the build's own dependencies.
        int[] top = block(text, name, "(?m)^");
        return top != null ? top : block(text, name, "(?m)^[ \\t]*");
    }

    static int[] block(String text, String name, String indent) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile(indent + Pattern.quote(name) + "\\s*\\{").matcher(text);
        while (m.find()) {
            if (!code[m.start() + m.group().indexOf(name.charAt(0))]) continue;
            int open = m.end() - 1, end = LoaderRules.close(text, code, open);
            if (end < 0) return null;
            int stop = end;
            while (stop < text.length() && text.charAt(stop) != '\n') stop++;
            return new int[] { m.start(), Math.min(text.length(), stop + 1), open, end };
        }
        return null;
    }

    /** A block body without the named blocks directly inside it: the settings written as plain lines. */
    static String withoutChildren(String body) {
        boolean[] code = LoaderRules.code(body);
        Matcher m = Pattern.compile("([\\w\"'${}]+)\\s*\\{").matcher(body);
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1, end = LoaderRules.close(body, code, open);
            if (end < 0) break;
            out.append(body, from, m.start());
            from = end;
        }
        return out.append(body.substring(from)).toString();
    }

    /** The next top-level block of that name starting at or after from, or null. */
    static int[] nextBlock(String text, String name, int from) {
        int[] found = block(text.substring(from), name, "(?m)^");
        return found == null ? null : new int[] { found[0] + from, found[1] + from, found[2] + from, found[3] + from };
    }

    /** The named blocks directly inside a block body: runs { client { ... } } gives client. */
    static Map<String, String> children(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean[] code = LoaderRules.code(body);
        Matcher m = Pattern.compile("([\\w\"'${}]+)\\s*\\{").matcher(body);
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1, end = LoaderRules.close(body, code, open);
            if (end < 0) break;
            // A name given twice (two loom blocks read as one) keeps both bodies.
            out.merge(m.group(1).replaceAll("[\"']", ""), body.substring(open + 1, end - 1), (a, b) -> a + "\n" + b);
            from = end;
        }
        return out;
    }

    String runToNeoForge(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = unit + unit + unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher arg = Pattern.compile("^(?:vmArg|jvmArg)\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            Matcher prop = Pattern.compile(
                "^property\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\s*\\)?$"
            ).matcher(line);
            Matcher dir = Pattern.compile("^runDir\\s*=?\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?$").matcher(line);
            Matcher program = Pattern.compile("^programArgs?\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.matches("(?:name|configName)\\s*=?\\s*\\(?\\s*[\"'].*")) out
                .append(in)
                .append("ideName = ")
                .append(line.replaceFirst("^(?:name|configName)\\s*=?\\s*\\(?\\s*", "").replaceFirst("\\)$", ""))
                .append('\n');
            else if (line.matches("ideConfigGenerated\\b.*")) continue;
            else if (arg.find()) {
                Matcher d = Pattern.compile("^-D([^=]+)=(.*)$").matcher(arg.group(1));
                if (d.find()) out
                    .append(in)
                    .append("systemProperty '")
                    .append(d.group(1))
                    .append("', '")
                    .append(d.group(2))
                    .append("'\n");
                else out.append(in).append("jvmArgument '").append(arg.group(1)).append("'\n");
            } else if (prop.find()) out
                .append(in)
                .append("systemProperty '")
                .append(prop.group(1))
                .append("', '")
                .append(prop.group(2))
                .append("'\n");
            else if (dir.find()) out
                .append(in)
                .append("gameDirectory = project.file('")
                .append(dir.group(1))
                .append("')\n");
            else if (program.find()) out.append(in).append("programArgument '").append(program.group(1)).append("'\n");
            else leave(
                "build.gradle",
                0,
                "loom run " + name + ": " + line,
                "this Loom run setting has no ModDevGradle counterpart in the rules"
            );
        }
        return out.toString();
    }

    static final Pattern LOOM_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"](?:net\\.fabricmc\\.)?fabric-loom(?:-remap)?['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    /**
     * Loom's dependency configurations in one statement as ModDevGradle's, wherever they are in it (implementation
     * include("x") nests one in another); a configuration call only, never part of a longer name (includedLibrary).
     */
    static String loomConfigurationsToNeoForge(String raw) {
        Matcher mod = Pattern.compile(
            "\\b(mod(?:Implementation|Api|CompileOnly|CompileOnlyApi|RuntimeOnly|LocalRuntime)|client(?:Implementation|Api|CompileOnly|RuntimeOnly)|include|localRuntime)(?=\\s*[(\"']|\\s+\\w)"
        ).matcher(raw);
        StringBuilder renamed = new StringBuilder();
        while (mod.find()) mod.appendReplacement(
            renamed,
            switch (mod.group(1)) {
                case "modImplementation" -> "implementation";
                case "modApi" -> "api";
                case "modCompileOnly", "modCompileOnlyApi" -> "compileOnly";
                case "modRuntimeOnly", "modLocalRuntime", "localRuntime", "clientRuntimeOnly" -> "runtimeOnly";
                case "clientImplementation" -> "implementation";
                case "clientApi" -> "api";
                case "clientCompileOnly" -> "compileOnly";
                default -> "jarJar";
            }
        );
        mod.appendTail(renamed);
        return renamed.toString();
    }

    /** A top-level build statement that configures Loom through its extensions: loom.runs { }, delete loom.runs.x.runDirectory. */
    static boolean loomStatement(String statement) {
        String first = statement.strip();
        return first.matches("(?s)(?:loom|fabricApi)\\s*\\.\\s*\\w.*") || first.matches("(?s)\\w+\\s+(?:loom|fabricApi)\\.\\w.*");
    }

    /**
     * From NeoForge for Minecraft 26.1 (26.1.1.15-beta, the first with net.neoforged.neoforge.common.OnlyInWarningsHandler)
     * a mod class marked @OnlyIn logs an ERROR on every launch: NeoForge no longer strips by side, and client code is kept
     * apart by where it is called from. Fabric's @Environment therefore has no counterpart there and goes.
     */
    static final String ONLY_IN_ERROR = "26.1";

    static final Pattern SIDE_ONLY = Pattern.compile(
        "(?m)^[ \\t]*@(?:net\\.fabricmc\\.api\\.)?Environment\\(\\s*(?:net\\.fabricmc\\.api\\.)?EnvType\\.\\w+\\s*\\)[ \\t]*\\n|" +
        "@(?:net\\.fabricmc\\.api\\.)?Environment\\(\\s*(?:net\\.fabricmc\\.api\\.)?EnvType\\.\\w+\\s*\\)\\s*"
    );

    /** A source without Fabric's @Environment(EnvType.X) marks (in code, not comments) and their import. */
    static String withoutSideOnly(String text) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = SIDE_ONLY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, code[m.start() + m.group().indexOf('@')] ? "" : Matcher.quoteReplacement(m.group()));
        m.appendTail(out);
        return LoaderRules.dropImport(out.toString(), "net.fabricmc.api.Environment");
    }

    /** Fabric's @Environment(EnvType.X) marks removed (NeoForge 26.1 and later), with their import. */
    void dropSideOnly() throws Exception {
        int dropped = 0;
        for (String file : javaFiles()) {
            String text = read(file), out = withoutSideOnly(text);
            if (out.equals(text)) continue;
            write(file, out);
            dropped += SIDE_ONLY.matcher(text).results().count();
            applied.computeIfAbsent("side-only-dropped", k -> new ArrayList<>()).add(file);
        }
        if (dropped > 0) notes.add(
            "@Environment: NeoForge for Minecraft " + ONLY_IN_ERROR + " and later logs an error for @OnlyIn on mod classes, " +
            "so the " + dropped + " side mark(s) are removed rather than written as @OnlyIn; client code stays apart by where it is called from."
        );
    }

    /** NightConfig as NeoForge 21.1.250's published module metadata requires it (com.electronwill.night-config core and toml). */
    static final String NIGHT_CONFIG = "3.8.3";

    /** The oldest Gradle ModDevGradle applies on: its own check refuses anything older. */
    static final String MODDEV_GRADLE_MIN = "8.8";

    void buildToNeoForge(String minecraft) throws Exception {
        String gradle = dropLoomTypes(read("build.gradle"));
        Matcher plugin = LOOM_PLUGIN.matcher(gradle);
        if (!plugin.find()) throw new IOException(
            "build.gradle does not apply the Fabric Loom plugin with id 'fabric-loom' or 'net.fabricmc.fabric-loom' in its plugins block."
        );
        String neo = latestNeoForge(minecraft);
        String mdg = stablePlugin("https://maven.neoforged.net/releases", "net.neoforged", "moddev-gradle");
        // ModDevGradle refuses to apply below Gradle 8.8 ("To use the NeoForge plugin, please use at least Gradle 8.8"),
        // which some of its releases do not state in their published metadata.
        String wrapper = project.wrapperVersion();
        if (wrapper != null && Versions.compare(wrapper, MODDEV_GRADLE_MIN) < 0) upgradeWrapper(MODDEV_GRADLE_MIN, "ModDevGradle " + mdg);
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            "id 'net.neoforged.moddev' version '" +
            mdg +
            "'" +
            gradle.substring(plugin.end());
        boolean split = false;
        StringBuilder runs = new StringBuilder();
        runs
            .append(unit)
            .append(unit)
            .append("client {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("client()\n")
            .append(unit)
            .append(unit)
            .append("}\n");
        runs
            .append(unit)
            .append(unit)
            .append("server {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("server()\n")
            .append(unit)
            .append(unit)
            .append("}\n");
        // Loom configuration written as top-level statements (loom.runs { }, loom.runs.configureEach { }, delete
        // loom.runs.x.runDirectory, fabricApi.configureTests { }): Loom's own extensions, gone with the plugin.
        gradle = dropStatements(gradle, Convert::loomStatement, "Fabric Loom's own configuration, which ModDevGradle has no counterpart for");
        // A build can configure Loom in more than one loom block; every one is read below, the first in place.
        int[] loom = block(gradle, "loom");
        for (int[] more = loom == null ? null : nextBlock(gradle, "loom", loom[1]); more != null; more = nextBlock(gradle, "loom", loom[1])) {
            String extra = gradle.substring(more[2] + 1, more[3] - 1);
            gradle = gradle.substring(0, more[0]) + gradle.substring(more[1]);
            gradle = gradle.substring(0, loom[3] - 1) + extra + gradle.substring(loom[3] - 1);
            loom = block(gradle, "loom");
        }
        int insertAt;
        if (loom != null) {
            String body = gradle.substring(loom[2] + 1, loom[3] - 1);
            Map<String, String> parts = children(body);
            for (var part : parts.entrySet()) {
                if (part.getKey().equals("runs")) {
                    for (var run : children(part.getValue()).entrySet()) {
                        if (run.getValue().contains("fabric-api.datagen")) {
                            Matcher dir = Pattern.compile("fabric-api\\.datagen\\.output-dir=([^\"'\\s]+)").matcher(
                                run.getValue()
                            );
                            if (datagenOutput != null && dir.find()) datagenOutput = dir
                                .group(1)
                                .replaceFirst("^\\$\\{(?:project\\.)?projectDir}/", "");
                            if (datagenOutput == null) leave(
                                "build.gradle",
                                0,
                                "loom run " + run.getKey(),
                                "it runs Fabric data generation, which was not converted"
                            );
                            continue;
                        }
                        if (Set.of("client", "server").contains(run.getKey())) continue;
                        runs
                            .append(unit)
                            .append(unit)
                            .append(run.getKey())
                            .append(" {\n")
                            .append(runToNeoForge(run.getKey(), run.getValue()))
                            .append(unit)
                            .append(unit)
                            .append("}\n");
                    }
                } else if (!part.getKey().equals("mods")) leave(
                    "build.gradle",
                    0,
                    "loom " + part.getKey() + " block",
                    "no ModDevGradle counterpart in the rules"
                );
            }
            String rest = withoutChildren(body);
            for (String raw : rest.lines().toList()) {
                String line = raw.strip().replaceAll("[\\w\"'${}]+\\s*\\{\\s*\\}", "").strip();
                if (line.isEmpty() || line.startsWith("//") || line.equals("}")) continue;
                if (line.startsWith("splitEnvironmentSourceSets")) split = true;
                else if (line.startsWith("accessWidenerPath") && widenerConverted) continue;
                else if (line.startsWith("accessWidenerPath")) leave(
                    "build.gradle",
                    0,
                    line,
                    "access wideners are not converted to NeoForge access transformers yet"
                );
                else leave("build.gradle", 0, "loom: " + line, "no ModDevGradle counterpart in the rules");
            }
            gradle = gradle.substring(0, loom[0]) + gradle.substring(loom[1]);
            insertAt = loom[0];
        } else {
            int[] deps = block(gradle, "dependencies");
            insertAt = deps == null ? gradle.length() : deps[0];
        }
        int[] fabricApi = block(gradle, "fabricApi");
        if (fabricApi != null) {
            if (gradle.substring(fabricApi[0], fabricApi[1]).contains("DataGeneration") && datagenOutput == null) leave(
                "build.gradle",
                0,
                "fabricApi { configureDataGeneration }",
                "Fabric data generation was not converted"
            );
            if (fabricApi[0] < insertAt) insertAt -= fabricApi[1] - fabricApi[0];
            gradle = gradle.substring(0, fabricApi[0]) + gradle.substring(fabricApi[1]);
        }
        if (datagenOutput != null) {
            runs
                .append(unit)
                .append(unit)
                .append("clientData {\n")
                .append(unit)
                .append(unit)
                .append(unit)
                .append("clientData()\n");
            runs
                .append(unit)
                .append(unit)
                .append(unit)
                .append("programArguments.addAll('--mod', '")
                .append(modid)
                .append("', '--all', '--output', file('")
                .append(datagenOutput)
                .append("').absolutePath, '--existing', file('src/main/resources').absolutePath)\n");
            runs.append(unit).append(unit).append("}\n");
        }
        StringBuilder neoForge = new StringBuilder("neoForge {\n");
        neoForge
            .append(unit)
            .append("version = project.neo_version\n\n")
            .append(unit)
            .append("runs {\n")
            .append(runs)
            .append(unit)
            .append("}\n\n");
        neoForge
            .append(unit)
            .append("mods {\n")
            .append(unit)
            .append(unit)
            .append(LoaderRules.quote(modid))
            .append(" {\n")
            .append(unit)
            .append(unit)
            .append(unit)
            .append("sourceSet sourceSets.main\n")
            .append(unit)
            .append(unit)
            .append("}\n")
            .append(unit)
            .append("}\n}\n");
        if (datagenOutput != null) neoForge
            .append(
                "\n// Generated data (runClientData) ships with the mod's own resources.\nsourceSets.main.resources {\n"
            )
            .append(unit)
            .append("srcDir '")
            .append(datagenOutput)
            .append("'\n")
            .append(unit)
            .append("exclude '.cache'\n}\n");
        if (split) neoForge.append(
            "\n// NeoForge has no split client source set: the client sources build with the rest.\nsourceSets.main.java.srcDir 'src/client/java'\nsourceSets.main.resources.srcDir 'src/client/resources'\n"
        );
        gradle =
            gradle.substring(0, insertAt) +
            neoForge +
            (insertAt < gradle.length() && gradle.charAt(insertAt) != '\n' ? "\n" : "") +
            gradle.substring(insertAt);
        int[] deps = block(gradle, "dependencies");
        if (deps != null) {
            // Whole statements, not lines: a dependency with a closure ({ exclude ... }) is one statement.
            List<String> kept = new ArrayList<>();
            boolean dropping = false;
            for (String raw : statements(gradle.substring(deps[2] + 1, deps[3] - 1))) {
                // What the statement depends on is in its first line; its closure (exclude(group: "net.fabricmc.fabric-api")) is not.
                String line = raw.strip().lines().findFirst().orElse("");
                boolean drop =
                    line.matches("(?s)(?:minecraft|mappings)\\b.*") ||
                    line.contains("net.fabricmc:fabric-loader") ||
                    line.contains("net.fabricmc.fabric-api") ||
                    line.contains("fabricApi.module") ||
                    line.contains("com.terraformersmc:modmenu") ||
                    // Loom's own configurations for its production run tasks, which go with Loom.
                    line.matches("(?s)productionRuntime(?:Mods)?\\b.*");
                if (line.matches("(?s)productionRuntime(?:Mods)?\\b.*")) notes.add(
                    "Build: removed `" + line + "` (Loom's production run configuration)."
                );
                // A Fabric-only library whose API the Fabric bridge mirrors on NeoForge (bridges[].replaces).
                String replaced = bridgeReplaces(line);
                if (replaced != null) {
                    drop = true;
                    notes.add("Build: removed `" + line + "`; the Fabric bridge mirrors its API (" + replaced + ") on NeoForge's.");
                    applied.computeIfAbsent("fabric-bridge-dependency", k -> new ArrayList<>()).add("build.gradle: " + replaced);
                }
                if (drop) {
                    // A comment that only introduced the dropped statements goes with them.
                    while (
                        !dropping &&
                        kept.size() >= 2 &&
                        kept.get(kept.size() - 1).strip().startsWith("//") &&
                        kept.get(kept.size() - 2).isBlank()
                    ) kept.remove(kept.size() - 1);
                    dropping = true;
                    continue;
                }
                dropping = false;
                if (line.startsWith("//") || line.startsWith("/*")) {
                    kept.add(raw);
                    continue;
                }
                // A Fabric build of another mod moves to its NeoForge build; Loom's own configurations are renamed after.
                raw = dependencyLine(raw, "fabric", "neoforge", repositories(gradle), false);
                kept.add(loomConfigurationsToNeoForge(raw));
            }
            String inner = String.join("", kept);
            if (!inner.endsWith("\n")) inner += "\n";
            gradle =
                gradle.substring(0, deps[2] + 1) + "\n" + inner.replaceAll("^\\n+", "") + gradle.substring(deps[3] - 1);
        }
        gradle = gradle
            .replace("filesMatching(\"fabric.mod.json\")", "filesMatching(\"META-INF/neoforge.mods.toml\")")
            .replace("filesMatching('fabric.mod.json')", "filesMatching('META-INF/neoforge.mods.toml')");
        gradle = gradle.replaceAll("\\bremapJar\\b", "jar").replaceAll("\\bremapSourcesJar\\b", "sourcesJar");
        // The properties the Fabric build had and NeoForge's has not: a map entry that only passed one to the metadata goes.
        List<String> droppedEntries = new ArrayList<>();
        gradle = dropPropertyEntries(gradle, FABRIC_PROPERTIES, droppedEntries);
        if (!droppedEntries.isEmpty()) notes.add("Build: removed " + String.join(", ", droppedEntries) + " (Fabric's properties, which only filled fabric.mod.json).");
        gradle = publishLoader(gradle, "fabric", "neoforge");
        gradle = dropPluginCode(gradle, "net.fabricmc.loom");
        // A line of code (not a comment) that still names Fabric.
        boolean[] gradleCode = LoaderRules.code(gradle);
        for (Matcher named = Pattern.compile("(?m)^.*fabricmc.*$").matcher(gradle); named.find(); ) {
            int at = gradle.indexOf("fabricmc", named.start());
            if (gradleCode[at]) leave("build.gradle", 0, named.group().strip(), "still names Fabric; no rule changes it");
        }
        write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        for (String key : FABRIC_PROPERTIES) props.put(key, null);
        props.put("neo_version", neo);
        write("gradle.properties", setProperties(read("gradle.properties"), props));
        String settings = read("settings.gradle");
        if (settings.contains("pluginManagement") && !settings.contains("gradlePluginPortal()")) {
            write(
                "settings.gradle",
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)",
                    "$1\n" + unit + unit + "gradlePluginPortal()"
                )
            );
        }
        notes.add(
            "Build: ModDevGradle " +
            mdg +
            " with NeoForge " +
            neo +
            " (the newest release for Minecraft " +
            minecraft +
            ")."
        );
    }

    // ---- NeoForge to Fabric ------------------------------------------------------------------------------

    /** A small TOML reader for neoforge.mods.toml: top-level keys, [[mods]], [[dependencies.id]] and [[mixins]] tables. */
    static Map<String, Object> toml(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> current = out;
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher table = Pattern.compile("^\\[\\[([\\w.\\-]+)]]").matcher(line);
            if (table.find()) {
                current = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) out.computeIfAbsent("[[" + table.group(1) + "]]", k ->
                    new ArrayList<>()
                );
                list.add(current);
                continue;
            }
            if (line.startsWith("[")) {
                current = new LinkedHashMap<>();
                out.put(line, current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip(), value = line.substring(eq + 1).strip();
            for (String quote : List.of("'''", "\"\"\"")) if (value.startsWith(quote)) {
                StringBuilder s = new StringBuilder(value.substring(3));
                while (!s.toString().contains(quote) && i + 1 < lines.size()) s.append('\n').append(lines.get(++i));
                String all = s.toString();
                value = all.substring(0, all.indexOf(quote)).replaceAll("^\\n", "").replaceAll("\\n$", "");
                current.put(key, value);
                key = null;
                break;
            }
            if (key == null) continue;
            if (value.startsWith("\"")) {
                Matcher m = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value);
                current.put(key, m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : value);
            } else if (value.startsWith("'")) current.put(key, value.substring(1, value.indexOf('\'', 1)));
            else current.put(key, value.replaceAll("\\s*#.*$", ""));
        }
        return out;
    }

    void toFabric(String minecraft) throws Exception {
        String metaFile = Files.isRegularFile(project.root.resolve(NEO_META)) ? NEO_META : NEO_TEMPLATE;
        String metaText = read(metaFile);
        String expanded = expandProperties(metaText);
        metadataExpanded = !expanded.equals(metaText);
        if (metadataExpanded) notes.add(
            metaFile +
            " is expanded by the build from gradle.properties; fabric.mod.json gets the expanded values, and the build's expand step for it is removed."
        );
        Map<String, Object> toml = toml(expanded);
        List<Object> mods = toml.get("[[mods]]") instanceof List<?> l ? Json.array(l) : List.of();
        if (mods.size() != 1) throw new IOException(
            "neoforge.mods.toml declares " + mods.size() + " mods; convert moves a jar with one mod."
        );
        Map<String, Object> mod = Json.object(mods.get(0));
        modid = Json.text(mod, "modId", "");
        fabricId = toml.get("[modproperties." + modid + "]") instanceof Map<?, ?> properties ? Json.text(Json.object(properties), "fabricId", modid) : modid;
        // The @Mod classes first: their annotation names Dist, which the source rules would otherwise rename.
        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        String menu = null;
        unbridgeFabric();
        prepareBridge(minecraft);
        for (String file : javaFiles()) {
            String text = read(file);
            Matcher annotation = Pattern.compile(
                "@Mod\\(\\s*(?:value\\s*=\\s*)?([^,)]+?)\\s*(?:,\\s*dist\\s*=\\s*Dist\\.(\\w+)\\s*)?\\)"
            ).matcher(text);
            if (!annotation.find()) continue;
            String name = file.replaceFirst("^src/\\w+/java/", "").replaceAll("\\.java$", "").replace('/', '.');
            String dist = annotation.group(2);
            String api = dist == null
                ? "ModInitializer"
                : dist.equals("CLIENT") ? "ClientModInitializer" : "DedicatedServerModInitializer";
            String method = dist == null
                ? "onInitialize"
                : dist.equals("CLIENT") ? "onInitializeClient" : "onInitializeServer";
            Matcher menuLine = Pattern.compile(
                "container\\.registerExtensionPoint\\(IConfigScreenFactory\\.class, \\(modContainer, parent\\) -> new ([\\w.]+)\\(\\)\\.getModConfigScreenFactory\\(\\)\\.apply\\(parent\\)\\);\\n"
            ).matcher(text);
            if (menuLine.find()) {
                menu = menuLine.group(1);
                text = text.substring(0, text.lastIndexOf('\n', menuLine.start()) + 1) + text.substring(menuLine.end());
                text = LoaderRules.dropImport(text, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
            }
            Matcher made = Pattern.compile(
                "/\\*\\* NeoForge's entry for (\\w+), which registers in its static fields"
            ).matcher(text);
            if (made.find()) {
                String target = file.substring(0, file.lastIndexOf('/') + 1) + made.group(1) + ".java";
                if (
                    !exists(target) ||
                    !text.contains("new " + made.group(1) + "()." + method + "()")
                ) {
                    leave(
                        file,
                        0,
                        "@Mod class " + made.group(1) + "NeoForge",
                        "it names an entry class this conversion cannot find"
                    );
                    continue;
                }
                delete(file);
                write(target, implementFabric(read(target), made.group(1), api, method));
                entrypoints
                    .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                        new ArrayList<>()
                    )
                    .add(name.substring(0, name.lastIndexOf('.') + 1) + made.group(1));
                applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(target);
                continue;
            }
            String converted = entryToFabric(file, text, annotation, api, method);
            if (converted == null) continue;
            write(file, converted);
            entrypoints
                .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                    new ArrayList<>()
                )
                .add(name);
            applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
        }
        if (bridgeUsed && !clientEntry) {
            // NeoForge posts FMLClientSetupEvent and runs client subscribers without a client @Mod class; Fabric needs an entrypoint for that.
            String name = bridgePackage + ".ClientEntrypoint";
            StringBuilder body = new StringBuilder();
            for (Subscriber s : subscribers) if (s.client()) body.append("\t\tBridge.subscribe(").append(s.type()).append(".class);\n");
            write(
                bridgeFolder() + "/ClientEntrypoint.java",
                "package " +
                bridgePackage +
                ";\n\n/** The client entrypoint modaccel convert added: NeoForge's client subscribers and FMLClientSetupEvent. */\npublic final class ClientEntrypoint implements net.fabricmc.api.ClientModInitializer {\n\n\t@Override\n\tpublic void onInitializeClient() {\n" +
                body +
                "\t\tClientBridge.client(" +
                LoaderRules.quote(modid) +
                ");\n\t}\n}\n"
            );
            entrypoints.computeIfAbsent("client", k -> new ArrayList<>()).add(name);
        }
        rewriteSources("neoforge", "fabric");
        dropNeoForgeTabOrder();
        if (bridgeUsed) applyBridge();
        if (menu != null) {
            String file = classFile(menu);
            if (file != null) {
                write(file, LoaderRules.modMenuToFabric(read(file)));
                for (String other : javaFiles()) write(
                    other,
                    read(other).replace(".getModConfigScreenFactory().apply(", ".getModConfigScreenFactory().create(")
                );
                entrypoints.computeIfAbsent("modmenu", k -> new ArrayList<>()).add(menu);
                applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
            }
        }
        // Access transformer lines a rule added for NeoForge go away with the code that needed them; the mod's own
        // lines become a Fabric access widener.
        Set<String> known = new HashSet<>();
        for (Object rule : LoaderRules.list(rules.get("expressions"))) if (
            Json.object(rule).get("accessTransformers") instanceof Map<?, ?> at
        ) known.addAll(LoaderRules.strings(Json.object(at).get("neoforge")));
        if (exists(AT_FILE)) {
            List<String> keep = new ArrayList<>();
            for (String line : read(AT_FILE).lines().toList()) if (!known.contains(line.strip())) keep.add(line);
            if (!keep.stream().allMatch(l -> l.isBlank() || l.strip().startsWith("#"))) {
                List<String> unconverted = new ArrayList<>();
                String widener = transformerToWidener(
                    String.join("\n", keep),
                    Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) >= 0,
                    unconverted,
                    gameFieldTypes(minecraft)
                );
                for (String u : unconverted) leave(AT_FILE, 0, u, "an access transformer line with no access widener counterpart");
                fabricWidener = fabricId + ".accesswidener";
                write("src/main/resources/" + fabricWidener, widener);
                widenerConverted = true;
                applied.computeIfAbsent("access-widener", k -> new ArrayList<>()).add(AT_FILE);
            }
            delete(AT_FILE);
        }
        toml.remove("[[accessTransformers]]");
        buildToFabric(minecraft, menu != null);
        write("src/main/resources/fabric.mod.json", fabricJson(toml, mod, entrypoints, minecraft, menu != null));
        delete(metaFile);
        leftovers(List.of("net.neoforged."));
    }

    /**
     * NeoForge adds withTabsBefore and withTabsAfter to the game's creative tab builder to order tabs; Fabric's builder has
     * neither, so the calls go (the tab keeps its items and title, and takes the next free place) and a note says so.
     */
    void dropNeoForgeTabOrder() throws Exception {
        int dropped = 0;
        for (String file : javaFiles()) {
            String text = read(file);
            String out = dropCalls(text, List.of("withTabsBefore", "withTabsAfter"));
            if (!out.equals(text)) {
                write(file, out);
                dropped++;
                applied.computeIfAbsent("creative-tab-order", k -> new ArrayList<>()).add(file);
            }
        }
        if (dropped > 0) notes.add(
            "Creative tab order: NeoForge's withTabsBefore and withTabsAfter have no Fabric counterpart, so the converted tabs take the next free place (" +
            dropped +
            " file(s))."
        );
    }

    /** Removes chained calls .name(...) (balanced arguments, code only) from a text. */
    static String dropCalls(String text, List<String> names) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\s*\\.\\s*(?:" + String.join("|", names) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int open = m.end() - 1;
            if (!code[open] || open < last) continue;
            int end = LoaderRules.close(text, code, open);
            if (end < 0) continue;
            out.append(text, last, m.start());
            last = end;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    // ---- the NeoForge bridge on Fabric ---------------------------------------------------------------------

    /** A class NeoForge finds by its @EventBusSubscriber annotation: its name in source and whether only the client has it. */
    record Subscriber(String type, boolean client) {}

    /** The package the bridge goes in (beside the mod's first @Mod class), or null when there is no bridge for this game. */
    String bridgePackage;
    boolean bridgeUsed, clientEntry;
    final List<Subscriber> subscribers = new ArrayList<>();
    Map<String, Object> bridge;

    /**
     * Where the bridge goes: it holds registration and the event bus, which both sides use, so beside the common @Mod
     * class (one with no dist), else in the package every @Mod class shares; null when there is no @Mod class.
     */
    static String bridgePackage(List<String> sources) {
        List<String> common = new ArrayList<>(), all = new ArrayList<>();
        Pattern pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
        for (String text : sources) {
            Matcher annotation = Pattern.compile("@Mod\\(([^)]*)\\)").matcher(text);
            Matcher p = pkg.matcher(text);
            if (!annotation.find() || !p.find()) continue;
            all.add(p.group(1));
            if (!annotation.group(1).contains("dist")) common.add(p.group(1));
        }
        if (!common.isEmpty()) return common.stream().min(Comparator.comparingInt(String::length)).orElseThrow() + ".loaderbridge";
        if (all.isEmpty()) return null;
        String shared = all.get(0);
        for (String other : all) while (!(other.equals(shared) || other.startsWith(shared + ".")) && shared.contains(".")) shared = shared.substring(0, shared.lastIndexOf('.'));
        // Only client @Mod classes: the bridge still serves both sides, so not in their client package.
        if (shared.endsWith(".client")) shared = shared.substring(0, shared.length() - ".client".length());
        return shared + ".loaderbridge";
    }

    /** The bridge's version layer for a game older than the bridge's own files, or null (knowledge/loaders.json bridges[].layers). */
    Map<String, Object> bridgeLayer;

    /**
     * The newest layer written for a game version at or below this one, when the version is older than the bridge's own
     * files: its files replace the bridge's files of the same name, since the game's and Fabric API's names differ there.
     */
    static Map<String, Object> bridgeLayer(Map<String, Object> bridge, String minecraft) {
        if (Versions.compare(minecraft, Json.text(bridge, "minecraft", "99")) >= 0) return null;
        Map<String, Object> best = null;
        for (Object item : LoaderRules.list(bridge.get("layers"))) {
            var layer = Json.object(item);
            String from = Json.text(layer, "minecraft", "99");
            if (Versions.compare(minecraft, from) < 0) continue;
            // A layer is checked against the releases of its own range only.
            if (layer.get("below") != null && Versions.compare(minecraft, Json.text(layer, "below", "")) >= 0) continue;
            if (best == null || Versions.compare(from, Json.text(best, "minecraft", "")) > 0) best = layer;
        }
        return best;
    }

    String bridgeFolder() {
        return "src/main/java/" + bridgePackage.replace('.', '/');
    }

    /**
     * The NeoForge bridge (knowledge/loaders.json "bridges"): NeoForge's registration, event bus, mod container, config
     * and the commonest lifecycle events written on Fabric's API, for the Minecraft versions it is written for. A mod
     * written for NeoForge first keeps its own code; its entrypoints get what NeoForge passed their constructors from it.
     */
    void prepareBridge(String minecraft) throws Exception {
        for (Object item : LoaderRules.list(rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"neoforge".equals(b.get("from")) || !"fabric".equals(b.get("to"))) continue;
            Map<String, Object> layer = bridgeLayer(b, minecraft);
            if (Versions.compare(minecraft, Json.text(b, "minecraft", "99")) < 0 && layer == null) continue;
            bridge = b;
            bridgeLayer = layer;
        }
        if (bridge == null) return;
        bridgePackage = bridgePackage(javaFiles().stream().map(f -> {
            try {
                return read(f);
            } catch (IOException e) {
                return "";
            }
        }).toList());
        for (String file : javaFiles()) {
            String text = read(file);
            Matcher mod = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            Matcher s = Pattern.compile(
                "@EventBusSubscriber\\b(\\s*\\([^)]*\\))?\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract)\\s+)*(?:class|interface|enum|record)\\s+(\\w+)"
            ).matcher(text);
            String pkg = mod.reset().find() ? mod.group(1) + "." : "";
            String top = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
            while (s.find()) {
                String args = s.group(1) == null ? "" : s.group(1);
                boolean client = args.contains("Dist.CLIENT") && !args.contains("DEDICATED_SERVER");
                String type = pkg + (s.group(2).equals(top) ? top : top + "." + s.group(2));
                subscribers.add(new Subscriber(type, client));
            }
        }
    }

    /**
     * A NeoForge @Mod class as a Fabric entrypoint through the bridge: its constructor's body runs in the entrypoint
     * method, with the constructor's IEventBus and ModContainer parameters as locals the bridge supplies; the
     * entrypoint then registers the @EventBusSubscriber classes of its side and ends as NeoForge's start-up does.
     */
    String bridgeEntry(String file, String text, Matcher annotation, String simple, String api, String method) {
        boolean client = api.equals("ClientModInitializer");
        String id = annotation.group(1).strip();
        unit = LoaderRules.unit(text);
        boolean[] code = LoaderRules.code(text);
        Matcher ctor = Pattern.compile(
            "(?m)^([ \\t]*)(?:(?:public|protected|private)\\s+)?" + simple + "\\s*\\(([^)]*)\\)\\s*\\{"
        ).matcher(text);
        int start = -1, open = -1, end = -1;
        String indent = unit, params = "";
        int found = 0;
        while (ctor.find()) if (code[ctor.start(2) - 1]) {
            found++;
            start = ctor.start();
            open = ctor.end() - 1;
            indent = ctor.group(1);
            params = ctor.group(2).strip();
        }
        if (found > 1) {
            leave(file, LoaderRules.line(text, annotation.start()), "@Mod class " + simple, "it has more than one constructor; Fabric makes an entrypoint with none");
            return null;
        }
        StringBuilder locals = new StringBuilder();
        String in = indent + unit;
        if (!params.isEmpty()) for (String param : params.split(",")) {
            String[] p = param.strip().replaceAll("^final\\s+", "").split("\\s+");
            if (p.length != 2) return leaveEntry(file, text, annotation, simple, "parameter " + param.strip());
            switch (p[0]) {
                case "IEventBus" -> locals.append(in).append("IEventBus ").append(p[1]).append(" = Bridge.bus();\n");
                case "ModContainer" -> locals.append(in).append("ModContainer ").append(p[1]).append(" = Bridge.container(").append(id).append(");\n");
                case "Dist" -> locals.append(in).append("Dist ").append(p[1]).append(" = Dist.").append(client ? "CLIENT" : "DEDICATED_SERVER").append(";\n");
                default -> {
                    return leaveEntry(file, text, annotation, simple, "parameter " + param.strip());
                }
            }
        }
        String body = "";
        String before = text, after = "";
        if (start >= 0) {
            end = LoaderRules.close(text, code, open);
            body = text.substring(open + 1, end - 1).replaceAll("^\\s*\\n", "").replaceAll("\\s+$", "");
            before = text.substring(0, start);
            after = text.substring(end);
        } else {
            // No constructor: the entrypoint method goes at the end of the class.
            int last = text.lastIndexOf('}');
            before = text.substring(0, last).replaceAll("\\s+$", "") + "\n";
            after = "\n" + text.substring(last);
            indent = unit;
            in = indent + unit;
        }
        StringBuilder entry = new StringBuilder();
        entry.append(indent).append("/** NeoForge's constructor of this @Mod class, run as Fabric's entrypoint (modaccel convert, NeoForge bridge). */\n");
        entry.append(indent).append("public void ").append(method).append("() {\n").append(locals);
        if (!body.isEmpty()) entry.append(body.replaceAll("(?m)^" + Pattern.quote(indent + unit), in)).append('\n');
        for (Subscriber s : subscribers) if (s.client() == client) entry.append(in).append("Bridge.subscribe(").append(s.type()).append(".class);\n");
        entry.append(in).append(client ? "ClientBridge.client(" : "Bridge.common(").append(id).append(");\n");
        entry.append(indent).append("}");
        String out = before + entry + after;
        int lineStart = out.lastIndexOf('\n', out.indexOf(annotation.group())) + 1;
        int lineEnd = out.indexOf('\n', out.indexOf(annotation.group())) + 1;
        out = out.substring(0, lineStart) + out.substring(lineEnd);
        out = implementFabric(out, simple, api, method);
        out = LoaderRules.dropImport(out, "net.neoforged.fml.common.Mod");
        out = LoaderRules.addImport(out, bridgePackage + ".Bridge");
        if (client) {
            out = LoaderRules.addImport(out, bridgePackage + ".ClientBridge");
            clientEntry = true;
        }
        bridgeUsed = true;
        applied.computeIfAbsent("neoforge-bridge-entrypoint", k -> new ArrayList<>()).add(file);
        return out;
    }

    String leaveEntry(String file, String text, Matcher annotation, String simple, String what) {
        leave(file, LoaderRules.line(text, annotation.start()), "@Mod class " + simple, "its constructor takes " + what + ", which the NeoForge bridge does not supply");
        return null;
    }

    /**
     * The bridge's own files, and every import of a NeoForge class it mirrors pointed at it. Listeners added by method
     * reference or typed lambda get their event class written in (NeoForge reads it from the generic signature).
     */
    void applyBridge() throws Exception {
        Map<String, Object> classes = Json.object(bridge.get("classes"));
        Path templates = build.harness.resolve(Json.text(bridge, "folder", ""));
        Set<String> attachmentFields = new TreeSet<>();
        for (String file : javaFiles()) for (Matcher f = ATTACHMENT_FIELD.matcher(read(file)); f.find(); ) attachmentFields.add(f.group(1));
        List<String> drops = LoaderRules.strings(bridgeLayer == null ? null : bridgeLayer.get("drops"));
        for (String file : javaFiles()) {
            String text = read(file);
            String out = text;
            // Plain, static and nested-class imports of a mirrored class (import static ...FluidType.BUCKET_VOLUME;).
            for (var c : classes.entrySet()) if (!dropped(drops, String.valueOf(c.getValue()).replace('.', '/') + ".java")) out = out.replaceAll(
                "(?m)^import(\\s+static)?\\s+" + Pattern.quote(c.getKey()) + "((?:\\.\\w+)*(?:\\.\\*)?)\\s*;",
                "import$1 " + Matcher.quoteReplacement(bridgePackage + "." + c.getValue()) + "$2;"
            );
            out = eventClassesInListeners(file, out);
            out = sideQueries(out);
            String held = attachmentCalls(out, attachmentFields);
            if (!held.equals(out)) out = LoaderRules.addImport(held, bridgePackage + ".Attachments");
            if (!out.equals(text)) {
                write(file, out);
                applied.computeIfAbsent("neoforge-bridge", k -> new ArrayList<>()).add(file);
            }
        }
        Map<String, Path> files = bridgeTemplates(templates, bridgeLayer == null ? null : build.harness.resolve(Json.text(bridgeLayer, "folder", "")));
        files.keySet().removeIf(rel -> dropped(drops, rel));
        if (bridgeLayer != null) {
            notes.add(
                "NeoForge bridge: the layer for Minecraft " +
                Json.text(bridgeLayer, "minecraft", "") +
                " (" +
                Json.text(bridgeLayer, "folder", "") +
                ") replaces the files whose game or Fabric API names differ there."
            );
        }
        for (var template : files.entrySet()) write(
            bridgeFolder() + "/" + template.getKey(),
            fillBridge(Files.readString(template.getValue(), StandardCharsets.UTF_8), bridgePackage)
        );
        bridgeMixinConfig = bridgeMixins(bridge, bridgeLayer, bridgePackage, javaFiles().stream().filter(f -> !f.startsWith(bridgeFolder() + "/")).toList());
        notes.add(
            "NeoForge bridge: " +
            bridgePackage +
            " holds NeoForge's registration, event bus, mod container, config and lifecycle events written on Fabric's API, for the code that uses them."
        );
    }

    /**
     * NeoForge's Dist answers isClient() and isDedicatedServer(); on Fabric the side is EnvType, which has neither (the
     * physical-side rule makes Dist EnvType). The side the bridge's FMLEnvironment and FMLLoader give is compared instead.
     */
    static String sideQueries(String text) {
        Matcher m = Pattern.compile(
            "\\b(FMLEnvironment\\.getDist\\(\\)|FMLEnvironment\\.dist|FMLLoader\\.getCurrent\\(\\)\\.getDist\\(\\)|FMLLoader\\.getDist\\(\\))\\.(isClient|isDedicatedServer)\\(\\)"
        ).matcher(text);
        if (!m.find()) return text;
        String out = m.reset().replaceAll(r -> Matcher.quoteReplacement("(" + r.group(1) + " == EnvType." + (r.group(2).equals("isClient") ? "CLIENT" : "SERVER") + ")"));
        return LoaderRules.addImport(out, "net.fabricmc.api.EnvType");
    }

    /** A field the mod declares as a NeoForge attachment type (Supplier<AttachmentType<X>> NAME = ..., DeferredHolder<...> NAME = ...). */
    static final Pattern ATTACHMENT_FIELD = Pattern.compile("\\bAttachmentType<[^;=]*>\\s+(\\w+)\\s*=");

    static final List<String> HOLDER_METHODS = List.of("getData", "hasData", "setData", "removeData", "getExistingData", "getExistingDataOrNull");

    /**
     * NeoForge adds IAttachmentHolder's methods to the game's entities, block entities, levels and chunks; Fabric API adds
     * its AttachmentTarget to the same classes under other names. holder.getData(TYPE) becomes Attachments.getData(holder,
     * TYPE) when TYPE (or TYPE.get()) is a field the mod declares as an attachment type, so other getData methods are left.
     */
    static String attachmentCalls(String text, Set<String> fields) {
        if (fields.isEmpty()) return text;
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\.\\s*(" + String.join("|", HOLDER_METHODS) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int dot = m.start();
            if (!code[dot] || dot < last) continue;
            int open = m.end() - 1;
            int close = LoaderRules.close(text, code, open);
            if (close < 0) continue;
            String args = text.substring(open + 1, close - 1);
            List<String> parts = LoaderRules.split(args);
            if (parts.isEmpty()) continue;
            String type = parts.get(0).replaceAll("\\s*\\.\\s*get\\(\\)$", "");
            if (!fields.contains(type.replaceAll("^.*?(\\w+)$", "$1"))) continue;
            int start = LoaderRules.chainStart(text, code, dot);
            if (start >= dot || start < last) continue;
            out.append(text, last, start).append("Attachments.").append(m.group(1)).append('(').append(text, start, dot).append(", ").append(args).append(')');
            last = close;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** The way back: Attachments.getData(holder, TYPE) is holder.getData(TYPE) again. */
    static String attachmentCallsBack(String text) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\bAttachments\\.(" + String.join("|", HOLDER_METHODS) + ")\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (!code[m.start()] || m.start() < last) continue;
            int open = m.end() - 1;
            int close = LoaderRules.close(text, code, open);
            if (close < 0) continue;
            List<String> parts = LoaderRules.split(text.substring(open + 1, close - 1));
            if (parts.size() < 2) continue;
            out.append(text, last, m.start()).append(parts.get(0)).append('.').append(m.group(1)).append('(').append(String.join(", ", parts.subList(1, parts.size()))).append(')');
            last = close;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** The mixin config of the bridge's mixins this mod needs (src/main/resources), or null when it needs none. */
    String bridgeMixinConfig;

    /** Interfaces the bridge's mixins add to game classes, by class (fabric.mod.json loom:injected_interfaces). */
    final Map<String, List<String>> bridgeInjected = new LinkedHashMap<>();

    /** fabric.mod.json's custom values without the NeoForge bridge's injected interfaces, which go with the bridge. */
    static Map<String, Object> withoutBridgeInterfaces(Map<String, Object> custom) {
        if (!(custom.get("loom:injected_interfaces") instanceof Map<?, ?> injected)) return custom;
        Map<String, Object> out = new LinkedHashMap<>(custom), kept = new LinkedHashMap<>();
        for (var e : Json.object(injected).entrySet()) {
            List<Object> names = new ArrayList<>();
            for (Object name : e.getValue() instanceof List<?> list ? Json.array(list) : List.of()) if (!String.valueOf(name).contains("/loaderbridge/")) names.add(name);
            if (!names.isEmpty()) kept.put(e.getKey(), names);
        }
        if (kept.isEmpty()) out.remove("loom:injected_interfaces");
        else out.put("loom:injected_interfaces", kept);
        return out;
    }

    /** A bridge template with the mod's own names written in. */
    String fillBridge(String template, String pkg) {
        // Line ends as the bridge's files are written (a Windows checkout may give the templates CRLF).
        return template.replace("\r\n", "\n").replace("${package}", pkg).replace("${modid}", modid == null ? "" : modid);
    }

    /**
     * Whether a version layer leaves out one of the bridge's files (bridges[].layers[].drops: path prefixes under the
     * bridge folder, such as "transfer/"): parts of the bridge written for a newer game than the layer's.
     */
    static boolean dropped(List<String> drops, String rel) {
        for (String prefix : drops) if (rel.startsWith(prefix)) return true;
        return false;
    }

    /**
     * A bridge's template files by their path under its folder (sub-packages kept), the version layer's files replacing
     * the bridge's own of the same path. Version layers are the bridge folder's sub-folders named by a Minecraft version.
     */
    static Map<String, Path> bridgeTemplates(Path folder, Path layer) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        for (Path root : layer == null ? List.of(folder) : List.of(folder, layer)) {
            try (var walk = Files.walk(root)) {
                for (Path template : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path rel = root.relativize(template);
                    // The bridge's own folder holds its version layers (1.21.1/...): those are read only as the layer.
                    if (root == folder && rel.getNameCount() > 1 && Character.isDigit(rel.getName(0).toString().charAt(0))) continue;
                    files.put(rel.toString().replace('\\', '/'), template);
                }
            }
        }
        return files;
    }

    /**
     * The bridge's mixins (knowledge/loaders.json bridges[].mixins, or its version layer's) that the converted code needs:
     * a mixin is listed when a source file outside the bridge imports one of the bridge classes it fires ("for"). They go
     * in one mixin config, so a mod that uses none of those events gets none of the bridge's mixins. Returns its file name.
     */
    String bridgeMixins(Map<String, Object> b, Map<String, Object> layer, String pkg, List<String> sources) throws Exception {
        Object list = layer != null && layer.get("mixins") != null ? layer.get("mixins") : b.get("mixins");
        List<String> common = new ArrayList<>(), client = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (String file : sources) all.append(read(file)).append('\n');
        String text = all.toString();
        for (Object item : LoaderRules.list(list)) {
            var mixin = Json.object(item);
            boolean needed = false;
            for (String target : LoaderRules.strings(mixin.get("for"))) if (
                Pattern.compile("(?m)^import\\s+(?:static\\s+)?" + Pattern.quote(pkg + "." + target) + "\\b").matcher(text).find()
            ) needed = true;
            if (!needed) continue;
            (Boolean.TRUE.equals(mixin.get("client")) ? client : common).add(Json.text(mixin, "mixin", ""));
            // A mixin that adds one of the bridge's interfaces to a game class: Loom injects it for the mod's compile.
            if (mixin.get("injects") instanceof Map<?, ?> injects) for (var e : Json.object(injects).entrySet()) bridgeInjected
                .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                .add((pkg + "." + e.getValue()).replace('.', '/'));
        }
        if (common.isEmpty() && client.isEmpty()) return null;
        String name = (fabricId == null || fabricId.isEmpty() ? modid : fabricId) + "." + pkg.substring(pkg.lastIndexOf('.') + 1) + ".mixins.json";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("required", true);
        config.put("minVersion", "0.8");
        config.put("package", pkg + ".mixin");
        config.put("compatibilityLevel", Versions.compare(gameVersion, Route.FIRST_UNOBFUSCATED) >= 0 ? "JAVA_25" : "JAVA_21");
        if (!common.isEmpty()) config.put("mixins", common);
        if (!client.isEmpty()) config.put("client", client);
        config.put("injectors", Json.map("defaultRequire", 1));
        write("src/main/resources/" + name, pretty(config, "") + "\n");
        notes.add("Bridge mixins (" + name + "): " + String.join(", ", common) + (client.isEmpty() ? "" : (common.isEmpty() ? "" : ", ") + "client " + String.join(", ", client)) + ", which fire the events the code listens to at the point the other loader fires them.");
        applied.computeIfAbsent("bridge-mixins", k -> new ArrayList<>()).add("src/main/resources/" + name);
        return name;
    }

    /** addListener(this::method) and addListener((Type event) -> ...) with the event class written in, as the bridge's IEventBus takes it. */
    String eventClassesInListeners(String file, String text) throws IOException {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile(
            "\\.addListener\\(\\s*((?:EventPriority\\.\\w+\\s*,\\s*)?(?:(?:true|false)\\s*,\\s*)?)(?:(this|[A-Z]\\w*(?:\\.[A-Z]\\w*)*)::(\\w+)|\\(\\s*(?:final\\s+)?([A-Z][\\w.]*)(?:<[^>]*>)?\\s+\\w+\\s*\\)\\s*->)"
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (!code[m.start()]) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String type = m.group(4);
            if (type == null) {
                String owner = m.group(2).equals("this") ? text : classSource(text, m.group(2));
                Matcher method = owner == null
                    ? null
                    : Pattern.compile(
                        "\\b" + Pattern.quote(m.group(3)) + "\\s*\\(\\s*(?:(?:final|@[\\w.]+(?:\\([^)]*\\))?)\\s+)*([A-Z][\\w.]*)(?:<[^>]*>)?\\s+\\w+\\s*\\)"
                    ).matcher(owner);
                if (method != null && method.find()) type = method.group(1);
            }
            if (type == null) {
                leave(file, LoaderRules.line(text, m.start()), "addListener" + m.group().substring(m.group().indexOf('(')), "the listener's event type could not be read from its method");
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            int at = m.end(1) - m.start();
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().substring(0, at) + type + ".class, " + m.group().substring(at)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The source of a class named in a file (its own package or an import), or null. */
    String classSource(String text, String name) throws IOException {
        String simple = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simple) + ")\\s*;").matcher(text);
        Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
        String full = imported.find() ? imported.group(1) : pkg.find() ? pkg.group(1) + "." + simple : simple;
        String file = classFile(full);
        // A class reached through a wildcard import (import a.b.*;).
        for (Matcher wild = Pattern.compile("(?m)^import\\s+([\\w.]+)\\.\\*\\s*;").matcher(text); file == null && wild.find(); ) file = classFile(
            wild.group(1) + "." + simple
        );
        return file == null ? null : read(file);
    }

    /** A NeoForge @Mod class back as a Fabric entrypoint: the constructor convert wrote is removed; any other constructor is named. */
    String entryToFabric(String file, String text, Matcher annotation, String api, String method) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
        Matcher ctor = Pattern.compile(
            "(?s)\\n[ \\t]*/\\*\\* Runs the Fabric (?:client )?entrypoint[^\\n]*\\n[ \\t]*public " +
            simple +
            "\\(IEventBus modBus(?:, (?:net\\.neoforged\\.fml\\.)?ModContainer container)?\\) \\{\\n(.*?)\\n[ \\t]*}\\n"
        ).matcher(text);
        if (!ctor.find() || !ctor.group(1).contains(method + "()")) {
            if (bridgePackage != null) return bridgeEntry(file, text, annotation, simple, api, method);
            leave(
                file,
                LoaderRules.line(text, annotation.start()),
                "@Mod class " + simple,
                "its constructor is NeoForge code convert did not write; move its work into " +
                method +
                "() of a Fabric " +
                api +
                " by hand (the NeoForge bridge is written for Minecraft " +
                Route.FIRST_UNOBFUSCATED +
                " and later)"
            );
            return null;
        }
        if (
            ctor
                .group(1)
                .replaceAll(
                    "(?s)this\\(\\);|modBus\\.addListener\\(.*?RegisterEvent\\.class, event -> \\{.*?}\\);|modBus\\.addListener\\(EventPriority\\.HIGHEST, RegisterKeyMappingsEvent\\.class, event -> \\w+\\(\\)\\);",
                    ""
                )
                .strip()
                .length() >
            0
        ) {
            leave(
                file,
                LoaderRules.line(text, ctor.start()),
                "@Mod constructor of " + simple,
                "it does more than run " + method + "(); move that work by hand"
            );
            return null;
        }
        String rest = text.substring(ctor.end());
        // A constructor convert put right after the class's opening brace leaves no blank line behind it.
        if (ctor.start() > 0 && text.charAt(ctor.start() - 1) == '{' && rest.startsWith("\n")) rest = rest.substring(1);
        String out = text.substring(0, ctor.start()) + "\n" + rest;
        out = out.replace("\n\n\n", "\n\n");
        int lineStart = out.lastIndexOf('\n', out.indexOf(annotation.group())) + 1;
        int lineEnd = out.indexOf('\n', out.indexOf(annotation.group())) + 1;
        out = out.substring(0, lineStart) + out.substring(lineEnd);
        out = implementFabric(out, simple, api, method);
        for (String name : List.of(
            "net.neoforged.fml.common.Mod",
            "net.neoforged.bus.api.IEventBus",
            "net.neoforged.neoforge.registries.RegisterEvent",
            "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
            "net.minecraft.core.registries.Registries",
            "net.neoforged.api.distmarker.Dist",
            "net.neoforged.fml.ModContainer",
            "net.neoforged.bus.api.EventPriority"
        )) out = LoaderRules.dropImport(out, name);
        return out;
    }

    // ---- back through the NeoForge bridge ---------------------------------------------------------------------

    static final Pattern BRIDGED_ENTRY = Pattern.compile(
        "(?m)^([ \\t]*)/\\*\\* NeoForge's constructor of this @Mod class, run as Fabric's entrypoint \\(modaccel convert, NeoForge bridge\\)\\. \\*/\\n[ \\t]*@Override\\n[ \\t]*public void (onInitialize|onInitializeClient|onInitializeServer)\\(\\) \\{"
    );

    static final Pattern BRIDGED_LOCAL = Pattern.compile(
        "^\\s*(IEventBus|ModContainer|Dist) (\\w+) = (?:Bridge\\.bus\\(\\)|Bridge\\.container\\(.*\\)|Dist\\.\\w+);\\s*$"
    );

    static final Pattern BRIDGED_END = Pattern.compile("^\\s*(?:Bridge\\.common|ClientBridge\\.client)\\((.+)\\);\\s*$");

    /** An event class convert wrote into addListener for the bridge, before a method reference or typed lambda. */
    static final Pattern WRITTEN_EVENT_CLASS = Pattern.compile(
        "\\.addListener\\(\\s*((?:EventPriority\\.\\w+\\s*,\\s*)?(?:(?:true|false)\\s*,\\s*)?)[A-Z][\\w.]*\\.class,\\s*(?=(?:this|[A-Z]\\w*(?:\\.[A-Z]\\w*)*)::\\w+\\s*\\)|\\(\\s*(?:final\\s+)?[A-Z][\\w.]*(?:<[^>]*>)?\\s+\\w+\\s*\\)\\s*->)"
    );

    /**
     * A mod convert moved to Fabric through the NeoForge bridge, going back to NeoForge: every entrypoint the bridge made
     * of a @Mod class is that @Mod class again (its constructor takes back the IEventBus, ModContainer and Dist the bridge
     * supplied, without the bridge's own calls), imports of the bridge's classes name NeoForge's again, the event class
     * written into addListener goes, and the bridge's files and the client entrypoint it added are removed. Returns the
     * entrypoint classes that became @Mod classes, which the Fabric entrypoints then leave alone.
     */
    Set<String> unbridge() throws Exception {
        Set<String> done = new HashSet<>();
        String pkg = null;
        for (String file : javaFiles()) if (
            file.endsWith("/Bridge.java") && read(file).contains("What a NeoForge @Mod class's constructor received")
        ) {
            Matcher m = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(read(file));
            if (m.find()) pkg = m.group(1);
        }
        if (pkg == null) return done;
        Map<String, String> back = new HashMap<>();
        for (Object item : LoaderRules.list(rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"neoforge".equals(b.get("from"))) continue;
            for (var c : Json.object(b.get("classes")).entrySet()) back.put(String.valueOf(c.getValue()), c.getKey());
        }
        String folder = "src/main/java/" + pkg.replace('.', '/') + "/";
        for (String file : javaFiles()) {
            if (file.startsWith(folder)) continue;
            String text = read(file), out = text;
            Matcher entry = BRIDGED_ENTRY.matcher(out);
            if (entry.find()) {
                String converted = unbridgeEntry(file, out, entry);
                if (converted != null) {
                    out = converted;
                    done.add(file.replaceFirst("^src/\\w+/java/", "").replaceAll("\\.java$", "").replace('/', '.'));
                }
            }
            for (var c : back.entrySet()) out = out.replaceAll(
                "(?m)^import(\\s+static)?\\s+" + Pattern.quote(pkg + "." + c.getKey()) + "((?:\\.\\w+)*(?:\\.\\*)?)\\s*;",
                "import$1 " + Matcher.quoteReplacement(c.getValue()) + "$2;"
            );
            out = LoaderRules.dropImport(LoaderRules.dropImport(out, pkg + ".Bridge"), pkg + ".ClientBridge");
            out = LoaderRules.dropImport(attachmentCallsBack(out), pkg + ".Attachments");
            out = WRITTEN_EVENT_CLASS.matcher(out).replaceAll(".addListener($1");
            if (!out.equals(text)) {
                write(file, out);
                applied.computeIfAbsent("neoforge-bridge-back", k -> new ArrayList<>()).add(file);
            }
        }
        for (String file : javaFiles()) if (file.startsWith(folder)) delete(file);
        Path resources = project.root.resolve("src/main/resources");
        if (Files.isDirectory(resources)) try (var list = Files.list(resources)) {
            for (Path config : list.filter(p -> p.getFileName().toString().endsWith(".loaderbridge.mixins.json")).toList()) delete(relative(config));
        }
        done.add(pkg + ".ClientEntrypoint");
        notes.add("NeoForge bridge: " + pkg + " is removed; the @Mod classes it ran as Fabric entrypoints are @Mod classes again.");
        return done;
    }

    /** One entrypoint the bridge made, back as NeoForge's @Mod class constructor; null (and named) when it is not as the bridge wrote it. */
    String unbridgeEntry(String file, String text, Matcher entry) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
        boolean[] code = LoaderRules.code(text);
        int open = entry.end() - 1;
        int end = LoaderRules.close(text, code, open);
        if (end < 0) return null;
        String indent = entry.group(1), method = entry.group(2);
        List<String> params = new ArrayList<>(), body = new ArrayList<>();
        String id = null;
        for (String line : text.substring(open + 1, end - 1).split("\n", -1)) {
            Matcher local = BRIDGED_LOCAL.matcher(line), last = BRIDGED_END.matcher(line);
            if (body.stream().allMatch(String::isBlank) && local.matches()) params.add(local.group(1) + " " + local.group(2));
            else if (last.matches()) id = last.group(1);
            else if (!line.matches("^\\s*Bridge\\.subscribe\\([\\w.]+\\.class\\);\\s*$")) body.add(line);
        }
        if (id == null) {
            leave(file, LoaderRules.line(text, entry.start()), "entrypoint " + simple, "the NeoForge bridge's entrypoint no longer ends as the bridge wrote it");
            return null;
        }
        String inner = String.join("\n", body).replaceAll("^(?:[ \\t]*\\n)+", "").replaceAll("\\s+$", "");
        String ctor = params.isEmpty() && inner.isEmpty()
            ? ""
            : indent + "public " + simple + "(" + String.join(", ", params) + ") {\n" + (inner.isEmpty() ? "" : inner + "\n") + indent + "}";
        String out = text.substring(0, entry.start()) + ctor + text.substring(end);
        String side = method.equals("onInitializeClient") ? "CLIENT" : method.equals("onInitializeServer") ? "DEDICATED_SERVER" : null;
        String api = side == null ? "ModInitializer" : side.equals("CLIENT") ? "ClientModInitializer" : "DedicatedServerModInitializer";
        Matcher c = CLASS.matcher(out);
        while (c.find()) if (c.group(3).equals(simple)) {
            String heritage = c.group(4)
                .replaceAll("\\bimplements\\s+" + api + "\\s*$", "")
                .replaceAll("\\bimplements\\s+" + api + "\\s*,\\s*", "implements ")
                .replaceAll(",\\s*" + api + "\\b", "");
            String annotation = side == null ? "@Mod(" + id + ")" : "@Mod(value = " + id + ", dist = Dist." + side + ")";
            out =
                out.substring(0, c.start()) +
                c.group(1) +
                annotation +
                "\n" +
                c.group(1) +
                c.group(2) +
                "class " +
                simple +
                (heritage.isBlank() ? " " : " " + heritage.strip() + " ") +
                "{" +
                out.substring(c.end());
            break;
        }
        out = LoaderRules.dropImport(out, "net.fabricmc.api." + api);
        out = LoaderRules.addImport(out, "net.neoforged.fml.common.Mod");
        return side == null ? out : LoaderRules.addImport(out, "net.neoforged.api.distmarker.Dist");
    }

    /** The class implements the Fabric entrypoint interface again, with @Override on its entry method. */
    static String implementFabric(String out, String simple, String api, String method) {
        Matcher c = CLASS.matcher(out);
        while (c.find()) if (c.group(3).equals(simple)) {
            String heritage = c.group(4);
            String next = heritage.contains("implements")
                ? heritage.replaceFirst("implements\\s+", "implements " + api + ", ")
                : heritage.strip().isEmpty()
                    ? " implements " + api + " "
                    : " " + heritage.strip() + " implements " + api + " ";
            out =
                out.substring(0, c.start()) +
                c.group(1) +
                c.group(2) +
                "class " +
                simple +
                next +
                "{" +
                out.substring(c.end());
            break;
        }
        out = out.replaceFirst("(?m)^([ \\t]*)(public\\s+void\\s+" + method + "\\s*\\()", "$1@Override\n$1$2");
        return LoaderRules.addImport(out, "net.fabricmc.api." + api);
    }

    String fabricJson(
        Map<String, Object> toml,
        Map<String, Object> mod,
        Map<String, List<String>> entrypoints,
        String minecraft,
        boolean menu
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("id", fabricId);
        out.put("version", Json.text(mod, "version", "${version}"));
        out.put("name", Json.text(mod, "displayName", modid));
        out.put("description", Json.text(mod, "description", ""));
        String authors = Json.text(mod, "authors", "");
        if (!authors.isEmpty()) out.put("authors", Arrays.stream(authors.split("\\s*,\\s*")).toList());
        Map<String, Object> contact = new LinkedHashMap<>();
        if (mod.get("displayURL") != null) contact.put("homepage", mod.get("displayURL"));
        if (toml.get("issueTrackerURL") != null) contact.put("issues", toml.get("issueTrackerURL"));
        if (!contact.isEmpty()) out.put("contact", contact);
        out.put("license", Json.text(toml, "license", "All Rights Reserved"));
        for (String key : List.of("iconFile", "logoFile")) if (
            mod.get(key) != null && !out.containsKey("icon")
        ) out.put("icon", mod.get(key));
        boolean client = entrypoints.containsKey("client"), main = entrypoints.containsKey("main"), server =
            entrypoints.containsKey("server");
        out.put("environment", main || (client && server) ? "*" : client ? "client" : server ? "server" : "*");
        // Entrypoints and custom values the way to NeoForge kept in modproperties (fabric.entrypoints.KEY, fabric.custom).
        Map<String, Object> custom = null;
        if (toml.get("[modproperties." + modid + "]") instanceof Map<?, ?> props) for (var e : Json.object(props).entrySet()) {
            String key = e.getKey().replaceAll("^\"|\"$", "");
            String value = String.valueOf(e.getValue());
            if (key.startsWith("fabric.entrypoints.") && value.startsWith("[")) {
                List<String> names = new ArrayList<>();
                for (Matcher q = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value); q.find(); ) names.add(q.group(1));
                entrypoints.computeIfAbsent(key.substring("fabric.entrypoints.".length()), k -> new ArrayList<>()).addAll(names);
            } else if (key.equals("fabric.custom")) custom = Json.object(Json.parse(value));
        }
        out.put("entrypoints", entrypoints);
        List<Object> mixins = new ArrayList<>();
        for (Object m : toml.get("[[mixins]]") instanceof List<?> l ? Json.array(l) : List.of()) {
            String config = Json.text(Json.object(m), "config", "");
            // The Fabric bridge's own mixins went with the bridge (unbridgeFabric).
            if (!config.endsWith(".fabricbridge.mixins.json")) mixins.add(config);
        }
        if (bridgeMixinConfig != null) mixins.add(bridgeMixinConfig);
        if (!mixins.isEmpty()) out.put("mixins", mixins);
        Map<String, Object> depends = new LinkedHashMap<>(), suggests = new LinkedHashMap<>(), breaks =
            new LinkedHashMap<>(), conflicts = new LinkedHashMap<>();
        depends.put("fabricloader", ">=" + fabricLoader);
        depends.put("minecraft", "~" + minecraft);
        depends.put("java", ">=" + javaVersion());
        depends.put("fabric-api", "*");
        for (Object d : toml.get("[[dependencies." + modid + "]]") instanceof List<?> l ? Json.array(l) : List.of()) {
            Map<String, Object> dep = Json.object(d);
            String id = Json.text(dep, "modId", ""), type = Json.text(
                dep,
                "type",
                Json.text(dep, "mandatory", "true").equals("true") ? "required" : "optional"
            );
            if (id.equals("neoforge") || id.equals("forge")) continue;
            String range = fabricPredicate(Json.text(dep, "versionRange", "*"));
            if (id.equals("minecraft")) {
                depends.put("minecraft", range);
                continue;
            }
            switch (type) {
                case "required" -> depends.put(id, range);
                case "incompatible" -> breaks.put(id, range);
                case "discouraged" -> conflicts.put(id, range);
                default -> suggests.put(id, range);
            }
            notes.add("Dependency " + id + " (" + type + ") is kept by id; it needs a Fabric build of that mod.");
        }
        if (menu) suggests.put("modmenu", "*");
        out.put("depends", depends);
        if (!suggests.isEmpty()) out.put("suggests", suggests);
        if (!breaks.isEmpty()) out.put("breaks", breaks);
        if (!conflicts.isEmpty()) out.put("conflicts", conflicts);
        if (fabricWidener != null) out.put("accessWidener", fabricWidener);
        if (!bridgeInjected.isEmpty()) {
            // The bridge's interfaces on game classes (Loom interface injection), which its mixins add at runtime.
            custom = custom == null ? new LinkedHashMap<>() : new LinkedHashMap<>(custom);
            Map<String, Object> injected = new LinkedHashMap<>();
            if (custom.get("loom:injected_interfaces") instanceof Map<?, ?> existing) injected.putAll(Json.object(existing));
            for (var e : bridgeInjected.entrySet()) {
                List<Object> names = new ArrayList<>(injected.get(e.getKey()) instanceof List<?> l ? Json.array(l) : List.of());
                for (String name : e.getValue()) if (!names.contains(name)) names.add(name);
                injected.put(e.getKey(), names);
            }
            custom.put("loom:injected_interfaces", injected);
        }
        if (custom != null) out.put("custom", custom);
        return pretty(out, "") + "\n";
    }

    /** JSON with one entry per line and tab indents, the way fabric.mod.json files are written. */
    static String pretty(Object value, String indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder out = new StringBuilder("{\n");
            int i = 0;
            for (var entry : map.entrySet()) {
                out
                    .append(indent)
                    .append('\t')
                    .append(Json.write(String.valueOf(entry.getKey())))
                    .append(": ")
                    .append(pretty(entry.getValue(), indent + "\t"));
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            return out.append(indent).append('}').toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder out = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) out
                .append(indent)
                .append('\t')
                .append(pretty(list.get(i), indent + "\t"))
                .append(i + 1 < list.size() ? ",\n" : "\n");
            return out.append(indent).append(']').toString();
        }
        return Json.write(value);
    }

    String fabricLoader = "0.16.0";

    String javaVersion() {
        Matcher m = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)|release\\s*=\\s*(\\d+)").matcher(
            readQuiet("build.gradle")
        );
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return "21";
    }

    String readQuiet(String relative) {
        try {
            return read(relative);
        } catch (IOException e) {
            return "";
        }
    }

    static final Pattern MDG_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"]net\\.neoforged\\.moddev['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    static final Pattern NEOGRADLE_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"]net\\.neoforged\\.gradle\\.userdev['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    boolean parchmentRepository;
    /** The Fabric access widener became NeoForge access transformer lines (or, going back, the reverse). */
    boolean widenerConverted;
    /** The access widener file (in src/main/resources) a NeoForge mod's transformers became, or null. */
    String fabricWidener;
    /** True when neoforge.mods.toml had ${property} placeholders the build expanded: fabric.mod.json gets the values. */
    boolean metadataExpanded;

    /** A Groovy value as text inside a GString: 'x' or "x" is x, anything else (project.v, v) is ${...}. */
    static String groovyValue(String expression) {
        String e = expression.strip().replaceAll("\\s*//.*$", "");
        if (e.matches("'[^']*'|\"[^\"$]*\"")) return e.substring(1, e.length() - 1);
        return "${" + e + "}";
    }

    String addRepository(String gradle, String url) {
        int[] repos = block(gradle, "repositories");
        String repo = unit + "maven { url = \"" + url + "\" }\n";
        if (repos != null) return gradle.substring(0, repos[2] + 1) + "\n" + repo + gradle.substring(repos[2] + 1).replaceFirst("^\\n", "");
        return gradle.replaceFirst("(?m)^dependencies\\s*\\{", Matcher.quoteReplacement("repositories {\n" + repo + "}\n\ndependencies {"));
    }

    /**
     * Removes the top-level build statements that only served NeoForge's metadata: NeoForge's MDK template expansion
     * (generateModMetadata, src/main/templates), the neoForge extension's own calls (neoForge.ideSyncTask ...), and, when
     * fabric.mod.json was written with the expanded values, a processResources expand of neoforge.mods.toml.
     */
    String dropMetadataExpansion(String gradle) {
        return dropStatements(
            gradle,
            statement ->
                statement.contains("generateModMetadata") ||
                statement.contains("src/main/templates") ||
                statement.strip().startsWith("neoForge.") ||
                (metadataExpanded && statement.contains("neoforge.mods.toml") && statement.contains("expand")),
            "NeoForge metadata or ModDevGradle only"
        );
    }

    /**
     * Fabric Loom's own types imported into the build script (net.fabricmc.loom...): the imports go, and so does every
     * top-level statement that names one of them (a task of a Loom type has no ModDevGradle counterpart).
     */
    String dropLoomTypes(String gradle) {
        List<String> removed = new ArrayList<>();
        String out = dropLoomTypes(gradle, removed);
        for (String r : removed) notes.add("Build: removed `" + r + "` (Fabric Loom's own types).");
        return out;
    }

    static String dropLoomTypes(String gradle, List<String> removed) {
        Matcher imports = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.loom\\.[\\w.]*?(\\w+)\\s*;?[ \\t]*$").matcher(gradle);
        List<String> types = new ArrayList<>();
        while (imports.find()) types.add(imports.group(1));
        if (types.isEmpty()) return gradle;
        Pattern named = Pattern.compile("\\b(?:" + String.join("|", types) + ")\\b");
        return dropStatements(gradle, statement -> named.matcher(statement).find(), removed);
    }

    String dropStatements(String gradle, java.util.function.Predicate<String> drop, String why) {
        List<String> removed = new ArrayList<>();
        String out = dropStatements(gradle, drop, removed);
        for (String r : removed) notes.add("Build: removed `" + r + "` (" + why + ").");
        return out;
    }

    // ---- dependencies ------------------------------------------------------------------------------------

    static final Pattern COORDINATE = Pattern.compile("([\"'])([\\w.\\-${}]+):([\\w.\\-${}]+):([^\"':]+)((?::[^\"']*)?)\\1");
    static final Map<String, String[]> LOADER_WORDS = Map.of(
        "fabric",
        new String[] { "fabric", "Fabric", "FABRIC" },
        "neoforge",
        new String[] { "neoforge", "NeoForge", "NEOFORGE" }
    );

    /** The Maven repositories a build script declares, then Maven Central. */
    static List<String> repositories(String gradle) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\burl\\s*=?\\s*\\(?\\s*(?:uri\\s*\\(\\s*)?[\"'](https?://[^\"'$]+?)/?[\"']").matcher(gradle);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        out.add("https://repo.maven.apache.org/maven2");
        return out;
    }

    /** An artifact name with the loader's word, as a whole part between - _ or ., changed to the other loader's; null when it has none. */
    static String swapLoaderWord(String artifact, String from, String to) {
        String[] a = LOADER_WORDS.get(from), b = LOADER_WORDS.get(to);
        for (int i = 0; i < a.length; i++) {
            Matcher m = Pattern.compile("(?<=^|[-_.])" + a[i] + "(?=$|[-_.])").matcher(artifact);
            if (m.find()) return artifact.substring(0, m.start()) + b[i] + artifact.substring(m.end());
        }
        return null;
    }

    /** Whether an artifact names no loader at all (a library, or a mod's loader-neutral API). */
    static boolean loaderNeutral(String artifact) {
        return !Pattern.compile("(?i)(?:^|[-_.])(?:fabric|neoforge|forge|quilt)(?:$|[-_.])").matcher(artifact).find();
    }

    String resolveProperties(String text) throws IOException {
        Map<String, String> props = properties(read("gradle.properties"));
        Matcher m = Pattern.compile("\\$\\{?(?:project\\.)?(\\w+)}?").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A dependency line moved to the other loader's build of the same project: the coordinate's artifact with its loader
     * word changed (jei-26.1.2-neoforge to jei-26.1.2-fabric), only when one of the build's own repositories publishes that
     * artifact at that version. Null when none does; the line is then named, never guessed.
     */
    String otherLoaderDependency(String line, String from, String to, List<String> repos) throws IOException {
        Matcher c = COORDINATE.matcher(line);
        if (!c.find()) return null;
        String artifact = c.group(3), swapped = swapLoaderWord(artifact, from, to);
        if (swapped == null) return null;
        String group = resolveProperties(c.group(2)), name = resolveProperties(swapped), version = resolveProperties(c.group(4));
        if (group.contains("$") || name.contains("$")) return null;
        for (String repo : repos) {
            try {
                List<String> versions = metadataVersions(
                    Target.fetch(build, repo + "/" + group.replace('.', '/') + "/" + name + "/maven-metadata.xml")
                );
                if (version.contains("$") ? versions.isEmpty() : !versions.contains(version)) continue;
                return line.substring(0, c.start(3)) + swapped + line.substring(c.end(3));
            } catch (Exception notThere) {
                // The next repository.
            }
        }
        return null;
    }

    /**
     * One dependency line of the source loader's build, for the target build: a loader-specific artifact moves to the
     * other loader's build of the same project when a repository publishes it; a loader-neutral library stays and is
     * not named; anything else is named with the reason.
     */
    String dependencyLine(String raw, String from, String to, List<String> repos, boolean remapMods) throws IOException {
        // A statement over several lines ({ exclude ... }) is named by its first line.
        String line = raw.strip().lines().findFirst().orElse("");
        Matcher c = COORDINATE.matcher(line);
        if (!c.find()) return raw;
        if (c.group(2).startsWith("curse.maven") || c.group(2).startsWith("maven.modrinth")) {
            leave("build.gradle", 0, "dependency " + line, "a file of one loader's build on CurseForge or Modrinth: the other loader's file has another id, which convert cannot choose");
            return raw;
        }
        if (loaderNeutral(c.group(3))) {
            neutralDependencies.add(c.group(2) + ":" + c.group(3));
            return raw;
        }
        String moved = otherLoaderDependency(raw, from, to, repos);
        if (moved == null) {
            leave("build.gradle", 0, "dependency " + line, "a " + from + " build of another mod: no " + to + " build of it at this version in the build's repositories");
            return raw;
        }
        if (remapMods) moved = remapConfiguration(moved);
        Matcher now = COORDINATE.matcher(moved);
        String coordinate = now.find() ? now.group(2) + ":" + now.group(3) : moved.strip();
        applied.computeIfAbsent("dependency-other-loader-build", k -> new ArrayList<>()).add("build.gradle: " + coordinate);
        return moved;
    }

    /** Loom's remapping configurations for a mod dependency (before 26.1): implementation becomes modImplementation and so on. */
    static String remapConfiguration(String line) {
        Matcher m = Pattern.compile("\\b(implementation|compileOnly|runtimeOnly|localRuntime|api)(?=\\s*[(\"'])").matcher(line);
        if (!m.find()) return line;
        String config = m.group(1);
        return line.substring(0, m.start()) + "mod" + Character.toUpperCase(config.charAt(0)) + config.substring(1) + line.substring(m.end());
    }

    final Set<String> neutralDependencies = new TreeSet<>();

    /** Removes each top-level statement of a build script (balanced brackets; comments are kept) that matches. */
    static String dropStatements(String gradle, java.util.function.Predicate<String> drop, List<String> removed) {
        StringBuilder out = new StringBuilder();
        for (String statement : statements(gradle)) {
            String s = statement.strip();
            boolean comment = s.startsWith("//") || s.startsWith("/*") || s.startsWith("*");
            if (!comment && drop.test(statement)) removed.add(s.lines().findFirst().orElse(""));
            else out.append(statement);
        }
        return out.toString();
    }

    /**
     * A build script (or a block's body) as its statements, each with its line ends: a statement runs to the end of the
     * line where its brackets balance, so implementation("x") { exclude(...) } is one statement over three lines.
     */
    static List<String> statements(String gradle) {
        boolean[] code = LoaderRules.code(gradle);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < gradle.length()) {
            int depth = 0, end = i;
            while (end < gradle.length()) {
                char c = gradle.charAt(end);
                if (code[end] && (c == '{' || c == '(' || c == '[')) depth++;
                else if (code[end] && (c == '}' || c == ')' || c == ']')) depth--;
                end++;
                if (c == '\n' && depth <= 0) break;
            }
            out.add(gradle.substring(i, end));
            i = end;
        }
        return out;
    }

    void buildToFabric(String minecraft, boolean menu) throws Exception {
        // Before 26.1 the game is obfuscated: Fabric's own template builds those versions with the remapping Loom
        // (net.fabricmc.fabric-loom-remap) on Mojang's names (loom.officialMojangMappings()), the names NeoForge mods use.
        boolean remap = Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) < 0;
        String gradle = read("build.gradle");
        Matcher plugin = MDG_PLUGIN.matcher(gradle);
        boolean neoGradle = false;
        if (!plugin.find()) {
            plugin = NEOGRADLE_PLUGIN.matcher(gradle);
            neoGradle = plugin.find();
            if (!neoGradle) throw new IOException(
                "build.gradle applies neither ModDevGradle (id 'net.neoforged.moddev') nor NeoGradle (id 'net.neoforged.gradle.userdev') in its plugins block."
            );
        }
        String loom = stablePlugin("https://maven.fabricmc.net", "net.fabricmc", "fabric-loom");
        String parchment = null;
        if (neoGradle) {
            // NeoGradle's top-level blocks: runs and minecraft go (Loom makes its own client and server runs), and its
            // parchment subsystem becomes a Loom mappings layer.
            for (String name : List.of("subsystems", "runs", "minecraft", "jarJar")) {
                int[] b = block(gradle, name);
                if (b == null) continue;
                String body = gradle.substring(b[2] + 1, b[3] - 1);
                if (name.equals("subsystems")) parchment = children(body).get("parchment");
                else if (name.equals("runs")) notes.add("NeoGradle's runs block is replaced by Loom's own client and server runs.");
                // The usual file (META-INF/accesstransformer.cfg) becomes the access widener; another file is named.
                else if (name.equals("minecraft") && body.contains("accessTransformers") && !body.contains(AT_FILE)) leave(
                    "build.gradle",
                    0,
                    "minecraft accessTransformers",
                    "access transformers are not converted to Fabric access wideners yet"
                );
                gradle = gradle.substring(0, b[0]) + gradle.substring(b[1]);
            }
        } else {
            int[] neoBlock = block(gradle, "neoForge");
            if (neoBlock != null) parchment = children(gradle.substring(neoBlock[2] + 1, neoBlock[3] - 1)).get("parchment");
        }
        String mappings = null;
        if (remap) {
            mappings = "mappings loom.officialMojangMappings()";
            if (parchment != null) {
                Matcher mc = Pattern.compile("minecraftVersion\\s*=?\\s*(.+)").matcher(parchment);
                Matcher mv = Pattern.compile("mappingsVersion\\s*=?\\s*(.+)").matcher(parchment);
                if (mc.find() && mv.find()) {
                    String a = groovyValue(mc.group(1)), b = groovyValue(mv.group(1));
                    mappings =
                        "mappings loom.layered() {\n" +
                        unit +
                        unit +
                        "officialMojangMappings()\n" +
                        unit +
                        unit +
                        "parchment(\"org.parchmentmc.data:parchment-" +
                        a +
                        ":" +
                        b +
                        "@zip\")\n" +
                        unit +
                        "}";
                    parchmentRepository = true;
                }
            }
        } else if (parchment != null) notes.add(
            "Parchment only adds parameter names; Loom on Minecraft 26.1 and later builds on the game's own names, so it is dropped."
        );
        gradle = dropMetadataExpansion(gradle);
        fabricLoader = Json.text(
            Json.object(
                Json.array(Json.parse(Target.fetch(build, "https://meta.fabricmc.net/v2/versions/loader")))
                    .stream()
                    .filter(v -> Boolean.TRUE.equals(Json.object(v).get("stable")))
                    .findFirst()
                    .orElseThrow()
            ),
            "version",
            ""
        );
        List<String> apis = new ArrayList<>();
        for (String v : metadataVersions(
            Target.fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml")
        )) if (v.endsWith("+" + minecraft) || v.endsWith("+" + minecraft.replaceFirst("\\.0$", ""))) apis.add(v);
        if (apis.isEmpty()) throw new IOException("Fabric API has no release for Minecraft " + minecraft + ".");
        String api = newest(apis);
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            (remap ? "id 'net.fabricmc.fabric-loom-remap' version '" : "id 'net.fabricmc.fabric-loom' version '") +
            loom +
            "'" +
            (neoGradle && block(gradle, "idea") != null && !gradle.matches("(?s).*id\\s*\\(?\\s*['\"]idea['\"].*")
                    ? "\n" + plugin.group(1) + "id 'idea'"
                    : "") +
            gradle.substring(plugin.end());
        if (neoGradle && gradle.contains("\n" + plugin.group(1) + "id 'idea'")) notes.add(
            "Build: NeoGradle applies Gradle's idea plugin itself and Loom does not, so the build's idea block gets it from its plugins block."
        );
        int[] neo = block(gradle, "neoForge");
        StringBuilder loomBlock = new StringBuilder();
        if (neo != null) {
            Map<String, String> parts = children(gradle.substring(neo[2] + 1, neo[3] - 1));
            StringBuilder runs = new StringBuilder();
            for (var run : children(parts.getOrDefault("runs", "")).entrySet()) {
                if (
                    Set.of("client", "server").contains(run.getKey()) &&
                    run.getValue().strip().matches("client\\(\\)|server\\(\\)")
                ) continue;
                String body = run.getValue();
                // NeoForge's game test server is Fabric API's: a server run with -Dfabric-api.gametest, which runs the
                // game tests the mod registers with Fabric API (tests registered through NeoForge's events are named apart).
                if (body.matches("(?s).*\\btype\\s*=\\s*[\"']gameTestServer[\"'].*") || body.matches("(?s).*\\bgameTestServer\\(\\).*")) {
                    String rest = body.replaceAll("(?m)^\\s*(?:type\\s*=\\s*[\"']gameTestServer[\"']|gameTestServer\\(\\))\\s*$", "");
                    runs.append(unit).append(unit).append(run.getKey()).append(" {\n")
                        .append(unit).append(unit).append(unit).append("server()\n")
                        .append(unit).append(unit).append(unit).append("vmArg \"-Dfabric-api.gametest\"\n")
                        .append(runToLoom(run.getKey(), rest))
                        .append(unit).append(unit).append("}\n");
                    notes.add("Build: NeoForge's game test server run is a Loom server run with Fabric API's -Dfabric-api.gametest.");
                    continue;
                }
                // A data run with nothing to generate: the mod adds no data providers (no GatherDataEvent), so it goes.
                if (body.matches("(?s).*\\b(?:clientData|serverData|data)\\(\\).*|(?s).*\\btype\\s*=\\s*[\"']data[\"'].*")) {
                    boolean providers = false;
                    for (String file : javaFiles()) if (read(file).contains("GatherDataEvent")) providers = true;
                    if (!providers) {
                        notes.add("Build: the " + run.getKey() + " run is removed; the mod adds no data providers (GatherDataEvent), so it generated nothing.");
                        continue;
                    }
                }
                // A Loom run needs an environment (client() or server()); a data run has none on Fabric.
                if (
                    !run.getKey().equals("configureEach") &&
                    !run.getValue().matches("(?s).*\\b(?:client|server)\\(\\).*")
                ) {
                    leave(
                        "build.gradle",
                        0,
                        "neoForge run " + run.getKey(),
                        "a run with no client() or server(): Loom has no counterpart for its type"
                    );
                    continue;
                }
                runs
                    .append(unit)
                    .append(unit)
                    .append(run.getKey())
                    .append(" {\n")
                    .append(runToLoom(run.getKey(), run.getValue()))
                    .append(unit)
                    .append(unit)
                    .append("}\n");
            }
            if (runs.length() > 0) loomBlock
                .append("loom {\n")
                .append(unit)
                .append("runs {\n")
                .append(runs)
                .append(unit)
                .append("}\n}\n");
            for (String key : parts.keySet()) if (!Set.of("runs", "mods", "parchment").contains(key)) leave(
                "build.gradle",
                0,
                "neoForge " + key + " block",
                "no Loom counterpart in the rules"
            );
            gradle = gradle.substring(0, neo[0]) + loomBlock + gradle.substring(neo[1]);
        }
        if (fabricWidener != null) {
            int[] dependencies = block(gradle, "dependencies");
            int at = dependencies == null ? gradle.length() : dependencies[0];
            gradle =
                gradle.substring(0, at) +
                "loom {\n" +
                unit +
                "accessWidenerPath = file(\"src/main/resources/" +
                fabricWidener +
                "\")\n}\n\n" +
                gradle.substring(at);
        }
        gradle = gradle.replaceAll("(?m)^// NeoForge has no split client source set[^\\n]*\\n", "");
        int[] deps = block(gradle, "dependencies");
        String add =
            unit +
            "minecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n" +
            (mappings == null ? "" : unit + mappings + "\n") +
            unit +
            (remap ? "modImplementation" : "implementation") +
            " \"net.fabricmc:fabric-loader:${project.loader_version}\"\n" +
            unit +
            (remap ? "modImplementation" : "implementation") +
            " \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n" +
            (menu
                    ? unit +
                    "compileOnly \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n" +
                    unit +
                    "localRuntime \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n"
                    : "");
        List<String> mavens = repositories(gradle);
        if (deps != null) {
            StringBuilder body = new StringBuilder();
            // Whole statements, not lines: a dependency with a closure ({ exclude ... }) is one statement.
            for (String raw : statements(gradle.substring(deps[2] + 1, deps[3] - 1))) {
                String line = raw.strip();
                // NeoForge's jarJar is Loom's include, wherever it is in the statement (implementation jarJar("x")).
                raw = raw.replaceAll("\\bjarJar(?=\\s*[(\"'])", "include");
                // NeoGradle names NeoForge itself as a dependency; the Loom lines above replace it.
                if (line.matches("(?s)\\w+\\s*\\(?\\s*[\"']net\\.neoforged:neoforge:.*")) continue;
                boolean comment = line.startsWith("//") || line.startsWith("/*") || line.startsWith("*");
                if (!line.isEmpty() && !comment) raw = dependencyLine(raw, "neoforge", "fabric", mavens, remap);
                body.append(raw);
            }
            if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') body.append('\n');
            gradle =
                gradle.substring(0, deps[2] + 1) +
                "\n" +
                add +
                body.toString().replaceAll("^\\n+", "") +
                gradle.substring(deps[3] - 1);
        } else gradle += "\ndependencies {\n" + add + "}\n";
        // NeoForge's development classpath carries the JSR 305 annotations (javax.annotation.Nullable ...); Loom's does not.
        boolean jsr305 = false;
        for (String file : javaFiles()) if (read(file).contains("import javax.annotation.")) jsr305 = true;
        if (jsr305 && !gradle.contains("jsr305")) {
            int[] dependencies = block(gradle, "dependencies");
            if (dependencies != null) {
                gradle =
                    gradle.substring(0, dependencies[3] - 1) +
                    unit +
                    "compileOnly \"com.google.code.findbugs:jsr305:3.0.2\"\n" +
                    gradle.substring(dependencies[3] - 1);
                notes.add("Build: the JSR 305 annotations (javax.annotation) the code uses, which NeoForge's classpath carried, as a compile-only dependency.");
            }
        }
        // NeoForge ships NightConfig (its config files' TOML library) and mods use it directly; on Fabric the mod carries it.
        boolean nightConfig = false;
        for (String file : javaFiles()) if (read(file).contains("import com.electronwill.nightconfig.")) nightConfig = true;
        if (nightConfig && !gradle.contains("night-config")) {
            int[] dependencies = block(gradle, "dependencies");
            if (dependencies != null) {
                gradle =
                    gradle.substring(0, dependencies[3] - 1) +
                    unit +
                    "include implementation(\"com.electronwill.night-config:core:" +
                    NIGHT_CONFIG +
                    "\")\n" +
                    unit +
                    "include implementation(\"com.electronwill.night-config:toml:" +
                    NIGHT_CONFIG +
                    "\")\n" +
                    gradle.substring(dependencies[3] - 1);
                notes.add(
                    "Build: NightConfig " + NIGHT_CONFIG + " (the version NeoForge 21.1 requires), which the code uses and NeoForge carried, inside the mod's jar."
                );
            }
        }
        if (parchmentRepository && !gradle.contains("maven.parchmentmc.org")) gradle = addRepository(
            gradle,
            "https://maven.parchmentmc.org"
        );
        if (menu && !gradle.contains("maven.terraformersmc.com")) {
            int[] repos = block(gradle, "repositories");
            String repo = unit + "maven { url \"https://maven.terraformersmc.com/releases/\" }\n";
            if (repos != null) gradle =
                gradle.substring(0, repos[2] + 1) +
                "\n" +
                repo +
                gradle.substring(repos[2] + 1).replaceFirst("^\\n", "");
            else gradle = gradle.replaceFirst(
                "(?m)^dependencies\\s*\\{",
                "repositories {\n" + repo + "}\n\ndependencies {"
            );
        }
        gradle = gradle
            .replace("filesMatching(\"META-INF/neoforge.mods.toml\")", "filesMatching(\"fabric.mod.json\")")
            .replace("filesMatching('META-INF/neoforge.mods.toml')", "filesMatching('fabric.mod.json')");
        // ModDevGradle's Jar-in-Jar: Loom nests include dependencies in its own jar task, so the switch goes and the
        // jarJar task's output is Loom's jar (remapJar before 26.1).
        List<String> jarJarDropped = new ArrayList<>();
        gradle = dropStatements(gradle, s -> s.strip().matches("jarJar\\.enable\\(\\)\\s*;?"), jarJarDropped);
        if (!jarJarDropped.isEmpty()) notes.add("Build: removed jarJar.enable(); Loom nests include dependencies in its jar.");
        gradle = gradle.replaceAll("\\btasks\\.jarJar\\b", remap ? "tasks.remapJar" : "tasks.jar");
        gradle = publishLoader(gradle, "neoforge", "fabric");
        gradle = dropPluginCode(gradle, "net.neoforged.moddevgradle");
        gradle = dropPluginCode(gradle, "net.neoforged.gradle");
        if (!neutralDependencies.isEmpty() && !gradle.contains("exclude group: 'net.neoforged', module: 'neoforge'")) {
            // A dependency kept as it is can be a NeoForge mod whose own metadata asks for NeoForge; Fabric's build has none.
            gradle +=
                "\n// Dependencies kept from the NeoForge build may ask for NeoForge itself, which the Fabric build does not have.\nconfigurations.configureEach {\n" +
                unit +
                "exclude group: 'net.neoforged', module: 'neoforge'\n}\n";
            notes.add("Build: NeoForge itself is excluded from the dependencies the kept libraries bring with them.");
        }
        write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        props.put("neo_version", null);
        props.put("neoforge_version", null);
        props.put("loader_version", fabricLoader);
        props.put("fabric_api_version", api);
        // Loom asks Mojang for the game by its version id: 26.2, not the 26.2.0 a NeoForge build may write.
        if (minecraft.matches("\\d+\\.\\d+\\.0")) props.put("minecraft_version", minecraft.replaceFirst("\\.0$", ""));
        // Loom's build names the game by minecraft_version; a NeoForge build that had only its NeoForge release gets it.
        else if (!properties(read("gradle.properties")).containsKey("minecraft_version")) props.put("minecraft_version", minecraft);
        if (menu) {
            // The newest stable Mod Menu that Modrinth, where Mod Menu publishes, lists for this Minecraft version on Fabric.
            String modmenu = null;
            for (Object version : Json.array(
                Json.parse(
                    Target.fetch(
                        build,
                        "https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%22" +
                        minecraft +
                        "%22%5D&loaders=%5B%22fabric%22%5D"
                    )
                )
            )) {
                String number = Json.text(Json.object(version), "version_number", "");
                if (!number.contains("-") && (modmenu == null || Versions.compare(number, modmenu) > 0)) modmenu =
                    number;
            }
            if (modmenu == null) throw new IOException(
                "Mod Menu has no release for Minecraft " +
                minecraft +
                " on Modrinth, so the config screen cannot move back to it."
            );
            props.put("modmenu_version", modmenu);
        }
        write("gradle.properties", setProperties(read("gradle.properties"), props));
        String settings = read("settings.gradle");
        if (!settings.contains("maven.fabricmc.net")) {
            String repo =
                unit +
                unit +
                "maven {\n" +
                unit +
                unit +
                unit +
                "name = 'Fabric'\n" +
                unit +
                unit +
                unit +
                "url = 'https://maven.fabricmc.net/'\n" +
                unit +
                unit +
                "}\n";
            if (settings.matches("(?s).*pluginManagement\\s*\\{\\s*repositories\\s*\\{.*")) settings =
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)\\n?",
                    "$1\n" + Matcher.quoteReplacement(repo)
                );
            else settings =
                "pluginManagement {\n" +
                unit +
                "repositories {\n" +
                repo +
                unit +
                unit +
                "mavenCentral()\n" +
                unit +
                unit +
                "gradlePluginPortal()\n" +
                unit +
                "}\n}\n\n" +
                settings;
            write("settings.gradle", settings);
        }
        notes.add("Build: Fabric Loom " + loom + ", Fabric Loader " + fabricLoader + ", Fabric API " + api + ".");
    }

    String runToLoom(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = unit + unit + unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher prop = Pattern.compile(
                "^systemProperty\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)?$"
            ).matcher(line);
            Matcher jvm = Pattern.compile("^jvmArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            Matcher dir = Pattern.compile(
                "^gameDirectory\\s*=\\s*project\\.file\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)$"
            ).matcher(line);
            Matcher program = Pattern.compile("^programArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            // NeoForge-only run switches with no effect on Fabric: which game test namespaces NeoForge enables (Fabric API
            // runs every test a mod registers), and ModDevGradle's console log level for its runs.
            if (line.matches("systemProperty\\s*\\(?\\s*['\"]neoforge\\.enabledGameTestNamespaces['\"].*")) {
                notes.add("Build: run " + name + ": NeoForge's neoforge.enabledGameTestNamespaces is removed; Fabric API runs every game test a mod registers.");
                continue;
            }
            if (line.matches("logLevel\\s*=.*")) {
                notes.add("Build: run " + name + ": ModDevGradle's run log level (" + line + ") is removed; Loom's runs log at Minecraft's own levels.");
                continue;
            }
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.startsWith("ideName")) out
                .append(in)
                .append("name = ")
                .append(line.replaceFirst("^ideName\\s*=\\s*", ""))
                .append('\n');
            else if (prop.find()) out
                .append(in)
                .append("vmArg \"-D")
                .append(prop.group(1))
                .append('=')
                .append(prop.group(2))
                .append("\"\n");
            else if (jvm.find()) out.append(in).append("vmArg \"").append(jvm.group(1)).append("\"\n");
            else if (dir.find()) out.append(in).append("runDir = \"").append(dir.group(1)).append("\"\n");
            else if (program.find()) out.append(in).append("programArgs \"").append(program.group(1)).append("\"\n");
            else leave(
                "build.gradle",
                0,
                "neoForge run " + name + ": " + line,
                "this ModDevGradle run setting has no Loom counterpart in the rules"
            );
        }
        return out.toString();
    }

    // ---- check and report --------------------------------------------------------------------------------

    void compile(Map<String, Object> result) throws Exception {
        Build fresh = new Build(project, build.harness, "", build.report);
        Map<String, Object> model;
        try {
            model = fresh.model(true, false);
        } catch (IOException failure) {
            result.put("compiled", false);
            result.put("ok", false);
            result.put("compileError", failure.getMessage());
            return;
        }
        List<String> tasks = fresh.tasks(model, "classes");
        Run.Result run = fresh.gradle(tasks, "convert-compile");
        boolean ok = run.exit() == 0;
        result.put("compiled", ok);
        result.put("compileLog", run.log().toString());
        if (!ok) result.put("errors", Port.errors(run.log()));
        result.put("ok", ok && left.isEmpty());
    }

    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        boolean dryRun = Boolean.TRUE.equals(result.get("dryRun"));
        out
            .append(dryRun ? "Would convert " : "Converted ")
            .append(result.get("from"))
            .append(" to ")
            .append(result.get("to"))
            .append(" (Minecraft ")
            .append(result.get("minecraft"))
            .append("): ");
        out
            .append(Json.array(result.get("changed")).size())
            .append(dryRun ? " file(s) would change, " : " file(s) changed, ")
            .append(Json.array(result.get("removed")).size())
            .append(dryRun ? " would be removed.\n" : " removed.\n");
        if (dryRun) {
            for (Object file : Json.array(result.get("changed"))) out.append("  change ").append(file).append('\n');
            for (Object file : Json.array(result.get("removed"))) out.append("  remove ").append(file).append('\n');
        }
        Map<String, Object> rulesApplied = Json.object(result.get("rulesApplied"));
        if (!rulesApplied.isEmpty()) out
            .append("Rules applied: ")
            .append(String.join(", ", rulesApplied.keySet()))
            .append('\n');
        for (Object note : Json.array(result.get("notes"))) out.append("Note: ").append(note).append('\n');
        List<Object> left = Json.array(result.get("left"));
        if (!left.isEmpty()) {
            out.append("Not converted (").append(left.size()).append("), named instead of dropped:\n");
            for (Object item : left) {
                var l = Json.object(item);
                Object line = l.get("line");
                out
                    .append("  ")
                    .append(l.get("file"))
                    .append(line instanceof Number n && n.intValue() > 0 ? ":" + n : "")
                    .append("  ")
                    .append(l.get("what"))
                    .append(": ")
                    .append(l.get("why"))
                    .append('\n');
            }
        }
        if (Boolean.TRUE.equals(result.get("compiled"))) out
            .append("The converted source compiles on ")
            .append(result.get("to"))
            .append(". Prove it works in the game next: modaccel ui-check or world-check with --assert.\n");
        else if (Boolean.FALSE.equals(result.get("compiled"))) {
            out.append("The converted source does not compile yet.");
            if (result.get("compileError") != null) out.append(' ').append(result.get("compileError"));
            List<Object> errors = result.get("errors") instanceof List<?> e ? Json.array(e) : List.of();
            for (Object e : errors.subList(0, Math.min(15, errors.size()))) {
                var error = Json.object(e);
                out
                    .append("\n  ")
                    .append(error.get("file"))
                    .append(':')
                    .append(error.get("line"))
                    .append(": ")
                    .append(error.get("message"));
            }
            out.append('\n');
        }
        if (dryRun) out.append("Dry run: nothing was written. Run it without --dry-run to convert and compile.");
        else out.append("Undo: modaccel convert --undo ").append(result.get("undoConvert"));
        return out.toString();
    }
}
