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
        LookupFailure(String message, Throwable cause) { super(message, cause); }
    }
    /** Accumulates every target change as text, so one file is never edited twice against stale content. */
    static final class Setup {
        final Build build; final Map<String, Object> project; final String to;
        final List<Dependencies.Selection> selections;
        final Map<String, String> replacements = new LinkedHashMap<>(), pins = new LinkedHashMap<>();
        final Map<Path, String> texts = new LinkedHashMap<>(), originals = new LinkedHashMap<>();
        final List<Map<String, Object>> applied = new ArrayList<>(), blockers = new ArrayList<>(), unchecked = new ArrayList<>();
        final List<Path> excluded = new ArrayList<>();
        /** Target dependency (version) -> the Loom version its jar was built with. */
        final Map<String, String> loomBuilt = new LinkedHashMap<>();
        Setup(Build build, Map<String, Object> project, String to, List<Dependencies.Selection> selections) {
            this.build = build; this.project = project; this.to = to; this.selections = selections;
        }
        String text(Path file) throws IOException { return texts.containsKey(file) ? texts.get(file) : Files.readString(file); }
        void replace(Path file, String from, String to) throws IOException {
            String text = text(file);
            int at = text.indexOf(from);
            if (at < 0) throw new IOException("Cannot locate an unambiguous setting in " + file + ": " + from);
            if (text.indexOf(from, at + 1) >= 0) throw new IOException("Multiple matching settings in " + file + "; resolve their owner first: " + from);
            originals.putIfAbsent(file, text);
            texts.put(file, text.substring(0, at) + to + text.substring(at + from.length()));
        }
        List<Edit> edits() {
            List<Edit> edits = new ArrayList<>();
            for (Path file : texts.keySet()) if (!Objects.equals(originals.get(file), texts.get(file))) edits.add(new Edit(file, originals.get(file), texts.get(file)));
            return edits;
        }
    }
    static Setup plan(Build build, Map<String, Object> model, String to) throws Exception { return plan(build, model, to, List.of()); }
    static Setup plan(Build build, Map<String, Object> model, String to, List<Dependencies.Selection> selections) throws Exception {
        if (Json.array(model.get("projects")).size() != 1)
            throw new IOException("Automatic target setup needs one selected project; choose --project.");
        var project = Json.object(Json.array(model.get("projects")).get(0));
        Set<String> loaders = Support.loaders(project);
        if (!loaders.equals(Set.of("fabric")) && !loaders.equals(Set.of("neoforge")))
            throw new IOException("Automatic target dependency setup requires one Fabric or NeoForge project; found " + loaders
                    + ". Set this loader's target dependencies in the source build, then rerun port --from OLD --to NEW --fix.");
        Setup setup = new Setup(build, project, to, selections);
        var pins = new LinkedHashMap<>(Json.object(project.get("pins")));
        for (var pin : pins.entrySet()) if (pin.getValue() != null) setup.pins.put(pin.getKey(), pin.getValue().toString());
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (pins.containsKey(key)) setup.replacements.put(key, to);
        if (setup.replacements.isEmpty()) throw new IOException("Minecraft is pinned inside build logic; update its owning setting, then rerun port.");
        if (loaders.equals(Set.of("neoforge"))) {
            String resolved = neoForgeVersion(build, to);
            boolean owned = false;
            for (String key : List.of("neo_version", "neoforge_version", "neoForgeVersion", "neoforgeVersion")) if (pins.containsKey(key)) { setup.replacements.put(key, resolved); owned = true; }
            if (!owned) throw new IOException("No NeoForge version pin (neo_version/neoforge_version) in this project; the build would keep its old NeoForge and Minecraft jars. Add or expose that property, then rerun port.");
            // Mod metadata declares an accepted game range; leaving it on the old version makes NeoForge refuse the mod at runtime.
            if (pins.containsKey("minecraft_version_range")) setup.replacements.put("minecraft_version_range", "[" + to + "," + to + "]");
            for (String key : List.of("neo_version_range", "neoforge_version_range")) if (pins.containsKey(key)) setup.replacements.put(key, "[" + resolved + ",)");
            Dependencies.resolve(setup, "neoforge");
            applyReplacements(setup, build, pins);
            neoGradleFollows(setup, build, to);
            toolchain(setup, build, to);
            runTypeRename(setup, build, to);
            parchmentFollows(setup, build, to);
            setup.applied.add(Json.map("artifact", "net.neoforged:neoforge", "to", resolved, "identity", "resolved from maven.neoforged.net", "gameVersion", to,
                    "evidence", "NeoForge " + resolved + " publishes for " + to));
            return setup;
        }
        for (String key : List.of("yarn_mappings", "yarn_version")) if (pins.containsKey(key)) {
            var versions = Json.array(Json.parse(fetch(build, "https://meta.fabricmc.net/v2/versions/yarn/" + to)));
            if (versions.isEmpty()) throw new IOException("No published Yarn mappings for " + to + "; mapping namespace changes need a guided migration.");
            setup.replacements.put(key, Json.text(Json.object(versions.get(0)), "version", ""));
        }
        if (pins.containsKey("loader_version")) {
            var versions = Json.array(Json.parse(fetch(build, "https://meta.fabricmc.net/v2/versions/loader/" + to)));
            String loader = "";
            for (Object item : versions) {
                var value = Json.object(Json.object(item).get("loader"));
                if (Boolean.TRUE.equals(value.get("stable"))) { loader = value.get("version").toString(); break; }
            }
            if (loader.isEmpty()) throw new IOException("No published stable Fabric loader for " + to);
            setup.replacements.put("loader_version", loader);
        }
        if (pins.containsKey("fabric_version") || pins.containsKey("fabric_api_version")) {
            String xml = fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml");
            var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("version");
            String api = "";
            for (int i = 0; i < nodes.getLength(); i++) { String value = nodes.item(i).getTextContent(); if (value.endsWith("+" + to)) api = value; }
            if (api.isEmpty()) throw new IOException("No published Fabric API for " + to);
            for (String key : List.of("fabric_version", "fabric_api_version")) if (pins.containsKey(key)) setup.replacements.put(key, api);
            try {
                String built = Dependencies.builtWithLoom(download(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" + api + "/fabric-api-" + api + ".jar"));
                if (!built.isEmpty()) setup.loomBuilt.put("Fabric API " + api, built);
            } catch (IOException unread) {
                setup.unchecked.add(Json.map("artifact", "net.fabricmc.fabric-api:fabric-api", "version", api, "reason", "Its jar could not be read for the Loom it needs: " + unread.getMessage()));
            }
        }
        Dependencies.resolve(setup);
        applyReplacements(setup, build, pins);
        loomFollows(setup, build, to);
        parchmentFollows(setup, build, to);
        manifest(setup);
        return setup;
    }
    /** Parchment's newest release of parameter names for exactly this Minecraft version, or "" when it publishes none. */
    static String parchmentRelease(Build build, String to) throws Exception {
        String xml;
        try { xml = fetch(build, "https://maven.parchmentmc.org/org/parchmentmc/data/parchment-" + to + "/maven-metadata.xml"); }
        catch (LookupFailure couldNotAsk) { throw couldNotAsk; }
        catch (IOException answered) { if (answered.getMessage() != null && answered.getMessage().contains("(HTTP 404)")) return ""; throw answered; }
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
    static void parchmentFollows(Setup setup, Build build, String to) throws Exception { parchmentEdit(setup, build, to, null); }
    /** parchmentFollows with Parchment's release for the target given ("" for none), or null to look it up only if the build states Parchment. */
    static boolean parchmentEdit(Setup setup, Build build, String to, String known) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        String props = Files.isRegularFile(properties) ? setup.text(properties) : "";
        Matcher neoGradle = Pattern.compile("(?m)^[ \\t]*neogradle\\.subsystems\\.parchment\\.minecraftVersion[ \\t]*=[ \\t]*(\\S+)").matcher(props);
        Matcher modDev = Pattern.compile("(?m)^[ \\t]*parchment_minecraft_version[ \\t]*=[ \\t]*(\\S+)").matcher(props);
        List<Path> scripts = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts")) if (Files.isRegularFile(build.project.root.resolve(name))) scripts.add(build.project.root.resolve(name));
        Pattern layered = Pattern.compile("parchment\\(\\s*[\"']org\\.parchmentmc\\.data:parchment-([^:\"']+):([^@\"']+)@zip[\"']\\s*\\)");
        Path loomScript = null; Matcher loom = null;
        for (Path script : scripts) { Matcher found = layered.matcher(setup.text(script)); if (found.find()) { loomScript = script; loom = found; break; } }
        boolean hasNeoGradle = neoGradle.find(), hasModDev = modDev.find();
        if (!hasNeoGradle && !hasModDev && loom == null) return false;
        String stated = hasNeoGradle ? neoGradle.group(1) : hasModDev ? modDev.group(1) : loom.group(1);
        String release = known != null ? known : parchmentRelease(build, to);
        String evidence = release.isEmpty() ? "Parchment publishes no parameter names for " + to + " (maven.parchmentmc.org)" : "Parchment " + release + " is its newest release for " + to;
        if (hasNeoGradle) {
            if (release.isEmpty()) removeLines(setup, properties, "neogradle\\.subsystems\\.parchment\\.[\\w.]+[ \\t]*=.*");
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
                    Matcher block = Pattern.compile("(?m)^[ \\t]*parchment\\s*\\{[^{}]*parchment_(?:minecraft|mappings)_version[^{}]*\\}[ \\t]*\\r?\\n").matcher(text);
                    if (block.find()) { setup.replace(script, block.group(), ""); removed = true; }
                }
                if (!removed) throw new IOException("gradle.properties sets parchment_minecraft_version=" + stated + ", Parchment publishes no names for " + to
                        + ", and no parchment { } block reading it was found to remove. Remove the Parchment setting from the build, then rerun port.");
                removeLines(setup, properties, "parchment_(?:minecraft|mappings)_version[ \\t]*=.*");
            } else {
                setProperty(setup, properties, "parchment_minecraft_version", to);
                setProperty(setup, properties, "parchment_mappings_version", release);
            }
        }
        if (loom != null) {
            String text = setup.text(loomScript);
            Matcher line = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(loom.group()) + "[ \\t]*\\r?\\n").matcher(text);
            if (release.isEmpty() && line.find()) setup.replace(loomScript, line.group(), "");
            else if (release.isEmpty()) throw new IOException(loomScript.getFileName() + " layers Parchment " + loom.group(1) + " into its mappings and Parchment publishes no names for " + to
                    + "; remove that parchment(...) layer, then rerun port.");
            else setup.replace(loomScript, loom.group(), "parchment(\"org.parchmentmc.data:parchment-" + to + ":" + release + "@zip\")");
        }
        setup.applied.add(Json.map("change", release.isEmpty() ? "Parchment " + stated + " parameter names removed for " + to : "Parchment " + stated + " -> " + to + " " + release, "evidence", evidence));
        return true;
    }
    private static void setProperty(Setup setup, Path properties, String key, String value) throws IOException {
        Matcher line = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)(\\S*)").matcher(setup.text(properties));
        if (!line.find()) throw new IOException(properties.getFileName() + " has no " + key + " to set to " + value + "; set it by hand, then rerun port.");
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
        for (var built : setup.loomBuilt.entrySet())
            if (need.isEmpty() || Versions.compare(Dependencies.minor(built.getValue()), Dependencies.minor(need)) > 0) { need = built.getValue(); needBy = built.getKey(); }
        if (need.isEmpty() || current.isEmpty() || Versions.compare(Dependencies.minor(need), Dependencies.minor(current)) <= 0) {
            if (current.isEmpty()) for (var built : setup.loomBuilt.entrySet()) {
                String warning = Dependencies.loomWarning(Json.map("version_number", built.getKey()), built.getValue(), "(not stated)");
                if (warning != null) System.err.println("Warning: " + warning);
            }
            return;
        }
        String line = Dependencies.minor(need), loom = newestLoom(build, line);
        if (!loomEdit(setup, build, current, loom)) {
            System.err.println("Warning: " + Dependencies.loomWarning(Json.map("version_number", needBy), need, current));
            return;
        }
        setup.applied.add(Json.map("change", "Loom " + current + " -> " + loom + " for " + to, "evidence", needBy + " was built with Loom " + need));
        gradleFollows(setup, build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/" + loom + "/fabric-loom-" + loom + ".module", "Loom " + loom, "fabric-loom " + loom);
    }
    /**
     * Gradle rises to the version a build plugin's own module metadata requires (org.gradle.plugin.api-version), and is
     * never lowered: an older Gradle refuses the plugin while the build loads, before any source is compiled.
     */
    static void gradleFollows(Setup setup, Build build, String moduleUrl, String plugin, String evidence) throws Exception {
        Matcher api = Pattern.compile("\"org\\.gradle\\.plugin\\.api-version\"\\s*:\\s*\"([^\"]+)\"").matcher(fetch(build, moduleUrl));
        if (!api.find()) return;
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) return;
        String text = setup.text(wrapper);
        Matcher url = Pattern.compile("(distributionUrl=[^\\r\\n]*?gradle-)([0-9][0-9.]*(?:-rc-?\\d+)?)(-(?:bin|all)\\.zip)").matcher(text);
        if (!url.find() || Versions.compare(url.group(2), api.group(1)) >= 0) return;
        String gradle = api.group(1);
        setup.replace(wrapper, url.group(0), url.group(1) + gradle + url.group(3));
        Matcher sum = Pattern.compile("(?m)^distributionSha256Sum=\\S+").matcher(setup.text(wrapper));
        if (sum.find()) setup.replace(wrapper, sum.group(0), "distributionSha256Sum="
                + fetch(build, "https://services.gradle.org/distributions/gradle-" + gradle + url.group(3) + ".sha256").trim());
        setup.applied.add(Json.map("file", wrapper.toString(), "change", "Gradle " + url.group(2) + " -> " + gradle + " for " + plugin,
                "evidence", evidence + " requires Gradle " + gradle + " (org.gradle.plugin.api-version)"));
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
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version")) if (setup.pins.containsKey(key)) { from = setup.pins.get(key); break; }
        if (!from.isEmpty() && Versions.compare(to, from) <= 0) return;
        Pattern pluginLine = Pattern.compile("(id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.userdev[\"']\\s*\\)?\\s*version\\s*[\"'])([^\"']+)([\"'])");
        Path properties = build.project.root.resolve("gradle.properties");
        for (Path file : List.of(build.project.root.resolve("build.gradle"), build.project.root.resolve("build.gradle.kts"), build.project.root.resolve("settings.gradle"), build.project.root.resolve("settings.gradle.kts"), properties)) {
            if (!Files.isRegularFile(file)) continue;
            Matcher pin = file.equals(properties)
                    ? Pattern.compile("(?m)^([ \\t]*(?:neogradle_version|neoGradleVersion|neogradleVersion)[ \\t]*=[ \\t]*)(\\S+)()[ \\t]*$").matcher(setup.text(file))
                    : pluginLine.matcher(setup.text(file));
            if (!pin.find() || !pin.group(2).matches("\\d+\\.\\d+\\.\\d+")) continue;
            String current = pin.group(2), newest = latestNeoGradle(build, "7");
            if (Versions.compare(newest, current) <= 0) return;
            setup.replace(file, pin.group(0), pin.group(1) + newest + pin.group(3));
            setup.applied.add(Json.map("file", file.toString(), "change", "NeoGradle " + current + " -> " + newest + " for " + to, "evidence", "the newest NeoGradle 7.x on maven.neoforged.net"));
            gradleFollows(setup, build, "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/" + newest + "/userdev-" + newest + ".module", "NeoGradle " + newest, "NeoGradle " + newest);
            return;
        }
    }
    /** The newest release of one Loom line (e.g. 1.10 -> 1.10.5), read from maven.fabricmc.net. */
    static String newestLoom(Build build, String line) throws Exception {
        String xml = fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("version");
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
        Pattern plugin = Pattern.compile("(id\\s*\\(?\\s*[\"'](?:fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])");
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.matches("(?:build|settings)\\.gradle(?:\\.kts)?")) continue;
            Matcher found = plugin.matcher(setup.text(file));
            if (!found.find()) continue;
            Matcher property = Pattern.compile("^\\$\\{?(?:project\\.)?([A-Za-z_][A-Za-z0-9_]*)\\}?$").matcher(found.group(2));
            if (property.find()) { keys.add(0, property.group(1)); break; }
            if (!found.group(2).strip().equals(current)) continue;
            setup.replace(file, found.group(0), found.group(1) + loom + found.group(3));
            return true;
        }
        if (!Files.isRegularFile(properties)) return false;
        for (String key : keys) {
            Matcher pin = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)(\\S+)[ \\t]*$").matcher(setup.text(properties));
            if (pin.find() && pin.group(2).equals(current)) { setup.replace(properties, pin.group(0), pin.group(1) + loom); return true; }
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
            String updated = text.substring(0, match.start()) + match.group(1) + "clientData" + match.group(2) + "{" + text.substring(match.end());
            setup.replace(file, text, updated);
            setup.applied.add(Json.map("file", file.toString(), "change", "data run type renamed to clientData for NeoForge 1.21.4+"));
        }
    }
    private static void applyReplacements(Setup setup, Build build, Map<String, Object> pins) throws IOException {
        for (var pin : setup.replacements.entrySet()) {
            List<Path> owners = new ArrayList<>();
            for (Path file : build.project.inputs()) if (file.getFileName().toString().equals("gradle.properties")
                    && Objects.equals(ProjectModel.properties(file).getProperty(pin.getKey()), setup.pins.get(pin.getKey()))) owners.add(file);
            if (owners.size() > 1) {
                // Stonecutter-style repos keep one property file per node; the selected node owns its own copy.
                Path directory = Path.of(Json.text(setup.project, "directory", "")).toAbsolutePath().normalize();
                List<Path> own = owners.stream().filter(p -> p.toAbsolutePath().normalize().startsWith(directory)).toList();
                if (own.size() == 1) owners = own;
            }
            if (owners.size() != 1) throw new IOException("Cannot choose an unambiguous gradle.properties owner for " + pin.getKey() + ": " + owners);
            Path owner = owners.get(0); String text = setup.text(owner);
            Matcher match = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(pin.getKey()) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*").matcher(text);
            if (!match.find()) throw new IOException("Cannot find property " + pin.getKey() + " in " + owner);
            String prefix = match.group(1); int start = match.start(), end = match.end();
            if (match.find()) throw new IOException("Duplicate property " + pin.getKey() + " in " + owner);
            setup.replace(owner, text, text.substring(0, start) + prefix + pin.getValue() + text.substring(end));
        }
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
                setup.applied.add(Json.map("file", file.toString(), "change", "Java toolchain " + java.group(1) + " -> " + want + " for " + to));
            }
        }
        Path properties = build.project.root.resolve("gradle.properties");
        if (Files.isRegularFile(properties)) {
            for (String key : List.of("java_version", "java_toolchain_version")) {
                Matcher pin = Pattern.compile("(?m)^([ \\t]*" + key + "[ \\t]*=[ \\t]*)(\\d+)[ \\t]*$").matcher(setup.text(properties));
                if (!pin.find()) continue;
                int want = javaFor(to);
                if (Integer.parseInt(pin.group(2)) != want) {
                    setup.replace(properties, pin.group(0), pin.group(1) + want);
                    setup.applied.add(Json.map("file", properties.toString(), "change", key + " " + pin.group(2) + " -> " + want + " for " + to));
                }
            }
        }
        // Gradle 9 removed the project-level archivesBaseName property; the base extension owns it now.
        Matcher archives = Pattern.compile("(?m)^[ \\t]*archivesBaseName[ \\t]*=[ \\t]*([^\\r\\n]+)$").matcher(setup.text(file));
        if (archives.find()) {
            String value = archives.group(1).trim();
            setup.replace(file, archives.group(0), "base {\n\tarchivesName = " + value + "\n}");
            setup.applied.add(Json.map("file", file.toString(), "change", "archivesBaseName -> base.archivesName for Gradle 9"));
        }
        String archivesText = setup.text(file);
        String bare = archivesText.replaceAll("(?<![\\w.])archivesBaseName(?![\\w(])", "project.base.archivesName.get()");
        if (!bare.equals(archivesText)) {
            setup.originals.putIfAbsent(file, archivesText);
            setup.texts.put(file, bare);
            setup.applied.add(Json.map("file", file.toString(), "change", "remaining archivesBaseName reads go through base.archivesName"));
        }
        if (archivesText.contains("project.archivesBaseName")) {
            String text = setup.text(file);
            setup.originals.putIfAbsent(file, text);
            setup.texts.put(file, text.replace("project.archivesBaseName", "project.base.archivesName.get()"));
            setup.applied.add(Json.map("file", file.toString(), "change", "publication archivesBaseName read through base.archivesName"));
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
        String xml = fetch(build, "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("version");
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
    static String neoForgePrefix(String minecraft) { return minecraft.equals("1.20.1") ? "47." : minecraft.replaceFirst("^1\\.", "") + "."; }
    static String neoForgeVersion(Build build, String minecraft) throws Exception {
        String prefix = neoForgePrefix(minecraft);
        String xml = fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("version");
        String stable = "", any = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(prefix)) continue;
            if (value.matches("[0-9.]+") && (stable.isEmpty() || Versions.compare(value, stable) > 0)) stable = value;
            else if (any.isEmpty() || Versions.compare(value, any) > 0) any = value;
        }
        String chosen = stable.isEmpty() ? any : stable;
        if (chosen.isEmpty()) throw new IOException("No published NeoForge build for " + minecraft + " (looked for " + prefix + "x)");
        return chosen;
    }
    /** Replace the value of one property line, keeping its original separator and spacing. */
    static String replace(String text, String key, String value) throws IOException {
        Matcher match = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*)[^\\r\\n]*").matcher(text);
        if (!match.find()) throw new IOException("Cannot edit property " + key + " without rewriting build logic");
        int start = match.start(), end = match.end(); String replacement = match.group(1) + value;
        if (match.find()) throw new IOException("Duplicate property " + key + "; resolve its owner first");
        return text.substring(0, start) + replacement + text.substring(end);
    }
    private static String propertyText(String text, String key) throws IOException {
        Matcher match = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*[^\\r\\n]*").matcher(text);
        if (!match.find()) throw new IOException("Cannot find property " + key);
        return match.group();
    }
    /** Retarget one owned source manifest; a range that already accepts the target is left byte-exact. */
    static void manifest(Setup setup) throws Exception {
        for (Path file : manifests(setup.project)) {
            String before = setup.text(file);
            Map<String, Object> metadata;
            try { metadata = Json.object(Json.parse(before)); }
            catch (IllegalArgumentException invalid) { throw new IOException("Cannot safely edit source Fabric manifest " + file + ": " + invalid.getMessage(), invalid); }
            if (!(metadata.get("depends") instanceof Map<?, ?>)) continue;
            var depends = Json.object(metadata.get("depends"));
            if (!depends.containsKey("minecraft")) continue;
            if (!(depends.get("minecraft") instanceof String constraint))
                throw new IOException("Fabric manifest depends.minecraft is not one literal string in " + file + "; update its owner manually.");
            if (constraint.contains("${") || constraint.equals(toRange(setup.to))) continue;
            if (Versions.matches(constraint, setup.to)) continue;
            if (!constraint.matches("~?[0-9]+(?:\\.[0-9]+){1,2}"))
                throw new IOException("Fabric manifest depends.minecraft has a complex constraint " + constraint + " in " + file + "; update its owner manually.");
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
            if (start >= 0) throw new IOException("Fabric manifest has two depends objects in " + file + "; update its owner manually.");
            start = depends.end(); end = closing(text, depends.end() - 1);
        }
        if (start < 0 || end < 0) throw new IOException("Cannot locate depends in Fabric manifest " + file + "; update its owner manually.");
        String block = text.substring(start, end);
        Matcher entry = Pattern.compile("(\"minecraft\"\\s*:\\s*\")" + Pattern.quote(constraint) + "\"").matcher(block);
        if (!entry.find()) throw new IOException("Cannot locate depends.minecraft \"" + constraint + "\" in " + file + "; update its owner manually.");
        int at = entry.start(), after = entry.end(); String key = entry.group(1);
        if (entry.find()) throw new IOException("Fabric manifest names minecraft twice in depends in " + file + "; update its owner manually.");
        return text.substring(0, start + at) + key + to + "\"" + text.substring(start + after);
    }
    /** JSON object depth at a position, ignoring braces inside strings. */
    private static int depth(String text, int position) {
        int depth = 0; boolean string = false;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i);
            if (string) { if (c == '\\') i++; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return depth;
    }
    /** Index of the brace that closes the object opened at {@code open}, or -1. */
    private static int closing(String text, int open) {
        int depth = 0; boolean string = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) { if (c == '\\') i++; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
    private static String toRange(String to) { return to; }
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
                try { root = root.toRealPath(); } catch (IOException missing) { }
                if (!root.startsWith(directory)) throw new IOException("Project " + project.get("path") + " reads its resources from " + root + ", a folder it shares with other projects"
                        + " (a Stonecutter build does this: every version node compiles the same src/). Porting one node would change the files every other node uses, so modaccel does not edit them."
                        + " To build another Minecraft version, select the node that already targets it (modaccel setup lists them, e.g. --project :26.2), or add a node for it in settings.gradle.kts. Nothing was changed.");
                Path file = root.resolve("fabric.mod.json");
                if (Files.isRegularFile(file)) {
                    Path owned = file.toRealPath();
                    if (!owned.startsWith(directory)) throw new IOException("Fabric manifest resolves outside the selected project: " + owned);
                    manifests.add(owned);
                }
            }
        }
        if (manifests.size() > 1) throw new IOException("Multiple source fabric.mod.json files in selected project; choose the Minecraft dependency owner manually: " + manifests);
        return manifests;
    }
    static void apply(List<Edit> edits) throws IOException {
        for (Edit edit : edits) if (!Files.readString(edit.file()).equals(edit.before())) throw new IOException("Target configuration changed during setup: " + edit.file());
        requireWritable(edits);
        write(edits);
    }
    static void requireWritable(List<Edit> edits) throws IOException {
        // Every file is checked before the first is written: a read-only one stops the port with nothing changed.
        List<String> locked = new ArrayList<>();
        for (Edit edit : edits) if (!Files.isWritable(edit.file())) locked.add(edit.file().toString());
        if (!locked.isEmpty()) throw new IOException("Cannot write " + String.join(", ", locked) + ": " + (locked.size() == 1 ? "it is" : "they are")
                + " read-only. Clear the read-only flag (Windows: file Properties; macOS and Linux: chmod u+w), then rerun. Nothing was changed.");
    }
    static void write(List<Edit> edits) throws IOException {
        for (Edit edit : edits) Files.writeString(edit.file(), edit.after());
    }
    static String fetch(Build build, String url) throws Exception {
        Path cache = build.project.state().resolve("metadata/" + Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        if (Files.isRegularFile(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 3600000) return Files.readString(cache);
        var request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "modding-accelerator/local-port-tool").timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response;
        try { response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (IOException transport) { throw unreachable(url, transport); }
        int status = response.statusCode();
        if (status == 429 || status >= 500) throw new LookupFailure("Target dependency lookup failed (HTTP " + status + ", the server did not answer the question): " + url, null);
        if (status != 200) {
            var version = java.util.regex.Pattern.compile("meta\\.fabricmc\\.net/v2/versions/(?:loader|yarn|intermediary)/([^/?]+)").matcher(url);
            if (version.find() && (status == 400 || status == 404))
                throw new IOException("Fabric has no release for Minecraft " + version.group(1) + " (" + url + " answered HTTP " + status + "). Check the version: it must be a released Minecraft version Fabric supports, such as 1.21.6 or 26.3. Nothing was changed.");
            throw new IOException("Target dependency lookup failed (HTTP " + status + "): " + url);
        }
        Files.createDirectories(cache.getParent()); Files.writeString(cache, response.body()); return response.body();
    }
    /** A network error names the host and the real exception, never a bare "null". */
    static LookupFailure unreachable(String url, IOException transport) {
        String host; try { host = URI.create(url).getHost(); } catch (IllegalArgumentException invalid) { host = url; }
        String detail = transport.getMessage() == null || transport.getMessage().isBlank() ? transport.getClass().getSimpleName() : transport.getClass().getSimpleName() + ": " + transport.getMessage();
        return new LookupFailure("Could not reach " + host + " (" + detail + "): " + url, transport);
    }
    /** Download one published artifact into the project's metadata cache; returns its local path. */
    static Path download(Build build, String url) throws Exception {
        Path cache = build.project.state().resolve("metadata/downloads/" + Fingerprints.bytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".jar");
        if (Files.isRegularFile(cache)) return cache;
        var request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "modding-accelerator/local-port-tool").timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> response;
        try { response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (IOException transport) { throw unreachable(url, transport); }
        if (response.statusCode() != 200) throw new IOException("Target artifact download failed (HTTP " + response.statusCode() + "): " + url);
        Files.createDirectories(cache.getParent());
        try (var input = response.body()) { Files.copy(input, cache, StandardCopyOption.REPLACE_EXISTING); }
        return cache;
    }
}
