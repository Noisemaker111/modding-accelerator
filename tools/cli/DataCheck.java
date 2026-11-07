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
 * or tag costs a few CPU-seconds instead of a fresh start. Changed classes or loader metadata start a new
 * one. --once starts it for that check only; game-stop ends it.
 *
 * Fabric on Minecraft 26.x for now: the probe is compiled against the mod's own launch, so another
 * game version or loader is reported as unchecked with the compiler's reason, never guessed.
 */
final class DataCheck {
    static final List<String> SOURCES = List.of("tools/datacheck/ModaccelDataCheck.java", "tools/datacheck/ModaccelDataProbe.java");
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
        String fingerprint = codeFingerprint(launch, classes), how;
        long started = System.nanoTime(); int exit;
        Map<String, Object> loaded = keep ? live(build, fingerprint) : null;
        if (loaded != null && "ok".equals(ask(loaded, "check " + out, TIMEOUT_SECONDS))) {
            // Same code as the loaded game: reading the data again from disk is all a changed data file needs.
            how = "reused the loaded game (process " + loaded.get("pid") + "), data read again"; exit = 0;
            Files.writeString(log, "data-check asked the loaded game (process " + loaded.get("pid") + ") to read the data again; its own log: " + loaded.get("log") + "\n");
        } else {
            boolean replaced = stop(build);
            List<String> command = MixinCheck.options(launch, "server");
            command.addAll(List.of("-Dmodaccel.datacheck.out=" + out, "-Dmodaccel.datacheck.outputs=" + String.join(File.pathSeparator, outputs())));
            // A JVM that runs for seconds spends more on C2 compiling than it gets back: C1 only halves its CPU, as for hidden games.
            if (command.stream().noneMatch(o -> o.startsWith("-XX:TieredStopAtLevel="))) command.add(1, "-XX:TieredStopAtLevel=1");
            String token = UUID.randomUUID().toString();
            if (keep) command.addAll(List.of("-Dmodaccel.datacheck.serve=true", "-Dmodaccel.datacheck.state=" + state(build), "-Dmodaccel.datacheck.token=" + token,
                    "-Dmodaccel.datacheck.fingerprint=" + fingerprint));
            String main = MixinCheck.fabricMain(launch, "ModaccelDataCheck", command);
            MixinCheck.classPath(command, argfile, classes, launch);
            command.add(main); command.addAll(launch.arguments());
            Files.createDirectories(launch.directory());
            if (!keep) {
                exit = Run.command(command, launch.directory(), new HashMap<>(launch.environment()), log, TIMEOUT_SECONDS).exit();
                how = "started the game's code for this check only (--once)";
            } else {
                exit = startLoaded(command, launch, log, out, token);
                how = (replaced ? "the mod's code changed, so a new game was loaded" : "loaded the game's code") + "; it stays loaded for the next data-check (game-stop ends it)";
            }
        }
        build.report.stage("data-check", new Run.Result(exit, log, (System.nanoTime() - started) / 1e9), Json.map("launch", launch.recorded() ? "recorded now" : "reused recording", "game", how));
        List<Map<String, Object>> events = new ArrayList<>();
        if (Files.isRegularFile(out)) for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) if (!line.isBlank()) events.add(Json.object(Json.parse(line)));
        Map<String, Object> verdict = verdict(events, exit, log.toString());
        verdict.put("game", how);
        verdict.put("launch", Json.map("task", task, "recorded", launch.recorded(), "mode", launch.mode()));
        verdict.put("seconds", (System.nanoTime() - begin) / 1e9);
        return verdict;
    }

    // --- The loaded game: one JVM per project that reads the data again for each later check ---

    static Path state(Build build) { return build.project.state().resolve("data-check.json"); }

    /**
     * What decides whether the loaded game can answer: the mod's compiled classes and loader metadata (a
     * changed recipe or loot table is read again; changed code needs a new JVM), the launch's class path
     * and data-check's own runner.
     */
    String codeFingerprint(MixinCheck.Launch launch, Path classes) throws Exception {
        List<Path> code = new ArrayList<>();
        for (String output : outputs()) {
            Path folder = Path.of(output); if (!Files.isDirectory(folder)) continue;
            try (var walk = Files.walk(folder)) {
                walk.filter(Files::isRegularFile).filter(f -> { String n = f.getFileName().toString(); return n.endsWith(".class") || n.equals("fabric.mod.json") || n.endsWith(".mixins.json")
                        || n.endsWith(".accesswidener") || n.endsWith(".classtweaker"); }).forEach(code::add);
            }
        }
        return Fingerprints.bytes((Fingerprints.files(code) + "|" + launch.classPath() + "|" + classes.getFileName()).getBytes(StandardCharsets.UTF_8));
    }

    /** The loaded game for this project when it is still running the same code, else null. */
    static Map<String, Object> live(Build build, String fingerprint) {
        try {
            if (!Files.isRegularFile(state(build))) return null;
            Map<String, Object> session = Json.read(state(build));
            boolean alive = ProcessHandle.of(((Number) session.get("pid")).longValue()).map(ProcessHandle::isAlive).orElse(false);
            return alive && fingerprint.equals(session.get("fingerprint")) ? session : null;
        } catch (Exception unreadable) { return null; }
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
        var process = ProcessHandle.of(((Number) session.get("pid")).longValue()).filter(ProcessHandle::isAlive);
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
            if (token.equals(session.get("token"))) { session.put("log", log.toString()); Json.save(state(build), session); return 0; }
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
        String key = Fingerprints.files(sources).substring(0, 12) + "-" + Fingerprints.bytes(launch.classPath().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
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
                "cpuSeconds", cpu, "firstInJvm", first, "phaseCpu", phases, "gameVerified", false, "behaviorVerified", false, "details", details.toString().strip());
        if (!ok) result.put("reason", details.toString().strip());
        return result;
    }
}
