import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * data-check: what the game loads for the mod, read from the game's own code without launching the
 * game (no window, no world, no network). The mod's dev launch is recorded once (shared with
 * mixin-check) and started with tools/datacheck/ModaccelDataCheck in place of the game: Knot on the
 * dedicated-server side, Bootstrap, every mod's main and server entrypoints, the registries frozen,
 * then the data packs a new world enables loaded by the game's own WorldLoader. It reports what the
 * mod registered, each item as a real stack (its components bind only once data loads), and every
 * warning and error the game logged.
 *
 * The game's code stays loaded after the first check (one JVM per project, like the hidden client), and
 * each later check reads the data packs again from disk, as /reload does: a changed recipe, loot table
 * or tag costs a few CPU-seconds instead of a fresh start. Beside it waits a spare, started in the
 * background and stopped just before the mods' own code runs (Bootstrap, most of a start, is done). When
 * the project's classes change, the spare takes the new code if none of the classes it has already read
 * changed: the mods start and the data loads, a few CPU-seconds instead of a fresh start, and a new spare
 * starts behind it. Otherwise a new game is loaded. --once starts one for that check only and keeps
 * nothing; game-stop ends both.
 *
 * Fabric on Minecraft 26.x for now: the probe is compiled against the mod's own launch, so another
 * game version or loader is reported as unchecked with the compiler's reason, never guessed.
 */
final class DataCheck {
    static final List<String> SOURCES = List.of("tools/datacheck/ModaccelDataCheck.java", "tools/datacheck/ModaccelDataProbe.java");
    /** A mod only data-check's game loads (fabric.addMods): it skips start-up work a data check never reads. */
    static final List<String> MOD = List.of("tools/datacheck/mod/SkipShapeCache.java", "tools/datacheck/mod/fabric.mod.json", "tools/datacheck/mod/modaccel_datacheck.mixins.json");
    static final int TIMEOUT_SECONDS = 600;

    final Build build; final Map<String, Object> model;
    DataCheck(Build build, Map<String, Object> model) { this.build = build; this.model = model; }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        long begin = System.nanoTime();
        String loader = MixinCheck.loader(model);
        if (!loader.equals("fabric")) return unchecked(loader.isEmpty()
                ? "This build has no Fabric Loader among its evaluated dependencies, so there is no game code to load data with."
                : "data-check reads the game's data on Fabric only for now; this is a " + loader + " build. mixin-check and ui-check work here.");
        MixinCheck mixins = new MixinCheck(build, model);
        String task = mixins.clientTask(options);
        if (task == null) return unchecked("No client run task (runClient) in the selected project, so there is no dev launch to start the game's code from. Select --project, or pass --client-task.");
        MixinCheck.Launch launch = mixins.launch(task, loader, null, options.has("refresh"));
        Path classes;
        try { classes = tools(launch); }
        catch (Unsupported unsupported) { return unchecked(unsupported.getMessage()); }

        Path out = build.report.directory.resolve("data-check.jsonl"), argfile = build.report.directory.resolve("data-check.args");
        Files.createDirectories(out.getParent()); Files.deleteIfExists(out);
        Path log = build.report.log("data-check");
        boolean keep = !options.has("once");
        Map<String, String> files = codeFiles();
        String base = base(launch, classes), fingerprint = Fingerprints.bytes((base + "|" + files).getBytes(StandardCharsets.UTF_8)), how = null, spareNote = "";
        long started = System.nanoTime(); int exit = 0; Object warmup = null;
        Map<String, Object> loaded = keep ? live(build, fingerprint) : null;
        if (loaded != null && "ok".equals(ask(loaded, "check " + out, TIMEOUT_SECONDS))) {
            // Same code as the loaded game: reading the data again from disk is all a changed data file needs.
            how = "reused the loaded game (process " + loaded.get("pid") + "), data read again";
            Files.writeString(log, "data-check asked the loaded game (process " + loaded.get("pid") + ") to read the data again; its own log: " + loaded.get("log") + "\n");
        }
        if (how == null && keep) {
            List<String> why = new ArrayList<>();
            Map<String, Object> spare = readySpare(base, files, why);
            String answer = spare == null ? null : ask(spare, "go " + out, TIMEOUT_SECONDS);
            if (answer != null) {
                stop(build);
                Files.deleteIfExists(spareRecord(build)); Files.deleteIfExists(spareState(build));
                warmup = spare.get("cpu");
                Files.writeString(log, "data-check sent the changed code to the game loaded in advance (process " + spare.get("pid") + "); its own log: " + spare.get("log") + "\n");
                if ("ok".equals(answer)) {
                    spare.put("fingerprint", fingerprint); spare.remove("started"); spare.remove("cpu");
                    Json.save(state(build), spare);
                    how = "the code changed; the game loaded in advance (process " + spare.get("pid") + ") took it and stays loaded";
                } else { exit = 4; how = "the code changed; the game loaded in advance took it, and the mods failed to start"; }
            } else {
                if (spare != null) why.add("the game loaded in advance did not answer");
                stopSpare(build);
                if (!why.isEmpty()) spareNote = " (no game loaded in advance could take the code: " + String.join("; ", why) + ")";
            }
        }
        if (how == null) {
            boolean replaced = stop(build);
            String token = UUID.randomUUID().toString();
            List<String> command = command(launch, classes, out, argfile, !keep ? List.of() : List.of("-Dmodaccel.datacheck.serve=true",
                    "-Dmodaccel.datacheck.state=" + state(build), "-Dmodaccel.datacheck.token=" + token, "-Dmodaccel.datacheck.fingerprint=" + fingerprint));
            if (!keep) {
                exit = Run.command(command, launch.directory(), new HashMap<>(launch.environment()), log, TIMEOUT_SECONDS).exit();
                how = "started the game's code for this check only (--once)";
            } else {
                exit = startLoaded(command, launch, log, out, token);
                how = (replaced ? "the mod's code changed, so a new game was loaded" : "loaded the game's code") + spareNote + "; it stays loaded for the next data-check (game-stop ends it)";
            }
        }
        build.report.stage("data-check", new Run.Result(exit, log, (System.nanoTime() - started) / 1e9), Json.map("launch", launch.recorded() ? "recorded now" : "reused recording", "game", how));
        // The next code change finds a spare ready, started now while nothing waits on it.
        if (keep) ensureSpare(launch, classes, base, files);
        List<Map<String, Object>> events = new ArrayList<>();
        if (Files.isRegularFile(out)) for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) if (!line.isBlank()) events.add(Json.object(Json.parse(line)));
        Map<String, Object> verdict = verdict(events, exit, log.toString());
        verdict.put("game", how);
        // A spare's start was paid in the background, before this check: shown beside the check's own CPU.
        if (warmup != null) verdict.put("spareWarmupCpuSeconds", warmup);
        verdict.put("launch", Json.map("task", task, "recorded", launch.recorded(), "mode", launch.mode()));
        verdict.put("seconds", (System.nanoTime() - begin) / 1e9);
        return verdict;
    }

    // --- The loaded game: one JVM per project that reads the data again for each later check ---

    static Path state(Build build) { return build.project.state().resolve("data-check.json"); }

    /** What every game this check may use must share with the launch now: Java, options, class path, data-check's runner, the outputs. */
    String base(MixinCheck.Launch launch, Path classes) {
        return launch.java() + "|" + launch.options() + "|" + launch.classPath() + "|" + classes.getFileName() + "|" + outputs();
    }

    /**
     * Each of the project's class files and loader metadata files, with its hash: the loaded game answers
     * only while all are unchanged (a changed recipe or loot table is read again; changed code is not).
     */
    Map<String, String> codeFiles() throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String output : outputs()) {
            Path folder = Path.of(output); if (!Files.isDirectory(folder)) continue;
            try (var walk = Files.walk(folder)) { for (Path file : walk.filter(f -> f.toString().endsWith(".class")).toList()) files.put(file.toString(), Fingerprints.files(List.of(file))); }
        }
        for (Path file : earlyFiles().metadata()) files.put(file.toString(), Files.isRegularFile(file) ? Fingerprints.files(List.of(file)) : "missing");
        return files;
    }

    /**
     * What the game reads of the project before any of its code runs: fabric.mod.json, the mixin configs
     * and refmaps it names, access wideners and class tweakers (metadata), and the classes in each mixin
     * config's package, which Mixin reads as bytes while it changes the game's classes, without loading them.
     */
    record Early(List<Path> metadata, List<Path> mixinPackages) {}
    Early earlyFiles() {
        List<Path> metadata = new ArrayList<>(), packages = new ArrayList<>(); List<String> configs = new ArrayList<>();
        List<Path> roots = outputs().stream().map(Path::of).filter(Files::isDirectory).toList();
        for (Path root : roots) {
            Path mod = root.resolve("fabric.mod.json"); if (!Files.isRegularFile(mod)) continue;
            metadata.add(mod);
            try {
                Map<String, Object> json = Json.object(Json.parse(Files.readString(mod)));
                for (Object entry : json.get("mixins") instanceof List<?> list ? list : List.of())
                    configs.add(entry instanceof Map<?, ?> m ? String.valueOf(m.get("config")) : String.valueOf(entry));
                for (String key : List.of("accessWidener", "classTweaker")) if (json.get(key) instanceof String name) configs.add("#" + name);
            } catch (Exception unreadable) { /* a broken fabric.mod.json fails the start itself, and its bytes still count */ }
        }
        for (String config : configs) for (Path root : roots) {
            Path file = root.resolve(config.replaceFirst("^#", "")); if (!Files.isRegularFile(file)) continue;
            metadata.add(file);
            if (config.startsWith("#")) continue;
            try {
                Map<String, Object> json = Json.object(Json.parse(Files.readString(file)));
                if (json.get("refmap") instanceof String refmap) for (Path r : roots) metadata.add(r.resolve(refmap));
                if (json.get("package") instanceof String pkg) for (Path r : roots) packages.add(r.resolve(pkg.replace('.', '/')));
            } catch (Exception unreadable) { /* as above */ }
        }
        return new Early(metadata, packages);
    }

    /** The process a state file names, only while it is alive and is the same process (a pid can be reused after a restart). */
    static Optional<ProcessHandle> process(Map<String, Object> session) {
        if (!(session.get("pid") instanceof Number pid)) return Optional.empty();
        return ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive)
                .filter(p -> session.get("since") == null || p.info().startInstant().map(i -> i.toString().equals(session.get("since"))).orElse(false));
    }
    static String since(long pid) { return ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Object::toString).orElse(null); }

    /** The loaded game for this project when it is still running the same code, else null. */
    static Map<String, Object> live(Build build, String fingerprint) {
        try {
            if (!Files.isRegularFile(state(build))) return null;
            Map<String, Object> session = Json.read(state(build));
            return process(session).isPresent() && fingerprint.equals(session.get("fingerprint")) ? session : null;
        } catch (Exception unreadable) { return null; }
    }

    // --- The spare: started in the background, stopped before the mods' own code runs ---

    static Path spareRecord(Build build) { return build.project.state().resolve("data-check-spare-launch.json"); }
    static Path spareState(Build build) { return build.project.state().resolve("data-check-spare.json"); }

    /**
     * The spare, ready, when it can take the project's code as it is now, else null with the reason in why.
     * One started by the last check may still be starting: waiting for it is shorter than a new start.
     */
    Map<String, Object> readySpare(String base, Map<String, String> now, List<String> why) throws Exception {
        if (!Files.isRegularFile(spareRecord(build))) return null;
        Map<String, Object> record = Json.read(spareRecord(build));
        Optional<ProcessHandle> process = process(record);
        if (process.isEmpty()) { why.add("it had stopped"); return null; }
        if (!base.equals(record.get("base"))) { why.add("the launch or class path changed"); return null; }
        Map<String, Object> session = null;
        long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        while (session == null && System.nanoTime() < deadline && process.get().isAlive()) {
            if (Files.isRegularFile(spareState(build))) {
                try { var state = Json.object(Json.parse(Files.readString(spareState(build)))); if (record.get("token").equals(state.get("token"))) session = state; }
                catch (Exception halfWritten) { /* written by a move; read again */ }
            }
            if (session == null) Thread.sleep(100);
        }
        if (session == null) { why.add("it stopped while starting; its log: " + record.get("log")); return null; }
        String answer = ask(session, "loaded", 30);
        if (answer == null) { why.add("it did not answer"); return null; }
        List<String> changed = changedReads(record, now, ProjectModel.strings(Json.parse(answer)));
        if (!changed.isEmpty()) { why.add("it had already read " + String.join(", ", changed.subList(0, Math.min(5, changed.size()))) + (changed.size() > 5 ? " and " + (changed.size() - 5) + " more" : "") + ", which changed since"); return null; }
        session.put("log", record.get("log")); session.put("since", record.get("since"));
        return session;
    }

    /**
     * The files a spare has read that differ now, by name: the project's classes it has defined (loaded), its
     * loader metadata and every class in a mixin package (appeared or vanished ones too), against the hashes
     * taken when it started. Empty: the spare may take the code as it is now. Static so the self-test can feed it.
     */
    static List<String> changedReads(Map<String, Object> record, Map<String, String> now, Collection<String> loaded) {
        Map<String, Object> then = Json.object(record.get("files"));
        Set<String> read = new TreeSet<>(loaded); read.addAll(ProjectModel.strings(record.get("early")));
        Set<String> all = new TreeSet<>(then.keySet()); all.addAll(now.keySet());
        for (String file : all) for (Object pkg : Json.array(record.get("mixinPackages"))) if (Path.of(file).startsWith(Path.of(pkg.toString()))) read.add(file);
        List<String> changed = new ArrayList<>();
        for (String file : read) if (!Objects.equals(then.get(file), now.get(file))) changed.add(Path.of(file).getFileName().toString());
        return changed;
    }

    /** Starts a spare for this code unless one is already starting or waiting whose early reads still match. */
    void ensureSpare(MixinCheck.Launch launch, Path classes, String base, Map<String, String> files) throws Exception {
        if (Files.isRegularFile(spareRecord(build))) {
            Map<String, Object> record = Json.read(spareRecord(build));
            // Its own classes are read only at "go"; what it read before them must still match.
            if (process(record).isPresent() && base.equals(record.get("base")) && changedReads(record, files, List.of()).isEmpty()) return;
            stopSpare(build);
        }
        String token = UUID.randomUUID().toString();
        Path folder = build.project.state(), log = folder.resolve("data-check-spare.log");
        List<String> command = command(launch, classes, folder.resolve("data-check-spare.jsonl"), folder.resolve("data-check-spare.args"),
                List.of("-Dmodaccel.datacheck.spare=true", "-Dmodaccel.datacheck.state=" + spareState(build), "-Dmodaccel.datacheck.token=" + token));
        Files.deleteIfExists(spareState(build));
        ProcessBuilder builder = new ProcessBuilder(command).directory(launch.directory().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(launch.environment());
        Process process = builder.start();
        Early early = earlyFiles();
        Json.save(spareRecord(build), Json.map("token", token, "pid", process.pid(), "since", since(process.pid()), "base", base, "files", files, "log", log.toString(),
                "early", early.metadata().stream().map(Path::toString).toList(), "mixinPackages", early.mixinPackages().stream().map(Path::toString).toList()));
    }

    /** Stops this project's spare, starting or waiting, if one runs. True when there was one. */
    static boolean stopSpare(Build build) throws Exception {
        if (!Files.isRegularFile(spareRecord(build))) return false;
        Map<String, Object> record;
        try { record = Json.read(spareRecord(build)); } catch (Exception unreadable) { Files.deleteIfExists(spareRecord(build)); return false; }
        Optional<ProcessHandle> process = process(record);
        // Nothing of the game has started in a spare, so it can simply end.
        process.ifPresent(p -> { p.destroyForcibly(); try { p.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception slow) { } });
        Files.deleteIfExists(spareRecord(build)); Files.deleteIfExists(spareState(build));
        return process.isPresent();
    }

    /** The JVM command for this launch with data-check's runner in place of the game. */
    List<String> command(MixinCheck.Launch launch, Path classes, Path out, Path argfile, List<String> properties) throws IOException {
        List<String> command = MixinCheck.options(launch, "server");
        command.addAll(List.of("-Dmodaccel.datacheck.out=" + out, "-Dmodaccel.datacheck.outputs=" + String.join(File.pathSeparator, outputs())));
        // A JVM that runs for seconds spends more on C2 compiling than it gets back: C1 only halves its CPU, as for hidden games.
        if (command.stream().noneMatch(o -> o.startsWith("-XX:TieredStopAtLevel="))) command.add(1, "-XX:TieredStopAtLevel=1");
        // One collector thread and a heap that starts small: on Fabric 26.3, 2 to 3 CPU-s less per start and 380 MB
        // instead of 515 MB for a loaded game (G1 and its default 1/64-of-RAM starting heap), which matters with a
        // loaded game and a spare per project. A launch that sets its own collector or heap keeps it.
        if (command.stream().noneMatch(o -> o.matches("-XX:\\+Use\\w+GC"))) command.add(1, "-XX:+UseSerialGC");
        if (command.stream().noneMatch(o -> o.startsWith("-Xms") || o.startsWith("-XX:InitialHeapSize="))) command.add(1, "-Xms64m");
        // A spare lists the project's classes it has already read (ClassLoader.findLoadedClass).
        command.add(1, "--add-opens=java.base/java.lang=ALL-UNNAMED");
        Path mod = classes.resolve("mod");
        if (Files.isRegularFile(mod.resolve("fabric.mod.json"))) {
            int existing = -1; for (int i = 0; i < command.size(); i++) if (command.get(i).startsWith("-Dfabric.addMods=")) existing = i;
            if (existing < 0) command.add(1, "-Dfabric.addMods=" + mod); else command.set(existing, command.get(existing) + File.pathSeparator + mod);
        }
        command.addAll(properties);
        String main = MixinCheck.fabricMain(launch, "ModaccelDataCheck", command);
        MixinCheck.classPath(command, argfile, classes, launch);
        command.add(main); command.addAll(launch.arguments());
        Files.createDirectories(launch.directory());
        return command;
    }

    /** One request to the loaded game; its one-line answer, or null when it did not answer. */
    static String ask(Map<String, Object> session, String request, int timeoutSeconds) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), ((Number) session.get("port")).intValue()), 5000);
            socket.setSoTimeout(timeoutSeconds * 1000);
            var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(session.get("token") + " " + request);
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        } catch (Exception noAnswer) { return null; }
    }

    /** Stops this project's loaded game, if one runs. True when there was one. game-stop calls it too. */
    static boolean stop(Build build) throws Exception {
        if (!Files.isRegularFile(state(build))) return false;
        Map<String, Object> session;
        try { session = Json.read(state(build)); } catch (Exception unreadable) { Files.deleteIfExists(state(build)); return false; }
        var process = process(session);
        if (process.isPresent()) {
            ask(session, "quit", 10);
            try { process.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException stuck) { process.get().destroyForcibly(); }
        }
        Files.deleteIfExists(state(build));
        return process.isPresent();
    }

    /** Starts the game's code to stay loaded and waits for its first check: done, failed, or the JVM gone. */
    int startLoaded(List<String> command, MixinCheck.Launch launch, Path log, Path out, String token) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(launch.directory().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(launch.environment());
        Process process = builder.start();
        long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) return process.exitValue();
            // Serving starts once the first check has been written: the state file carries this start's token.
            Map<String, Object> session = Files.isRegularFile(state(build)) ? Json.object(Json.parse(Files.readString(state(build)))) : Map.of();
            if (token.equals(session.get("token"))) { session.put("log", log.toString()); session.put("since", since(process.pid())); Json.save(state(build), session); return 0; }
            Thread.sleep(200);
        }
        process.descendants().forEach(ProcessHandle::destroy); process.destroyForcibly();
        throw new IOException("data-check's game did not finish loading in " + TIMEOUT_SECONDS + " s. Log: " + log);
    }

    /** Every source set's output folders: a mod loaded from one of them is this project's own. */
    List<String> outputs() {
        List<String> outputs = new ArrayList<>();
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(p).get("sourceSets"))) outputs.addAll(ProjectModel.strings(Json.object(s).get("output")));
        return outputs;
    }

    static final class Unsupported extends IOException { Unsupported(String message) { super(message); } }

    /** The runner, compiled against this launch's own class path once per runner source and class path. */
    Path tools(MixinCheck.Launch launch) throws Exception {
        List<Path> sources = new ArrayList<>(); for (String name : SOURCES) sources.add(build.harness.resolve(name));
        List<Path> keyed = new ArrayList<>(sources); for (String name : MOD) keyed.add(build.harness.resolve(name));
        String key = Fingerprints.files(keyed).substring(0, 12) + "-" + Fingerprints.bytes(launch.classPath().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        Path classes = build.harness.resolve(".modaccel/data-check/" + key);
        if (Files.exists(classes.resolve("ready"))) return classes;
        Files.createDirectories(classes);
        // The game's classes are as new as the Java it runs on, so compile with that Java's compiler when it has one.
        Path launchJavac = Path.of(launch.java()).resolveSibling(Runtimes.WINDOWS ? "javac.exe" : "javac");
        String javac = Files.isRegularFile(launchJavac) ? launchJavac.toString()
                : Runtimes.ensureAtLeast(build.harness, 25).home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString();
        Path argfile = classes.resolve("javac.args");
        Files.writeString(argfile, "-cp\n\"" + launch.classPath().replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>(List.of(javac, "-nowarn", "-encoding", "UTF-8", "-proc:none", "-d", classes.toString(), "@" + argfile));
        sources.forEach(p -> command.add(p.toString()));
        var compiled = Run.command(command, build.project.root, Map.of(), build.report.log("data-check-tools"), 180);
        build.report.stage("data-check-tools", compiled, Map.of());
        if (compiled.exit() != 0)
            throw new Unsupported("data-check knows Minecraft 26.x's code (Mojang names); this game's code differs, so its data was not read. The compiler said:\n" + Run.tail(compiled.log()));
        // The helper mod is a speed-up only: when it does not compile against this game, the check runs without it.
        Path mod = classes.resolve("mod");
        List<String> modCommand = new ArrayList<>(List.of(javac, "-nowarn", "-encoding", "UTF-8", "-proc:none", "-d", mod.toString(), "@" + argfile, build.harness.resolve(MOD.get(0)).toString()));
        var helper = Run.command(modCommand, build.project.root, Map.of(), build.report.log("data-check-mod"), 180);
        build.report.stage("data-check-mod", helper, Map.of());
        if (helper.exit() == 0) for (String name : MOD.subList(1, MOD.size())) Files.copy(build.harness.resolve(name), mod.resolve(Path.of(name).getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(classes.resolve("ready"), "ok");
        return classes;
    }

    Map<String, Object> unchecked(String reason) {
        return Json.map("ok", false, "tier", "data-unchecked", "checked", false, "loader", MixinCheck.loader(model), "reason", reason, "details", reason, "gameVerified", false);
    }

    /** The probe's events, sorted into what a world would see. Static so the self-test can feed it events. */
    static Map<String, Object> verdict(List<Map<String, Object>> events, int exit, String log) {
        boolean started = false, done = false, first = true; double cpu = -1, from = 0;
        List<String> ours = new ArrayList<>(); List<Object> registered = new ArrayList<>(), items = new ArrayList<>(), errors = new ArrayList<>(), warnings = new ArrayList<>(), failures = new ArrayList<>();
        Map<String, Object> phases = new LinkedHashMap<>(); Object packs = null;
        for (Map<String, Object> event : events) {
            switch (Json.text(event, "event", "")) {
                case "start" -> { started = true; first = !Boolean.FALSE.equals(event.get("first")); if (!first && event.get("cpu") instanceof Number n) from = n.doubleValue(); }
                // The first check counts the whole JVM; a check in the loaded game counts only its own work.
                case "done" -> { done = true; if (event.get("cpu") instanceof Number n) cpu = Math.round((n.doubleValue() - from) * 10) / 10.0; }
                case "phase" -> phases.put(Json.text(event, "name", ""), event.get("cpu"));
                case "mods" -> ours.addAll(ProjectModel.strings(event.get("ours")));
                case "packs" -> packs = event.get("selected");
                case "registered" -> registered.add(Json.map("mod", event.get("mod"), "counts", event.get("counts")));
                case "item" -> {
                    items.add(event);
                    if (event.get("error") != null) failures.add(Json.map("kind", "item", "id", event.get("id"), "message", event.get("error"),
                            "meaning", "This item cannot be made into a stack once data has loaded, so the game would fail wherever it creates one."));
                }
                case "log" -> {
                    Map<String, Object> entry = Json.map("level", event.get("level"), "logger", event.get("logger"), "message", event.get("message"), "thrown", event.get("thrown"));
                    (Json.text(event, "level", "").equals("WARN") ? warnings : errors).add(entry);
                }
                case "error" -> failures.add(Json.map("kind", Json.text(event, "phase", ""), "message", event.get("message"), "trace", event.get("trace"),
                        "meaning", "The game's own code stopped while loading; a real start would stop here too."));
                default -> { }
            }
        }
        boolean ran = started && done && exit == 0;
        StringBuilder details = new StringBuilder();
        if (!ran) {
            details.append("data-check did not complete");
            if (!failures.isEmpty()) details.append(": ").append(Json.object(failures.get(failures.size() - 1)).get("message"));
            details.append(". Log: ").append(log);
            // What the game itself said before it stopped names the file and the reason.
            for (Object e : errors) details.append("\n  [error] ").append(Json.object(e).get("logger")).append(": ").append(Json.object(e).get("message"));
        } else if (ours.isEmpty()) {
            details.append("The game loaded, but no mod from this project was among its mods, so nothing of the mod was checked.");
        } else {
            for (Object r : registered) {
                Map<String, Object> mod = Json.object(r); Map<String, Object> counts = Json.object(mod.get("counts"));
                List<String> parts = new ArrayList<>(); counts.forEach((k, v) -> parts.add(v + " " + k.replace('_', ' ')));
                details.append(mod.get("mod")).append(": ").append(parts.isEmpty() ? "nothing registered" : String.join(", ", parts)).append(".\n");
            }
            for (Object i : items) {
                Map<String, Object> item = Json.object(i);
                if (item.get("error") != null) continue;
                details.append("  ").append(item.get("id")).append(" \"").append(item.get("name")).append("\": stacks to ").append(item.get("maxStack"));
                if (item.get("maxDamage") instanceof Number d && d.intValue() > 0) details.append(", durability ").append(d.intValue());
                if (item.get("nutrition") != null) details.append(", food ").append(item.get("nutrition"));
                details.append(", ").append(item.get("rarity")).append("\n");
            }
            details.append(errors.size()).append(" error").append(errors.size() == 1 ? "" : "s").append(" and ").append(warnings.size()).append(" warning").append(warnings.size() == 1 ? "" : "s")
                    .append(" logged by the game while loading");
            for (Object e : errors) details.append("\n  [error] ").append(Json.object(e).get("logger")).append(": ").append(Json.object(e).get("message"));
            for (Object f : failures) details.append("\n  [").append(Json.object(f).get("kind")).append("] ").append(Json.object(f).get("id")).append(": ").append(Json.object(f).get("message"));
        }
        boolean ok = ran && !ours.isEmpty() && errors.isEmpty() && failures.isEmpty();
        Map<String, Object> result = Json.map("ok", ok, "tier", "data-loaded", "checked", ran && !ours.isEmpty(),
                "checkedBy", "Minecraft's own Bootstrap, the mods' entrypoints and WorldLoader with a new world's data packs, in Fabric Loader's Knot (server side); no window, no world",
                "mods", ours, "registered", registered, "items", items, "packs", packs, "errors", errors, "warnings", warnings, "failures", failures,
                "cpuSeconds", cpu < 0 ? null : cpu, "firstInJvm", first, "phaseCpu", phases, "gameVerified", false, "behaviorVerified", false, "details", details.toString().strip());
        if (!ok) result.put("reason", details.toString().strip());
        return result;
    }
}
