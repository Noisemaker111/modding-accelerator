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
                if (Files.readString(log).contains("Done (")) break;
                if (!process.isAlive()) throw new IOException("Dedicated server exited before ready: " + Run.tail(log) + "\nLog: " + log);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Dedicated server did not become ready in 180 seconds. Log: " + log);
                WatchKey key = watch.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(5)), TimeUnit.NANOSECONDS);
                if (key != null) key.reset();
            }
        } catch (Exception failure) { close(); throw failure; }
        build.report.stages.add(Json.map("name", "server-start", "task", task, "pid", process.pid(), "port", port, "log", log.toString()));
    }
    public void close() {
        if (!process.isAlive()) return;
        try { process.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); process.getOutputStream().flush();
            process.onExit().get(20, TimeUnit.SECONDS); }
        catch (Exception error) { process.descendants().forEach(ProcessHandle::destroy); process.destroy(); }
    }
}
