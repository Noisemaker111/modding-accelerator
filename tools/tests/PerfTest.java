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
        optimizerGates();
        modTemplates();
        check(Run.running(ProcessHandle.current()), "current process is running");
        Path modRoot = Path.of("/work/mymod").toAbsolutePath();
        check(PerfAttribution.underRoot(modRoot.resolve("versions/1.21.1/build/classes/java/main"), modRoot), "the mod's own build output is the mod");
        check(!PerfAttribution.underRoot(modRoot.resolve(".gradle/loom-cache/remapped_mods/fabric-api.jar"), modRoot),
            "dependencies Loom remaps inside the checkout are not the mod");
        Path copySource = Path.of("/elsewhere/copy-source").toAbsolutePath();
        Map<String, Object> copied = Json.map("root", copySource.toString(), "pid", Long.MAX_VALUE - 7, "processStart", "2026-01-01T00:00:00Z");
        check(!Game.foreign(copied, Path.of("/here")), "a copied checkout's exited session does not block this project");
        Map<String, Object> running = Json.map("root", copySource.toString(), "pid", ProcessHandle.current().pid(),
            "processStart", ProcessHandle.current().info().startInstant().map(java.time.Instant::toString).orElse(""));
        check(Game.foreign(running, Path.of("/here")), "another project's running game is still refused");
        check(!Game.foreign(running, copySource), "this project's own session is not foreign");
        check(Run.exitedProcStat("123 (name with ) spaces) Z 1 2 3"), "unreaped Linux exits are not running games");
        check(!Run.exitedProcStat("123 (java) S 1 2 3"), "sleeping Linux process remains live");
        Path home = Path.of(args[0]);
        runRecords(home);
        patternLibrary(home);
        Path scratch = Files.createTempDirectory(Path.of(args[1]).toAbsolutePath().getParent(), "perf-fixture-");
        Modaccel.Report receipt = new Modaccel.Report(scratch, "perf-test");
        Path agent = Performance.agent(home, receipt);
        Path source = scratch.resolve("net/minecraft/client/Minecraft.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package net.minecraft.client;
            public class Minecraft {
                static volatile long sink;
                static final com.example.mod.ModBlock BLOCK = new com.example.mod.ModBlock();
                public void runTick(boolean render) throws Exception {
                    if (outer() != 7L) throw new AssertionError("return value changed");
                    net.neoforged.bus.EventBus.post(new net.neoforged.neoforge.event.TickEvent());
                    BLOCK.tick();
                    long end = System.nanoTime() + 8_000_000;
                    while (System.nanoTime() < end) sink++;
                    Thread.sleep(2);
                }
                public long outer() throws Exception {
                    inner();
                    try { fails(); } catch (NumberFormatException expected) { }
                    recursive(2);
                    return 7L;
                }
                public void inner() throws Exception { Thread.sleep(22); }
                public void fails() { Integer.parseInt("not-a-number"); }
                public int recursive(int n) { return n > 0 ? recursive(n - 1) : n; }
                public static void main(String[] args) throws Exception {
                    Minecraft mc = new Minecraft();
                    Thread worker = new Thread(() -> {
                        try { while (true) mc.outer(); } catch (Exception error) { throw new RuntimeException(error); }
                    }, "fixture-worker");
                    worker.setDaemon(true);
                    worker.start();
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
        List<Path> sources = new ArrayList<>(List.of(source, server));
        sources.add(fixture(scratch, "net/minecraft/world/level/block/Block.java", "package net.minecraft.world.level.block; public class Block { public void tick() { } }"));
        sources.add(fixture(scratch, "net/neoforged/neoforge/event/TickEvent.java", "package net.neoforged.neoforge.event; public class TickEvent { }"));
        sources.add(fixture(scratch, "net/neoforged/bus/EventBus.java", "package net.neoforged.bus; public class EventBus { "
            + "public static void post(net.neoforged.neoforge.event.TickEvent event) { com.example.mod.Handler.onTick(event); } }"));
        sources.add(fixture(scratch, "com/example/mod/Handler.java", "package com.example.mod; public class Handler { static volatile long sink; "
            + "public static void onTick(net.neoforged.neoforge.event.TickEvent event) { long end = System.nanoTime() + 6_000_000; while (System.nanoTime() < end) sink += String.valueOf(sink).hashCode(); } }"));
        sources.add(fixture(scratch, "com/example/mod/ModBlock.java", "package com.example.mod; public class ModBlock extends net.minecraft.world.level.block.Block { "
            + "static volatile long sink; final long[] state = new long[4096]; "
            + "public void tick() { long end = System.nanoTime() + 6_000_000; while (System.nanoTime() < end) sink += Long.toHexString(sink + state.length).length(); } }"));
        Path jdk = Runtimes.ensureAtLeast(home, 17).home();
        List<String> javac = new ArrayList<>(List.of(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
            "--release", "17", "-d", scratch.toString()));
        for (Path file : sources) javac.add(file.toString());
        Run.Result compilation = Run.command(javac, home, Map.of(), scratch.resolve("compile.log"), 30);
        check(compilation.exit() == 0, "fixture compilation: " + Run.tail(compilation.log()));
        // The mod's classes load from their own folder with fabric.mod.json, as a mod jar or dev output would.
        Path mod = scratch.resolve("mod-classes");
        Files.createDirectories(mod.resolve("com/example"));
        Files.move(scratch.resolve("com/example/mod"), mod.resolve("com/example/mod"));
        Files.writeString(mod.resolve("fabric.mod.json"), "{\"schemaVersion\":1,\"id\":\"examplemod\",\"name\":\"Example Mod\",\"version\":\"1\"}");
        Process process = new ProcessBuilder(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "java.exe" : "java")).toString(),
            "-cp", scratch + File.pathSeparator + mod, "net.minecraft.client.Minecraft").redirectErrorStream(true).redirectOutput(scratch.resolve("fixture.log").toFile()).start();
        try {
            Thread.sleep(500);
            String pid = Long.toString(process.pid());
            Path first = scratch.resolve("first");
            Files.createDirectories(first);
            Path request = Performance.request(first, 2, 0, false);
            Path probes = scratch.resolve("probes.json");
            List<Object> selectors = new ArrayList<>();
            selectors.add(Json.map("class", "net.minecraft.client.Minecraft", "method", "runTick", "descriptor", "(Z)V", "label", "renderCalls"));
            for (String method : List.of("outer", "inner", "fails", "recursive")) {
                String descriptor = method.equals("outer") ? "()J" : method.equals("recursive") ? "(I)I" : "()V";
                selectors.add(Json.map("class", "net.minecraft.client.Minecraft", "method", method,
                    "descriptor", descriptor, "label", method, "mode", "timing"));
            }
            Json.save(probes, Json.map("probes", selectors));
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
            functionTiming(report, first);
            attribution(report, first);
            Path second = scratch.resolve("no-hooks");
            Files.createDirectories(second);
            Performance.capture(pid, agent, Performance.request(second, 1, 0, true), second, 1, 0, false);
            PerfReport.write(second, second);
            Map<String, Object> unhooked = Json.read(second.resolve("report.json"));
            check(!Json.object(unhooked.get("metrics")).containsKey("frame_ms"), "hooks removed and missing FPS is not fabricated");
            check(((Number) Json.object(unhooked.get("functionTiming")).get("retainedCalls")).longValue() == 0,
                "timing probes are restored and do not leak into the next capture");
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
        allocationCharges(scratch, jdk, agent);
        Path rooted = scratch.resolve("rooted");
        Files.createDirectories(rooted);
        Path rootRequest = Performance.request(rooted, 1, 0, false);
        Performance.attribution(rootRequest, new Modaccel.Options(new String[]{"perf", "--root", scratch.toString()}));
        check(Performance.properties(rootRequest).getProperty("allowHidden", "").equals("true"),
            "perf --root profiles the game it started the way the background preference says, hidden or not");
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
    /**
     * The game thread allocates 3 GB before the capture, then a mod allocates about 20 MB/s and has a cheap method under a
     * timing probe. JFR's first allocation sample on that thread carries the 3 GB, and the probe's own bookkeeping allocates
     * inside the mod's method: neither is the mod's.
     */
    static void allocationCharges(Path scratch, Path jdk, Path agent) throws Exception {
        Path classes = scratch.resolve("allocation"), mod = scratch.resolve("allocation-mod");
        List<Path> sources = List.of(
            fixture(classes, "net/minecraft/server/Warm.java", "package net.minecraft.server; public class Warm { static volatile Object sink; "
                + "public static void fill() { for (int i = 0; i < 3000; i++) sink = new byte[1 << 20]; } "
                + "public static void main(String[] args) throws Exception { Thread server = new Thread(() -> { fill(); System.out.println(\"READY\"); long sum = 0; "
                + "while (true) { com.example.alloc.Spawner.tick(); for (int i = 0; i < 2000; i++) sum += com.example.alloc.Spawner.tiny(i); "
                + "try { Thread.sleep(12); } catch (InterruptedException stop) { return; } } }, \"Server thread\"); server.start(); server.join(); } }"),
            fixture(classes, "com/example/alloc/Spawner.java", "package com.example.alloc; public class Spawner { static volatile Object sink; "
                + "public static void tick() { sink = new byte[256 * 1024]; } public static int tiny(int x) { return x + 1; } }"));
        List<String> javac = new ArrayList<>(List.of(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
            "--release", "17", "-d", classes.toString()));
        for (Path file : sources) javac.add(file.toString());
        Run.Result compilation = Run.command(javac, classes, Map.of(), classes.resolve("compile.log"), 30);
        check(compilation.exit() == 0, "allocation fixture compilation: " + Run.tail(compilation.log()));
        Files.createDirectories(mod.resolve("com/example"));
        Files.move(classes.resolve("com/example/alloc"), mod.resolve("com/example/alloc"));
        Files.writeString(mod.resolve("fabric.mod.json"), "{\"schemaVersion\":1,\"id\":\"allocmod\",\"name\":\"Alloc Mod\",\"version\":\"1\"}");
        Path log = scratch.resolve("allocation.log");
        Process process = new ProcessBuilder(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "java.exe" : "java")).toString(), "-Xmx256m",
            "-cp", classes + File.pathSeparator + mod, "net.minecraft.server.Warm").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!Files.readString(log).contains("READY")) {
                check(process.isAlive() && System.nanoTime() < deadline, "allocation fixture starts: " + Files.readString(log));
                Thread.sleep(100);
            }
            Path out = scratch.resolve("allocation-capture");
            Files.createDirectories(out);
            Path request = Performance.request(out, 2, 0, false);
            Path probes = scratch.resolve("allocation-probes.json");
            Json.save(probes, Json.map("probes", List.of(Json.map("class", "com.example.alloc.Spawner", "method", "tiny", "descriptor", "(I)I",
                "label", "tiny", "mode", "timing"))));
            Performance.probes(request, new Modaccel.Options(new String[]{"perf", "--probes", probes.toString()}));
            Performance.capture(Long.toString(process.pid()), agent, request, out, 2, 0, false);
            PerfReport.write(out, out);
            Map<String, Object> attribution = Json.object(Json.read(out.resolve("report.json")).get("attribution"));
            Map<String, Object> charged = Json.array(attribution.get("mods")).stream().map(Json::object)
                .filter(m -> m.get("id").equals("allocmod")).findFirst().orElseThrow(() -> new AssertionError("allocation fixture mod identified"));
            double modRate = ((Number) charged.get("allocationBytesPerSecond")).doubleValue();
            check(modRate > 1e6 && modRate < 200e6,
                "a mod is charged what it allocates in the capture (about 20 MB/s), not the 3 GB its thread allocated before it: " + Math.round(modRate / 1e6) + " MB/s");
            double profilerRate = ((Number) Json.object(attribution.get("profilerOverhead")).get("allocationBytesPerSecond")).doubleValue();
            check(profilerRate > 1e6, "a timing probe's own allocation is profiler overhead, not the mod's: " + Math.round(profilerRate / 1e6) + " MB/s");
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    static Path fixture(Path scratch, String name, String text) throws IOException {
        Path file = scratch.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    static void attribution(Map<String, Object> report, Path directory) throws Exception {
        Map<String, Object> attribution = Json.object(report.get("attribution"));
        Map<String, Object> mod = Json.array(attribution.get("mods")).stream().map(Json::object)
            .filter(m -> m.get("id").equals("examplemod")).findFirst().orElseThrow(() -> new AssertionError("fixture mod identified from its fabric.mod.json"));
        check(mod.get("name").toString().contains("Example Mod"), "mod display name comes from its metadata");
        check(((Number) mod.get("samples")).longValue() > 0, "samples inside the mod are charged to it");
        List<Map<String, Object>> features = Json.array(mod.get("features")).stream().map(Json::object).toList();
        check(features.stream().anyMatch(f -> "event handler".equals(f.get("kind")) && f.get("name").toString().contains("TickEvent → Handler.onTick")),
            "a method called by an event bus is an event handler feature, named with its event: " + features);
        check(features.stream().anyMatch(f -> "block".equals(f.get("kind")) && f.get("name").toString().contains("ModBlock")),
            "a block subclass is a block feature, from its class chain: " + features);
        check(Json.array(attribution.get("mods")).stream().map(Json::object).noneMatch(m -> m.get("name").toString().contains("Minecraft")),
            "game and JVM code is never a mod");
        check(((Number) mod.get("heapShallowBytes")).longValue() > 0 && Json.array(mod.get("heapClasses")).stream()
            .anyMatch(c -> Json.object(c).get("name").equals("com.example.mod.ModBlock")), "live instances of the mod's own classes are measured after the capture");
        check(Json.array(attribution.get("threads")).size() > 0, "per-thread CPU load is reported");
        List<Object> suggested = Json.array(Json.read(directory.resolve("probes.suggested.json")).get("probes"));
        check(suggested.stream().map(Json::object).anyMatch(p -> p.get("class").equals("com.example.mod.Handler") && p.get("descriptor").toString().startsWith("(")),
            "timing probes are suggested for sampled mod methods with exact descriptors");
        check(Files.readString(directory.resolve("report.html")).contains("Where the cost is"), "report ranks cost by mod and feature");
        Map<String, Object> outer = Json.array(attribution.get("unattributed")).stream().map(Json::object)
            .filter(r -> r.get("label").equals("outer")).findFirst().orElseThrow(() -> new AssertionError("timed game method listed as unattributed exact cost"));
        check(Boolean.TRUE.equals(outer.get("complete")) && ((Number) outer.get("calls")).longValue() > 0,
            "exact rows come from complete agent totals, not the capped per-call history");
    }

    static void modTemplates() throws Exception {
        Path root = Files.createTempDirectory("perf-mod-");
        fixture(root, "src/main/resources/assets/demo/lang/en_us.json",
            "{\"block.demo.lamp\":\"Lamp\",\"entity.demo.ghoul\":\"Ghoul\",\"item.demo.dust\":\"Dust\",\"block.minecraft.stone\":\"x\",\"block.demo.lamp.desc\":\"y\"}");
        Map<String, List<String>> content = PerfTemplates.content(root);
        check(content.get("block").equals(List.of("demo:lamp")) && content.get("entity").equals(List.of("demo:ghoul")),
            "mod content comes from the mod's own lang keys, not items, vanilla ids or longer keys: " + content);
        Map<String, Object> made = PerfTemplates.run(new Modaccel.Options(new String[]{"perf-template", "--family", "mod-content", "--root", root.toString(),
            "--counts", "3,16", "--out", root.resolve("out").toString()}));
        Map<String, Object> scenario = Json.read(Path.of(made.get("scenario").toString()));
        List<Object> cases = PerfScenario.validate(scenario);
        check(cases.size() == 3, "one block scene and two crowd sizes");
        Map<String, Object> crowd = cases.stream().map(Json::object).filter(c -> c.get("id").equals("demo-ghoul-16")).findFirst().orElseThrow();
        long summons = Json.array(crowd.get("setup")).stream().map(Json::object).filter(s -> s.get("command").toString().startsWith("summon demo:ghoul")).count();
        check(summons == 16, "a crowd summons exactly its count");
        check(Json.array(crowd.get("cleanup")).stream().map(Json::object).anyMatch(s -> s.get("command").toString().contains("tag=modaccel_perf")),
            "crowd cleanup removes only the tagged entities");
    }

    /** Every session run writes the same record shape; phases, verdicts and rejection reasons are checked. */
    static void runRecords(Path home) throws Exception {
        PerfRecord record = new PerfRecord("perf-optimize", null);
        record.record.put("candidate", Json.map("label", "tints", "diffSha256", "ab12", "changedFiles", List.of()));
        Map<String, Object> launch = Json.map("stages", List.of(Json.map("name", "metadata", "seconds", 20.0), Json.map("name", "compile", "seconds", 30.0),
            Json.map("name", "game-start", "seconds", 40.0)), "result", Json.map("modsLoaded", Json.map("loader", "fabric",
            "mods", List.of(Json.map("id", "diorama", "loadedVersion", "0.10.1")))));
        record.launchStages("baseline-launch", launch, 95.0);
        record.modFromLaunch(launch);
        check(((Number) record.phases.get("build")).doubleValue() == 50.0 && ((Number) record.phases.get("launch")).doubleValue() == 45.0,
            "a launch splits into its build stages and the rest");
        check("diorama".equals(Json.object(record.record.get("mod")).get("id")) && "fabric".equals(Json.object(record.record.get("mod")).get("loader")),
            "the record names the loaded mod and loader from the launch receipt");
        record.step("nonsense", "x", 1.0);
        check(((Number) record.phases.get("other")).doubleValue() == 1.0, "an unknown phase counts as other");
        Map<String, Object> result = Json.map("speedup", Json.map("cold", Json.map("geometricMean", 1.0, "low", 0.9, "high", 1.1),
            "warm", Json.map("geometricMean", 1.2, "low", 1.1, "high", 1.3)), "gates", List.of(Json.map("name", "bakes", "passed", false, "failures", List.of("run 0 differs"))));
        String why = PerfRecord.rejection(result);
        check(why.contains("gate bakes failed") && why.contains("cold speedup") && !why.contains("warm speedup"), "a rejection names every failed gate and interval: " + why);
        Map<String, Object> done = record.finish("rejected", why);
        check(PerfRecord.validate(done).isEmpty(), "a complete rejected record validates: " + PerfRecord.validate(done));
        done.put("verdict", "accepted");
        check(PerfRecord.validate(done).stream().anyMatch(p -> p.contains("speedup.cold.low")), "an accepted record needs both intervals above 1");
        done.put("candidate", null);
        check(PerfRecord.validate(done).stream().anyMatch(p -> p.contains("candidate")), "a comparison names its candidate");
        if (Files.isRegularFile(home.resolve("coverage/perf-runs.json"))) {
            for (Object item : Json.array(Json.read(home.resolve("coverage/perf-runs.json")).get("runs"))) {
                Map<String, Object> kept = Json.object(item);
                check(PerfRecord.validate(kept).isEmpty(), "kept run " + kept.get("id") + " validates: " + PerfRecord.validate(kept));
            }
        }
    }

    /**
     * knowledge/perf-patterns.json: each pattern is complete, cites an accepted kept run, and its symptom
     * fires on its own test row and stays quiet on the other, as each port rule carries a test.
     */
    static void patternLibrary(Path home) throws Exception {
        Map<String, Object> mod = Json.map("id", "m", "samples", 100, "methods", List.of(Json.map("name", "a.Tints.blend(La/Snap;)V", "samples", 30),
            Json.map("name", "a.Other.run()V", "samples", 70)), "threads", List.of(Json.map("name", "worker-1", "samples", 90)));
        Map<String, Object> symptom = Json.map("text", "t", "when", List.of(Json.map("in", "methods", "matches", "\\.blend\\(", "minShare", 0.2),
            Json.map("in", "threads", "matches", "^worker", "minShare", 0.5)));
        check(PerfPatterns.evidence(symptom, mod) != null, "every clause holding matches");
        Map<String, Object> strict = Json.map("text", "t", "when", List.of(Json.map("in", "methods", "matches", "\\.blend\\(", "minShare", 0.5)));
        check(PerfPatterns.evidence(strict, mod) == null, "a share below minShare does not match");
        Map<String, Object> pattern = Json.map("id", "reuse-x", "symptom", symptom, "change", "c", "gain", Json.map("scope", "s",
            "cold", Json.map("geometricMean", 1.5, "low", 1.2, "high", 1.8), "warm", Json.map("geometricMean", 1.4, "low", 1.1, "high", 1.7)),
            "gate", Json.map("name", "g", "compare", "exact"), "provenIn", Json.map("mod", "m", "version", "1", "loader", "fabric", "scope", "s", "record", "r1"),
            "test", Json.map("fires", mod, "quiet", Json.map("id", "q", "samples", 100, "methods", List.of(Json.map("name", "a.Other.run()V", "samples", 100)))));
        check(PerfPatterns.validate(Json.map("schema", 1, "patterns", List.of(pattern)), Set.of("r1")).isEmpty(), "a complete pattern validates");
        check(PerfPatterns.validate(Json.map("schema", 1, "patterns", List.of(pattern)), Set.of()).stream().anyMatch(p -> p.contains("not an accepted run")),
            "a pattern must cite an accepted kept run");
        Map<String, Object> wrongTest = new LinkedHashMap<>(pattern);
        wrongTest.put("test", Json.map("fires", Json.map("id", "q", "samples", 1, "methods", List.of()), "quiet", mod));
        List<String> problems = PerfPatterns.validate(Json.map("schema", 1, "patterns", List.of(wrongTest)), Set.of("r1"));
        check(problems.stream().anyMatch(p -> p.contains("test.fires")) && problems.stream().anyMatch(p -> p.contains("test.quiet")), "a pattern's own test must fire and stay quiet");
        List<Object> matched = PerfPatterns.match(List.of(pattern), Json.map("mods", List.of(mod)));
        check(matched.size() == 1 && "reuse-x".equals(Json.object(matched.get(0)).get("pattern")), "a capture showing the symptom names the pattern");
        Path library = PerfPatterns.file(home);
        if (Files.isRegularFile(library)) {
            List<String> found = PerfPatterns.validate(Json.read(library), PerfPatterns.acceptedRecords(home));
            check(found.isEmpty(), "knowledge/perf-patterns.json: " + String.join("; ", found));
        }
    }

    static void optimizerGates() throws Exception {
        Map<String, Object> speed = PerfOptimize.speedup(List.of(2.0, 2.0, 2.0, 2.0), 1);
        check(Math.abs(((Number) speed.get("geometricMean")).doubleValue() - 2) < 1e-9 && ((Number) speed.get("low")).doubleValue() > 1.99,
            "identical paired ratios give that speedup with no spread");
        Map<String, Object> mixed = PerfOptimize.speedup(List.of(0.8, 1.3, 0.9, 1.2), 1);
        check(((Number) mixed.get("low")).doubleValue() < 1 && ((Number) mixed.get("high")).doubleValue() > 1, "noisy ratios give an interval spanning 1");
        Map<Integer, Map<String, Map<String, Object>>> pairs = new TreeMap<>();
        String[][] values = {{"a1", "a1", "7"}, {"a1", "a1", "7"}, {"a1", "a2", "7"}};
        for (int run = 0; run < 2; run++) {
            pairs.put(run, new HashMap<>());
            for (String arm : List.of("baseline", "candidate")) {
                Map<String, Object> gates = Json.map("same", "digest", "drift", run == 0 ? "x" : "y", "count", arm.equals("baseline") ? "[10, 20]" : "[10.5, 20]", "flag", "true");
                pairs.get(run).put(arm, Json.map("gates", gates));
            }
        }
        check(Boolean.TRUE.equals(PerfOptimize.gate(Json.map("name", "same", "compare", "exact"), pairs).get("passed")), "equal deterministic outputs pass");
        Map<String, Object> drift = PerfOptimize.gate(Json.map("name", "drift", "compare", "exact"), pairs);
        check(Boolean.FALSE.equals(drift.get("passed")) && Boolean.FALSE.equals(drift.get("baselineStable")),
            "an output that changes between baseline launches cannot certify equivalence");
        check(Boolean.TRUE.equals(PerfOptimize.gate(Json.map("name", "count", "compare", "tolerance", "tolerance", 0.1), pairs).get("passed")), "within tolerance passes");
        check(Boolean.FALSE.equals(PerfOptimize.gate(Json.map("name", "count", "compare", "tolerance", "tolerance", 0.01), pairs).get("passed")), "outside tolerance fails");
        check(Boolean.TRUE.equals(PerfOptimize.gate(Json.map("name", "flag", "compare", "expect", "expect", "true"), pairs).get("passed")), "behaviour assertion passes");
        Path dir = Files.createTempDirectory("perf-frames-");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "png", dir.resolve("a.png").toFile());
        image.setRGB(5, 5, 0x010000);
        javax.imageio.ImageIO.write(image, "png", dir.resolve("b.png").toFile());
        Map<String, Object> diff = PerfOptimize.pixels(dir.resolve("a.png"), dir.resolve("b.png"));
        check(((Number) diff.get("differentPixels")).longValue() == 1 && ((Number) diff.get("maxChannelDelta")).intValue() == 1, "one changed channel value is a visible difference");
        Map<String, Object> report = Json.map("seconds", 10.0, "recording", "r", "attribution", Json.map("samplePeriodMs", 10, "mods", List.of(Json.map("id", "m",
            "sampledCpuMsPerSecond", 30.0, "threads", List.of(Json.map("name", "m-baker-1", "samples", 20), Json.map("name", "m-import", "samples", 10)),
            "inclusiveMethods", List.of(Json.map("name", "a.Mesher.build(I)V", "samples", 12, "cpuMsPerSecond", 12.0))))));
        check(PerfOptimize.metric(report, Json.map("mod", "m")) == 30.0, "a mod metric is its whole sampled CPU");
        check(PerfOptimize.metric(report, Json.map("mod", "m", "threads", "^m-baker")) == 20.0, "a thread filter counts only matching threads");
        check(PerfOptimize.metric(report, Json.map("mod", "m", "method", "Mesher.build")) == 12.0, "a method scope is its inclusive CPU");
        boolean refused = false;
        try { PerfOptimize.validate(Json.map("schema", 1, "measure", Json.map("metric", Json.map("probe", "x")), "gates", List.of())); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "an optimization with no equivalence gate is refused");
    }

    static void functionTiming(Map<String, Object> report, Path directory) throws Exception {
        Map<String, Object> timing = Json.object(report.get("functionTiming"));
        List<Object> rows = Json.array(timing.get("methods"));
        Map<String, Object> outer = rows.stream().map(Json::object).filter(r -> r.get("name").equals("outer")).findFirst().orElseThrow();
        check(((Number) outer.get("inclusiveTotalMs")).doubleValue() > ((Number) outer.get("selfTotalMs")).doubleValue(),
            "nested sleeps are subtracted from parent self time");
        Map<String, Object> inner = rows.stream().map(Json::object).filter(r -> r.get("name").equals("inner")).findFirst().orElseThrow();
        check(((Number) Json.object(inner.get("inclusive")).get("mean")).doubleValue() >= 1,
            "function timing records real elapsed sleeps, not sampled CPU percentages");
        Map<String, Object> fails = rows.stream().map(Json::object).filter(r -> r.get("name").equals("fails")).findFirst().orElseThrow();
        check(fails.get("calls").toString().equals(fails.get("exceptionalCalls").toString()), "implicit propagated exceptions close every timing scope");
        check(((Number) outer.get("exceptionalCalls")).longValue() == 0, "caught child exceptions do not mark the parent exceptional");
        check(Json.array(timing.get("edges")).stream().map(Json::object).anyMatch(r -> r.get("name").equals("recursive → recursive")),
            "recursive calls retain their nearest timed caller");
        List<Object> trace = Json.array(Json.read(directory.resolve("functions.trace.json")).get("traceEvents"));
        check(trace.stream().map(Json::object).anyMatch(e -> e.get("name").equals("jdk.ThreadSleep")), "recorded blocking context accompanies function durations");
        List<Object> events = trace.stream().filter(e -> Json.object(e).get("cat").equals("instrumented-function")).toList();
        check(events.stream().map(Json::object).map(r -> r.get("tid")).distinct().count() >= 2, "timelines retain separate threads");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Object event : events) {
            Map<String, Object> e = Json.object(event);
            byId.put(Json.object(e.get("args")).get("callId").toString(), e);
        }
        boolean sameThread = true;
        for (Object event : events) {
            Map<String, Object> e = Json.object(event), args = Json.object(e.get("args"));
            Map<String, Object> parent = byId.get(args.get("parentId").toString());
            if (parent != null && !parent.get("tid").equals(e.get("tid"))) sameThread = false;
        }
        check(sameThread, "parent IDs never cross threads");
        check(!Boolean.TRUE.equals(timing.get("partial")), "small timing fixture retains complete emitted history");
        PerfFunctions truncated = new PerfFunctions();
        Properties status = new Properties();
        status.setProperty("timingCompleted", "100001");
        status.setProperty("timingDropped", "1");
        check(Boolean.TRUE.equals(truncated.finish(status).get("partial")), "dropped timing events must mark totals incomplete");
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
