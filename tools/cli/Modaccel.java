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
                // --undo with no transaction undoes the newest port.
                else if (key.equals("undo") && (i + 1 == args.length || args[i + 1].startsWith("--"))) values.put(key, "latest");
                else if (Set.of("root", "project", "task", "from", "to", "names", "map", "log", "rules", "rounds", "undo", "screen", "click", "click-at", "move-at", "key", "type", "assert", "expect-screen", "expect-value", "expect-change", "world", "server", "server-task", "client-task", "selection", "code", "file", "thread", "java", "until", "timeout", "against", "only").contains(key)) {
                    if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --" + key);
                    values.put(key, args[i]);
                } else throw new IllegalArgumentException("Unknown option --" + key + ". Run modaccel help" + (command.equals("help") ? "" : " " + command) + " for the options it takes.");
            }
            // Gradle project paths start with ':'; "26.3" alone would be read as a task name.
            for (String key : List.of("project", "against")) if (values.containsKey(key)) {
                List<String> paths = new ArrayList<>();
                for (String path : values.get(key).split(",")) { path = path.strip(); if (!path.isEmpty()) paths.add(path.startsWith(":") ? path : ":" + path); }
                values.put(key, String.join(",", paths));
            }
        }
        String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
        boolean has(String key) { return flags.contains(key); }
    }
    static final Set<String> COMMANDS = Set.of("support", "discover", "doctor", "setup", "compile", "fast", "test", "port", "explain", "dependencies", "mixin-check",
            "ui-check", "world-check", "mp-check", "eval", "reload", "game-status", "game-stop", "package", "self-test", "help");
    public static void main(String[] args) {
        int exit = 2; Options options = null; Report report = null; ProjectLock lock = null;
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
            if (!COMMANDS.contains(options.command)) throw new IllegalArgumentException(unknownCommand(options.command));
            ProjectModel project = new ProjectModel(root);
            if (!Set.of("package", "self-test").contains(options.command)) project.requireGradleBuild();
            // Commands that build, edit or start the game take the project lock; reading state and talking to a running game don't.
            if (!Set.of("discover", "doctor", "package", "self-test", "game-status", "game-stop", "eval").contains(options.command)) lock = ProjectLock.acquire(project, options.command);
            report = new Report(project.root, options.command); Map<String, Object> result;
            String interrupted = options.command.equals("port") ? null : Port.interruptedText(project);
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
            } else if (options.command.equals("dependencies") && Route.unknownRelease(home, options.get("to", "")) != null) {
                result = Json.map("ready", false, "reason", Route.unknownRelease(home, options.get("to", ""))); exit = 1;
            } else {
                // explain reads two projects' classpaths: the one that fails and the one it's compared with.
                String selected = options.command.equals("explain") && options.values.containsKey("against") ? options.get("project", "") + "," + options.get("against", "") : options.get("project", "");
                Build build = new Build(project, home, selected, report);
                Map<String, Object> model = Set.of("game-status", "game-stop", "eval").contains(options.command) || options.command.equals("port") && options.values.containsKey("undo") ? Json.map()
                        : build.model(options.has("refresh"), options.command.equals("port"));
                switch (options.command) {
                    case "setup" -> { result = summary(model, project); exit = 0; }
                    case "fast" -> {
                        result = FastLane.run(build, model, options); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                        if (exit == 0) exit = withModChecks(result, model);
                    }
                    case "ui-check", "world-check", "mp-check", "game-status", "game-stop", "eval", "reload" -> { result = new Game(build, model).run(options); exit = "reload".equals(options.command) && Boolean.TRUE.equals(result.get("restartNeeded")) ? 1 : 0; }
                    case "port" -> { result = new Port(build, model).run(options); if (!options.values.containsKey("undo")) result.put("support", Support.evaluated(model)); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1; }
                    case "explain" -> { result = new Explain(build, model).run(options); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1; }
                    case "mixin-check" -> {
                        result = new MixinCheck(build, model).run(options); exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                        // Mixin applies a require = 0 injection that matches nothing without a word; the audit against the game jar names it.
                        if (Boolean.TRUE.equals(result.get("checked"))) {
                            var audit = new JavaTools(build).audit(model);
                            String optional = optionalInjections(Files.readAllLines(audit.log(), java.nio.charset.StandardCharsets.UTF_8));
                            if (!optional.isEmpty() && !(result.get("optional") instanceof List<?> seen && !seen.isEmpty())) { result.put("details", result.get("details") + "\n" + optional); if (result.get("reason") != null) result.put("reason", result.get("reason") + "\n" + optional); }
                        }
                    }
                    case "dependencies" -> { result = new Port(build, model).dependencies(options); exit = Boolean.TRUE.equals(result.get("ready")) ? 0 : 1; }
                    case "compile", "test" -> {
                        String name = options.command.equals("compile") ? "classes" : options.command.equals("test") ? "test" : "modaccelPure";
                        List<String> tasks = options.values.containsKey("task") ? List.of(options.get("task", "")) : build.tasks(model, name);
                        if (tasks.isEmpty()) throw new IOException("No " + name + " task in the selected projects. Select --project :module or --task :module:task.");
                        // A NeoGradle mod whose game jar is not built yet compiles against NeoForge's installer-made jar.
                        if (options.command.equals("compile") && !options.values.containsKey("task") && (result = OwnCompile.run(build, model, tasks)) != null) { exit = 0; break; }
                        Run.Result run = build.gradle(tasks, options.command); exit = run.exit() == 0 ? 0 : 1;
                        result = Json.map("tier", options.command.equals("compile") ? "compile" : "logic", "tasks", tasks, "log", run.log().toString(),
                                "gameVerified", false, "details", options.command.equals("compile") ? "Compiled " + String.join(", ", tasks) + "." : Run.tail(run.log()));
                        if (run.exit() != 0) result.put("reason", Run.gradleFailure(run.log(), model) + "\nLog: " + run.log());
                        if (options.command.equals("test") && run.exit() == 0) {
                            Map<String, Object> counts = TestResults.readTasks(model, tasks, report.id);
                            result.put("tests", counts); result.put("details", TestResults.text(counts));
                            if (((Number)counts.get("failed")).intValue() > 0) exit = 1;
                            if (((Number)counts.get("executed")).intValue() == 0) { exit = 1; result.put("reason", FastLane.noTests(model, false)); }
                        }
                        if (exit == 0) exit = withModChecks(result, model);
                        // A mixin aimed at a method that does not exist compiles; say which check finds it.
                        if (exit == 0 && result.get("modChecks") instanceof Map<?, ?> checks && checks.get("mixinClasses") instanceof Number mixins && mixins.intValue() > 0)
                            result.put("details", result.get("details") + "\nMixin targets are not checked by " + options.command + ": modaccel mixin-check lets Mixin itself apply them, without starting the game.");
                    }
                    default -> throw new IllegalArgumentException("Unknown command " + options.command + ". " + help());
                }
            }
            if (interrupted != null) result.put("warning", interrupted + " Undo it with: modaccel port --root \"" + project.root + "\" --undo");
            Map<String, Object> output = report.finish(exit, result);
            if (options.has("json")) System.out.println(Json.write(output));
            else {
                System.out.printf("%s: %s (%.2f seconds)%n", options.command, exit == 0 ? "passed" : "failed", output.get("seconds"));
                String text = switch (options.command) {
                    case "setup" -> setupText(result, project);
                    case "discover", "doctor" -> discoverText(result, project, options.command);
                    default -> String.valueOf(result.getOrDefault("reason", result.getOrDefault("details", "")));
                };
                if (!text.isBlank()) System.out.println(text.strip());
                if (result.get("warning") != null) System.out.println("Warning: " + result.get("warning"));
                if (result.containsKey("undo")) System.out.println("Undo: modaccel port --root \"" + project.root + "\" --undo \"" + result.get("undo") + "\"");
                if (result.containsKey("zip")) System.out.println("Package: " + result.get("zip"));
                System.out.println("Receipt: " + output.get("receipt"));
            }
        } catch (Exception error) {
            try {
                String reported = plainError(error);
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
                else {
                    // Words first; the receipt keeps the stages and timings for anyone who wants them.
                    System.err.println((options == null ? "modaccel" : options.command) + ": failed\n" + reported.strip());
                    if (value.get("receipt") != null) System.err.println("Receipt: " + value.get("receipt"));
                }
            } catch (IOException secondary) { System.err.println(error + "; could not save receipt: " + secondary); }
            exit = 2;
        } finally {
            if (lock != null) try { lock.close(); } catch (IOException ignored) { }
        }
        System.exit(exit);
    }
    /** An exception as a sentence: file-system errors whose message is only a path get their meaning back. */
    /** An unknown command, with the nearest real one when it is a slip of the keyboard. */
    static String unknownCommand(String command) {
        String nearest = null; int best = Integer.MAX_VALUE;
        for (String known : new TreeSet<>(COMMANDS)) { int d = Explain.distance(known, command.toLowerCase(Locale.ROOT)); if (d < best) { best = d; nearest = known; } }
        return "Unknown command " + command + "." + (nearest != null && best <= 2 ? " Did you mean " + nearest + "?" : "") + "\n" + help();
    }
    private static final java.util.regex.Pattern NO_PROJECT = java.util.regex.Pattern.compile("No project (\\S+); available projects: \\[([^\\]]*)\\]");
    static String plainError(Throwable error) {
        // Gradle's own words for a --project that is not in the build, buried under "Could not create task": say it plainly.
        java.util.regex.Matcher project = error.getMessage() == null ? null : NO_PROJECT.matcher(error.getMessage());
        if (project != null && project.find()) {
            List<String> available = new ArrayList<>(); for (String path : project.group(2).split(",\\s*")) if (!path.isBlank() && !path.equals(":")) available.add(path.strip());
            return "There is no project " + project.group(1) + " in this build. It has " + available.size() + ": " + String.join(", ", available)
                    + ".\nPick one with --project (modaccel setup lists them with their loader and Minecraft version).";
        }
        // Port wraps its failure to add the undo line; the file-system cause underneath still needs words.
        Throwable cause = error;
        while (cause != null && !(cause instanceof FileSystemException)) cause = cause.getCause() == cause ? null : cause.getCause();
        if (cause == null) return error.getMessage() == null ? error.toString() : error.getMessage();
        String undo = error != cause && error.getMessage() != null && error.getMessage().contains("\nUndo this port with:") ? error.getMessage().substring(error.getMessage().indexOf("\nUndo this port with:")) : "";
        if (cause instanceof AccessDeniedException denied)
            return "Cannot write " + denied.getFile() + ": access was denied. The file is read-only, or another program (an editor, the game, antivirus) has it open."
                    + " Clear the read-only flag or close that program, then rerun." + undo;
        if (cause instanceof NoSuchFileException missing) return "No such file or folder: " + missing.getFile() + (missing.getReason() == null ? "" : " (" + missing.getReason() + ")") + "." + undo;
        if (cause instanceof FileSystemException other && other.getReason() != null && other.getReason().toLowerCase(Locale.ROOT).contains("used by another process"))
            return "Cannot use " + other.getFile() + ": another program has it open. Close it (the game, an editor or a file viewer), then rerun." + undo;
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
    /** MixinAudit's require = 0 notes, as the injections that will never run; "" when there are none. */
    static String optionalInjections(List<String> auditLines) {
        List<String> optional = new ArrayList<>();
        for (String line : auditLines) if (line.contains("optional (require = 0)")) optional.add(line.strip().replaceFirst("^note:\\s*", "").replace("optional (require = 0): ", ""));
        if (optional.isEmpty()) return "";
        return optional.size() + (optional.size() == 1 ? " optional injection" : " optional injections") + " (require = 0) found no target in this game, so Mixin skips "
                + (optional.size() == 1 ? "it" : "them") + " without an error and " + (optional.size() == 1 ? "it never runs" : "they never run") + ":\n  " + String.join("\n  ", optional);
    }
    /** Mod resource and metadata checks after a passing compile or test run; problems turn the pass into a failure. */
    static int withModChecks(Map<String, Object> result, Map<String, Object> model) throws IOException {
        Map<String, Object> checks = ModChecks.run(model);
        result.put("modChecks", checks);
        String text = ModChecks.text(checks);
        String details = String.valueOf(result.getOrDefault("details", ""));
        result.put("details", details + (details.isBlank() ? "" : "\n") + "Checked resources and metadata: " + checks.get("checked") + "." + (text.isEmpty() ? "" : "\n" + text));
        if (Boolean.TRUE.equals(checks.get("ok"))) return 0;
        result.put("reason", result.get("details"));
        return 1;
    }
    static String discoverText(Map<String, Object> result, ProjectModel project, String command) {
        StringBuilder out = new StringBuilder();
        out.append("Gradle ").append(result.getOrDefault("gradle", "?")).append(" build at ").append(project.root);
        if (Boolean.TRUE.equals(result.get("stonecutter"))) out.append("\nStonecutter: one source tree built for several Minecraft versions; each version is its own project (select one with --project).");
        for (Object item : Json.array(result.getOrDefault("builds", List.of()))) {
            var hint = Json.object(item); List<String> parts = new ArrayList<>();
            for (String key : List.of("loader", "loaders", "minecraft", "path", "file")) if (hint.get(key) != null && !String.valueOf(hint.get(key)).isBlank()) parts.add(key + " " + hint.get(key));
            if (!parts.isEmpty()) out.append("\n  ").append(String.join(", ", parts));
        }
        if (command.equals("doctor")) {
            out.append("\nJava for this build: ").append(result.getOrDefault("selectedJava", "?"));
            List<String> installed = new ArrayList<>();
            for (Object jdk : Json.array(result.getOrDefault("jdks", List.of()))) installed.add(String.valueOf(Json.object(jdk).get("version")));
            if (!installed.isEmpty()) out.append("\nJDKs found: ").append(String.join(", ", installed));
        }
        if (result.get("details") != null) out.append('\n').append(result.get("details"));
        out.append("\nNext: modaccel setup --root \"").append(project.root).append("\" evaluates the build (a minute or more the first time). Add --json for every field.");
        return out.toString();
    }
    static String setupText(Map<String, Object> result, ProjectModel project) {
        StringBuilder out = new StringBuilder("Gradle " + result.getOrDefault("gradle", "?") + ", Java " + result.getOrDefault("java", "?"));
        List<Object> projects = Json.array(result.getOrDefault("projects", List.of()));
        List<String> mods = new ArrayList<>();
        for (Object item : projects) {
            var p = Json.object(item);
            // summary() keeps loaders as a set, and a model read back from JSON has a list: take either.
            Set<String> loaders = new TreeSet<>();
            if (p.get("loaders") instanceof Collection<?> named) for (Object loader : named) loaders.add(String.valueOf(loader));
            if (loaders.isEmpty()) continue;
            Object minecraft = p.get("pins") instanceof Map<?, ?> pins ? pins.get("minecraft_version") : null;
            mods.add(p.get("path") + "  " + String.join(", ", loaders) + (minecraft == null ? "" : ", Minecraft " + minecraft));
        }
        out.append("\n").append(mods.size()).append(mods.size() == 1 ? " mod project:" : " mod projects:");
        for (String mod : mods) out.append("\n  ").append(mod);
        if (mods.size() > 1) {
            String first = mods.get(mods.size() - 1).split("\\s+")[0];
            out.append("\nCommands work on one of them at a time: add --project, e.g. modaccel compile --root \"").append(project.root).append("\" --project ").append(first);
        } else out.append("\nNext: modaccel compile --root \"").append(project.root).append("\", then fast for tests.");
        out.append("\nModel: ").append(result.get("model")).append(" (add --json for every task and pin)");
        return out.toString();
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
            case "explain" -> "Explain a port's compiler errors: modaccel explain --root SOURCE --project :failing --against :building [--only TEXT] [--log FILE] [--task :p:task]\n"
                    + "Compiles the failing project (or reads --log), maps errors in Stonecutter's generated sources back to src/, and for each one shows the\n"
                    + "class, method or field it names as each project's jars have it: where a missing class lives, a member's signatures, or similar names.\n"
                    + "--only keeps errors whose source path contains TEXT.";
            case "mixin-check" -> "Let Mixin itself apply the mod's mixins, without starting the game: modaccel mixin-check --root SOURCE [--project :module] [--client-task :p:runClient] [--refresh] --json\n"
                    + "The build's client launch is recorded once per build (Gradle compiles and prepares it, without downloading game assets), then started with\n"
                    + "the loader set up as its own JUnit support does (Fabric: Knot, as fabric-loader-junit; NeoForge: the forgejunituserdev target, as junit-fml).\n"
                    + "Every class the mod's mixin configs target is loaded, so Mixin applies the mixins; nothing opens a window or a world. Mixin's debug\n"
                    + "injector check is on, so an injection that matches fewer places than it expects is reported as dead. Each finding is Mixin's own message,\n"
                    + "with the mixin's source file. Exit 1 when a mixin would stop the game or is dead; --refresh records the launch again.";
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
            case "fast", "test" -> "Run the mod's logic tests without the game: modaccel fast --root SOURCE [--project :module]\n"
                    + "fast runs JUnit 5 tests, reusing compiled code when nothing changed. With no Minecraft or loader on the test classpath\n"
                    + "(plain Java, a Paper/Bukkit plugin) every test runs; with Minecraft present only tests tagged @Tag(\"pure\") run.\n"
                    + "test runs the project's whole test task. Both fail when no test ran, and check the mod's resources and metadata afterwards.\n"
                    + "The build needs JUnit: testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\") and testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:1.11.4\").";
            case "compile" -> "Compile the mod and check what the game reads at start-up: modaccel compile --root SOURCE [--project :module]\n"
                    + "After javac, every JSON file in the resources must parse, fabric.mod.json / neoforge.mods.toml must name a valid mod id, mixin\n"
                    + "configs and their classes, and entrypoints that exist, and the code's MOD_ID must match the metadata. Items made by a recipe\n"
                    + "without a translation or model are warnings.\n"
                    + "Build a jar with the project's own task: modaccel compile --root SOURCE --task :jar. That is the build path for\n"
                    + "Paper/Bukkit plugins (the jar lands in build/libs); then modaccel plugin-check loads it.";
            default -> "modaccel <command> --root SOURCE [--project :module] [--json]\n"
                    + "  support                what works today        discover, doctor   what kind of build this is\n"
                    + "  setup                  evaluate the build      compile            compile + resource/metadata checks\n"
                    + "  fast, test             logic tests, no game    mixin-check        Mixin applies the mixins, no game\n"
                    + "  port                   move to another version explain            explain compiler errors\n"
                    + "  dependencies           target dependencies     ui-check, world-check, mp-check   real game checks\n"
                    + "  eval, reload           talk to the running game          game-status, game-stop\n"
                    + "  package, self-test     build or test modaccel itself\n"
                    + "Run modaccel help COMMAND for one command (port, fast, compile, mixin-check, explain, ui-check, eval, reload ...).\n"
                    + "--root is the mod's Gradle source checkout (the folder with gradlew); a multi-version build needs --project, e.g. --project :1.21.6.";
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
