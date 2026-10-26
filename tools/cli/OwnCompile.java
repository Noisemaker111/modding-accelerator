import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/**
 * First compile of a NeoGradle mod without NeoForm's decompile, source patch and recompile of the whole game
 * (about five minutes cold). NeoForge's own installer, the one players run, renames Mojang's client jar and
 * applies NeoForge's binary patches; tools/GamePrep.java adds the access transformers and caches the result
 * once per NeoForge version per machine. Then javac compiles the mod's source sets with the classpath Gradle
 * reported, the game jar swapped in. Everything is built here from Mojang's and NeoForge's downloads.
 *
 * Gradle stays the authority: this runs only while NeoForm's game jar is not built yet, writes nothing into
 * the user's build folder (classes go under .modaccel/portable/own-compile), and any doubt or failure,
 * compile errors included, falls back to the Gradle compile.
 */
final class OwnCompile {
    static final Pattern NEOGRADLE_GAME = Pattern.compile("[\\\\/]ng_dummy_ng[\\\\/]net[\\\\/]neoforged[\\\\/]neoforge[\\\\/]([^\\\\/]+)[\\\\/]neoforge-\\1\\.jar$");

    /** Why the own compile does not apply, or null when it does. */
    static String skipReason(Map<String, Object> model, List<String> tasks) throws IOException {
        if ("false".equals(System.getenv("MODACCEL_OWN_GAME"))) return "MODACCEL_OWN_GAME=false";
        if (neoforgeVersion(model) == null) return "not a NeoGradle build waiting on NeoForm";
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            for (String plugin : ProjectModel.strings(p.get("plugins"))) if (plugin.toLowerCase(Locale.ROOT).contains("kotlin") || plugin.toLowerCase(Locale.ROOT).contains("groovy")) return "non-Java sources (" + plugin + ")";
            Path buildFile = Path.of(Json.text(p, "buildFile", ""));
            if (Files.isRegularFile(buildFile)) for (String line : Files.readAllLines(buildFile, StandardCharsets.UTF_8)) {
                String code = line.replaceAll("//.*", "");
                if (code.contains("accessTransformer") || code.contains("interfaceInjection")) return "the build configures access transformers or injected interfaces itself";
            }
        }
        return null;
    }
    /** The NeoForge version a NeoGradle compile classpath waits on, while its game jar is not built yet. */
    static String neoforgeVersion(Map<String, Object> model) throws IOException {
        for (Object item : Json.array(model.get("projects")))
            for (Object c : Json.array(Json.object(item).get("compile")))
                for (String path : ProjectModel.strings(Json.object(c).get("classpath"))) {
                    Matcher m = NEOGRADLE_GAME.matcher(path); if (!m.find()) continue;
                    if (built(Path.of(path))) return null;
                    return m.group(1);
                }
        return null;
    }
    static boolean built(Path jar) {
        if (!Files.isRegularFile(jar)) return false;
        try (var zip = new ZipFile(jar.toFile())) { return zip.getEntry("net/minecraft/client/Minecraft.class") != null; } catch (IOException e) { return false; }
    }

    /** Compiles the source sets behind the given classes tasks; null when the caller should run the Gradle compile. */
    static Map<String, Object> run(Build build, Map<String, Object> model, List<String> tasks) throws Exception {
        String skip = skipReason(model, tasks);
        if (skip != null) { build.report.stages.add(Json.map("name", "own-game", "used", false, "reason", skip, "seconds", 0)); return null; }
        String version = neoforgeVersion(model);
        List<Path> ownTransformers = new ArrayList<>();
        List<Map<String, Object>> units = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item); String prefix = p.get("path").equals(":") ? "" : p.get("path").toString();
            Map<String, Map<String, Object>> compiles = new HashMap<>();
            for (Object c : Json.array(p.get("compile"))) { var compile = Json.object(c); compiles.put(Json.text(compile, "task", ""), compile); }
            for (Object s : Json.array(p.get("sourceSets"))) {
                var set = Json.object(s); String name = Json.text(set, "name", "");
                if (!tasks.contains(prefix + ":" + Json.text(set, "classesTask", ""))) continue;
                String task = prefix + ":compile" + (name.equals("main") ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1)) + "Java";
                var compile = compiles.get(task);
                if (compile == null) return fallback(build, "no " + task + " in the model");
                units.add(compile);
                if (name.equals("main")) for (String resources : ProjectModel.strings(set.get("resources"))) {
                    Path at = Path.of(resources, "META-INF", "accesstransformer.cfg"); if (Files.isRegularFile(at)) ownTransformers.add(at);
                }
            }
        }
        if (units.isEmpty()) return fallback(build, "no Java source set behind " + tasks);
        units.sort(Comparator.comparing(u -> Json.text(u, "task", "").endsWith(":compileJava") ? 0 : 1));

        JavaTools tools = new JavaTools(build);
        List<String> args = new ArrayList<>(List.of("neoforge-install", version)); ownTransformers.forEach(f -> args.add(f.toString()));
        Run.Result prep = tools.run("GamePrep", args, "own-game");
        if (prep.exit() != 0) return fallback(build, "game prep failed: " + Run.tail(prep.log()));
        String jar = null, universal = null;
        for (String line : Files.readAllLines(prep.log(), StandardCharsets.UTF_8)) {
            if (line.startsWith("jar=")) jar = line.substring(4); else if (line.startsWith("universal=")) universal = line.substring(10);
        }
        if (jar == null || universal == null) return fallback(build, "game prep printed no jar");

        Path outputs = build.project.state().resolve("own-compile").toAbsolutePath();
        Map<String, String> moved = new HashMap<>(); List<Map<String, Object>> compiled = new ArrayList<>();
        for (var unit : units) {
            String task = Json.text(unit, "task", "");
            Path out = outputs.resolve(task.replace(':', '_')); deleteTree(out); Files.createDirectories(out);
            List<String> classpath = new ArrayList<>();
            for (String path : ProjectModel.strings(unit.get("classpath"))) {
                if (NEOGRADLE_GAME.matcher(path).find()) { classpath.add(jar); classpath.add(universal); }
                else if (path.contains("ng_dummy_ng") && !Files.exists(Path.of(path))) continue; // NeoForm's resource jar: not needed by javac
                else classpath.add(moved.getOrDefault(path, path));
            }
            List<String> sources = ProjectModel.strings(unit.get("sources")).stream().filter(f -> f.endsWith(".java")).toList();
            if (sources.isEmpty()) { moved.put(Json.text(unit, "destination", ""), out.toString()); continue; }
            String release = String.valueOf(unit.get("release")).replace("1.", "");
            List<String> javacArgs = new ArrayList<>(List.of("-d", out.toString(), "-g", "-encoding", Json.text(unit, "encoding", "UTF-8").isEmpty() ? "UTF-8" : Json.text(unit, "encoding", "UTF-8")));
            if (!release.isBlank() && !release.equals("null")) javacArgs.addAll(List.of("--release", release));
            List<String> processors = ProjectModel.strings(unit.get("processors"));
            if (processors.isEmpty()) javacArgs.add("-proc:none"); else javacArgs.addAll(List.of("-processorpath", String.join(File.pathSeparator, processors)));
            javacArgs.addAll(ProjectModel.strings(unit.get("arguments")));
            javacArgs.addAll(List.of("-cp", String.join(File.pathSeparator, classpath)));
            javacArgs.addAll(sources);
            Path argfile = outputs.resolve(task.replace(':', '_') + ".args");
            Files.write(argfile, javacArgs.stream().map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList(), StandardCharsets.UTF_8);
            Path javac = Path.of(Json.text(unit, "compiler", ""));
            if (!Files.isRegularFile(javac)) {
                int wanted = 17; try { wanted = Integer.parseInt(release); } catch (NumberFormatException ignored) { }
                javac = Runtimes.ensureAtLeast(build.harness, wanted).home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac"));
            }
            Run.Result result = Run.command(List.of(javac.toString(), "@" + argfile), build.project.root, Map.of(), build.report.log("own-" + task.replace(':', '_')), 900);
            build.report.stage("own-compile", result, Json.map("task", task, "classes", out.toString(), "sources", sources.size()));
            if (result.exit() != 0) return fallback(build, task + " did not compile against the installer-made game jar: " + Run.failure(result.log()));
            moved.put(Json.text(unit, "destination", ""), out.toString());
            compiled.add(Json.map("task", task, "classes", out.toString(), "sources", sources.size()));
        }
        return result(tasks, version, jar, universal, ownTransformers, compiled, outputs);
    }
    /** modaccel's own compile, not Gradle's: it says so, and port and ledger verdicts never use it. */
    static Map<String, Object> result(List<String> tasks, String version, Object jar, Object universal, List<Path> ownTransformers, List<?> compiled, Path outputs) {
        return Json.map("tier", "own-compile", "compiledBy", "modaccel (own javac against its own game prep), not Gradle", "gradleConfirmed", false,
                "tasks", tasks, "gameVerified", false, "ownGame", Json.map("neoforge", version, "jar", jar, "universal", universal, "transformers", ownTransformers.stream().map(Path::toString).toList()),
                "compiled", compiled, "details", "Own compile (modaccel's javac, not Gradle; Gradle has not confirmed it): compiled " + compiled.size() + " source set(s) against NeoForge " + version
                        + "'s installer-made game jar, without NeoForm's decompile and recompile. Classes: " + outputs
                        + ". Resources were not processed; set MODACCEL_OWN_GAME=false for the Gradle compile.");
    }
    static Map<String, Object> fallback(Build build, String reason) {
        build.report.stages.add(Json.map("name", "own-game", "used", false, "reason", reason, "seconds", 0));
        System.err.println("Note: compiling through Gradle instead (" + reason.lines().findFirst().orElse(reason) + ").");
        return null;
    }
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
}
