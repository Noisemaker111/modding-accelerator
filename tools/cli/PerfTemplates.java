import java.nio.file.*;
import java.util.*;

/** Reusable Minecraft command fixtures. Generating a file never edits a world. */
final class PerfTemplates {
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
     * they return something about the mod.
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
        List<Object> probes = new ArrayList<>();
        if (Files.isRegularFile(capture.resolve("probes.suggested.json"))) {
            for (Object item : Json.array(Json.read(capture.resolve("probes.suggested.json")).get("probes"))) {
                Map<String, Object> probe = Json.object(item);
                if (!id.equals(probe.get("mod")) || probes.size() >= 6) continue;
                probes.add(Json.map("class", probe.get("class"), "method", probe.get("method"), "descriptor", probe.get("descriptor"),
                    "label", probe.get("label"), "mode", "timing"));
            }
        }
        List<Object> threads = Json.array(mod.getOrDefault("threads", List.of()));
        String busiest = threads.isEmpty() ? "" : Json.object(threads.get(0)).get("name").toString();
        Map<String, Object> metric;
        List<Object> scopes = new ArrayList<>();
        if (busiest.equals("Server thread") && !probes.isEmpty()) {
            // Server work is the same on a hidden client; an exact probe is far less noisy than a few sampled ticks.
            metric = Json.map("probe", Json.object(probes.get(0)).get("label"), "statistic", "inclusiveMsPerTick");
            scopes.add(Json.map("name", id + "Cpu", "mod", id));
        } else {
            Set<String> names = new LinkedHashSet<>();
            for (Object t : threads.subList(0, Math.min(3, threads.size()))) {
                names.add(Json.object(t).get("name").toString().replaceAll("[^A-Za-z0-9 _-]", ".").replaceAll("[0-9]+", "\\\\d+"));
            }
            metric = names.isEmpty() ? Json.map("mod", id) : Json.map("mod", id, "threads", "^(" + String.join("|", names) + ")");
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
                Json.map("name", "digest", "file", "digest.java", "thread", "control", "compare", "exact")),
            "jit", "full");
        if (!options.get("project", "").isBlank()) experiment.put("project", options.get("project", ""));
        PerfOptimize.validate(experiment);
        Path out = Performance.output(options);
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
        String details = "Wrote " + out.resolve("experiment.json") + " for " + id + ": metric " + Json.write(metric) + ", " + probes.size()
            + " timing probes, " + scopes.size() + " scopes, full JIT.\nNext: make the world once and copy it to " + out.resolve("world/perf")
            + " (or change world/worldSource), write behaviour.java and digest.java, then run perf-optimize --check-only.";
        return Json.map("ok", true, "experiment", out.resolve("experiment.json").toString(), "mod", id, "metric", metric, "details", details);
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
