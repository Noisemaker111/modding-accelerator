import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * plugin-check: a Paper/Bukkit plugin's run check without a real server. The plugin's own jar task
 * builds the jar; Gradle resolves MockBukkit (a fake Paper server) for the plugin's Paper API line
 * in a detached configuration; tools/plugincheck/ModaccelPluginCheck loads and enables the real jar
 * in a fresh JVM and reports each check. MockBukkit has no Minecraft server inside, so code through
 * server internals, and API MockBukkit does not implement, is reported unchecked, never passed.
 */
final class PluginCheck {
    static final List<String> SOURCES = List.of("tools/plugincheck/ModaccelPluginCheck.java", "tools/cli/Json.java");
    static final Pattern API_JAR = Pattern.compile("(?:^|[\\\\/])(paper-api|spigot-api|bukkit)-(\\d[^\\\\/]*?)\\.jar$");
    static final int TIMEOUT_SECONDS = 300;

    record Api(String kind, String version) { }
    record Release(String paper, String artifact, String version, String paperApi, int java) { }

    final Build build; final Map<String, Object> model;
    PluginCheck(Build build, Map<String, Object> model) { this.build = build; this.model = model; }

    /** The server API the plugin compiles against, from its resolved compile class path. */
    static Api api(Map<String, Object> model) {
        for (Object p : Json.array(model.get("projects"))) for (Object c : Json.array(Json.object(p).getOrDefault("compile", List.of())))
            for (String file : ProjectModel.strings(Json.object(c).get("classpath"))) {
                Matcher m = API_JAR.matcher(file);
                if (m.find()) return new Api(m.group(1), m.group(2));
            }
        for (Object p : Json.array(model.get("projects"))) for (Object d : Json.array(Json.object(p).getOrDefault("dependencies", List.of()))) {
            var dependency = Json.object(d); String name = Json.text(dependency, "name", ""), version = Json.text(dependency, "version", "");
            if (Set.of("paper-api", "spigot-api", "bukkit").contains(name) && !version.isEmpty()) return new Api(name, version);
        }
        return null;
    }
    /** The Paper line of an API version: 26.3.build.214-stable is 26.3, 1.21.10-R0.1-SNAPSHOT is 1.21. */
    static String line(String version) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version);
        return m.find() ? m.group(1) + "." + m.group(2) : version;
    }
    static Release release(Path harness, String version) throws IOException {
        String line = line(version);
        for (Object r : Json.array(Json.read(harness.resolve("knowledge/mockbukkit.json")).get("releases"))) {
            var release = Json.object(r);
            if (Json.text(release, "paper", "").equals(line))
                return new Release(line, Json.text(release, "artifact", ""), Json.text(release, "version", ""), Json.text(release, "paperApi", ""), ((Number)release.get("java")).intValue());
        }
        return null;
    }
    static List<String> lines(Path harness) throws IOException {
        List<String> out = new ArrayList<>();
        for (Object r : Json.array(Json.read(harness.resolve("knowledge/mockbukkit.json")).get("releases"))) out.add(Json.text(Json.object(r), "paper", ""));
        return out;
    }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        long begin = System.nanoTime();
        Api api = api(model);
        if (api == null) return unchecked("This build compiles against no Paper, Spigot or Bukkit API (no paper-api, spigot-api or bukkit jar on its compile class path), so it is not a server plugin and plugin-check has nothing to load. Mods use ui-check, world-check and mixin-check.");
        Release release = release(build.harness, api.version());
        if (release == null) return unchecked("The plugin builds against " + api.kind() + " " + api.version() + " (Paper " + line(api.version()) + "), and plugin-check has no MockBukkit release for that line (it has "
                + String.join(", ", lines(build.harness)) + "). Nothing was loaded; this needs a real Paper server or a MockBukkit line added to knowledge/mockbukkit.json.");
        List<Map<String, Object>> plugins = pluginProjects();
        if (plugins.isEmpty()) return unchecked("No plugin.yml or paper-plugin.yml in the selected project's resources (src/main/resources), so there is no plugin to load. Select the plugin's module with --project.");
        if (plugins.size() > 1) return unchecked("Several projects have a plugin descriptor (" + String.join(", ", plugins.stream().map(p -> p.get("path").toString()).toList()) + "). Pick one with --project.");
        Map<String, Object> project = plugins.get(0);
        Path descriptor = Path.of(project.get("descriptor").toString());

        // One Gradle run: the plugin's own jar task, then MockBukkit and the Paper API resolved next to it.
        Path work = build.report.directory.resolve("plugin-check"); Files.createDirectories(work);
        Path spec = work.resolve("classpath-spec.json"), classpathFile = work.resolve("classpath.json");
        List<String> coordinates = new ArrayList<>(List.of(release.artifact() + ":" + release.version(), "io.papermc.paper:paper-api:" + release.paperApi()));
        if (api.kind().equals("paper-api")) coordinates.add("io.papermc.paper:paper-api:" + api.version());
        coordinates.addAll(libraries(descriptor));
        Json.save(spec, Json.map("coordinates", coordinates, "output", classpathFile.toString(), "jarTask", options.get("task", "")));
        String path = project.get("path").toString();
        Run.Result gradle = build.gradle(List.of("-Dmodaccel.pluginCheck=" + spec, (path.equals(":") ? "" : path) + ":modaccelPluginClasspath"), "plugin-build");
        if (gradle.exit() != 0 || !Files.isRegularFile(classpathFile)) {
            String reason = "The plugin jar did not build, or MockBukkit " + release.version() + " for Paper " + release.paper() + " did not resolve, so nothing was loaded. "
                    + Run.gradleFailure(gradle.log(), model) + "\nLog: " + gradle.log();
            return Json.map("ok", false, "tier", "plugin-build", "gameVerified", false, "reason", reason, "details", reason, "log", gradle.log().toString());
        }
        Map<String, Object> resolved = Json.read(classpathFile);
        List<String> classpath = ProjectModel.strings(resolved.get("files"));
        String jar = Json.text(resolved, "jar", "");

        int java = Math.max(release.java(), release(project));
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(build.harness, java);
        Path tools = tools(jdk, classpath, java);
        Path result = work.resolve("result.json"), checkSpec = work.resolve("check-spec.json");
        List<String> pluginJars = new ArrayList<>();
        if (options.values.containsKey("plugins")) {
            Path folder = build.project.root.resolve(options.get("plugins", "")).normalize();
            if (!Files.isDirectory(folder)) throw new IllegalArgumentException("--plugins needs a folder of plugin jars; " + folder + " is not a folder.");
            try (var files = Files.list(folder)) { files.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(f -> pluginJars.add(f.toAbsolutePath().toString())); }
        }
        Json.save(checkSpec, Json.map("jar", jar, "result", result.toString(), "descriptorSource", descriptor.toString(),
                "javaRoots", project.get("java"), "plugins", pluginJars));
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-Xshare:auto", "-cp", tools + File.pathSeparator + String.join(File.pathSeparator, classpath),
                "ModaccelPluginCheck", checkSpec.toString()));
        Run.Result check = Run.command(command, build.project.root, Map.of(), build.report.log("plugin-check"), TIMEOUT_SECONDS);
        build.report.stage("plugin-check", check, Json.map("mockbukkit", release.artifact() + ":" + release.version()));
        if (!Files.isRegularFile(result)) {
            String reason = "The MockBukkit check JVM stopped before writing a result (exit " + check.exit() + "). " + Run.tail(check.log());
            return Json.map("ok", false, "tier", "plugin-check", "gameVerified", false, "reason", reason, "details", reason, "log", check.log().toString());
        }
        Map<String, Object> out = Json.read(result);
        out.put("tier", "plugin-check");
        out.put("checkedBy", "MockBukkit " + release.version() + " (" + release.artifact().replaceAll("^.*:", "") + "), a fake Paper server in a test JVM; no Minecraft server ran");
        out.put("gameVerified", false);
        out.put("jarTask", resolved.get("jarTask"));
        out.put("api", Json.map("kind", api.kind(), "version", api.version()));
        out.put("seconds", (System.nanoTime() - begin) / 1e9);
        out.put("log", check.log().toString());
        String text = text(out, build.project.root);
        out.put("details", text);
        if (!Boolean.TRUE.equals(out.get("ok"))) out.put("reason", text);
        return out;
    }

    /** Projects among the selected ones whose resources hold plugin.yml or paper-plugin.yml. */
    List<Map<String, Object>> pluginProjects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            for (Object s : Json.array(p.get("sourceSets"))) {
                var set = Json.object(s);
                if (!"main".equals(set.get("name"))) continue;
                Path found = null;
                for (String dir : ProjectModel.strings(set.get("resources")))
                    for (String name : List.of("paper-plugin.yml", "plugin.yml")) {
                        Path f = Path.of(dir, name);
                        if (found == null && Files.isRegularFile(f)) found = f;
                    }
                if (found != null) out.add(Json.map("path", p.get("path"), "descriptor", found.toString(), "java", ProjectModel.strings(set.get("java"))));
            }
        }
        return out;
    }
    /** The plugin's own Java release, so a plugin built for Java 25 is not run on 21. */
    int release(Map<String, Object> project) {
        int release = 17;
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item); if (!Objects.equals(p.get("path"), project.get("path"))) continue;
            for (Object c : Json.array(p.get("compile"))) {
                String value = String.valueOf(Json.object(c).get("release")).replace("1.", "");
                try { release = Math.max(release, Integer.parseInt(value)); } catch (NumberFormatException ignored) { }
            }
        }
        return release;
    }
    /** plugin.yml `libraries:` are Maven coordinates Paper downloads before loading the plugin. */
    static List<String> libraries(Path descriptor) throws IOException {
        List<String> out = new ArrayList<>(); boolean in = false;
        for (String l : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
            if (l.matches("^libraries\\s*:.*")) { in = true; continue; }
            if (in && l.matches("^\\s*-\\s*.+")) { String c = l.replaceFirst("^\\s*-\\s*", "").replaceAll("^['\"]|['\"]\\s*$", "").strip(); if (c.split(":").length >= 3 && !c.contains("${")) out.add(c); }
            else if (in && !l.isBlank() && !l.startsWith(" ") && !l.startsWith("#")) in = false;
        }
        return out;
    }

    Path tools(Runtimes.Jdk jdk, List<String> classpath, int release) throws Exception {
        List<Path> sources = new ArrayList<>(); for (String name : SOURCES) sources.add(build.harness.resolve(name));
        String key = Fingerprints.bytes((Fingerprints.files(sources) + "|" + String.join("|", classpath) + "|" + jdk.home() + "|" + release).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path directory = build.project.state().resolve("plugin-check/" + key);
        if (Files.exists(directory.resolve("ready"))) return directory.resolve("classes");
        Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(), "--release", Integer.toString(release),
                "-nowarn", "-encoding", "UTF-8", "-cp", String.join(File.pathSeparator, classpath), "-d", classes.toString()));
        sources.forEach(p -> command.add(p.toString()));
        var compiled = Run.command(command, build.project.root, Map.of(), build.report.log("plugin-check-tools"), 120);
        build.report.stage("plugin-check-tools", compiled, Map.of());
        if (compiled.exit() != 0) throw new IOException("plugin-check's MockBukkit runner did not compile against this MockBukkit. " + Run.tail(compiled.log()));
        Files.writeString(directory.resolve("ready"), "ok");
        return classes;
    }

    Map<String, Object> unchecked(String reason) {
        return Json.map("ok", false, "tier", "plugin-unchecked", "checked", false, "gameVerified", false, "reason", reason, "details", reason);
    }

    /** One line per check, failures with file:line, then what stayed unchecked and the warnings. */
    static String text(Map<String, Object> out, Path root) {
        StringBuilder b = new StringBuilder();
        var plugin = Json.object(out.getOrDefault("plugin", Json.map()));
        var server = Json.object(out.getOrDefault("server", Json.map()));
        boolean failed = Boolean.TRUE.equals(out.get("failed"));
        List<Object> unchecked = Json.array(out.get("unchecked"));
        b.append(failed ? "plugin-check FAILED" : unchecked.isEmpty() ? "plugin-check passed" : "plugin-check passed what MockBukkit can check; some parts are unchecked")
                .append(plugin.isEmpty() ? "" : " for " + plugin.get("name") + " " + plugin.get("version"))
                .append(server.isEmpty() ? "" : " on MockBukkit (Paper API " + server.get("paperApi") + ")").append(".\n");
        for (Object c : Json.array(out.get("checks"))) {
            var check = Json.object(c);
            String where = check.get("file") == null ? "" : " (" + relative(root, check.get("file").toString()) + (check.get("line") == null ? "" : ":" + check.get("line")) + ")";
            b.append("pass".equals(check.get("status")) ? "  PASS " : "  FAIL ").append(check.get("name")).append(": ").append(check.get("details")).append(where).append('\n');
        }
        for (Object u : unchecked) b.append("  UNCHECKED ").append(u).append('\n');
        for (Object w : Json.array(out.get("warnings"))) b.append("  WARN ").append(w).append('\n');
        b.append("No Minecraft server ran: this is MockBukkit's fake Paper server, so world, entity and network behaviour is not proven here.");
        return b.toString();
    }
    static String relative(Path root, String file) {
        try { Path f = Path.of(file); return f.isAbsolute() && f.startsWith(root) ? root.relativize(f).toString().replace('\\', '/') : file; }
        catch (InvalidPathException e) { return file; }
    }
}
