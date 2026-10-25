import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Native framebuffer checks through an owned Java-agent session, with event-driven startup. */
final class Game {
    final Build build; final Map<String, Object> model;
    Game(Build build, Map<String, Object> model) { this.build = build; this.model = model; }
    Map<String, Object> session() throws Exception {
        Path file = build.project.state().resolve("agent.json");
        if (!Files.exists(file)) return null;
        Map<String, Object> value = Json.read(file);
        if (!build.project.root.toString().equals(value.get("root"))) throw new IOException("Game session belongs to a different project.");
        long pid = ((Number)value.get("pid")).longValue();
        var process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) return null;
        String recorded = Json.text(value, "processStart", "");
        if (recorded.isEmpty() || !process.get().info().startInstant().map(Instant::toString).orElse("").equals(recorded)) return null;
        return value;
    }
    /**
     * Reusable visual gate: a key, click or screen action must change the real frame by at
     * least the required percentage. It catches output that renders but is invisible.
     */
    static Map<String, Object> compare(Path before, Path after, double required) throws Exception {
        if (required <= 0 || required > 100) throw new IllegalArgumentException("--expect-change needs a percentage above 0 and at most 100");
        var first = ImageIO.read(before.toFile()); var second = ImageIO.read(after.toFile());
        if (first == null || second == null) throw new IOException("Cannot compare captures: " + before + " and " + after);
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) throw new IOException("Captures have different sizes: " + before + " and " + after);
        long changed = 0, total = (long) first.getWidth() * first.getHeight();
        int minX = first.getWidth(), minY = first.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < first.getHeight(); y++) for (int x = 0; x < first.getWidth(); x++) {
            int left = first.getRGB(x, y), right = second.getRGB(x, y);
            int difference = Math.abs(((left >> 16) & 255) - ((right >> 16) & 255))
                    + Math.abs(((left >> 8) & 255) - ((right >> 8) & 255)) + Math.abs((left & 255) - (right & 255));
            if (difference <= 24) continue;
            changed++; minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        double percent = Math.round(changed * 100000.0 / total) / 1000.0;
        double shown = Math.round(required * 1000) / 1000.0;
        Map<String, Object> region = changed == 0 ? Json.map() : Json.map("x", minX, "y", minY, "width", maxX - minX + 1, "height", maxY - minY + 1);
        if (percent < required)
            throw new IOException(String.format("Frame changed by only %.3f%% of pixels (required %.3f%%); the action produced no visible result. Baseline: %s Final: %s", percent, shown, before, after));
        return Json.map("baseline", before.toString(), "capture", after.toString(), "changedPixels", changed, "changedPercent", percent, "requiredPercent", shown, "changedRegion", region, "passed", true,
                "details", String.format("Frame changed by %.3f%% of pixels (required %.3f%%), inside %s.", percent, shown, Json.write(region)));
    }
    static Map<String, Object> request(Map<String, Object> session, Map<String, Object> request) throws Exception {
        request.put("token", session.get("token"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ((Number)session.get("port")).intValue()), 2000); socket.setSoTimeout(135000);
            var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            output.println(Json.write(request));
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = input.readLine(); if (line == null) throw new IOException("Game closed its control connection; check " + session.get("log"));
            var result = Json.object(Json.parse(line));
            if (!Boolean.TRUE.equals(result.get("ok"))) throw new IOException("Game action failed: " + result.get("error"));
            return result;
        } catch (IOException failure) { throw new IOException(failure.getMessage() + "\nGame log: " + session.get("log"), failure); }
    }
    Map<String, Object> run(Modaccel.Options options) throws Exception {
        if (options.command.equals("mp-check") && !options.values.containsKey("server")) {
            try (Server owned = new Server(build, model, options)) {
                options.values.put("server", "127.0.0.1:" + owned.port);
                try {
                    Map<String, Object> checked = run(options);
                    checked.put("server", Json.map("owned", true, "task", owned.task, "pid", owned.process.pid(), "port", owned.port, "log", owned.log.toString()));
                    return checked;
                } finally {
                    options.values.remove("server");
                    Map<String, Object> client = session();
                    if (client != null) try { request(client, Json.map("command", "quit")); }
                    catch (Exception ignored) { }
                }
            }
        }
        String command = options.command; Map<String, Object> current = session();
        if (command.equals("eval")) {
            if (current == null) throw new IOException("No running game for this project. Start one with ui-check or world-check, then eval against it.");
            String code = options.values.containsKey("file") ? Files.readString(Path.of(options.get("file", ""))) : options.get("code", "");
            if (code.isBlank()) throw new IllegalArgumentException("eval needs --code SNIPPET or --file PATH");
            String thread = options.get("thread", "game");
            if (!thread.equals("game") && !thread.equals("control")) throw new IllegalArgumentException("--thread is game or control");
            long start = System.nanoTime();
            Map<String, Object> answer = request(current, Json.map("command", "eval", "code", code, "thread", thread));
            build.report.stages.add(Json.map("name", "eval", "seconds", (System.nanoTime() - start) / 1e9));
            String output = Json.text(answer, "output", "");
            answer.put("details", (output.isEmpty() ? "" : output + (output.endsWith("\n") ? "" : "\n")) + "=> " + answer.get("value"));
            return answer;
        }
        if (command.equals("reload")) {
            if (current == null) throw new IOException("No running game for this project. Start one with ui-check or world-check, then reload into it.");
            List<String> tasks = options.values.containsKey("task") ? List.of(options.get("task", "")) : build.tasks(model, "classes");
            if (tasks.isEmpty()) throw new IOException("No classes task in the selected project. Select --project :module.");
            Run.Result compiled = build.compileCached(tasks, "compile");
            if (compiled.exit() != 0) throw new IOException("Compile failed; the game still runs the previous build.\n" + Run.failure(compiled.log()));
            long start = System.nanoTime();
            Map<String, Object> answer = request(current, Json.map("command", "reload"));
            build.report.stages.add(Json.map("name", "redefine", "seconds", (System.nanoTime() - start) / 1e9));
            List<Object> restart = Json.array(answer.get("needsRestart"));
            if (restart.isEmpty()) {
                // The game now runs this build, so later checks against it are not stale.
                Map<String, Object> session = Json.read(build.project.state().resolve("agent.json"));
                session.put("fingerprint", fingerprint());
                Json.save(build.project.state().resolve("agent.json"), session);
            }
            List<Object> redefined = Json.array(answer.get("redefined")), later = Json.array(answer.get("loadOnFirstUse"));
            StringBuilder details = new StringBuilder();
            details.append("Swapped ").append(redefined.size()).append(redefined.size() == 1 ? " class" : " classes").append(redefined.isEmpty() ? "" : ": " + String.join(", ", redefined.stream().map(String::valueOf).toList()));
            if (!later.isEmpty()) details.append("\n").append(later.size()).append(" changed but not loaded yet; the game reads them on first use");
            if (Boolean.TRUE.equals(answer.get("resourcesReloaded"))) details.append("\nReloaded resource packs (").append(answer.get("changedResources")).append(" changed files)");
            if (!restart.isEmpty()) details.append("\nNEEDS RESTART (game-stop, then start again): ").append(String.join("; ", restart.stream().map(String::valueOf).toList()));
            answer.put("details", details.toString());
            answer.put("restartNeeded", !restart.isEmpty());
            return answer;
        }
        if (command.equals("game-status")) return current == null ? Json.map("running", false) : request(current, Json.map("command", "status"));
        if (command.equals("game-stop")) {
            if (current == null) return Json.map("running", false);
            var process = ProcessHandle.of(((Number)current.get("pid")).longValue());
            var result = request(current, Json.map("command", "quit"));
            if (process.isPresent()) process.get().onExit().get(20, TimeUnit.SECONDS);
            result.put("stopped", true); return result;
        }
        String fingerprint = fingerprint();
        if (current != null && !fingerprint.equals(current.get("fingerprint")))
            throw new IOException("The running game has a different build. Run game-stop, then ui-check; stale game results are refused.");
        if (current == null) current = start(options, fingerprint);
        Map<String, Object> result;
        List<Object> actions = new ArrayList<>();
        request(current, Json.map("command", "resize", "width", 1280, "height", 720));
        request(current, Json.map("command", "ready"));
        // A never-started client opens first-run onboarding before any world; dismiss it like a person would.
        Map<String, Object> first = request(current, Json.map("command", "status"));
        if (String.valueOf(first.get("screen")).endsWith("AccessibilityOnboardingScreen")) {
            actions.add(request(current, Json.map("command", "click", "label", "Continue")));
        }
        if (command.equals("world-check")) {
            String world = options.get("world", "lab");
            if (world.isEmpty() || world.equals(".") || world.equals("..") || world.indexOf('/') >= 0 || world.indexOf('\\') >= 0 || world.indexOf('\0') >= 0)
                throw new IOException("--world must name one existing save folder");
            Path launch = build.project.state().resolve("launch.json");
            Path gameDir = Files.exists(launch) ? Path.of(Json.text(Json.read(launch), "directory", "")) : build.project.root.resolve("run");
            Path savesRoot = gameDir.resolve("saves").toRealPath();
            Path saves = savesRoot.resolve(world);
            if (!Files.isRegularFile(saves.resolve("level.dat"))) throw new IOException("World save does not exist: " + saves);
            if (!saves.toRealPath().getParent().equals(savesRoot)) throw new IOException("World save must be directly inside " + savesRoot);
            Map<String, Object> before = request(current, Json.map("command", "status"));
            if (Boolean.TRUE.equals(before.get("inWorld"))) verifyWorld(before, saves);
            else actions.add(request(current, Json.map("command", "open-world", "world", world)));
        }
        if (command.equals("mp-check")) {
            String server = options.get("server", "127.0.0.1:25565");            if (!server.matches("[A-Za-z0-9_.-]+:[0-9]{1,5}")) throw new IOException("--server needs host:port");
            if (Boolean.TRUE.equals(request(current, Json.map("command", "status")).get("inWorld")))
                throw new IOException("Client is already in a world; run game-stop before mp-check");
            actions.add(request(current, Json.map("command", "connect", "address", server)));
        }
        Map<String, Object> saveRoundtrip = null;
        if (command.equals("world-check") && options.has("save-roundtrip")) {
            String world = options.get("world", "lab");
            request(current, Json.map("command", "ready", "expectWorld", true));
            Map<String, Object> before = request(current, Json.map("command", "state"));
            if (!Boolean.TRUE.equals(before.get("inWorld"))) throw new IOException("Save round-trip needs a loaded world");
            request(current, Json.map("command", "key", "key", 256));
            request(current, Json.map("command", "ready", "requireScreen", true));
            actions.add(request(current, Json.map("command", "click", "label", "Save and Quit to Title")));
            request(current, Json.map("command", "ready", "expectScreen", "net.minecraft.client.gui.screen.TitleScreen"));
            actions.add(request(current, Json.map("command", "open-world", "world", world)));
            request(current, Json.map("command", "ready", "expectWorld", true));
            Map<String, Object> after = request(current, Json.map("command", "state"));
            double moved = Math.abs(number(before, "x") - number(after, "x")) + Math.abs(number(before, "y") - number(after, "y")) + Math.abs(number(before, "z") - number(after, "z"));
            boolean preserved = samePosition(before, after, 0.05);
            saveRoundtrip = Json.map("before", before, "after", after, "tolerance", 0.05, "positionPreserved", preserved,
                    "details", preserved ? "Player position survived saving, quitting to the title and reloading the same save."
                            : "Player position moved by " + String.format("%.3f", moved) + " across the save round-trip.");
            if (!preserved) throw new IOException((String)saveRoundtrip.get("details"));
        }
        if (options.values.containsKey("screen")) actions.add(request(current, Json.map("command", "screen", "class", options.get("screen", ""))));
        Path shot = build.project.root.resolve("build/modaccel/shots/" + build.report.id + ".png");
        Path baseline = shot.resolveSibling(build.report.id + "-before.png");
        boolean expectChange = options.values.containsKey("expect-change");
        if (expectChange) {
            Files.createDirectories(baseline.getParent());
            request(current, Json.map("command", "capture", "path", baseline.toString(), "width", 1280, "height", 720));
        }
        if (options.values.containsKey("click")) actions.add(request(current, Json.map("command", "click", "label", options.get("click", ""))));
        if (options.values.containsKey("click-at")) actions.add(request(current, point("click-at", options.get("click-at", ""))));
        if (options.values.containsKey("move-at")) actions.add(request(current, point("move-at", options.get("move-at", ""))));
        if (options.values.containsKey("key")) {
            int key;
            try { key = Integer.parseInt(options.get("key", "")); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("--key needs a GLFW key number"); }
            boolean openingScreen = options.values.containsKey("type") && Json.text(request(current, Json.map("command", "status")), "screen", "").isEmpty();
            actions.add(request(current, Json.map("command", "key", "key", key)));
            if (openingScreen) request(current, Json.map("command", "ready", "requireScreen", true));
        }
        if (options.values.containsKey("type")) actions.add(request(current, Json.map("command", "type", "text", options.get("type", ""))));
        // A real click may schedule a transition or a fade; wait for the requested outcome.
        request(current, Json.map("command", "ready", "expectScreen", options.get("expect-screen", ""), "expectWorld", options.has("expect-world") || command.equals("world-check") || command.equals("mp-check")));
        if (options.values.containsKey("assert")) actions.add(request(current, Json.map("command", "assert", "method", options.get("assert", ""))));
        if (options.values.containsKey("expect-value")) {
            String[] check = options.get("expect-value", "").split("=", 2);
            if (check.length != 2 || !check[0].matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("--expect-value needs getter=value, for example shards=6");
            actions.add(request(current, Json.map("command", "expect-value", "getter", check[0], "value", check[1])));
        }
        long captureStart = System.nanoTime();
        Map<String, Object> captured = request(current, Json.map("command", "capture", "path", shot.toString(), "width", 1280, "height", 720));
        Map<String, Object> change = expectChange ? compare(baseline, shot, Double.parseDouble(options.get("expect-change", "0"))) : null;
        result = request(current, Json.map("command", "status"));
        if ((options.has("expect-world") || command.equals("world-check") || command.equals("mp-check")) && !Boolean.TRUE.equals(result.get("inWorld"))) throw new IOException("Expected a loaded world; current screen: " + result.get("screen"));
        if (command.equals("world-check")) {
            Path launch = build.project.state().resolve("launch.json");
            Path gameDir = Files.exists(launch) ? Path.of(Json.text(Json.read(launch), "directory", "")) : build.project.root.resolve("run");
            verifyWorld(result, gameDir.resolve("saves/" + options.get("world", "lab")));
        }
        if (command.equals("mp-check") && Boolean.TRUE.equals(result.get("integratedServer"))) throw new IOException("Client joined an integrated world instead of the dedicated server");
        if (options.values.containsKey("expect-screen") && !options.get("expect-screen", "").equals(result.get("screen"))) throw new IOException("Expected screen " + options.get("expect-screen", "") + "; got " + result.get("screen"));
        var image = ImageIO.read(shot.toFile());
        if (image == null || image.getWidth() != 1280 || image.getHeight() != 720) throw new IOException("Saved capture is not a 1280x720 PNG: " + shot);
        if (!fingerprint.equals(fingerprint())) throw new IOException("Project changed during verification; the capture cannot verify the current build.");
        build.report.stages.add(Json.map("name", "framebuffer-check", "seconds", (System.nanoTime() - captureStart) / 1e9));
        result.putAll(Json.map("tier", command.equals("mp-check") ? "dedicated-client" : command.equals("world-check") ? "integrated-world" : "client-render", "capture", captured, "fingerprint", fingerprint, "actions", actions,
                "actionOrder", List.of("screen", "click", "click-at", "move-at", "key", "type", "assert", "expect-value", "capture"),
                "worldVerified", command.equals("world-check") || command.equals("mp-check"), "behaviorVerified", options.values.containsKey("assert") || options.values.containsKey("expect-value") || change != null || saveRoundtrip != null,
                "visualInspectionRequired", true, "details", "Real client rendered a 1280x720 Minecraft framebuffer: " + shot));
        if (change != null) {
            result.put("visualChange", change);
            result.put("details", change.get("details"));
        }
        if (saveRoundtrip != null) {
            result.put("saveRoundtrip", saveRoundtrip);
            result.put("details", saveRoundtrip.get("details"));
        }
        if (command.equals("mp-check") && options.values.containsKey("server")) result.put("server", Json.map("owned", false, "address", options.get("server", ""), "buildVerified", false));
        return result;
    }
    String fingerprint() throws Exception {
        return Fingerprints.bytes((Fingerprints.build(model, build.project, build.init) + Fingerprints.files(List.of(
                build.harness.resolve("tools/agent/PortableAgent.java"), build.harness.resolve("tools/agent/Console.java"), build.harness.resolve("tools/agent/HotReload.java"), build.harness.resolve("tools/cli/Json.java"),
                build.harness.resolve("tools/cli/Relocate.java")))).getBytes(StandardCharsets.UTF_8));
    }
    private static void verifyWorld(Map<String, Object> status, Path expected) throws Exception {
        if (!Boolean.TRUE.equals(status.get("integratedServer"))) throw new IOException("Expected an integrated world; client is connected elsewhere");
        String actual = Json.text(status, "worldPath", "");
        if (actual.isEmpty() || !Path.of(actual).toRealPath().equals(expected.toRealPath()))
            throw new IOException("Loaded world " + actual + " differs from requested save " + expected);
    }
    private static double number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof Number number ? number.doubleValue() : 0;
    }
    /** A reloaded save must put the player back within a small tolerance of where they left. */
    static boolean samePosition(Map<String, Object> before, Map<String, Object> after, double tolerance) {
        double moved = Math.abs(number(before, "x") - number(after, "x")) + Math.abs(number(before, "y") - number(after, "y")) + Math.abs(number(before, "z") - number(after, "z"));
        return moved <= tolerance;
    }
    private static Map<String, Object> point(String command, String value) {        String[] parts = value.split(",", -1);
        if (parts.length != 2) throw new IllegalArgumentException("--" + command + " needs x,y in the 1280x720 framebuffer");
        try { return Json.map("command", command, "x", Integer.parseInt(parts[0].trim()), "y", Integer.parseInt(parts[1].trim())); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("--" + command + " needs integer x,y"); }
    }
    Map<String, Object> start(Modaccel.Options options, String fingerprint) throws Exception {
        Path agent = agent(); String token = UUID.randomUUID().toString();
        String task = options.get("client-task", options.get("task", ""));
        if (task.isEmpty()) {
            List<String> tasks = new ArrayList<>(), warm = new ArrayList<>();
            for (Object p : Json.array(model.get("projects"))) for (Object t : Json.array(Json.object(p).get("tasks"))) {
                String path = Json.text(Json.object(t), "task", ""); if (path.endsWith(":runClient")) tasks.add(path); else if (path.endsWith(":runClientWarm")) warm.add(path);
            }
            if (tasks.isEmpty()) tasks = warm;
            if (tasks.size() != 1) throw new IOException("Choose --project and/or --task for the client: " + tasks);
            task = tasks.get(0);
        }
        Path recipe = build.project.state().resolve("launch.json");
        List<String> command; Map<String, String> environment = new HashMap<>(); Path directory; String mode;
        boolean jbr = options.has("jbr");
        boolean saved = Files.exists(recipe) && Boolean.TRUE.equals(Json.read(recipe).get("verified")) && fingerprint.equals(Json.read(recipe).get("fingerprint")) && task.equals(Json.read(recipe).get("task"))
                && jbr == Boolean.TRUE.equals(Json.read(recipe).get("jbr"));
        if (!saved && jbr) {
            Run.Result prepared = build.gradle(List.of("-Dmodaccel.recompile=true", "-Dmodaccel.captureTask=" + task, "-Dmodaccel.agent=" + agent, "-Dmodaccel.fingerprint=" + fingerprint,
                    "-Dmodaccel.token=" + token, "-Dmodaccel.jbr=true", task), "prepare-jbr-launch");
            if (prepared.exit() != 0 || !Files.exists(recipe) || Json.read(recipe).get("jbrExecutable") == null)
                throw new IOException("Could not prepare the JetBrains Runtime launch.\n" + Run.failure(prepared.log()));
            saved = true;
        }
        if (saved) {
            var launch = Json.read(recipe); command = new ArrayList<>();
            if (jbr) command.addAll(List.of(launch.get("jbrExecutable").toString(), "-XX:+AllowEnhancedClassRedefinition", "-XX:TieredStopAtLevel=1"));
            else command.add(launch.get("executable").toString());
            for (String argument : ProjectModel.strings(launch.get("arguments"))) command.add(argument.startsWith("-Dmodaccel.token=") ? "-Dmodaccel.token=" + token : argument);
            Json.object(launch.get("environment")).forEach((key, value) -> { if (value != null) environment.put(key, value.toString()); });
            directory = Path.of(launch.get("directory").toString()); mode = "saved launch; dependencies already prepared";
            command = savedCommand(command, build.report.directory.resolve("client-classpath.args"));
        } else {
            command = build.command(List.of("-Dmodaccel.recompile=true", "-Dmodaccel.captureTask=" + task, "-Dmodaccel.agent=" + agent, "-Dmodaccel.fingerprint=" + fingerprint,
                    "-Dmodaccel.token=" + token, "-Dmodaccel.jbr=" + jbr, task));
            environment.put("JAVA_HOME", build.jdk.home().toString()); directory = build.project.root; mode = "Gradle prepares and launches the actual client";
        }
        Path log = build.report.log("game"); Files.createDirectories(log.getParent()); Files.createDirectories(build.project.state());
        final CompletableFuture<Map<String, Object>> ready = new CompletableFuture<>();
        WatchService watch = FileSystems.getDefault().newWatchService();
        build.project.state().register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        Thread observer = new Thread(() -> {
            try {
                while (!ready.isDone()) {
                    WatchKey key = watch.take();
                    for (WatchEvent<?> event : key.pollEvents()) if (event.context().toString().equals("agent.json")) {
                        try {
                            var value = Json.read(build.project.state().resolve("agent.json"));
                            if (token.equals(value.get("token")) && fingerprint.equals(value.get("fingerprint"))) ready.complete(value);
                        } catch (Exception notCommittedYet) { }
                    }
                    if (!key.reset()) break;
                }
            } catch (InterruptedException | ClosedWatchServiceException stopped) { }
        }, "modaccel-start-event"); observer.setDaemon(true); observer.start();
        long start = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()); builder.environment().putAll(environment);
        Process process = builder.start();
        process.onExit().thenAccept(ended -> ready.completeExceptionally(new IOException("Client stopped before its first rendered frame (exit " + ended.exitValue() + "). Log: " + log)));
        Map<String, Object> value;
        try { value = ready.get(180, TimeUnit.SECONDS); }
        catch (Exception failure) {
            process.descendants().forEach(ProcessHandle::destroy); process.destroy();
            throw new IOException("Client startup failed: " + failure + "\n" + Run.failure(log) + "\nLog: " + log);
        } finally { watch.close(); }
        long pid = ((Number)value.get("pid")).longValue();
        value.put("processStart", ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Instant::toString).orElseThrow());
        value.put("log", log.toString()); Json.save(build.project.state().resolve("agent.json"), value);
        if (Files.exists(recipe)) { var launch = Json.read(recipe); if (fingerprint.equals(launch.get("fingerprint"))) { launch.put("verified", true); Json.save(recipe, launch); } }
        build.report.stages.add(Json.map("name", "game-start", "seconds", (System.nanoTime() - start) / 1e9, "mode", mode, "log", log.toString()));
        return value;
    }
    // Keep existing Loom @files on the command line: Java does not expand nested @files.
    // The captured classpath can exceed Windows' 32767-character process limit by itself.
    static List<String> savedCommand(List<String> original, Path argumentFile) throws IOException {
        List<String> command = new ArrayList<>(original);
        for (int i = 1; i + 1 < command.size(); i++) {
            if (!List.of("-cp", "-classpath", "--class-path").contains(command.get(i))) continue;
            String classpath = command.get(i + 1);
            if (classpath.length() > 8000) {
                Files.createDirectories(argumentFile.toAbsolutePath().getParent());
                Files.writeString(argumentFile, "-cp\n" + quoteJavaArgument(classpath) + "\n", StandardCharsets.UTF_8);
                command.set(i, "@" + argumentFile.toAbsolutePath()); command.remove(i + 1);
            }
            break;
        }
        return command;
    }
    private static String quoteJavaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "\"";
    }
    Path agent() throws Exception {
        Set<String> jars = new LinkedHashSet<>();
        for (Object p : Json.array(model.get("projects"))) for (Object c : Json.array(Json.object(p).get("compile"))) jars.addAll(ProjectModel.strings(Json.object(c).get("classpath")));
        Path asm = null;
        for (String path : jars) if (Path.of(path).getFileName().toString().matches("asm-[0-9].*\\.jar")) { asm = Path.of(path); break; }
        if (asm == null) throw new IOException("No ASM library in the actual build classpath; this client cannot use the portable agent yet.");
        Path source = build.harness.resolve("tools/agent/PortableAgent.java"), console = build.harness.resolve("tools/agent/Console.java"), reload = build.harness.resolve("tools/agent/HotReload.java"), json = build.harness.resolve("tools/cli/Json.java");
        String key = Fingerprints.files(List.of(source, console, reload, json, asm, build.harness.resolve("tools/cli/Relocate.java"))) + "-private-asm-redefine";
        Path dir = build.project.state().resolve("agent/" + key); Files.createDirectories(dir);
        Path jar = dir.resolve("modaccel-agent.jar"); if (Files.exists(jar)) return jar;
        Path classes = dir.resolve("classes"); Files.createDirectories(classes);
        var result = Run.command(List.of(Runtimes.ensureAtLeast(build.harness, 17).home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release", "8", "-cp", asm.toString(), "-d", classes.toString(), source.toString(), console.toString(), reload.toString(), json.toString()), build.project.root, Map.of(), build.report.log("agent-compile"), 120);
        build.report.stage("agent-compile", result, Map.of());
        if (result.exit() != 0) throw new IOException("Could not build client control: " + Run.tail(result.log()));
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Premain-Class", "PortableAgent");
        // reload swaps recompiled classes into the running game.
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        Path temporary = dir.resolve("agent.tmp"); Set<String> entries = new HashSet<>();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporary), manifest); var input = new ZipFile(asm.toFile()); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/'); output.putNextEntry(new JarEntry(name)); output.write(Relocate.classes(Files.readAllBytes(file))); output.closeEntry(); entries.add(name);
            }
            var items = input.entries(); while (items.hasMoreElements()) {
                ZipEntry entry = items.nextElement(); if (!entry.getName().startsWith("org/objectweb/asm/") || !entry.getName().endsWith(".class") || !entries.add(entry.getName())) continue;
                output.putNextEntry(new JarEntry(Relocate.name(entry.getName()))); try (var stream = input.getInputStream(entry)) { output.write(Relocate.classes(stream.readAllBytes())); } output.closeEntry();
            }
        }
        Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING); return jar;
    }
}
