import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Real JFR and dynamic attach against synthetic game-shaped methods, not a Minecraft performance claim. */
public final class PerfTest {
    static int checks;
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        adviceEvidence();
        check(Run.running(ProcessHandle.current()), "current process is running");
        check(Run.exitedProcStat("123 (name with ) spaces) Z 1 2 3"), "unreaped Linux exits are not running games");
        check(!Run.exitedProcStat("123 (java) S 1 2 3"), "sleeping Linux process remains live");
        Path home = Path.of(args[0]);
        Path scratch = Files.createTempDirectory(Path.of(args[1]).toAbsolutePath().getParent(), "perf-fixture-");
        Modaccel.Report receipt = new Modaccel.Report(scratch, "perf-test");
        Path agent = Performance.agent(home, receipt);
        Path source = scratch.resolve("net/minecraft/client/Minecraft.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package net.minecraft.client;
            public class Minecraft {
                static volatile long sink;
                public void runTick(boolean render) throws Exception {
                    long end = System.nanoTime() + 8_000_000;
                    while (System.nanoTime() < end) sink++;
                    Thread.sleep(2);
                }
                public static void main(String[] args) throws Exception {
                    Minecraft mc = new Minecraft();
                    net.minecraft.server.MinecraftServer server = new net.minecraft.server.MinecraftServer();
                    while (true) {
                        mc.runTick(true);
                        server.tickServer(() -> true);
                    }
                }
            }
            """);
        Path server = scratch.resolve("net/minecraft/server/MinecraftServer.java");
        Files.createDirectories(server.getParent());
        Files.writeString(server, """
            package net.minecraft.server;
            public class MinecraftServer {
                public void tickServer(java.util.function.BooleanSupplier running) throws Exception {
                    Thread.sleep(3);
                }
            }
            """);
        Path jdk = Runtimes.ensureAtLeast(home, 17).home();
        Run.Result compilation = Run.command(List.of(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
            "--release", "17", "-d", scratch.toString(), source.toString(), server.toString()), home, Map.of(), scratch.resolve("compile.log"), 30);
        check(compilation.exit() == 0, "fixture compilation: " + Run.tail(compilation.log()));
        Process process = new ProcessBuilder(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "java.exe" : "java")).toString(),
            "-cp", scratch.toString(), "net.minecraft.client.Minecraft").redirectErrorStream(true).redirectOutput(scratch.resolve("fixture.log").toFile()).start();
        try {
            Thread.sleep(500);
            String pid = Long.toString(process.pid());
            Path first = scratch.resolve("first");
            Files.createDirectories(first);
            Path request = Performance.request(first, 2, 0, false);
            Path probes = scratch.resolve("probes.json");
            Json.save(probes, Json.map("probes", List.of(Json.map("class", "net.minecraft.client.Minecraft",
                "method", "runTick", "descriptor", "(Z)V", "label", "renderCalls"))));
            Performance.probes(request, new Modaccel.Options(new String[]{"perf", "--probes", probes.toString()}));
            Performance.capture(pid, agent, request, first, 2, 0, false);
            check(process.isAlive(), "profiling must leave the target running");
            check(Performance.properties(first.resolve("status.properties")).getProperty("errors", "").isEmpty(), "hooks install and restore without errors");
            PerfReport.write(first, first);
            Map<String, Object> report = Json.read(first.resolve("report.json"));
            Map<String, Object> metrics = Json.object(report.get("metrics"));
            check(metrics.containsKey("frame_ms") && metrics.containsKey("tick_ms"), "both recognized timing hooks must produce data");
            check(((Number) Json.object(report.get("probeCalls")).get("renderCalls")).longValue() > 0, "explicit method probe counted entries alongside frame hook");
            check(((Number) report.get("fps")).doubleValue() > 0, "FPS calculated from measured intervals");
            check(((Number) Json.object(metrics.get("tick_ms")).get("p50")).doubleValue() >= 2, "tick hook measures real sleeps");
            check(((Number) report.get("executionSamples")).longValue() > 0, "CPU execution samples recorded");
            check(Files.readString(first.resolve("report.html")).contains("What this session costs"), "HTML report generated");
            check(!Files.readString(first.resolve("report.html")).contains("__DATA__"), "report has embedded data");
            check(Files.isRegularFile(first.resolve("stacks.speedscope.json")), "sampled stack export exists");
            Path second = scratch.resolve("no-hooks");
            Files.createDirectories(second);
            Performance.capture(pid, agent, Performance.request(second, 1, 0, true), second, 1, 0, false);
            PerfReport.write(second, second);
            Map<String, Object> unhooked = Json.read(second.resolve("report.json"));
            check(!Json.object(unhooked.get("metrics")).containsKey("frame_ms"), "hooks removed and missing FPS is not fabricated");
            Path third = scratch.resolve("repeat");
            Files.createDirectories(third);
            Performance.capture(pid, agent, Performance.request(third, 1, 0, false), third, 1, 0, false);
            PerfReport.write(third, third);
            double maximum = ((Number) Json.object(Json.object(Json.read(third.resolve("report.json")).get("metrics")).get("frame_ms")).get("max")).doubleValue();
            check(maximum < 1000, "repeated captures must not include the interval between recordings");
            boolean refused = false;
            try { Performance.request(first, 1, 0, false); }
            catch (IOException expected) { refused = true; }
            check(refused, "existing captures cannot be overwritten");
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
        check(PerfReport.percentile(List.of(1.0, 2.0, 3.0, 4.0), .5) == 2.5, "linear percentile");
        check(Performance.candidate("net.fabricmc.loader.impl.launch.knot.KnotClient"), "recognize Fabric");
        check(Performance.candidate("cpw.mods.bootstraplauncher.BootstrapLauncher"), "recognize Forge bootstrap launcher");
        check(Performance.candidate("org.prismlauncher.EntryPoint"), "recognize Prism launcher game entry");
        check(!Performance.candidate("org.gradle.launcher.GradleDaemon"), "do not automatically attach to Gradle");
        Map<String, Object> scenario = Json.map("schema", 1, "cases", List.of(Json.map("id", "one", "after",
            List.of(Json.map("command", "list", "expect", "players")))));
        check(PerfScenario.validate(scenario).size() == 1, "scenario assertions accepted");
        Json.object(Json.array(scenario.get("cases")).get(0)).put("after", List.of());
        boolean rejected = false;
        try { PerfScenario.validate(scenario); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "a scenario with no correctness assertion is rejected");
        rconProtocol();
        System.out.println(checks + " performance checks passed (synthetic JVM fixture; no Minecraft claims)");
    }
    static void adviceEvidence() {
        Map<String, Object> absent = Json.map("metrics", Map.of(), "executionSamples", 0);
        PerfAdvice.enrich(absent);
        List<Object> catalog = Json.array(absent.get("metricCatalog"));
        check(catalog.size() >= 24, "report exposes a broad metric checklist");
        check(catalog.stream().allMatch(v -> Json.object(v).get("value") == null), "missing telemetry must not become measured zero");
        check(Json.array(absent.get("recommendations")).size() == 1
            && Json.object(Json.array(absent.get("recommendations")).get(0)).get("id").equals("capture-coverage"),
            "missing telemetry requests evidence rather than inventing a bottleneck");
        Map<String, Object> quiet = Json.map("metrics", Map.of(), "framesOver50ms", 0, "ticksOver50ms", 0);
        PerfAdvice.enrich(quiet);
        check(Json.array(quiet.get("metricCatalog")).stream().filter(v -> Json.object(v).get("availability").equals("recorded")).count() == 2,
            "recorded zero counts remain available");
        Map<String, Object> slow = Json.map("metrics", Json.map("frame_ms", Json.map("p99", 90)),
            "framesOver50ms", 12, "ticksOver50ms", 4, "gcPauseTotalMs", 30, "observedMonitorBlockedMs", 70,
            "hotMethods", List.of(Json.map("name", "fixture.Expensive.work()V", "samples", 100, "percent", 50)), "executionSamples", 200);
        PerfAdvice.enrich(slow);
        List<Object> findings = Json.array(slow.get("recommendations"));
        check(findings.size() == 5, "frame, tick, GC, contention and method evidence produce separate investigation steps");
        check(findings.stream().allMatch(v -> Boolean.FALSE.equals(Json.object(v).get("fixVerified"))
            && !Json.object(Json.object(v).get("evidence")).isEmpty() && !Json.object(v).get("validation").toString().isBlank()),
            "every suggestion has evidence and a validation procedure; none asserts an untested fix");
        check(Json.object(Json.object(findings.get(0)).get("evidence")).get("framesOver50ms").equals(12), "recommendations preserve exact source evidence");
    }

    static void rconProtocol() throws Exception {
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var task = java.util.concurrent.Executors.newSingleThreadExecutor();
            var served = task.submit(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var input = socket.getInputStream();
                    var output = socket.getOutputStream();
                    int[] auth = readPacket(input);
                    sendPacket(output, auth[0], 2, "");
                    int[] command = readPacket(input);
                    Thread.sleep(30);
                    if (input.available() != 0) throw new AssertionError("RCON requests must not be pipelined before first reply");
                    sendPacket(output, command[0], 0, "MULTI ");
                    sendPacket(output, command[0], 0, "PART");
                    int[] marker = readPacket(input);
                    sendPacket(output, marker[0], 0, "");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (PerfScenario.Rcon client = new PerfScenario.Rcon(listener.getLocalPort(), "test-secret")) {
                check(client.command("test").equals("MULTI PART"), "fragmented RCON response is assembled without pipelining requests");
                served.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally { task.shutdownNow(); }
        }
    }

    static int[] readPacket(InputStream input) throws Exception {
        int size = java.nio.ByteBuffer.wrap(input.readNBytes(4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        var body = java.nio.ByteBuffer.wrap(input.readNBytes(size)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return new int[]{body.getInt(), body.getInt()};
    }

    static void sendPacket(OutputStream output, int id, int type, String text) throws Exception {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var packet = java.nio.ByteBuffer.allocate(bytes.length + 14).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        packet.putInt(bytes.length + 10).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        output.write(packet.array());
        output.flush();
    }

}
