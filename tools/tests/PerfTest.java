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
        optimizerStopping();
        experimentCheck();
        modTemplates();
        check(Run.running(ProcessHandle.current()), "current process is running");
        Properties kinds = new Properties();
        kinds.setProperty("m.BlockMain", "block");
        kinds.setProperty("m.TileMain", "block entity");
        String[] ticker = PerfAttribution.typed(List.of("m.Net.scan", "m.TileMain", "m.BlockMain$$Lambda.0x000001a8a9314fd0"), 2, 0, kinds);
        check(ticker != null && "block entity".equals(ticker[0]) && "TileMain".equals(ticker[1]),
            "a block's ticker lambda does not name the feature by its hidden class's address: " + (ticker == null ? null : String.join(" · ", ticker)));
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
        // The checked-in records, patterns and experiments belong to the checkout. From the source launcher the harness
        // is the checkout; the package's harness is its unpacked resources, so the checkout is self-test's --root, the
        // working directory. CI runs only the package's self-test, so reading the harness there checked none of them.
        Path checkout = Files.isDirectory(home.resolve("bench")) ? home : Path.of("").toAbsolutePath();
        runRecords(checkout);
        patternLibrary(checkout);
        if (Files.isDirectory(checkout.resolve("bench"))) committedExperiments(checkout);
        else System.out.println("Not a modaccel checkout: no committed experiments to check");
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
            childOutputEncoding(scratch, first);
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
        check(Json.array(report.getOrDefault("threadCpuTime", List.of())).stream().map(Json::object)
            .anyMatch(t -> ((Number) t.get("cpuMs")).doubleValue() > 0), "each thread's operating-system CPU time over the window is reported");
        List<Object> suggested = Json.array(Json.read(directory.resolve("probes.suggested.json")).get("probes"));
        check(suggested.stream().map(Json::object).anyMatch(p -> p.get("class").equals("com.example.mod.Handler") && p.get("descriptor").toString().startsWith("(")),
            "timing probes are suggested for sampled mod methods with exact descriptors");
        check(Files.readString(directory.resolve("report.html")).contains("Where the cost is"), "report ranks cost by mod and feature");
        Map<String, Object> outer = Json.array(attribution.get("unattributed")).stream().map(Json::object)
            .filter(r -> r.get("label").equals("outer")).findFirst().orElseThrow(() -> new AssertionError("timed game method listed as unattributed exact cost"));
        check(Boolean.TRUE.equals(outer.get("complete")) && ((Number) outer.get("calls")).longValue() > 0,
            "exact rows come from complete agent totals, not the capped per-call history");
        long perThread = 0;
        for (String thread : List.of("server", "render", "other")) {
            if (outer.get(thread + "Calls") instanceof Number n) perThread += n.longValue();
        }
        check(outer.get("threads") instanceof List<?> && perThread == ((Number) outer.get("calls")).longValue(),
            "each probe row splits its calls by game thread (server, render, other), and the parts add up: " + outer);
    }

    /**
     * A perf-optimize child prints a matched pattern's provenIn ("mod version · scope") and the parent reads it.
     * On Windows the console code page once turned "·" into byte 0xB7 and the whole run ended in MalformedInputException.
     * LC_ALL=C gives POSIX children an ASCII console the same way.
     */
    static void childOutputEncoding(Path scratch, Path capture) throws Exception {
        Path home = scratch.resolve("pattern-home");
        Files.createDirectories(home.resolve("knowledge"));
        Map<String, Object> pattern = Json.map("id", "façade-cache", "symptom", Json.map("text", "Façade work", "when",
            List.of(Json.map("in", "methods", "matches", ".", "minShare", 0))), "change", "réutiliser", "gain", Json.map(),
            "gate", Json.map("name", "g"), "provenIn", Json.map("mod", "Façade", "version", "1", "scope", "näive"));
        Json.save(home.resolve("knowledge/perf-patterns.json"), Json.map("schema", 1, "patterns", List.of(pattern)));
        List<String> command = PerfOptimize.childJava(home);
        command.addAll(List.of("perf-report", "--file", capture.toString(), "--out", scratch.resolve("pattern-report").toString(), "--json"));
        Path stdout = scratch.resolve("pattern-report.json"), stderr = scratch.resolve("pattern-report.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put("LC_ALL", "C");
        Process child = builder.start();
        check(child.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "perf-report child finishes");
        String text = PerfOptimize.childOutput(stdout).strip();
        Map<String, Object> answer = Json.object(Json.parse(text.substring(Math.max(0, text.lastIndexOf("\n{") + 1))));
        check(Boolean.TRUE.equals(answer.get("ok")), "the child's report: " + Run.tail(stderr));
        List<Object> patterns = Json.array(Json.object(answer.get("result")).get("patterns"));
        check(!patterns.isEmpty() && "Façade 1 · näive".equals(Json.object(patterns.get(0)).get("provenIn")),
            "a matched pattern's non-ASCII provenIn survives the child's console encoding: " + patterns);
        Map<String, Object> crashed = Json.map("accepted", false, "error", "java.nio.charset.MalformedInputException: Input length = 1");
        check("error".equals(PerfOptimize.verdict(crashed)) && PerfOptimize.summary(crashed).startsWith("ERROR"),
            "a tool crash is an error, never a candidate rejection: " + PerfOptimize.summary(crashed));
        check("rejected".equals(PerfOptimize.verdict(Json.map("accepted", false))), "a decided slower candidate is still a rejection");
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
    /**
     * Every experiment under bench/ names only files the checkout has; only its save is made once per machine. A file the
     * repository ignores (Pipez's options.txt was) passes on the machine that made it and fails on every other. Nested
     * experiments count too: Diorama's is bench/diorama/play, whose options.txt a one-level ignore exception missed.
     * Files copied out of the save (Diorama's frozen region files) are made with it, by its prepare step.
     */
    static void committedExperiments(Path home) throws Exception {
        int checked = 0;
        List<Path> files;
        try (var walk = Files.walk(home.resolve("bench"), 3)) {
            files = walk.filter(p -> p.getFileName().toString().equals("experiment.json")).sorted().toList();
        }
        for (Path file : files) {
            Map<String, Object> experiment = Json.read(file);
            Path save = file.getParent().resolve(Json.text(experiment, "worldSource", "-")).normalize();
            Map<String, Object> result = PerfCheck.check(file, experiment, null, null, null);
            List<String> problems = new ArrayList<>();
            for (Object item : Json.array(result.get("problems"))) {
                String problem = item.toString();
                if (problem.startsWith("worldSource ")) continue;
                if (problem.startsWith("files: ") && file.getParent().resolve(problem.substring(7, problem.indexOf(" (for "))).normalize().startsWith(save)) continue;
                problems.add(problem);
            }
            check(problems.isEmpty(), home.relativize(file) + " names files the checkout lacks: " + problems);
            checked++;
        }
        check(checked >= 4, "bench experiments are checked, nested ones too: " + checked);
    }

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
        report.put("threadCpuTime", List.of(Json.map("thread", "m-baker-1", "cpuMsPerSecond", 7.0, "cpuMs", 70.0),
            Json.map("thread", "m-baker-2", "cpuMsPerSecond", 5.0, "cpuMs", 50.0), Json.map("thread", "Render thread", "cpuMsPerSecond", 400.0, "cpuMs", 4000.0)));
        check(PerfOptimize.metric(report, Json.map("threadCpu", "^m-baker")) == 12.0, "a threadCpu metric sums the matching threads' CPU time");
        report.put("probeCalls", Json.map("mesh", 40L));
        check(PerfOptimize.metric(report, Json.map("threadCpu", "^m-baker", "perCall", "mesh")) == 3.0, "perCall divides the window's CPU time by a counted probe's calls");
        boolean none = false;
        try { PerfOptimize.metric(report, Json.map("threadCpu", "^nobody")); }
        catch (IllegalStateException expected) { none = true; }
        check(none, "a threadCpu metric that matches no thread is an error, not zero");
        boolean refused = false;
        try { PerfOptimize.validate(Json.map("schema", 1, "measure", Json.map("metric", Json.map("probe", "x")), "gates", List.of())); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "an optimization with no equivalence gate is refused");
    }

    /** A measured pair with these cold and warm values per arm, and gate outputs. */
    static List<Object> pair(int run, double base, double cand, String baseGate, String candGate) {
        return List.of(Json.map("run", run, "arm", "baseline", "captures", Json.map("cold", Json.map("value", base), "warm", Json.map("value", base)),
                "gates", Json.map("digest", baseGate)),
            Json.map("run", run, "arm", "candidate", "captures", Json.map("cold", Json.map("value", cand), "warm", Json.map("value", cand)),
                "gates", Json.map("digest", candGate)));
    }

    /** perf-optimize stops at the first failed gate, and once 99% t intervals decide; never before --min-runs for speed. */
    static void optimizerStopping() throws Exception {
        Map<String, Object> experiment = Json.map("gates", List.of(Json.map("name", "digest", "compare", "exact")));
        List<Object> measured = new ArrayList<>(pair(0, 2.0, 1.0, "a", "b"));
        check(PerfStopping.decide(experiment, measured, 3, 10, 1) == null,
            "one pair cannot tell a candidate's difference from a gate that differs between launches");
        measured.addAll(pair(1, 2.0, 1.0, "a", "b"));
        Map<String, Object> stop = PerfStopping.decide(experiment, measured, 3, 10, 1);
        check(PerfStopping.progress(experiment, measured, 60).endsWith("gates digest FAILED"), "a failed gate shows in the pair's line");
        check(stop != null && "rejected".equals(stop.get("decided")) && stop.get("reason").toString().contains("gate digest"),
            "an exact gate that fails with two baseline launches ends the run: " + stop);
        Map<String, Object> asserted = Json.map("gates", List.of(Json.map("name", "digest", "compare", "expect", "expect", "a")));
        Map<String, Object> first = PerfStopping.decide(asserted, new ArrayList<>(pair(0, 2.0, 1.0, "a", "b")), 3, 10, 1);
        check(first != null && "rejected".equals(first.get("decided")), "a failed expect gate ends the run at the first pair");
        measured = new ArrayList<>();
        double[][] fast = {{2.00, 1.00}, {2.02, 1.00}, {1.98, 1.00}};
        for (int i = 0; i < fast.length; i++) {
            measured.addAll(pair(i, fast[i][0], fast[i][1], "a", "a"));
            Map<String, Object> early = PerfStopping.decide(experiment, measured, 3, 10, 1);
            String line = PerfStopping.progress(experiment, measured, 90);
            check(line.startsWith("pair " + (i + 1) + " (1.5 min): cold ") && line.contains("digest " + (i == 0 ? "pending" : "ok"))
                && (i == 0) != line.contains("so far 2.0"), "each pair prints its speedups, the running mean and each gate: " + line);
            if (i < 2) check(early == null, "speed is not decided before --min-runs pairs");
            else check(early != null && "faster".equals(early.get("decided")), "a large, consistent speedup is decided at --min-runs: " + early);
        }
        measured = new ArrayList<>();
        double[] noisy = {1.3, 0.8, 1.2, 0.9, 1.1};
        for (int i = 0; i < noisy.length; i++) measured.addAll(pair(i, noisy[i], 1.0, "a", "a"));
        check(PerfStopping.decide(experiment, measured, 3, 10, 1) == null, "a noisy ratio around 1 keeps launching");
        check(PerfStopping.decide(experiment, measured.subList(0, 6), 3, 3, 1) == null, "the last pair is decided by the final rule, not the stopping rule");
        measured = new ArrayList<>();
        for (int i = 0; i < 4; i++) measured.addAll(pair(i, 1.0, 1.5 + 0.01 * i, "a", "a"));
        Map<String, Object> slower = PerfStopping.decide(experiment, measured, 3, 10, 1);
        check(slower != null && "rejected".equals(slower.get("decided")), "a clearly slower candidate stops: " + slower);
        measured.add(Json.map("run", 4, "arm", "baseline", "captures", Json.map("cold", Json.map("value", 1.0), "warm", Json.map("value", 1.0)), "gates", Json.map("digest", "a")));
        check(PerfStopping.pairs(measured).size() == 4, "an arm without its pair is not a pair");
        Map<String, Object> t = PerfStopping.interval(List.of(2.0, 2.0));
        check(Math.abs(((Number) t.get("low")).doubleValue() - 2) < 1e-9, "identical ratios have no spread");
        check(PerfStopping.t995(2) > PerfStopping.t995(10) && PerfStopping.t995(100) == PerfStopping.t995(30), "t quantiles shrink with pairs and stop at 30");
        PerfRecord record = new PerfRecord("perf-optimize", null);
        record.share = 0.5;
        record.step("measure", "baseline-cold", 60);
        record.share = 1;
        record.step("setup", "worktrees", 2);
        check(((Number) record.phases.get("measure")).doubleValue() == 30 && Json.object(record.steps.get(0)).get("seconds").equals(60.0),
            "parallel arms count half of each step in the phases, and keep its elapsed seconds");
        Path experimentDir = Files.createTempDirectory("perf-snapshot-");
        Files.writeString(experimentDir.resolve("trace.java"), "return \"one\";");
        Files.writeString(experimentDir.resolve("options.txt"), "pauseOnLostFocus:false");
        Map<String, Object> snapshotted = Json.map("measure", Json.map("metric", Json.map("probe", "p")),
            "gates", List.of(Json.map("name", "transfers", "file", "trace.java")), "files", Json.map("run/options.txt", "options.txt"));
        Map<String, Path> copies = PerfOptimize.snapshot(snapshotted, experimentDir, experimentDir.resolve("out/experiment"));
        Files.writeString(experimentDir.resolve("trace.java"), "return \"two\";");
        check(copies.size() == 2 && Files.readString(copies.get("trace.java")).contains("one") && copies.get("options.txt").startsWith(experimentDir.resolve("out")),
            "a run reads the gate snippets and files as they were when it started: " + copies);
        check(PerfOptimize.asked(experimentDir, 2) == null, "a run goes on unless asked to stop");
        Files.writeString(experimentDir.resolve("stop"), "");
        Map<String, Object> asked = PerfOptimize.asked(experimentDir, 2);
        check(asked != null && "asked".equals(asked.get("decided")) && asked.get("afterPairs").equals(2), "OUT/stop ends a run after the pair in progress: " + asked);
        check(PerfOptimize.gitCommand(Path.of("."), "worktree", "add").containsAll(List.of("-c", "core.longpaths=true")),
            "worktrees are made with long paths allowed, as OUT lengthens every path in them");
        Path cp1252 = Files.createTempFile("perf-child-", ".json");
        Files.write(cp1252, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xb7, '"', '}'});
        check(PerfOptimize.childOutput(cp1252).contains("�") && PerfOptimize.childJava(Path.of(".")).contains("-Dstdout.encoding=UTF-8"),
            "a child's answer is read as UTF-8 without failing on a stray byte, and the child is told to write UTF-8");
        Files.delete(cp1252);
        redirectedOutputIsUtf8();
        boolean refused = false;
        try { PerfOptimize.parallel("both"); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused && PerfOptimize.parallel("parallel") && !PerfOptimize.parallel("sequential"), "--arms is auto, parallel or sequential");
    }

    /** What perf-optimize refuses before any launch. */
    static void experimentCheck() throws Exception {
        Path dir = Files.createTempDirectory("perf-check-");
        fixture(dir, "gate.java", "// " + PerfCheck.TEMPLATE_MARK + "\nreturn \"\";\n");
        fixture(dir, "clock.java", "return String.valueOf(System.currentTimeMillis());\n");
        fixture(dir, "probes.json", "{\"probes\":[{\"class\":\"a.B\",\"method\":\"tick\",\"descriptor\":\"()V\",\"label\":\"tick\",\"mode\":\"timing\"}]}");
        Map<String, Object> experiment = Json.map("schema", 1, "world", "w", "worldSource", "missing",
            "measure", Json.map("probes", "probes.json", "metric", Json.map("report", "metrics.frame_ms.mean"),
                "scopes", List.of(Json.map("name", "s", "probe", "absent"))),
            "gates", List.of(Json.map("name", "template", "file", "gate.java", "compare", "exact"), Json.map("name", "clock", "file", "clock.java", "compare", "exact"),
                Json.map("name", "gone", "file", "gone.java", "compare", "expect", "expect", "true")));
        Map<String, Object> result = PerfCheck.check(dir.resolve("experiment.json"), experiment, null, null, Json.map("background", true));
        String problems = String.join("\n", Json.array(result.get("problems")).stream().map(Object::toString).toList());
        String warnings = String.join("\n", Json.array(result.get("warnings")).stream().map(Object::toString).toList());
        check(Boolean.FALSE.equals(result.get("ok")), "a broken experiment does not pass");
        check(problems.contains("still the generated template"), "an unwritten template gate is refused: " + problems);
        check(problems.contains("gone.java does not exist"), "a missing snippet is refused");
        check(problems.contains("level.dat"), "a missing world is refused");
        check(problems.contains("probe absent"), "a scope naming a probe the file lacks is refused");
        check(problems.contains("8 frames per second"), "a frame metric on a hidden client is refused");
        check(warnings.contains("currentTimeMillis") && warnings.contains("C1"), "clock reads in exact gates and the C1-only JIT are warned about: " + warnings);
        Map<String, Object> visible = PerfCheck.check(dir.resolve("experiment.json"), experiment, null, null, Json.map("background", false));
        check(!Json.array(visible.get("problems")).toString().contains("frames per second"), "a visible client may measure frames");
        Path capture = dir.resolve("capture");
        fixture(capture, "report.json", Json.write(Json.map("attribution", Json.map("mods", List.of(Json.map("id", "pipes", "yourMod", true,
            "threads", List.of(Json.map("name", "Server thread", "samples", 20))))))));
        fixture(capture, "probes.suggested.json", Json.write(Json.map("probes", List.of(
            Json.map("class", "p.Pipe", "method", "tick", "descriptor", "()V", "label", "Pipe.tick", "mode", "timing", "mod", "pipes"),
            Json.map("class", "p.Pipe", "method", "sort", "descriptor", "()V", "label", "Pipe.sort", "mode", "timing", "mod", "pipes"),
            Json.map("class", "o.Other", "method", "x", "descriptor", "()V", "label", "Other.x", "mode", "timing", "mod", "other")))));
        Map<String, Object> made = PerfTemplates.run(new Modaccel.Options(new String[]{"perf-template", "--family", "experiment", "--from", capture.toString(),
            "--root", dir.toString(), "--out", dir.resolve("exp").toString()}));
        Map<String, Object> generated = Json.read(dir.resolve("exp/experiment.json"));
        Map<String, Object> metric = Json.object(Json.object(generated.get("measure")).get("metric"));
        check("Pipe.tick".equals(metric.get("probe")) && "full".equals(generated.get("jit")), "server-thread cost is measured by the top probe per tick, with the full JIT: " + made);
        check(Json.array(Json.read(dir.resolve("exp/probes.json")).get("probes")).size() == 2, "only the mod's own suggested probes are kept");
        Map<String, Object> fresh = PerfCheck.check(dir.resolve("exp/experiment.json"), generated, null, null, Json.map("background", true));
        check(Json.array(fresh.get("problems")).toString().contains("still the generated template"), "a generated experiment cannot launch until its gates are written");
        check(Json.array(generated.get("gates")).toString().contains("chunks.java") && Files.readString(dir.resolve("exp/chunks.java")).contains("getTicketDebugString"),
            "the generated experiment has a gate comparing loaded chunks and their tickets between the arms");
        check("serverInclusiveMsPerTick".equals(metric.get("statistic")) && made.get("details").toString().contains("call rates unknown"),
            "the probe metric counts server-thread calls only, and a capture without call counts says so: " + made);
        // A block entity that also ticks on the client (more Render than Server samples), a tiny method timed at
        // 2,000 calls a tick: the metric stays on the server thread and the tiny method gets no probe.
        Path farm = dir.resolve("farm");
        fixture(farm, "report.json", Json.write(Json.map("metrics", Json.map("tick_ms", Json.map("n", 100)),
            "attribution", Json.map("mods", List.of(Json.map("id", "farms", "yourMod", true,
                "threads", List.of(Json.map("name", "Render thread", "samples", 35), Json.map("name", "Server thread", "samples", 31)),
                "features", List.of(Json.map("name", "block entity · Farm", "threads",
                    List.of(Json.map("name", "Render thread", "samples", 33), Json.map("name", "Server thread", "samples", 28)))),
                "exact", List.of(Json.map("label", "Farm.tick", "callsPerTick", 128.0), Json.map("label", "Villager.getAge", "callsPerTick", 2048.0))))))));
        fixture(farm, "probes.suggested.json", Json.write(Json.map("probes", List.of(
            Json.map("class", "f.Farm", "method", "tick", "descriptor", "()V", "label", "Farm.tick", "mode", "timing", "mod", "farms"),
            Json.map("class", "f.Villager", "method", "getAge", "descriptor", "()I", "label", "Villager.getAge", "mode", "timing", "mod", "farms")))));
        Map<String, Object> farmMade = PerfTemplates.run(new Modaccel.Options(new String[]{"perf-template", "--family", "experiment", "--from", farm.toString(),
            "--root", dir.toString(), "--out", dir.resolve("farm-exp").toString()}));
        Map<String, Object> farmMetric = Json.object(Json.object(Json.read(dir.resolve("farm-exp/experiment.json")).get("measure")).get("metric"));
        List<Object> farmProbes = Json.array(Json.read(dir.resolve("farm-exp/probes.json")).get("probes"));
        String farmWarnings = Json.array(farmMade.get("warnings")).toString();
        check("Farm.tick".equals(farmMetric.get("probe")) && "serverInclusiveMsPerTick".equals(farmMetric.get("statistic")),
            "a block entity ticking on both sides is measured on the server thread: " + farmMetric);
        check(farmProbes.size() == 1 && farmWarnings.contains("Villager.getAge") && farmWarnings.contains("Render thread"),
            "a method above --max-calls-per-tick gets no probe, and the client-side ticking is warned about: " + farmWarnings);
        Map<String, Object> split = Json.map("attribution", Json.map("mods", List.of(Json.map("id", "farms", "exact", List.of(
            Json.map("label", "Farm.tick", "inclusiveMsPerTick", 3.0, "serverInclusiveMsPerTick", 1.0, "threads", List.of("render", "server")),
            Json.map("label", "Client.only", "inclusiveMsPerTick", 2.0, "renderInclusiveMsPerTick", 2.0, "threads", List.of("render")))))));
        check(PerfOptimize.metric(split, Json.map("probe", "Farm.tick", "statistic", "serverInclusiveMsPerTick")) == 1.0
            && PerfOptimize.metric(split, Json.map("probe", "Client.only", "statistic", "serverInclusiveMsPerTick")) == 0.0,
            "a per-thread probe statistic reads that thread's calls, and none on it is zero");
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

    /** modaccel run with its output redirected (no -Dstdout.encoding) writes UTF-8, whatever the platform charset. */
    static void redirectedOutputIsUtf8() throws Exception {
        Path scratch = Files.createTempDirectory("perf-utf8-");
        Path source = scratch.resolve("Utf8Probe.java");
        Files.writeString(source, "public class Utf8Probe { public static void main(String[] args) { Modaccel.utf8WhenRedirected(); "
            + "System.out.print(\"95% 9.94\\u201311.33; 3.97 \\u2192 0.38\"); System.out.flush(); } }");
        Path out = scratch.resolve("out.txt");
        String classes = Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", classes, source.toString())
            .redirectErrorStream(true).redirectOutput(out.toFile()).start();
        boolean done = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        String text = new String(Files.readAllBytes(out), java.nio.charset.StandardCharsets.UTF_8);
        check(done && process.exitValue() == 0 && text.equals("95% 9.94\u201311.33; 3.97 \u2192 0.38"),
            "redirected modaccel output is UTF-8, so a log or the desktop app reads its dashes and arrows: " + text);
        Files.walk(scratch).sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }

}
