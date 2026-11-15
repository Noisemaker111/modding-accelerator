import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Portable public entrypoint. Every result states the tier actually checked. */
public final class Mmt {

    static final class Report {

        final Path root, directory;
        final String command, id =
            Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        final long start = System.nanoTime();
        final List<Object> stages = Collections.synchronizedList(new ArrayList<>());

        Report(Path root, String command) {
            this.root = root;
            this.command = command;
            directory = root.resolve(".runs/portable/" + id);
        }

        Path log(String name) {
            Path log = directory.resolve(stages.size() + "-" + name + ".log");
            Run.progress(name, log);
            return log;
        }

        void stage(String name, Run.Result result, Map<String, Object> extra) {
            Map<String, Object> value = Json.map(
                "name",
                name,
                "seconds",
                result.seconds(),
                "exit",
                result.exit(),
                "log",
                result.log().toString()
            );
            value.putAll(extra);
            stages.add(value);
        }

        Map<String, Object> finish(int exit, Map<String, Object> value) throws IOException {
            double total = (System.nanoTime() - start) / 1e9, accounted = 0;
            for (Object stage : stages) {
                Object seconds = Json.object(stage).get("seconds");
                if (seconds instanceof Number number) accounted += number.doubleValue();
            }
            var result = Json.map(
                "schema",
                1,
                "id",
                id,
                "command",
                command,
                "root",
                root.toString(),
                "ok",
                exit == 0,
                "exit",
                exit,
                "seconds",
                total,
                "stageSeconds",
                accounted,
                "unaccountedSeconds",
                Math.max(0, total - accounted),
                "stages",
                stages,
                "result",
                value,
                "receipt",
                directory.resolve("result.json").toString()
            );
            Json.save(directory.resolve("result.json"), result);
            return result;
        }
    }

    static final class Options {

        /** Every --switch and --option any command reads; has() and get() refuse a name missing here, so a documented option cannot be read but rejected. */
        static final Set<String> SWITCHES = Set.of(
            "json",
            "refresh",
            "help",
            "dry-run",
            "no-compile",
            "fix",
            "direct",
            "expect-world",
            "save-roundtrip",
            "jbr",
            "fresh-server",
            "once",
            "list",
            "prepare",
            "no-hooks",
            "learn-from-mods",
            "intermediary",
            "no-heap",
            "allow-hidden",
            "keep",
            "check-only"
        );
        static final Set<String> VALUES = Set.of(
            "command",
            "label",
            "session",
            "parent",
            "jit",
            "root",
            "project",
            "task",
            "from",
            "to",
            "names",
            "map",
            "log",
            "rules",
            "rounds",
            "undo",
            "screen",
            "click",
            "click-at",
            "move-at",
            "key",
            "type",
            "assert",
            "expect-screen",
            "expect-value",
            "expect-change",
            "world",
            "server",
            "server-task",
            "client-task",
            "selection",
            "code",
            "file",
            "thread",
            "java",
            "until",
            "timeout",
            "against",
            "only",
            "plugins",
            "server-version",
            "server-build",
            "server-xms",
            "background",
            "quiet",
            "pack",
            "minecraft",
            "loader",
            "memory",
            "attach", "instance", "launch", "duration", "warmup", "out", "scenario",
            "rcon-port", "rcon-password-env", "repeat", "seed", "probes", "baseline", "candidate", "family", "block",
            "check",
            "mod-package", "mod-root", "entity", "counts", "experiment", "runs", "patch",
            "min-runs", "min-speedup", "arms", "replay", "max-calls-per-tick"
        );
        final Map<String, String> values = new LinkedHashMap<>();
        final Set<String> flags = new HashSet<>();
        String command = "fast";

        Options(String[] args) {
            int i = 0;
            if (args.length > 0 && !args[0].startsWith("-")) command = args[i++];
            if (command.equals("help") && i < args.length && !args[i].startsWith("-")) values.put("command", args[i++]);
            for (; i < args.length; i++) {
                String key = args[i];
                if (!key.startsWith("--")) throw new IllegalArgumentException("Expected --option; got " + key);
                key = key.substring(2);
                if (SWITCHES.contains(key)) flags.add(key);
                // --undo with no transaction undoes the newest port.
                else if (key.equals("undo") && (i + 1 == args.length || args[i + 1].startsWith("--"))) values.put(
                    key,
                    "latest"
                );
                else if (VALUES.contains(key)) {
                    if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException(
                        "Missing value for --" + key
                    );
                    values.put(key, args[i]);
                } else throw new IllegalArgumentException(
                    "Unknown option --" +
                    key +
                    ". Run mmt help" +
                    (command.equals("help") ? "" : " " + command) +
                    " for the options it takes."
                );
            }
            // Gradle project paths start with ':'; "26.3" alone would be read as a task name.
            for (String key : List.of("project", "against")) if (values.containsKey(key)) {
                List<String> paths = new ArrayList<>();
                for (String path : values.get(key).split(",")) {
                    path = path.strip();
                    if (!path.isEmpty()) paths.add(path.startsWith(":") ? path : ":" + path);
                }
                values.put(key, String.join(",", paths));
            }
        }

        String get(String key, String fallback) {
            known(VALUES, key);
            return values.getOrDefault(key, fallback);
        }

        boolean has(String key) {
            known(SWITCHES, key);
            return flags.contains(key);
        }

        private static void known(Set<String> names, String key) {
            if (!names.contains(key)) throw new IllegalStateException(
                "--" + key + " is read but not in Options." + (names == SWITCHES ? "SWITCHES" : "VALUES")
            );
        }
    }

    /** Commands for mmt's own tests, left out of help. */
    static final Set<String> INTERNAL = Set.of("legacy-agent");

    static final Set<String> COMMANDS = Set.of(
        "perf", "perf-report", "perf-diff", "perf-template", "perf-optimize",
        "support",
        "discover",
        "doctor",
        "setup",
        "compile",
        "fast",
        "test",
        "port",
        "convert",
        "explain",
        "dependencies",
        "mixin-check",
        "data-check",
        "plugin-check",
        "server-check",
        "ui-check",
        "world-check",
        "mp-check",
        "eval",
        "reload",
        "game-status",
        "game-stop",
        "package",
        "self-test",
        "preferences",
        "legacy-agent",
        "pack-check",
        "help"
    );

    /**
     * Output that goes to a file or pipe (the desktop app, an agent's log, a redirect) is written in UTF-8, as those
     * readers decode it. Java otherwise writes redirected output in the platform charset: on Windows (windows-1252)
     * perf-optimize's "95% 9.94–11.33" and "baseline → candidate" reached a log as a replacement character and "?".
     * A terminal keeps the charset Java chose for it.
     */
    public static void utf8WhenRedirected() {
        if (terminal()) return;
        System.setOut(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192), true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.err), 8192), true, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Whether standard output is a terminal: System.console() is null when redirected before Java 22, and says so after. */
    static boolean terminal() {
        Console console = System.console();
        if (console == null) return false;
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException beforeJava22) {
            return true;
        }
    }

    public static void main(String[] args) {
        utf8WhenRedirected();
        int exit = 2;
        Options options = null;
        Report report = null;
        ProjectLock lock = null;
        // convert --dry-run keeps its receipt here while it runs and deletes it after: it leaves nothing behind.
        Path previewReceipts = null;
        try {
            options = new Options(args);
            if (options.has("help") || options.command.equals("help")) {
                System.out.println(help(options.command.equals("help") ? options.get("command", "") : options.command));
                return;
            }
            if (options.command.equals("support")) {
                System.out.println(options.has("json") ? Json.write(Support.features()) : Support.text());
                return;
            }
            if (options.command.equals("preferences")) {
                Map<String, Object> preferences = Preferences.change(Preferences.file(), options);
                if (options.has("json")) System.out.println(Json.write(preferences));
                else System.out.println(
                    "Background window: " +
                    (Boolean.TRUE.equals(preferences.get("background")) ? "on" : "off") +
                    "\nQuiet sound and narrator: " +
                    (Boolean.TRUE.equals(preferences.get("quiet")) ? "on" : "off") +
                    "\nChange with: mmt preferences --background on|off --quiet on|off"
                );
                return;
            }
            Path home = Installation.home();
            if (Set.of("perf", "perf-report", "perf-diff", "perf-template", "perf-optimize").contains(options.command)) {
                report = new Report(Path.of(options.get("root", ".")).toAbsolutePath(), options.command);
                Map<String, Object> result = Performance.run(home, options, report);
                exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                Map<String, Object> output = report.finish(exit, result);
                if (options.has("json")) System.out.println(Json.write(output));
                else System.out.println(Performance.text(result));
                System.exit(exit);
            }
            if (options.command.equals("pack-check")) {
                // A modpack, not a mod's source: no Gradle build and no project lock. Receipts go with the pack cache.
                report = new Report(PackCheck.receipts(), options.command);
                Map<String, Object> result = new PackCheck(home, options, report).run();
                exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                Map<String, Object> output = report.finish(exit, result);
                if (options.has("json")) System.out.println(Json.write(output));
                else System.out.printf(
                    "pack-check: %s (%.0f seconds)%n%s%nReceipt: %s%n",
                    exit == 0 ? "passed" : "failed",
                    output.get("seconds"),
                    String.valueOf(result.getOrDefault("details", "")).strip(),
                    output.get("receipt")
                );
                System.exit(exit);
            }
            Path root = Path.of(options.get("root", "."));
            if (
                Files.isRegularFile(root) && root.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
            ) throw new IllegalArgumentException(
                "--root needs the mod's Gradle source directory, not a compiled JAR. Check out its source and run mmt discover --root SOURCE."
            );
            if (!COMMANDS.contains(options.command)) throw new IllegalArgumentException(
                unknownCommand(options.command)
            );
            ProjectModel project = new ProjectModel(root);
            if (!Set.of("package", "self-test").contains(options.command)) project.requireGradleBuild();
            // Commands that build, edit or start the game take the project lock; reading state and talking to a running game don't.
            // convert --dry-run converts a copy elsewhere: it takes no lock and keeps no receipt.
            boolean preview = options.command.equals("convert") && options.has("dry-run") && !options.values.containsKey("undo");
            if (
                !preview &&
                !Set.of("discover", "doctor", "package", "self-test", "game-status", "game-stop", "eval").contains(
                    options.command
                )
            ) lock = ProjectLock.acquire(project, options.command);
            if (preview) previewReceipts = Files.createTempDirectory("mmt-convert-receipt-");
            report = new Report(preview ? previewReceipts : project.root, options.command);
            Map<String, Object> result;
            String interrupted = options.command.equals("port") ? null : Port.interruptedText(project);
            long discovered = System.nanoTime();
            if (options.command.equals("discover")) {
                result = project.discover();
                result.put("support", Support.discovered(result));
                exit = 0;
                if (Json.array(result.get("builds")).isEmpty()) result.put(
                    "details",
                    "No build.gradle or build.gradle.kts file was found, so there are no build hints" +
                    (Boolean.TRUE.equals(result.get("stonecutter"))
                            ? " (a Stonecutter build names its scripts build.<variant>.gradle.kts)"
                            : "") +
                    ". Run mmt setup to evaluate the build itself."
                );
            } else if (options.command.equals("package")) {
                result = Installation.pack(home, report);
                exit = 0;
            } else if (options.command.equals("self-test")) {
                result = Installation.test(home, report, options.get("only", ""));
                exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
            } else if (options.command.equals("doctor")) {
                result = project.discover();
                result.put("support", Support.discovered(result));
                result.put(
                    "jdks",
                    Runtimes.installed(home)
                        .stream()
                        .map(j -> Json.map("home", j.home().toString(), "version", j.version()))
                        .toList()
                );
                result.put("selectedJava", Runtimes.forGradle(home, project.wrapperVersion()).home().toString());
                exit = 0;
            } else if (
                options.command.equals("port") &&
                !options.values.containsKey("undo") &&
                (result = Route.blocked(home, options.get("from", ""), options.get("to", ""))) != null
            ) {
                // Refuse before evaluating a build that predates official names; nothing is changed.
                exit = 1;
            } else if (
                options.command.equals("dependencies") && Route.unknownRelease(home, options.get("to", "")) != null
            ) {
                result = Json.map("ready", false, "reason", Route.unknownRelease(home, options.get("to", "")));
                exit = 1;
            } else {
                // explain reads two projects' classpaths: the one that fails and the one it's compared with.
                String selected = options.command.equals("explain") && options.values.containsKey("against")
                    ? options.get("project", "") + "," + options.get("against", "")
                    : options.get("project", "");
                Build build = new Build(project, home, selected, report);
                boolean noModel = Set.of("game-status", "game-stop", "eval").contains(options.command) ||
                    (options.command.equals("port") && options.values.containsKey("undo")) ||
                    options.command.equals("convert");
                Map<String, Object> model;
                try {
                    model = noModel ? Json.map() : build.model(options.has("refresh"), options.command.equals("port"));
                } catch (IOException unreadable) {
                    // A port may drop a decompiler-only plugin that is no longer published; the port's undo restores it.
                    String dead = options.command.equals("port") && options.has("fix") && !options.has("dry-run")
                        ? Port.deadDevPlugin(unreadable.getMessage())
                        : null;
                    if (dead == null) throw unreadable;
                    List<String> changed = Port.removeDevPlugin(project, dead);
                    if (changed.isEmpty()) throw unreadable;
                    report.stages.add(
                        Json.map(
                            "name",
                            "dead-dev-plugin",
                            "plugin",
                            dead,
                            "files",
                            changed,
                            "evidence",
                            "the build names a version of this decompiler-only plugin that none of its repositories publishes"
                        )
                    );
                    try {
                        model = build.model(true, true);
                    } catch (IOException still) {
                        Port.restorePending(project);
                        throw still;
                    }
                }
                switch (options.command) {
                    case "setup" -> {
                        result = summary(model, project);
                        exit = 0;
                    }
                    case "legacy-agent" -> {
                        result = Json.map("agent", new Game(build, model).agent().toString());
                        exit = 0;
                    }
                    case "fast" -> {
                        result = FastLane.run(build, model, options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                        if (exit == 0) exit = withModChecks(result, model);
                    }
                    case "ui-check", "world-check", "mp-check", "game-status", "game-stop", "eval", "reload" -> {
                        result = new Game(build, model).run(options);
                        exit = "reload".equals(options.command) && Boolean.TRUE.equals(result.get("restartNeeded"))
                            ? 1
                            : 0;
                    }
                    case "port" -> {
                        try {
                            result = new Port(build, model).run(options);
                        } finally {
                            // Changes made before the port's transaction are undone unless the transaction took them over.
                            Port.restorePending(project);
                        }
                        if (!options.values.containsKey("undo")) result.put("support", Support.evaluated(model));
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "convert" -> {
                        result = preview ? Convert.preview(project.root, home, options) : new Convert(build).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "explain" -> {
                        result = new Explain(build, model).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "data-check" -> {
                        result = new DataCheck(build, model).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "mixin-check" -> {
                        result = new MixinCheck(build, model).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                        // Mixin applies a require = 0 injection that matches nothing without a word; the audit against the game jar names it.
                        if (Boolean.TRUE.equals(result.get("checked"))) {
                            var audit = new JavaTools(build).audit(model);
                            String optional = optionalInjections(
                                Files.readAllLines(audit.log(), java.nio.charset.StandardCharsets.UTF_8)
                            );
                            if (
                                !optional.isEmpty() &&
                                !(result.get("optional") instanceof List<?> seen && !seen.isEmpty())
                            ) {
                                result.put("details", result.get("details") + "\n" + optional);
                                if (result.get("reason") != null) result.put(
                                    "reason",
                                    result.get("reason") + "\n" + optional
                                );
                            }
                        }
                    }
                    case "plugin-check" -> {
                        result = new PluginCheck(build, model).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "server-check" -> {
                        result = new ServerCheck(build, model).run(options);
                        exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
                    }
                    case "dependencies" -> {
                        result = new Port(build, model).dependencies(options);
                        exit = Boolean.TRUE.equals(result.get("ready")) ? 0 : 1;
                    }
                    case "compile", "test" -> {
                        String name = options.command.equals("compile")
                            ? "classes"
                            : options.command.equals("test") ? "test" : "mmtPure";
                        List<String> tasks = options.values.containsKey("task")
                            ? List.of(options.get("task", ""))
                            : build.tasks(model, name);
                        if (tasks.isEmpty()) throw new IOException(
                            "No " +
                            name +
                            " task in the selected projects. Select --project :module or --task :module:task."
                        );
                        // A NeoGradle mod whose game jar is not built yet compiles against NeoForge's installer-made jar.
                        if (
                            options.command.equals("compile") &&
                            !options.values.containsKey("task") &&
                            (result = OwnCompile.run(build, model, tasks)) != null
                        ) {
                            exit = 0;
                            break;
                        }
                        Run.Result run = build.gradle(tasks, options.command);
                        exit = run.exit() == 0 ? 0 : 1;
                        result = Json.map(
                            "tier",
                            options.command.equals("compile") ? "compile" : "logic",
                            "tasks",
                            tasks,
                            "log",
                            run.log().toString(),
                            "gameVerified",
                            false,
                            "details",
                            options.command.equals("compile")
                                ? "Compiled " + String.join(", ", tasks) + "."
                                : Run.tail(run.log())
                        );
                        if (run.exit() != 0) result.put(
                            "reason",
                            Run.gradleFailure(run.log(), model) + "\nLog: " + run.log()
                        );
                        if (options.command.equals("test") && run.exit() == 0) {
                            Map<String, Object> counts = TestResults.readTasks(model, tasks, report.id);
                            result.put("tests", counts);
                            result.put("details", TestResults.text(counts));
                            if (((Number) counts.get("failed")).intValue() > 0) exit = 1;
                            if (((Number) counts.get("executed")).intValue() == 0) {
                                exit = 1;
                                result.put("reason", FastLane.noTests(model, false));
                            }
                        }
                        if (exit == 0) exit = withModChecks(result, model);
                        // A mixin aimed at a method that does not exist compiles; say which check finds it.
                        if (
                            exit == 0 &&
                            result.get("modChecks") instanceof Map<?, ?> checks &&
                            checks.get("mixinClasses") instanceof Number mixins &&
                            mixins.intValue() > 0
                        ) result.put(
                            "details",
                            result.get("details") +
                            "\nMixin targets are not checked by " +
                            options.command +
                            ": mmt mixin-check lets Mixin itself apply them, without starting the game."
                        );
                    }
                    default -> throw new IllegalArgumentException("Unknown command " + options.command + ". " + help());
                }
            }
            if (interrupted != null) result.put(
                "warning",
                interrupted + " Undo it with: mmt port --root \"" + project.root + "\" --undo"
            );
            Map<String, Object> output = report.finish(exit, result);
            if (previewReceipts != null) output.remove("receipt");
            if (options.has("json")) System.out.println(Json.write(output));
            else {
                System.out.printf(
                    "%s: %s (%.2f seconds)%n",
                    options.command,
                    exit == 0 ? "passed" : "failed",
                    output.get("seconds")
                );
                String text =
                    switch (options.command) {
                        case "setup" -> setupText(result, project);
                        case "discover", "doctor" -> discoverText(result, project, options.command);
                        default -> String.valueOf(result.getOrDefault("reason", result.getOrDefault("details", "")));
                    };
                if (!text.isBlank()) System.out.println(text.strip());
                if (result.get("warning") != null) System.out.println("Warning: " + result.get("warning"));
                if (result.containsKey("undo")) System.out.println(
                    "Undo: mmt port --root \"" + project.root + "\" --undo \"" + result.get("undo") + "\""
                );
                if (result.containsKey("zip")) System.out.println("Package: " + result.get("zip"));
                if (output.get("receipt") != null) System.out.println("Receipt: " + output.get("receipt"));
            }
        } catch (Exception error) {
            // MMT_TRACE=1 prints where a failure came from, for a bug in mmt itself.
            if (System.getenv("MMT_TRACE") != null) error.printStackTrace();
            try {
                String reported = plainError(error);
                // A dedicated server that died mid-check is the likelier cause of a client failure: say so with the error.
                if (report != null) for (Object stage : report.stages) if (
                    "server-exit".equals(Json.object(stage).get("name"))
                ) reported += "\n" + Json.object(stage).get("details");
                var value = Json.map("error", reported);
                if (report != null) value = report.finish(2, value);
                if (previewReceipts != null) value.remove("receipt");
                if (options != null && options.has("json")) System.out.println(Json.write(value));
                else if (options != null && options.command.equals("eval")) {
                    // A snippet's own error doesn't need the game log; a game that died does.
                    String message = String.valueOf(error.getMessage());
                    if (message.startsWith("Game action failed: ")) message = message
                        .substring("Game action failed: ".length())
                        .replaceFirst("\nGame log: .*$", "");
                    System.err.println("eval: failed\n" + message);
                } else {
                    // Words first; the receipt keeps the stages and timings for anyone who wants them.
                    System.err.println(
                        (options == null ? "mmt" : options.command) + ": failed\n" + reported.strip()
                    );
                    if (value.get("receipt") != null) System.err.println("Receipt: " + value.get("receipt"));
                }
            } catch (IOException secondary) {
                System.err.println(error + "; could not save receipt: " + secondary);
            }
            exit = 2;
        } finally {
            if (lock != null) try {
                lock.close();
            } catch (IOException ignored) {}
            if (previewReceipts != null) try {
                OwnCompile.deleteTree(previewReceipts);
            } catch (IOException ignored) {}
        }
        System.exit(exit);
    }

    /** An exception as a sentence: file-system errors whose message is only a path get their meaning back. */
    /** An unknown command, with the nearest real one when it is a slip of the keyboard. */
    static String unknownCommand(String command) {
        String nearest = null;
        int best = Integer.MAX_VALUE;
        for (String known : new TreeSet<>(COMMANDS)) {
            int d = Explain.distance(known, command.toLowerCase(Locale.ROOT));
            if (d < best) {
                best = d;
                nearest = known;
            }
        }
        return (
            "Unknown command " +
            command +
            "." +
            (nearest != null && best <= 2 ? " Did you mean " + nearest + "?" : "") +
            "\n" +
            help()
        );
    }

    private static final java.util.regex.Pattern NO_PROJECT = java.util.regex.Pattern.compile(
        "No project (\\S+); available projects: \\[([^\\]]*)\\]"
    );

    static String plainError(Throwable error) {
        // Gradle's own words for a --project that is not in the build, buried under "Could not create task": say it plainly.
        java.util.regex.Matcher project = error.getMessage() == null ? null : NO_PROJECT.matcher(error.getMessage());
        if (project != null && project.find()) {
            List<String> available = new ArrayList<>();
            for (String path : project.group(2).split(",\\s*")) if (!path.isBlank() && !path.equals(":")) available.add(
                path.strip()
            );
            return (
                "There is no project " +
                project.group(1) +
                " in this build. It has " +
                available.size() +
                ": " +
                String.join(", ", available) +
                ".\nPick one with --project (mmt setup lists them with their loader and Minecraft version)."
            );
        }
        // Port wraps its failure to add the undo line; the file-system cause underneath still needs words.
        Throwable cause = error;
        while (cause != null && !(cause instanceof FileSystemException)) cause = cause.getCause() == cause
            ? null
            : cause.getCause();
        if (cause == null) return error.getMessage() == null ? error.toString() : error.getMessage();
        String undo = error != cause &&
            error.getMessage() != null &&
            error.getMessage().contains("\nUndo this port with:")
            ? error.getMessage().substring(error.getMessage().indexOf("\nUndo this port with:"))
            : "";
        if (cause instanceof AccessDeniedException denied) return (
            "Cannot write " +
            denied.getFile() +
            ": access was denied. The file is read-only, or another program (an editor, the game, antivirus) has it open." +
            " Clear the read-only flag or close that program, then rerun." +
            undo
        );
        if (cause instanceof NoSuchFileException missing) return (
            "No such file or folder: " +
            missing.getFile() +
            (missing.getReason() == null ? "" : " (" + missing.getReason() + ")") +
            "." +
            undo
        );
        if (
            cause instanceof FileSystemException other &&
            other.getReason() != null &&
            other.getReason().toLowerCase(Locale.ROOT).contains("used by another process")
        ) return (
            "Cannot use " +
            other.getFile() +
            ": another program has it open. Close it (the game, an editor or a file viewer), then rerun." +
            undo
        );
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    /** MixinAudit's require = 0 notes, as the injections that will never run; "" when there are none. */
    static String optionalInjections(List<String> auditLines) {
        List<String> optional = new ArrayList<>();
        for (String line : auditLines) if (line.contains("optional (require = 0)")) optional.add(
            line.strip().replaceFirst("^note:\\s*", "").replace("optional (require = 0): ", "")
        );
        if (optional.isEmpty()) return "";
        return (
            optional.size() +
            (optional.size() == 1 ? " optional injection" : " optional injections") +
            " (require = 0) found no target in this game, so Mixin skips " +
            (optional.size() == 1 ? "it" : "them") +
            " without an error and " +
            (optional.size() == 1 ? "it never runs" : "they never run") +
            ":\n  " +
            String.join("\n  ", optional)
        );
    }

    /** Mod resource and metadata checks after a passing compile or test run; problems turn the pass into a failure. */
    static int withModChecks(Map<String, Object> result, Map<String, Object> model) throws IOException {
        Map<String, Object> checks = ModChecks.run(model);
        result.put("modChecks", checks);
        String text = ModChecks.text(checks);
        String details = String.valueOf(result.getOrDefault("details", ""));
        result.put(
            "details",
            details +
            (details.isBlank() ? "" : "\n") +
            "Checked resources and metadata: " +
            checks.get("checked") +
            "." +
            (text.isEmpty() ? "" : "\n" + text)
        );
        if (Boolean.TRUE.equals(checks.get("ok"))) return 0;
        result.put("reason", result.get("details"));
        return 1;
    }

    static String discoverText(Map<String, Object> result, ProjectModel project, String command) {
        StringBuilder out = new StringBuilder();
        out.append("Gradle ").append(result.getOrDefault("gradle", "?")).append(" build at ").append(project.root);
        if (Boolean.TRUE.equals(result.get("stonecutter"))) out.append(
            "\nStonecutter: one source tree built for several Minecraft versions; each version is its own project (select one with --project)."
        );
        for (Object item : Json.array(result.getOrDefault("builds", List.of()))) {
            var hint = Json.object(item);
            List<String> parts = new ArrayList<>();
            for (String key : List.of("loader", "loaders", "minecraft", "path", "file")) if (
                hint.get(key) != null && !String.valueOf(hint.get(key)).isBlank()
            ) parts.add(key + " " + hint.get(key));
            if (!parts.isEmpty()) out.append("\n  ").append(String.join(", ", parts));
        }
        if (command.equals("doctor")) {
            out.append("\nJava for this build: ").append(result.getOrDefault("selectedJava", "?"));
            List<String> installed = new ArrayList<>();
            for (Object jdk : Json.array(result.getOrDefault("jdks", List.of()))) installed.add(
                String.valueOf(Json.object(jdk).get("version"))
            );
            if (!installed.isEmpty()) out.append("\nJDKs found: ").append(String.join(", ", installed));
        }
        if (result.get("details") != null) out.append('\n').append(result.get("details"));
        out
            .append("\nNext: mmt setup --root \"")
            .append(project.root)
            .append("\" evaluates the build (a minute or more the first time). Add --json for every field.");
        return out.toString();
    }

    static String setupText(Map<String, Object> result, ProjectModel project) {
        StringBuilder out = new StringBuilder(
            "Gradle " + result.getOrDefault("gradle", "?") + ", Java " + result.getOrDefault("java", "?")
        );
        List<Object> projects = Json.array(result.getOrDefault("projects", List.of()));
        List<String> mods = new ArrayList<>();
        for (Object item : projects) {
            var p = Json.object(item);
            // summary() keeps loaders as a set, and a model read back from JSON has a list: take either.
            Set<String> loaders = new TreeSet<>();
            if (p.get("loaders") instanceof Collection<?> named) for (Object loader : named) loaders.add(
                String.valueOf(loader)
            );
            if (loaders.isEmpty()) continue;
            Object minecraft = p.get("pins") instanceof Map<?, ?> pins ? pins.get("minecraft_version") : null;
            mods.add(
                p.get("path") +
                "  " +
                String.join(", ", loaders) +
                (minecraft == null ? "" : ", Minecraft " + minecraft)
            );
        }
        out.append("\n").append(mods.size()).append(mods.size() == 1 ? " mod project:" : " mod projects:");
        for (String mod : mods) out.append("\n  ").append(mod);
        if (mods.size() > 1) {
            String first = mods.get(mods.size() - 1).split("\\s+")[0];
            out
                .append("\nCommands work on one of them at a time: add --project, e.g. mmt compile --root \"")
                .append(project.root)
                .append("\" --project ")
                .append(first);
        } else out.append("\nNext: mmt compile --root \"").append(project.root).append("\", then fast for tests.");
        out.append("\nModel: ").append(result.get("model")).append(" (add --json for every task and pin)");
        return out.toString();
    }

    static String help() {
        return help("");
    }

    static String help(String command) {
        return switch (command) {
            case "perf" -> "Profile a running local Java 17+ game/server: mmt perf [--attach PID | --instance FOLDER | --root SOURCE] [--duration 30] [--warmup 5] [--out FOLDER] [--json]\n" +
                "Start your game normally, then run perf; --list shows PIDs when automatic selection is ambiguous. Source --root profiles the client mmt started as your preferences say, hidden or not, with no --allow-hidden needed (hidden: frame timing is not representative).\n" +
                "Open report.html in the output folder. Frame/tick hooks depend on game mappings; missing metrics are explicit. GPU timing is not collected.\n" +
                "--mod-root SOURCE (with --attach) or --mod-package PKG names your mod in the cost ranking; --allow-hidden lets --attach or --instance profile a hidden, throttled game (frame timing not representative; --root implies it); --no-heap skips the post-capture class histogram.\n" +
                "--no-hooks records JFR only. --prepare prints a startup -javaagent argument when dynamic attach is disabled. Requires JDK 17+ and same OS user.\n" +
                "Each capture writes record.json (--session NAME groups runs) and names known patterns from knowledge/perf-patterns.json that its hotspots match.\n" +
                "--scenario FILE --rcon-port 25575 [--rcon-password-env MMT_RCON_PASSWORD] [--repeat 1] [--seed 0] drives explicit test-world commands.\n" +
                "Scenarios edit the selected local server world: use a disposable world. Each command requires an expected response. Repeats share one JVM.";
            case "perf-template" -> "Generate editable scenario files: mmt perf-template --family transport|block-scene|entity-crowd|mod-content [--block MOD:BLOCK] [--entity MOD:ENTITY] [--counts 1,16,64] [--root SOURCE] [--out FOLDER]\n" +
                "--family experiment --from CAPTURE --root SOURCE [--out .runs/MOD-experiment] [--max-calls-per-tick 256] writes a perf-optimize experiment, probes and gate snippets to fill in from a perf --root capture.\n" +
                "Methods the capture timed above --max-calls-per-tick get no probe (capture again with perf --probes CAPTURE/probes.suggested.json for call counts); a probe metric counts Server thread calls only (serverInclusiveMsPerTick).\n" +
                "mod-content reads the mod's lang files for its own blocks and entities. Generation does not edit a world. perf --root SOURCE --scenario FILE runs it in the source client's own world; other games need a disposable local server with RCON.";
            case "perf-diff" -> "Compare observed captures: mmt perf-diff --baseline FOLDER_OR_REPORT --candidate FOLDER_OR_REPORT [--out FOLDER]\nDescriptive differences only; this does not establish equal workload, causality or statistical significance.";
            case "perf-optimize" -> "Accept or reject one candidate change with measured speedup: mmt perf-optimize --root SOURCE --experiment EXPERIMENT.json --patch CANDIDATE.diff [--runs 5] [--out FOLDER] [--dry-run] [--keep]\n" +
                "The candidate is a git diff you or your own agent wrote from the perf report; mmt never writes code. Baseline (HEAD) and candidate (HEAD + diff)\n" +
                "are separate git worktrees, launched alternately in fresh games. Each launch runs the experiment's setup snippets, a cold capture and a warm capture of\n" +
                "the chosen metric (a timing probe or a report value), then its gates: eval snippets compared exactly, against an expected value or within a tolerance,\n" +
                "and 1280x720 framebuffer captures compared pixel by pixel. Every gate must also agree baseline-to-baseline. Accepted only when all gates pass and the\n" +
                "95% bootstrap interval of the speedup is above 1 for cold and warm; an accepted diff is then applied to SOURCE (not with --dry-run). See PERFORMANCE.md.\n" +
                "--label TEXT names the candidate and --session NAME groups runs in record.json, which bench/perf_sessions.py summarises.\n" +
                "Pairs stop at the first failed gate, or from --min-runs pairs (3) once 99% t intervals lie above 1 (cold and warm) or below --min-speedup (1). Each pair prints a line; creating OUT/stop ends the run after the pair in progress.\n" +
                "--arms auto|parallel|sequential: auto starts both arms of a pair together on 12 or more cores.\n" +
                "--check-only checks the experiment (and --patch) offline, as every run does first. --replay OUT1,OUT2 decides finished runs again and prints their wall time.";
            case "perf-report" -> "Render an existing capture/JFR locally: mmt perf-report --file FOLDER_OR_JFR [--out FOLDER]\nNo game or network is required. External JFR files may lack frame/tick events or code-origin metadata.";
            case "self-test" -> "Test mmt itself, with no game and no network: mmt self-test [--only SUITE,SUITE] [--json]\n" +
            "Suites: " +
            String.join(", ", Installation.SUITES.stream().map(Installation.Suite::name).toList()) +
            ". Each is a class in tools/tests.\n" +
            "A failed suite is reported first, with the end of its log; --only runs the named suites alone.";
            case "preferences" -> "See or save your game launch preferences: mmt preferences [--background on|off] [--quiet on|off] [--json]\n" +
            "Background keeps the client hidden and prevents cursor capture (default off).\n" +
            "Quiet starts every game with sound and narrator off (default on); opt out with --quiet off,\n" +
            "and the next game gets back the sound, narrator and first-launch options quiet changed in its folder.";
            case "port" -> "Port a Gradle mod source checkout (not a JAR):\n" +
            "  mmt discover --root SOURCE\n" +
            "  mmt dependencies --root SOURCE --to 1.21.6 --json\n" +
            "  mmt port --root SOURCE --from 1.21.5 --to 1.21.6 --fix --json\n" +
            "  mmt port --root SOURCE --undo TRANSACTION\n" +
            "  mmt port --root SOURCE --from 1.21.1 --to 26.3 --fix --check \"java tools/tests/ZombieAiCheck.java ...\" --json\n" +
            "--check COMMAND runs the mod's own real-game check on the ported project (MMT_PORTED_ROOT is set); the port is ok only when it passes. --learn-from-mods also runs the rule book's older rules mined from other mods (provenance third-party-mods) and learns rules from other mods' version branches (knowledge/port-corpus.json); by default rules come only from the game's own jars and our own rules.\n" +
            "A port across several families routes FROM -> each family hub between -> TO (coverage/changes/index.json), joins every update's renames into one map, runs only the knowledge rules whose jump covers an update it crosses, builds once, and names the update behind each remaining error. --direct keeps the one-jump map. Releases before 1.14.4 are refused (no official names).\n" +
            "Fabric pins and verifiable published dependencies are retargeted automatically. When discovery cannot identify a dependency, add a verified selection to .mmt/dependencies.json (or --selection FILE): source, target coordinate, and modrinth or repository evidence. A dependency with no target release can be disabled with disable:true, a reason and its published project; importing sources and entrypoints are removed with the port and restored by undo. Other loaders need target dependencies set in the source build first.";
            case "convert" -> "Move a mod's source to another loader on the same Minecraft version: mmt convert --root SOURCE --to neoforge|fabric [--dry-run | --no-compile] [--json]\n" +
            "  mmt convert --root SOURCE --undo [ID]   restore every file the conversion changed, byte for byte\n" +
            "Fabric to NeoForge (any version with a NeoForge release) and NeoForge to Fabric (Minecraft 26.1 and later, where both use Mojang's names).\n" +
            "It rewrites the build (Loom <-> ModDevGradle, the newest loader releases for that Minecraft version), the metadata (fabric.mod.json <->\n" +
            "META-INF/neoforge.mods.toml), the entrypoints (ModInitializer <-> @Mod classes that run it in NeoForge's first registry event), and the\n" +
            "code with the rules in knowledge/loaders.json: loader calls, events, key mappings, Mod Menu's config screen. Every rule names classes checked\n" +
            "against the loaders' own published jars (python bench/loader_api.py verify) and has a before/after test. There is no AI step.\n" +
            "Anything with no rule is listed with its file and line and left as it was, never dropped. Then the converted build is compiled\n" +
            "(--no-compile skips that); exit 0 means it compiled and nothing was left. --dry-run writes nothing in the project (no state, no\n" +
            "receipt): it lists each file that would change or be removed and what would be left, compiles nothing, and exits 0 when it ran.\n" +
            "A compile is not proof: check the mod in the game with ui-check or world-check and --assert.\n" +
            "Forge, Quilt and mod <-> Paper plugin moves are not converted yet.";
            case "explain" -> "Explain a port's compiler errors: mmt explain --root SOURCE --project :failing --against :building [--only TEXT] [--log FILE] [--task :p:task]\n" +
            "Compiles the failing project (or reads --log), maps errors in Stonecutter's generated sources back to src/, and for each one shows the\n" +
            "class, method or field it names as each project's jars have it: where a missing class lives, a member's signatures, or similar names.\n" +
            "--only keeps errors whose source path contains TEXT.";
            case "mixin-check" -> "Let Mixin itself apply the mod's mixins, without starting the game: mmt mixin-check --root SOURCE [--project :module] [--client-task :p:runClient] [--refresh] --json\n" +
            "The build's client launch is recorded once per build (Gradle compiles and prepares it, without downloading game assets), then started with\n" +
            "the loader set up as its own JUnit support does (Fabric: Knot, as fabric-loader-junit; NeoForge: the forgejunituserdev target, as junit-fml).\n" +
            "Every class the mod's mixin configs target is loaded, so Mixin applies the mixins; nothing opens a window or a world. Mixin's debug\n" +
            "injector check is on, so an injection that matches fewer places than it expects is reported as dead. Each finding is Mixin's own message,\n" +
            "with the mixin's source file. Exit 1 when a mixin would stop the game or is dead; --refresh records the launch again.";
            case "data-check" -> "Read what the game loads for the mod, without launching the game: mmt data-check --root SOURCE [--project :module] [--client-task :p:runClient] [--once] [--refresh] --json\n" +
            "The mod's dev launch (recorded once, shared with mixin-check) starts the game's own code with no window, no world and no network:\n" +
            "Bootstrap, every mod's main and server entrypoints, the registries frozen, then a new world's data packs loaded by the game's WorldLoader.\n" +
            "Reports what the mod registered (items, blocks, recipes, loot tables, tags ...), each item as a real stack (stack size, durability, food),\n" +
            "and every warning and error the game logged. Exit 1 when the game logged an error, an item cannot be made, or nothing of the mod loaded.\n" +
            "The game's code stays loaded: the next data-check reads the data again (a changed recipe or loot table costs a few CPU-seconds);\n" +
            "changed classes or fabric.mod.json load it again. --once does not keep it; game-stop ends it.\n" +
            "Fabric on Minecraft 26.x; other versions and loaders are reported as unchecked with the reason.";
            case "plugin-check" -> "Load and enable a Paper/Bukkit plugin on MockBukkit, a fake Paper server, without a real server: mmt plugin-check --root SOURCE [--project :module] [--task :shadowJar] [--plugins FOLDER] --json\n" +
            "The plugin's own jar task builds the jar (shadowJar, reobfJar or jar; --task picks another). MockBukkit for the plugin's Paper API line\n" +
            "(knowledge/mockbukkit.json) loads that jar in a fresh JVM, the way Paper does, and checks: plugin.yml / paper-plugin.yml parses and its\n" +
            "api-version is not newer than the server; the main class loads and onLoad, onEnable, 100 ticks of its tasks and onDisable run without\n" +
            "an exception; every plugin.yml command has a handler; every declared permission registers; every registered listener's handlers are\n" +
            "valid; hard dependencies are present (put their jars in --plugins FOLDER). Failures name the file and line. Code through server\n" +
            "internals (net.minecraft, CraftBukkit) or API MockBukkit lacks is reported unchecked, never passed. Exit 1 on a failure or anything unchecked.";
            case "dependencies" -> "Report target dependencies without editing: mmt dependencies --root SOURCE --to 1.21.6 [--selection FILE] --json\nEach non-loader dependency is resolved, explicitly selected, or blocked with the selection entry to add in .mmt/dependencies.json.";
            case "ui-check" -> "Capture the target client: mmt ui-check --root SOURCE --screen net.minecraft.client.gui.screen.TitleScreen --json\nInspect the receipt's framebuffer capture; add --assert METHOD for behavior checks, or --expect-change PERCENT to require the action to visibly change the frame.\n" +
            "--screen opens a new instance of that screen; leave it out to capture what the running client shows now.\n" +
            "--java JAVA_HOME (ui-check or world-check) starts the client on that runtime instead of the build's, for failures that only happen on one Java build.";
            case "reload" -> "Recompile and swap the changes into the running game: mmt reload --root SOURCE --project :module\n" +
            "Changed method bodies swap in place; changed resources reload the resource packs; classes the game hasn't loaded yet\n" +
            "are read on first use. A changed class shape (added or removed methods or fields) on a stock JVM, a mixin, or mod\n" +
            "metadata needs a restart: the command says so and exits 1. Objects keep their field values and static initializers do not rerun.\n" +
            "Start the game with --jbr (ui-check or world-check) to run it on JetBrains Runtime, which also swaps added or removed methods and fields.";
            case "eval" -> "Run Java inside the running game: mmt eval --root SOURCE --code 'mc.player.getX()'\n" +
            "  --file SNIPPET.java  read the snippet from a file instead\n" +
            "  --thread control     run off the game thread (for snippets that wait); default is the game thread\n" +
            "  --until CONDITION    run CONDITION every 0.25 s until it returns true, e.g. --until 'mc.level != null';\n" +
            "                       --timeout SECONDS (default 60, at most 120) fails it if it never does\n" +
            "An expression is returned; statements return what they return. 'mc' is the Minecraft instance, print(x) adds output,\n" +
            "vars is a map kept between snippets; call(objectOrClass, \"name\", args...) and field(objectOrClass, \"name\") reach private\n" +
            "members. Leading import lines are allowed. Start the game first with ui-check or world-check.";
            case "world-check" -> "Check a loaded save: mmt world-check --root SOURCE --world lab --assert METHOD --json\nThe save must exist in this project's game directory. Inspect the receipt's framebuffer capture; --expect-change PERCENT requires a visible result from the key or click.";
            case "fast",
                "test" -> "Run the mod's logic tests without the game: mmt fast --root SOURCE [--project :module]\n" +
            "fast runs JUnit 5 tests, reusing compiled code when nothing changed. With no Minecraft or loader on the test classpath\n" +
            "(plain Java, a Paper/Bukkit plugin) every test runs; with Minecraft present only tests tagged @Tag(\"pure\") run.\n" +
            "test runs the project's whole test task. Both fail when no test ran, and check the mod's resources and metadata afterwards.\n" +
            "The build needs JUnit: testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\") and testRuntimeOnly(\"org.junit.platform:junit-platform-launcher:1.11.4\").";
            case "server-check" -> "Run a plugin on a real headless Paper server with a bot player and check what it really did: mmt server-check --root SOURCE [--file SCENARIO] [--server-version 26.3] [--server-build N] [--server-xms 512M] [--fresh-server] [--plugins FOLDER_OR_JARS] [--refresh] --json\n" +
            "The plugin's jar task builds the jar; the real Paper server for its Minecraft version is downloaded once from PaperMC's API\n" +
            "(SHA-256 checked, cached), started headless in offline mode on 127.0.0.1 with a flat fixed-seed world, and stopped after.\n" +
            "--fresh-server uses a new server directory in the receipt, so Paper patches and libraries are not reused.\n" +
            "mmt accepts Minecraft's EULA only for that throwaway local test server. A protocol bot (MCProtocolLib, knowledge/mcprotocollib.json) joins as\n" +
            "serverFakeBot; on a server of another Minecraft version it joins through ViaProxy (knowledge/viaproxy.json, 1.8 and up). The scenario (--file, else .mmt/server-check.scenario) is one step per line:\n" +
            "  join, leave, restart-server, say TEXT, command /CMD, rcon CMD, wait Ns, server-env K=V, server-property K=V, background NAME: CMD, wait-port P\n" +
            "  expect-chat|expect-title|expect-actionbar|expect-log|expect-rcon CMD|expect-http GET URL  ~regex~ [within Ns]; expect-no-rcon CMD ~regex~ [for Ns]\n" +
            "  expect-entity TYPE [named ~re~] [near R], expect-block X Y Z ID[props] (~ is relative), expect-inventory ITEM [count N] [~re~],\n" +
            "  expect-position X Y Z [tolerance T], and expect-no-chat|-log|-entity|-inventory ... [within|for Ns].\n" +
            "  process-env K=V, helper-env K=V; expect-process COMMAND ~success output~ within Ns runs an existing test, requires exit 0 and matching output.\n" +
            "The process receives MC_HOST, MC_PORT, RCON_PORT and RCON_PASSWORD; MMT_RESTART_PORT and MMT_RESTART_TOKEN request a managed restart over loopback.\n" +
            "restart-server preserves the world and plugin data, disconnects the bot, and requires join again before bot steps.\n" +
            "${bot}, ${root}, ${harness}, ${port:NAME} and ${secret:NAME} expand; secrets come from the environment or .mmt/secrets.properties (keep it\n" +
            "out of git), are masked as ${secret:NAME} in every receipt and server file, and a missing one fails with \"needs secret NAME\".\n" +
            "Every expect step must pass; a scenario without one fails. Exit 1 names the step, what it expected and what came instead.";
            case "compile" -> "Compile the mod and check what the game reads at start-up: mmt compile --root SOURCE [--project :module]\n" +
            "After javac it fails only on what the loader or game rejects: JSON Minecraft cannot read even leniently (assets/, data/,\n" +
            "pack.mcmeta), a fabric.mod.json Fabric's strict reader refuses or with an invalid id, an invalid mods.toml id, a missing\n" +
            "mixin config, a missing class in a \"required\" mixin config, a missing entrypoint or access widener. Anything that depends on\n" +
            "the game version or on how the jar is assembled, JSON nothing in the game reads, a MOD_ID that differs from the metadata,\n" +
            "and items made by a recipe without a translation or model are warnings.\n" +
            "Build a jar with the project's own task: mmt compile --root SOURCE --task :jar. That is the build path for\n" +
            "Paper/Bukkit plugins (the jar lands in build/libs); then mmt plugin-check loads it.";
            case "pack-check" -> "Check a modpack the way its players run it: mmt pack-check --pack PACK [--world SAVE] [--only ITEM,ITEM] [--fix] [--json]\n" +
            "--pack is a CurseForge export (.zip or folder), a Modrinth .mrpack (or folder), a CurseForge app, Prism/MultiMC or vanilla launcher\n" +
            "instance, or any folder with mods/. --minecraft 1.21.1 and --loader neoforge[-21.1.77] override what the pack says; with no loader\n" +
            "version the loader's newest stable one is used. mmt pack-check --list shows the inputs, loaders, checks and repairs it knows.\n" +
            "Mod files are downloaded once, checked against their hashes or sizes, and cached in ~/.mmt/packs; sides come from the pack,\n" +
            "CurseForge or Modrinth (by SHA-1). Forge, NeoForge, Fabric and Quilt servers, any version, come from each loader's own installer.\n" +
            "Without the game it reads every jar the way the pack's loader does and warns about duplicate ids, missing or wrong-version\n" +
            "dependencies, mods for another game version, loader version or loader, declared conflicts, shared block files and names, and\n" +
            "mods with a proven repair in knowledge/pack-fixes.json. Warnings become problems only when the game shows them.\n" +
            "Then the pack's server starts offline on 127.0.0.1 with its mods and configs and a probe mod. mmt accepts Minecraft's EULA\n" +
            "only for that throwaway local test server. Mods that only work in a game client are left out and named.\n" +
            "Start 1 makes a new world, saves every dimension the way level.dat stores it and reads it back, and places every block item (or --only)\n" +
            "the way a player right-clicks; start 2 opens that world again and reads every placed block back. --world SAVE also opens a copy of your\n" +
            "save. A start that fails names the mod its log, crash report, mixin configs or stack point at.\n" +
            "--fix runs the repaired pack's server beside the shipped one and compares them check by check; only when the repaired one passes\n" +
            "is your pack changed: an instance folder in place (undo with --undo), an export or .mrpack as a fixed copy.\n" +
            "--memory 6G sets the server's heap. A start is judged by its log: --timeout SECONDS (default 600) stops one whose log stops growing,\n" +
            "and a ceiling of 30 minutes plus 8 s per mod past 100 stops one that never finishes. How to add a loader, input, check or\n" +
            "repair: tools/packcheck/CONTRIBUTING.md.";
            default -> "mmt <command> --root SOURCE [--project :module] [--json]\n" +
            "  support                what works today        discover, doctor   what kind of build this is\n" +
            "  setup                  evaluate the build      compile            compile + resource/metadata checks\n" +
            "  fast, test             logic tests, no game    mixin-check        Mixin applies the mixins, no game\n" +
            "  data-check             what the game loads for the mod (items, recipes, errors), no game\n" +
            "  port                   move to another version explain            explain compiler errors\n" +
            "  convert                move to another loader (Fabric <-> NeoForge), with undo\n" +
            "  dependencies           target dependencies     ui-check, world-check, mp-check   real game checks\n" +
            "  eval, reload           talk to the running game          game-status, game-stop\n" +
            "  plugin-check           Paper/Bukkit plugin on a fake Paper server (MockBukkit), no real server\n" +
            "  server-check           plugin on a real Paper server with a bot player and a scenario\n" +
            "  pack-check             a modpack on its real Forge/NeoForge/Fabric/Quilt server: crashes, conflicts, worlds that will not reopen\n" +
            "  preferences            show or change your game window and sound choices\n" +
            "  perf, perf-report      where a mod's CPU, memory, I/O and frame/tick time go\n" +
            "  perf-diff              compare two captures    perf-template      scenario files for perf --scenario\n" +
            "  perf-optimize          accept or reject a candidate change with equivalence gates and measured speedup\n" +
            "  package, self-test     build or test mmt itself\n" +
            "Run mmt help COMMAND for one command (port, convert, fast, compile, mixin-check, data-check, explain, ui-check, eval, reload ...).\n" +
            "--root is the mod's Gradle source checkout (the folder with gradlew); a multi-version build needs --project, e.g. --project :1.21.6.";
        };
    }

    static Map<String, Object> summary(Map<String, Object> model, ProjectModel owner) {
        List<Object> projects = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            Set<String> loaders = new TreeSet<>();
            for (Object dep : Json.array(p.get("dependencies"))) {
                String group = Json.text(Json.object(dep), "group", "");
                if (group.equals("net.fabricmc")) loaders.add("fabric");
                if (group.startsWith("net.neoforged")) loaders.add("neoforge");
                if (group.equals("net.minecraftforge")) loaders.add("forge");
            }
            projects.add(
                Json.map(
                    "path",
                    p.get("path"),
                    "directory",
                    p.get("directory"),
                    "loaders",
                    loaders,
                    "pins",
                    p.get("pins"),
                    "sourceSets",
                    Json.array(p.get("sourceSets")).stream().map(s -> Json.object(s).get("name")).toList(),
                    "tasks",
                    p.get("tasks")
                )
            );
        }
        return Json.map(
            "authoritative",
            true,
            "gradle",
            model.get("gradle"),
            "java",
            model.get("java"),
            "projects",
            projects,
            "support",
            Support.evaluated(model),
            "model",
            owner.state().resolve("model.json").toString(),
            "buildFilesChanged",
            false
        );
    }
}
