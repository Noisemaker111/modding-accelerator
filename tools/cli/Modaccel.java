import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Portable public entrypoint. Every result states the tier actually checked. */
public final class Modaccel {
    static final class Report {
        final Path root, directory; final String command, id = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        final long start = System.nanoTime(); final List<Object> stages = new ArrayList<>();
        Report(Path root, String command) { this.root = root; this.command = command; directory = root.resolve(".runs/portable/" + id); }
        Path log(String name) { return directory.resolve(stages.size() + "-" + name + ".log"); }
        void stage(String name, Run.Result result, Map<String, Object> extra) {
            Map<String, Object> value = Json.map("name", name, "seconds", result.seconds(), "exit", result.exit(), "log", result.log().toString());
            value.putAll(extra); stages.add(value);
        }
        Map<String, Object> finish(int exit, Map<String, Object> value) throws IOException {
            double total = (System.nanoTime() - start) / 1e9, accounted = 0;
            for (Object stage : stages) { Object seconds = Json.object(stage).get("seconds"); if (seconds instanceof Number number) accounted += number.doubleValue(); }
            var result = Json.map("schema", 1, "id", id, "command", command, "root", root.toString(), "ok", exit == 0, "exit", exit,
                    "seconds", total, "stageSeconds", accounted, "unaccountedSeconds", Math.max(0, total - accounted),
                    "stages", stages, "result", value, "receipt", directory.resolve("result.json").toString());
            Json.save(directory.resolve("result.json"), result); return result;
        }
    }
    static final class Options {
        final Map<String, String> values = new LinkedHashMap<>(); final Set<String> flags = new HashSet<>(); String command = "fast";
        Options(String[] args) {
            Set<String> switches = Set.of("json", "refresh", "help", "dry-run", "fix", "direct", "expect-world", "save-roundtrip", "jbr");
            int i = 0; if (args.length > 0 && !args[0].startsWith("-")) command = args[i++];
            if (command.equals("help") && i < args.length && !args[i].startsWith("-")) values.put("command", args[i++]);
            for (; i < args.length; i++) {
                String key = args[i]; if (!key.startsWith("--")) throw new IllegalArgumentException("Expected --option; got " + key);
                key = key.substring(2);
                if (switches.contains(key)) flags.add(key);
                else if (Set.of("root", "project", "task", "from", "to", "names", "map", "log", "rules", "rounds", "undo", "screen", "click", "click-at", "move-at", "key", "type", "assert", "expect-screen", "expect-value", "expect-change", "world", "server", "server-task", "client-task", "selection", "code", "file", "thread", "java", "until", "timeout").contains(key)) {
                    if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --" + key);
                    values.put(key, args[i]);
                } else throw new IllegalArgumentException("Unknown option --" + key);
            }
        }
        String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
        boolean has(String key) { return flags.contains(key); }
    }
    public static void main(String[] args) {
        int exit = 2; Options options = null; Report report = null;
        try {
            options = new Options(args);
            if (options.has("help") || options.command.equals("help")) { System.out.println(help(options.command.equals("help") ? options.get("command", "") : options.command)); return; }
            if (options.command.equals("support")) {
                System.out.println(options.has("json") ? Json.write(Support.features()) : Support.text());
                return;
            }
            Path home = Installation.home();
            Path root = Path.of(options.get("root", "."));
            if (Files.isRegularFile(root) && root.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                throw new IllegalArgumentException("--root needs the mod's Gradle source directory, not a compiled JAR. Check out its source and run modaccel discover --root SOURCE.");
            ProjectModel project = new ProjectModel(root);
            if (!Set.of("package", "self-test").contains(options.command)) project.requireGradleBuild();
            report = new Report(project.root, options.command); Map<String, Object> result;
            long discovered = System.nanoTime();
            if (options.command.equals("discover")) {
                result = project.discover(); result.put("support", Support.discovered(result)); exit = 0;
                if (Json.array(result.get("builds")).isEmpty()) result.put("details", "No build.gradle or build.gradle.kts file was found, so there are no build hints"
                        + (Boolean.TRUE.equals(result.get("stonecutter")) ? " (a Stonecutter build names its scripts build.<variant>.gradle.kts)" : "")
                        + ". Run modaccel setup to evaluate the build itself.");
            }
            else if (options.command.equals("package")) { result = Installation.pack(home, report); exit = 0; }
            else if (options.command.equals("self-test")) { result = Installation.test(home, report); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1; }
            else if (options.command.equals("doctor")) {
                result = project.discover(); result.put("support", Support.discovered(result)); result.put("jdks", Runtimes.installed(home).stream().map(j -> Json.map("home", j.home().toString(), "version", j.version())).toList());
                result.put("selectedJava", Runtimes.forGradle(home, project.wrapperVersion()).home().toString()); exit = 0;
            } else if (options.command.equals("port") && !options.values.containsKey("undo")
                    && (result = Route.blocked(home, options.get("from", ""), options.get("to", ""))) != null) {
                // Refuse before evaluating a build that predates official names; nothing is changed.
                exit = 1;
            } else {
                Build build = new Build(project, home, options.get("project", ""), report);
                Map<String, Object> model = Set.of("game-status", "game-stop", "eval").contains(options.command) || options.command.equals("port") && options.values.containsKey("undo") ? Json.map()
                        : build.model(options.has("refresh"), options.command.equals("port"));
                switch (options.command) {
                    case "setup" -> { result = summary(model, project); exit = 0; }
                    case "fast" -> { result = FastLane.run(build, model, options); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1; }
                    case "ui-check", "world-check", "mp-check", "game-status", "game-stop", "eval", "reload" -> { result = new Game(build, model).run(options); exit = "reload".equals(options.command) && Boolean.TRUE.equals(result.get("restartNeeded")) ? 1 : 0; }
                    case "port" -> { result = new Port(build, model).run(options); if (!options.values.containsKey("undo")) result.put("support", Support.evaluated(model)); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1; }
                    case "dependencies" -> { result = new Port(build, model).dependencies(options); exit = Boolean.TRUE.equals(result.get("ready")) ? 0 : 1; }
                    case "compile", "test" -> {
                        String name = options.command.equals("compile") ? "classes" : options.command.equals("test") ? "test" : "modaccelPure";
                        List<String> tasks = options.values.containsKey("task") ? List.of(options.get("task", "")) : build.tasks(model, name);
                        if (tasks.isEmpty()) throw new IOException("No " + name + " task in the selected projects. Select --project :module or --task :module:task.");
                        // A NeoGradle mod whose game jar is not built yet compiles against NeoForge's installer-made jar.
                        if (options.command.equals("compile") && !options.values.containsKey("task") && (result = OwnCompile.run(build, model, tasks)) != null) { exit = 0; break; }
                        Run.Result run = build.gradle(tasks, options.command); exit = run.exit() == 0 ? 0 : 1;
                        result = Json.map("tier", options.command.equals("compile") ? "compile" : "logic", "tasks", tasks, "log", run.log().toString(),
                                "gameVerified", false, "details", Run.tail(run.log()));
                        if (run.exit() != 0) result.put("reason", Run.gradleFailure(run.log()) + "\nLog: " + run.log());
                        if (options.command.equals("fast") || options.command.equals("test")) {
                            Map<String, Object> counts = TestResults.readTasks(model, tasks, report.id);
                            result.put("tests", counts); if (((Number)counts.get("failed")).intValue() > 0) exit = 1;
                            if (((Number)counts.get("executed")).intValue() == 0) { exit = 1; result.put("reason", "No tests executed; add meaningful tests before claiming logic verified."); }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown command " + options.command + ". " + help());
                }
            }
            Map<String, Object> output = report.finish(exit, result);
            if (options.has("json")) System.out.println(Json.write(output));
            else {
                System.out.printf("%s: %s (%.2f seconds)%n", options.command, exit == 0 ? "passed" : "failed", output.get("seconds"));
                if (options.command.equals("setup") || options.command.equals("discover") || options.command.equals("doctor")) System.out.println(Json.write(result));
                else System.out.println(result.getOrDefault("reason", result.getOrDefault("details", "")));
                if (result.containsKey("undo")) System.out.println("Undo: modaccel port --root \"" + project.root + "\" --undo \"" + result.get("undo") + "\"");
                if (result.containsKey("zip")) System.out.println("Package: " + result.get("zip"));
                System.out.println("Receipt: " + output.get("receipt"));
            }
        } catch (Exception error) {
            try {
                String reported = error.getMessage() == null ? error.toString() : error.getMessage();
                // A dedicated server that died mid-check is the likelier cause of a client failure: say so with the error.
                if (report != null) for (Object stage : report.stages)
                    if ("server-exit".equals(Json.object(stage).get("name"))) reported += "\n" + Json.object(stage).get("details");
                var value = Json.map("error", reported);
                if (report != null) value = report.finish(2, value);
                if (options != null && options.has("json")) System.out.println(Json.write(value));
                else if (options != null && options.command.equals("eval")) {
                    // A snippet's own error doesn't need the game log; a game that died does.
                    String message = String.valueOf(error.getMessage());
                    if (message.startsWith("Game action failed: ")) message = message.substring("Game action failed: ".length()).replaceFirst("\nGame log: .*$", "");
                    System.err.println("eval: failed\n" + message);
                }
                else System.err.println(Json.write(value));
            } catch (IOException secondary) { System.err.println(error + "; could not save receipt: " + secondary); }
            exit = 2;
        }
        System.exit(exit);
    }
    static String help() { return help(""); }
    static String help(String command) {
        return switch (command) {
            case "port" -> "Port a Gradle mod source checkout (not a JAR):\n"
                    + "  modaccel discover --root SOURCE\n"
                    + "  modaccel dependencies --root SOURCE --to 1.21.6 --json\n"
                    + "  modaccel port --root SOURCE --from 1.21.5 --to 1.21.6 --fix --json\n"
                    + "  modaccel port --root SOURCE --undo TRANSACTION\n"
                    + "A port across several families routes FROM -> each family hub between -> TO (coverage/changes/index.json), joins every update's renames into one map, runs only the knowledge rules whose jump covers an update it crosses, builds once, and names the update behind each remaining error. --direct keeps the one-jump map. Releases before 1.14.4 are refused (no official names).\n"
                    + "Fabric pins and verifiable published dependencies are retargeted automatically. When discovery cannot identify a dependency, add a verified selection to .modaccel/dependencies.json (or --selection FILE): source, target coordinate, and modrinth or repository evidence. A dependency with no target release can be disabled with disable:true, a reason and its published project; importing sources and entrypoints are removed with the port and restored by undo. Other loaders need target dependencies set in the source build first.";
            case "dependencies" -> "Report target dependencies without editing: modaccel dependencies --root SOURCE --to 1.21.6 [--selection FILE] --json\nEach non-loader dependency is resolved, explicitly selected, or blocked with the selection entry to add in .modaccel/dependencies.json.";
            case "ui-check" -> "Capture the target client: modaccel ui-check --root SOURCE --screen net.minecraft.client.gui.screen.TitleScreen --json\nInspect the receipt's framebuffer capture; add --assert METHOD for behavior checks, or --expect-change PERCENT to require the action to visibly change the frame.\n"
                    + "--screen opens a new instance of that screen; leave it out to capture what the running client shows now.\n"
                    + "--java JAVA_HOME (ui-check or world-check) starts the client on that runtime instead of the build's, for failures that only happen on one Java build.";
            case "reload" -> "Recompile and swap the changes into the running game: modaccel reload --root SOURCE --project :module\n"
                    + "Changed method bodies swap in place; changed resources reload the resource packs; classes the game hasn't loaded yet\n"
                    + "are read on first use. A changed class shape (added or removed methods or fields) on a stock JVM, a mixin, or mod\n"
                    + "metadata needs a restart: the command says so and exits 1. Objects keep their field values and static initializers do not rerun.\n"
                    + "Start the game with --jbr (ui-check or world-check) to run it on JetBrains Runtime, which also swaps added or removed methods and fields.";
            case "eval" -> "Run Java inside the running game: modaccel eval --root SOURCE --code 'mc.player.getX()'\n"
                    + "  --file SNIPPET.java  read the snippet from a file instead\n"
                    + "  --thread control     run off the game thread (for snippets that wait); default is the game thread\n"
                    + "  --until CONDITION    run CONDITION every 0.25 s until it returns true, e.g. --until 'mc.level != null';\n"
                    + "                       --timeout SECONDS (default 60, at most 120) fails it if it never does\n"
                    + "An expression is returned; statements return what they return. 'mc' is the Minecraft instance, print(x) adds output,\n"
                    + "vars is a map kept between snippets; call(objectOrClass, \"name\", args...) and field(objectOrClass, \"name\") reach private\n"
                    + "members. Leading import lines are allowed. Start the game first with ui-check or world-check.";
            case "world-check" -> "Check a loaded save: modaccel world-check --root SOURCE --world lab --assert METHOD --json\nThe save must exist in this project's game directory. Inspect the receipt's framebuffer capture; --expect-change PERCENT requires a visible result from the key or click.";
            default -> "modaccel <support|discover|doctor|setup|compile|fast|test|port|dependencies|ui-check|world-check|mp-check|eval|reload|game-status|game-stop|package|self-test> [--root SOURCE] [--project :module] [--task :task] [--refresh] [--json]\n"
                    + "Use modaccel help port or modaccel port --help for port, undo and client examples. Input is a Gradle source checkout with a wrapper.";
        };
    }
    static Map<String, Object> summary(Map<String, Object> model, ProjectModel owner) {
        List<Object> projects = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item); Set<String> loaders = new TreeSet<>();
            for (Object dep : Json.array(p.get("dependencies"))) {
                String group = Json.text(Json.object(dep), "group", "");
                if (group.equals("net.fabricmc")) loaders.add("fabric");
                if (group.startsWith("net.neoforged")) loaders.add("neoforge");
                if (group.equals("net.minecraftforge")) loaders.add("forge");
            }
            projects.add(Json.map("path", p.get("path"), "directory", p.get("directory"), "loaders", loaders, "pins", p.get("pins"),
                    "sourceSets", Json.array(p.get("sourceSets")).stream().map(s -> Json.object(s).get("name")).toList(), "tasks", p.get("tasks")));
        }
        return Json.map("authoritative", true, "gradle", model.get("gradle"), "java", model.get("java"), "projects", projects, "support", Support.evaluated(model),
                "model", owner.state().resolve("model.json").toString(), "buildFilesChanged", false);
    }
}
