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
        Map<String, Object> value = recorded();
        return value != null && alive(value) ? value : null;
    }
    /** The last game started for this project, running or not. */
    Map<String, Object> recorded() throws Exception {
        Path file = build.project.state().resolve("agent.json");
        if (!Files.exists(file)) return null;
        Map<String, Object> value = Json.read(file);
        if (!build.project.root.toString().equals(value.get("root"))) throw new IOException("Game session belongs to a different project.");
        return value;
    }
    static boolean alive(Map<String, Object> session) {
        var process = ProcessHandle.of(((Number)session.get("pid")).longValue());
        if (process.isEmpty() || !process.get().isAlive()) return false;
        String recorded = Json.text(session, "processStart", "");
        return !recorded.isEmpty() && process.get().info().startInstant().map(Instant::toString).orElse("").equals(recorded);
    }
    /** Why there's no game to talk to: the last one crashed or exited, or none was started. */
    String noGame(String then) throws Exception {
        Map<String, Object> last = recorded();
        if (last == null || Json.text(last, "processStart", "").isEmpty()) return "No running game for this project. Start one with ui-check or world-check, then " + then + ".";
        return exited(last) + "\nStart a new one with ui-check or world-check, then " + then + ".";
    }
    /**
     * What became of a game whose process is gone, read from its log: a JVM fatal error (with
     * its hs_err file), a Minecraft crash report, or neither. Only named lines are copied, since
     * an hs_err file's command line and environment can hold an access token.
     */
    static String exited(Map<String, Object> session) {
        StringBuilder out = new StringBuilder("The game started for this project (pid " + session.get("pid") + ") has exited");
        Path log = Path.of(Json.text(session, "log", ""));
        List<String> lines = lines(log);
        int fatal = -1, crashed = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("A fatal error has been detected by the Java Runtime Environment")) fatal = i;
            if (lines.get(i).contains("Game crashed! Crash report saved to:")) crashed = i;
        }
        if (fatal >= 0) {
            out.append(": the JVM crashed.");
            String file = null;
            for (int i = fatal + 1; i < lines.size() && lines.get(i).startsWith("#"); i++) {
                String line = lines.get(i).substring(1).strip(), next = i + 1 < lines.size() ? lines.get(i + 1).replaceFirst("^#\\s*", "") : "";
                if (line.matches("(EXCEPTION_|SIG|Internal Error|Out of Memory|There is insufficient memory).*")) out.append("\n  ").append(line);
                else if (line.startsWith("JRE version:")) out.append("\n  ").append(line);
                else if (line.equals("Problematic frame:")) out.append("\n  Problematic frame: ").append(next);
                else if (line.startsWith("An error report file with more information is saved as:")) file = next.strip();
            }
            List<String> report = file == null ? List.of() : lines(Path.of(file));
            for (int i = 0; i < report.size(); i++) {
                java.util.regex.Matcher thread = java.util.regex.Pattern.compile("^Current thread \\(\\w+\\):\\s+\\w+ \"([^\"]+)\"").matcher(report.get(i));
                if (thread.find()) out.append("\n  Thread: ").append(thread.group(1));
                if (report.get(i).equals("Current CompileTask:") && i + 1 < report.size()) out.append("\n  Compiling: ").append(report.get(i + 1).strip());
            }
            if (file != null) out.append("\n  Crash file: ").append(file);
        } else if (crashed >= 0) {
            out.append(": Minecraft crashed.");
            String file = lines.get(crashed).replaceFirst(".*Crash report saved to:\\s*(#@!@#\\s*)?", "").strip();
            List<String> report = lines(Path.of(file));
            for (int i = 0; i < report.size(); i++) {
                if (!report.get(i).startsWith("Description:")) continue;
                out.append("\n  ").append(report.get(i));
                // The exception follows a blank line, then its first frames.
                int shown = 0;
                for (int j = i + 1; j < report.size() && shown < 6; j++) {
                    String line = report.get(j);
                    if (line.isBlank()) { if (shown > 0) break; continue; }
                    out.append("\n  ").append(line.strip()); shown++;
                }
                break;
            }
            out.append("\n  Crash report: ").append(file);
        } else {
            out.append(" without a crash report. Last lines of its log:");
            for (String line : lines.subList(Math.max(0, lines.size() - 8), lines.size())) out.append("\n  ").append(line);
        }
        return out.append("\nGame log: ").append(log).toString();
    }
    /**
     * Why the game never showed a frame, cause first: Minecraft's crash report (its description and
     * exception), a mixin that could not apply, or the loader refusing the mods; otherwise the log's
     * own error lines. The wrapper exceptions around it say nothing a person needs.
     */
    static String startupFailure(Throwable failure, Path log) throws IOException {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) cause = cause.getCause();
        String what = String.valueOf(cause.getMessage()).replaceFirst("\\s*Log: .*$", "");
        List<String> lines = lines(log);
        StringBuilder out = new StringBuilder("The game did not start: " + what);
        int crashed = -1, mixin = -1, incompatible = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("Game crashed! Crash report saved to:")) crashed = i;
            if (mixin < 0 && (line.contains("MixinApplyError") || line.contains("InvalidInjectionException") || line.contains("InvalidMixinException") || line.contains("MixinTargetAlreadyLoadedException"))) mixin = i;
            if (incompatible < 0 && (line.contains("Incompatible mods found") || line.contains("Mod resolution encountered an incompatible mod set") || line.contains("Mod Loading has failed"))) incompatible = i;
        }
        if (mixin >= 0) {
            out.append("\nA mixin could not be applied:");
            for (int i = mixin; i < lines.size() && i < mixin + 3; i++) out.append("\n  ").append(lines.get(i).strip());
            out.append("\nCheck the mixin's target class and method (modaccel mixin-check finds most of these without starting the game).");
        }
        if (incompatible >= 0) {
            out.append("\nThe mod loader refused the mods:");
            for (int i = incompatible; i < lines.size() && i < incompatible + 8 && !(i > incompatible && lines.get(i).isBlank()); i++) out.append("\n  ").append(lines.get(i).strip());
        }
        if (crashed >= 0) {
            String file = lines.get(crashed).replaceFirst(".*Crash report saved to:\\s*(#@!@#\\s*)?", "").strip();
            List<String> report = lines(Path.of(file));
            for (int i = 0; i < report.size(); i++) {
                if (!report.get(i).startsWith("Description:")) continue;
                out.append("\nMinecraft crashed. ").append(report.get(i).strip());
                // The first stack: its exception, each "Caused by" (the deepest is the real cause), and the
                // first frame in the mod's own code under the deepest cause. Loader wrapper frames say nothing.
                String exception = null, frame = null; List<String> causes = new ArrayList<>();
                for (int j = i + 1; j < report.size(); j++) {
                    String line = report.get(j).strip();
                    if (line.startsWith("A detailed walkthrough") || line.startsWith("-- ")) break;
                    if (line.isEmpty() || line.startsWith("...")) continue;
                    if (line.startsWith("Caused by:")) { causes.add(line); frame = null; }
                    else if (line.startsWith("at ")) {
                        String at = line.substring(3).replaceFirst("^[\\w.-]*//", "");
                        if (frame == null && !at.matches("(?:java|jdk|sun|net\\.minecraft|com\\.mojang|net\\.fabricmc|net\\.neoforged|cpw|org\\.spongepowered|com\\.google|it\\.unimi)\\..*")) frame = at;
                    } else if (exception == null) exception = line;
                }
                if (exception != null) out.append("\n  ").append(exception);
                for (String causedBy : causes) out.append("\n  ").append(causedBy);
                if (frame != null) out.append("\n  first frame in the mod's code: ").append(frame);
                break;
            }
            out.append("\nCrash report: ").append(file);
        }
        if (mixin < 0 && incompatible < 0 && crashed < 0) out.append('\n').append(Run.failure(log).strip());
        return out.append("\nGame log: ").append(log).toString();
    }
    private static List<String> lines(Path file) {
        // Decoded leniently: a dying JVM writes its banner in the platform charset.
        try { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList(); }
        catch (IOException | InvalidPathException unreadable) { return List.of(); }
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
        Map<String, Object> result;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ((Number)session.get("port")).intValue()), 2000); socket.setSoTimeout(135000);
            var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            output.println(Json.write(request));
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = input.readLine(); if (line == null) throw new IOException("Game closed its control connection; check " + session.get("log"));
            result = Json.object(Json.parse(line));
        } catch (IOException failure) {
            // A dropped connection usually means the game died. A dying JVM keeps the socket
            // until its crash file is written, so give it a moment to finish exiting.
            var process = ProcessHandle.of(((Number)session.get("pid")).longValue());
            if (!(failure instanceof SocketTimeoutException) && process.isPresent())
                try { process.get().onExit().get(10, TimeUnit.SECONDS); } catch (Exception stillRunning) { }
            if (!alive(session)) throw new IOException(failure.getMessage() + "\n" + exited(session), failure);
            throw new IOException(failure.getMessage() + "\nGame log: " + session.get("log"), failure);
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new IOException("Game action failed: " + result.get("error") + "\nGame log: " + session.get("log"));
        return result;
    }
    Map<String, Object> run(Modaccel.Options options) throws Exception {
        if (options.command.equals("mp-check") && !options.values.containsKey("server")) {
            try (Server owned = new Server(build, model, options)) {
                options.values.put("server", "127.0.0.1:" + owned.port);
                try {
                    Map<String, Object> checked = run(options);
                    // The client's loader was asked; the dedicated server has no agent to ask, so its mod list is unchecked.
                    checked.put("server", Json.map("owned", true, "task", owned.task, "pid", owned.process.pid(), "port", owned.port, "log", owned.log.toString(), "modsChecked", false));
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
            if (current == null) throw new IOException(noGame("eval against it"));
            boolean until = options.values.containsKey("until");
            String code = until ? options.get("until", "") : options.values.containsKey("file") ? Files.readString(Path.of(options.get("file", ""))) : options.get("code", "");
            if (code.isBlank()) throw new IllegalArgumentException("eval needs --code SNIPPET, --file PATH or --until CONDITION");
            String thread = options.get("thread", "game");
            if (!thread.equals("game") && !thread.equals("control")) throw new IllegalArgumentException("--thread is game or control");
            double timeout;
            try { timeout = Double.parseDouble(options.get("timeout", "60")); } catch (NumberFormatException error) { throw new IllegalArgumentException("--timeout is a number of seconds"); }
            // The control connection gives up on an answer after 135 s.
            if (timeout <= 0 || timeout > 120) throw new IllegalArgumentException("--timeout is more than 0 and at most 120 seconds");
            long start = System.nanoTime();
            Map<String, Object> request = Json.map("command", "eval", "code", code, "thread", thread);
            if (until) { request.put("until", true); request.put("timeout", timeout); }
            Map<String, Object> answer = request(current, request);
            build.report.stages.add(Json.map("name", "eval", "seconds", (System.nanoTime() - start) / 1e9));
            String output = Json.text(answer, "output", "");
            answer.put("details", (output.isEmpty() ? "" : output + (output.endsWith("\n") ? "" : "\n")) + "=> " + answer.get("value")
                    + (until ? String.format(Locale.ROOT, " after %.1f s", ((Number) answer.get("waitedSeconds")).doubleValue()) : ""));
            return answer;
        }
        if (command.equals("reload")) {
            if (current == null) throw new IOException(noGame("reload into it"));
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
        if (command.equals("game-status")) {
            if (current != null) {
                Map<String, Object> status = request(current, Json.map("command", "status"));
                String started = Json.text(current, "processStart", "");
                try { started = LocalTime.ofInstant(Instant.parse(started), ZoneId.systemDefault()).withNano(0).toString(); } catch (RuntimeException unparsed) { }
                String screen = Json.text(status, "screen", "");
                status.put("running", true);
                status.put("details", "A game is running for this project (process " + current.get("pid") + (started.isEmpty() ? "" : ", started " + started) + "), "
                        + (Boolean.TRUE.equals(status.get("inWorld")) ? "in a world" : "not in a world") + (screen.isEmpty() ? "" : ", showing " + screen.substring(screen.lastIndexOf('.') + 1))
                        + ".\nStop it with game-stop; reload swaps changed code into it.");
                return status;
            }
            Map<String, Object> last = recorded();
            return last == null || Json.text(last, "processStart", "").isEmpty() ? Json.map("running", false, "details", "No game is running for this project. Start one with ui-check or world-check.")
                    : Json.map("running", false, "details", exited(last));
        }
        if (command.equals("game-stop")) {
            Map<String, Object> result;
            if (current == null) {
                Map<String, Object> last = recorded();
                result = last == null || Json.text(last, "processStart", "").isEmpty() ? Json.map("running", false, "details", "No game was running for this project; nothing to stop.")
                        : Json.map("running", false, "details", exited(last));
            } else {
                var process = ProcessHandle.of(((Number)current.get("pid")).longValue());
                result = request(current, Json.map("command", "quit"));
                if (process.isPresent()) process.get().onExit().get(20, TimeUnit.SECONDS);
                result.put("stopped", true);
                result.put("details", "Stopped the game (process " + current.get("pid") + ").");
            }
            // A stopped game didn't crash; later commands shouldn't say it exited.
            Files.deleteIfExists(build.project.state().resolve("agent.json"));
            // The daemons that prepared, launched and recompiled the game hold a gigabyte or more each.
            Run.Result daemons = build.stopDaemons();
            result.put("gradleDaemonsStopped", daemons == null || daemons.exit() == 0); return result;
        }
        String fingerprint = fingerprint();
        if (current != null && !fingerprint.equals(current.get("fingerprint")))
            throw new IOException("A game is already running for this project, started from an older build (the code or resources changed since). "
                    + "A check against it would test the old code, so this one did nothing. Run modaccel game-stop --root \"" + build.project.root + "\""
                    + (options.values.containsKey("project") ? " --project " + options.get("project", "") : "") + ", then " + command
                    + " again; or use reload to swap changed method bodies into the running game.");
        if (current != null && options.values.containsKey("java")
                && !javaExecutable(options.get("java", "")).toString().equals(current.get("java")))
            throw new IOException("The running game isn't on the --java runtime. Run game-stop, then start it again with --java.");
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
        // A rendered frame is not a loaded mod: ask the loader before any check counts.
        Map<String, Object> mods = modsLoaded(current, options, build.project.root.resolve("build/modaccel/shots/" + build.report.id + "-mods.png"));
        if (command.equals("world-check")) {
            String world = options.get("world", "lab");
            if (world.isEmpty() || world.equals(".") || world.equals("..") || world.indexOf('/') >= 0 || world.indexOf('\\') >= 0 || world.indexOf('\0') >= 0)
                throw new IOException("--world must name one existing save folder");
            Path gameDir = gameDirectory(request(current, Json.map("command", "status")));
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
        // Behaviour counts only when an assertion ran against the mod's own code or screen.
        List<Path> own = LoadedMods.outputs(model, LoadedMods.owner(clientTask(options)));
        String asserted = null, valueScreen = null, valueCheck = null;
        if (options.values.containsKey("assert")) {
            asserted = options.get("assert", "");
            actions.add(request(current, Json.map("command", "assert", "method", asserted)));
        }
        if (options.values.containsKey("expect-value")) {
            String[] check = options.get("expect-value", "").split("=", 2);
            if (check.length != 2 || !check[0].matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("--expect-value needs getter=value, for example shards=6");
            Map<String, Object> value = request(current, Json.map("command", "expect-value", "getter", check[0], "value", check[1]));
            actions.add(value);
            valueScreen = Json.text(value, "screenClass", ""); valueCheck = options.get("expect-value", "");
        }
        long captureStart = System.nanoTime();
        Map<String, Object> captured = request(current, Json.map("command", "capture", "path", shot.toString(), "width", 1280, "height", 720));
        Map<String, Object> change = expectChange ? compare(baseline, shot, Double.parseDouble(options.get("expect-change", "0"))) : null;
        result = request(current, Json.map("command", "status"));
        if ((options.has("expect-world") || command.equals("world-check") || command.equals("mp-check")) && !Boolean.TRUE.equals(result.get("inWorld"))) throw new IOException("Expected a loaded world; current screen: " + result.get("screen"));
        if (command.equals("world-check")) {
            verifyWorld(result, gameDirectory(result).resolve("saves/" + options.get("world", "lab")));
        }
        if (command.equals("mp-check") && Boolean.TRUE.equals(result.get("integratedServer"))) throw new IOException("Client joined an integrated world instead of the dedicated server");
        if (options.values.containsKey("expect-screen") && !options.get("expect-screen", "").equals(result.get("screen"))) throw new IOException("Expected screen " + options.get("expect-screen", "") + "; got " + result.get("screen"));
        var image = ImageIO.read(shot.toFile());
        if (image == null || image.getWidth() != 1280 || image.getHeight() != 720) throw new IOException("Saved capture is not a 1280x720 PNG: " + shot);
        if (!fingerprint.equals(fingerprint())) throw new IOException("Project changed during verification; the capture cannot verify the current build.");
        build.report.stages.add(Json.map("name", "framebuffer-check", "seconds", (System.nanoTime() - captureStart) / 1e9));
        result.putAll(Json.map("tier", command.equals("mp-check") ? "dedicated-client" : command.equals("world-check") ? "integrated-world" : "client-render", "capture", captured, "fingerprint", fingerprint, "actions", actions,
                "actionOrder", List.of("screen", "click", "click-at", "move-at", "key", "type", "assert", "expect-value", "capture"),
                "worldVerified", command.equals("world-check") || command.equals("mp-check"), "modsLoaded", mods,
                "visualInspectionRequired", true, "details", Json.text(mods, "details", "") + "\nReal client rendered a 1280x720 Minecraft framebuffer: " + shot));
        result.putAll(behaviour(own, asserted, valueScreen, valueCheck, change != null, saveRoundtrip != null));
        if (change != null) {
            result.put("visualChange", change);
            result.put("details", Json.text(mods, "details", "") + "\n" + change.get("details"));
        }
        if (saveRoundtrip != null) {
            result.put("saveRoundtrip", saveRoundtrip);
            result.put("details", Json.text(mods, "details", "") + "\n" + saveRoundtrip.get("details"));
        }
        if (command.equals("mp-check") && options.values.containsKey("server")) result.put("server", Json.map("owned", false, "address", options.get("server", ""), "buildVerified", false, "modsChecked", false));
        return result;
    }
    /**
     * The running game's own directory. The recorded launch directory was wrong for ForgeGradle,
     * NeoGradle and Architectury Loom (the project folder instead of its run folder), so world-check
     * looked for saves where the game never writes them. Older agents don't report it.
     */
    Path gameDirectory(Map<String, Object> status) throws Exception {
        String reported = Json.text(status, "gameDirectory", "");
        if (!reported.isEmpty()) return Path.of(reported);
        Path launch = build.project.state().resolve("launch.json");
        return Files.exists(launch) ? Path.of(Json.text(Json.read(launch), "directory", "")) : build.project.root.resolve("run");
    }
    String fingerprint() throws Exception {
        return Fingerprints.bytes((Fingerprints.build(model, build.project, build.init) + Fingerprints.files(List.of(
                build.harness.resolve("tools/agent/PortableAgent.java"), build.harness.resolve("tools/agent/Console.java"), build.harness.resolve("tools/agent/HotReload.java"), build.harness.resolve("tools/cli/Json.java"),
                build.harness.resolve("tools/cli/Relocate.java"), build.harness.resolve("tools/cli/QuietOptions.java")))).getBytes(StandardCharsets.UTF_8));
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
    /**
     * behaviorVerified and why. Only a passed assertion whose class is this project's own code, or
     * an --expect-value read from the project's own screen, counts. A frame change and a save
     * round-trip happen with the mod missing, so they never count. Assertions that failed threw earlier.
     */
    static Map<String, Object> behaviour(List<Path> own, String asserted, String valueScreen, String valueCheck, boolean frameChanged, boolean saveReloaded) {
        List<String> counted = new ArrayList<>(), not = new ArrayList<>();
        if (asserted != null) {
            String owner = asserted.split("#", 2)[0];
            if (LoadedMods.ownClass(own, owner)) counted.add("assertion " + asserted + " passed");
            else not.add("the assertion's class " + owner + " is not in this project's build output");
        }
        if (valueCheck != null) {
            if (LoadedMods.ownClass(own, valueScreen)) counted.add("the mod's screen " + valueScreen + " reported " + valueCheck);
            else not.add("--expect-value read " + (valueScreen == null || valueScreen.isEmpty() ? "a screen" : valueScreen) + ", which is not this project's class");
        }
        if (!counted.isEmpty()) return Json.map("behaviorVerified", true, "behaviorBasis", String.join("; ", counted));
        if (frameChanged) not.add("a frame change happens with or without the mod");
        if (saveReloaded) not.add("a save round-trip is vanilla");
        return Json.map("behaviorVerified", false, "behaviorBasis", "Behaviour not checked" + (not.isEmpty() ? "" : ": " + String.join("; ", not))
                + ". Assert on the mod's own state with --assert class#method or --expect-value on the mod's screen.");
    }
    /** The Gradle task that runs the client: --client-task or --task, else the one runClient task. */
    String clientTask(Modaccel.Options options) throws IOException {
        String task = options.get("client-task", options.get("task", ""));
        if (!task.isEmpty()) return task;
        List<String> tasks = new ArrayList<>(), warm = new ArrayList<>();
        for (Object p : Json.array(model.get("projects"))) for (Object t : Json.array(Json.object(p).get("tasks"))) {
            String path = Json.text(Json.object(t), "task", ""); if (path.endsWith(":runClient")) tasks.add(path); else if (path.endsWith(":runClientWarm")) warm.add(path);
        }
        if (tasks.isEmpty()) tasks = warm;
        if (tasks.size() != 1) throw new IOException("Choose --project and/or --task for the client: " + tasks);
        return tasks.get(0);
    }
    /**
     * Fails unless the running loader lists every mod this project's built metadata declares, with
     * its version. On failure the current frame is saved first, so the refusal can be looked at.
     */
    Map<String, Object> modsLoaded(Map<String, Object> session, Modaccel.Options options, Path shot) throws Exception {
        long start = System.nanoTime();
        List<Path> outputs = LoadedMods.outputs(model, LoadedMods.owner(clientTask(options)));
        List<LoadedMods.Declared> declared = LoadedMods.declared(outputs);
        Map<String, Object> answer = request(session, Json.map("command", "mods", "ids", LoadedMods.ids(declared)));
        Map<String, Object> verdict = LoadedMods.verdict(declared, outputs, answer);
        build.report.stages.add(Json.map("name", "mods-loaded", "seconds", (System.nanoTime() - start) / 1e9, "ok", verdict.get("ok"), "loader", verdict.get("loader")));
        if (!Boolean.TRUE.equals(verdict.get("ok"))) {
            String saved = "";
            try {
                Files.createDirectories(shot.getParent());
                request(session, Json.map("command", "capture", "path", shot.toString(), "width", 1280, "height", 720));
                saved = "\nFrame at the time: " + shot;
            } catch (Exception noCapture) { }
            throw new IOException(verdict.get("details") + saved + "\nGame log: " + session.get("log"));
        }
        return verdict;
    }
    /**
     * Runtime files setup could not download (recorded as runtimeUnresolved, e.g. ForgeGradle asking
     * Maven Central for lwjgl-freetype natives-macos-patch on Apple silicon). Compiling never reads
     * them, but the game cannot start without them, so a game check stops here and names them
     * instead of failing deep inside the launch. Null when nothing is missing.
     */
    static String missingRuntime(Map<String, Object> model, String owner) {
        Set<String> missing = new LinkedHashSet<>();
        for (Object item : model.get("projects") instanceof List<?> projects ? projects : List.of()) {
            var project = Json.object(item);
            if (owner != null && !owner.isEmpty() && !owner.equals(project.get("path"))) continue;
            for (Object set : project.get("sourceSets") instanceof List<?> sets ? sets : List.of()) {
                var sourceSet = Json.object(set);
                if (String.valueOf(sourceSet.get("name")).toLowerCase(Locale.ROOT).contains("test")) continue;
                missing.addAll(ProjectModel.strings(sourceSet.get("runtimeUnresolved")));
            }
        }
        if (missing.isEmpty()) return null;
        return "The game cannot start: " + missing.size() + (missing.size() == 1 ? " runtime file" : " runtime files") + " it needs could not be downloaded when the build was set up:\n  "
                + String.join("\n  ", missing)
                + "\nCompiling and logic tests do not need " + (missing.size() == 1 ? "it" : "them") + ", which is why those passed. This is usually a loader or Gradle plugin asking a repository"
                + " that does not publish the file (Forge on Apple silicon asks Maven Central for lwjgl natives-macos-patch, which only Mojang hosts)."
                + " Add a repository that has it, or run the game check on another machine; rerun with --refresh after changing the build. The game was not started.";
    }
    Map<String, Object> start(Modaccel.Options options, String fingerprint) throws Exception {
        Path agent = agent(); String token = UUID.randomUUID().toString();
        String task = clientTask(options);
        String missing = missingRuntime(model, LoadedMods.owner(task));
        if (missing != null) throw new IOException(missing);
        Path recipe = build.project.state().resolve("launch.json");
        List<String> command; Map<String, String> environment = new HashMap<>(); Path directory; String mode;
        boolean jbr = options.has("jbr");
        // --java: start on the runtime players actually have (a launcher's bundled Java, one vendor's
        // build), since some failures only happen on one runtime.
        Path java = options.values.containsKey("java") ? javaExecutable(options.get("java", "")) : null;
        boolean saved = Files.exists(recipe) && Boolean.TRUE.equals(Json.read(recipe).get("verified")) && fingerprint.equals(Json.read(recipe).get("fingerprint")) && task.equals(Json.read(recipe).get("task"))
                && jbr == Boolean.TRUE.equals(Json.read(recipe).get("jbr")) && replayable(Json.read(recipe));
        if (!saved && (jbr || java != null)) {
            Run.Result prepared = build.gradle(List.of("-Dmodaccel.recompile=true", "-Dmodaccel.captureTask=" + task, "-Dmodaccel.agent=" + agent, "-Dmodaccel.fingerprint=" + fingerprint,
                    "-Dmodaccel.token=" + token, "-Dmodaccel.jbr=" + jbr, "-Dmodaccel.recordOnly=true", task), jbr ? "prepare-jbr-launch" : "prepare-launch");
            if (prepared.exit() != 0 || !Files.exists(recipe) || jbr && Json.read(recipe).get("jbrExecutable") == null)
                throw new IOException("Could not prepare the " + (jbr ? "JetBrains Runtime " : "") + "launch.\n" + Run.failure(prepared.log()));
            if (!replayable(Json.read(recipe)))
                throw new IOException("This build's " + task + " supplies its classpath only while Gradle runs it, so the recorded launch cannot be started on "
                        + (jbr ? "JetBrains Runtime" : "--java") + ". Start it without " + (jbr ? "--jbr" : "--java") + ".");
            saved = true;
        }
        if (saved) {
            var launch = Json.read(recipe); command = new ArrayList<>();
            command.add(java != null ? java.toString() : launch.get(jbr ? "jbrExecutable" : "executable").toString());
            if (jbr) command.addAll(List.of("-XX:+AllowEnhancedClassRedefinition", "-XX:TieredStopAtLevel=1"));
            for (String argument : ProjectModel.strings(launch.get("arguments"))) command.add(argument.startsWith("-Dmodaccel.token=") ? "-Dmodaccel.token=" + token : argument);
            Json.object(launch.get("environment")).forEach((key, value) -> { if (value != null) environment.put(key, value.toString()); });
            directory = Path.of(launch.get("directory").toString()); mode = "saved launch; dependencies already prepared" + (java != null ? "; runtime " + java : "");
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
        try { value = awaitFirstFrame(ready, log, saved); }
        catch (Exception failure) {
            process.descendants().forEach(ProcessHandle::destroy); process.destroy();
            throw new IOException(startupFailure(failure, log));
        } finally { watch.close(); }
        long pid = ((Number)value.get("pid")).longValue();
        value.put("processStart", ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Instant::toString).orElseThrow());
        if (java != null) value.put("java", java.toString());
        value.put("log", log.toString()); Json.save(build.project.state().resolve("agent.json"), value);
        if (Files.exists(recipe)) { var launch = Json.read(recipe); if (fingerprint.equals(launch.get("fingerprint"))) { launch.put("verified", true); Json.save(recipe, launch); } }
        build.report.stages.add(Json.map("name", "game-start", "seconds", (System.nanoTime() - start) / 1e9, "mode", mode, "log", log.toString()));
        return value;
    }
    /**
     * A recorded launch can only be replayed if it says where the classes are. NeoGradle 7.0 (Jade)
     * and ForgeGradle 6 (MineColonies) supply the classpath and program arguments at exec time, so
     * the recipe holds only JVM flags and the main class, and replaying it failed with
     * "Could not find or load main class cpw.mods.bootstraplauncher.BootstrapLauncher". Such a
     * recipe is left to Gradle, which prepares and launches the client again.
     */
    static boolean replayable(Map<String, Object> launch) {
        for (String argument : ProjectModel.strings(launch.get("arguments")))
            if (Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path", "-jar").contains(argument) || argument.startsWith("@")) return true;
        return false;
    }
    static final long GAME_START_SECONDS = 180, GRADLE_PREPARE_SECONDS = 30 * 60;
    /**
     * Waits for the client's first frame. The 180 s budget starts when the game itself starts:
     * when Gradle launches it, Gradle first compiles and prepares the run, which on a big mod
     * (MineColonies: about 4 minutes of javac) used to use up the whole budget before the game
     * existed. Gradle's own phase gets a separate, much larger budget; its exit still fails at once.
     * A Fabric loader that rejects the mods leaves an error window open instead of exiting, so
     * that line fails the start at once instead of after the whole timeout.
     */
    static Map<String, Object> awaitFirstFrame(CompletableFuture<Map<String, Object>> ready, Path log, boolean launchedDirectly) throws Exception {
        long begin = System.nanoTime(), gameStarted = launchedDirectly ? begin : 0, offset = 0;
        StringBuilder tail = new StringBuilder();
        while (true) {
            try { return ready.get(1, TimeUnit.SECONDS); }
            catch (TimeoutException waiting) { }
            if (Files.exists(log)) try (var file = new RandomAccessFile(log.toFile(), "r")) {
                if (file.length() > offset) {
                    file.seek(offset); byte[] bytes = new byte[(int)Math.min(file.length() - offset, 1 << 20)]; file.readFully(bytes); offset += bytes.length;
                    tail.append(new String(bytes, StandardCharsets.UTF_8)); if (tail.length() > 4096) tail.delete(0, tail.length() - 4096);
                    if (gameStarted == 0 && tail.indexOf("MODACCEL_LAUNCH") >= 0) gameStarted = System.nanoTime();
                    if (tail.indexOf("Incompatible mods found!") >= 0) throw new IOException("The mod loader rejected the mods (Incompatible mods found!); the reason is in the game log.");
                }
            }
            long now = System.nanoTime();
            if (gameStarted != 0 && now - gameStarted > GAME_START_SECONDS * 1_000_000_000L)
                throw new TimeoutException("no rendered frame " + GAME_START_SECONDS + " s after the game started");
            if (gameStarted == 0 && now - begin > GRADLE_PREPARE_SECONDS * 1_000_000_000L)
                throw new TimeoutException("Gradle did not start the game within " + GRADLE_PREPARE_SECONDS + " s");
        }
    }
    /** The java executable for --java, given a Java home or the executable itself. */
    static Path javaExecutable(String value) throws IOException {
        Path given = Path.of(value).toAbsolutePath().normalize();
        Path executable = Files.isDirectory(given) ? given.resolve("bin").resolve(Runtimes.WINDOWS ? "java.exe" : "java") : given;
        if (!Files.isRegularFile(executable)) throw new IOException("--java needs a Java home or a java executable; nothing at " + executable);
        return executable;
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
        Path source = build.harness.resolve("tools/agent/PortableAgent.java"), console = build.harness.resolve("tools/agent/Console.java"), reload = build.harness.resolve("tools/agent/HotReload.java"), json = build.harness.resolve("tools/cli/Json.java"), quiet = build.harness.resolve("tools/cli/QuietOptions.java");
        String key = Fingerprints.files(List.of(source, console, reload, json, quiet, asm, build.harness.resolve("tools/cli/Relocate.java"))) + "-private-asm-redefine";
        Path dir = build.project.state().resolve("agent/" + key); Files.createDirectories(dir);
        Path jar = dir.resolve("modaccel-agent.jar"); if (Files.exists(jar)) return jar;
        Path classes = dir.resolve("classes"); Files.createDirectories(classes);
        var result = Run.command(List.of(Runtimes.ensureAtLeast(build.harness, 17).home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release", "8", "-cp", asm.toString(), "-d", classes.toString(), source.toString(), console.toString(), reload.toString(), json.toString(), quiet.toString()), build.project.root, Map.of(), build.report.log("agent-compile"), 120);
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
