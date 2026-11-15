import java.nio.file.*;
import java.util.*;

/** Reusable Minecraft command fixtures. Generating a file never edits a world. */
final class PerfTemplates {
    /** Game, JVM and pool threads that run other work besides a mod's: a mod's own threads are the rest. */
    static final java.util.regex.Pattern SHARED_THREAD = java.util.regex.Pattern.compile(
        "^(Render thread|Server thread|main|Worker-Main|IO-Worker|Netty|ForkJoinPool|pool-|Download|Timer|Signal|Reference Handler|Finalizer|Common-Cleaner|JFR|Attach)");

    static Map<String, Object> run(Modaccel.Options options) throws Exception {
        String family = options.get("family", "transport");
        if (family.equals("experiment")) return experiment(options);
        List<Object> cases = new ArrayList<>();
        if (family.equals("transport")) {
            for (int length : List.of(1, 8, 32)) for (int stack : List.of(1, 4, 16)) cases.add(transport(length, stack));
        } else if (family.equals("block-scene")) {
            String block = options.get("block", "minecraft:stone");
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?")) throw new IllegalArgumentException("Invalid namespaced block state");
            for (int width : List.of(1, 4, 8, 16)) cases.add(scene(width, block));
        } else if (family.equals("entity-crowd")) {
            String entity = id(options.get("entity", ""), "--entity");
            for (int count : counts(options.get("counts", "1,16,64,256"))) cases.add(crowd(entity, count));
        } else if (family.equals("mod-content")) {
            Map<String, List<String>> content = content(Path.of(options.get("root", ".")).toAbsolutePath());
            for (String block : content.get("block")) cases.add(scene(8, block));
            for (String entity : content.get("entity")) for (int count : counts(options.get("counts", "16,64"))) cases.add(crowd(entity, count));
            if (cases.isEmpty()) throw new IllegalArgumentException("No blocks or entities of the mod were found in its lang files; use --family entity-crowd --entity ID or block-scene --block ID");
        } else throw new IllegalArgumentException("--family must be transport, block-scene, entity-crowd, mod-content or experiment");
        Map<String, Object> template = Json.map("schema", 1, "name", family,
            "requirements", family.equals("transport")
                ? "Minecraft 1.21.1 command syntax; disposable overworld; local RCON. Use duration >=20 and warmup 0. Fixed total: 16 stone items."
                : family.equals("block-scene")
                ? "Minecraft 1.21.1 command syntax; disposable local server; one connected client; attach profiler to that client. Camera, game mode and blocks will change."
                : "Minecraft 1.20.5+ command syntax; a disposable world: perf --root SOURCE runs it in the source client's own world, or a local server with RCON. "
                    + "Entities stay inside a barrier pen and are removed afterwards; block cases change camera and game mode.",
            "region", "Overworld x=0..33, y=63..66, z=0..16; chunks temporarily force-loaded. Never run in a valuable world.",
            "cases", cases);
        PerfScenario.validate(template);
        Path out = Performance.output(options);
        Path file = out.resolve(family + ".json");
        if (Files.exists(file)) throw new IllegalArgumentException("Template already exists: " + file);
        Json.save(file, template);
        return Json.map("ok", true, "scenario", file.toString(), "details", template.get("requirements"),
            "warning", template.get("region"));
    }

    /**
     * A perf-optimize experiment for the mod a capture ranked, from that capture: the metric where the mod's cost
     * is (a timing probe's ms per tick when the server thread carries it, else the mod's sampled CPU on its busiest
     * threads), scopes for its next hottest methods, timing probes from the capture's suggestions, the full JIT, and
     * gate snippets to write. The gates hold PerfCheck's template mark, so perf-optimize refuses to launch until
     * they return something about the mod. A method the capture timed or counted at more than --max-calls-per-tick
     * calls a tick gets no probe (its overhead would outweigh it); a capture without call counts gets a warning. The
     * metric names its thread: a block entity that also ticks on the client would otherwise add its Render thread
     * calls to a server metric. Without --out the draft goes to .runs/ID-experiment, not bench/.
     */
    static Map<String, Object> experiment(Modaccel.Options options) throws Exception {
        Path capture = Path.of(options.get("from", "")).toAbsolutePath();
        if (!Files.isRegularFile(capture.resolve("report.json"))) throw new IllegalArgumentException("--from CAPTURE needs a perf capture folder with report.json");
        Map<String, Object> report = Json.read(capture.resolve("report.json"));
        String named = options.get("mod-package", "");
        Map<String, Object> mod = null;
        for (Object item : Json.array(Json.object(report.get("attribution")).get("mods"))) {
            Map<String, Object> row = Json.object(item);
            boolean chosen = named.isBlank() ? Boolean.TRUE.equals(row.get("yourMod")) : named.equals(row.get("id"));
            if (chosen && mod == null) mod = row;
        }
        if (mod == null) throw new IllegalArgumentException("The capture marks no mod as yours; capture with perf --root SOURCE, or name its id with --mod-package ID");
        String id = mod.get("id").toString();
        List<String> warnings = new ArrayList<>();
        double maxCalls = Double.parseDouble(options.get("max-calls-per-tick", "256"));
        if (!(maxCalls > 0)) throw new IllegalArgumentException("--max-calls-per-tick must be positive");
        Map<String, Double> rates = callsPerTick(report);
        List<Object> probes = new ArrayList<>();
        List<String> uncounted = new ArrayList<>();
        if (Files.isRegularFile(capture.resolve("probes.suggested.json"))) {
            for (Object item : Json.array(Json.read(capture.resolve("probes.suggested.json")).get("probes"))) {
                Map<String, Object> probe = Json.object(item);
                if (!id.equals(probe.get("mod")) || probes.size() >= 6) continue;
                String label = probe.get("label").toString();
                Double calls = rates.get(label);
                if (calls != null && calls > maxCalls) {
                    warnings.add("no probe on " + label + ": " + Math.round(calls) + " calls a tick, over --max-calls-per-tick " + Math.round(maxCalls)
                        + "; a timed call costs about a microsecond, so time its caller");
                    continue;
                }
                if (calls == null) uncounted.add(label);
                probes.add(Json.map("class", probe.get("class"), "method", probe.get("method"), "descriptor", probe.get("descriptor"),
                    "label", label, "mode", "timing"));
            }
        }
        if (!uncounted.isEmpty()) {
            warnings.add("call rates unknown for " + String.join(", ", uncounted) + ": capture again with perf --probes "
                + capture.resolve("probes.suggested.json") + " and pass that capture to --from, so methods called more than "
                + Math.round(maxCalls) + " times a tick get no probe");
        }
        List<Object> threads = Json.array(mod.getOrDefault("threads", List.of()));
        long total = 0;
        long server = 0;
        long render = 0;
        for (Object t : threads) {
            Map<String, Object> thread = Json.object(t);
            long samples = ((Number) thread.get("samples")).longValue();
            total += samples;
            if (thread.get("name").equals("Server thread")) server = samples;
            if (thread.get("name").equals("Render thread")) render = samples;
        }
        for (Object f : Json.array(mod.getOrDefault("features", List.of()))) {
            Map<String, Object> feature = Json.object(f);
            Set<String> on = new TreeSet<>();
            for (Object t : Json.array(feature.getOrDefault("threads", List.of()))) on.add(Json.object(t).get("name").toString());
            if (on.contains("Server thread") && on.contains("Render thread")) {
                warnings.add(feature.get("name") + " runs on the Server thread and the Render thread (it also ticks on the client); the metric counts one of them");
            }
        }
        Map<String, Object> metric;
        List<Object> scopes = new ArrayList<>();
        if (server > 0 && server * 5 >= total && !probes.isEmpty()) {
            // Server work is the same on a hidden client; an exact probe is far less noisy than a few sampled ticks.
            // Only Server thread calls count: the same method on the Render thread is the client's copy of the block entity.
            metric = Json.map("probe", Json.object(probes.get(0)).get("label"), "statistic", "serverInclusiveMsPerTick");
            scopes.add(Json.map("name", id + "ServerCpu", "mod", id, "threads", "^Server thread$"));
            if (render > 0) scopes.add(Json.map("name", id + "RenderCpu", "mod", id, "threads", "^Render thread$"));
        } else {
            Set<String> names = new LinkedHashSet<>();
            for (Object t : threads.subList(0, Math.min(3, threads.size()))) {
                String name = Json.object(t).get("name").toString();
                // Server work belongs in a timing probe's server statistic, never mixed into client CPU.
                if (name.equals("Server thread")) continue;
                names.add(name.replaceAll("[^A-Za-z0-9 _-]", ".").replaceAll("[0-9]+", "\\\\d+"));
            }
            metric = names.isEmpty() ? Json.map("mod", id) : Json.map("mod", id, "threads", "^(" + String.join("|", names) + ")$");
            if (server > 0) warnings.add("the metric leaves out the Server thread (" + server + " of " + total + " samples); time server work with a probe");
            // Threads the mod started run only its work: their operating-system CPU time is exact where samples
            // also count a preempted thread's wait. Shared game and pool threads stay sampled, as a scope.
            Set<String> own = new LinkedHashSet<>();
            for (String name : names) if (!SHARED_THREAD.matcher(name).find()) own.add(name);
            if (!own.isEmpty()) {
                scopes.add(Json.map("name", id + "SampledCpu", "mod", id, "threads", metric.getOrDefault("threads", ".")));
                metric = Json.map("threadCpu", "^(" + String.join("|", own) + ")$");
            }
        }
        for (Object item : probes.subList(Math.min(1, probes.size()), probes.size())) {
            String label = Json.object(item).get("label").toString();
            scopes.add(Json.map("name", label, "probe", label, "statistic", "meanInclusiveMs"));
        }
        Path root = Path.of(options.get("root", ".")).toAbsolutePath();
        String game = Files.isDirectory(root.resolve("runs/client")) ? "runs/client" : "run";
        Map<String, Object> experiment = Json.map("schema", 1, "gameDirectory", game, "world", "perf", "worldSource", "world/perf",
            "files", Json.map(game + "/options.txt", "options.txt"),
            "setup", List.of(),
            "measure", Json.map("seconds", 30, "warmup", 20, "probes", "probes.json", "metric", metric, "scopes", scopes),
            "gates", List.of(Json.map("name", "behaviour", "file", "behaviour.java", "thread", "control", "compare", "expect", "expect", "true"),
                Json.map("name", "digest", "file", "digest.java", "thread", "control", "compare", "exact"),
                Json.map("name", "chunks", "file", "chunks.java", "thread", "control", "compare", "exact")),
            "jit", "full");
        if (!options.get("project", "").isBlank()) experiment.put("project", options.get("project", ""));
        PerfOptimize.validate(experiment);
        // A draft lives in .runs/ until perf-optimize accepts a change; then it is copied into bench/<mod>/.
        Path out = options.values.containsKey("out") ? Performance.output(options) : Path.of(".runs", id + "-experiment").toAbsolutePath().normalize();
        Files.createDirectories(out);
        if (out.toString().replace('\\', '/').contains("/bench/")) {
            warnings.add("drafts belong in .runs/: copy the experiment into bench/<mod>/ once perf-optimize accepts a change");
        }
        if (Files.exists(out.resolve("experiment.json"))) throw new IllegalArgumentException("Experiment already exists: " + out.resolve("experiment.json"));
        Json.save(out.resolve("experiment.json"), experiment);
        Json.save(out.resolve("probes.json"), Json.map("probes", probes));
        Files.writeString(out.resolve("options.txt"), "pauseOnLostFocus:false\ntutorialStep:none\n");
        Files.writeString(out.resolve("behaviour.java"), "// " + PerfCheck.TEMPLATE_MARK + ": delete this line once the snippet checks the mod.\n"
            + "// Behaviour gate (expect \"true\"): after both captures, assert the workload did what it should, so a candidate\n"
            + "// cannot win by doing less (items conserved, the feature still ran, nothing left waiting).\n"
            + "return \"true\";\n");
        String hot = String.join(", ", probes.stream().map(p -> Json.object(p).get("label").toString()).toList());
        Files.writeString(out.resolve("digest.java"), "// " + PerfCheck.TEMPLATE_MARK + ": delete this line once the snippet hashes the changed code's output.\n"
            + "// Exact gate: run the code the candidate changes on frozen inputs (an area far from the player, ticked by hand,\n"
            + "// a fixed game time, no randomness) and return a hash of all it produced. Two baseline launches must agree.\n"
            + "// Hot methods in the capture: " + hot + "\n"
            + "return \"\";\n");
        Files.writeString(out.resolve("chunks.java"), "// " + PerfCheck.TEMPLATE_MARK + ": delete this line once AREA and KEEP name your scene's chunks.\n" + CHUNKS_GATE);
        String details = "Wrote " + out.resolve("experiment.json") + " for " + id + ": metric " + Json.write(metric) + ", " + probes.size()
            + " timing probes, " + scopes.size() + " scopes, full JIT.\nNext: make the world once and copy it to " + out.resolve("world/perf")
            + " (or change world/worldSource), write behaviour.java and digest.java, then run perf-optimize --check-only.";
        for (String warning : warnings) details += "\nwarning: " + warning;
        return Json.map("ok", true, "experiment", out.resolve("experiment.json").toString(), "mod", id, "metric", metric,
            "warnings", warnings, "details", details);
    }

    /**
     * An exact gate comparing what each arm keeps loaded: chunks and their tickets. Vanilla reads a chunk with
     * ServerChunkCache.getChunk(x, z, FULL, true), which renews a one-tick "unknown" ticket, so code that looks
     * something up every tick keeps that chunk loaded; a cache that skips the lookup lets it unload. Mojang names.
     */
    static final String CHUNKS_GATE = """
        // Exact gate: which chunks the mod keeps loaded, and with which tickets. Level.getBlockState, getBlockEntity and
        // capability lookups read a chunk with ServerChunkCache.getChunk(x, z, FULL, true), which renews a one-tick
        // "unknown" ticket on it, so code that looks something up every tick keeps that chunk loaded. A candidate that
        // caches the lookup skips the read, the ticket lapses and the chunk unloads: what players see can change (a storage
        // network shows fewer items). This forces only KEEP (the chunk of the block that ticks, e.g. a controller), releases
        // the rest of AREA, waits 3 s, then polls 40 times, 100 ms apart, between server ticks, whether each chunk of AREA is
        // loaded at all, none or some polls, and which ticket types and levels it carries (never their tick stamps).
        // Pick AREA so the scene's far end lies beyond 2 chunks of KEEP: nearer chunks stay loaded by KEEP's own ticket.
        var server = mc.getSingleplayerServer();
        // AREA: chunk x0, z0, x1, z1 covering the scene and one chunk around it (at most 256 chunks, forceload's limit).
        // KEEP: the one chunk to keep forced.
        int[] area = {0, 0, 0, 0};
        int[] keep = {0, 0};
        var source = server.createCommandSourceStack().withSuppressedOutput();
        java.util.function.Consumer<String> run = command -> server.submit(() -> server.getCommands().performPrefixedCommand(source, command)).join();
        java.lang.reflect.Method distance = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("getDistanceManager");
        distance.setAccessible(true);
        java.lang.reflect.Method debug = net.minecraft.server.level.DistanceManager.class.getDeclaredMethod("getTicketDebugString", long.class);
        debug.setAccessible(true);
        int width = area[2] - area[0] + 1;
        int depth = area[3] - area[1] + 1;
        int[] loaded = new int[width * depth];
        java.util.List<java.util.TreeSet<String>> tickets = new java.util.ArrayList<>();
        for (int i = 0; i < width * depth; i++) {
            tickets.add(new java.util.TreeSet<>());
        }
        String forced = (area[0] * 16) + " " + (area[1] * 16) + " " + (area[2] * 16 + 15) + " " + (area[3] * 16 + 15);
        StringBuilder out = new StringBuilder();
        try {
            run.accept("forceload add " + forced);
            Thread.sleep(3000);
            run.accept("forceload remove " + forced);
            run.accept("forceload add " + (keep[0] * 16) + " " + (keep[1] * 16));
            Thread.sleep(3000);
            for (int poll = 0; poll < 40; poll++) {
                Thread.sleep(100);
                server.submit(() -> {
                    var level = server.overworld();
                    for (int x = area[0]; x <= area[2]; x++) {
                        for (int z = area[1]; z <= area[3]; z++) {
                            int i = (x - area[0]) * depth + (z - area[1]);
                            if (level.getChunkSource().hasChunk(x, z)) {
                                loaded[i]++;
                            }
                            try {
                                String held = String.valueOf(debug.invoke(distance.invoke(level.getChunkSource().chunkMap), net.minecraft.world.level.ChunkPos.asLong(x, z)));
                                for (String ticket : held.split("Ticket")) {
                                    if (ticket.startsWith("[")) {
                                        tickets.get(i).add(ticket.substring(1, ticket.indexOf(" (")));
                                    }
                                }
                            } catch (ReflectiveOperationException e) {
                                tickets.get(i).add("unreadable");
                            }
                        }
                    }
                }).join();
            }
            for (int x = area[0]; x <= area[2]; x++) {
                for (int z = area[1]; z <= area[3]; z++) {
                    int i = (x - area[0]) * depth + (z - area[1]);
                    String seen = loaded[i] == 40 ? "all" : loaded[i] == 0 ? "none" : "some";
                    out.append(x).append(',').append(z).append('=').append(seen).append(tickets.get(i)).append(' ');
                }
            }
        } finally {
            run.accept("forceload remove " + forced);
            run.accept("forceload remove " + (keep[0] * 16) + " " + (keep[1] * 16));
        }
        return out.toString().trim();
        """;

    /** Calls per server tick of each probe label the capture timed or counted; empty when it had no probes. */
    static Map<String, Double> callsPerTick(Map<String, Object> report) {
        Map<String, Double> rates = new HashMap<>();
        Map<String, Object> attribution = Json.object(report.getOrDefault("attribution", Map.of()));
        List<Object> rows = new ArrayList<>(Json.array(attribution.getOrDefault("unattributed", List.of())));
        for (Object mod : Json.array(attribution.getOrDefault("mods", List.of()))) {
            rows.addAll(Json.array(Json.object(mod).getOrDefault("exact", List.of())));
        }
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            if (row.get("callsPerTick") instanceof Number n) rates.put(row.get("label").toString(), n.doubleValue());
        }
        double ticks = 0;
        if (report.get("metrics") instanceof Map<?, ?> metrics && metrics.get("tick_ms") instanceof Map<?, ?> tick && tick.get("n") instanceof Number n) {
            ticks = n.doubleValue();
        }
        if (ticks > 0 && report.get("probeCalls") instanceof Map<?, ?> counted) {
            for (var entry : counted.entrySet()) {
                if (entry.getValue() instanceof Number n) rates.putIfAbsent(entry.getKey().toString(), n.doubleValue() / ticks);
            }
        }
        return rates;
    }

    static String id(String value, String option) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException(option + " needs a namespaced id such as mymod:thing");
        return value;
    }

    static List<Integer> counts(String text) {
        List<Integer> counts = new ArrayList<>();
        for (String part : text.split(",")) {
            int count = Integer.parseInt(part.strip());
            if (count < 1 || count > 1024) throw new IllegalArgumentException("--counts values must be 1..1024");
            counts.add(count);
        }
        return counts;
    }

    /** The mod's own blocks and entities, from the translation keys its lang files define. */
    static Map<String, List<String>> content(Path root) throws Exception {
        Set<String> blocks = new TreeSet<>(), entities = new TreeSet<>();
        Path src = root.resolve("src");
        if (Files.isDirectory(src)) try (var walk = Files.walk(src, 8)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().equals("en_us.json") && p.getParent().getFileName().toString().equals("lang")).toList()) {
                for (String key : Json.object(Json.parse(Files.readString(file))).keySet()) {
                    String[] parts = key.split("\\.");
                    if (parts.length != 3 || parts[1].equals("minecraft") || !(parts[1] + ":" + parts[2]).matches("[a-z0-9_.-]+:[a-z0-9_/-]+")) continue;
                    if (parts[0].equals("block")) blocks.add(parts[1] + ":" + parts[2]);
                    if (parts[0].equals("entity")) entities.add(parts[1] + ":" + parts[2]);
                }
            }
        }
        return Map.of("block", blocks.stream().limit(16).toList(), "entity", entities.stream().limit(16).toList());
    }

    /** count entities on a grid inside a barrier pen, tagged so assertions and cleanup touch only them. */
    static Map<String, Object> crowd(String entity, int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        List<Object> setup = new ArrayList<>();
        Map<String, Object> load = command("forceload add 0 0 32 32", "force load");
        load.put("waitMs", 1000);
        setup.add(load);
        setup.add(Json.map("command", "fill -1 63 -1 33 70 33 minecraft:barrier hollow", "expectAny", List.of("Successfully filled", "No blocks were filled")));
        setup.add(Json.map("command", "fill 0 64 0 32 69 32 minecraft:air", "expectAny", List.of("Successfully filled", "No blocks were filled")));
        double spacing = side > 1 ? 30.0 / (side - 1) : 0;
        for (int i = 0; i < count; i++) {
            double x = 1 + (i % side) * spacing;
            double z = 1 + (i / side) * spacing;
            setup.add(command(String.format(Locale.ROOT, "summon %s %.2f 64 %.2f {Tags:[\"modaccel_perf\"],PersistenceRequired:1b}", entity, x, z), "Summoned"));
        }
        Map<String, Object> present = command("execute if entity @e[tag=modaccel_perf]", "Test passed");
        return Json.map("id", entity.replace(':', '-') + "-" + count,
            "parameters", Json.map("entity", entity, "instances", count, "pen", "barrier box x=-1..33 y=63..70 z=-1..33"),
            "setup", setup, "before", List.of(present), "after", List.of(present),
            "cleanup", List.of(Json.map("command", "kill @e[tag=modaccel_perf]", "expectAny", List.of("Killed", "No entity was found")),
                Json.map("command", "fill -1 63 -1 33 70 33 minecraft:air", "expectAny", List.of("Successfully filled", "No blocks were filled")),
                command("forceload remove 0 0 32 32", "force load")));
    }

    static Map<String, Object> command(String text, String expected) {
        return Json.map("command", text, "expect", expected);
    }

    static Map<String, Object> transport(int length, int stack) {
        List<Object> setup = new ArrayList<>();
        Map<String, Object> load = command("forceload add 0 0 32 0", "force load");
        load.put("waitMs", 1000);
        setup.add(load);
        setup.add(command("fill 0 63 0 33 65 0 minecraft:stone", "Successfully filled"));
        setup.add(command("fill 0 63 0 33 65 0 minecraft:air", "Successfully filled"));
        setup.add(command("setblock 0 63 0 minecraft:redstone_block", "Changed the block"));
        for (int x = 0; x < length; x++) setup.add(command("setblock " + x + " 64 0 minecraft:hopper[facing=east]", "Changed the block"));
        setup.add(command("setblock 0 65 0 minecraft:chest", "Changed the block"));
        setup.add(command("setblock " + length + " 64 0 minecraft:chest", "Changed the block"));
        for (int slot = 0; slot < 16 / stack; slot++) {
            setup.add(command("item replace block 0 65 0 container." + slot + " with minecraft:stone " + stack, "Replaced"));
        }
        List<Object> after = new ArrayList<>();
        after.add(command("execute if data block " + length + " 64 0 Items[{id:\"minecraft:stone\",count:16}]", "Test passed"));
        after.add(command("execute unless data block 0 65 0 Items[0]", "Test passed"));
        for (int x = 0; x < length; x++) after.add(command("execute unless data block " + x + " 64 0 Items[0]", "Test passed"));
        Map<String, Object> release = command("setblock 0 63 0 minecraft:air", "Changed the block");
        release.put("atSeconds", 0);
        return Json.map("id", "length-" + length + "-stack-" + stack,
            "parameters", Json.map("length", length, "stackSize", stack, "totalItems", 16, "traffic", "single-burst", "transport", "vanilla hopper"),
            "setup", setup, "before", List.of(command("execute unless data block " + length + " 64 0 Items[0]", "Test passed")),
            "during", List.of(release), "after", after,
            "cleanup", List.of(Json.map("command", "fill 0 63 0 33 65 0 minecraft:air", "expectAny", List.of("Successfully filled", "No blocks were filled")),
                command("forceload remove 0 0 32 0", "force load")));
    }

    static Map<String, Object> scene(int width, String block) {
        Map<String, Object> load = command("forceload add 0 0 32 16", "force load");
        load.put("waitMs", 1000);
        Map<String, Object> mode = Json.map("command", "gamemode spectator @a", "expectAny", List.of("Spectator", "already"), "expectEmpty", true);
        return Json.map("id", "scene-" + width * width,
            "parameters", Json.map("instances", width * width, "block", block, "camera", "8 70 -10 / yaw 0 pitch 20"),
            "setup", List.of(load, command("fill 0 64 0 16 64 16 minecraft:barrier", "Successfully filled"),
                command("fill 0 64 0 16 64 16 minecraft:air", "Successfully filled"),
                command("fill 0 64 0 " + (width - 1) + " 64 " + (width - 1) + " " + block, "Successfully filled"),
                mode, command("execute if entity @a[gamemode=spectator]", "Test passed"), command("tp @a 8 70 -10 0 20", "Teleported")),
            "before", List.of(command("execute if entity @a", "Test passed")),
            "after", List.of(command("execute if block 0 64 0 " + block, "Test passed")),
            "cleanup", List.of(command("fill 0 64 0 16 64 16 minecraft:air", "Successfully filled"),
                command("forceload remove 0 0 32 16", "force load")));
    }
}
