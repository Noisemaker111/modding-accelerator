import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * pack-check's checks that need no game, on jars built here. The Prehistoric World 2 case: Traverse bundles
 * Biolith 1.0.1-alpha.1, whose saved climate list makes a world open once and never again. The known fault must be
 * found through the bundled copy, and must go away once a newer Biolith sits in mods/, because Forge loads the highest
 * version of a mod id wherever it is. Also: one mod id in two jars, a required mod that is missing, a block file two
 * mods both ship, the words a failed start is reported with, and an instance folder read without a manifest.
 */
public final class PackCheckTest {
    static int count;
    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("modaccel-pack");
        try {
            Path mods = Files.createDirectories(scratch.resolve("instance/mods"));
            Path biolithAlpha = jar(scratch.resolve("biolith-alpha.jar"), toml("biolith", "1.0.1-alpha.1", ""), Map.of());
            jar(mods.resolve("traverse.jar"), toml("traverse", "7.0.12", dependency("terrablender")),
                    Map.of("META-INF/jarjar/metadata.json", "{\"jars\":[{\"path\":\"META-INF/jarjar/biolith.jar\"}]}", "META-INF/jarjar/biolith.jar", biolithAlpha,
                            "assets/minecraft/blockstates/oak_log.json", "{}", "assets/minecraft/models/item/oak_log.json", "{}",
                            "assets/traverse/lang/en_us.json", "{\"block.traverse.fir_planks\":\"Fir Planks\",\"block.minecraft.banner.base.white\":\"Fully White Field\"}"));
            jar(mods.resolve("logs-a.jar"), toml("sameid", "1", dependency("traverse")), Map.of("assets/minecraft/blockstates/oak_log.json", "{}", "assets/minecraft/models/item/oak_log.json", "{}",
                    "assets/sameid/lang/en_us.json", "{\"block.sameid.fir_planks\":\"Fir planks\",\"block.sameid.white\":\"Fully White Field\"}"));
            jar(mods.resolve("logs-b.jar"), toml("sameid", "2", ""), Map.of());
            Files.writeString(scratch.resolve("instance/minecraftinstance.json"), "{\"gameVersion\":\"1.20.1\",\"baseModLoader\":{\"name\":\"forge-47.4.20\"}}");

            PackCheck check = check(harness, scratch);
            PackCheck.Pack pack = check.read(scratch.resolve("instance"));
            expect(pack.minecraft().equals("1.20.1") && pack.loader().equals("forge") && pack.loaderVersion().equals("47.4.20") && pack.mods().size() == 3 && pack.instance(),
                    "an instance folder is read from minecraftinstance.json and mods/: " + pack);
            var providers = check.scan(pack);
            expect(providers.get("biolith") != null && providers.get("biolith").get(0).bundled() && providers.get("biolith").get(0).version().equals("1.0.1-alpha.1"),
                    "a mod bundled inside another jar is found with its version: " + providers.get("biolith"));
            expect(kinds(check).contains("duplicate-mod") && text(check, "duplicate-mod").contains("sameid"), "one mod id in two jars is a problem: " + check.findings);
            expect(text(check, "missing-mod").contains("terrablender") && text(check, "missing-mod").contains("traverse.jar"), "a missing required mod names who needs it: " + check.findings);
            expect(!text(check, "missing-mod").contains("traverse require") && !text(check, "missing-mod").contains("mod id traverse"), "a required mod that is in the pack is not missing: " + check.findings);
            expect(text(check, "same-block-file").contains("assets/minecraft/blockstates/oak_log.json") && text(check, "same-block-file").contains("models/item/oak_log.json")
                    && kinds(check).stream().filter("same-block-file"::equals).count() == 1 && count(check, "same-block-file") == 1, "look files two jars ship are one note for that pair: " + check.findings);
            expect(text(check, "same-block-name").contains("fir planks") && text(check, "same-block-name").contains("traverse:fir_planks") && text(check, "same-block-name").contains("sameid:fir_planks"),
                    "one name on blocks from two mods is noted: " + check.findings);
            expect(!text(check, "same-block-name").contains("fully white field"), "a lang key with dots in its name is not read as another mod's block: " + check.findings);
            var faults = check.knownFaults(pack, providers);
            expect(faults.size() == 1 && "biolith-saved-climate-list".equals(faults.get(0).get("id")) && text(check, "known-fault").contains("1.0.1-alpha.1"),
                    "the bundled Biolith alpha matches the proven repair: " + faults);

            // Forge picks the highest version: a newer standalone Biolith replaces the bundled one, so the fault is gone.
            jar(mods.resolve("biolith-forge-1.0.1-beta.1.jar"), toml("biolith", "1.0.1-beta.1", ""), Map.of());
            PackCheck fixed = check(harness, scratch);
            PackCheck.Pack fixedPack = fixed.read(scratch.resolve("instance"));
            expect(fixed.knownFaults(fixedPack, fixed.scan(fixedPack)).isEmpty() && !kinds(fixed).contains("known-fault"), "Biolith beta in mods/ clears the fault: " + fixed.findings);
            expect(!text(fixed, "duplicate-mod").contains("biolith"), "a bundled copy beside a standalone jar is not a duplicate: " + fixed.findings);

            // The fault is for 1.20.1 Forge only.
            PackCheck other = check(harness, scratch);
            PackCheck.Pack newer = new PackCheck.Pack("p", "", "1.21.1", "forge", "52", pack.mods(), pack.overrides(), pack.input(), true);
            expect(other.knownFaults(newer, other.scan(newer)).isEmpty(), "the repair is not offered for another game version");

            // A CurseForge app instance lists each mod's project and file; their sides come from the (here pre-cached) file API. A jar added by hand stays unknown.
            String home = System.getProperty("user.home");
            try {
                System.setProperty("user.home", scratch.resolve("home").toString());
                Path app = scratch.resolve("app"), appMods = Files.createDirectories(app.resolve("mods"));
                jar(appMods.resolve("blur-forge-3.1.1.jar"), toml("blur", "3.1.1", ""), Map.of());
                jar(appMods.resolve("traverse-forge-7.0.12.jar"), toml("traverse", "7.0.12", ""), Map.of());
                jar(appMods.resolve("byhand.jar"), toml("byhand", "1", ""), Map.of());
                Files.writeString(app.resolve("minecraftinstance.json"), "{\"gameVersion\":\"1.20.1\",\"baseModLoader\":{\"name\":\"forge-47.4.20\"},\"installedAddons\":["
                        + "{\"addonID\":11,\"fileNameOnDisk\":\"blur-forge-3.1.1.jar\",\"installedFile\":{\"id\":111}},"
                        + "{\"addonID\":22,\"fileNameOnDisk\":\"traverse-forge-7.0.12.jar\",\"installedFile\":{\"id\":222}}]}");
                for (String[] file : List.of(new String[] {"11-111", "blur-forge-3.1.1.jar", "\"1.20.1\",\"Forge\",\"Client\""}, new String[] {"22-222", "traverse-forge-7.0.12.jar", "\"1.20.1\",\"Forge\",\"Client\",\"Server\""})) {
                    Path meta = Files.createDirectories(scratch.resolve("home/.modaccel/packs/curseforge/" + file[0])).resolve("file.json");
                    Files.writeString(meta, "{\"data\":{\"fileName\":\"" + file[1] + "\",\"fileLength\":1,\"gameVersions\":[" + file[2] + "]}}");
                }
                PackCheck.Pack listed = check(harness, scratch).read(app);
                Map<String, String> sides = new TreeMap<>(); for (PackCheck.Mod mod : listed.mods()) sides.put(mod.file(), mod.side());
                expect(sides.equals(Map.of("blur-forge-3.1.1.jar", "client", "traverse-forge-7.0.12.jar", "both", "byhand.jar", "unknown")), "a CurseForge instance's mods get their sides: " + sides);
            } finally { System.setProperty("user.home", home); }

            // A failed start is told in the game's own words.
            String log = String.join("\n", "[main/INFO] Preparing level \"world\"", "[main/ERROR] Failed to load datapacks, can't proceed with server load",
                    "java.lang.IllegalStateException: Failed to load datapacks", "Caused by: java.lang.IllegalStateException: Overworld settings missing", "\tat x.y(Z.java:1)");
            String failure = PackCheck.failure(log);
            expect(failure.contains("Overworld settings missing") && failure.contains("Failed to load datapacks") && !failure.contains("at x.y"), "a failed start keeps the cause: " + failure);
            // Client-only mods stopping a server, in Forge 47's own lines from this pack: Blur's cause is nested; a mod failing for another reason is kept.
            String clientOnly = String.join("\n",
                    "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: blur, class com.tterrag.blur.Blur",
                    "java.lang.ExceptionInInitializerError: null", "\tat x.y(Z.java:1)",
                    "Caused by: java.lang.RuntimeException: Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER",
                    "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to register automatic subscribers. ModID: buildpaste, class com.mistrx.buildpaste.BuildPasteMod",
                    "java.lang.RuntimeException: Attempted to load class net/minecraft/client/renderer/LevelRenderer for invalid dist DEDICATED_SERVER",
                    "[17:06:44] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: brokenmod, class a.B",
                    "java.lang.NullPointerException: config was null",
                    "[17:06:44] [main/INFO] [STDERR/]: [Rhino] Failed to get declared methods: Attempted to load class net/minecraft/client/gui/GuiGraphics for invalid dist DEDICATED_SERVER",
                    "[17:06:56] [main/FATAL] [ne.mi.fm.ModLoader/LOADING]: Failed to complete lifecycle event CONSTRUCT, 3 errors found", "java.lang.Exception: Mod Loading has failed");
            expect(PackCheck.clientOnly(clientOnly).equals(new LinkedHashSet<>(List.of("blur", "buildpaste"))), "client-only mods are read from Forge's failure blocks, and only those: " + PackCheck.clientOnly(clientOnly));
            expect(PackCheck.clientOnly(clientOnly.replace("Mod Loading has failed", "Done")).isEmpty(), "a server that started has no client-only mods to remove");
            // A result that holds a file path (the probe's output file) is written as text; it once overflowed the stack.
            expect(Json.write(Json.map("probeFile", scratch.resolve("probe-place.json"))).contains("probe-place.json"), "a path in a result is written as text");
            expect(PackCheck.doneSeconds("[Server thread/INFO] Done (41.250s)! For help, type \"help\"") == 41.25, "the start time is read");
            System.out.println(count + " pack-check checks passed: instance folder, bundled mods, duplicate ids, missing mods, shared look files, shared block names, CurseForge instance sides, known fault and its repair, client-only mods, failed-start words");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    static PackCheck check(Path harness, Path scratch) {
        return new PackCheck(harness, new Modaccel.Options(new String[] {"pack-check"}), new Modaccel.Report(scratch, "pack-check"));
    }
    static String toml(String id, String version, String dependencies) {
        // Forge's template comments its section lines; the parser must read past that.
        return "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]] #mandatory\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n" + dependencies;
    }
    static String dependency(String id) { return "[[dependencies.x]] #optional\nmodId=\"" + id + "\"\nmandatory=true\nversionRange=\"[1,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"; }
    static Path jar(Path target, String toml, Map<String, Object> entries) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new ZipEntry("META-INF/mods.toml")); out.write(toml.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            for (var entry : new TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue() instanceof Path p ? Files.readAllBytes(p) : String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return target;
    }
    static long count(PackCheck check, String kind) { return check.findings.stream().filter(f -> kind.equals(Json.object(f).get("kind"))).count(); }
    static Set<String> kinds(PackCheck check) { Set<String> kinds = new HashSet<>(); for (Object f : check.findings) kinds.add(String.valueOf(Json.object(f).get("kind"))); return kinds; }
    static String text(PackCheck check, String kind) {
        StringBuilder out = new StringBuilder();
        for (Object f : check.findings) if (kind.equals(Json.object(f).get("kind"))) out.append(Json.object(f).get("summary")).append(' ').append(Json.object(f).get("jars")).append('\n');
        return out.toString();
    }
    static void expect(boolean ok, String what) { if (!ok) throw new AssertionError(what); count++; }
}
