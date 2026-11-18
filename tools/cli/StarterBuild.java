import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * Replaces a mod's own Gradle build with the build its loader publishes for a game version (Fabric's example mod, one
 * branch per version; NeoForge's ModDevGradle MDK, one repository per version), and moves the mod into it: its source and
 * resource folders (shared modules such as common/ included, other loaders' modules left out), its own properties, its
 * metadata expansion, access transformer or widener, and the dependencies it declares. Every quirk of an old build
 * (plugins that are gone, settings Gradle removed, sibling Forge modules, build-only properties) goes with the old
 * script, and what remains is the shape the loader itself maintains for every version.
 */
final class StarterBuild {

    private StarterBuild() {}


    /** The loader's starter for a version, cloned once into ~/.mmt/starters. */
    static Path starter(String loader, String version) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".mmt/starters", loader + "-" + version);
        if (Files.isRegularFile(dir.resolve("build.gradle"))) return dir;
        if (Files.isDirectory(dir)) LibraryPort.deleteTree(dir);
        Files.createDirectories(dir.getParent());
        String url;
        List<String> branch;
        if (loader.equals("fabric")) {
            url = "https://github.com/FabricMC/fabric-example-mod";
            branch = List.of("-b", version);
        } else if (loader.equals("neoforge")) {
            url = "https://github.com/NeoForgeMDKs/MDK-" + version + "-ModDevGradle";
            branch = List.of();
        } else throw new IOException("No starter project for " + loader);
        List<String> args = new ArrayList<>(List.of("clone", "--quiet", "--depth", "1"));
        args.addAll(branch);
        args.addAll(List.of(url, dir.getFileName().toString()));
        try {
            LibraryPort.git(dir.getParent(), args.toArray(String[]::new));
        } catch (IOException none) {
            throw new IOException(loader + " publishes no starter project for " + version + " (" + url + ")");
        }
        return dir;
    }

    /** What moving the mod in changed: written files and why, for the port's record. */
    record Result(List<Path> written, List<Path> removed, List<String> notes) {}

    /**
     * Writes the starter build for version at the mod's root and moves the mod into it. track is called for every file
     * before it is written or removed, so the port's undo restores the old build.
     */
    static Result apply(Path root, Map<String, Object> project, String loader, String version, Consumer<Path> track) throws Exception {
        Path starter = starter(loader, version);
        Path selected = Path.of(Json.text(project, "directory", root.toString()));
        List<Path> written = new ArrayList<>(), removed = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // Source and resource folders: the selected project's, and every other module that is not another loader's.
        List<String> java = new ArrayList<>(), resources = new ArrayList<>(), clientJava = new ArrayList<>(), clientResources = new ArrayList<>();
        for (Object s : Json.array(project.get("sourceSets"))) {
            var set = Json.object(s);
            String name = Json.text(set, "name", "");
            if (!name.equals("main") && !name.equals("client")) continue;
            for (String d : ProjectModel.strings(set.get("java"))) if (Files.isDirectory(Path.of(d))) (name.equals("main") ? java : clientJava).add(rel(root, d));
            for (String d : ProjectModel.strings(set.get("resources"))) if (Files.isDirectory(Path.of(d))) (name.equals("main") ? resources : clientResources).add(rel(root, d));
        }
        // Only modules the old build includes and the selected project depends on (common/): a folder beside them can be a
        // separate build (Zombie Apocalypse Addon's forge1201/), another loader's, or a test mod (MidnightLib's test-fabric/).
        Set<String> siblings = siblings(root, project);
        try (var dirs = Files.list(root)) {
            for (Path module : dirs.filter(Files::isDirectory).toList()) {
                String name = module.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!siblings.contains(name) || module.equals(selected)) continue;
                if (Files.isDirectory(module.resolve("src/main/java"))) java.add(rel(root, module.resolve("src/main/java").toString()));
                if (Files.isDirectory(module.resolve("src/main/resources"))) resources.add(rel(root, module.resolve("src/main/resources").toString()));
            }
        }
        if (java.isEmpty()) throw new IOException("The mod's build names no Java source folder, so there is nothing to move into a starter build.");
        // What a build task wrote (build/generated/...) comes back from the starter's own tasks, not as a source folder.
        resources.removeIf(d -> d.startsWith("build/") || d.contains("/build/"));
        for (Object p : Json.array(project.get("plugins"))) if (String.valueOf(p).toLowerCase(Locale.ROOT).contains("architectury")) throw new IOException(
            "The mod builds with Architectury, whose plugin rewrites its code (@ExpectPlatform), which a starter build does not do.");
        // The old build scripts: how they packaged resources (renames, expanded files and the values they expanded with).
        StringBuilder oldScripts = new StringBuilder();
        for (Path s : new LinkedHashSet<>(List.of(root.resolve("build.gradle"), root.resolve("build.gradle.kts"),
                Path.of(Json.text(project, "buildFile", root.resolve("build.gradle").toString()))))) {
            if (Files.isRegularFile(s)) oldScripts.append(Files.readString(s)).append('\n');
        }

        // Properties: the starter's (its game, loader and plugin versions), then the mod's own that the starter does not set.
        Properties starterProps = ProjectModel.properties(starter.resolve("gradle.properties"));
        Map<String, String> props = new LinkedHashMap<>();
        for (String k : starterProps.stringPropertyNames()) props.put(k, starterProps.getProperty(k));
        Map<String, String> modProps = new LinkedHashMap<>();
        for (Path p : List.of(root.resolve("gradle.properties"), selected.resolve("gradle.properties"))) {
            if (!Files.isRegularFile(p)) continue;
            Properties own = ProjectModel.properties(p);
            for (String k : own.stringPropertyNames()) modProps.put(k, own.getProperty(k));
        }
        for (var e : modProps.entrySet()) {
            if (e.getKey().startsWith("org.gradle.") || props.containsKey(e.getKey())) continue;
            props.put(e.getKey(), e.getValue());
        }
        String modId = modId(root, java, resources, modProps);
        if (modId == null) throw new IOException("No mod id in the mod's fabric.mod.json or neoforge.mods.toml, so the starter build cannot name the mod.");
        props.put("mod_id", modId);
        String modVersion = modProps.getOrDefault("mod_version", modProps.getOrDefault("version", "1.0.0"));
        String group = modProps.getOrDefault("mod_group_id", modProps.getOrDefault("maven_group", modProps.getOrDefault("group", "com.example")));
        if (loader.equals("neoforge")) {
            props.put("mod_version", modVersion);
            props.put("mod_group_id", group);
            for (String k : List.of("mod_name", "mod_license", "mod_authors", "mod_description")) if (modProps.containsKey(k)) props.put(k, modProps.get(k));
        } else {
            props.put("version", modVersion);
            props.put("group", group);
        }
        // Values the old build expanded under another name ("mod_display_name": project.mod_name).
        Matcher alias = Pattern.compile("[\"']?(\\w+)[\"']?\\s*[:=]\\s*(?:project\\.)?(\\w+)\\s*[,\\]\\n]").matcher(oldScripts);
        while (alias.find()) if (!props.containsKey(alias.group(1)) && props.containsKey(alias.group(2))) props.put(alias.group(1), props.get(alias.group(2)));
        // Gradle's own switches the starter turns on that an unprepared mod can trip over: no configuration cache.
        props.put("org.gradle.configuration-cache", "false");

        // The mod's names: a yarn-named Fabric mod keeps yarn at its own version.
        String yarn = modProps.getOrDefault("yarn_mappings", "");
        String text = Files.readString(starter.resolve("build.gradle"), StandardCharsets.UTF_8);
        boolean hasClient = !clientJava.isEmpty();
        if (loader.equals("fabric")) {
            text = text.replace("\"modid\"", "\"" + modId + "\"");
            if (!hasClient) {
                text = text.replaceAll("(?m)^\\s*splitEnvironmentSourceSets\\(\\)\\s*\\R", "");
                text = text.replaceAll("(?m)^\\s*sourceSet\\s+sourceSets\\.client\\s*\\R", "");
            }
            if (!yarn.isEmpty() && text.contains("loom.officialMojangMappings()")) {
                text = text.replace("loom.officialMojangMappings()", "\"net.fabricmc:yarn:${project.yarn_mappings}:v2\"");
                notes.add("yarn names kept: net.fabricmc:yarn:" + yarn);
            } else if (yarn.isEmpty() && !text.contains("officialMojangMappings") && text.contains("yarn_mappings")) {
                text = text.replaceAll("\"net\\.fabricmc:yarn:\\$\\{project\\.yarn_mappings}:v2\"", "loom.officialMojangMappings()");
            }
            // The mod's fabric.mod.json may use any of its own properties, not only ${version}.
            text = text.replace("expand \"version\": version", "expand project.properties.findAll { k, v -> v instanceof String }");
        } else {
            text = text.replaceAll("(?s)var replaceProperties = \\[.*?\\]", "var replaceProperties = project.properties.findAll { k, v -> v instanceof String }");
        }

        StringBuilder extra = new StringBuilder("\n// Moved in by mmt port from the mod's own build.\n");
        extra.append("sourceSets {\n    main {\n");
        // Gradle reads its own default folders already; naming them again copies every resource twice.
        for (String d : java) if (!d.equals("src/main/java")) extra.append("        java.srcDir '").append(d).append("'\n");
        for (String d : resources) if (!d.equals("src/main/resources") && !text.contains("'" + d + "'")) extra.append("        resources.srcDir '").append(d).append("'\n");
        extra.append("    }\n");
        if (hasClient && loader.equals("fabric")) {
            extra.append("    client {\n");
            for (String d : clientJava) extra.append("        java.srcDir '").append(d).append("'\n");
            for (String d : clientResources) extra.append("        resources.srcDir '").append(d).append("'\n");
            extra.append("    }\n");
        }
        extra.append("}\n");
        // The mod's metadata file in its resources is filled from its properties, as its own build did.
        // So are the other files the old build filled (modid.mixins.json), and its renames (modid.mixins.json to ${mod_id}.mixins.json).
        Set<String> expanded = new LinkedHashSet<>(loader.equals("fabric") ? List.of() : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "pack.mcmeta"));
        Matcher matching = Pattern.compile("filesMatching\\s*\\(?\\s*(\\[[^\\]]*\\]|[\"'][^\"']+[\"'])").matcher(oldScripts);
        while (matching.find()) {
            Matcher quoted = Pattern.compile("[\"']([^\"'$]+)[\"']").matcher(matching.group(1));
            while (quoted.find()) if (!(loader.equals("fabric") && quoted.group(1).equals("fabric.mod.json"))) expanded.add(quoted.group(1));
        }
        List<String> renames = new ArrayList<>();
        Matcher rename = Pattern.compile("(?m)^\\s*rename\\s*\\(?\\s*(\"[^\"\\n]*\"|'[^'\\n]*')\\s*,\\s*(\"[^\"\\n]*\"|'[^'\\n]*')").matcher(oldScripts);
        while (rename.find()) renames.add("    rename " + rename.group(1) + ", " + rename.group(2) + "\n");
        if (!expanded.isEmpty() || !renames.isEmpty()) {
            extra.append("processResources {\n");
            if (!expanded.isEmpty()) {
                extra.append("    def mmtProps = project.properties.findAll { k, v -> v instanceof String }\n    inputs.properties mmtProps\n    filesMatching([");
                extra.append(String.join(", ", expanded.stream().map(f -> "'" + f + "'").toList())).append("]) { expand mmtProps }\n");
            }
            renames.forEach(extra::append);
            extra.append("}\n");
        }
        // The access widener (Fabric) or transformer (NeoForge) the mod declares.
        String widener = accessWidener(root, resources, props);
        if (widener == null && loader.equals("fabric")) {
            // The metadata names the packaged file (${mod_id}.accesswidener) and the old build renamed its source file to it.
            String named = accessWidenerName(root, resources, props);
            Path script = Path.of(Json.text(project, "buildFile", root.resolve("build.gradle").toString()));
            Matcher path = Pattern.compile("accessWidenerPath\\s*=\\s*(?:project\\.)?file\\(\\s*[\"']([^\"']+)[\"']").matcher(Files.isRegularFile(script) ? Files.readString(script) : "");
            if (named != null && path.find() && Files.isRegularFile(script.getParent().resolve(path.group(1)))) {
                widener = rel(root, script.getParent().resolve(path.group(1)).toAbsolutePath().normalize().toString());
                String file = Path.of(widener).getFileName().toString();
                if (!file.equals(named) && renames.isEmpty()) extra.append("processResources {\n    rename { it == '").append(file).append("' ? '").append(named).append("' : it }\n}\n");
            }
        }
        if (widener != null) extra.append("loom {\n    accessWidenerPath = file('").append(widener).append("')\n}\n");
        String transformer = accessTransformer(root, resources);
        if (transformer != null && !transformer.endsWith("src/main/resources/META-INF/accesstransformer.cfg")) {
            extra.append("neoForge {\n    accessTransformers.from(file('").append(transformer).append("'))\n}\n");
        }
        // Repositories and dependencies the mod declares, minus what the starter brings (the game, the loader, its API).
        Set<String> repos = new LinkedHashSet<>();
        for (String r : ProjectModel.strings(project.get("repositories"))) if (r.startsWith("https://") && !text.contains(r)) repos.add(r);
        if (!repos.isEmpty()) {
            extra.append("repositories {\n");
            for (String r : repos) extra.append("    maven { url = '").append(r).append("' }\n");
            extra.append("}\n");
        }
        List<String> deps = dependencies(project, loader, siblings, modProps);
        if (!deps.isEmpty()) {
            extra.append("dependencies {\n");
            for (String d : deps) extra.append("    ").append(d).append('\n');
            extra.append("}\n");
        }
        // The mod's unit tests: JUnit and, on NeoForge, the game on the test classpath (Loom puts it there already).
        if (Files.isDirectory(root.resolve("src/test/java"))) {
            extra.append("dependencies {\n    testImplementation platform('org.junit:junit-bom:5.10.2')\n")
                .append("    testImplementation 'org.junit.jupiter:junit-jupiter'\n    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'\n}\n")
                .append("tasks.named('test', Test).configure { useJUnitPlatform() }\n");
            if (loader.equals("neoforge")) extra.append("neoForge {\n    unitTest {\n        enable()\n        testedMod = mods.\"${mod_id}\"\n    }\n}\n");
        }
        notes.add(deps.size() + " dependencies, " + java.size() + " source folders, " + resources.size() + " resource folders moved in");

        // Write: build files at the root, the starter's wrapper; the old root build files and buildSrc go (undo restores them).
        for (String old : List.of("build.gradle.kts", "settings.gradle.kts")) {
            Path p = root.resolve(old);
            if (Files.isRegularFile(p)) {
                track.accept(p);
                Files.delete(p);
                removed.add(p);
            }
        }
        Path buildSrc = root.resolve("buildSrc");
        if (Files.isDirectory(buildSrc)) {
            try (var walk = Files.walk(buildSrc)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.isRegularFile(f)) {
                        track.accept(f);
                        removed.add(f);
                    }
                    Files.delete(f);
                }
            }
        }
        write(root.resolve("build.gradle"), text + extra, track, written);
        String settings = Files.readString(starter.resolve("settings.gradle"), StandardCharsets.UTF_8)
            .replaceAll("(?m)^\\s*rootProject\\.name\\s*=.*$", "")
            + "\nrootProject.name = '" + modId + "'\n";
        write(root.resolve("settings.gradle"), settings, track, written);
        StringBuilder properties = new StringBuilder("# Written by mmt port: the loader's starter properties, then the mod's own.\n");
        for (var e : props.entrySet()) properties.append(e.getKey()).append('=').append(e.getValue().replace("\n", "\\n")).append('\n');
        write(root.resolve("gradle.properties"), properties.toString(), track, written);
        for (String f : List.of("gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat")) {
            Path from = starter.resolve(f), to = root.resolve(f);
            if (!Files.isRegularFile(from)) continue;
            track.accept(to);
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            written.add(to);
        }
        return new Result(written, removed, notes);
    }

    /** The module folders the old settings file includes (include 'common', include(":neoforge")), lower case. */
    static Set<String> included(Path root) throws IOException {
        Set<String> names = new HashSet<>();
        for (String settings : List.of("settings.gradle", "settings.gradle.kts")) {
            Path file = root.resolve(settings);
            if (!Files.isRegularFile(file)) continue;
            Matcher include = Pattern.compile("(?m)^\\s*include\\s*\\(?([^\\n)]*)").matcher(Files.readString(file));
            while (include.find()) {
                Matcher name = Pattern.compile("[\"']:?([\\w.-]+)[\"']").matcher(include.group(1));
                while (name.find()) names.add(name.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * A version the mod's properties hold once, written as that property (${jei_version}), so the porter's target step moves it
     * as it does in the mod's own build. Names stay as declared: AppleSkin pins jei-1.20.1-fabric on every version.
     */
    static String versionProperty(String version, Map<String, String> props) {
        List<String> keys = new ArrayList<>();
        for (var e : props.entrySet()) if (e.getValue().equals(version)) keys.add(e.getKey());
        return keys.size() == 1 ? "${" + keys.get(0) + "}" : version;
    }

    /** Included modules the selected project depends on: Gradle reports a project dependency by the module's name. */
    static Set<String> siblings(Path root, Map<String, Object> project) throws IOException {
        Set<String> included = included(root), names = new HashSet<>();
        for (Object o : Json.array(project.get("dependencies"))) {
            String name = Json.text(Json.object(o), "name", "").toLowerCase(Locale.ROOT);
            if (included.contains(name)) names.add(name);
        }
        return names;
    }

    static void write(Path file, String text, Consumer<Path> track, List<Path> written) throws IOException {
        track.accept(file);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        written.add(file);
    }

    static String rel(Path root, String dir) {
        return root.relativize(Path.of(dir).toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The mod id its metadata declares, a ${property} filled from its properties. */
    static String modId(Path root, List<String> java, List<String> resources, Map<String, String> props) throws IOException {
        for (String r : resources) {
            Path dir = root.resolve(r);
            for (Path meta : List.of(dir.resolve("fabric.mod.json"), dir.resolve("META-INF/neoforge.mods.toml"), dir.resolve("META-INF/mods.toml"))) {
                if (!Files.isRegularFile(meta)) continue;
                String text = Files.readString(meta);
                Matcher m = (meta.toString().endsWith(".json") ? Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"") : Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"")).matcher(text);
                if (m.find()) return fill(m.group(1), props);
            }
        }
        // A NeoForge mod keeps its metadata template in src/main/templates.
        for (String j : java) {
            Path templates = root.resolve(j).getParent().resolve("templates/META-INF/neoforge.mods.toml");
            if (Files.isRegularFile(templates)) {
                Matcher m = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(templates));
                if (m.find()) return fill(m.group(1), props);
            }
        }
        return props.get("mod_id");
    }

    static String fill(String value, Map<String, String> props) {
        Matcher m = Pattern.compile("\\$\\{(\\w+)}").matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
        return m.appendTail(out).toString();
    }

    static String accessWidener(Path root, List<String> resources, Map<String, String> props) throws IOException {
        String named = accessWidenerName(root, resources, props);
        if (named != null) for (String d : resources) if (Files.isRegularFile(root.resolve(d).resolve(named))) return d + "/" + named;
        return null;
    }

    /** The access widener file the mod's fabric.mod.json names, its ${properties} filled. */
    static String accessWidenerName(Path root, List<String> resources, Map<String, String> props) throws IOException {
        for (String r : resources) {
            Path meta = root.resolve(r).resolve("fabric.mod.json");
            if (!Files.isRegularFile(meta)) continue;
            Matcher m = Pattern.compile("\"accessWidener\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(meta));
            if (m.find()) return fill(m.group(1), props);
        }
        return null;
    }

    static String accessTransformer(Path root, List<String> resources) {
        for (String r : resources) if (Files.isRegularFile(root.resolve(r).resolve("META-INF/accesstransformer.cfg"))) return r + "/META-INF/accesstransformer.cfg";
        return null;
    }

    /** Configurations a mod declares its own dependencies in (what plugins add for themselves is left out). */
    static final Set<String> DECLARED = Set.of("implementation", "api", "compileOnly", "compileOnlyApi", "runtimeOnly", "localRuntime",
        "annotationProcessor", "jarJar", "include", "modImplementation", "modApi", "modCompileOnly", "modCompileOnlyApi", "modRuntimeOnly",
        "modLocalRuntime", "testImplementation", "testCompileOnly", "testRuntimeOnly");

    /** The mod's declared dependencies as starter-build lines, without the game, the loader, its API or test libraries. */
    static List<String> dependencies(Map<String, Object> project, String loader, Set<String> siblings, Map<String, String> props) {
        Set<String> lines = new LinkedHashSet<>();
        for (Object o : Json.array(project.get("dependencies"))) {
            var d = Json.object(o);
            String conf = Json.text(d, "configuration", ""), group = Json.text(d, "group", ""), name = Json.text(d, "name", ""), version = Json.text(d, "version", "");
            if (!DECLARED.contains(conf) || group.isEmpty() || version.isEmpty() || name.equals("unspecified")) continue;
            if (siblings.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (group.startsWith("net.neoforged") || group.startsWith("net.fabricmc") || group.equals("com.mojang") || group.startsWith("net.minecraft")
                || group.startsWith("org.junit") || group.startsWith("ng_dummy") || group.startsWith("loom_mappings")) continue;
            // NeoForge's ModDevGradle has no mod* configurations: the jar is used as it is.
            if (loader.equals("neoforge") && conf.startsWith("mod")) conf = Character.toLowerCase(conf.charAt(3)) + conf.substring(4);
            if (loader.equals("neoforge") && conf.equals("include")) conf = "jarJar";
            if (loader.equals("fabric") && conf.equals("jarJar")) conf = "include";
            lines.add(conf + " \"" + group + ":" + name + ":" + versionProperty(version, props) + "\"");
        }
        return new ArrayList<>(lines);
    }
}
