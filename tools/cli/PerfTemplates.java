import java.nio.file.*;
import java.util.*;

/** Reusable Minecraft command fixtures. Generating a file never edits a world. */
final class PerfTemplates {
    static Map<String, Object> run(Modaccel.Options options) throws Exception {
        String family = options.get("family", "transport");
        List<Object> cases = new ArrayList<>();
        if (family.equals("transport")) {
            for (int length : List.of(1, 8, 32)) for (int stack : List.of(1, 4, 16)) cases.add(transport(length, stack));
        } else if (family.equals("block-scene")) {
            String block = options.get("block", "minecraft:stone");
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?")) throw new IllegalArgumentException("Invalid namespaced block state");
            for (int width : List.of(1, 4, 8, 16)) cases.add(scene(width, block));
        } else throw new IllegalArgumentException("--family must be transport or block-scene");
        Map<String, Object> template = Json.map("schema", 1, "name", family,
            "requirements", family.equals("transport")
                ? "Minecraft 1.21.1 command syntax; disposable overworld; local RCON. Use duration >=20 and warmup 0. Fixed total: 16 stone items."
                : "Minecraft 1.21.1 command syntax; disposable local server; one connected client; attach profiler to that client. Camera, game mode and blocks will change.",
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
