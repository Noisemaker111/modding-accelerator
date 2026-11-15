import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * modaccel fingerprint: what a mod does, recorded the same way for every mod (FINGERPRINT.md). One probe,
 * tools/packcheck/ModaccelFingerprint.java, runs in a seeded world frozen from its first tick:
 *
 * --root SOURCE runs the mod's own development client hidden: a flat world made by the vanilla server of the mod's
 *   Minecraft version (fixed seed, time and weather) is opened frozen, the probe is compiled inside the game against the
 *   game's own classes, places every block item and spawns every entity type of the mod's namespaces, runs a fixed number
 *   of ticks with checkpoints and saves; the game is closed, the save opened again frozen, and the same area read back.
 * --jar JAR[,JAR] runs the jars on their loader's real server through pack-check's machinery, with the same probe.
 *
 * Both write one JSON file; fingerprint-diff compares two of them (FingerprintDiff).
 */
final class Fingerprint {
    static final long SEED = 20261115L;
    static final String WORLD = "modaccel-fingerprint";
    static final List<String> PROBE = List.of(
        "tools/packcheck/ModaccelFingerprint.java",
        "tools/packcheck/ModaccelPackProbe.java"
    );

    private Fingerprint() {}

    static Map<String, Object> run(Path home, Modaccel.Options options, Modaccel.Report report) throws Exception {
        Path out = Path.of(options.get("out", report.directory.resolve("fingerprint.json").toString())).toAbsolutePath();
        Map<String, Object> result = options.values.containsKey("jar") ? jars(home, options, report, out) : source(home, options, report, out);
        result.put("out", out.toString());
        return result;
    }

    // ------------------------------------------------------------------------------------------ a mod's source

    static Map<String, Object> source(Path home, Modaccel.Options options, Modaccel.Report report, Path out) throws Exception {
        Path root = Path.of(options.get("root", ".")).toAbsolutePath().normalize();
        ProjectModel model = new ProjectModel(root);
        model.requireGradleBuild();
        String minecraft = options.get("minecraft", minecraftHint(model.discover()));
        if (minecraft.isBlank()) throw new IllegalArgumentException("Could not read the mod's Minecraft version from gradle.properties; pass --minecraft VERSION");
        Set<String> namespaces = options.values.containsKey("namespaces")
            ? new TreeSet<>(Arrays.asList(options.get("namespaces", "").split(",")))
            : modIds(root);
        namespaces.removeIf(String::isBlank);
        return record(home, root, options.get("project", ""), minecraft, namespaces, scenario(options, namespaces), report, out, "fingerprint");
    }

    /** The source path's whole run, for fingerprint --root and port --check fingerprint (before and after a port). */
    static Map<String, Object> record(Path home, Path root, String project, String minecraft, Set<String> namespaces, Map<String, Object> args,
        Modaccel.Report report, Path out, String name) throws Exception {
        ProjectModel model = new ProjectModel(root);
        Path work = report.directory.resolve(name);
        Files.createDirectories(work);
        Child child = new Child(home, root, project, work.resolve("logs"));
        long started = System.nanoTime();
        Path template = template(home, minecraft, longValue(args.get("seed")), report);
        Map<String, Object> content, reload;
        List<Object> logs = new ArrayList<>();
        try {
            child.run("game-stop", List.of(), 300);
            Map<String, Object> ui = child.ok("ui-check", List.of(), 1800);
            // A hidden window loses focus; with pauseOnLostFocus the game pauses, and a paused built-in server skips its ticks.
            child.ok("eval", List.of("--code", "mc.options.pauseOnLostFocus = false"), 300);
            Path gameDir = gameDirectory(child, ui);
            Path save = gameDir.resolve("saves").resolve(WORLD);
            PerfOptimize.deleteTree(save);
            PerfOptimize.copyTree(template, save);
            report.stages.add(Json.map("name", "fingerprint-world", "seconds", (System.nanoTime() - started) / 1e9, "save", save.toString(), "template", template.toString()));
            child.ok("world-check", List.of("--world", WORLD, "--freeze"), 1800);
            Path classes = probeClasses(home, root, minecraft);
            Map<String, Object> contentArgs = new LinkedHashMap<>(args);
            contentArgs.put("out", work.resolve("content.json").toString());
            content = inGame(child, home, minecraft, classes, "content", contentArgs, work);
            logs.add(log(gameDir, "content"));
            child.ok("game-stop", List.of(), 300);
            child.ok("world-check", List.of("--world", WORLD, "--freeze"), 1800);
            Map<String, Object> reloadArgs = Json.map("out", work.resolve("reload.json").toString(), "area", content.get("area"));
            reload = inGame(child, home, minecraft, classes, "reload", reloadArgs, work);
            logs.add(log(gameDir, "reload"));
        } finally {
            child.run("game-stop", List.of(), 300);
        }
        Map<String, Object> fingerprint = assemble(content, reload, logs);
        fingerprint.put("target", Json.map("root", root.toString(), "minecraft", minecraft, "loaders", loaders(model.discover()), "namespaces", new ArrayList<>(namespaces), "environment", "client"));
        Json.save(out, fingerprint);
        return summary(fingerprint, (System.nanoTime() - started) / 1e9);
    }

    /** The scenario's fixed numbers, from options or defaults. */
    static Map<String, Object> scenario(Modaccel.Options options, Set<String> namespaces) {
        return Json.map(
            "ticks", Integer.parseInt(options.get("ticks", "100")),
            "every", Integer.parseInt(options.get("every", "20")),
            "seed", Long.parseLong(options.get("seed", String.valueOf(SEED))),
            "namespaces", new ArrayList<>(namespaces)
        );
    }

    /** content and reload, with the round trip and the log, as one record. */
    static Map<String, Object> assemble(Map<String, Object> content, Map<String, Object> reload, List<Object> logs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("kind", "modaccel-fingerprint");
        for (String key : List.of("loader", "mods", "freezes", "frozenAtStart", "seeded", "ticksRun", "targets", "registries", "placement", "spawned", "checkpoints", "ticketsDuringRun", "area", "error")) {
            if (content.containsKey(key)) out.put(key, content.get(key));
        }
        List<Object> checkpoints = Json.array(content.getOrDefault("checkpoints", List.of()));
        if (!checkpoints.isEmpty()) {
            Map<String, Object> saved = Json.object(checkpoints.get(checkpoints.size() - 1));
            Map<String, Object> reloaded = Json.object(reload.getOrDefault("reloaded", Map.of()));
            out.put("roundTrip", roundTrip(saved, reloaded));
            out.put("reloaded", reloaded);
        }
        if (reload.containsKey("error")) out.put("reloadError", reload.get("error"));
        out.put("log", mergeLogs(logs));
        out.put("unfrozen", UNFROZEN);
        return out;
    }

    /**
     * What a save and a reload lost or changed: the last checkpoint against the same area read back. Scheduled ticks,
     * tickets and the random state are left out (a reload re-creates them), and so is the in-game time.
     */
    static Map<String, Object> roundTrip(Map<String, Object> saved, Map<String, Object> reloaded) {
        Map<String, String> a = FingerprintDiff.flatten(saved), b = FingerprintDiff.flatten(reloaded);
        Map<String, Object> differences = new TreeMap<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            if (key.startsWith("tick") || key.equals("rng") || key.matches("levels/[^/]+/(tickets|scheduledTicks|randomState|gameTime|dayTime)(/.*)?")) continue;
            String before = a.get(key), after = b.get(key);
            if (!Objects.equals(before, after)) differences.put(key, Json.map("saved", before, "reloaded", after));
        }
        return Json.map("same", differences.isEmpty(), "differences", differences.size(), "changed", FingerprintDiff.first(differences, 200));
    }

    static final List<String> UNFROZEN = List.of(
        "Code a mod runs in its own server or level tick events runs while the world is frozen, before the scenario starts.",
        "Randomness a mod keeps in its own static fields, or takes from ThreadLocalRandom, Math.random, new Random() or the clock.",
        "The order of hash maps and sets keyed by identity (identity hash codes differ in every run).",
        "Work on other threads: chunk generation, lighting, a mod's own executors.",
        "Before Minecraft 1.20.3 there is no tick freeze: the world runs from load until the scenario holds the server thread."
    );

    // ------------------------------------------------------------------------------------------ jars on a real server

    /**
     * The jars on their loader's real server, through pack-check: a pack of only these jars, whose start makes the
     * fingerprint world with the same probe, then a second start reads it back.
     */
    static Map<String, Object> jars(Path home, Modaccel.Options options, Modaccel.Report report, Path out) throws Exception {
        if (options.get("minecraft", "").isBlank() || options.get("loader", "").isBlank()) throw new IllegalArgumentException(
            "fingerprint --jar needs --minecraft VERSION and --loader neoforge|forge|fabric|quilt"
        );
        Path pack = report.directory.resolve("fingerprint-pack");
        Files.createDirectories(pack.resolve("mods"));
        for (String jar : options.get("jar", "").split(",")) {
            Path file = Path.of(jar.strip()).toAbsolutePath();
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No such jar: " + file);
            Files.copy(file, pack.resolve("mods").resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> argv = new ArrayList<>(List.of("pack-check", "--pack", pack.toString(), "--minecraft", options.get("minecraft", ""),
            "--loader", options.get("loader", ""), "--fingerprint", "--only", "modaccel:none", "--out", out.toString()));
        for (String key : List.of("ticks", "every", "seed", "namespaces", "memory")) if (options.values.containsKey(key)) {
            argv.add("--" + key);
            argv.add(options.get(key, ""));
        }
        Modaccel.Options packOptions = new Modaccel.Options(argv.toArray(String[]::new));
        long started = System.nanoTime();
        Map<String, Object> checked = new PackCheck(home, packOptions, report).run();
        if (!Files.isRegularFile(out)) throw new IOException("The server run wrote no fingerprint: " + checked.getOrDefault("details", checked));
        Map<String, Object> fingerprint = Json.read(out);
        return summary(fingerprint, (System.nanoTime() - started) / 1e9);
    }

    // ------------------------------------------------------------------------------------------ in the game

    /** Compiles the probe inside the running game (once per probe and game), starts the entry, and waits for it. */
    static Map<String, Object> inGame(Runner child, Path home, String minecraft, Path classes, String entry, Map<String, Object> args, Path work) throws Exception {
        Path snippet = work.resolve("start-" + entry + ".java");
        Files.writeString(snippet, bootstrap(home, minecraft, classes, entry, args));
        Map<String, Object> started = child.run("eval", List.of("--file", snippet.toString(), "--thread", "control"), 600);
        if (!Boolean.TRUE.equals(started.get("ok"))) throw new IOException("The fingerprint " + entry + " did not start: " + started.get("error"));
        // No quotes in a snippet passed as an argument: Windows' command line drops them. The key is spelled as chars.
        StringBuilder chars = new StringBuilder();
        for (char c : "modaccelFingerprint".toCharArray()) chars.append(chars.length() == 0 ? "" : ",").append((int) c);
        String key = "String.valueOf(new char[] {" + chars + "})";
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
        while (true) {
            Map<String, Object> waited = child.run("eval", List.of("--until", "((java.util.concurrent.Future<?>) vars.get(" + key + ")).isDone()", "--thread", "control", "--timeout", "110"), 300);
            if (Boolean.TRUE.equals(waited.get("ok"))) break;
            if (!String.valueOf(waited.get("error")).contains("Still not true")) throw new IOException("The fingerprint stopped answering: " + waited.get("error"));
            if (System.nanoTime() > deadline) throw new IOException("The fingerprint " + entry + " ran for more than 60 minutes");
        }
        Map<String, Object> done = child.run("eval", List.of("--code", "((java.util.concurrent.Future<?>) vars.get(" + key + ")).get()", "--thread", "control"), 300);
        if (!Boolean.TRUE.equals(done.get("ok"))) throw new IOException("The fingerprint " + entry + " failed in the game: " + done.get("error"));
        Path written = Path.of(args.get("out").toString());
        if (!Files.isRegularFile(written)) throw new IOException("The fingerprint " + entry + " wrote nothing; it answered " + Json.object(done.get("result")).get("value") + " (started: " + started.get("result") + ")");
        return Json.read(written);
    }

    /**
     * The eval snippet that compiles the probe against the running game's own classes (javac in the game's JVM, with
     * the game's class path), loads it beside the game's class loader and starts one entry on its own thread.
     */
    static String bootstrap(Path home, String minecraft, Path classes, String entry, Map<String, Object> args) throws Exception {
        List<String> sources = new ArrayList<>();
        for (String source : PROBE) sources.add(home.resolve(source).toString());
        sources.add(PackLoaders.gameLayer(home, minecraft).toString());
        sources.add(fingerprintLayer(home, minecraft).toString());
        List<String> stubs = new ArrayList<>();
        try (var walk = Files.walk(home.resolve("tools/packcheck/stubs"))) {
            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> stubs.add(p.toString()));
        }
        return String.join("\n",
            "// modaccel fingerprint: compiles the probe against this game's own classes once, then runs " + entry + " on its own thread.",
            "java.nio.file.Path classes = java.nio.file.Paths.get(" + quote(classes.toString()) + ");",
            "java.nio.file.Path stubs = classes.resolveSibling(\"stubs\");",
            "if (!java.nio.file.Files.isRegularFile(classes.resolve(\"modaccel/packprobe/ModaccelFingerprint.class\"))) {",
            "    javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();",
            "    if (javac == null) throw new IllegalStateException(\"The game runs on a JRE without a Java compiler\");",
            "    java.nio.file.Files.createDirectories(classes);",
            "    java.nio.file.Files.createDirectories(stubs);",
            "    java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();",
            "    int a = javac.run(null, errors, errors, " + list("\"-nowarn\", \"-proc:none\", \"-d\", stubs.toString()", stubs) + ");",
            "    String path = System.getProperty(\"java.class.path\") + java.io.File.pathSeparator + stubs;",
            "    int b = a != 0 ? a : javac.run(null, errors, errors, " + list("\"-nowarn\", \"-proc:none\", \"-encoding\", \"UTF-8\", \"-cp\", path, \"-d\", classes.toString()", sources) + ");",
            "    if (a != 0 || b != 0) throw new IllegalStateException(\"The fingerprint probe did not compile against this game:\\n\" + errors);",
            "}",
            "java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() }, mc.getClass().getClassLoader());",
            "Class<?> probe = Class.forName(\"modaccel.packprobe.ModaccelFingerprint\", true, loader);",
            "Object server = mc.getSingleplayerServer();",
            "if (server == null) throw new IllegalStateException(\"No world is open\");",
            "String arguments = " + quote(Json.write(args)) + ";",
            "java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(() -> probe.getMethod(" + quote(entry) + ", Object.class, String.class).invoke(null, server, arguments));",
            "vars.put(\"modaccelFingerprint\", task);",
            "Thread thread = new Thread(task, \"modaccel-fingerprint\");",
            "thread.setDaemon(true);",
            "thread.start();",
            "return \"started " + entry + "\";"
        ) + "\n";
    }

    static String list(String first, List<String> files) {
        StringBuilder text = new StringBuilder(first);
        for (String file : files) text.append(", ").append(quote(file));
        return text.toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 32 || c > 126) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Where the compiled probe is kept for this project: by the hash of its sources, so a changed probe compiles again. */
    static Path probeClasses(Path home, Path root, String minecraft) throws Exception {
        List<Path> inputs = new ArrayList<>();
        for (String source : PROBE) inputs.add(home.resolve(source));
        inputs.add(PackLoaders.gameLayer(home, minecraft));
        inputs.add(fingerprintLayer(home, minecraft));
        inputs.add(home.resolve("tools/packcheck/stubs"));
        String hash = Fingerprints.files(inputs).substring(0, 16);
        return root.resolve("build/modaccel/fingerprint/" + hash + "/classes");
    }

    /** The fingerprint's version layer: tools/packcheck/fingerprint/VERSION with the newest VERSION at or below the game's. */
    static Path fingerprintLayer(Path harness, String minecraft) throws IOException {
        Path root = harness.resolve("tools/packcheck/fingerprint");
        String best = null;
        try (var list = Files.list(root)) {
            for (Path dir : list.toList()) {
                String version = dir.getFileName().toString();
                if (Versions.compare(version, minecraft) <= 0 && (best == null || Versions.compare(version, best) > 0)) best = version;
            }
        }
        if (best == null) throw new IOException("The fingerprint has no game layer for Minecraft " + minecraft + ": tools/packcheck/fingerprint has none at or below it.");
        return root.resolve(best).resolve("ModaccelFingerprintGame.java");
    }

    // ------------------------------------------------------------------------------------------ the world

    /**
     * A flat world made by the vanilla dedicated server of the game version, kept per version and seed: fixed seed,
     * creative, easy, no structures, no mob, patrol, trader, phantom or warden spawning, time fixed at noon and clear
     * weather that stays. Both game-rule spellings are sent (1.21.11 renamed them); the one the game lacks is refused.
     */
    static Path template(Path home, String minecraft, long seed, Modaccel.Report report) throws Exception {
        Path cache = PackCheck.receipts().resolve("fingerprint-worlds").resolve(minecraft + "-" + seed);
        Path world = cache.resolve("world");
        if (Files.isRegularFile(world.resolve("level.dat")) && Files.isRegularFile(cache.resolve("done"))) return world;
        PackCheck.deleteTree(cache);
        Files.createDirectories(cache);
        Runtimes.Jdk java = Runtimes.ensureExact(home, PackLoaders.Vanilla.java(PackCheck.receipts(), home, minecraft));
        PackLoaders.Context context = new PackLoaders.Context(home, PackCheck.receipts(), report, java);
        PackLoaders.Vanilla.get(context, minecraft);
        Path jar = PackCheck.receipts().resolve("minecraft").resolve(minecraft).resolve("server.jar");
        Files.writeString(cache.resolve("eula.txt"), "eula=true\n");
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Files.writeString(cache.resolve("server.properties"), String.join("\n",
            "level-seed=" + seed,
            "level-type=" + (Versions.compare(minecraft, "1.19") >= 0 ? "minecraft\\:flat" : "flat"),
            "generate-structures=false",
            "difficulty=easy",
            "gamemode=creative",
            "spawn-protection=0",
            "online-mode=false",
            "server-ip=127.0.0.1",
            "server-port=" + port,
            "view-distance=4",
            "simulation-distance=4",
            "spawn-monsters=false",
            "spawn-animals=false",
            "spawn-npcs=false",
            "enable-rcon=false",
            "enable-query=false",
            "sync-chunk-writes=true",
            "max-tick-time=-1"
        ) + "\n");
        Path log = report.log("fingerprint-world-" + minecraft);
        long started = System.nanoTime();
        Process server = new ProcessBuilder(java.java().toString(), "-Xmx2G", "-jar", jar.toString(), "nogui")
            .directory(cache.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (Writer console = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (!Files.readString(log, StandardCharsets.ISO_8859_1).matches("(?s).*\\]: Done \\(.*")) {
                if (!server.isAlive()) throw new IOException("The vanilla server stopped before it finished starting: " + Run.tail(log));
                if (System.nanoTime() > deadline) throw new IOException("The vanilla server did not finish starting in 10 minutes: " + Run.tail(log));
                Thread.sleep(250);
            }
            List<String> commands = new ArrayList<>();
            for (String rule : List.of("doDaylightCycle", "doWeatherCycle", "doMobSpawning", "doPatrolSpawning", "doTraderSpawning", "doInsomnia", "doWardenSpawning",
                "advance_time", "advance_weather", "spawn_mobs", "spawn_patrols", "spawn_wandering_traders", "spawn_phantoms", "spawn_wardens")) commands.add("gamerule " + rule + " false");
            commands.addAll(List.of("time set 6000", "weather clear", "save-all flush", "stop"));
            for (String command : commands) {
                console.write(command + "\n");
                console.flush();
                Thread.sleep(command.startsWith("save-all") ? 1500 : 150);
            }
        } catch (IOException closed) {
            // the server closed its console when it stopped
        }
        if (!server.waitFor(5, TimeUnit.MINUTES)) {
            server.destroyForcibly();
            throw new IOException("The vanilla server did not stop: " + Run.tail(log));
        }
        if (!Files.isRegularFile(world.resolve("level.dat"))) throw new IOException("The vanilla server made no world: " + Run.tail(log));
        Files.deleteIfExists(world.resolve("session.lock"));
        Files.writeString(cache.resolve("done"), "made " + Instant.now() + "\n");
        report.stages.add(Json.map("name", "fingerprint-world-" + minecraft, "seconds", (System.nanoTime() - started) / 1e9, "log", log.toString()));
        return world;
    }

    static Path gameDirectory(Child child, Map<String, Object> started) throws Exception {
        Map<String, Object> status = child.ok("game-status", List.of(), 120);
        Object dir = Json.object(status.get("result")).get("gameDirectory");
        if (dir == null || dir.toString().isBlank()) throw new IOException("The running game did not say where its game directory is");
        return Path.of(dir.toString());
    }

    // ------------------------------------------------------------------------------------------ the log

    static final Pattern LOG_LINE = Pattern.compile("^\\[[^\\]]*\\] \\[([^\\]/]*)/(WARN|ERROR|FATAL)\\]\\s*(?:\\[([^\\]]*)\\])?:?\\s*(.*)$");

    /**
     * The game's warnings and errors, in a form two runs can compare: timestamps and threads dropped, then numbers that
     * differ by run (hashes, addresses, durations, counts of milliseconds) and paths replaced by placeholders.
     */
    static Map<String, Object> log(Path gameDir, String phase) throws IOException {
        return logFile(gameDir.resolve("logs/latest.log"), phase);
    }

    static Map<String, Object> logFile(Path latest, String phase) throws IOException {
        List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
        if (Files.isRegularFile(latest)) for (String line : new String(Files.readAllBytes(latest), StandardCharsets.UTF_8).split("\r?\n")) {
            Matcher m = LOG_LINE.matcher(line);
            if (!m.matches()) continue;
            String text = normalLogLine((m.group(3) == null ? "" : m.group(3) + ": ") + m.group(4));
            (m.group(2).equals("WARN") ? warnings : errors).add(text);
        }
        return Json.map("phase", phase, "warnings", warnings, "errors", errors);
    }

    static String normalLogLine(String text) {
        return text
            .replaceAll("[A-Za-z]:\\\\[^\\s:'\"]+|/(?:[\\w.-]+/)+[\\w.-]+", "<path>")
            .replaceAll("@[0-9a-fA-F]{4,}", "@<id>")
            .replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "<uuid>")
            .replaceAll("\\b\\d+(?:\\.\\d+)?\\s*(?:ms|s|ns|seconds|milliseconds)\\b", "<time>")
            .replaceAll("Can't keep up!.*", "Can't keep up!")
            .strip();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeLogs(List<Object> logs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String level : List.of("warnings", "errors")) {
            Map<String, Integer> counts = new TreeMap<>();
            for (Object phase : logs) for (Object line : Json.array(Json.object(phase).get(level))) counts.merge(line.toString(), 1, Integer::sum);
            out.put(level, counts);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ the mod

    static String minecraftHint(Map<String, Object> discovered) {
        for (Object build : Json.array(discovered.getOrDefault("builds", List.of()))) {
            Object hint = Json.object(build).get("minecraftHint");
            if (hint != null && !hint.toString().isBlank()) return hint.toString();
        }
        return "";
    }

    static List<Object> loaders(Map<String, Object> discovered) {
        Set<String> loaders = new TreeSet<>();
        for (Object build : Json.array(discovered.getOrDefault("builds", List.of()))) {
            if (Json.object(build).get("loaderHints") instanceof Collection<?> hints) for (Object loader : hints) loaders.add(loader.toString());
        }
        return new ArrayList<>(loaders);
    }

    static final Pattern TOML_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * The mod's namespaces: the ids its metadata's [[mods]] entries declare (a property like ${mod_id} filled from
     * gradle.properties), and the namespaces of its own assets folders. Dependencies' ids are not the mod's.
     */
    static Set<String> modIds(Path root) throws IOException {
        Properties properties = new Properties();
        Path gradle = root.resolve("gradle.properties");
        if (Files.isRegularFile(gradle)) try (Reader reader = Files.newBufferedReader(gradle, StandardCharsets.ISO_8859_1)) { properties.load(reader); }
        Set<String> ids = new TreeSet<>();
        try (var walk = Files.walk(root, 8)) {
            for (Path file : walk.filter(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                return !rel.matches("(?:.*/)?(?:build|\\.gradle|\\.runs|run|runs|bin|out)(?:/.*)?");
            }).toList()) {
                String name = file.getFileName().toString();
                Path parent = file.getParent();
                if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("assets") && Files.isDirectory(file)
                    && parent.getParent() != null && parent.getParent().getFileName().toString().equals("resources") && name.matches("[a-z0-9_.-]+")) {
                    if (!name.equals("minecraft")) ids.add(name);
                    continue;
                }
                if (!Set.of("mods.toml", "neoforge.mods.toml", "fabric.mod.json", "quilt.mod.json").contains(name)) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // Only the [[mods]] entries: what follows the first [[dependencies...]] header names other mods.
                if (!name.endsWith(".json")) text = text.split("(?m)^\\s*\\[\\[dependencies", 2)[0];
                Matcher m = (name.endsWith(".json") ? FABRIC_ID : TOML_ID).matcher(text);
                while (m.find()) {
                    String id = m.group(1);
                    Matcher placeholder = Pattern.compile("\\$\\{([^}]+)\\}").matcher(id);
                    if (placeholder.matches()) id = properties.getProperty(placeholder.group(1), "");
                    if (id.matches("[a-z0-9_.-]+") && !Set.of("minecraft", "neoforge", "forge", "fabricloader", "fabric", "java").contains(id)) ids.add(id);
                    if (name.endsWith(".json")) break;
                }
            }
        }
        return ids;
    }

    static Map<String, Object> summary(Map<String, Object> fingerprint, double seconds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !fingerprint.containsKey("error") && !fingerprint.containsKey("reloadError"));
        out.put("seconds", seconds);
        if (fingerprint.get("targets") instanceof Map<?, ?> targets) out.put("targets", targets);
        if (fingerprint.get("placement") instanceof Map<?, ?> placement) out.put("placement", Json.map("placed", placement.get("placed"), "unplaced", placement.get("unplaced"), "failed", placement.get("failed")));
        out.put("checkpoints", Json.array(fingerprint.getOrDefault("checkpoints", List.of())).size());
        if (fingerprint.get("roundTrip") instanceof Map<?, ?> trip) out.put("roundTrip", Json.map("same", trip.get("same"), "differences", trip.get("differences")));
        if (fingerprint.get("log") instanceof Map<?, ?> log) out.put("log", Json.map("warnings", Json.object(log.get("warnings")).size(), "errors", Json.object(log.get("errors")).size()));
        out.put("hash", FingerprintDiff.hash(fingerprint));
        out.put("details", String.format(Locale.ROOT, "Fingerprint %s in %.0f s: %s", out.get("hash"), seconds, Json.write(out)));
        return out;
    }

    static long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    // ------------------------------------------------------------------------------------------ child commands

    /**
     * modaccel commands run as children, each with its own receipt. A child of a command that holds the project's lock
     * (port --check fingerprint) is told so, and does not wait for that lock.
     */
    /** Runs one modaccel command for the project and returns its JSON answer. */
    interface Runner {
        Map<String, Object> run(String command, List<String> args, int timeout) throws Exception;
    }

    static final class Child implements Runner {
        final Path home, root, logs;
        final String project;
        int step;

        Child(Path home, Path root, String project, Path logs) {
            this.home = home;
            this.root = root;
            this.project = project;
            this.logs = logs;
        }

        Map<String, Object> ok(String command, List<String> args, int timeout) throws Exception {
            Map<String, Object> answer = run(command, args, timeout);
            // A game that crashes before its first frame (a loader reading its own config while it is rewritten) is
            // started once more; a second crash is the mod's or the setup's, and is reported.
            if (!Boolean.TRUE.equals(answer.get("ok")) && Set.of("ui-check", "world-check").contains(command) && String.valueOf(answer.get("error")).contains("did not start")) {
                run("game-stop", List.of(), 300);
                answer = run(command, args, timeout);
                answer.put("startedAgain", true);
            }
            if (!Boolean.TRUE.equals(answer.get("ok"))) throw new IOException("modaccel " + command + " " + String.join(" ", args) + " failed: " + answer.get("error"));
            return answer;
        }

        public Map<String, Object> run(String command, List<String> args, int timeout) throws Exception {
            List<String> argv = PerfOptimize.childJava(home);
            argv.add(command);
            argv.addAll(List.of("--root", root.toString()));
            if (!project.isBlank()) argv.addAll(List.of("--project", project));
            argv.addAll(args);
            argv.add("--json");
            Files.createDirectories(logs);
            String name = String.format(Locale.ROOT, "%03d-%s", step++, command);
            Path stdout = logs.resolve(name + ".json"), stderr = logs.resolve(name + ".log");
            ProcessBuilder builder = new ProcessBuilder(argv).directory(home.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            String held = System.getenv(ProjectLock.HELD);
            String own = ProjectLock.heldHere();
            if (own != null) builder.environment().put(ProjectLock.HELD, own);
            else if (held != null) builder.environment().put(ProjectLock.HELD, held);
            Process process = builder.start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
                return Json.map("ok", false, "error", command + " timed out after " + timeout + " s; see " + stderr);
            }
            String text = PerfOptimize.childOutput(stdout).strip();
            Map<String, Object> answer;
            try {
                answer = Json.object(Json.parse(text.substring(Math.max(0, text.lastIndexOf("\n{") + 1))));
            } catch (Exception unreadable) {
                answer = Json.map("ok", false, "error", "No JSON result (exit " + process.exitValue() + "): " + Run.tail(stderr));
            }
            if (!Boolean.TRUE.equals(answer.get("ok")) && !answer.containsKey("error")) {
                answer.put("error", Json.text(Json.object(answer.getOrDefault("result", Map.of())), "error", Run.tail(stderr)));
            }
            return answer;
        }
    }
}
