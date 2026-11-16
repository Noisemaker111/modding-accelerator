import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Resolve published Fabric pins, explicit dependency selections and one safe manifest retarget. */
final class Target {

    record Edit(Path file, String before, String after) {}

    /** A lookup that got no answer (network error, 5xx, 429): never evidence that something is unpublished. */
    static final class LookupFailure extends IOException {

        LookupFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Accumulates every target change as text, so one file is never edited twice against stale content. */
    static final class Setup {

        final Build build;
        final Map<String, Object> project;
        final String to;
        final List<Dependencies.Selection> selections;
        final Map<String, String> replacements = new LinkedHashMap<>(), pins = new LinkedHashMap<>();
        final Map<Path, String> texts = new LinkedHashMap<>(), originals = new LinkedHashMap<>();
        final List<Map<String, Object>> applied = new ArrayList<>(), blockers = new ArrayList<>(), unchecked =
            new ArrayList<>();
        final List<Path> excluded = new ArrayList<>();
        /** Target dependency (version) -> the Loom version its jar was built with. */
        final Map<String, String> loomBuilt = new LinkedHashMap<>();

        Setup(Build build, Map<String, Object> project, String to, List<Dependencies.Selection> selections) {
            this.build = build;
            this.project = project;
            this.to = to;
            this.selections = selections;
        }

        String text(Path file) throws IOException {
            return texts.containsKey(file) ? texts.get(file) : Files.readString(file);
        }

        void replace(Path file, String from, String to) throws IOException {
            String text = text(file);
            int at = text.indexOf(from);
            if (at < 0) throw new IOException("Cannot locate an unambiguous setting in " + file + ": " + from);
            if (text.indexOf(from, at + 1) >= 0) throw new IOException(
                "Multiple matching settings in " + file + "; resolve their owner first: " + from
            );
            originals.putIfAbsent(file, text);
            texts.put(file, text.substring(0, at) + to + text.substring(at + from.length()));
        }

        List<Edit> edits() {
            List<Edit> edits = new ArrayList<>();
            for (Path file : texts.keySet()) if (!Objects.equals(originals.get(file), texts.get(file))) edits.add(
                new Edit(file, originals.get(file), texts.get(file))
            );
            return edits;
        }
    }

    static Setup plan(Build build, Map<String, Object> model, String to) throws Exception {
        return plan(build, model, to, List.of());
    }

    static Setup plan(Build build, Map<String, Object> model, String to, List<Dependencies.Selection> selections)
        throws Exception {
        List<Object> projects = Json.array(model.get("projects"));
        // A multi-module mod on one loader (Cardinal Components' modules, owo-lib and its sentinel) keeps its versions in
        // the shared gradle.properties, so the root project's setup retargets every module.
        // Plain helper projects with no loader (Twilight Forest's :tf-asm) are not mods to retarget.
        List<Object> modProjects = projects.stream().filter(p -> !Support.loaders(Json.object(p)).isEmpty()).toList();
        Set<Set<String>> loaderSets = new HashSet<>();
        for (Object p : modProjects) loaderSets.add(Support.loaders(Json.object(p)));
        Object root = modProjects.stream().filter(p -> ":".equals(Json.object(p).get("path"))).findFirst().orElse(null);
        boolean oneLoader = loaderSets.size() == 1 && loaderSets.iterator().next().size() == 1 && (root != null || modProjects.size() == 1);
        if (projects.size() != 1 && !oneLoader) throw new IOException(
            "Automatic target setup needs one selected project; choose --project."
        );
        var project = Json.object(projects.size() == 1 ? projects.get(0) : modProjects.size() == 1 ? modProjects.get(0) : root);
        Set<String> loaders = Support.loaders(project);
        // A NeoForge build (ModDevGradle or NeoGradle applied) that also lists a Forge artifact among its dependencies is NeoForge.
        String appliedPlugins = String.valueOf(project.get("plugins")).toLowerCase(Locale.ROOT);
        if (loaders.equals(Set.of("forge", "neoforge")) && appliedPlugins.matches("(?s).*(moddev|net\\.neoforged\\.gradle).*")) loaders = Set.of("neoforge");
        if (!loaders.equals(Set.of("fabric")) && !loaders.equals(Set.of("neoforge"))) throw new IOException(
            "Automatic target dependency setup requires one Fabric or NeoForge project; found " +
            loaders +
            ". Set this loader's target dependencies in the source build, then rerun port --from OLD --to NEW --fix."
        );
        Setup setup = new Setup(build, project, to, selections);
        var pins = new LinkedHashMap<>(Json.object(project.get("pins")));
        for (var pin : pins.entrySet()) if (pin.getValue() != null) setup.pins.put(
            pin.getKey(),
            pin.getValue().toString()
        );
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (
            pins.containsKey(key)
        ) setup.replacements.put(key, to);
        if (setup.replacements.isEmpty()) throw new IOException(
            "Minecraft is pinned inside build logic; update its owning setting, then rerun port."
        );
        if (loaders.equals(Set.of("neoforge"))) {
            String resolved = neoForgeVersion(build, to);
            boolean owned = false;
            for (String key : List.of("neo_version", "neoforge_version", "neoForgeVersion", "neoforgeVersion")) if (
                pins.containsKey(key)
            ) {
                setup.replacements.put(key, resolved);
                owned = true;
            }
            if (!owned) throw new IOException(
                "No NeoForge version pin (neo_version/neoforge_version) in this project; the build would keep its old NeoForge and Minecraft jars. Add or expose that property, then rerun port."
            );
            // Mod metadata declares an accepted game range; leaving it on the old version makes NeoForge refuse the mod at runtime.
            if (pins.containsKey("minecraft_version_range")) setup.replacements.put(
                "minecraft_version_range",
                "[" + to + "]"
            );
            for (String key : List.of("neo_version_range", "neoforge_version_range")) if (
                pins.containsKey(key)
            ) setup.replacements.put(key, "[" + resolved + ",)");
            // A multi-loader build keeps Fabric's versions beside NeoForge's: the sibling project must follow the same game.
            try {
                fabricPins(setup, build, pins, to);
                // The Fabric project's Loom follows the jars its Fabric API was built with, as in a Fabric port.
                if (!setup.loomBuilt.isEmpty()) loomFollows(setup, build, to);
                unobfuscatedFabric(setup, build, to);
            } catch (IOException sibling) {
                setup.unchecked.add(Json.map("artifact", "Fabric (sibling project)", "version", to, "reason", sibling.getMessage()));
            }
            forgeSibling(setup, build, pins, to);
            Dependencies.resolve(setup, "neoforge");
            siblingDependencies(setup, build, modProjects, project, to, selections);
            applyReplacements(setup, build, pins);
            neoGradleFollows(setup, build, to);
            modDevFollows(setup, build, to);
            noAdditionalRuntimeClasspath(setup, build, to);
            neoFormFollows(setup, build, to);
            toolchain(setup, build, to);
            gradleForJava25(setup, build, to);
            buildDslFollows(setup, build);
            runTypeRename(setup, build, to);
            parchmentFollows(setup, build, to);
            unitTestsInFml(setup, build, to, resolved);
            setup.applied.add(
                Json.map(
                    "artifact",
                    "net.neoforged:neoforge",
                    "to",
                    resolved,
                    "identity",
                    "resolved from maven.neoforged.net",
                    "gameVersion",
                    to,
                    "evidence",
                    "NeoForge " + resolved + " publishes for " + to
                )
            );
            return setup;
        }
        fabricPins(setup, build, pins, to);
        // A multi-loader build keeps NeoForge's version beside Fabric's: the sibling project must follow the same game.
        neoForgeSibling(setup, build, pins, to);
        forgeSibling(setup, build, pins, to);
        neoFormFollows(setup, build, to);
        Dependencies.resolve(setup);
        siblingDependencies(setup, build, modProjects, project, to, selections);
        applyReplacements(setup, build, pins);
        loomFollows(setup, build, to);
        unobfuscatedFabric(setup, build, to);
        buildDslFollows(setup, build);
        toolchain(setup, build, to);
        gradleForJava25(setup, build, to);
        parchmentFollows(setup, build, to);
        manifest(setup);
        return setup;
    }

    /**
     * A multi-loader build ported through one loader's project still builds the other loaders' modules: their own
     * libraries move to the target too (TorchMaster's Fabric module kept owo-lib 0.12.11+1.21, whose 1.21 jar Loom then
     * refused on 26.3). Each other single-loader mod project is resolved like the selected one, against the same pending
     * edits, and its changes, blockers and unchecked entries join the port's.
     */
    static void siblingDependencies(Setup setup, Build build, List<Object> modProjects, Map<String, Object> selected, String to,
        List<Dependencies.Selection> selections) throws Exception {
        // A port selected to one project read only that project; the others are read once from a model of every project.
        if (modProjects.size() <= 1 && !build.selected.isBlank()) {
            try {
                modProjects = Json.array(build.allProjects().model(false, true).get("projects"))
                    .stream()
                    .filter(p -> !Support.loaders(Json.object(p)).isEmpty())
                    .toList();
            } catch (Exception unreadable) {
                setup.unchecked.add(Json.map("artifact", "the other loaders' projects", "version", to, "reason", "the build could not be read with every project: " + unreadable.getMessage()));
                return;
            }
        }
        for (Object item : modProjects) {
            var sibling = Json.object(item);
            if (sibling == selected || Objects.equals(sibling.get("path"), selected.get("path"))) continue;
            Set<String> loaders = Support.loaders(sibling);
            if (loaders.size() != 1 || !(loaders.contains("fabric") || loaders.contains("neoforge"))) continue;
            Setup view = new Setup(build, sibling, to, selections);
            view.texts.putAll(setup.texts);
            view.originals.putAll(setup.originals);
            view.pins.putAll(setup.pins);
            // The sibling's own properties (Waystones' journeymap_api_version), so a version it moves can be written where
            // it is defined.
            for (var pin : Json.object(sibling.get("pins")).entrySet()) if (pin.getValue() != null) {
                view.pins.putIfAbsent(pin.getKey(), pin.getValue().toString());
                setup.pins.putIfAbsent(pin.getKey(), pin.getValue().toString());
            }
            view.replacements.putAll(setup.replacements);
            try {
                Dependencies.resolve(view, loaders.iterator().next());
            } catch (IOException unresolved) {
                setup.unchecked.add(Json.map("artifact", "dependencies of project " + view.project.get("path"), "version", to, "reason", String.valueOf(unresolved.getMessage())));
                continue;
            }
            setup.texts.putAll(view.texts);
            for (var original : view.originals.entrySet()) setup.originals.putIfAbsent(original.getKey(), original.getValue());
            for (var r : view.replacements.entrySet()) {
                if (setup.pins.containsKey(r.getKey())) setup.replacements.putIfAbsent(r.getKey(), r.getValue());
                else setup.unchecked.add(Json.map("artifact", r.getKey(), "version", r.getValue(), "reason", "a library of project " + sibling.get("path") + " moves, but its property has no gradle.properties owner mmt can find"));
            }
            setup.applied.addAll(view.applied);
            setup.blockers.addAll(view.blockers);
            setup.unchecked.addAll(view.unchecked);
            setup.excluded.addAll(view.excluded);
            for (var built : view.loomBuilt.entrySet()) setup.loomBuilt.putIfAbsent(built.getKey(), built.getValue());
        }
    }

    /**
     * A Fabric build moving onto the unobfuscated game (26.1 and later) stops remapping: Minecraft ships with Mojang's names,
     * so Loom's plugin is net.fabricmc.fabric-loom (fabric-loom and fabric-loom-remap remap), the mappings line goes, the
     * remapping configurations become the plain ones (modImplementation -> implementation, ...), remapJar becomes jar, and
     * Java rises to the game's. Written to the build files that apply Loom; a backport onto an obfuscated game is not
     * changed here.
     */
    static void unobfuscatedFabric(Setup setup, Build build, String to) throws Exception {
        String from = null;
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (
            setup.pins.get(key) != null
        ) from = setup.pins.get(key);
        if (from == null || Versions.compare(to, "26.1") < 0 || Versions.compare(from, "26.1") >= 0) return;
        Set<Path> files = new LinkedHashSet<>();
        Object own = setup.project.get("buildFile");
        if (own != null) files.add(Path.of(own.toString()));
        for (String name : List.of("build.gradle", "build.gradle.kts")) files.add(build.project.root.resolve(name));
        // Every module's own script: a multi-loader build ported through its NeoForge project still configures its Fabric
        // one (Waystones, Carry On), which must stop remapping too. Scripts that apply no Loom are left unchanged.
        Set<String> resourceDirs = new LinkedHashSet<>();
        for (Path input : build.project.inputs()) if (
            input.getFileName().toString().matches("build\\.gradle(\\.kts)?") &&
            !build.project.root.relativize(input).toString().replace('\\', '/').startsWith("buildSrc/")
        ) {
            files.add(input);
            resourceDirs.add(input.resolveSibling("src").toString());
        }
        // An access widener names the namespace its entries are in; on the unobfuscated game that is "official". The planning
        // model may list no resource folders (Origins), so the project's src folder is searched too.
        for (Object set : Json.array(setup.project.get("sourceSets"))) resourceDirs.addAll(
            ProjectModel.strings(Json.object(set).get("resources"))
        );
        Object projectDir = setup.project.get("directory");
        resourceDirs.add((projectDir == null ? build.project.root : Path.of(projectDir.toString())).resolve("src").toString());
        for (String resources : resourceDirs) {
            Path dir = Path.of(resources);
            if (!Files.isDirectory(dir)) continue;
            List<Path> wideners;
            try (var walk = Files.walk(dir, 6)) {
                wideners = walk.filter(p -> p.toString().endsWith(".accesswidener") || p.toString().endsWith(".classtweaker")).toList();
            }
            for (Path widener : wideners) {
                String before = setup.text(widener), text = before.replaceFirst(
                    "^(\\s*(?:accessWidener|classTweaker)\\s+v\\d+\\s+)named\\b",
                    "$1official"
                );
                if (text.equals(before)) continue;
                setup.originals.putIfAbsent(widener, before);
                setup.texts.put(widener, text);
                setup.applied.add(Json.map("file", widener.toString(), "change", "access widener namespace named -> official for " + to));
            }
        }
        // A script a Loom script applies (apply from: 'dependencies.gradle', Waystones' fabric module) declares its
        // dependencies with the same remapping configurations, though it names no plugin itself.
        Set<Path> applied = new LinkedHashSet<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file) || !appliesLoom(setup.text(file))) continue;
            Matcher applies = Pattern.compile(
                "apply\\s*\\(?\\s*from\\s*[:=]\\s*(?:rootProject\\.file\\(\\s*)?[\"']([^\"'$]+\\.gradle(?:\\.kts)?)[\"']"
            ).matcher(setup.text(file));
            while (applies.find()) {
                Path base = applies.group().contains("rootProject") ? build.project.root : file.getParent();
                Path script = base.resolve(applies.group(1)).normalize();
                if (script.startsWith(build.project.root) && Files.isRegularFile(script) && !files.contains(script)) applied.add(script);
            }
        }
        // Convention plugins in buildSrc (the MultiLoader template: FerriteCore's remapJar.archivePath) configure the Loom
        // modules from outside them, naming the same remapping tasks and configurations.
        for (Path input : build.project.inputs()) {
            String relative = build.project.root.relativize(input).toString().replace('\\', '/');
            if (relative.matches("buildSrc/src/main/.+\\.gradle(\\.kts)?") && !files.contains(input)) applied.add(input);
        }
        for (Path script : applied) {
            // A convention plugin that applies Loom itself (ImmediatelyFast's base-conventions) gets the whole rewrite.
            String before = setup.text(script);
            String text = appliesLoom(before) ? unobfuscatedLoom(before, javaFor(to)) : remapTasks(plainConfigurations(before));
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(script, before);
            setup.texts.put(script, text);
            setup.applied.add(Json.map("file", script.toString(), "change", "remapping configurations -> plain ones for " + to + " (applied by a Loom script)"));
        }
        fabricMavenFilter(setup, build);
        boolean anyLoom = false;
        for (Path file : files) if (Files.isRegularFile(file) && appliesLoom(setup.text(file))) anyLoom = true;
        String newestLoom = null, newestArchitectury = null, newestArchitecturyPlugin = null;
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            String before = setup.text(file), text = unobfuscatedLoom(before, javaFor(to));
            // A module script Loom reaches from elsewhere (MidnightLib's root applies it to every subproject, and
            // common/build.gradle names remapJar) uses the same names.
            if (!appliesLoom(before) && anyLoom) text = remapTasks(plainConfigurations(before));
            // A Loom older than the unobfuscated line is not published under net.fabricmc.fabric-loom (FerriteCore kept
            // 1.5-SNAPSHOT): it rises to the newest release.
            Matcher old = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']net\\.fabricmc\\.fabric-loom[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            StringBuilder raised = new StringBuilder();
            while (old.find()) {
                String declared = old.group(2).replace("-SNAPSHOT", "");
                if (Versions.compare(declared, FIRST_UNOBFUSCATED_LOOM) >= 0) continue;
                if (newestLoom == null) newestLoom = newestLoom(build, newestLoomLine(build));
                old.appendReplacement(raised, Matcher.quoteReplacement(old.group(1) + newestLoom + old.group(3)));
            }
            text = old.appendTail(raised).toString();
            Matcher arch = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']dev\\.architectury\\.loom-no-remap[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            StringBuilder archRaised = new StringBuilder();
            while (arch.find()) {
                String declared = arch.group(2).replace("-SNAPSHOT", "");
                if (Versions.compare(declared, FIRST_UNOBFUSCATED_LOOM) >= 0) continue;
                if (newestArchitectury == null) newestArchitectury = newestArchitecturyLoom(build);
                arch.appendReplacement(archRaised, Matcher.quoteReplacement(arch.group(1) + newestArchitectury + arch.group(3)));
            }
            text = arch.appendTail(archRaised).toString();
            // The Architectury plugin configures every platform from the common project and, before its newest line,
            // expects Loom's remap tasks there (MidnightLib: "Task with name 'remapJar' not found in project ':common'").
            Matcher plugin = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']architectury-plugin[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            if (plugin.find()) {
                if (newestArchitecturyPlugin == null) newestArchitecturyPlugin = newestArchitecturyPlugin(build);
                if (!plugin.group(2).equals(newestArchitecturyPlugin)) text = text.substring(0, plugin.start()) + plugin.group(1) + newestArchitecturyPlugin + plugin.group(3) + text.substring(plugin.end());
            }
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "Loom without remapping for " + to + ": plugin net.fabricmc.fabric-loom, no mappings, plain configurations, jar for remapJar, Java " + javaFor(to),
                    "evidence",
                    "Minecraft " + to + " ships unobfuscated, with Mojang's names"
                )
            );
        }
        if (newestArchitectury != null) gradleFollows(
            setup,
            build,
            "https://maven.architectury.dev/dev/architectury/architectury-loom/" + newestArchitectury + "/architectury-loom-" + newestArchitectury + ".module",
            "Architectury Loom " + newestArchitectury,
            "architectury-loom " + newestArchitectury
        );
        if (newestLoom != null) {
            loomInRoot(setup, build, newestLoom);
            gradleFollows(
                setup,
                build,
                "https://maven.fabricmc.net/net/fabricmc/fabric-loom/" + newestLoom + "/fabric-loom-" + newestLoom + ".module",
                "Loom " + newestLoom,
                "fabric-loom " + newestLoom
            );
        }
    }

    /**
     * ModDevGradle keeps its separate additionalRuntimeClasspath only through Minecraft 1.21.9
     * (VersionCapabilitiesInternal.hasLegacyClasspath); later it refuses any dependency added there ("there is no
     * additional classpath anymore"), since a plain runtime dependency is on the game's class path. Ars Nouveau's Lucene
     * libraries move to runtimeOnly.
     */
    static void noAdditionalRuntimeClasspath(Setup setup, Build build, String to) throws IOException {
        if (Versions.compare(to, "1.21.9") <= 0) return;
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.matches(".+\\.gradle(\\.kts)?") || name.startsWith("settings")) continue;
            String before = setup.text(file), text = withoutAdditionalRuntimeClasspath(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(Json.map("file", file.toString(), "change", "additionalRuntimeClasspath -> runtimeOnly for " + to, "evidence", "ModDevGradle has no additional runtime classpath after Minecraft 1.21.9"));
        }
    }

    /**
     * A settings filter that sends only named groups to Fabric's Maven (TorchMaster: includeGroup('net.fabricmc') and
     * includeGroup('fabric-loom')) must also pass what a newer Loom brings: its plugin marker for the unobfuscated id
     * (group net.fabricmc.fabric-loom) and its own libraries (net.fabricmc.unpick). net.fabricmc becomes
     * includeGroupAndSubgroups (Gradle 8.1+, which every Loom of that age needs).
     */
    static void fabricMavenFilter(Setup setup, Build build) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) {
            Path file = build.project.root.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            String before = setup.text(file), text = fabricSubgroups(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(Json.map("file", file.toString(), "change", "Fabric Maven filter passes net.fabricmc and its subgroups (Loom's plugin marker and libraries)"));
        }
    }

    static String fabricSubgroups(String text) {
        return text.replaceAll("includeGroup(\\s*\\(?\\s*([\"'])net\\.fabricmc\\2\\s*\\)?)", "includeGroupAndSubgroups$1");
    }

    static String withoutAdditionalRuntimeClasspath(String text) {
        return text.replaceAll("(?<![\\w$])additionalRuntimeClasspath(?![\\w$])", "runtimeOnly");
    }

    static final Pattern LOOM_WITH_VERSION = Pattern.compile(
        "(id\\s*\\(?\\s*([\"'])(fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?)\\2(?:\\s*\\))?)\\s*version\\s*\\(?\\s*[\"'][^\"']+[\"'](?:\\s*\\))?"
    );

    static String withoutLoomVersion(String text) {
        return LOOM_WITH_VERSION.matcher(text).replaceAll("$1");
    }

    /** A root build script that declares Loom (apply false) in its plugins block; null when it has a buildscript block and no plugins block. */
    static String rootLoom(String text, String id, String loom, String quote, boolean kotlin) {
        String line = kotlin
            ? "id(\"" + id + "\") version \"" + loom + "\" apply false"
            : "id " + quote + id + quote + " version " + quote + loom + quote + " apply false";
        Matcher plugins = Pattern.compile("(?m)^plugins\\s*\\{").matcher(text);
        if (plugins.find()) return text.substring(0, plugins.end()) + "\n    " + line + text.substring(plugins.end());
        return text.contains("buildscript") ? null : "plugins {\n    " + line + "\n}\n\n" + text;
    }

    /** The first Loom that applies its companion plugin (net.fabricmc.fabric-loom-companion) across a multi-project build. */
    static final String FIRST_COMPANION_LOOM = "1.12";

    /**
     * Loom 1.12 and later applies a companion plugin by id across the build, which only resolves when the root project has
     * Loom on its class path (Repurposed 1.21.5 -> 1.21.6: "Plugin with id 'net.fabricmc.fabric-loom-companion' not
     * found"). When only subprojects declare Loom, the root declares it once with apply false and the subprojects drop
     * their version, as Gradle requires of a plugin already on the class path.
     */
    static void loomInRoot(Setup setup, Build build, String loom) throws IOException {
        if (Versions.compare(loom, FIRST_COMPANION_LOOM) < 0) return;
        Path root = null;
        for (String name : List.of("build.gradle", "build.gradle.kts")) if (Files.isRegularFile(build.project.root.resolve(name))) root = build.project.root.resolve(name);
        if (root == null || appliesLoom(setup.text(root))) return;
        Map<Path, String> subprojects = new LinkedHashMap<>();
        String id = null, quote = "'";
        for (Path file : build.project.inputs()) {
            String name = build.project.root.relativize(file).toString().replace('\\', '/');
            if (file.equals(root) || name.startsWith("buildSrc/") || !name.matches(".+/build\\.gradle(\\.kts)?")) continue;
            Matcher m = LOOM_WITH_VERSION.matcher(setup.text(file));
            if (!m.find()) continue;
            id = m.group(3);
            quote = m.group(2);
            subprojects.put(file, withoutLoomVersion(setup.text(file)));
        }
        if (id == null) return;
        String before = setup.text(root), text = rootLoom(before, id, loom, quote, root.toString().endsWith(".kts"));
        if (text == null) return; // a buildscript block and no plugins block: left as it is
        for (var sub : subprojects.entrySet()) {
            setup.originals.putIfAbsent(sub.getKey(), setup.text(sub.getKey()));
            setup.texts.put(sub.getKey(), sub.getValue());
        }
        setup.originals.putIfAbsent(root, before);
        setup.texts.put(root, text);
        setup.applied.add(Json.map("file", root.toString(), "change", "Loom " + loom + " declared in the root project (apply false), so its companion plugin resolves", "evidence", "Loom " + FIRST_COMPANION_LOOM + "+ applies net.fabricmc.fabric-loom-companion across the build"));
    }

    /** The first Loom published under net.fabricmc.fabric-loom, the plugin id of the unobfuscated game. */
    static final String FIRST_UNOBFUSCATED_LOOM = "1.14";

    static boolean appliesLoom(String text) {
        return Pattern.compile(
            "[\"'](?:fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?|dev\\.architectury\\.loom(?:-remap|-no-remap)?)[\"']"
        ).matcher(text).find();
    }

    /** The newest Loom minor line Fabric publishes (1.19 when this was written). */
    static String newestLoomLine(Build build) throws Exception {
        Matcher v = Pattern.compile("<version>(\\d+\\.\\d+)\\.\\d+</version>").matcher(
            fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml")
        );
        String line = "";
        while (v.find()) if (line.isEmpty() || Versions.compare(v.group(1), line) > 0) line = v.group(1);
        if (line.isEmpty()) throw new IOException("No published Fabric Loom release");
        return line;
    }

    /** Loom's remapping tasks as the plain ones (remapJar -> jar, remapSourcesJar -> sourcesJar). */
    static String remapTasks(String text) {
        return text.replaceAll("(?<![\\w$])remapSourcesJar(?![\\w$])", "sourcesJar").replaceAll("(?<![\\w$])remapJar(?![\\w$])", "jar");
    }

    /** Loom's remapping configurations as the plain ones the unobfuscated game uses. */
    static String plainConfigurations(String text) {
        Map<String, String> configurations = new LinkedHashMap<>();
        configurations.put("modImplementation", "implementation");
        configurations.put("modApi", "api");
        configurations.put("modCompileOnlyApi", "compileOnlyApi");
        configurations.put("modCompileOnly", "compileOnly");
        configurations.put("modRuntimeOnly", "runtimeOnly");
        configurations.put("modLocalRuntime", "localRuntime");
        for (var c : configurations.entrySet()) text = text.replaceAll("(?<![\\w$])" + c.getKey() + "(?![\\w$])", c.getValue());
        return text;
    }

    /** One build script rewritten for Loom on an unobfuscated game; unchanged when it does not apply Loom. */
    static String unobfuscatedLoom(String text, int java) {
        if (!appliesLoom(text)) return text;
        text = text.replaceAll("([\"'])(?:fabric-loom|net\\.fabricmc\\.fabric-loom-remap)([\"'])", "$1net.fabricmc.fabric-loom$2");
        // Architectury's fork splits the same way: dev.architectury.loom-no-remap is its plugin for the unobfuscated game.
        text = text.replaceAll("([\"'])dev\\.architectury\\.loom(?:-remap)?([\"'])", "$1dev.architectury.loom-no-remap$2");
        text = removeStatements(text, Pattern.compile("(?m)^[ \\t]*mappings\\b"));
        Map<String, String> configurations = new LinkedHashMap<>();
        configurations.put("modImplementation", "implementation");
        configurations.put("modApi", "api");
        configurations.put("modCompileOnlyApi", "compileOnlyApi");
        configurations.put("modCompileOnly", "compileOnly");
        configurations.put("modRuntimeOnly", "runtimeOnly");
        configurations.put("modLocalRuntime", "localRuntime");
        configurations.put("remapSourcesJar", "sourcesJar");
        configurations.put("remapJar", "jar");
        for (var c : configurations.entrySet()) text = text.replaceAll("(?<![\\w$])" + c.getKey() + "(?![\\w$])", c.getValue());
        text = text.replaceAll("JavaVersion\\.VERSION_(?:1_8|1[0-9]|2[0-4])\\b", "JavaVersion.VERSION_" + java);
        text = text.replaceAll("JavaLanguageVersion\\.of\\((?:[89]|1[0-9]|2[0-4])\\)", "JavaLanguageVersion.of(" + java + ")");
        text = text.replaceAll("(options\\.release(?:\\.set\\(|\\s*=\\s*))(?:[89]|1[0-9]|2[0-4])\\b", "$1" + java);
        return text;
    }

    /** Removes each whole statement that starts where the pattern matches, through its balanced brackets. */
    static String removeStatements(String text, Pattern start) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        Matcher m = start.matcher(text);
        while (m.find(at)) {
            int end = m.start(), depth = 0;
            boolean string = false;
            char quote = 0;
            for (; end < text.length(); end++) {
                char c = text.charAt(end);
                if (string) {
                    if (c == '\\') end++;
                    else if (c == quote) string = false;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    string = true;
                    quote = c;
                } else if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') depth--;
                else if (c == '\n' && depth <= 0) break;
            }
            out.append(text, at, m.start());
            at = Math.min(text.length(), end + 1);
        }
        return out.append(text.substring(at)).toString();
    }

    /** Yarn, Fabric loader and Fabric API pins of a Fabric build, each moved to its release for the target game. */
    static void fabricPins(Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        for (String key : List.of("yarn_mappings", "yarn_version")) if (pins.containsKey(key)) {
            // The unobfuscated game has no yarn: the port crosses into Mojang's names and drops the mappings line.
            if (Versions.compare(to, "26.1") >= 0) continue;
            var versions = Json.array(Json.parse(fetch(build, "https://meta.fabricmc.net/v2/versions/yarn/" + to)));
            if (versions.isEmpty()) throw new IOException(
                "No published Yarn mappings for " + to + "; mapping namespace changes need a guided migration."
            );
            String yarn = Json.text(Json.object(versions.get(0)), "version", "");
            // A build that joins the game version to a build number (Cardinal Components: ${minecraft_version}+build.${yarn_mappings},
            // yarn_mappings=1) keeps only the number in the property.
            if (String.valueOf(pins.get(key)).matches("\\d+") && yarn.matches(".*\\+build\\.\\d+")) yarn = yarn.substring(yarn.lastIndexOf('.') + 1);
            setup.replacements.put(key, yarn);
        }
        if (pins.containsKey("loader_version")) {
            var versions = Json.array(Json.parse(fetch(build, "https://meta.fabricmc.net/v2/versions/loader/" + to)));
            String loader = "";
            for (Object item : versions) {
                var value = Json.object(Json.object(item).get("loader"));
                if (Boolean.TRUE.equals(value.get("stable"))) {
                    loader = value.get("version").toString();
                    break;
                }
            }
            if (loader.isEmpty()) throw new IOException("No published stable Fabric loader for " + to);
            setup.replacements.put("loader_version", loader);
        }
        if (pins.containsKey("fabric_version") || pins.containsKey("fabric_api_version")) {
            String xml = fetch(
                build,
                "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
            );
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var nodes = factory
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .getElementsByTagName("version");
            List<String> published = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                String value = nodes.item(i).getTextContent();
                if (value.endsWith("+" + to)) published.add(value);
            }
            if (published.isEmpty()) throw new IOException("No published Fabric API for " + to);
            // "+1.18" builds span 1.18 to 1.18.2 and the newest needs 1.18.2: take the newest whose own fabric.mod.json
            // accepts the target game, read from the jar (a few of the newest are tried; an unreadable one is skipped).
            String api = published.get(published.size() - 1);
            for (int i = published.size() - 1, tried = 0; i >= 0 && tried < 12; i--, tried++) {
                String candidate = published.get(i);
                try {
                    String accepts = Dependencies.minecraftConstraint(
                        download(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" + candidate + "/fabric-api-" + candidate + ".jar")
                    );
                    if (accepts.isEmpty() || Versions.matches(accepts, to)) {
                        api = candidate;
                        break;
                    }
                } catch (Exception unread) {
                    break;
                }
            }
            for (String key : List.of("fabric_version", "fabric_api_version")) if (
                pins.containsKey(key)
            ) setup.replacements.put(key, api);
            try {
                String built = Dependencies.builtWithLoom(
                    download(
                        build,
                        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" +
                        api +
                        "/fabric-api-" +
                        api +
                        ".jar"
                    )
                );
                if (!built.isEmpty()) setup.loomBuilt.put("Fabric API " + api, built);
            } catch (IOException unread) {
                setup.unchecked.add(
                    Json.map(
                        "artifact",
                        "net.fabricmc.fabric-api:fabric-api",
                        "version",
                        api,
                        "reason",
                        "Its jar could not be read for the Loom it needs: " + unread.getMessage()
                    )
                );
            }
        }
    }

    /** NeoForge pins kept beside a Fabric project's (Architectury and MultiLoader builds) move to NeoForge's release for the target. */
    static void neoForgeSibling(Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        for (String key : List.of("neo_version", "neoforge_version", "neoForgeVersion", "neoforgeVersion")) if (pins.containsKey(key)) {
            try {
                String resolved = neoForgeVersion(build, to);
                setup.replacements.put(key, resolved);
                setup.applied.add(
                    Json.map("artifact", "net.neoforged:neoforge", "to", resolved, "evidence", "the build's NeoForge project follows the same game: NeoForge " + resolved + " publishes for " + to)
                );
            } catch (IOException none) {
                setup.unchecked.add(Json.map("artifact", "net.neoforged:neoforge (sibling project)", "version", to, "reason", none.getMessage()));
            }
        }
    }

    /**
     * ModDevGradle follows a forward port to the newest release of its line on maven.neoforged.net. An old release cannot set
     * up a newer game: a mod's moddev 1.0.0 moved to 1.21.4 failed every build on com.mojang:jtracy, a library
     * 1.21.4 added. The line is kept, so the build script's DSL stays the one it was written for; Gradle rises to what the
     * plugin's own module metadata asks. A backport keeps its build tools.
     */
    static void modDevFollows(Setup setup, Build build, String to) throws Exception {
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (
            setup.pins.containsKey(key)
        ) {
            from = setup.pins.get(key);
            break;
        }
        if (!from.isEmpty() && Versions.compare(to, from) <= 0) return;
        Pattern pluginLine = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.moddev[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        Path properties = build.project.root.resolve("gradle.properties");
        List<Path> files = new ArrayList<>();
        Object own = setup.project.get("buildFile");
        if (own != null) files.add(Path.of(own.toString()));
        for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) files.add(build.project.root.resolve(name));
        files.add(properties);
        for (Path file : new LinkedHashSet<>(files)) {
            if (!Files.isRegularFile(file)) continue;
            Matcher pin = file.equals(properties)
                ? Pattern.compile(
                    "(?m)^([ \\t]*(?:moddev_version|moddevgradle_version|mdg_version|moddevVersion)[ \\t]*=[ \\t]*)(\\S+)()[ \\t]*$"
                ).matcher(setup.text(file))
                : pluginLine.matcher(setup.text(file));
            if (!pin.find() || !pin.group(2).matches("\\d+\\.\\d+\\.\\d+(?:-beta)?")) continue;
            // The unobfuscated game (26.1+) needs ModDevGradle 2: 1.x cannot set up its game artifacts (a NeoForge port 1.21.3 -> 26.3).
            String current = pin.group(2), line = current.substring(0, current.indexOf('.'));
            if (Versions.compare(to, "26.1") >= 0 && Integer.parseInt(line) < 2) line = "2";
            // 0.1.x was ModDevGradle's preview line, with 1.0 its stable continuation and the same DSL; 0.1.131 cannot set up
            // 1.21.2's game (TorchMaster: com.mojang:jtracy unresolved), 1.0.x can.
            if (line.equals("0")) line = "1";
            String newest = newestInLine(
                fetch(build, "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml"),
                line
            );
            if (newest == null || Versions.compare(newest, current) <= 0) return;
            setup.replace(file, pin.group(0), pin.group(1) + newest + pin.group(3));
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "ModDevGradle " + current + " -> " + newest + " for " + to,
                    "evidence",
                    "the newest ModDevGradle " + line + ".x on maven.neoforged.net"
                )
            );
            gradleFollows(
                setup,
                build,
                "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/" + newest + "/moddev-gradle-" + newest + ".module",
                "ModDevGradle " + newest,
                "ModDevGradle " + newest
            );
            return;
        }
    }

    /** The newest final release of one major line in a maven-metadata.xml (pre-releases only when the line has nothing else). */
    static String newestInLine(String xml, String major) {
        String release = null, any = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(major) + "\\.[^<]+)</version>").matcher(xml);
        while (version.find()) {
            String v = version.group(1);
            if (any == null || Versions.compare(v, any) > 0) any = v;
            if (!v.matches(".*-(?:beta|alpha|rc|SNAPSHOT).*") && (release == null || Versions.compare(v, release) > 0)) release = v;
        }
        return release != null ? release : any;
    }

    /**
     * A multi-loader build (Architectury, MultiLoader) keeps Forge's version beside Fabric's and NeoForge's, and Gradle
     * configures every project: a Forge project left on the old game stops the whole build (Chat Heads 1.21.4 -> 1.21.5
     * asked for forge 1.21.5-54.0.16, which does not exist). Forge's pin moves to Forge's newest release for the target
     * (maven.minecraftforge.net); when Forge publishes none, an Architectury enabled_platforms list drops forge, so the
     * other loaders still build. The game version ranges the build keeps for each loader's metadata follow the target.
     */
    static void forgeSibling(Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        // port moves Fabric and NeoForge projects, never Forge ones, and a Forge project left in breaks the whole build's
        // configuration once Gradle moves past what ForgeGradle supports (No Chat Reports, Sound Physics, Carry On). So a
        // Forge project included by name leaves the build (undo puts it back); the version pins are retargeted otherwise.
        if (dropForgeProject(setup, build, to, "mmt port moves only the Fabric and NeoForge projects; a Forge project left in stops the build's configuration")) {
            if (pins.containsKey("enabled_platforms") && String.valueOf(pins.get("enabled_platforms")).contains("forge")) setup.replacements.put(
                "enabled_platforms",
                String.join(",", Arrays.stream(String.valueOf(pins.get("enabled_platforms")).split(",")).map(String::trim).filter(p -> !p.equals("forge")).toList())
            );
            return;
        }
        for (String key : List.of("forge_version", "forgeVersion", "minecraft_forge_version")) if (pins.containsKey(key)) {
            String old = String.valueOf(pins.get(key)), release;
            try {
                release = forgeRelease(fetch(build, "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"), to);
            } catch (IOException unreachable) {
                setup.unchecked.add(Json.map("artifact", "net.minecraftforge:forge (sibling project)", "version", to, "reason", unreachable.getMessage()));
                continue;
            }
            if (release != null) {
                setup.replacements.put(key, old.contains("-") ? release : release.substring(release.indexOf('-') + 1));
                setup.applied.add(
                    Json.map("artifact", "net.minecraftforge:forge", "to", release, "evidence", "the build's Forge project follows the same game: Forge " + release + " publishes for " + to)
                );
            } else if (pins.containsKey("enabled_platforms") && String.valueOf(pins.get("enabled_platforms")).contains("forge")) {
                String platforms = String.join(
                    ",",
                    Arrays.stream(String.valueOf(pins.get("enabled_platforms")).split(",")).map(String::trim).filter(p -> !p.equals("forge")).toList()
                );
                setup.replacements.put("enabled_platforms", platforms);
                setup.applied.add(
                    Json.map("change", "enabled_platforms drops forge for " + to, "evidence", "Forge publishes no release for Minecraft " + to + " (maven.minecraftforge.net)")
                );
            } else if (!dropForgeProject(setup, build, to, "Forge publishes no release for Minecraft " + to + " (maven.minecraftforge.net)")) setup.unchecked.add(
                Json.map("artifact", "net.minecraftforge:forge (sibling project)", "version", to, "reason", "Forge publishes no release for Minecraft " + to)
            );
        }
        for (var pin : pins.entrySet()) {
            String key = pin.getKey(), value = String.valueOf(pin.getValue());
            if (key.equals("minecraft_version_range") || setup.replacements.containsKey(key)) continue;
            if (!key.matches("(?i)(?:forge|neoforge|neo|fabric|quilt)_?(?:game|minecraft|mc)_?version_?range")) continue;
            if (!value.matches("[\\[(][^,]*,[^,]*[\\])]") || value.contains(to)) continue;
            setup.replacements.put(key, "[" + to + "]");
        }
    }

    /** Forge's newest release for one game version in its maven-metadata.xml ("1.21.5-55.0.21"), or null. */
    static String forgeRelease(String xml, String to) {
        String newest = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(to) + "-[0-9][^<]*)</version>").matcher(xml);
        while (version.find()) {
            String v = version.group(1);
            if (newest == null || Versions.compare(v.substring(to.length() + 1), newest.substring(to.length() + 1)) > 0) newest = v;
        }
        return newest;
    }

    /**
     * A vanilla-only module (an Architectury or MultiLoader common built on ModDevGradle's neoForm) names a NeoForm release:
     * its full version, or a timestamp the build joins to the Minecraft version (JEI: ${minecraftVersion}-${neoformTimestamp}).
     * It moves to NeoForm's newest release for the target game on maven.neoforged.net; the old one does not exist for it.
     */
    /**
     * NeoForm's newest release for a game version: a timestamp through 1.21.x (1.21.1-20240808.144430), a build number from
     * 26.1 on (26.3-1); snapshots and pre-releases are not releases of that version. Null when there is none.
     */
    static String neoFormRelease(String metadata, String to) {
        String newest = null, key = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(to) + "-(\\d{8}\\.\\d{6}|\\d+))</version>").matcher(metadata);
        while (version.find()) {
            String k = version.group(2).contains(".") ? version.group(2) : String.format("%020d", Long.parseLong(version.group(2)));
            if (key == null || k.compareTo(key) > 0) {
                key = k;
                newest = version.group(1);
            }
        }
        return newest;
    }

    static void neoFormFollows(Setup setup, Build build, String to) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        if (!Files.isRegularFile(properties)) return;
        String text = setup.text(properties);
        Matcher pin = Pattern.compile(
            "(?m)^([ \\t]*((?i:neo_?form_?(?:version|timestamp)))[ \\t]*=[ \\t]*)(\\S+)[ \\t]*$"
        ).matcher(text);
        if (!pin.find()) return;
        // TorchMaster: neo_form_version=1.21-20240613.152323 (a full version names its own game, which may lag the mod's).
        String newest = neoFormRelease(fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"), to);
        if (newest == null) {
            setup.unchecked.add(Json.map("artifact", "net.neoforged:neoform", "version", to, "reason", "NeoForm publishes no release for Minecraft " + to));
            return;
        }
        boolean timestampKey = pin.group(2).toLowerCase(Locale.ROOT).contains("timestamp");
        if (timestampKey && !newest.matches(".+-\\d{8}\\.\\d{6}")) {
            setup.unchecked.add(Json.map("artifact", "net.neoforged:neoform", "version", newest, "reason", "the build keeps a NeoForm timestamp, and NeoForm " + to + " is numbered without one; set the full version"));
            return;
        }
        String value = timestampKey ? newest.substring(newest.indexOf('-') + 1) : newest;
        if (value.equals(pin.group(3))) return;
        setup.replace(properties, pin.group(0), pin.group(1) + value);
        setup.applied.add(Json.map("artifact", "net.neoforged:neoform", "from", pin.group(3), "to", newest, "evidence", "NeoForm's newest release for " + to + " on maven.neoforged.net"));
    }

    /**
     * A parchment { mappingsVersion = project.KEY } block whose property is not one of the conventional names (Ars Nouveau:
     * parchment_version, beside minecraftVersion = project.minecraft_version): the property moves to Parchment's release for
     * the target, a separate Minecraft property to the target, and the block goes when Parchment has no release for it.
     */
    static void parchmentBlockFollows(Setup setup, Build build, String to) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        if (!Files.isRegularFile(properties)) return;
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path script = build.project.root.resolve(name);
            if (!Files.isRegularFile(script)) continue;
            String text = setup.text(script);
            Matcher block = Pattern.compile("(?m)^[ \\t]*parchment\\s*\\{([^{}]*)\\}").matcher(text);
            if (!block.find()) continue;
            Matcher mappings = Pattern.compile("mappingsVersion\\s*(?:=|\\.set\\()\\s*(?:project\\.)?(?:property\\(\\s*[\"'])?(\\w+)").matcher(block.group(1));
            if (!mappings.find()) continue;
            String key = mappings.group(1);
            if (key.equals("parchment_mappings_version") || !Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*=").matcher(setup.text(properties)).find()) continue;
            String release = parchmentRelease(build, to);
            if (release.isEmpty()) {
                setup.originals.putIfAbsent(script, text);
                setup.texts.put(script, removeStatements(text, Pattern.compile("(?m)^[ \\t]*parchment\\s*\\{")));
                setup.applied.add(
                    Json.map(
                        "file",
                        script.toString(),
                        "change",
                        "parchment block removed for " + to,
                        "evidence",
                        "Parchment publishes no parameter names for " + to + " (maven.parchmentmc.org)"
                    )
                );
                continue;
            }
            setProperty(setup, properties, key, release);
            Matcher minecraft = Pattern.compile("minecraftVersion\\s*(?:=|\\.set\\()\\s*(?:project\\.)?(?:property\\(\\s*[\"'])?(\\w+)").matcher(block.group(1));
            if (minecraft.find() && !minecraft.group(1).matches("minecraft_version|minecraftVersion|mc_version")) setProperty(setup, properties, minecraft.group(1), to);
            setup.applied.add(Json.map("change", "Parchment " + key + " -> " + release + " for " + to, "evidence", "Parchment " + release + " is its newest release for " + to));
        }
    }

    /**
     * The unobfuscated game's build tools run on Java 25 (Loom for 26.x refuses a Gradle on 21: Carry On's Fabric project),
     * and Gradle runs on Java 25 from 9.1. A wrapper older than that rises to 9.4.1, which NeoGradle 7.1, ModDevGradle 2
     * and Loom 1.14+ all run on. A wrapper already on 9.1 or newer stays.
     */
    static void gradleForJava25(Setup setup, Build build, String to) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) return;
        String text = setup.text(wrapper);
        Matcher url = Pattern.compile("(distributionUrl=[^\\r\\n]*?gradle-)([0-9][0-9.]*(?:-rc-?\\d+)?)(-(?:bin|all)\\.zip)").matcher(text);
        if (!url.find() || Versions.compare(url.group(2), "9.1") >= 0) return;
        String gradle = "9.4.1";
        setup.replace(wrapper, url.group(0), url.group(1) + gradle + url.group(3));
        Matcher sum = Pattern.compile("(?m)^distributionSha256Sum=\\S+").matcher(setup.text(wrapper));
        if (sum.find()) setup.replace(
            wrapper,
            sum.group(0),
            "distributionSha256Sum=" + fetch(build, "https://services.gradle.org/distributions/gradle-" + gradle + url.group(3) + ".sha256").trim()
        );
        setup.applied.add(
            Json.map(
                "file",
                wrapper.toString(),
                "change",
                "Gradle " + url.group(2) + " -> " + gradle + " for " + to,
                "evidence",
                "Minecraft " + to + " builds on Java 25, which Gradle runs on from 9.1"
            )
        );
    }

    /**
     * Build-script settings the build tools the port moves to no longer have. Gradle 8 removed a jar task's classifier
     * (archiveClassifier replaces it: ModMenu 1.16.5 on Gradle 8 stopped with "unknown property 'classifier'"), and Loom
     * 1.x removed loom.shareCaches(), a cache-sharing switch with no effect on what is built (owo-lib). Only the build's own
     * scripts are changed, and only when the port's Gradle is 8 or newer for the first.
     */
    static void buildDslFollows(Setup setup, Build build) throws Exception {
        buildDslFollows(setup, build, true);
    }

    /** One build script without the settings buildDslFollows names. */
    static String removedDsl(String text, boolean gradle8) {
        return removedDsl(text, gradle8, false);
    }

    /**
     * Also, for Gradle 9: project-level sourceCompatibility and targetCompatibility belong to the java extension
     * (a Fabric port 1.20.1 -> 1.21.1 moves to Gradle 9 with Loom 1.18 and stopped on them).
     */
    static String removedDsl(String text, boolean gradle8, boolean gradle9) {
        if (gradle9) {
            text = text.replaceAll("(?m)^(sourceCompatibility|targetCompatibility)([ \\t]*=)", "java.$1$2");
            // Gradle 9 removed the project-level archivesBaseName; the base extension owns it.
            text = text.replaceAll("(?m)^([ \\t]*)archivesBaseName([ \\t]*=)", "$1base.archivesName$2");
            text = text.replaceAll("(?<![\\w.$])(?:project\\.)?archivesBaseName(?![\\w(])", "project.base.archivesName.get()");
        }
        if (gradle8) {
            text = text.replaceAll("(?m)^([ \\t]*)classifier(\\s*=\\s*|\\s+)([\"'])", "$1archiveClassifier = $3");
            // Gradle 8 removed the archive task's file properties for their Provider forms (FerriteCore: jar.archivePath).
            text = text.replaceAll("\\.archivePath(?![\\w$])", ".archiveFile.get().asFile");
            text = text.replaceAll("\\.archiveName(?![\\w$(])", ".archiveFileName.get()");
            text = text.replaceAll("\\.destinationDir(?![\\w$])", ".destinationDirectory.get().asFile");
        }
        return text.replaceAll("(?m)^[ \\t]*shareCaches\\(\\)[ \\t]*\\r?\\n", "");
    }

    private static void buildDslFollows(Setup setup, Build build, boolean unused) throws Exception {
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        Matcher gradle = Pattern.compile("gradle-([0-9]+)\\.").matcher(Files.isRegularFile(wrapper) ? setup.text(wrapper) : "");
        int major = gradle.find() ? Integer.parseInt(gradle.group(1)) : 0;
        boolean gradle8 = major >= 8, gradle9 = major >= 9;
        for (Path script : build.project.inputs()) {
            // build.gradle and the scripts it applies (ModMenu's gradle/*.gradle via apply from:), not settings.
            String file = script.getFileName().toString();
            if (!file.matches(".*\\.gradle(\\.kts)?") || file.startsWith("settings.") || file.startsWith("gradle.init")) continue;
            String before;
            try {
                before = setup.text(script);
            } catch (java.nio.charset.CharacterCodingException notText) {
                continue; // a script in another encoding (owo-lib has one); nothing to rewrite in it
            }
            String text = removedDsl(before, gradle8, gradle9);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(script, before);
            setup.texts.put(script, text);
            setup.applied.add(Json.map("file", script.toString(), "change", "build-script settings the new Gradle and Loom removed (classifier -> archiveClassifier, shareCaches())"));
        }
    }

    /**
     * A multi-loader build that includes its Forge project by name in settings.gradle (ImmediatelyFast: include(":forge"))
     * cannot configure it for a game Forge publishes nothing for, and Gradle configures every included project. The Forge
     * project leaves the include list, so the other loaders build; port does not port Forge projects in any case.
     */
    static boolean dropForgeProject(Setup setup, Build build, String to, String evidence) throws IOException {
        boolean dropped = false;
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = build.project.root.resolve(name);
            if (!Files.isRegularFile(settings)) continue;
            String before = setup.text(settings), text = withoutForgeInclude(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(settings, before);
            setup.texts.put(settings, text);
            setup.applied.add(
                Json.map(
                    "file",
                    settings.toString(),
                    "change",
                    "Forge project left out of the build for " + to,
                    "evidence",
                    evidence
                )
            );
            dropped = true;
        }
        return dropped;
    }

    /** A settings script without its Forge project: a one-name include line goes, a name in a longer list leaves it. */
    static String withoutForgeInclude(String text) {
        String forge = "[\"']:?[Ff]orge[\"']";
        text = text.replaceAll("(?m)^[ \\t]*include\\s*\\(?\\s*" + forge + "\\s*\\)?[ \\t]*(?:\\r?\\n|\\z)", "");
        text = text.replaceAll("(?m)^([ \\t]*include\\b[^\\n]*?),\\s*" + forge, "$1");
        text = text.replaceAll("(?m)^([ \\t]*include\\s*\\(?\\s*)" + forge + "\\s*,\\s*", "$1");
        // A line that configures the Forge project by path (No Chat Reports: project(":forge").name = "Forge") goes with it.
        return text.replaceAll("(?m)^[ \\t]*project\\s*\\(\\s*" + forge + "\\s*\\)[^\\n]*(?:\\r?\\n|\\z)", "");
    }

    /**
     * Architectury Loom (dev.architectury.loom, Fabric Loom's fork for Architectury builds) follows the same Loom line the
     * target's jars were built with: BetterF3 1.20.4 -> 1.20.5 kept 1.5-SNAPSHOT while Fabric API was built with 1.7, and
     * the build would not load. The plugin's version moves to that line's newest release on maven.architectury.dev, and
     * Gradle to what its own bootstrap class asks. Returns the new version, or null when the build applies no such plugin.
     */
    /** The Architectury plugin's newest version line on maven.architectury.dev (its marker's "latest", e.g. 3.5-SNAPSHOT). */
    static String newestArchitecturyPlugin(Build build) throws Exception {
        Matcher latest = Pattern.compile("<latest>([^<]+)</latest>").matcher(
            fetch(build, "https://maven.architectury.dev/architectury-plugin/architectury-plugin.gradle.plugin/maven-metadata.xml")
        );
        if (!latest.find()) throw new IOException("No published Architectury plugin");
        return latest.group(1);
    }

    /** Architectury Loom's newest release on maven.architectury.dev. */
    static String newestArchitecturyLoom(Build build) throws Exception {
        Matcher version = Pattern.compile("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>").matcher(
            fetch(build, "https://maven.architectury.dev/dev/architectury/architectury-loom/maven-metadata.xml")
        );
        String newest = null;
        while (version.find()) if (newest == null || Versions.compare(version.group(1), newest) > 0) newest = version.group(1);
        if (newest == null) throw new IOException("No published Architectury Loom release");
        return newest;
    }

    static String architecturyLoomFollows(Setup setup, Build build, String line) throws Exception {
        Pattern plugin = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']dev\\.architectury\\.loom[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        List<Path> scripts = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) scripts.add(build.project.root.resolve(name));
        for (Path script : scripts) {
            if (!Files.isRegularFile(script)) continue;
            Matcher found = plugin.matcher(setup.text(script));
            if (!found.find()) continue;
            String current = found.group(2);
            if (Versions.compare(Dependencies.minor(current.replace("-SNAPSHOT", "")), line) >= 0) return null;
            String newest = null;
            Matcher version = Pattern.compile("<version>(" + Pattern.quote(line) + "\\.[0-9]+)</version>").matcher(
                fetch(build, "https://maven.architectury.dev/dev/architectury/architectury-loom/maven-metadata.xml")
            );
            while (version.find()) if (newest == null || Versions.compare(version.group(1), newest) > 0) newest = version.group(1);
            if (newest == null) return null;
            setup.replace(script, found.group(0), found.group(1) + newest + found.group(3));
            gradleFollows(
                setup,
                build,
                "https://maven.architectury.dev/dev/architectury/architectury-loom/" + newest + "/architectury-loom-" + newest + ".module",
                "Architectury Loom " + newest,
                "architectury-loom " + newest
            );
            return newest;
        }
        return null;
    }

    /** Parchment's newest release of parameter names for exactly this Minecraft version, or "" when it publishes none. */
    static String parchmentRelease(Build build, String to) throws Exception {
        String xml;
        try {
            xml = fetch(
                build,
                "https://maven.parchmentmc.org/org/parchmentmc/data/parchment-" + to + "/maven-metadata.xml"
            );
        } catch (LookupFailure couldNotAsk) {
            throw couldNotAsk;
        } catch (IOException answered) {
            if (answered.getMessage() != null && answered.getMessage().contains("(HTTP 404)")) return "";
            throw answered;
        }
        Matcher release = Pattern.compile("<release>([^<]+)</release>").matcher(xml);
        if (release.find()) return release.group(1);
        String last = "";
        Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (version.find()) if (!version.group(1).contains("SNAPSHOT")) last = version.group(1);
        return last;
    }

    /**
     * Parchment follows the target. Parchment names a game's parameters by method, for one Minecraft version; applied to
     * another version its names can collide with the real ones. Seen on 2026-11-08: 1.21.5 names on the 26.3 game turned
     * BundlerInfo's "packet == delimiterPacket" into "packet == packet", so every packet started a bundle and a
     * singleplayer world never loaded. So the names move to Parchment's release for exactly the target version, and when
     * Parchment has none (26.x ships its own names) the setting is removed: without it only decompiled names are plainer.
     * Covers NeoGradle's neogradle.subsystems.parchment.* properties, ModDevGradle's parchment_* properties and their
     * parchment { } block, and Loom's layered parchment("org.parchmentmc.data:parchment-VERSION:...") coordinate.
     */
    static void parchmentFollows(Setup setup, Build build, String to) throws Exception {
        parchmentEdit(setup, build, to, null);
        parchmentBlockFollows(setup, build, to);
    }

    /** parchmentFollows with Parchment's release for the target given ("" for none), or null to look it up only if the build states Parchment. */
    static boolean parchmentEdit(Setup setup, Build build, String to, String known) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        String props = Files.isRegularFile(properties) ? setup.text(properties) : "";
        Matcher neoGradle = Pattern.compile(
            "(?m)^[ \\t]*neogradle\\.subsystems\\.parchment\\.minecraftVersion[ \\t]*=[ \\t]*(\\S+)"
        ).matcher(props);
        Matcher modDev = Pattern.compile("(?m)^[ \\t]*parchment_minecraft_version[ \\t]*=[ \\t]*(\\S+)").matcher(props);
        List<Path> scripts = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts")) if (
            Files.isRegularFile(build.project.root.resolve(name))
        ) scripts.add(build.project.root.resolve(name));
        // A multi-module build keeps its parchment block in a module's script (Repurposed Structures' :common).
        for (Path input : build.project.inputs()) if (input.getFileName().toString().matches("build\\.gradle(\\.kts)?") && !scripts.contains(input)) scripts.add(input);
        Pattern layered = Pattern.compile(
            "parchment\\(\\s*[\"']org\\.parchmentmc\\.data:parchment-([^:\"']+):([^@\"']+)@zip[\"']\\s*\\)"
        );
        Path loomScript = null;
        Matcher loom = null;
        for (Path script : scripts) {
            String scriptText = setup.text(script);
            Matcher found = layered.matcher(scriptText);
            boolean active = false;
            // A commented-out layer (FerriteCore: //parchment(...)) is not in the build.
            while (!active && found.find()) active = !scriptText.substring(scriptText.lastIndexOf('\n', found.start()) + 1, found.start()).trim().startsWith("//");
            if (active) {
                loomScript = script;
                loom = found;
                break;
            }
        }
        boolean hasNeoGradle = neoGradle.find(), hasModDev = modDev.find();
        if (!hasNeoGradle && !hasModDev && loom == null) return false;
        String stated = hasNeoGradle ? neoGradle.group(1) : hasModDev ? modDev.group(1) : loom.group(1);
        String release = known != null ? known : parchmentRelease(build, to);
        String evidence = release.isEmpty()
            ? "Parchment publishes no parameter names for " + to + " (maven.parchmentmc.org)"
            : "Parchment " + release + " is its newest release for " + to;
        if (hasNeoGradle) {
            if (release.isEmpty()) removeLines(
                setup,
                properties,
                "neogradle\\.subsystems\\.parchment\\.[\\w.]+[ \\t]*=.*"
            );
            else {
                setProperty(setup, properties, "neogradle.subsystems.parchment.minecraftVersion", to);
                setProperty(setup, properties, "neogradle.subsystems.parchment.mappingsVersion", release);
            }
        }
        if (hasModDev) {
            if (release.isEmpty()) {
                boolean removed = false;
                for (Path script : scripts) {
                    String text = setup.text(script);
                    Matcher block = Pattern.compile(
                        "(?m)^[ \\t]*parchment\\s*\\{[^{}]*parchment_(?:minecraft|mappings)_version[^{}]*\\}[ \\t]*\\r?\\n"
                    ).matcher(text);
                    if (block.find()) {
                        setup.replace(script, block.group(), "");
                        removed = true;
                    }
                }
                if (!removed) throw new IOException(
                    "gradle.properties sets parchment_minecraft_version=" +
                    stated +
                    ", Parchment publishes no names for " +
                    to +
                    ", and no parchment { } block reading it was found to remove. Remove the Parchment setting from the build, then rerun port."
                );
                removeLines(setup, properties, "parchment_(?:minecraft|mappings)_version[ \\t]*=.*");
            } else {
                setProperty(setup, properties, "parchment_minecraft_version", to);
                setProperty(setup, properties, "parchment_mappings_version", release);
            }
        }
        if (loom != null) {
            String text = setup.text(loomScript);
            Matcher line = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(loom.group()) + "[ \\t]*\\r?\\n").matcher(
                text
            );
            if (release.isEmpty() && line.find()) setup.replace(loomScript, line.group(), "");
            else if (release.isEmpty()) throw new IOException(
                loomScript.getFileName() +
                " layers Parchment " +
                loom.group(1) +
                " into its mappings and Parchment publishes no names for " +
                to +
                "; remove that parchment(...) layer, then rerun port."
            );
            else setup.replace(
                loomScript,
                loom.group(),
                "parchment(\"org.parchmentmc.data:parchment-" + to + ":" + release + "@zip\")"
            );
        }
        setup.applied.add(
            Json.map(
                "change",
                release.isEmpty()
                    ? "Parchment " + stated + " parameter names removed for " + to
                    : "Parchment " + stated + " -> " + to + " " + release,
                "evidence",
                evidence
            )
        );
        return true;
    }

    private static void setProperty(Setup setup, Path properties, String key, String value) throws IOException {
        // The whole value: a range such as ">=1.21.4 <1.21.5" is one property (Trash Cans' minecraft_dependency).
        Matcher line = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)([^\\r\\n]*?)[ \\t]*$").matcher(
            setup.text(properties)
        );
        if (!line.find()) throw new IOException(
            properties.getFileName() + " has no " + key + " to set to " + value + "; set it by hand, then rerun port."
        );
        if (!line.group(2).equals(value)) setup.replace(properties, line.group(), line.group(1) + value);
    }

    private static void removeLines(Setup setup, Path properties, String line) throws IOException {
        Matcher found = Pattern.compile("(?m)^[ \\t]*" + line + "(?:\\r?\\n|$)").matcher(setup.text(properties));
        List<String> lines = new ArrayList<>();
        while (found.find()) lines.add(found.group());
        for (String each : lines) setup.replace(properties, each, "");
    }

    /**
     * Loom follows the target. A Fabric mod jar built by a newer Loom stops an older Loom while the build
     * loads ("Mod was built with a newer version of Loom"), so the project's Loom rises to the newest line its
     * target jars (Fabric API and every resolved mod) were built with, at that line's newest release on
     * maven.fabricmc.net. Gradle then rises to the version that Loom's own module metadata requires
     * (org.gradle.plugin.api-version). Neither is ever lowered, so a backport keeps its build tools.
     */
    static void loomFollows(Setup setup, Build build, String to) throws Exception {
        String current = build.project.loomVersion(), need = "", needBy = "";
        for (var built : setup.loomBuilt.entrySet()) if (
            need.isEmpty() || Versions.compare(Dependencies.minor(built.getValue()), Dependencies.minor(need)) > 0
        ) {
            need = built.getValue();
            needBy = built.getKey();
        }
        if (
            need.isEmpty() ||
            current.isEmpty() ||
            Versions.compare(Dependencies.minor(need), Dependencies.minor(current)) <= 0
        ) {
            if (current.isEmpty()) for (var built : setup.loomBuilt.entrySet()) {
                String warning = Dependencies.loomWarning(
                    Json.map("version_number", built.getKey()),
                    built.getValue(),
                    "(not stated)"
                );
                if (warning != null) System.err.println("Warning: " + warning);
            }
            return;
        }
        String line = Dependencies.minor(need), loom = newestLoom(build, line);
        if (!loomEdit(setup, build, current, loom)) {
            String architectury = architecturyLoomFollows(setup, build, line);
            if (architectury != null) {
                setup.applied.add(Json.map("change", "Architectury Loom " + current + " -> " + architectury + " for " + to, "evidence", needBy + " was built with Loom " + need));
                return;
            }
            System.err.println(
                "Warning: " + Dependencies.loomWarning(Json.map("version_number", needBy), need, current)
            );
            return;
        }
        setup.applied.add(
            Json.map(
                "change",
                "Loom " + current + " -> " + loom + " for " + to,
                "evidence",
                needBy + " was built with Loom " + need
            )
        );
        loomInRoot(setup, build, loom);
        gradleFollows(
            setup,
            build,
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/" + loom + "/fabric-loom-" + loom + ".module",
            "Loom " + loom,
            "fabric-loom " + loom
        );
    }

    /**
     * Gradle rises to the version a build plugin's own module metadata requires (org.gradle.plugin.api-version), and is
     * never lowered: an older Gradle refuses the plugin while the build loads, before any source is compiled.
     */
    static void gradleFollows(Setup setup, Build build, String moduleUrl, String plugin, String evidence)
        throws Exception {
        String module;
        try {
            module = fetch(build, moduleUrl);
        } catch (LookupFailure unreachable) {
            throw unreachable;
        } catch (IOException none) {
            module = ""; // Architectury Loom publishes no Gradle module metadata
        }
        Matcher api = Pattern.compile("\"org\\.gradle\\.plugin\\.api-version\"\\s*:\\s*\"([^\"]+)\"").matcher(module);
        String required = api.find() ? api.group(1) : null, source = " (org.gradle.plugin.api-version)";
        // Loom before 1.x (and Architectury Loom, its fork) publishes no api-version; its own bootstrap class states the Gradle it needs.
        if (required == null && (moduleUrl.contains("/fabric-loom/") || moduleUrl.contains("/architectury-loom/"))) {
            required = loomMinimumGradle(build, download(build, moduleUrl.replaceFirst("\\.module$", ".jar")));
            source = " or newer (its LoomGradlePluginBootstrap states the minimum; the newest release of that line is used)";
        }
        if (required == null) return;
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) return;
        String text = setup.text(wrapper);
        Matcher url = Pattern.compile(
            "(distributionUrl=[^\\r\\n]*?gradle-)([0-9][0-9.]*(?:-rc-?\\d+)?)(-(?:bin|all)\\.zip)"
        ).matcher(text);
        if (!url.find() || Versions.compare(url.group(2), required) >= 0) return;
        String gradle = required;
        setup.replace(wrapper, url.group(0), url.group(1) + gradle + url.group(3));
        Matcher sum = Pattern.compile("(?m)^distributionSha256Sum=\\S+").matcher(setup.text(wrapper));
        if (sum.find()) setup.replace(
            wrapper,
            sum.group(0),
            "distributionSha256Sum=" +
            fetch(build, "https://services.gradle.org/distributions/gradle-" + gradle + url.group(3) + ".sha256").trim()
        );
        setup.applied.add(
            Json.map(
                "file",
                wrapper.toString(),
                "change",
                "Gradle " + url.group(2) + " -> " + gradle + " for " + plugin,
                "evidence",
                evidence + " requires Gradle " + gradle + source
            )
        );
    }

    /**
     * NeoGradle follows the target on a forward port. NeoForge 21.6's userdev runs on ASM 9.8 while NeoGradle 7.0.145
     * still put ASM 9.5 on the client's class path, so Notes 1.21 -> 1.21.6 compiled and then stopped at launch
     * ("Module named org.objectweb.asm.tree.analysis was already on the JVMs module path"). The plugin rises to the
     * newest NeoGradle 7.x (the 7.1 line, which 26.1+ needs anyway), then Gradle to what that release requires. One
     * NeoGradle for every port is also what lets a second mod reuse the first one's game setup: NeoGradle's own
     * cross-project cache (~/.gradle/caches/ng_execute) keys each NeoForm step by its task class, input properties and
     * input file contents, so two NeoGradle versions decompile the same game twice. A backport keeps its build tools.
     */
    static void neoGradleFollows(Setup setup, Build build, String to) throws Exception {
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (
            setup.pins.containsKey(key)
        ) {
            from = setup.pins.get(key);
            break;
        }
        if (!from.isEmpty() && Versions.compare(to, from) <= 0) return;
        Pattern pluginLine = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.userdev[\"']\\s*\\)?\\s*version\\s*[\"'])([^\"']+)([\"'])"
        );
        Path properties = build.project.root.resolve("gradle.properties");
        for (Path file : List.of(
            build.project.root.resolve("build.gradle"),
            build.project.root.resolve("build.gradle.kts"),
            build.project.root.resolve("settings.gradle"),
            build.project.root.resolve("settings.gradle.kts"),
            properties
        )) {
            if (!Files.isRegularFile(file)) continue;
            Matcher pin = file.equals(properties)
                ? Pattern.compile(
                    "(?m)^([ \\t]*(?:neogradle_version|neoGradleVersion|neogradleVersion)[ \\t]*=[ \\t]*)(\\S+)()[ \\t]*$"
                ).matcher(setup.text(file))
                : pluginLine.matcher(setup.text(file));
            if (!pin.find() || !pin.group(2).matches("\\d+\\.\\d+\\.\\d+")) continue;
            String current = pin.group(2), newest = latestNeoGradle(build, "7");
            if (Versions.compare(newest, current) <= 0) return;
            setup.replace(file, pin.group(0), pin.group(1) + newest + pin.group(3));
            // NeoGradle's other plugins (mixin, vanilla, ...) are published with userdev and only work at its version:
            // a mixin plugin left on 7.0.145 beside userdev 7.1 failed to apply (net/minecraftforge/gdi/BaseDSLElement).
            if (!file.equals(properties) && Versions.compare(newest, "7.1") >= 0) dropMixinPlugin(setup, build, file);
            if (!file.equals(properties)) {
                Matcher sibling = Pattern.compile(
                    "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.(?!userdev[\"'])[\\w.]+[\"']\\s*\\)?\\s*version\\s*[\"'])" +
                    Pattern.quote(current) +
                    "([\"'])"
                ).matcher(setup.text(file));
                List<String> siblings = new ArrayList<>();
                while (sibling.find()) siblings.add(sibling.group(0));
                for (String line : siblings) setup.replace(file, line, line.replace(current, newest));
            }
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "NeoGradle " + current + " -> " + newest + " for " + to,
                    "evidence",
                    "the newest NeoGradle 7.x on maven.neoforged.net"
                )
            );
            gradleFollows(
                setup,
                build,
                "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/" +
                newest +
                "/userdev-" +
                newest +
                ".module",
                "NeoGradle " + newest,
                "NeoGradle " + newest
            );
            return;
        }
    }

    /**
     * NeoGradle 7.1 has no mixin plugin (net.neoforged.gradle.mixin ends at 7.0.192): FML reads a mod's mixin configs from
     * its neoforge.mods.toml [[mixins]] entries, which is all the plugin's mixin { config ... } block was for. So the
     * plugin line and its block go, and every config the block named is declared in the mods.toml if it is not already.
     */
    static void dropMixinPlugin(Setup setup, Build build, Path file) throws IOException {
        String text = setup.text(file);
        Matcher plugin = Pattern.compile(
            "(?m)^[ \\t]*id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.mixin[\"']\\s*\\)?(?:\\s*version\\s*[\"'][^\"']*[\"'])?[ \\t]*\\r?\\n"
        ).matcher(text);
        if (!plugin.find()) return;
        Matcher block = Pattern.compile("(?m)^mixin\\s*\\{").matcher(text);
        List<String> configs = new ArrayList<>();
        String out = text.substring(0, plugin.start()) + text.substring(plugin.end());
        if (block.find()) {
            int depth = 0, end = -1;
            for (int i = block.end() - 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    end = i + 1;
                    break;
                }
            }
            if (end < 0) return;
            String body = text.substring(block.start(), end);
            Matcher config = Pattern.compile("(?m)^\\s*config\\s*\\(?\\s*[\"']([^\"']+)[\"']").matcher(body);
            while (config.find()) configs.add(config.group(1));
            String withoutPlugin = out;
            int at = withoutPlugin.indexOf(body);
            out = withoutPlugin.substring(0, at) + withoutPlugin.substring(at + body.length()).replaceFirst("^\\r?\\n", "");
        }
        Path toml = null;
        // NeoForge before 20.5 read META-INF/mods.toml (a 1.20.4 mod); later releases neoforge.mods.toml.
        for (String name : List.of("mods.toml", "neoforge.mods.toml")) for (String dir : List.of("src/main/resources/META-INF/", "src/main/templates/META-INF/")) {
            Path candidate = build.project.root.resolve(dir + name);
            if (Files.isRegularFile(candidate)) toml = candidate;
        }
        if (toml == null && !configs.isEmpty()) return;
        if (toml != null) {
            String metadata = setup.text(toml), added = metadata;
            for (String config : configs) {
                String literal = config.replace("${mod_id}", Objects.requireNonNullElse(modId(build.project.root), "${mod_id}"));
                if (added.contains("\"" + config + "\"") || added.contains("\"" + literal + "\"")) continue;
                added = added + (added.endsWith("\n") ? "" : "\n") + "\n[[mixins]]\nconfig = \"" + config + "\"\n";
            }
            if (!added.equals(metadata)) {
                setup.originals.putIfAbsent(toml, metadata);
                setup.texts.put(toml, added);
            }
        }
        setup.originals.putIfAbsent(file, text);
        setup.texts.put(file, out);
        setup.applied.add(
            Json.map(
                "file",
                file.toString(),
                "change",
                "NeoGradle's mixin plugin removed (NeoGradle 7.1 has none); its configs " + configs + " are mods.toml [[mixins]]",
                "evidence",
                "net.neoforged.gradle.mixin's newest release on maven.neoforged.net is 7.0.192"
            )
        );
    }

    /**
     * The Gradle a Loom jar says it needs, from its bootstrap class's constants: MIN_SUPPORTED_GRADLE_VERSION ("8.1") as
     * stated, or MIN_SUPPORTED_MAJOR_GRADLE_VERSION (7) as that major line's newest release on services.gradle.org.
     */
    static String loomMinimumGradle(Build build, Path jar) throws Exception {
        Map<String, Object> constants;
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("net/fabricmc/loom/bootstrap/LoomGradlePluginBootstrap.class");
            if (entry == null) return null;
            constants = classConstants(zip.getInputStream(entry).readAllBytes());
        }
        if (constants.get("MIN_SUPPORTED_GRADLE_VERSION") instanceof String version) return version;
        if (!(constants.get("MIN_SUPPORTED_MAJOR_GRADLE_VERSION") instanceof Integer major)) return null;
        return newestGradle(fetch(build, "https://services.gradle.org/versions/all"), major);
    }

    /** The newest final release of one Gradle major line in services.gradle.org/versions/all. */
    static String newestGradle(String versionsJson, int major) {
        String newest = null;
        Matcher release = Pattern.compile("\"version\"\\s*:\\s*\"(" + major + "\\.[0-9]+(?:\\.[0-9]+)?)\"").matcher(versionsJson);
        while (release.find()) if (newest == null || Versions.compare(release.group(1), newest) > 0) newest = release.group(1);
        return newest;
    }

    /** The compile-time constants of a class file's fields (int and String ConstantValue attributes), by field name. */
    static Map<String, Object> classConstants(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[] strings = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> in.readFloat();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 8 -> strings[i] = in.readUnsignedShort();
                case 7, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
        Map<String, Object> constants = new LinkedHashMap<>();
        int fields = in.readUnsignedShort();
        for (int f = 0; f < fields; f++) {
            in.readUnsignedShort();
            String name = String.valueOf(pool[in.readUnsignedShort()]);
            in.readUnsignedShort();
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = String.valueOf(pool[in.readUnsignedShort()]);
                int length = in.readInt();
                if (attribute.equals("ConstantValue") && length == 2) {
                    int index = in.readUnsignedShort();
                    constants.put(name, strings[index] != 0 ? pool[strings[index]] : pool[index]);
                } else in.skipNBytes(length);
            }
        }
        return constants;
    }

    /** The newest release of one Loom line (e.g. 1.10 -> 1.10.5), read from maven.fabricmc.net. */
    static String newestLoom(Build build, String line) throws Exception {
        String xml = fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getElementsByTagName("version");
        String chosen = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(line + ".") || !value.matches("[0-9.]+")) continue;
            if (chosen.isEmpty() || Versions.compare(value, chosen) > 0) chosen = value;
        }
        if (chosen.isEmpty()) throw new IOException("No published Fabric Loom release in the " + line + " line");
        return chosen;
    }

    /** Writes the new Loom version where the build states it (the places ProjectModel.loomVersion reads); false when it cannot. */
    static boolean loomEdit(Setup setup, Build build, String current, String loom) throws IOException {
        Path properties = build.project.root.resolve("gradle.properties");
        List<String> keys = new ArrayList<>(List.of("loom_version", "loomVersion", "fabric_loom_version"));
        Pattern plugin = Pattern.compile(
            "(id\\s*\\(?\\s*[\"'](?:fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.matches("(?:build|settings)\\.gradle(?:\\.kts)?")) continue;
            Matcher found = plugin.matcher(setup.text(file));
            if (!found.find()) continue;
            Matcher property = Pattern.compile("^\\$\\{?(?:project\\.)?([A-Za-z_][A-Za-z0-9_]*)\\}?$").matcher(
                found.group(2)
            );
            if (property.find()) {
                keys.add(0, property.group(1));
                break;
            }
            if (!found.group(2).strip().equals(current)) continue;
            setup.replace(file, found.group(0), found.group(1) + loom + found.group(3));
            return true;
        }
        if (!Files.isRegularFile(properties)) return false;
        for (String key : keys) {
            Matcher pin = Pattern.compile(
                "(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)(\\S+)[ \\t]*$"
            ).matcher(setup.text(properties));
            if (pin.find() && pin.group(2).equals(current)) {
                setup.replace(properties, pin.group(0), pin.group(1) + loom);
                return true;
            }
        }
        return false;
    }

    /**
     * NeoForge 1.21.4 split the `data` run type into clientData/serverData. A build that still
     * declares `data { ... }` fails during configuration, so rename the run with the version pins.
     */
    private static void runTypeRename(Setup setup, Build build, String to) throws IOException {
        if (Versions.compare(to, "1.21.4") < 0) return;
        Pattern block = Pattern.compile("(?m)^([\\t ]*)data([\\t ]*)\\{");
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".gradle") && !name.endsWith(".gradle.kts")) continue;
            String text = setup.text(file);
            Matcher match = block.matcher(text);
            if (!match.find()) continue;
            String updated =
                text.substring(0, match.start()) +
                match.group(1) +
                "clientData" +
                match.group(2) +
                "{" +
                text.substring(match.end());
            setup.replace(file, text, updated);
            setup.applied.add(
                Json.map("file", file.toString(), "change", "data run type renamed to clientData for NeoForge 1.21.4+")
            );
        }
    }

    private static void applyReplacements(Setup setup, Build build, Map<String, Object> pins) throws IOException {
        for (var pin : setup.replacements.entrySet()) {
            List<Path> owners = new ArrayList<>();
            for (Path file : build.project.inputs()) if (
                file.getFileName().toString().equals("gradle.properties") &&
                Objects.equals(ProjectModel.properties(file).getProperty(pin.getKey()), setup.pins.get(pin.getKey()))
            ) owners.add(file);
            if (owners.size() > 1) {
                // Stonecutter-style repos keep one property file per node; the selected node owns its own copy.
                Path directory = Path.of(Json.text(setup.project, "directory", "")).toAbsolutePath().normalize();
                List<Path> own = owners
                    .stream()
                    .filter(p -> p.toAbsolutePath().normalize().startsWith(directory))
                    .toList();
                if (own.size() == 1) owners = own;
            }
            if (owners.size() != 1) throw new IOException(
                "Cannot choose an unambiguous gradle.properties owner for " + pin.getKey() + ": " + owners
            );
            Path owner = owners.get(0);
            String text = setup.text(owner);
            Matcher match = Pattern.compile(
                "(?m)^([ \\t]*" + Pattern.quote(pin.getKey()) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*"
            ).matcher(text);
            if (!match.find()) throw new IOException("Cannot find property " + pin.getKey() + " in " + owner);
            String prefix = match.group(1);
            int start = match.start(), end = match.end();
            if (match.find()) throw new IOException("Duplicate property " + pin.getKey() + " in " + owner);
            setup.replace(owner, text, text.substring(0, start) + prefix + pin.getValue() + text.substring(end));
        }
    }

    /**
     * The mod's own JUnit tests keep running on NeoForge 26.x. Since 1.21.11 the game's Commands class builds permission
     * codecs from the built-in registries when it loads, so a plain test that registers a command tree stops with "Not
     * bootstrapped"; and on NeoForge 26.x the game can only be bootstrapped inside FancyModLoader (SharedConstants asks
     * FML whether it runs in production). NeoForge's userdev ships a junit run type for exactly this: the test task runs
     * on junit-fml, which starts FML, puts the mod and its tests in FML's class loader (MOD_CLASSES) and bootstraps the
     * game. A NeoForge mod ported 1.21.1 -> 26.3: 7 of 128 tests failed plain, all 128 pass this way. NeoGradle
     * projects only (ModDevGradle has its own unitTest block), forward ports onto 26.1+, and only when there are tests.
     */
    static void unitTestsInFml(Setup setup, Build build, String to, String neoForge) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "mc_version")) if (setup.pins.containsKey(key)) {
            from = setup.pins.get(key);
            break;
        }
        if (from.isEmpty() || Versions.compare(from, "26.1") >= 0) return;
        Path file = build.project.root.resolve("build.gradle");
        if (!Files.isRegularFile(file)) return;
        String text = setup.text(file);
        if (
            !text.contains("net.neoforged.gradle.userdev") ||
            text.contains("junit-fml") ||
            text.contains("unitTestSource") ||
            !hasJUnitTests(build.project.root.resolve("src/test/java"))
        ) return;
        String modId = modId(build.project.root);
        if (modId == null) return;
        Matcher loader = Pattern.compile(
            "<artifactId>loader</artifactId>\\s*<version>([^<]+)</version>"
        ).matcher(
            fetch(
                build,
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoForge + "/neoforge-" + neoForge + ".pom"
            )
        );
        if (!loader.find()) return;
        String block =
            "\n// Written by mmt port: on NeoForge 26.x the game can only be bootstrapped inside FancyModLoader, so the\n" +
            "// tests run on junit-fml, with the mod and its tests in FML's class loader.\n" +
            "runs {\n    junit {\n        junit true\n        modSource sourceSets.main\n        unitTestSource sourceSets.test\n    }\n}\n\n" +
            "dependencies {\n    testRuntimeOnly 'net.neoforged.fancymodloader:junit-fml:" +
            loader.group(1) +
            "'\n}\n\n" +
            "tasks.named('test', Test).configure {\n" +
            "    jvmArgs '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED', '--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow'\n" +
            "    def roots = sourceSets.main.output.classesDirs.files + [sourceSets.main.output.resourcesDir] + sourceSets.test.output.classesDirs.files\n" +
            "    environment 'MOD_CLASSES', roots.collect { \"" +
            modId +
            "%%${it}\" }.join(File.pathSeparator)\n}\n";
        setup.originals.putIfAbsent(file, text);
        setup.texts.put(file, text + block);
        setup.applied.add(
            Json.map(
                "file",
                file.toString(),
                "change",
                "tests run on junit-fml " + loader.group(1) + " (FancyModLoader) for " + to,
                "evidence",
                "NeoForge " + neoForge + "'s published pom names FancyModLoader " + loader.group(1)
            )
        );
    }

    static boolean hasJUnitTests(Path tests) throws IOException {
        if (!Files.isDirectory(tests)) return false;
        try (var walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) if (
                Files.readString(file).contains("org.junit")
            ) return true;
        }
        return false;
    }

    /** The first modId in the mod's neoforge.mods.toml (or mods.toml), literal or from a ${mod_id}-style property. */
    static String modId(Path root) throws IOException {
        for (String name : List.of("neoforge.mods.toml", "mods.toml")) for (String dir : List.of(
            "src/main/resources/META-INF/",
            "src/main/templates/META-INF/"
        )) {
            Path toml = root.resolve(dir + name);
            if (!Files.isRegularFile(toml)) continue;
            Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(toml));
            if (!id.find()) continue;
            String value = id.group(1);
            Matcher property = Pattern.compile("\\$\\{(\\w+)}").matcher(value);
            if (!property.matches()) return value;
            Path props = root.resolve("gradle.properties");
            if (!Files.isRegularFile(props)) return null;
            Matcher pin = Pattern.compile("(?m)^\\s*" + Pattern.quote(property.group(1)) + "\\s*=\\s*(\\S+)").matcher(
                Files.readString(props)
            );
            return pin.find() ? pin.group(1) : null;
        }
        return null;
    }

    /**
     * The build plugin and Java toolchain move with the game. 26.1+ needs NeoGradle's 7.1 line and Java 25;
     * leaving either behind fails during configuration, before a single source error is reported.
     */
    static void toolchain(Setup setup, Build build, String to) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        Path groovy = build.project.root.resolve("build.gradle"), kts = build.project.root.resolve("build.gradle.kts");
        Path file = Files.isRegularFile(groovy) ? groovy : Files.isRegularFile(kts) ? kts : null;
        if (file == null) return;
        Matcher java = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)").matcher(setup.text(file));
        if (java.find()) {
            int want = javaFor(to);
            if (Integer.parseInt(java.group(1)) != want) {
                setup.replace(file, java.group(0), "JavaLanguageVersion.of(" + want + ")");
                setup.applied.add(
                    Json.map(
                        "file",
                        file.toString(),
                        "change",
                        "Java toolchain " + java.group(1) + " -> " + want + " for " + to
                    )
                );
            }
        }
        Path properties = build.project.root.resolve("gradle.properties");
        if (Files.isRegularFile(properties)) {
            for (String key : List.of("java_version", "java_toolchain_version", "java_target", "javaVersion", "java_release", "target_java_version")) {
                Matcher pin = Pattern.compile("(?m)^([ \\t]*" + key + "[ \\t]*=[ \\t]*)(\\d+)[ \\t]*$").matcher(
                    setup.text(properties)
                );
                if (!pin.find()) continue;
                int want = javaFor(to);
                if (Integer.parseInt(pin.group(2)) != want) {
                    setup.replace(properties, pin.group(0), pin.group(1) + want);
                    setup.applied.add(
                        Json.map(
                            "file",
                            properties.toString(),
                            "change",
                            key + " " + pin.group(2) + " -> " + want + " for " + to
                        )
                    );
                }
            }
        }
        // Gradle 9 removed the project-level sourceCompatibility and targetCompatibility; the java extension owns them.
        String compatibility = setup.text(file), javaOwned = compatibility.replaceAll(
            "(?m)^(sourceCompatibility|targetCompatibility)([ \\t]*=)",
            "java.$1$2"
        );
        if (!javaOwned.equals(compatibility)) {
            setup.originals.putIfAbsent(file, compatibility);
            setup.texts.put(file, javaOwned);
            setup.applied.add(
                Json.map("file", file.toString(), "change", "sourceCompatibility/targetCompatibility -> java.* for Gradle 9")
            );
        }
        // Gradle 9 removed the project-level archivesBaseName property; the base extension owns it now.
        Matcher archives = Pattern.compile("(?m)^[ \\t]*archivesBaseName[ \\t]*=[ \\t]*([^\\r\\n]+)$").matcher(
            setup.text(file)
        );
        if (archives.find()) {
            String value = archives.group(1).trim();
            setup.replace(file, archives.group(0), "base {\n\tarchivesName = " + value + "\n}");
            setup.applied.add(
                Json.map("file", file.toString(), "change", "archivesBaseName -> base.archivesName for Gradle 9")
            );
        }
        String archivesText = setup.text(file);
        String bare = archivesText.replaceAll(
            "(?<![\\w.])archivesBaseName(?![\\w(])",
            "project.base.archivesName.get()"
        );
        if (!bare.equals(archivesText)) {
            setup.originals.putIfAbsent(file, archivesText);
            setup.texts.put(file, bare);
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "remaining archivesBaseName reads go through base.archivesName"
                )
            );
        }
        if (archivesText.contains("project.archivesBaseName")) {
            String text = setup.text(file);
            setup.originals.putIfAbsent(file, text);
            setup.texts.put(file, text.replace("project.archivesBaseName", "project.base.archivesName.get()"));
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "publication archivesBaseName read through base.archivesName"
                )
            );
        }
    }

    /** The project's own Gradle stays: NeoGradle 7.1 runs on Gradle 8.14 and 9.4, and the newest Gradle (9.8) breaks it. */
    static int javaFor(String minecraft) {
        if (Versions.compare(minecraft, "26.1") >= 0) return 25;
        if (Versions.compare(minecraft, "1.20.5") >= 0) return 21;
        if (Versions.compare(minecraft, "1.18") >= 0) return 17;
        return Versions.compare(minecraft, "1.17") >= 0 ? 16 : 8;
    }

    /** NeoGradle's newest release whose version starts with line + "." (line "7" for the newest 7.x), from NeoForged's own published metadata. */
    static String latestNeoGradle(Build build, String line) throws Exception {
        String xml = fetch(
            build,
            "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml"
        );
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getElementsByTagName("version");
        String chosen = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(line + ".") || !value.matches("[0-9.]+")) continue;
            if (chosen.isEmpty() || Versions.compare(value, chosen) > 0) chosen = value;
        }
        if (chosen.isEmpty()) throw new IOException("No published NeoGradle release in the " + line + ".x line");
        return chosen;
    }

    /** NeoForge versions are numbered by Minecraft minor: 1.21.6 publishes as 21.6.x (1.20.1 as 47.x). */
    static String neoForgePrefix(String minecraft) {
        return minecraft.equals("1.20.1") ? "47." : minecraft.replaceFirst("^1\\.", "") + ".";
    }

    static String neoForgeVersion(Build build, String minecraft) throws Exception {
        String prefix = neoForgePrefix(minecraft);
        String xml = fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getElementsByTagName("version");
        String stable = "", any = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(prefix)) continue;
            if (value.matches("[0-9.]+") && (stable.isEmpty() || Versions.compare(value, stable) > 0)) stable = value;
            else if (any.isEmpty() || Versions.compare(value, any) > 0) any = value;
        }
        String chosen = stable.isEmpty() ? any : stable;
        if (chosen.isEmpty()) throw new IOException(
            "No published NeoForge build for " + minecraft + " (looked for " + prefix + "x)"
        );
        return chosen;
    }

    /** Replace the value of one property line, keeping its original separator and spacing. */
    static String replace(String text, String key, String value) throws IOException {
        Matcher match = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*").matcher(
            text
        );
        if (!match.find()) throw new IOException("Cannot edit property " + key + " without rewriting build logic");
        int start = match.start(), end = match.end();
        String replacement = match.group(1) + value;
        if (match.find()) throw new IOException("Duplicate property " + key + "; resolve its owner first");
        return text.substring(0, start) + replacement + text.substring(end);
    }

    private static String propertyText(String text, String key) throws IOException {
        Matcher match = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*[^\\r\\n]*").matcher(
            text
        );
        if (!match.find()) throw new IOException("Cannot find property " + key);
        return match.group();
    }

    /** Retarget one owned source manifest; a range that already accepts the target is left byte-exact. */
    static void manifest(Setup setup) throws Exception {
        for (Path file : manifests(setup.project)) {
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
            if (!(metadata.get("depends") instanceof Map<?, ?>)) continue;
            var depends = Json.object(metadata.get("depends"));
            if (!depends.containsKey("minecraft")) continue;
            // A list of alternatives (Sound Physics: ["1.21", "1.21.1"]) accepts the target when one of them does; when none
            // does, the list becomes the target release, as a single constraint would.
            if (depends.get("minecraft") instanceof List<?> alternatives && alternatives.stream().allMatch(a -> a instanceof String)) {
                boolean accepted = false;
                for (Object a : alternatives) if (String.valueOf(a).contains("${") || Versions.matches(String.valueOf(a), setup.to)) accepted = true;
                if (accepted) continue;
                Matcher list = Pattern.compile("(\"minecraft\"\\s*:\\s*)\\[[^\\]]*\\]").matcher(before);
                if (!list.find()) throw new IOException("Cannot locate depends.minecraft in " + file + "; update its owner manually.");
                int at = list.start();
                if (list.find()) throw new IOException("Fabric manifest names minecraft lists twice in " + file + "; update its owner manually.");
                Matcher only = Pattern.compile("(\"minecraft\"\\s*:\\s*)\\[[^\\]]*\\]").matcher(before);
                only.find(at);
                setup.replace(file, before, before.substring(0, only.start()) + only.group(1) + "\"" + setup.to + "\"" + before.substring(only.end()));
                continue;
            }
            if (!(depends.get("minecraft") instanceof String constraint)) throw new IOException(
                "Fabric manifest depends.minecraft is not one literal string in " +
                file +
                "; update its owner manually."
            );
            // The range filled in from a property when the jar is built (Trash Cans: "${minecraft_dependency}", with
            // minecraft_dependency=>=1.21.4 <1.21.5): the property is the owner, retargeted the same way.
            Matcher placeholder = Pattern.compile("^\\$\\{(\\w+)\\}$").matcher(constraint.trim());
            if (placeholder.find() && setup.build != null) {
                Path properties = setup.build.project.root.resolve("gradle.properties");
                Matcher owner = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(placeholder.group(1)) + "[ \\t]*=[ \\t]*([^\\r\\n]*?)[ \\t]*$").matcher(
                    Files.isRegularFile(properties) ? setup.text(properties) : ""
                );
                if (owner.find() && !owner.group(1).contains("$") && !Versions.matches(owner.group(1), setup.to)) {
                    setProperty(setup, properties, placeholder.group(1), setup.to);
                }
                continue;
            }
            if (constraint.contains("${") || constraint.equals(toRange(setup.to))) continue;
            if (Versions.matches(constraint, setup.to)) continue;
            // A range that excludes the target (">=26.3" ported to 26.2, ">=1.20.1 <1.21" to 1.21.6) would make Fabric refuse
            // the ported mod, so it becomes the target release, as a plain version does; the edit is one literal in the receipt.
            setup.replace(file, before, retargetMinecraft(before, constraint, setup.to, file));
        }
    }

    /**
     * Change only the depends.minecraft literal and keep every other byte, so the diff a mod author
     * reviews is one line instead of a re-serialized manifest.
     */
    static String retargetMinecraft(String text, String constraint, String to, Path file) throws IOException {
        Matcher depends = Pattern.compile("\"depends\"\\s*:\\s*\\{").matcher(text);
        int start = -1, end = -1;
        while (depends.find()) {
            if (depth(text, depends.start()) != 1) continue;
            if (start >= 0) throw new IOException(
                "Fabric manifest has two depends objects in " + file + "; update its owner manually."
            );
            start = depends.end();
            end = closing(text, depends.end() - 1);
        }
        if (start < 0 || end < 0) throw new IOException(
            "Cannot locate depends in Fabric manifest " + file + "; update its owner manually."
        );
        String block = text.substring(start, end);
        Matcher entry = Pattern.compile("(\"minecraft\"\\s*:\\s*\")" + Pattern.quote(constraint) + "\"").matcher(block);
        if (!entry.find()) throw new IOException(
            "Cannot locate depends.minecraft \"" + constraint + "\" in " + file + "; update its owner manually."
        );
        int at = entry.start(), after = entry.end();
        String key = entry.group(1);
        if (entry.find()) throw new IOException(
            "Fabric manifest names minecraft twice in depends in " + file + "; update its owner manually."
        );
        return text.substring(0, start + at) + key + to + "\"" + text.substring(start + after);
    }

    /** JSON object depth at a position, ignoring braces inside strings. */
    private static int depth(String text, int position) {
        int depth = 0;
        boolean string = false;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return depth;
    }

    /** Index of the brace that closes the object opened at {@code open}, or -1. */
    private static int closing(String text, int open) {
        int depth = 0;
        boolean string = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static String toRange(String to) {
        return to;
    }

    static List<Edit> manifestEditsForModel(Map<String, Object> model, String to) throws Exception {
        List<Edit> edits = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var project = Json.object(item);
            if (!Support.loaders(project).contains("fabric")) continue;
            Setup setup = new Setup(null, project, to, List.of());
            manifest(setup);
            edits.addAll(setup.edits());
        }
        return edits;
    }

    static List<Edit> manifestEdits(Map<String, Object> project, String to) throws Exception {
        Setup setup = new Setup(null, project, to, List.of());
        manifest(setup);
        return setup.edits();
    }

    static Set<Path> manifests(Map<String, Object> project) throws Exception {
        Path directory = Path.of(project.get("directory").toString()).toRealPath();
        Set<Path> manifests = new TreeSet<>();
        for (Object item : Json.array(project.get("sourceSets"))) {
            var set = Json.object(item);
            if (!Set.of("main", "client").contains(Json.text(set, "name", ""))) continue;
            for (String resource : ProjectModel.strings(set.get("resources"))) {
                Path root = Path.of(resource).toAbsolutePath().normalize();
                // A model can report a Windows 8.3 or symlinked path for the same directory; compare real paths.
                try {
                    root = root.toRealPath();
                } catch (IOException missing) {}
                if (!root.startsWith(directory)) throw new IOException(
                    "Project " +
                    project.get("path") +
                    " reads its resources from " +
                    root +
                    ", a folder it shares with other projects" +
                    " (a Stonecutter build does this: every version node compiles the same src/). Porting one node would change the files every other node uses, so mmt does not edit them." +
                    " To build another Minecraft version, select the node that already targets it (mmt setup lists them, e.g. --project :26.2), or add a node for it in settings.gradle.kts. Nothing was changed."
                );
                Path file = root.resolve("fabric.mod.json");
                if (Files.isRegularFile(file)) {
                    Path owned = file.toRealPath();
                    if (!owned.startsWith(directory)) throw new IOException(
                        "Fabric manifest resolves outside the selected project: " + owned
                    );
                    manifests.add(owned);
                }
            }
        }
        if (manifests.size() > 1) throw new IOException(
            "Multiple source fabric.mod.json files in selected project; choose the Minecraft dependency owner manually: " +
            manifests
        );
        return manifests;
    }

    static void apply(List<Edit> edits) throws IOException {
        for (Edit edit : edits) if (!Files.readString(edit.file()).equals(edit.before())) throw new IOException(
            "Target configuration changed during setup: " + edit.file()
        );
        requireWritable(edits);
        write(edits);
    }

    static void requireWritable(List<Edit> edits) throws IOException {
        // Every file is checked before the first is written: a read-only one stops the port with nothing changed.
        List<String> locked = new ArrayList<>();
        for (Edit edit : edits) if (!Files.isWritable(edit.file())) locked.add(edit.file().toString());
        if (!locked.isEmpty()) throw new IOException(
            "Cannot write " +
            String.join(", ", locked) +
            ": " +
            (locked.size() == 1 ? "it is" : "they are") +
            " read-only. Clear the read-only flag (Windows: file Properties; macOS and Linux: chmod u+w), then rerun. Nothing was changed."
        );
    }

    static void write(List<Edit> edits) throws IOException {
        for (Edit edit : edits) Files.writeString(edit.file(), edit.after());
    }

    static String fetch(Build build, String url) throws Exception {
        Path cache = build.project
            .state()
            .resolve("metadata/" + Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        if (
            Files.isRegularFile(cache) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 3600000
        ) return Files.readString(cache);
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "modding-accelerator/local-port-tool")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException transport) {
            throw unreachable(url, transport);
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) throw new LookupFailure(
            "Target dependency lookup failed (HTTP " + status + ", the server did not answer the question): " + url,
            null
        );
        if (status != 200) {
            var version = java.util.regex.Pattern.compile(
                "meta\\.fabricmc\\.net/v2/versions/(?:loader|yarn|intermediary)/([^/?]+)"
            ).matcher(url);
            if (version.find() && (status == 400 || status == 404)) throw new IOException(
                "Fabric has no release for Minecraft " +
                version.group(1) +
                " (" +
                url +
                " answered HTTP " +
                status +
                "). Check the version: it must be a released Minecraft version Fabric supports, such as 1.21.6 or 26.3. Nothing was changed."
            );
            throw new IOException("Target dependency lookup failed (HTTP " + status + "): " + url);
        }
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, response.body());
        return response.body();
    }

    /** A network error names the host and the real exception, never a bare "null". */
    static LookupFailure unreachable(String url, IOException transport) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException invalid) {
            host = url;
        }
        String detail = transport.getMessage() == null || transport.getMessage().isBlank()
            ? transport.getClass().getSimpleName()
            : transport.getClass().getSimpleName() + ": " + transport.getMessage();
        return new LookupFailure("Could not reach " + host + " (" + detail + "): " + url, transport);
    }

    /** Download one published artifact into the project's metadata cache; returns its local path. */
    static Path download(Build build, String url) throws Exception {
        Path cache = build.project
            .state()
            .resolve(
                "metadata/downloads/" +
                Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                ".jar"
            );
        if (Files.isRegularFile(cache)) return cache;
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "modding-accelerator/local-port-tool")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException transport) {
            throw unreachable(url, transport);
        }
        if (response.statusCode() != 200) throw new IOException(
            "Target artifact download failed (HTTP " + response.statusCode() + "): " + url
        );
        Files.createDirectories(cache.getParent());
        try (var input = response.body()) {
            Files.copy(input, cache, StandardCopyOption.REPLACE_EXISTING);
        }
        return cache;
    }
}
