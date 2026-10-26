import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Starts only this project's already licensed dedicated test server. */
final class Server implements AutoCloseable {
    final Build build;
    final Process process;
    final Path log;
    final int port;
    final String task;
    Server(Build build, Map<String, Object> model, Modaccel.Options options) throws Exception {
        this.build = build;
        String selected = options.get("server-task", "");
        List<String> available = new ArrayList<>();
        for (Object p : Json.array(model.get("projects"))) for (Object t : Json.array(Json.object(p).get("tasks"))) {
            String name = Json.text(Json.object(t), "task", "");
            if (name.endsWith(":runServerLab") || name.endsWith(":runServer")) available.add(name);
        }
        if (selected.isEmpty()) selected = available.stream().filter(x -> x.endsWith(":runServerLab")).findFirst()
                .orElseGet(() -> available.size() == 1 ? available.get(0) : "");
        if (!available.contains(selected)) throw new IOException("Choose --server-task from evaluated Gradle tasks: " + available);
        task = selected;
        String directory = "";
        for (Object p : Json.array(model.get("projects"))) for (Object t : Json.array(Json.object(p).get("tasks"))) {
            Map<String, Object> candidate = Json.object(t);
            if (task.equals(Json.text(candidate, "task", ""))) directory = Json.text(candidate, "directory", "");
        }
        if (directory.isEmpty()) throw new IOException("No evaluated run directory for " + task + "; rerun with --refresh to discover the task settings.");
        Path run = Path.of(directory).toAbsolutePath().normalize();
        Path eula = run.resolve("eula.txt"), properties = run.resolve("server.properties");
        Properties acceptance = new Properties();
        if (Files.isRegularFile(eula)) try (var input = Files.newInputStream(eula)) { acceptance.load(input); }
        if (!"true".equals(acceptance.getProperty("eula")))
            throw new IOException("Dedicated test server EULA is not accepted in " + eula + "; start and configure that server yourself first.");
        if (!Files.isRegularFile(properties)) throw new IOException("Missing existing test server settings: " + properties);
        Properties settings = new Properties(); try (var input = Files.newInputStream(properties)) { settings.load(input); }
        port = Integer.parseInt(settings.getProperty("server-port", "25565"));
        try (Socket occupied = new Socket()) {
            occupied.connect(new InetSocketAddress("127.0.0.1", port), 300);
            throw new IOException("Port " + port + " is already occupied; stop that server or pass --server to check an existing one.");
        } catch (ConnectException free) { }
        log = build.report.log("server"); Files.createDirectories(log.getParent());
        Path recipe = build.project.state().resolve("server-launch.json");
        List<String> command = build.command(List.of("-Dmodaccel.recompile=true", "-Dmodaccel.captureTask=" + task,
                "-Dmodaccel.launchFile=" + recipe, task));
        process = new ProcessBuilder(command).directory(build.project.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (WatchService watch = FileSystems.getDefault().newWatchService()) {
            log.getParent().register(watch, StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (true) {
                if (text(log).contains("Done (")) break;
                if (!process.isAlive()) throw new IOException("Dedicated server exited before it was ready" + death(process.exitValue(), log));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Dedicated server did not become ready in 180 seconds. Last lines:\n" + Run.tail(log) + "\nLog: " + log);
                WatchKey key = watch.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(5)), TimeUnit.NANOSECONDS);
                if (key != null) key.reset();
            }
        } catch (Exception failure) { close(); throw failure; }
        // process is the Gradle launcher; the game server is the JVM listening on the port.
        serverPid = listeningPid(port);
        build.report.stages.add(Json.map("name", "server-start", "task", task, "pid", serverPid, "launcherPid", process.pid(), "port", port, "log", log.toString(),
                "pidSource", serverPid == null ? "not found: no process was seen listening on port " + port : "the process listening on port " + port));
    }
    Long serverPid;
    public void close() {
        if (!process.isAlive()) {
            // It died before modaccel stopped it: say how, next to the result.
            build.report.stages.add(Json.map("name", "server-exit", "launcherExit", process.exitValue(), "log", log.toString(),
                    "details", "The dedicated server exited on its own before modaccel stopped it" + death(process.exitValue(), log)));
            return;
        }
        try { process.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); process.getOutputStream().flush();
            process.onExit().get(20, TimeUnit.SECONDS); }
        catch (Exception error) { process.descendants().forEach(ProcessHandle::destroy); process.destroy(); }
    }
    private static String text(Path log) {
        try { return new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8); } catch (IOException unreadable) { return ""; }
    }
    /** ": launcher exit N.", then the crash report's description, else the error lines, else the last lines; then the log. */
    static String death(int exit, Path log) {
        List<String> lines = text(log).lines().toList();
        StringBuilder out = new StringBuilder(exit >= 0 ? " (launcher exit code " + exit + ")." : ".");
        String report = null;
        for (String line : lines) {
            int at = line.indexOf("crash report has been saved to:");
            if (at < 0) at = line.indexOf("Crash report saved to:");
            if (at >= 0) report = line.substring(line.indexOf(':', at) + 1).replaceFirst("^\\s*(#@!@#\\s*)?", "").strip();
        }
        boolean fatal = lines.stream().anyMatch(line -> line.contains("A fatal error has been detected by the Java Runtime Environment"));
        if (fatal) out.append("\n  The server JVM crashed (fatal error banner in the log).");
        if (report != null) {
            out.append("\n  Minecraft crash report: ").append(report);
            List<String> crash = text(Path.of(report)).lines().toList();
            for (int i = 0; i < crash.size(); i++) {
                if (!crash.get(i).startsWith("Description:")) continue;
                out.append("\n  ").append(crash.get(i));
                int shown = 0;
                for (int j = i + 1; j < crash.size() && shown < 6; j++) {
                    if (crash.get(j).isBlank()) { if (shown > 0) break; continue; }
                    out.append("\n  ").append(crash.get(j).strip()); shown++;
                }
                break;
            }
        } else {
            List<String> errors = lines.stream().filter(line -> line.contains("/ERROR]") || line.contains("/FATAL]") || line.startsWith("Caused by:") || line.startsWith("Exception in thread")).toList();
            if (!errors.isEmpty()) { out.append("\n  Errors in its log:"); for (String line : errors.subList(Math.max(0, errors.size() - 6), errors.size())) out.append("\n  ").append(line); }
            else if (lines.stream().anyMatch(line -> line.startsWith("* What went wrong:"))) {
                try { out.append("\n  Gradle: ").append(Run.gradleFailure(log).replace("\n", "\n  ")); } catch (IOException unreadable) { }
            } else { out.append("\n  No crash report. Last lines of its log:"); for (String line : lines.subList(Math.max(0, lines.size() - 8), lines.size())) out.append("\n  ").append(line); }
        }
        return out.append("\nServer log: ").append(log).toString();
    }
    /** The pid listening on a local TCP port, from the platform's own tool; null when it cannot tell. */
    static Long listeningPid(int port) {
        List<String> command = Runtimes.WINDOWS ? List.of("netstat", "-ano", "-p", "TCP") : List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t");
        try {
            Process lookup = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(lookup.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!lookup.waitFor(10, TimeUnit.SECONDS)) { lookup.destroyForcibly(); return null; }
            return parseListeningPid(output, port, Runtimes.WINDOWS);
        } catch (Exception unavailable) { return null; }
    }
    static Long parseListeningPid(String output, int port, boolean netstat) {
        for (String line : output.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (netstat) {
                if (parts.length >= 5 && parts[0].equalsIgnoreCase("TCP") && parts[1].endsWith(":" + port) && parts[3].equalsIgnoreCase("LISTENING") && parts[4].matches("\\d+"))
                    return Long.parseLong(parts[4]);
            } else if (parts.length == 1 && parts[0].matches("\\d+")) return Long.parseLong(parts[0]);
        }
        return null;
    }
}
