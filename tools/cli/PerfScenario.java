import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Explicit, bounded scenario files drive a local test server through RCON. */
final class PerfScenario {
    static Map<String, Object> run(Path home, Modaccel.Options options, Modaccel.Report receipt) throws Exception {
        Map<String, Object> scenario = Json.read(Path.of(options.get("scenario", "")));
        List<Object> cases = validate(scenario);
        if (options.values.containsKey("only")) {
            Set<String> selected = new HashSet<>(Arrays.asList(options.get("only", "").split(",")));
            cases = cases.stream().filter(c -> selected.contains(Json.object(c).get("id"))).toList();
            if (cases.size() != selected.size()) throw new IllegalArgumentException("--only names unknown or duplicate scenario cases");
        }
        int seconds = Performance.integer(options, "duration", 30, 1, 3600);
        int warmup = Performance.integer(options, "warmup", 0, 0, 600);
        int repeat = Performance.integer(options, "repeat", 1, 1, 20);
        if ((long) cases.size() * repeat * (seconds + warmup) > 14400) {
            throw new IllegalArgumentException("Scenario exceeds four-hour capture budget; reduce cases, duration or repeats");
        }
        Path out = Performance.output(options);
        if (Files.exists(out.resolve("suite.json"))) throw new IOException("Scenario output already exists: " + out);
        String pid = Performance.select(home, options, receipt);
        Path agent = Performance.agent(home, receipt);
        String password = System.getenv(options.get("rcon-password-env", "MODACCEL_RCON_PASSWORD"));
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Set the RCON password environment variable first");
        int port = Performance.integer(options, "rcon-port", 25575, 1, 65535);
        List<Object> results = new ArrayList<>();
        List<Object> order = new ArrayList<>();
        for (int i = 0; i < repeat; i++) order.addAll(cases);
        Collections.shuffle(order, new Random(Long.parseLong(options.get("seed", "0"))));
        Map<String, Object> suite = Json.map("schema", 1, "name", scenario.get("name"), "pid", pid,
            "scenarioSha256", Fingerprints.bytes(Files.readAllBytes(Path.of(options.get("scenario", "")))),
            "seed", options.get("seed", "0"), "cases", results, "ok", false,
            "meaning", "Within-session scenario observations. Repeats share one JVM; not independent launches or causal comparisons.");
        Json.save(out.resolve("suite.json"), suite);
        try (Rcon rcon = new Rcon(port, password)) {
            int index = 0;
            for (Object value : order) {
                Map<String, Object> test = Json.object(value);
                Path capture = out.resolve(String.format(Locale.ROOT, "%03d", ++index));
                Files.createDirectories(capture);
                Map<String, Object> row = Json.map("id", test.get("id"), "parameters", test.get("parameters"), "capture", capture.toString());
                results.add(row);
                List<Object> evidence = Collections.synchronizedList(new ArrayList<>());
                row.put("evidence", evidence);
                try {
                    commands(rcon, test, "setup", evidence);
                    commands(rcon, test, "before", evidence);
                    Path request = Performance.request(capture, seconds, warmup, options.has("no-hooks"));
                    Performance.probes(request, options);
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    java.util.concurrent.Future<?> actions = executor.submit(() -> {
                        try { during(rcon, test, evidence, capture, seconds, warmup); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    });
                    try {
                        Performance.capture(pid, agent, request, capture, seconds, warmup, !options.has("json"));
                        actions.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    } finally {
                        actions.cancel(true);
                        executor.shutdownNow();
                        if (!executor.awaitTermination(12, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Scenario action did not stop");
                    }
                    commands(rcon, test, "after", evidence);
                    row.put("correctnessPassed", true);
                    row.put("report", PerfReport.write(capture, capture));
                    row.put("metrics", Json.read(capture.resolve("report.json")).get("metrics"));
                } catch (Exception error) {
                    row.put("correctnessPassed", false);
                    row.put("error", error.toString());
                } finally {
                    try { commands(rcon, test, "cleanup", evidence); }
                    catch (Exception error) { row.put("cleanupError", error.toString()); }
                    Json.save(out.resolve("suite.json"), suite);
                }
            }
        }
        boolean ok = results.stream().allMatch(r -> Boolean.TRUE.equals(Json.object(r).get("correctnessPassed")) && !Json.object(r).containsKey("cleanupError"));
        suite.put("ok", ok);
        Json.save(out.resolve("suite.json"), suite);
        String data = Json.write(suite).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        Files.writeString(out.resolve("index.html"), Files.readString(home.resolve("tools/perf/suite.html")).replace("__DATA__", data));
        return Json.map("ok", ok, "suite", out.resolve("suite.json").toString(), "html", out.resolve("index.html").toString(),
            "details", "Scenario run finished. Open " + out.resolve("index.html"));
    }

    static List<Object> validate(Map<String, Object> scenario) {
        if (!Long.valueOf(1).equals(scenario.get("schema")) && !Integer.valueOf(1).equals(scenario.get("schema"))) {
            throw new IllegalArgumentException("Scenario schema must be integer 1");
        }
        List<Object> cases = Json.array(scenario.get("cases"));
        if (cases.isEmpty() || cases.size() > 256) throw new IllegalArgumentException("Scenario needs 1..256 cases");
        Set<String> ids = new HashSet<>();
        for (Object value : cases) {
            Map<String, Object> test = Json.object(value);
            String id = Json.text(test, "id", "");
            if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Case IDs must be nonempty and unique");
            if (!test.containsKey("after") || Json.array(test.get("after")).isEmpty()) throw new IllegalArgumentException("Each case needs after assertions");
            for (String phase : List.of("setup", "before", "during", "after", "cleanup")) {
                if (!test.containsKey(phase)) continue;
                List<Object> steps = Json.array(test.get(phase));
                if (steps.size() > 1024) throw new IllegalArgumentException("Too many commands in " + phase);
                for (Object step : steps) {
                    Map<String, Object> command = Json.object(step);
                    if (command.containsKey("waitMs") && (!(command.get("waitMs") instanceof Number wait) || wait.longValue() < 0 || wait.longValue() > 5000)) {
                        throw new IllegalArgumentException("waitMs must be 0..5000");
                    }
                    if (phase.equals("during") && (!(command.get("atSeconds") instanceof Number at) || !Double.isFinite(at.doubleValue()) || at.doubleValue() < 0)) {
                        throw new IllegalArgumentException("during commands need a nonnegative atSeconds");
                    }
                    String text = Json.text(command, "command", "");
                    List<String> expected = expected(command);
                    if (text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 1400 || text.contains("\n") || expected.isEmpty() || expected.stream().anyMatch(String::isBlank)) {
                        throw new IllegalArgumentException("Every command needs a nonempty literal expected response and at most 1400 UTF-8 command bytes");
                    }
                }
            }
        }
        return cases;
    }

    static void during(Rcon rcon, Map<String, Object> test, List<Object> evidence, Path capture, int seconds, int warmup) throws Exception {
        if (!test.containsKey("during")) return;
        List<Object> actions = new ArrayList<>(Json.array(test.get("during")));
        actions.sort(Comparator.comparingDouble(a -> ((Number) Json.object(a).get("atSeconds")).doubleValue()));
        if (actions.stream().anyMatch(a -> ((Number) Json.object(a).get("atSeconds")).doubleValue() >= seconds)) {
            throw new IOException("during actions must occur before the capture duration ends");
        }
        long deadline = System.nanoTime() + (warmup + 60L) * 1_000_000_000L;
        while (!Performance.properties(capture.resolve("status.properties")).getProperty("state", "").equals("recording")) {
            if (System.nanoTime() > deadline) throw new IOException("Capture did not start for timed actions");
            Thread.sleep(20);
        }
        long start = System.nanoTime();
        for (Object value : actions) {
            Map<String, Object> action = Json.object(value);
            long at = start + (long) (((Number) action.get("atSeconds")).doubleValue() * 1e9);
            while (System.nanoTime() < at) Thread.sleep(Math.min(100, Math.max(1, (at - System.nanoTime()) / 1_000_000)));
            execute(rcon, action, "during", evidence);
        }
    }

    static void commands(Rcon rcon, Map<String, Object> test, String phase, List<Object> evidence) throws Exception {
        if (!test.containsKey(phase)) return;
        List<String> failures = new ArrayList<>();
        for (Object step : Json.array(test.get(phase))) {
            try { execute(rcon, Json.object(step), phase, evidence); }
            catch (Exception error) {
                if (!phase.equals("cleanup")) throw error;
                failures.add(error.toString());
            }
        }
        if (!failures.isEmpty()) throw new IOException(String.join("; ", failures));
    }

    static void execute(Rcon rcon, Map<String, Object> command, String phase, List<Object> evidence) throws Exception {
        String text = command.get("command").toString();
        List<String> expected = expected(command);
        String response = rcon.command(text);
        boolean passed = expected.stream().anyMatch(response::contains) || Boolean.TRUE.equals(command.get("expectEmpty")) && response.isEmpty();
        evidence.add(Json.map("phase", phase, "at", java.time.Instant.now().toString(),
            "command", text, "response", response, "expected", expected, "passed", passed));
        if (!passed) throw new IOException(phase + " assertion failed: " + text + " => " + response);
        if (command.containsKey("waitMs")) Thread.sleep(((Number) command.get("waitMs")).longValue());
    }

    static List<String> expected(Map<String, Object> command) {
        return command.containsKey("expectAny") ? ProjectModel.strings(command.get("expectAny")) : List.of(Json.text(command, "expect", ""));
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static final class Rcon implements AutoCloseable {
        final Socket socket = new Socket();
        InputStream input;
        OutputStream output;
        int serial = 10;
        Rcon(int port, String password) throws IOException {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(10000);
                input = socket.getInputStream();
                output = socket.getOutputStream();
                send(1, 3, password);
                for (int i = 0; i < 3; i++) {
                    Packet packet = read();
                    if (packet.id == -1) throw new IOException("RCON authentication failed");
                    if (packet.id == 1 && packet.type == 2) return;
                }
                throw new IOException("Invalid RCON authentication response");
            } catch (IOException error) {
                socket.close();
                throw error;
            }
        }
        String command(String text) throws IOException {
            int id = serial++, marker = serial++;
            send(id, 2, text);
            // Vanilla's RCON reader expects one request per read. Do not pipeline the marker:
            // the TCP stack can coalesce the two writes and vanilla closes the connection.
            Packet first = read();
            if (first.id != id || first.type != 0) throw new IOException("Unexpected RCON command response");
            StringBuilder result = new StringBuilder(first.body);
            send(marker, 2, "");
            while (result.length() < 1_048_576) {
                Packet packet = read();
                if (packet.id == marker) return result.toString();
                if (packet.id != id || packet.type != 0) throw new IOException("Unexpected RCON response");
                result.append(packet.body);
            }
            throw new IOException("RCON response exceeds 1 MiB");
        }
        void send(int id, int type, String text) throws IOException {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bytes = ByteBuffer.allocate(body.length + 14).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(body.length + 10).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
            output.write(bytes.array());
            output.flush();
        }
        Packet read() throws IOException {
            byte[] length = input.readNBytes(4);
            if (length.length != 4) throw new EOFException("RCON closed");
            int size = ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (size < 10 || size > 1_048_576) throw new IOException("Invalid RCON packet size");
            byte[] bytes = input.readNBytes(size);
            if (bytes.length != size || bytes[size - 1] != 0 || bytes[size - 2] != 0) throw new IOException("Truncated RCON packet");
            ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Packet(packet.getInt(), packet.getInt(), new String(bytes, 8, size - 10, StandardCharsets.UTF_8));
        }
        public void close() throws IOException { socket.close(); }
        record Packet(int id, int type, String body) { }
    }
}
