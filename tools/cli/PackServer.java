import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * pack-run --players N: a modpack's own dedicated server, started the way its players host it, with N stand-in players
 * on it (tools/packcheck/MmtCrowd.java), and left running for perf (--instance FOLDER), game-status and game-stop
 * (--root FOLDER). The loader's server is the one pack-check installs; the pack's mods are the ones pack-check would run
 * on a server (client-only ones left out); its configs are copied once into an instance folder that keeps its world
 * between starts unless --world gives one.
 */
final class PackServer {

    /** In an instance's state folder: the server pack-run started there, so game-status and game-stop accept it as --root. */
    static final String MARKER = "pack-server.json";

    private PackServer() {}

    static boolean instance(Path root) {
        return Files.isRegularFile(state(root).resolve(MARKER));
    }

    static Path state(Path root) {
        return root.toAbsolutePath().normalize().resolve(".mmt/portable");
    }

    static Map<String, Object> run(Path harness, Mmt.Options options, Mmt.Report report) throws Exception {
        Path input = Path.of(options.get("pack", "")).toAbsolutePath().normalize();
        if (options.get("pack", "").isEmpty() || !Files.exists(input)) throw new IllegalArgumentException(
            "Name the pack: mmt pack-run --pack PACK.zip|PACK.mrpack|FOLDER --players N. Got: " + options.get("pack", "(nothing)")
        );
        int players = Integer.parseInt(options.get("players", "4"));
        int moving = Integer.parseInt(options.get("moving", "0"));
        if (players < 0 || moving < 0 || moving > players) throw new IllegalArgumentException("--moving must be between 0 and --players");
        long started = System.nanoTime();
        PackCheck check = new PackCheck(harness, options, report);
        PackCheck.Pack pack = check.read(input);
        if (!check.unavailable.isEmpty()) throw new IllegalArgumentException(
            check.unavailable.size() + " mod file(s) the pack lists cannot be downloaded any more: " +
            String.join("; ", check.unavailable.stream().map(PackCheck.Unavailable::describe).toList()) +
            ". Run mmt pack-check --pack PACK --fix and start its fixed copy."
        );
        PackLoaders.Loader loader = PackLoaders.find(harness, pack.loader());
        if (loader == null) throw new IllegalArgumentException("pack-run has no adapter for the loader \"" + pack.loader() + "\"");
        Path cache = PackCheck.receipts();
        Runtimes.Jdk java = Runtimes.ensureExact(harness, PackLoaders.Vanilla.java(cache, harness, pack.minecraft()));
        PackLoaders.Context context = new PackLoaders.Context(harness, cache, report, java);
        if (pack.loaderVersion().isBlank()) pack = pack.withLoaderVersion(loader.newest(context, pack.minecraft()));
        PackMods.Scan scan = PackMods.scan(pack.mods(), loader.metadata(pack.minecraft()));
        List<PackCheck.Mod> mods = PackCheck.sides(pack.mods(), scan);
        Path install = loader.install(context, pack.minecraft(), pack.loaderVersion());
        Runtimes.Jdk suited = PackLoaders.javaFor(context, install);
        if (!suited.home().equals(java.home())) context = new PackLoaders.Context(harness, cache, report, suited);
        mods = PackCheck.clientCode(mods, PackLoaders.gameClasses(context, loader, install, pack.minecraft()));
        List<String> without = Arrays.stream(options.get("without", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        Map<String, String> leftOut = new LinkedHashMap<>(PackClient.leftOut(mods, scan, without));
        Map<String, Path> replaced = PackClient.replacements(options, scan, loader, pack.minecraft());
        check.readDropped();
        PackCheck.ServerMods chosen = PackCheck.serverMods(mods, scan, check.dropped, leftOut.keySet());
        for (var e : chosen.leftOut().entrySet()) leftOut.putIfAbsent(e.getKey(), e.getValue());
        Path probe = PackLoaders.probe(context, loader, pack.minecraft(), install);
        report.stages.add(Json.map("name", "read", "seconds", (System.nanoTime() - started) / 1e9));

        Path instance = options.values.containsKey("dir")
            ? Path.of(options.get("dir", "")).toAbsolutePath().normalize()
            : cache.resolve("servers").resolve(PackClient.key(input, without));
        Path state = state(instance);
        Map<String, Object> running = Files.isRegularFile(state.resolve(MARKER)) ? Json.read(state.resolve(MARKER)) : null;
        if (running != null && running.get("pid") != null && Game.alive(running)) throw new IllegalStateException(
            "A server of this pack is already running (process " + running.get("pid") + "). Stop it: mmt game-stop --root \"" + instance + "\""
        );
        boolean fresh = !Files.isDirectory(instance.resolve("config"));
        Files.createDirectories(state);
        // The pack's own files are copied once; a later start keeps what the server changed since, and its world.
        if (fresh) for (Path layer : pack.layers()) try (var list = Files.list(layer)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.equals("mods") || name.equals(".mmt-unpacked") || name.equals("saves") || name.equals("options.txt")) continue;
                if (Files.isDirectory(p)) PackCheck.copyTree(p, instance.resolve(name));
                else if (!name.endsWith(".json") && !name.endsWith(".html")) Files.copy(p, instance.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path modsFolder = instance.resolve("mods");
        PackCheck.deleteTree(modsFolder);
        Files.createDirectories(modsFolder);
        for (PackCheck.Mod mod : mods) {
            if (!chosen.kept().contains(mod.file())) continue;
            if (replaced.containsKey(mod.file())) Files.copy(replaced.get(mod.file()), modsFolder.resolve(mod.file()));
            else PackCheck.link(mod.jar(), modsFolder.resolve(mod.file()));
        }
        PackCheck.link(probe, modsFolder.resolve("zz-mmt-pack-probe.jar"));
        // --world SAVE: a copy of that save, fresh each start, so every start begins from the same world.
        String world = options.get("world", "");
        if (!world.isEmpty()) {
            Path save = Path.of(world).toAbsolutePath().normalize();
            if (!Files.isRegularFile(save.resolve("level.dat"))) throw new IllegalArgumentException("--world names no save (no level.dat): " + save);
            PackCheck.deleteTree(instance.resolve("world"));
            PackCheck.copyTree(save, instance.resolve("world"));
            // A singleplayer save keeps its player in level.dat; on a server that player would be nobody.
        }
        serverFiles(instance, options);
        Path out = state.resolve("crowd.json");
        Path stop = state.resolve("stop");
        Files.deleteIfExists(out);
        Files.deleteIfExists(stop);
        List<String> command = new ArrayList<>(List.of(
            context.java().java().toString(),
            "-Xmx" + options.get("memory", "10G"),
            // The G1 options server hosts copy from the official launcher's.
            "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20",
            "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M",
            "-Dmmt.probe.mode=crowd",
            "-Dmmt.probe.out=" + out,
            "-Dmmt.crowd.players=" + players,
            "-Dmmt.crowd.moving=" + moving,
            "-Dmmt.crowd.speed=" + options.get("speed", "10"),
            "-Dmmt.crowd.spacing=" + options.get("spacing", "512"),
            "-Dmmt.crowd.hours=" + options.get("hours", "3"),
            "-Dmmt.crowd.stop=" + stop
        ));
        if (!options.get("commands", "").isEmpty()) {
            Path commands = Path.of(options.get("commands", "")).toAbsolutePath().normalize();
            if (!Files.isRegularFile(commands)) throw new IllegalArgumentException("--commands names no file: " + commands);
            command.add("-Dmmt.crowd.commands=" + commands);
        }
        command.addAll(loader.launch(install, instance, pack.minecraft(), pack.loaderVersion()));
        command.add("nogui");
        Map<String, Object> marker = Json.map(
            "pack", (pack.name() + " " + pack.version()).strip(), "input", input.toString(), "minecraft", pack.minecraft(),
            "loader", pack.loader() + " " + pack.loaderVersion(), "mods", chosen.kept().size(), "leftOut", leftOut,
            "players", players, "moving", moving, "stop", stop.toString(), "command", command
        );
        Json.save(state.resolve(MARKER), marker);
        if (options.has("dry-run")) return Json.map(
            "ok", true, "instance", instance.toString(), "mods", chosen.kept().size(), "leftOut", leftOut, "command", command,
            "details", "Ready, not started (--dry-run): " + chosen.kept().size() + " mods in " + instance + "; " + leftOut.size() + " left out"
        );
        Path log = report.log("server");
        Files.createDirectories(log.getParent());
        long launched = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        marker.put("pid", process.pid());
        marker.put("processStart", process.toHandle().info().startInstant().map(Instant::toString).orElse(""));
        marker.put("log", log.toString());
        Json.save(state.resolve(MARKER), marker);
        Map<String, Object> crowd;
        try {
            crowd = awaitReady(process, out, log, loader, Integer.parseInt(options.get("timeout", "600")));
        } catch (Exception failed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw failed;
        }
        double seconds = (System.nanoTime() - launched) / 1e9;
        report.stages.add(Json.map("name", "server-start", "seconds", seconds, "log", log.toString()));
        List<Object> failedCommands = new ArrayList<>();
        for (Object o : Json.array(crowd.getOrDefault("commands", List.of()))) {
            String result = Json.text(Json.object(o), "result", "");
            if (!result.matches("[1-9][0-9]*")) failedCommands.add(o);
        }
        return Json.map(
            "ok", true,
            "pack", (pack.name() + " " + pack.version()).strip(),
            "loader", pack.loader() + " " + pack.loaderVersion(),
            "minecraft", pack.minecraft(),
            "instance", instance.toString(),
            "pid", process.pid(),
            "mods", chosen.kept().size(),
            "leftOut", leftOut,
            "crowd", crowd,
            "commandsThatDidNothing", failedCommands,
            "startSeconds", seconds,
            "log", log.toString(),
            "details", String.format(Locale.ROOT,
                "%s server running (process %d, %d mods, %d stand-in players, %d of them moving, ready after %.0f s)%s.%nInstance: %s%n" +
                "Profile it: mmt perf --instance \"%s\". Stop it: mmt game-stop --root \"%s\".",
                (pack.name() + " " + pack.version()).strip(), process.pid(), chosen.kept().size(), players, moving, seconds,
                failedCommands.isEmpty() ? "" : "; " + failedCommands.size() + " command(s) changed nothing (see crowd.commands)",
                instance, instance, instance)
        );
    }

    /** eula and server.properties for a local, offline test server: a free port, no tick watchdog, the view the options ask. */
    static void serverFiles(Path instance, Mmt.Options options) throws Exception {
        Files.writeString(
            instance.resolve("eula.txt"),
            "# Accepted by mmt pack-run for a local offline test server (127.0.0.1 only).\neula=true\n"
        );
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path file = instance.resolve("server.properties");
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.ISO_8859_1) : "";
        Map<String, String> set = new LinkedHashMap<>();
        set.put("server-ip", "127.0.0.1");
        set.put("server-port", String.valueOf(port));
        set.put("online-mode", "false");
        set.put("spawn-protection", "0");
        set.put("max-tick-time", "-1");
        set.put("level-name", "world");
        set.put("max-players", "100");
        set.put("motd", "mmt pack-run");
        String view = options.get("view-distance", "10");
        set.put("view-distance", view);
        set.put("simulation-distance", view);
        if (!options.get("seed", "").isEmpty()) set.put("level-seed", options.get("seed", ""));
        for (var e : set.entrySet()) {
            Pattern line = Pattern.compile("(?m)^" + Pattern.quote(e.getKey()) + "=.*$");
            text = line.matcher(text).find() ? line.matcher(text).replaceAll(e.getKey() + "=" + java.util.regex.Matcher.quoteReplacement(e.getValue())) : text + e.getKey() + "=" + e.getValue() + "\n";
        }
        Files.writeString(file, text, StandardCharsets.ISO_8859_1);
        PackCheck.ServerLane.voiceChatPort(instance);
    }

    /** Until the stand-in players are on (the probe's MMT-CROWD-READY), the server fails, or its log is silent for too long. */
    static Map<String, Object> awaitReady(Process process, Path out, Path log, PackLoaders.Loader loader, int quietSeconds) throws Exception {
        long last = System.nanoTime(), size = -1;
        long ceiling = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
        while (true) {
            String text = Files.isRegularFile(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
            if (text.contains("MMT-CROWD-READY") && Files.isRegularFile(out)) return Json.read(out);
            if (text.contains("MMT-PROBE-DONE")) throw new java.io.IOException(
                "The stand-in players could not be added: " + (Files.isRegularFile(out) ? Json.text(Json.read(out), "error", "") : "") + "\nLog: " + log
            );
            if (!process.isAlive()) throw new java.io.IOException("The server stopped before its players joined (exit " + process.exitValue() + "). Log: " + log + PackClient.crash(log));
            if (text.length() != size) {
                size = text.length();
                last = System.nanoTime();
            }
            if (System.nanoTime() - last > TimeUnit.SECONDS.toNanos(quietSeconds)) throw new java.io.IOException(
                "The server's log said nothing for " + quietSeconds + " s before its players joined. Log: " + log
            );
            if (System.nanoTime() > ceiling) throw new java.io.IOException("The server's players were not on in 60 minutes. Log: " + log);
            Thread.sleep(2000);
        }
    }

    /** game-status / game-stop --root INSTANCE for a server pack-run started. */
    static Map<String, Object> control(Path root, String command) throws Exception {
        Path state = state(root);
        Map<String, Object> marker = Json.read(state.resolve(MARKER));
        boolean alive = marker.get("pid") != null && Game.alive(marker);
        if (command.equals("game-status")) return Json.map(
            "ok", true, "running", alive, "pid", marker.get("pid"), "players", marker.get("players"), "log", marker.get("log"),
            "details", alive ? "Server running (process " + marker.get("pid") + ")." : "No server running in " + root
        );
        if (!alive) return Json.map("ok", true, "stopped", false, "details", "No server running in " + root);
        // The probe sees the stop file within a tick, saves the world and stops the server, as /stop does.
        Files.writeString(Path.of(Json.text(marker, "stop", state.resolve("stop").toString())), "stop\n");
        ProcessHandle process = ProcessHandle.of(((Number) marker.get("pid")).longValue()).orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(500);
        boolean saved = !process.isAlive();
        if (!saved) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        return Json.map(
            "ok", true, "stopped", true, "saved", saved,
            "details", saved ? "Server stopped and saved (process " + marker.get("pid") + ")." : "Server did not stop in 3 minutes; ended it without saving."
        );
    }
}
